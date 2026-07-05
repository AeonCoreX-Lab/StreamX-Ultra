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
    // Each installed session is tagged with the generation it was created
    // under. This lets stop() prove — not just infer from timing — that
    // the session it's about to take() is the one it was actually meant
    // to stop, even if a concurrent start() has installed a newer one in
    // the meantime. See stop()'s doc comment for the full race history.
    session:     Mutex<Option<(u64, Arc<TorrentSession>)>>,
    http_server: Mutex<Option<TorrentHttpServer>>,
    generation:  std::sync::atomic::AtomicU64,
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
            generation:  std::sync::atomic::AtomicU64::new(0),
        }
    }

    // ── Start ─────────────────────────────────────────────────────────────────
    pub fn start(&self, magnet: &str, save_dir: &str) {
        // Claim the next generation for the session we're about to create.
        let my_generation = self.generation.fetch_add(1, std::sync::atomic::Ordering::SeqCst) + 1;

        // Take and stop whatever session is currently installed, if any.
        // This always proceeds unconditionally — start() is about to
        // overwrite both the session slot and the HTTP server reference a
        // few lines below regardless, so there's nothing to race here.
        if let Some((_, sess)) = self.session.lock().take() {
            self.rt.block_on(async move { sess.stop().await });
        }

        let magnet   = magnet.to_string();
        let save_dir = save_dir.to_string();

        let session = Arc::new(TorrentSession::new());
        *self.session.lock() = Some((my_generation, session.clone()));

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

        info!("TorrentEngine::start — session started (generation {my_generation})");
    }

    // ── Stop ──────────────────────────────────────────────────────────────────
    // FIX (storage race): this used to fire-and-forget the async teardown
    // (rt.spawn(async move { sess.stop().await })) and return immediately.
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
    //
    // FIX (race causing instant stuck-00:00 on the NEXT movie): Kotlin's
    // stopAndClearCache() (called from onDispose) dispatches stopNative()
    // on a background scope and returns immediately (see TorrentEngine.kt's
    // FIX (main-thread block) comment) — it does NOT block the next
    // start() from running. Because of that, this used to be reachable:
    //
    //   1. Movie A's onDispose fires stop() in the background (thread T1).
    //   2. Before T1's rt.block_on(sess.stop()) even begins — or while
    //      it's still running — movie B's start() runs (thread T2): it
    //      takes/stops Movie A's session itself as its own internal
    //      pre-stop, then installs a brand-new session (Movie B's) and
    //      calls srv.set_session(new).
    //   3. T1 then reaches `self.session.lock().take()` — but the slot no
    //      longer holds Movie A's session (T2 already took and stopped
    //      it); it now holds MOVIE B'S brand-new session. A plain take()
    //      here would grab and stop Movie B's session instead — killing
    //      the movie the user just started, moments after it began, with
    //      no error, no crash, just an instant, buffer-less stuck 00:00.
    //
    // This is why a plain "does the generation counter still match"
    // check taken only AFTER take() isn't good enough: by the time you
    // can check anything, you may have already taken (and are about to
    // stop) the wrong session. The real fix has to make take() itself
    // refuse to grab a session it doesn't recognize as its own.
    //
    // Fix: stop() records the generation it observed BEFORE touching the
    // slot, then takes the slot's contents and checks the tag stored
    // alongside the session itself — not a separate, untagged Arc. If the
    // tag doesn't match what stop() expected (a newer start() has since
    // installed a different session), stop() puts that session BACK and
    // does nothing further — it never calls .stop() on a session it
    // didn't intend to touch, and never clears the HTTP server's
    // reference to it either. The one remaining edge case — Movie A's
    // session simply never gets explicitly stopped by this stale stop()
    // call — is not a regression: that session was already superseded by
    // start()'s own internal pre-stop in step 2 above, so it was already
    // stopped by the time this stop() call even runs.
    pub fn stop(&self) {
        let expected_generation = self.generation.load(std::sync::atomic::Ordering::SeqCst);

        let maybe_owned = {
            let mut slot = self.session.lock();
            match slot.as_ref() {
                Some((gen, _)) if *gen == expected_generation => slot.take(),
                _ => None, // slot is empty, or holds a newer session that isn't ours to stop
            }
        };

        if let Some((_, sess)) = maybe_owned {
            self.rt.block_on(async move { sess.stop().await });

            // Nothing newer can have raced in here: we already confirmed
            // (under the lock, atomically with the take()) that the
            // generation we captured was the current one, and no other
            // caller can install a session under our already-claimed
            // generation number. Safe to detach the HTTP server now.
            if let Some(srv) = self.http_server.lock().as_ref() {
                srv.clear_session();
            }
        }
        // else: either nothing was running, or a newer start() already
        // superseded whatever this stop() was meant for — in the latter
        // case that newer session's own start() call already stopped the
        // old one itself (see start()'s internal pre-stop), so there is
        // nothing left for this stale stop() call to do.
    }

    // ── Status (called from Kotlin every 250 ms) ──────────────────────────────
    pub fn status(&self) -> TorrentStatus {
        self.session.lock()
            .as_ref()
            .map(|(_, s)| s.status())
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
            .map(|(_, s)| s.debug_dump())
            .unwrap_or_else(|| "session=NONE (no torrent active)\n".to_string())
    }

    // ── Playhead update (from Kotlin MPV observer) ────────────────────────────
    pub fn set_playhead(&self, secs: f64) {
        if let Some((_, sess)) = self.session.lock().as_ref() {
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
