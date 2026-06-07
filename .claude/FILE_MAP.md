# StreamX File Map

## Legend
- ✅ DONE — file is correct and complete
- 🔄 REPLACE — use the version from patch zips
- ➕ NEW — file to be added
- ❌ DELETE — file must be removed
- 📁 KEEP — unchanged, do not touch

---

## StreamX-Ultra-Project (Android App)

### app/src/main/AndroidManifest.xml
🔄 REPLACE — Add deeplink intent-filters for `streamx://install-addon` and `streamx://add-source`
Also add `android:launchMode="singleTop"` to MainActivity.

### app/src/main/assets/
```
default_addons.json    ✅ DONE — 8 default addons seeded on first launch
```

### app/src/main/java/com/aeoncorex/streamx/
```
MainActivity.kt                    ✅ DONE — deeplink handler, AddonStorage.init, DefaultAddonManager.seed
navigation/AppNavigation.kt        ✅ DONE — addons route + pendingInstallUrl param
```

### streaming/
```
AddonStorage.kt                    ✅ DONE — full version (bundle + HTTP addons + pending)
AddonDescriptor.kt                 ✅ DONE — Descriptor/Manifest/Stream/ResourcePath types
AddonManager.kt                    ✅ DONE — install/uninstall/fetchManifest
DefaultAddonManager.kt             ✅ DONE — seeds default_addons.json on first launch
JsEngine.kt                        ✅ DONE — Rhino JS executor
JsProviderContext.kt               ✅ DONE — axios→OkHttp, cheerio→Jsoup bridges
JsStreamProviderEngine.kt          ✅ DONE — unified HTTP + Bundle engine (bugs fixed)
StreamProviderEngine.kt            ✅ DONE — thin delegate
SubtitleAddonClient.kt             ✅ DONE — queries subtitle addons + OpenSubtitles fallback
HttpClient.kt                      📁 KEEP
ModflixConfig.kt                   📁 KEEP — still needed for bundle JS addons' domain URLs
StreamCache.kt                     📁 KEEP
StreamResult.kt                    📁 KEEP
PrefetchEngine.kt                  📁 KEEP

streaming/transport/
  AddonTransport.kt                ✅ DONE — import bug fixed, HttpAddonTransport

streaming/registry/
  AddonRegistry.kt                 ✅ DONE — correct GitHub Pages URLs
```

### ui/
```
ui/addons/
  AddonScreen.kt                   ✅ DONE — Browse/Installed/HTTP tabs, autoInstallUrl param

ui/movie/
  ExoSourceSelectionScreen.kt      🔄 REPLACE — SubtitleAddonClient integrated
  ExoMoviePlayerScreen.kt          📁 KEEP — already parses subtitlesJson correctly
  TorrentEngine.kt                 ✅ DONE — Rust-backed, no TorrentStreamServer
  TorrentStreamServer.kt           ❌ DELETE — Rust HTTP server replaces it
  MoviePlayerScreen.kt             📁 KEEP
  MovieLinkSelectionScreen.kt      📁 KEEP
  TorrentModels.kt                 📁 KEEP
  TorrentProviders.kt              📁 KEEP
  TorrentRepository.kt             📁 KEEP

ui/settings/
  SettingsScreen.kt                ✅ DONE — Manage Addons item with count badge
```

### cpp/
```
CMakeLists.txt                     ✅ DONE — libtorrent/vcpkg removed, links Rust .a
native-lib.cpp                     ✅ DONE — MPV JNI only, torrent code removed
mpv_handler.cpp                    📁 KEEP
mpv_handler.hpp                    📁 KEEP
torrent-engine.cpp                 ❌ DELETE
torrent_system.hpp                 ❌ DELETE
```

### rust/ (app/src/main/rust/)
```
Cargo.toml                         ✅ DONE — librqbit + reqwest + hyper + tokio
src/lib.rs                         ✅ DONE — TMDB JNI + torrent JNI + addon transport JNI
src/torrent/
  mod.rs                           ✅ DONE — constants (ports, trackers, piece counts)
  engine.rs                        ✅ DONE — TorrentEngine singleton, Tokio runtime
  session.rs                       ✅ DONE — download loop, piece prioritization
  piece_picker.rs                  ✅ DONE — zero-buffer algorithm
  http_server.rs                   ✅ DONE — /stream + /sub/{filename} endpoints
```

### .github/workflows/
```
android-build.yml                  ✅ DONE — vcpkg removed, Rust cargo-ndk build added
create_release.yml                 ✅ DONE — vcpkg removed, signed APK release
pages.yml                          📁 KEEP
```

### app/build.gradle.kts
```
✅ DONE — org.mozilla:rhino:1.7.14 added, Ktor removed, cargo build task
```

---

## streamx-addons (GitHub Pages)

```
index.html                         ✅ DONE — futuristic catalog website (3D particles, real-time)
manifest.json                      ✅ DONE — 37 bundle provider addons
modflix.json                       ✅ DONE — live domain URLs
registry.json                      ✅ DONE — community HTTP addons list
dist/{key}/                        ✅ DONE — bundled JS modules for all providers

.github/workflows/
  pages.yml                        ✅ DONE — GitHub Pages auto-deploy
  check-urls.yml                   ✅ DONE — updates modflix.json every 6h
```

---

## streamx-registry

```
registry.json                      ✅ DONE — approved addons (starts empty or seeded)
strikes.json                       ✅ DONE — {}
graveyard.json                     ✅ DONE — []

scripts/
  health-check.js                  ✅ DONE — tests all addons, auto-removes after 3 fails
  validate-and-add.js              ✅ DONE — validates + auto-adds on Issue submission

.github/
  workflows/
    health-check.yml               ✅ DONE — runs every 6h
    handle-submission.yml          ✅ DONE — auto-processes addon Issues
  ISSUE_TEMPLATE/
    addon-submission.yml           ✅ DONE — structured form for addon submission
```

---

## streamx-deploy

```
streamx-deploy                     ✅ DONE — 1100-line bash CLI
install.sh                         ✅ DONE — one-line installer
README.md                          ✅ DONE — full documentation

.github/
  workflows/
    ci.yml                         ✅ DONE — validate syntax + publish to Pages + release
```

---

## Files to deliver to Nahid (from all patch zips)

### In StreamX-Ultra-Project:
- `app/src/main/AndroidManifest.xml` (🔄)
- `app/src/main/java/.../ui/movie/ExoSourceSelectionScreen.kt` (🔄)
- DELETE: `TorrentStreamServer.kt`, `torrent-engine.cpp`, `torrent_system.hpp`

### In streamx-addons:
- `index.html` (➕)
- `registry.json` (➕)

### In streamx-registry:
- `scripts/validate-and-add.js` (➕)
- `.github/workflows/handle-submission.yml` (➕)
- `.github/ISSUE_TEMPLATE/addon-submission.yml` (➕)

### In streamx-deploy:
- `.github/workflows/ci.yml` (➕)
- Update `cmd_register()` with new Issue-based flow
