# Architectural Decisions

## Why Rust for torrent (not C++ libtorrent)?

**Problem with C++ libtorrent:**
- vcpkg + Boost dependencies: complex build, `aligned_alloc` crash on Android
- C++ → JNI boundary: unsafe, hard to maintain
- Rarest-first piece selection: optimizes for seeder health, not playback → buffering

**Rust advantages:**
- `librqbit`: pure Rust BitTorrent, designed for streaming
- Same JNI function names → Kotlin code unchanged
- Zero-buffer piece picker: playback-aware priority zones
- Single binary: torrent + HTTP server + addon transport all in one `.a`
- Future WASM/Tauri reuse of same core

**Decision: Rust integrated INTO main app repo** (not separate repo)
- Builds automatically via Gradle `cargoBuild` task
- No separate CI to manage
- `app/src/main/rust/` follows Android project conventions

---

## Why Rhino for JS (not QuickJS, V8, etc.)?

- **Rhino**: mature, well-tested on Android, good Java interop
- QuickJS: faster but more complex Android integration
- V8: too large for mobile
- Rhino lets us bridge `axios` → OkHttp and `cheerio` → Jsoup cleanly

---

## Why Stremio protocol for HTTP addons?

- **Existing ecosystem**: thousands of community addons already built
- **Simple protocol**: just HTTP GET, JSON response — any language works
- **Interoperability**: addon works in both Stremio and StreamX
- **No proprietary format**: developers don't need StreamX-specific SDK

---

## Why GitHub Pages for registry (not a backend)?

- **Free**: no server cost ever
- **Always up**: GitHub SLA > 99.9%
- **Version controlled**: every change tracked in git
- **CI/CD built-in**: GitHub Actions for health checks and auto-update
- **No auth needed**: public JSON files

---

## Why bash for deploy tool (not Node.js)?

- **Universal**: every Unix/Linux/macOS system has bash
- **No runtime install**: developer doesn't need Node, Python, etc.
- **Single file**: one `curl | bash` install
- **Transparent**: developers can read/audit the tool easily
- Node.js version required Python too (for JSON parsing) — worse dependency situation

---

## Why Issue-based registration (not PR)?

- **Developer doesn't need to fork**: lower barrier to entry
- **Automatic**: bot validates + adds in minutes
- **Audit trail**: GitHub Issues track all submissions + bot comments
- **No merge conflicts**: bot handles registry.json updates
- PR approach required: fork → edit JSON → create PR → wait for review → merge
- Issue approach: open Issue → Submit → done (if valid)

---

## Why separate streamx-registry repo (not in streamx-addons)?

- **Different permissions**: registry needs `contents: write` for auto-commits
- **Different workflow frequency**: health check every 6h, addons deploy occasionally
- **Cleaner separation**: addon hosting vs quality control are different concerns
- **Graveyard isolation**: removed addons tracked separately from active ones

---

## Why two addon types coexist (Bundle + HTTP)?

- **Bundle addons**: scrape Indian/Asian content sites that don't have APIs
  - VegaMovies, HDHub4u, FilmyFly — no public API, need JS scraping
  - ModflixConfig handles domain changes without app update
- **HTTP addons**: international content, debrid services, subtitles
  - Torrentio, MediaFusion, AIOStreams — have proper APIs
  - Stremio compatibility: users can install any Stremio addon

**Cannot replace bundle addons with HTTP**: the content sources don't
have APIs. Scraping from JS running in Rhino is the only option.

---

## Why ExoPlayer AND MPV?

- **ExoPlayer**: normal HTTP streams (m3u8, mp4, mkv) — works with subtitles,
  hardware decoding, picture-in-picture, all Android-native features
- **MPV**: torrent streams from local HTTP server — handles any container,
  codecs that ExoPlayer doesn't support, better seeking into incomplete files

User flow:
- Stream URL → ExoPlayer (ExoMoviePlayerScreen)
- Magnet/torrent → TorrentEngine → local HTTP → MPV (MoviePlayerScreen)
