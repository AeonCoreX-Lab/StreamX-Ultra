// app/src/main/rust/src/torrent/http_server.rs
// ═══════════════════════════════════════════════════════════════════════
//  TorrentHttpServer — replaces TorrentStreamServer.kt (Ktor CIO)
//
//  Endpoints (identical to Ktor version):
//    GET /stream              → torrent video with Range support (MPV)
//    GET /sub/{filename}      → subtitle files from same directory
//    HEAD /stream             → Content-Length + Accept-Ranges
//
//  Port: 8088 (same as before — MPV/ExoPlayer URL unchanged)
// ═══════════════════════════════════════════════════════════════════════

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncSeekExt, SeekFrom};
use tokio::time::{timeout, Duration};
use parking_lot::RwLock;
use bytes::Bytes;
use hyper::service::service_fn;
use hyper::{Request, Response, StatusCode, Method};
use hyper::header::*;
use http_body_util::Full;
use log::{info, warn};

use super::session::{TorrentSession, STATE_READY};

// ── TorrentHttpServer ─────────────────────────────────────────────────────────
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

    pub fn set_session(&self, sess: Arc<TorrentSession>) {
        *self.session.write() = Some(sess);
    }

    pub fn start(&self, rt: Arc<Runtime>) {
        let port    = self.port;
        let session = self.session.clone();

        rt.spawn(async move {
            let addr: SocketAddr = ([127, 0, 0, 1], port).into();
            let listener = match tokio::net::TcpListener::bind(addr).await {
                Ok(l)  => { info!("HTTP server on {}", addr); l }
                Err(e) => { warn!("HTTP bind error: {}", e); return; }
            };

            loop {
                if let Ok((stream, _)) = listener.accept().await {
                    let sess = session.clone();
                    let io   = hyper_util::rt::TokioIo::new(stream);
                    tokio::spawn(async move {
                        let svc = service_fn(move |req| {
                            let s = sess.clone();
                            async move { handle(req, s).await }
                        });
                        let _ = hyper::server::conn::http1::Builder::new()
                            .serve_connection(io, svc)
                            .await;
                    });
                }
            }
        });
    }
}

