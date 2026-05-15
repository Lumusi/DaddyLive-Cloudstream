# CDNLiveTV Cloudstream Extension

Cloudstream3 extension for [cdnlivetv.tv](https://cdnlivetv.tv) — live sports from 762+ global channels across 38 countries.

## Features

- **📺 All Channels** — Browse all 762+ channels sorted by country (US, UK, Spain, Germany, Australia, Brazil, +31 more)
- **🔴 Live Now** — Channels that are currently online with active viewers
- **⚽ Sport Events** — Live & upcoming events by sport (Soccer, Basketball, Tennis, Hockey, MMA, Cricket, Golf, Motorsport, Handball, Volleyball, Cycling, Darts)
- **🔍 Search** — Find channels by name across all countries
- **🌍 Multi-source** — When available, see the same channel/event from different country broadcasts

## Install

1. Install Cloudstream3 from [recloudstream.github.io](https://recloudstream.github.io/csdocs/)
2. Settings → Extensions → Add Repository
3. Enter: `https://raw.githubusercontent.com/Lumusi/DaddyLive-Cloudstream/builds/repo.json`
4. Install the **CDNLiveTV** extension from the repository

## How It Works

The extension uses two API domains (unprotected JSON) and a player page (Cloudflare-protected):

1. **Channel list** — `api.cdnlivetv.ru` returns channel metadata (name, country, status, viewers)
2. **Sport events** — `api.cdnlivetv.tv` returns live/upcoming match listings with available broadcast sources
3. **Player page** — `cdnlivetv.tv` hosts OPlayer-based streams, extracted via WebView for .m3u8 capture

## Build

```bash
./gradlew make makePluginsJson
```

Artifacts are in `extensions/cdnlivetv/build/` and auto-deployed via CI on push to `main`.

## Architecture

```
extensions/cdnlivetv/
├── build.gradle.kts              # Cloudstream extension metadata
└── src/main/
    ├── AndroidManifest.xml
    └── kotlin/com/cdnlivetv/api/
        ├── CDNLiveTVPlugin.kt    # @CloudstreamPlugin entry point
        ├── CDNLiveTV.kt          # MainAPI — search, browse, load events/channels, link resolution
        ├── CDNLiveTVExtractor.kt # ExtractorApi — WebView-based .m3u8 capture
        └── EventsAPI.kt          # Data models for sport events
```
