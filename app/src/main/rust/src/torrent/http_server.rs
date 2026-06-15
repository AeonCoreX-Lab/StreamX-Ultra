//! HTTP streaming server for StreamX Ultra.
//!
//! ARCHITECTURE — why FileStream instead of disk I/O
//! ─────────────────────────────────────────────────
//! Previous implementation read the video file directly from disk with
//! tokio::fs::File.  This caused two fatal problems:
//!
//!   1. librqbit preallocates the full file as a sparse file.
//!      Undownloaded pieces read as zeros.  MPV parses zeros as a corrupt
//!      moov atom → aborts file-open → time-pos AND duration both stuck at 0
//!      → "00:00 / 00:00" on screen.
//!
//!   2. Progress guards (503 / 416 / wait_for_bytes) were bandaids that
//!      couldn't fix piece prioritization.  The MP4 moov atom is at the
//!      END of the file; with sequential download it arrives last.
//!
//! THE FIX — librqbit's built-in FileStream
//! ─────────────────────────────────────────
//! `ManagedTorrent::stream(file_id)` returns a `FileStream` that:
//!
//!   • Implements AsyncRead + AsyncSeek.
//!   • poll_read() checks the chunk tracker; if the piece is NOT downloaded
//!     yet it registers a Waker and returns Poll::Pending.  The caller
//!     (tokio) suspends with zero CPU overhead.
//!   • When the piece is written to disk, `wake_streams_on_piece_completed`
//!     fires the Waker and the read resumes automatically.
//!   • The current read position is fed to `iter_next_pieces()` which is
//!     called by `acquire_piece()` in the peer download loop → the pieces
//!     being actively read are prioritised above all others.
//!   • Seeking (e.g. MPV seeking to the moov atom) shifts the priority
//!     window → the new position's pieces are downloaded first.
//!
//! Result:
//!   MP4 moov at end  → MPV seeks there → FileStream blocks → librqbit
//!                       prioritises those pieces → downloaded → unblocked
//!                       → MPV gets moov → correct duration shown          ✓
//!   MKV / AVI / WebM → header at start → always downloaded first          ✓
//!   User seeks ahead  → FileStream seeks → piece priority shifts           ✓
//!   No zeros, no 503, no 416, no wait_for_bytes needed.

use std::sync::Arc;

use bytes::Bytes;
use hyper::http::{Method, StatusCode};
use hyper::http::header::{ACCEPT_RANGES, CONTENT_LENGTH, CONTENT_RANGE, CONTENT_TYPE, RANGE};
use http_body_util::{BodyExt, Full, StreamBody, combinators::BoxBody};
use hyper::{Request, Response, body::Frame};
use parking_lot::RwLock;
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::io::ReaderStream;
use futures_util::StreamExt;
use log::{info, warn};

use crate::torrent::session::TorrentSession;
use tokio::time::Duration;

type RespBody = BoxBody<Bytes, std::io::Error>;

fn full_body(b: Bytes) -> RespBody {
    Full::new(b)
        .map_err(|_: std::convert::Infallible| {
            std::io::Error::new(std::io::ErrorKind::Other, "infallible")
        })
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
    if path.ends_with(".mkv")                    { "video/x-matroska" }
    else if path.ends_with(".mp4") ||
            path.ends_with(".m4v")               { "video/mp4" }
    else if path.ends_with(".avi")               { "video/x-msvideo" }
    else if path.ends_with(".webm")              { "video/webm" }
    else if path.ends_with(".ts") ||
            path.ends_with(".m2ts")              { "video/mp2t" }
    else if path.ends_with(".mov")               { "video/quicktime" }
    else                                         { "application/octet-stream" }
}

