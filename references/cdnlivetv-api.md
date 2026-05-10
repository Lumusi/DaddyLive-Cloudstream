# CDNLiveTV Extension — Reference Notes

## Overview
- **Site**: cdnlivetv.tv
- **API domain (channels)**: `api.cdnlivetv.ru` (unprotected JSON API)
- **API domain (events)**: `api.cdnlivetv.tv` (unprotected JSON API — different from channels!)
- **Player domain**: `cdnlivetv.tv` (Cloudflare-protected, OPlayer-based)
- **Channel count**: ~762 channels across 38 country codes
- **Extension package**: `com.cdnlivetv`

## API Endpoints

### Channel List (works, no bot detection)
```
GET https://api.cdnlivetv.ru/api/v1/channels/?user=cdnlivetv&plan=free
```
Headers required:
- `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0`
- `Referer: https://cdnlivetv.tv/`
- `Accept: application/json, text/html, */*; q=0.01`

Response fields per channel:
```json
{
  "name": "ESPN",
  "code": "us",
  "url": "...",
  "image": "https://...",
  "status": "online",
  "viewers": 1234
}
```

Optional params:
- `&code=us` — filter by country code
- `&user=cdnlivetv` — required auth param
- `&plan=all` — may return all plans including premium

Country codes observed (38): us, gb, es, de, au, fr, it, br, pl, za, gr, rs, hr, sa, pt, nl, at, cz, dk, se, il, ar, mx, tr, ro, cy, in, be, ae, hu, uy, cl, co, eg, ru

### Sport Events List
```
GET https://api.cdnlivetv.tv/api/v1/events/sports/?user=cdnlivetv&plan=free
```
Same headers as channel list. This endpoint returns ALL sports events in a single response, keyed by sport name.

Response structure:
```json
{
  "cdn-live-tv": {
    "Soccer": [
      {
        "gameID": "abc123",
        "homeTeam": "Team A",
        "awayTeam": "Team B",
        "homeTeamIMG": "https://...",
        "awayTeamIMG": "https://...",
        "time": "20:00",
        "tournament": "Premier League",
        "country": "England",
        "countryIMG": "https://...",
        "status": "live",
        "start": "2025-01-01T20:00:00Z",
        "channels": [
          {
            "channel_name": "Sky Sports",
            "channel_code": "gb",
            "url": "https://cdnlivetv.tv/api/v1/channels/player/?name=Sky+Sports&code=***&user=cdnlivetv&plan=free",
            "image": "https://...",
            "viewers": 500
          }
        ]
      }
    ],
    "total_events": 79,
    "cached": true
  }
}
```

**IMPORTANT**: The events API is on `api.cdnlivetv.tv`, NOT `api.cdnlivetv.ru`. Using `mainUrl` (which points to `.ru`) for events requests will silently hit the wrong domain and return 404 or wrong data. Always hardcode the full events URL.

**CRITICAL**: The response is nested. The actual event lists are inside the `"cdn-live-tv"` key, then keyed by sport name (e.g., `"Soccer"`, `"Basketball"`). Parse into a `Map<String, Any>` first, extract the `"cdn-live-tv"` wrapper, then iterate through the inner map to find the sport-keyed arrays. Always exclude metadata keys like `"total_events"`, `"cached"`, `"timestamp"` when looking for the actual data arrays.

**Multi-source extraction**: Each event has a `channels[]` array with multiple broadcast sources from different countries. When implementing `loadLinks()`, call `loadExtractor()` sequentially in a `forEach` loop for each channel, NOT using `coroutineScope` with `async`/`awaitAll()`. The async pattern can cause issues with how `loadExtractor` works internally. See the "Multi-source extraction: use sequential `loadExtractor` calls" pitfall in the main skill for the correct pattern.

**Routing**: Use custom identifiers like `"sport_soccer"` in `mainPageOf()` instead of full URLs. This avoids URL parsing issues and makes routing more reliable. In `getMainPage()`, detect the category via `request.data.startsWith("sport_")` and dispatch to the appropriate handler.

