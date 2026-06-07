# GitHub Secrets Reference

## StreamX-Ultra-Project repo

Go to: Settings → Secrets and variables → Actions → New repository secret

| Secret | Value | Required for |
|--------|-------|-------------|
| `TMDB_API_KEY` | Your TMDB API v3 key | App build (embedded in Rust at compile time) |
| `GOOGLE_SERVICES_JSON` | `base64 -w0 google-services.json` | Firebase |
| `STARTAPP_APP_ID` | Your StartApp app ID | Ads |
| `BACKEND_BASE_URL` | https://your-backend.com | Backend API |
| `ACCESS_TOKEN` | GitHub PAT (repo scope) | Private submodule checkout |
| `RELEASE_KEYSTORE` | `base64 -w0 release.jks` | Signed APK |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password | Signed APK |
| `RELEASE_KEY_ALIAS` | Key alias name | Signed APK |
| `RELEASE_KEY_PASSWORD` | Key password | Signed APK |
| `BOT_TOKEN` | GitHub PAT (repo + workflow scope) | Create GitHub Releases |

### How to encode keystore
```bash
base64 -w0 release.jks | xclip  # Linux
base64 -i release.jks | pbcopy  # macOS
# Paste as RELEASE_KEYSTORE secret value
```

### How to encode google-services.json
```bash
base64 -w0 google-services.json | xclip  # Linux
base64 -i google-services.json | pbcopy  # macOS
```

---

## streamx-registry repo

| Secret | Value | Required for |
|--------|-------|-------------|
| `DISCORD_WEBHOOK` | Discord webhook URL | Health check alerts (optional) |

The `GITHUB_TOKEN` is auto-provided by GitHub Actions for `contents: write` and `issues: write`.

### Discord webhook setup (optional)
1. Discord server → channel settings → Integrations → Webhooks → New Webhook
2. Copy URL → add as `DISCORD_WEBHOOK` secret

---

## streamx-deploy repo

No custom secrets needed. `GITHUB_TOKEN` handles everything.

---

## Variables (non-secret, public)

Go to: Settings → Secrets and variables → Actions → Variables tab

| Variable | Repo | Value |
|----------|------|-------|
| `WORKER_URL` | streamx-deploy | Deployed Cloudflare worker URL (for post-deploy verification) |

---

## Local development

Create `local.properties` in project root (never commit):
```properties
tmdbApiKey=your_tmdb_key_here
backendBaseUrl=http://10.0.2.2:8080
startappAppId=your_startapp_id
```

Or set environment variables:
```bash
export TMDB_API_KEY=your_key
./gradlew assembleDebug
```