/// Main HTTP handler — all requests to the embedded server pass through here.
pub async fn handle(
    req:     Request<hyper::body::Incoming>,
    session: Arc<RwLock<Option<Arc<TorrentSession>>>>,
) -> Result<Response<RespBody>, hyper::Error> {

    // Only serve /stream
    if req.uri().path() != "/stream" {
        return Ok(Response::builder()
            .status(StatusCode::NOT_FOUND)
            .body(full_body(Bytes::from_static(b"not found")))
            .unwrap());
    }

    // ── Wait for stream_info (handle + file_id) ───────────────────────────
    //
    // stream_info() returns Some once:
    //   1. librqbit has received torrent metadata, AND
    //   2. we have identified the largest file as the video file.
    //
    // In practice this is set well before the Kotlin side triggers MPV
    // (Kotlin waits for STATE_READY which requires ≥3% download).
    // The 10 s timeout is a safety net for edge cases.
    //
    let stream_info = {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        loop {
            let info = {
                let g = session.read();
                g.as_ref().and_then(|s| s.stream_info())
            };
            if let Some(info) = info { break Some(info); }
            if std::time::Instant::now() >= deadline { break None; }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    };

    let (handle, file_id) = match stream_info {
        Some(v) => v,
        None => {
            warn!("[http_server] stream_info not available after 10 s");
            return Ok(resp503("Torrent not ready"));
        }
    };

    // ── MIME type and file size ───────────────────────────────────────────
    let video_path   = {
        let g = session.read();
        g.as_ref().map(|s| s.status().video_path).unwrap_or_default()
    };
    let mime         = detect_mime(&video_path);
    let stored_size  = {
        use std::sync::atomic::Ordering;
        let g = session.read();
        g.as_ref().map(|s| s.video_file_size.load(Ordering::Relaxed)).unwrap_or(0)
    };

    // ── HEAD ─────────────────────────────────────────────────────────────
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

    // ── Create FileStream ─────────────────────────────────────────────────
    //
    // This is the core fix.  FileStream:
    //   • Returns Poll::Pending (not zeros) when a piece is unavailable.
    //   • Prioritises pieces at the current read position.
    //   • Wakes automatically when the piece is written to disk.
    //   • file_stream.len() is the correct total file size from metadata.
    //
    let mut file_stream = match handle.clone().stream(file_id).await {
        Ok(s)  => s,
        Err(e) => {
            warn!("[http_server] FileStream error: {e:#}");
            return Ok(resp503("Stream not available"));
        }
    };

    let file_size = file_stream.len();

    // ── Parse Range header ───────────────────────────────────────────────
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

    info!("[http_server] {} bytes={}-{}/{} file_id={} (FileStream)",
          if is_range { "RANGE" } else { "GET" }, start, end, file_size, file_id);

    // ── Seek ──────────────────────────────────────────────────────────────
    //
    // AsyncSeek on FileStream moves the priority window so librqbit starts
    // downloading the pieces at `start` with highest priority.
    // For moov atom seeks (end of MP4): those pieces become top priority →
    // downloaded soon → FileStream unblocks → MPV gets moov → duration shown.
    //
    if start > 0 {
        if let Err(e) = file_stream.seek(std::io::SeekFrom::Start(start)).await {
            warn!("[http_server] seek to {start} failed: {e}");
            return Ok(resp503("Seek failed"));
        }
    }

    // ── Stream body ───────────────────────────────────────────────────────
    //
    // tokio::io::AsyncReadExt::take() limits reads to exactly `length` bytes.
    // ReaderStream converts the AsyncRead into a Stream<Item=Bytes>.
    // When FileStream::poll_read() returns Poll::Pending the whole chain
    // suspends; when the Waker fires it resumes transparently.
    //
    let limited       = file_stream.take(length);
    let reader_stream = ReaderStream::with_capacity(limited, 256 * 1024);
    let body_stream   = reader_stream.map(|r| {
        r.map(Frame::data)
    });
    let body          = BodyExt::boxed(StreamBody::new(body_stream));

    let mut builder = Response::builder()
        .status(if is_range { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK })
        .header(CONTENT_TYPE,  mime)
        .header(CONTENT_LENGTH, length)
        .header(ACCEPT_RANGES, "bytes");

    if is_range {
        builder = builder.header(
            CONTENT_RANGE,
            format!("bytes {}-{}/{}", start, end, file_size),
        );
    }

    Ok(builder.body(body).unwrap())
}

// ── HTTP server (unchanged from previous version) ────────────────────────────

use std::net::SocketAddr;
use hyper_util::rt::TokioIo;
use tokio::net::TcpListener;

pub struct TorrentHttpServer {
    port:    u16,
    session: Arc<RwLock<Option<Arc<TorrentSession>>>>,
}

impl TorrentHttpServer {
    pub fn new(port: u16) -> Self {
        Self {
            port,
            session: Arc::new(RwLock::new(None)),
        }
    }

    pub fn set_session(&self, session: Arc<TorrentSession>) {
        *self.session.write() = Some(session);
    }

    pub fn clear_session(&self) {
        *self.session.write() = None;
    }

    pub fn local_url(&self) -> String {
        format!("http://127.0.0.1:{}/stream", self.port)
    }

    pub fn start(&self, rt: Arc<tokio::runtime::Runtime>) {
        let port    = self.port;
        let session = self.session.clone();

        rt.spawn(async move {
            let addr: SocketAddr = ([127, 0, 0, 1], port).into();
            let listener = match TcpListener::bind(addr).await {
                Ok(l)  => { info!("[http_server] listening on {addr}"); l }
                Err(e) => { warn!("[http_server] bind failed: {e}"); return; }
            };

            loop {
                let (stream, _) = match listener.accept().await {
                    Ok(v)  => v,
                    Err(e) => { warn!("[http_server] accept: {e}"); continue; }
                };
                let session = session.clone();
                tokio::spawn(async move {
                    let io = TokioIo::new(stream);
                    let svc = hyper::service::service_fn(move |req| {
                        let session = session.clone();
                        async move { handle(req, session).await }
                    });
                    if let Err(e) = hyper::server::conn::http1::Builder::new()
                        .serve_connection(io, svc)
                        .await
                    {
                        // "connection reset" is normal (MPV closes connections)
                        if !e.to_string().contains("connection reset") {
                            warn!("[http_server] connection error: {e}");
                        }
                    }
                });
            }
        });
    }
}
