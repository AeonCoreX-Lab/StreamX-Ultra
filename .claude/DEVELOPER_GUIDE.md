# StreamX Addon Developer Guide

## Full flow: idea → registered addon in ~10 minutes

### Prerequisites
- Terminal (bash/zsh)
- Node.js 18+ (for running the addon)
- Account on your chosen hosting platform

### Step 1: Install streamx-deploy
```bash
curl -sSL https://aeoncorex-lab.github.io/streamx-deploy/install.sh | bash
```

### Step 2: Create project
```bash
streamx-deploy new
# Interactive prompts:
#   Project name:   my-addon
#   Addon ID:       community.myname.myaddon
#   Display name:   My StreamX Addon
#   Description:    Streams from MySite
#   Platform:       [choose one]
```

Generated files:
```
my-addon/
├── src/index.js          ← EDIT THIS
├── manifest.json
├── package.json
├── .streamx-deploy.json
├── wrangler.toml         (Cloudflare) or vercel.json etc.
└── .github/workflows/deploy.yml
```

### Step 3: Write your logic
Edit `src/index.js`, fill in `getStreams()`:
```javascript
async function getStreams(type, imdbId, season, episode) {
  // Fetch from your source
  const resp = await fetch(`https://mysite.xyz/api?id=${imdbId}`)
  const data = await resp.json()
  return data.links.map(link => ({
    url:         link.videoUrl,
    name:        link.quality,   // "1080p", "720p" etc.
    description: link.server     // "Server 1", "CDN" etc.
  }))
}
```

### Step 4: Test locally
```bash
cd my-addon
npm install
streamx-deploy test
# Tests: /manifest.json, /stream/movie/tt0068646.json, CORS headers
```

### Step 5: Deploy
```bash
streamx-deploy deploy
# Or with specific platform:
streamx-deploy deploy -p cloudflare
streamx-deploy deploy -p vercel
streamx-deploy deploy -p docker
```

Platform setup:

| Platform | One-time setup |
|----------|---------------|
| Cloudflare | `npm i -g wrangler && wrangler login` |
| Vercel | `npm i -g vercel && vercel login` |
| Render | Connect GitHub repo at render.com |
| Railway | `npm i -g @railway/cli && railway login` |
| Fly.io | `curl -L fly.io/install.sh \| sh && flyctl auth login` |
| Docker/VPS | Install Docker, have SSH access |
| Cherry | Same as Docker/VPS |

### Step 6: Validate live endpoint
```bash
streamx-deploy validate
# OR
streamx-deploy validate https://my-addon.vercel.app/manifest.json
```

### Step 7: Register to StreamX catalog
```bash
streamx-deploy register
# → Fetches your live manifest
# → Builds registry JSON automatically
# → Opens GitHub Issue pre-filled in browser
# → You click Submit
# → Bot validates + adds to registry in ~2 minutes
# → Addon appears on catalog website!
```

---

## Install in StreamX app

After deploying, users install your addon via:

**Method 1: Deeplink (from catalog website)**
```
User visits: https://aeoncorex-lab.github.io/streamx-addons/
Taps: "Install in StreamX" button
→ streamx://install-addon?url=https://your-addon.vercel.app/manifest.json
→ App opens, addon installs automatically
```

**Method 2: Manual URL paste**
```
Settings → Addons → 🔗 (link icon) → paste manifest URL → Add
```

**Method 3: Deeplink URL (share with users)**
```
streamx://install-addon?url=https://your-addon.vercel.app/manifest.json
```

---

## Auto-deploy on git push

After `streamx-deploy new`, a `.github/workflows/deploy.yml` is created.
Set the required secrets in your GitHub repo settings, and every `git push main`
will auto-deploy to your chosen platform.

Secrets per platform:

| Platform | Required GitHub Secrets |
|----------|------------------------|
| Cloudflare | `CF_API_TOKEN`, `CF_ACCOUNT_ID` |
| Vercel | `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` |
| Render | `RENDER_DEPLOY_HOOK` |
| Railway | `RAILWAY_TOKEN`, `RAILWAY_SERVICE` |
| Fly.io | `FLY_API_TOKEN` |
| Docker/VPS | `DOCKERHUB_USER`, `DOCKERHUB_TOKEN`, `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY` |

---

## Health check rules

After registration, your addon is health-checked every 6 hours:
- `/manifest.json` must return HTTP 200 with valid JSON
- `/stream/movie/tt0068646.json` must return `{ streams: [] }`
- Latency must be < 15 seconds

**Failures:**
- 1 fail → strike recorded
- 2 fails → second strike, Discord alert sent
- 3 fails → **auto-removed** from registry + GitHub Issue opened

If removed: fix your endpoint and run `streamx-deploy register` again.

---

## Adding API keys / secrets

Never put secrets in code. Use platform-native secrets:

```bash
# Cloudflare
wrangler secret put MY_API_KEY
# Access in code: env.MY_API_KEY

# Vercel
vercel env add MY_API_KEY
# Access in code: process.env.MY_API_KEY

# Railway
railway variables set MY_API_KEY=value

# Docker/VPS — .env file (never commit!)
echo "MY_API_KEY=value" >> .env
docker compose up -d  # reads .env automatically
```

---

## Stremio compatibility

Your addon works in both StreamX and Stremio — same protocol.
Users can install it in Stremio too using the same manifest URL.

To verify Stremio compatibility:
```bash
streamx-deploy validate https://your-addon.vercel.app/manifest.json
# Checks same things Stremio requires
```
