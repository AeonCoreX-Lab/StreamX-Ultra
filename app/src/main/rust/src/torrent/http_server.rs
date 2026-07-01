//! Torrent HTTP streaming server — uses librqbit's own Api::api_stream.
//!
//! PREVIOUS APPROACH (broken):
//!   Read from disk with tokio::fs::File → served zeros from sparse
//!   pre-allocated file → MPV parsed zeros as corrupt moov atom →
//!   aborted file-open → "00:00 / 00:00" permanently.
//!
//! THIS APPROACH:
//!   Api::new(session).api_stream(torrent_id, file_id) returns a FileStream
//!   that is librqbit's own AsyncRead implementation.  It:
//!
//!   1. Returns Poll::Pending when a piece is not yet downloaded — no zeros,
//!      no corrupt data, MPV's TCP connection simply waits.
//!   2. Registers the current read position with TorrentStreams so the piece
//!      picker marks those pieces as top-priority for download.
//!   3. Wakes the reader via wake_streams_on_piece_completed() when the piece
//!      arrives — zero CPU overhead while waiting.
//!   4. Supports AsyncSeek — seeking to the moov atom at end of an MP4 shifts
//!      the priority window so those tail pieces download immediately.
//!
//!   Result: MPV always receives correct bytes, duration is detected for all
//!   formats, "00:00" never appears.

use std::{net::SocketAddr, sync::Arc};

use bytes::Bytes;
use futures::StreamExt;          // needed for .map() on ReaderStream → StreamBody
use hyper::http::{Method, StatusCode};
use hyper::http::header::{ACCEPT_RANGES, CONTENT_LENGTH, CONTENT_RANGE, CONTENT_TYPE, RANGE};
use http_body_util::{BodyExt, Full, StreamBody, combinators::BoxBody};
use hyper::{Request, Response, body::Frame};
use parking_lot::RwLock;
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::io::ReaderStream;
use log::{info, warn};

use librqbit::api::{Api, TorrentIdOrHash};
use crate::torrent::session::TorrentSession;
use tokio::time::Duration;

type RespBody = BoxBody<Bytes, std::io::Error>;

fn full_body(b: Bytes) -> RespBody {
    Full::new(b)
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::Other))
        .boxed()
}

fn resp503(msg: &'static str) -> Response<RespBody> {
    Response::builder()
        .status(StatusCode::SERVICE_UNAVAILABLE)
        .header("Retry-After", "2")
        .header(CONTENT_TYPE, "text/plain")
        .body(full_body(Bytes::from_static(msg.as_bytes())))
        .unwrap()
}

fn detect_mime(path: &str) -> &'static str {
    if      path.ends_with(".mkv")  { "video/x-matroska" }
    else if path.ends_with(".mp4")
         || path.ends_with(".m4v")  { "video/mp4" }
    else if path.ends_with(".avi")  { "video/x-msvideo" }
    else if path.ends_with(".webm") { "video/webm" }
    else if path.ends_with(".ts")
         || path.ends_with(".m2ts") { "video/mp2t" }
    else if path.ends_with(".mov")  { "video/quicktime" }
    else                            { "application/octet-stream" }
}

