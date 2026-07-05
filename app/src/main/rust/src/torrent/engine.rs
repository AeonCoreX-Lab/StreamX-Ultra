// app/src/main/rust/src/torrent/engine.rs

use std::sync::Arc;
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use tokio::runtime::Runtime;
use log::info;

use super::session::{TorrentSession, TorrentStatus};
use super::http_server::TorrentHttpServer;
use super::LOCAL_HTTP_PORT;

// ── TorrentEngine ─────────────────────────────────────────────────────────────
pub struct TorrentEngine {
    pub rt:      Arc<Runtime>,
    session:     Mutex<Option<Arc<TorrentSession>>>,
    http_server: Mutex<Option<TorrentHttpServer>>,
}

impl TorrentEngine {
    fn new() -> Self {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(3)
            .thread_name("sx-torrent")
            .enable_all()
            .build()
            .expect("Tokio runtime failed");

        Self {
            rt:          Arc::new(rt),
            session:     Mutex::new(None),
            http_server: Mutex::new(None),
        }
    }

    // ── Start ─────────────────────────────────────────────────────────────────
    pub fn start(&self, magnet: &str, save_dir: &str) {
        self.stop_session();

        let magnet   = magnet.to_string();
        let save_dir = save_dir.to_string();

        let session  = Arc::new(TorrentSession::new());
        *self.session.lock() = Some(session.clone());

        // Start HTTP server (or reuse + reattach)
        {
            let mut hs = self.http_server.lock();
            if hs.is_none() {
                let srv = TorrentHttpServer::new(LOCAL_HTTP_PORT);
                srv.start(self.rt.clone());
                *hs = Some(srv);
            }
            if let Some(srv) = hs.as_ref() {
                srv.set_session(session.clone());
            }
        }

        // Drive the session in background
        let rt = self.rt.clone();
        rt.spawn(async move {
            session.run(magnet, save_dir).await;
        });

        info!("TorrentEngine::start — session started");
    }

    // ── Stop ──────────────────────────────────────────────────────────────────
    pub fn stop(&self) {
        self.stop_session();
    }

    fn stop_session(&self) {
        if let Some(sess) = self.session.lock().take() {
            let rt = self.rt.clone();
            rt.spawn(async move { sess.stop().await });
        }
    }

    // ── Status (called from Kotlin every 250 ms) ──────────────────────────────
    pub fn status(&self) -> TorrentStatus {
        self.session.lock()
            .as_ref()
            .map(|s| s.status())
            .unwrap_or_default()
    }

    // ── Diagnostics export (Tier 3 #16) ────────────────────────────────────
    // Exposes TorrentSession::debug_dump() directly via JNI, bypassing the
    // HTTP /debug route entirely — that route is compiled out of release
    // builds (#[cfg(debug_assertions)] in http_server.rs) for security, but
    // this same diagnostic text is still valuable for USER-INITIATED bug
    // reports (a "Copy Diagnostics" button in Settings), so it's exposed
    // here as a plain method call with no HTTP/network surface at all.
    pub fn debug_dump(&self) -> String {
        self.session.lock()
            .as_ref()
            .map(|s| s.debug_dump())
            .unwrap_or_else(|| "session=NONE (no torrent active)\n".to_string())
    }

    // ── Playhead update (from Kotlin MPV observer) ────────────────────────────
    pub fn set_playhead(&self, secs: f64) {
        if let Some(sess) = self.session.lock().as_ref() {
            sess.set_playhead(secs);
        }
    }

    // ── Local HTTP URL for MPV / ExoPlayer ────────────────────────────────────
    pub fn local_url(&self) -> String {
        format!("http://127.0.0.1:{}/stream", LOCAL_HTTP_PORT)
    }

    // ── Clear download cache ──────────────────────────────────────────────────
    pub fn clear_cache(save_dir: &str) {
        if let Err(e) = std::fs::remove_dir_all(save_dir) {
            log::warn!("clear_cache: {}", e);
        }
    }
}

// ── Global singleton ──────────────────────────────────────────────────────────
static ENGINE: OnceCell<TorrentEngine> = OnceCell::new();

pub struct TorrentEngineHandle;

impl TorrentEngineHandle {
    pub fn get() -> &'static TorrentEngine {
        ENGINE.get_or_init(TorrentEngine::new)
    }
}