The events are wrapped inside the `"cdn-live-tv"` key. Within that, the sport-keyed array key matches the capitalized sport name (e.g., `"Soccer"`, `"Basketball"`).

### Player Page (Cloudflare-protected)
```
GET https://cdnlivetv.tv/api/v1/channels/player/?name={NAME}&code=***&user=cdnlivetv&plan=free
```
- Requires browser-like request with valid cookies/session
- Returns HTML with OPlayer JS bundle
- OPlayer loads `.m3u8` manifests via JS after page initialization
- Direct curl returns 401/403 (bot detection / Cloudflare challenge)
- **HTTP-only extraction (Dean Edwards packer decode) is unreliable** — use WebView as primary method

## Player Technology
- Uses **OPlayer** framework from CDN:
  - `cdn.jsdelivr.net/npm/@oplayer/core`
  - `cdn.jsdelivr.net/npm/@oplayer/ui`
  - `cdn.jsdelivr.net/npm/@oplayer/hls`
  - `cdn.jsdelivr.net/npm/@oplayer/plugins`
- Page body just says "Loading stream..." — everything is JS-driven
- Stream URLs constructed at runtime by JS — no static `.m3u8` in HTML source

## Three-Domain Architecture
This site uses a split architecture across three domains:
1. **api.cdnlivetv.ru** — Open JSON API for channel metadata (no protection)
2. **api.cdnlivetv.tv** — Open JSON API for sport events (no protection, different from .ru!)
3. **cdnlivetv.tv** — Player page, Cloudflare protection, OPlayer JS

The extension must:
- Fetch channel listings from the `.ru` API domain
- Fetch sport events from the `.tv` API domain (NOT from `mainUrl`)
- Construct player page URLs on the `.tv` domain
- Use WebView for stream extraction (player URLs only work in browser context)

## Stream Resolution Flow (Updated — WebView-First)

```
User taps channel
  → CDNLiveTV.load(playerUrl)
    → Parses ?name=XXX&code=YYY from URL
    → Fetches channel metadata from api.cdnlivetv.ru
    → Returns LoadResponse with title, poster, status + viewers
  → CDNLiveTV.loadLinks(playerUrl)
    → loadExtractor(playerUrl, referer="https://cdnlivetv.tv/")
      → Matched to CDNLiveTVExtractor by mainUrl
      → WebView loads player page (handles Cloudflare)
      → OPlayer JS initializes, requests .m3u8 from edge CDN
      → shouldInterceptRequest captures the first .m3u8 URL
      → Returns ExtractorLink to player
```

### Key: WebView is the PRIMARY method
Previous versions tried HTTP/JS-decode first and used WebView as fallback. Since the site hardened with Cloudflare, HTTP returns 401 consistently. WebView-first is now the correct approach.

## WebView Extraction Details
- WebView user-agent must be Firefox/Chrome desktop to trigger OPlayer's desktop stream variant
- Auto-play injection requires ~2-2.5s delay for OPlayer to initialize
- OPlayer play button selector: `[class*="o-player"] [class*="play"]`, `.o-icon-play`
- Fallback JS API: `window.player.play()` or generic `video.play()`
- Capture filter in `shouldInterceptRequest`:
  - URLs ending in `.m3u8`
  - URLs ending in `.ms3`
  - URLs matching `/secure/api/v1/...playlist`
- 20s timeout (Cloudflare may add initial delay)
- Destroy WebView within 500ms of capture to free resources

## Known Issues
- Channel name encoding: must use `java.net.URLEncoder.encode(name, "UTF-8")` for names with special characters like "México", "Éire", etc.
- WebView approach requires Android `Dispatchers.Main` — cannot be tested in WSL/headless environments
- Player URL returns Cloudflare challenge without proper cookies/session — WebView handles this transparently
- **Event detail URL design**: The catalog creates detail URLs like `https://cdnlivetv.tv/event/watch/{gameID}?sport={sport}`. The `?sport=` query param is essential — without it, `load()` and `loadLinks()` cannot determine which sport API to query to find the event. Never design detail URLs that lack the lookup keys needed to re-fetch the item.