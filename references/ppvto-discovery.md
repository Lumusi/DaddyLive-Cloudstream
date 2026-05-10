# PPV.to Extension - API Discovery Notes

## Status
- **Extension**: PPV.to (com.ppvto.api)
- **API Domain**: api.ppv.st
- **Status**: Complete - uses loadExtractor() for WebView extraction

## API Endpoints

### Streams API
```
GET https://api.ppv.st/api/streams
Headers:
  - User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)
  - Referer: https://ppv.to/
  - Accept: application/json
```

### Response Structure
```json
{
  "status": "success",
  "output": [
    {
      "category": "Football",
      "id": 42,
      "streams": [
        {
          "id": 21301,
          "name": "Hamburg SV vs. SC Freiburg",
          "tag": "Bundesliga",
          "poster": "https://api.ppv.st/assets/thumb/...",
          "uri_name": "bundesliga/2026-05-10/hsv-scf",
          "category_name": "Football"
        }
      ]
    }
  ]
}
```

## Categories
- American Football
- Baseball
- Basketball
- Combat Sports
- Cricket
- Darts
- Football
- Ice Hockey
- 24/7 Streams

## Stream URL Pattern
```
https://pooembed.eu/embed/{sport}/{date}/{match}
```

## Implementation

### Extraction Method
Uses `loadExtractor()` for WebView-based stream extraction. This is the standard Cloudstream3 pattern for:
- Sites with Cloudflare protection
- Obfuscated player pages
- Adware-heavy embed pages (pooembed.eu)

### Key Methods
- `getMainPage()` - Fetches streams from API
- `search()` - Filter by match/category
- `load()` - Load stream metadata
- `loadLinks()` - WebView extraction via loadExtractor()

### Headers
```kotlin
val headers = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
    "Accept" to "application/json",
    "Referer" to "https://ppv.to/"
)
```

### Regional Restrictions
- PPV.to is blocked in UK due to Virgin Media court order
- Extension marked with `VPNStatus.MightBeNeeded`
- Users need VPN for UK access

## File Structure
```
extensions/ppvto/
├── build.gradle.kts
├── src/main/kotlin/com/ppvto/api/
│   ├── PPVTO.kt          # Main API implementation
│   └── PPVTOPlugin.kt    # Plugin registration
```

## Build & Deploy
GitHub Actions builds .cs3 artifacts on push to main branch.
Artifacts pushed to 'builds' branch.