// ── Request handler ───────────────────────────────────────────────────────────
async fn handle(
    req:     Request<hyper::body::Incoming>,
    session: Arc<RwLock<Option<Arc<TorrentSession>>>>,
) -> Result<Response<Full<Bytes>>, hyper::Error> {
    let path = req.uri().path();

    // ── /stream ───────────────────────────────────────────────────────────────
    if path == "/stream" {
        // Extract data from session while holding the lock, then drop the guard
        // before any await points to avoid Send issues with parking_lot guards.
        //
        // FIX (Warning): `state` was extracted but never read after this point.
        // Renamed to `_state` to suppress the unused-variable warning.
        // The readiness gate below re-reads state inside the timeout loop.
        let (video_path, _state) = {
            let g = session.read();
            match g.as_ref() {
                Some(s) => {
                    let status = s.status();
                    (status.video_path, status.state)
                }
                None => (String::new(), 0),
            }
        }; // guard dropped here before any await

        if video_path.is_empty() {
            return Ok(resp503("Torrent metadata not ready yet"));
        }

        let vpath = std::path::Path::new(&video_path);
        if !vpath.exists() {
            return Ok(resp503("Video file not created yet"));
        }

        // Wait for READY state before serving (same as Kotlin Buffering check).
        // Re-acquire lock on every iteration — never hold across an await point.
        let _ = timeout(Duration::from_secs(15), async {
            loop {
                let current_state = {
                    let g = session.read();
                    g.as_ref().map(|s| s.status().state).unwrap_or(0)
                }; // lock dropped before await
                if current_state == STATE_READY || current_state == 4 { break; }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }).await;

        let file_size = match tokio::fs::metadata(vpath).await {
            Ok(m)  => m.len(),
            Err(_) => return Ok(resp503("File metadata error")),
        };

        let mime  = mime_for(vpath);
        let range = req.headers().get(RANGE)
            .and_then(|v| v.to_str().ok())
            .and_then(|s| parse_range(s, file_size));

        return match req.method() {
            &Method::HEAD => Ok(Response::builder()
                .status(StatusCode::OK)
                .header(CONTENT_LENGTH,   file_size)
                .header(CONTENT_TYPE,     mime)
                .header(ACCEPT_RANGES,    "bytes")
                .body(Full::new(Bytes::new()))
                .unwrap()),

            &Method::GET => {
                let (start, end) = range.unwrap_or((0, file_size - 1));
                let length       = end - start + 1;
                let is_range     = range.is_some();

                match read_bytes(vpath, start, length).await {
                    Ok(data) => {
                        let status = if is_range { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK };
                        let mut b = Response::builder()
                            .status(status)
                            .header(CONTENT_TYPE,   mime)
                            .header(CONTENT_LENGTH, length)
                            .header(ACCEPT_RANGES,  "bytes")
                            .header(CACHE_CONTROL,  "no-cache");
                        if is_range {
                            b = b.header(CONTENT_RANGE, format!("bytes {}-{}/{}", start, end, file_size));
                        }
                        Ok(b.body(Full::new(data)).unwrap())
                    }
                    Err(_) => Ok(resp500()),
                }
            }
            _ => Ok(resp404()),
        };
    }

    // ── /sub/{filename} — subtitle files ──────────────────────────────────────
    if path.starts_with("/sub/") {
        let filename = &path[5..];

        // Extract video path while holding lock, then drop guard
        let video_path = {
            let g = session.read();
            g.as_ref().map(|s| s.status().video_path).unwrap_or_default()
        };

        if video_path.is_empty() { return Ok(resp404()); }

        let sub_path = PathBuf::from(&video_path)
            .parent()
            .map(|p| p.join(filename))
            .unwrap_or_default();

        if !sub_path.exists() { return Ok(resp404()); }

        let data = match read_bytes(&sub_path, 0, sub_path.metadata().map(|m| m.len()).unwrap_or(0)).await {
            Ok(d) => d,
            Err(_) => return Ok(resp404()),
        };

        let mime = match sub_path.extension().and_then(|e| e.to_str()) {
            Some("srt") => "application/x-subrip",
            Some("ass") | Some("ssa") => "text/x-ssa",
            Some("vtt") => "text/vtt",
            _ => "text/plain",
        };

        return Ok(Response::builder()
            .status(StatusCode::OK)
            .header(CONTENT_TYPE,   mime)
            .header(CACHE_CONTROL,  "no-cache")
            .body(Full::new(data))
            .unwrap());
    }

    Ok(resp404())
}

// ── File read ─────────────────────────────────────────────────────────────────
async fn read_bytes(path: &std::path::Path, start: u64, length: u64) -> std::io::Result<Bytes> {
    let mut file = File::open(path).await?;
    file.seek(SeekFrom::Start(start)).await?;
    let to_read = length.min(4 * 1024 * 1024) as usize;
    let mut buf = vec![0u8; to_read];
    let n = file.read(&mut buf).await?;
    buf.truncate(n);
    Ok(Bytes::from(buf))
}

// ── Helpers ───────────────────────────────────────────────────────────────────
fn parse_range(header: &str, size: u64) -> Option<(u64, u64)> {
    let s      = header.strip_prefix("bytes=")?;
    let (a, b) = s.split_once('-')?;
    let start  = a.parse::<u64>().ok()?;
    let end    = if b.is_empty() { size - 1 } else { b.parse::<u64>().ok()?.min(size - 1) };
    Some((start, end))
}

fn mime_for(p: &std::path::Path) -> &'static str {
    match p.extension().and_then(|e| e.to_str()) {
        Some("mkv")  => "video/x-matroska",
        Some("mp4")  => "video/mp4",
        Some("avi")  => "video/x-msvideo",
        Some("webm") => "video/webm",
        Some("mov")  => "video/quicktime",
        Some("ts")   => "video/mp2t",
        _            => "application/octet-stream",
    }
}

fn resp404() -> Response<Full<Bytes>> {
    Response::builder().status(404).body(Full::new(Bytes::from("Not Found"))).unwrap()
}
fn resp500() -> Response<Full<Bytes>> {
    Response::builder().status(500).body(Full::new(Bytes::new())).unwrap()
}
fn resp503(msg: &str) -> Response<Full<Bytes>> {
    Response::builder().status(503).body(Full::new(Bytes::from(msg.to_owned()))).unwrap()
}
