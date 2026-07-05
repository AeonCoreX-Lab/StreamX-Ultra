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
    // FIX (storage race): this used to fire-and-forget the async teardown
    // (rt.spawn(async move { sess.stop().await }) and return immediately.
    // Kotlin's TorrentEngine.stop() calls this and then IMMEDIATELY calls
    // clearCache() → remove_dir_all() on the same directory, synchronously,
    // on the calling (JNI) thread. If the spawned sess.stop().await hadn't
    // actually finished dropping its Arc<ManagedTorrent>/session handles
    // yet, remove_dir_all() could hit a still-open file handle and fail
    // (silently — only warn!-logged), leaving the just-played movie's data
    // on disk. Over repeated plays this defeats the entire point of
    // clearing on dispose and slowly re-creates the storage-bloat problem
    // even with the new per-movie subfolder isolation.
    //
    // Fix: block the calling thread on rt.block_on(...) until sess.stop()
    // has actually finished, so that by the time this function returns to
    // Kotlin (and Kotlin proceeds to call clearCache()), the directory is
    // guaranteed free of any live handles. This is safe to call from a JNI
    // thread: JNI calls run on an Android-managed native thread, never on
    // one of this struct's own tokio worker threads, so block_on() here
    // cannot deadlock against the runtime it's blocking on.
    pub fn stop(&self) {
        self.stop_session();
    }

    fn stop_session(&self) {
        if let Some(sess) = self.session.lock().take() {
            self.rt.block_on(async move { sess.stop().await });
        }
        // Explicitly detach the HTTP server's reference too. TorrentSession::
        // stop() already nulls torrent_handle/rq_session internally, so a
        // stray request landing here would get a clean 503 either way — but
        // clearing it here makes the "no active session" state explicit
        // instead of relying on that as a side effect, and avoids the
        // TorrentHttpServer holding an Arc<TorrentSession> alive (however
        // inert) for longer than the session is actually meant to exist.
        if let Some(srv) = self.http_server.lock().as_ref() {
            srv.clear_session();
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
    // Returns true if the directory was removed (or didn't exist to begin
    // with — also a success from the caller's point of view). Returns false
    // only on a real removal failure (e.g. a file handle still open), so
    // Kotlin can log/report it instead of silently assuming success.
    pub fn clear_cache(save_dir: &str) -> bool {
        match std::fs::remove_dir_all(save_dir) {
            Ok(())                                                    => true,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound         => true,
            Err(e) => {
                log::warn!("clear_cache: failed to remove {save_dir}: {e}");
                false
            }
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
