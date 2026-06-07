# StreamX GitHub Actions Workflows

## StreamX-Ultra-Project

### android-build.yml  (push to main)
```
Trigger:  git push main / workflow_dispatch

Steps:
1. Checkout code
2. Setup JDK 17 + Android SDK + NDK 29
3. Install Rust + cargo-ndk
4. Cache Rust build artifacts (keyed by Cargo.lock)
5. Build MPV + FFmpeg for arm64/armv7l/x86_64
6. Create google-services.json from secret
7. ./gradlew assembleDebug
8. Upload APK as artifact

Secrets needed:
  GOOGLE_SERVICES_JSON (base64 encoded)
  TMDB_API_KEY
  STARTAPP_APP_ID
  BACKEND_BASE_URL
  ACCESS_TOKEN (for private submodules if any)
```

### create_release.yml  (push tag v*.*)
```
Trigger:  git tag v1.0.0 && git push --tags

Steps:
1-7: Same as android-build.yml
8.  Decode release keystore from secret
9.  ./gradlew assembleRelease (signed)
10. Generate changelog from git history
11. Create GitHub Release with APK attached

Secrets needed:
  (all of android-build.yml secrets, plus:)
  RELEASE_KEYSTORE          (base64 encoded .jks file)
  RELEASE_KEYSTORE_PASSWORD
  RELEASE_KEY_ALIAS
  RELEASE_KEY_PASSWORD
  BOT_TOKEN                 (GitHub PAT for release creation)
```

---

## streamx-addons

### pages.yml
```
Trigger:  git push main

Steps:
1. Deploy to GitHub Pages
2. index.html, manifest.json, registry.json, modflix.json all served
```

### check-urls.yml  (every 6h + manual)
```
Trigger:  schedule 0 */6 * * *

Steps:
1. For each provider in manifest.json:
   → Test if domain is reachable
   → Update modflix.json with working URLs
2. Commit updated modflix.json
3. Providers always get fresh domains without app update
```

---

## streamx-registry

### health-check.yml  (every 6h)
```
Trigger:  schedule 0 */6 * * *

Steps:
1. Run scripts/health-check.js
   → For each addon in registry.json:
      → GET /manifest.json (must return 200)
      → GET /stream/movie/tt0068646.json (must return { streams: [] })
      → Check latency < 15s
   → Record pass/fail in strikes.json
   → If strikes >= 3:
      → Remove from registry.json
      → Add to graveyard.json
      → Open GitHub Issue (auto)
      → POST Discord webhook (if DISCORD_WEBHOOK secret set)
2. Commit updated files
3. Upload health-report.json as artifact (30 day retention)

Secrets needed:
  DISCORD_WEBHOOK (optional)
```

### handle-submission.yml  (on Issue labeled "addon-submission")
```
Trigger:  issues labeled with "addon-submission"

Steps:
1. Extract JSON from issue body (```json block)
2. Run scripts/validate-and-add.js:
   → Check required manifest fields
   → Check id format (community.*.*)
   → Check HTTPS
   → Check transportUrl ends with /manifest.json
   → Check not already in registry (or allow update)
   → Fetch live /manifest.json → verify id matches
   → Fetch /stream/movie/tt0068646.json → verify shape
   → Post intermediate validation comment on Issue
   → If ALL pass: add to registry.json
3. Commit registry.json
4. Comment success + close Issue
5. On failure: comment error + add "validation-failed" label

No fork needed. No PR needed.
Developer just runs: streamx-deploy register
```

---

## streamx-deploy

### ci.yml  (push to main, tags)
```
Trigger:  git push main / tags / pull_request

Jobs:
  validate:
    → bash -n streamx-deploy (syntax check)
    → shellcheck linting
    → Test: streamx-deploy help / version / platforms

  publish (main branch only):
    → Copy script + installer to _site/
    → Deploy to GitHub Pages
    → Serves: streamx-deploy, install.sh, index.html, README.md

  release (tags only):
    → Create .tar.gz + .zip archive
    → Create GitHub Release with files attached
    → Developer can download versioned releases
```

---

## GitHub Secrets Reference

| Secret | Used in | Description |
|--------|---------|-------------|
| `TMDB_API_KEY` | App build | Embedded at compile time via Rust |
| `GOOGLE_SERVICES_JSON` | App build | Firebase config (base64) |
| `STARTAPP_APP_ID` | App build | Ad network |
| `BACKEND_BASE_URL` | App build | Backend API URL |
| `ACCESS_TOKEN` | App checkout | GitHub PAT for private repos |
| `RELEASE_KEYSTORE` | Release | .jks file (base64) |
| `RELEASE_KEYSTORE_PASSWORD` | Release | Keystore password |
| `RELEASE_KEY_ALIAS` | Release | Key alias |
| `RELEASE_KEY_PASSWORD` | Release | Key password |
| `BOT_TOKEN` | Release | PAT for creating releases |
| `DISCORD_WEBHOOK` | Registry | Health alert notifications |