pub async fn handle(
    req:     Request<hyper::body::Incoming>,
    session: Arc<RwLock<Option<Arc<TorrentSession>>>>,
) -> Result<Response<RespBody>, hyper::Error> {

    // ── /debug ────────────────────────────────────────────────────────────
    // curl http://127.0.0.1:8088/debug — dumps internal session state
    // immediately (no 10 s wait). Use this to see exactly which field is
    // unset when /stream returns 503, without needing logcat.
    if req.uri().path() == "/debug" {
        let dump = {
            let g = session.read();
            match g.as_ref() {
                Some(s) => s.debug_dump(),
                None    => "session=NONE (TorrentSession not created yet — \
                            engine.start() was never called or already stopped)\n".to_string(),
            }
        };
        return Ok(Response::builder()
            .status(StatusCode::OK)
            .header(CONTENT_TYPE, "text/plain")
            .body(full_body(Bytes::from(dump)))
            .unwrap());
    }

    if req.uri().path() != "/stream" {
        return Ok(Response::builder()
            .status(StatusCode::NOT_FOUND)
            .body(full_body(Bytes::from_static(b"not found")))
            .unwrap());
    }

    // ── Wait for api_stream_info (rq_session + torrent_id + file_id) ────────
    // Set after metadata arrives and the video file is identified.
    // By the time Kotlin triggers MPV (STATE_READY), this is always set.
    // The 10 s wait is a safety net only.
    let info = {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        loop {
            let v = {
                let g = session.read();
                g.as_ref().and_then(|s| s.api_stream_info())
            };
            if let Some(v) = v { break Some(v); }
            if std::time::Instant::now() >= deadline { break None; }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    };

    let (rq_session, torrent_id, file_id) = match info {
        Some(v) => v,
        None => {
            warn!("[http] api_stream_info not available after 10 s");
            return Ok(resp503("Not ready"));
        }
    };

    // ── MIME + stored file size (for HEAD) ────────────────────────────────
    let (video_path, stored_size) = {
        use std::sync::atomic::Ordering;
        let g = session.read();
        let (vp, sz) = g.as_ref()
            .map(|s| (s.status().video_path, s.video_file_size.load(Ordering::Relaxed)))
            .unwrap_or_default();
        (vp, sz)
    };
    let mime = detect_mime(&video_path);

    // ── HEAD ─────────────────────────────────────────────────────────────────
    if *req.method() == Method::HEAD {
        return Ok(Response::builder()
            .status(StatusCode::OK)
            .header(CONTENT_LENGTH, stored_size)
            .header(CONTENT_TYPE, mime)
            .header(ACCEPT_RANGES, "bytes")
            .body(full_body(Bytes::new()))
            .unwrap());
    }
    if *req.method() != Method::GET {
        return Ok(Response::builder()
            .status(StatusCode::METHOD_NOT_ALLOWED)
            .body(full_body(Bytes::new()))
            .unwrap());
    }

    // ── FileStream via librqbit's own Api::api_stream ─────────────────────
    //
    // This is exactly what rqbit's own desktop app uses for its streaming
    // endpoint (/torrents/{id}/stream/{file_id}).  It correctly:
    //   • Returns Poll::Pending instead of zeros for not-yet-downloaded pieces
    //   • Prioritizes pieces at the current read/seek position
    //   • Wakes automatically when a piece is written to disk
    //
    let api = Api::new(rq_session, None);
    let mut file_stream = match api.api_stream(
        TorrentIdOrHash::Id(torrent_id),
        file_id,
    ).await {
        Ok(s)  => s,
        Err(e) => {
            warn!("[http] api_stream error: {e:#}");
            return Ok(resp503("Stream not available"));
        }
    };

    let file_size = file_stream.len();

    // ── Range header ─────────────────────────────────────────────────────────
    let range = req.headers().get(RANGE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("bytes="))
        .and_then(|v| v.split_once('-'))
        .and_then(|(s, e)| {
            let start = s.parse::<u64>().ok()?;
            let end   = if e.is_empty() {
                file_size.saturating_sub(1)
            } else {
                e.parse::<u64>().ok()?
            };
            Some((start, end))
        });

    let (start, end) = range.unwrap_or((0, file_size.saturating_sub(1)));
    let end          = end.min(file_size.saturating_sub(1));
    let length       = end.saturating_sub(start) + 1;
    let is_range     = range.is_some();

    info!("[http] {} bytes={start}-{end}/{file_size} tid={torrent_id} fid={file_id}",
          if is_range { "RANGE" } else { "GET" });

    // ── Seek ─────────────────────────────────────────────────────────────────
    // AsyncSeek shifts FileStream's priority window so pieces at `start`
    // are downloaded first.  For moov-at-end MP4: seeking to moov position
    // makes those pieces top-priority → they download in seconds.
    if start > 0 {
        if let Err(e) = file_stream.seek(std::io::SeekFrom::Start(start)).await {
            warn!("[http] seek to {start} failed: {e}");
            return Ok(resp503("Seek failed"));
        }
    }

    // ── Stream body ───────────────────────────────────────────────────────────
    // poll_read() on FileStream returns Poll::Pending when a piece isn't ready.
    // Tokio suspends the task; wake_streams_on_piece_completed() resumes it.
    // MPV's TCP connection stays open and data flows in as pieces download.
    let limited = file_stream.take(length);
    let reader_stream = ReaderStream::with_capacity(limited, 256 * 1024);
    let body_stream   = reader_stream.map(|r| r.map(Frame::data));
    let body          = BodyExt::boxed(StreamBody::new(body_stream));

    let mut builder = Response::builder()
        .status(if is_range { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK })
        .header(CONTENT_TYPE,  mime)
        .header(CONTENT_LENGTH, length)
        .header(ACCEPT_RANGES, "bytes");

    if is_range {
        builder = builder.header(
            CONTENT_RANGE,
            format!("bytes {start}-{end}/{file_size}"),
        );
    }

    Ok(builder.body(body).unwrap())
}

// ── Server ────────────────────────────────────────────────────────────────────

pub struct TorrentHttpServer {
    port:    u16,
    session: Arc<RwLock<Option<Arc<TorrentSession>>>>,
}

impl TorrentHttpServer {
    pub fn new(port: u16) -> Self {
        Self { port, session: Arc::new(RwLock::new(None)) }
    }
    pub fn set_session(&self, s: Arc<TorrentSession>) { *self.session.write() = Some(s); }
    pub fn clear_session(&self)                       { *self.session.write() = None; }
    pub fn local_url(&self) -> String { format!("http://127.0.0.1:{}/stream", self.port) }

    pub fn start(&self, rt: Arc<tokio::runtime::Runtime>) {
        let port    = self.port;
        let session = self.session.clone();
        rt.spawn(async move {
            let addr: SocketAddr = ([127, 0, 0, 1], port).into();
            let listener = match tokio::net::TcpListener::bind(addr).await {
                Ok(l)  => { info!("[http] listening on {addr}"); l }
                Err(e) => { warn!("[http] bind failed: {e}"); return; }
            };
            loop {
                let (stream, _) = match listener.accept().await {
                    Ok(v)  => v,
                    Err(e) => { warn!("[http] accept: {e}"); continue; }
                };
                let session = session.clone();
                tokio::spawn(async move {
                    use hyper_util::rt::TokioIo;
                    let io  = TokioIo::new(stream);
                    let svc = hyper::service::service_fn(move |req| {
                        let session = session.clone();
                        async move { handle(req, session).await }
                    });
                    if let Err(e) = hyper::server::conn::http1::Builder::new()
                        .serve_connection(io, svc).await
                    {
                        if !e.to_string().contains("connection reset") {
                            warn!("[http] conn error: {e}");
                        }
                    }
                });
            }
        });
    }
}
