# StreamX Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  StreamX Android App                                                 │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  JsStreamProviderEngine  (unified stream engine)             │   │
│  │                                                              │   │
│  │  Type A: HTTP Addons (Stremio protocol)                      │   │
│  │    AddonStorage.getHttpAddons()                              │   │
│  │    → HttpAddonTransport                                      │   │
│  │    → GET /stream/{type}/{id}.json                            │   │
│  │    → ANY Stremio community addon works ✓                     │   │
│  │                                                              │   │
│  │  Type B: Bundle JS Addons (Vega-style)                       │   │
│  │    AddonStorage.getInstalled()                               │   │
│  │    → JsEngine (Rhino)                                        │   │
│  │    → execModule(stream.js)                                   │   │
│  │    → JsProviderContext (axios→OkHttp, cheerio→Jsoup)         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  SubtitleAddonClient                                         │   │
│  │    → HTTP subtitle addons (GET /subtitles/{type}/{id}.json)  │   │
│  │    → OpenSubtitles REST fallback                             │   │
│  │    → Passes to ExoMoviePlayerScreen via subtitlesJson        │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  TorrentEngine (Rust-backed via JNI)                         │   │
│  │    → librqbit (pure Rust, replaces C++ libtorrent)           │   │
│  │    → Zero-buffer piece picker (playback-aware priorities)    │   │
│  │    → Built-in HTTP server port 8088 (replaces Ktor server)   │   │
│  │    → MPV plays from http://127.0.0.1:8088/stream             │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
         ↕ GitHub Pages (free)
┌─────────────────────────────────────────────────────────────────────┐
│  streamx-addons repo (GitHub Pages)                                  │
│  URL: https://aeoncorex-lab.github.io/streamx-addons/               │
│                                                                      │
│  /index.html        → Catalog website (futuristic, 3D particles)    │
│  /manifest.json     → Official bundle addon list (37 providers)     │
│  /registry.json     → Community HTTP addon list                     │
│  /modflix.json      → Live domain URLs (updated every 6h)           │
│  /dist/{key}/       → Bundled JS modules for each provider          │
└─────────────────────────────────────────────────────────────────────┘
         ↕ GitHub Actions
┌─────────────────────────────────────────────────────────────────────┐
│  streamx-registry repo                                               │
│                                                                      │
│  registry.json      → Approved community HTTP addons                │
│  strikes.json       → Health check failure tracking                 │
│  graveyard.json     → Auto-removed addons                           │
│  scripts/           → health-check.js, validate-and-add.js          │
│  .github/workflows/ → health-check.yml, handle-submission.yml       │
└─────────────────────────────────────────────────────────────────────┘
         ↕ Developer uses
┌─────────────────────────────────────────────────────────────────────┐
│  streamx-deploy (bash CLI tool)                                      │
│  Install: curl -sSL .../install.sh | bash                           │
│                                                                      │
│  streamx-deploy new        → scaffold addon project                 │
│  streamx-deploy deploy     → deploy to any platform                 │
│  streamx-deploy test       → local endpoint testing                 │
│  streamx-deploy validate   → validate live addon                    │
│  streamx-deploy register   → open GitHub Issue (bot auto-adds)      │
└─────────────────────────────────────────────────────────────────────┘
```

## Stremio Protocol (identical in StreamX)

```
GET /manifest.json               → addon capabilities
GET /stream/movie/tt1234567.json → streams for movie
GET /stream/series/tt1234567:1:3.json → streams for S01E03
GET /subtitles/movie/tt1234567.json   → subtitle files
GET /catalog/movie/top.json           → browse content

Response shapes:
  { streams:   [{ url, name, description, subtitles, behaviorHints }] }
  { subtitles: [{ url, lang, id }] }
  { metas:     [{ id, type, name, poster }] }
```

## Zero-Buffer Torrent Algorithm

```
Piece priority zones (updated every 250ms):
  DONE/behind  → priority 1 (deprioritize)
  CRITICAL     → priority 7 (playhead .. +30 pieces)
  HIGH         → priority 5 (+30 .. +90 pieces)
  NORMAL       → priority 3 (+90 .. +200 pieces)
  LOW          → priority 1 (rest of file)
  HEADER       → priority 7 (first 30 pieces, always)
  TAIL         → priority 6 (last 10 pieces, always)

Ready gate: header OK + ≥20 CRITICAL pieces + progress ≥3%
Seek: instantly reassign all priorities around new playhead
```

## Deeplink System

```
streamx://install-addon?url=<manifest_url>
  → MainActivity.handleIntent()
  → AddonRegistry.installByUrl(url)
  → AddonStorage.saveHttpAddon(desc)
  → Opens AddonScreen (Installed tab)

streamx://add-source?url=<repo_url>&author=<name>
  → AddonStorage.addSource(author, url)
  → AddonManager.initialize()
```

## Registration Flow (No fork/PR needed)

```
Developer:
  1. streamx-deploy register
  2. GitHub Issue auto-opens (pre-filled JSON)
  3. Developer clicks Submit

GitHub Actions (handle-submission.yml):
  → validate-and-add.js runs
  → Tests /manifest.json + /stream endpoint
  → If PASS: adds to registry.json, commits, closes Issue
  → If FAIL: comments error on Issue

Health check (every 6h):
  → Tests all registered addons
  → 3 failures → auto-remove + GitHub issue + Discord
```
