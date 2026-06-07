# StreamX Ultra — Claude Project Context

## What is this project?
StreamX Ultra is a professional Android streaming app built by **Nahid** (GitHub: `aeoncorex-lab`).
It is architecturally comparable to Stremio — a dynamic addon system, Rust torrent core,
community addon infrastructure, and a catalog website.

## Read these files in order
1. `CLAUDE.md` ← you are here
2. `ARCHITECTURE.md` — full system design, what each repo does
3. `FILE_MAP.md` — every file in every repo, status (done/missing/replace)
4. `ADDON_SYSTEM.md` — how addons work (bundle JS + HTTP/Stremio protocol)
5. `RUST_CORE.md` — Rust torrent engine, JNI bridge, zero-buffer algorithm
6. `WORKFLOWS.md` — GitHub Actions, CI/CD, registration flow
7. `STATUS.md` — current build status, what works, what's pending

## Key facts Claude must always know

### Language
Nahid communicates in **Banglish** (Bengali + English mix). Always respond in the same style.

### GitHub Organization
```
aeoncorex-lab (GitHub org)
├── StreamX-Ultra-Project   → Android app (Kotlin + Rust + MPV)
├── streamx-addons          → Providers + catalog website (GitHub Pages)
├── streamx-registry        → Community addon registry + health check
└── streamx-deploy          → Bash CLI deploy tool for addon developers
```

### Tech Stack
- **Android**: Kotlin + Jetpack Compose + ExoPlayer + MPV
- **Torrent**: Rust (librqbit) — replaces C++ libtorrent
- **JS addons**: Rhino (executes bundled provider JS)
- **HTTP addons**: Stremio protocol (GET /stream/{type}/{id}.json)
- **Build**: Gradle + cargo-ndk + CMake

### Never suggest
- Reverting to C++ libtorrent (replaced by Rust)
- Keeping TorrentStreamServer.kt (deleted, Rust HTTP server replaces it)
- Adding vcpkg or Boost dependencies (removed)
- Separate repo for Rust core (integrated into main app at `app/src/main/rust/`)
