# StreamX Addon System

## Two addon types

### Type A — Bundle JS Addons (Vega-style)
- Served from GitHub Pages (`streamx-addons/dist/{key}/`)
- Executed via **Rhino** (embedded JS engine) — no network requests from JS engine itself
- `JsProviderContext` bridges: `axios` → OkHttp, `cheerio` → Jsoup
- `ModflixConfig` provides live domain URLs via `providerContext.getBaseUrl(key)`
- Stored in `AddonStorage.getInstalled()` → `List<AddonInfo>`
- Examples: VegaMovies, HDHub4u, FilmyFly, HiAnime, KissKh

### Type B — HTTP Addons (Stremio protocol)
- Live HTTP endpoints anywhere (Cloudflare, Vercel, etc.)
- `HttpAddonTransport` calls `GET /stream/{type}/{id}.json`
- **Any Stremio community addon works** — same protocol
- Stored in `AddonStorage.getHttpAddons()` → `List<AddonDescriptor>`
- Examples: Torrentio, MediaFusion, AIOStreams, OpenSubtitles v3

## Addon data flow

```kotlin
// JsStreamProviderEngine fetches BOTH types in parallel:

// Type A
val bundleAddons = AddonStorage.getInstalled().filter { !it.disabled }
// → JsEngine.execModule(stream.js, JsProviderContext)

// Type B
val httpAddons = AddonStorage.getHttpAddons().filter {
    it.manifest.supportsStream(contentType, videoId)
}
// → HttpAddonTransport.streams(type, id)
// → GET {baseUrl}/stream/{type}/{encodedId}.json

// Results merged, deduplicated, sorted by quality, capped at 20
```

## Subtitle addon flow

```kotlin
// ExoSourceSelectionScreen — parallel fetch:
val subtitleJob = async {
    SubtitleAddonClient.fetchSubtitles(imdbId, language, isSeries, season, episode)
}
// Sources subtitles from:
//   1. Installed HTTP subtitle addons (resources: ["subtitles"])
//   2. OpenSubtitles REST API fallback

// On play:
subtitlesJson = [stream.subtitles] + [addonSubtitles.take(5)]
// → passed to ExoMoviePlayerScreen via navigation
// → ExoPlayer SubtitleConfiguration + MPV sub-add
```

## AddonStorage keys (SharedPreferences)
```
"addon_sources"    → List<AddonSource>      (bundle repo URLs)
"installed_addons" → List<AddonInfo>        (installed bundle addons)
"addon_modules"    → cached JS code strings  (stream.js, catalog.js, etc.)
"http_addons"      → List<AddonDescriptor>  (HTTP endpoint addons)
"pending_addons"   → List<AddonInfo>        (failed first-launch → retry)
"manifest_{author}" → cached manifest JSON
"manifest_time_{author}" → cache timestamp (24h TTL)
```

## AddonDescriptor JSON format (registry.json entry)
```json
{
  "manifest": {
    "id":          "community.myname.myaddon",
    "version":     "1.0.0",
    "name":        "My Addon",
    "description": "Streams from MySite",
    "logo":        "https://...",
    "types":       ["movie", "series"],
    "resources":   ["stream"],
    "idPrefixes":  ["tt"],
    "catalogs":    []
  },
  "transportUrl": "https://my-addon.vercel.app/manifest.json",
  "kind":  "HTTP_ENDPOINT",
  "flags": { "official": false, "verified": true, "nsfw": false }
}
```

## Default addon source
```
Author: streamx
URL:    https://aeoncorex-lab.github.io/streamx-addons
Serves: manifest.json + modflix.json + dist/ JS modules
```

## Registry URLs (in AddonRegistry.kt)
```kotlin
OFFICIAL_MANIFEST_URL  = ".../streamx-addons/manifest.json"
COMMUNITY_REGISTRY_URL = ".../streamx-addons/registry.json"
MODFLIX_URL            = ".../streamx-addons/modflix.json"
```

## Stremio addon compatibility
Any Stremio addon works in StreamX:
- Same protocol: GET /manifest.json + GET /stream/{type}/{id}.json
- Same ID format: "tt{imdbId}" or "tt{imdbId}:{season}:{episode}"
- User installs via Settings → Addons → HTTP tab → Add by URL
- OR via deeplink: streamx://install-addon?url={manifestUrl}
- OR via catalog website "Install in StreamX" button

Known working Stremio addons:
- https://v3-cinemeta.strem.io/manifest.json (metadata)
- https://torrentio.strem.fun/manifest.json (torrent streams)
- https://opensubtitles-v3.strem.io/manifest.json (subtitles)
- https://mediafusion.elfhosted.com/manifest.json (multi-source)

## ModflixConfig — why it's still needed
Bundle JS addons call `providerContext.getBaseUrl('Vega')` to get the current
domain for VegaMovies (or any other provider). Domains change frequently.
ModflixConfig fetches the latest URLs from `streamx-addons/modflix.json`
which is auto-updated every 6h by GitHub Actions. Without this, bundle
JS addons cannot find their source domains.
