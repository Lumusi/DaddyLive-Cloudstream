# PPV.to Extension - API Discovery Notes

## Status
- **Extension**: PPV.to (com.ppvto.api)
- **API Domain**: api.ppv.st
- **Status**: Working API discovered, embed extraction needs WebView

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
      "always_live": false,
      "streams": [
        {
          "id": 21301,
          "name": "Hamburg SV vs. SC Freiburg",
          "tag": "Bundesliga",
          "source_tag": "ESPN+",
          "poster": "https://api.ppv.st/assets/thumb/...",
          "uri_name": "bundesliga/2026-05-10/hsv-scf",
          "iframe": "https://pooembed.eu/embed/bundesliga/2026-05-10/hsv-scf",
          "always_live": 0,
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

## Implementation Notes

### Headers Required
- User-Agent: Browser-like
- Referer: https://ppv.to/
- Origin: https://ppv.to

### Stream Extraction
The embed page (pooembed.eu) uses heavy obfuscation with adware scripts.
Use Cloudstream's `loadExtractor()` for WebView-based extraction.

### Regional Restrictions
- PPV.to is blocked in UK due to Virgin Media court order
- VPN required for UK users
- Extension marked with `VPNStatus.MightBeNeeded`

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