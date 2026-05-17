# CDNLiveTV, DamiTV & AIOLive Cloudstream Extensions

Multi-extension Cloudstream3 repository for live sports streaming.

## Extensions

| Extension | Package | Source | Type |
|-----------|---------|--------|------|
| **AIOLive** | `com.aiolive` | [dami-tv.pro](https://dami-tv.pro) + [cdnlivetv.tv](https://cdnlivetv.tv) | All-in-one: DamiTV events + CDNLiveTV channels |
| **CDNLiveTV** | `com.cdnlivetv` | [cdnlivetv.tv](https://cdnlivetv.tv) | 762+ global channels, sport events |
| **DamiTV** | `com.damitv` | [dami-tv.pro](https://dami-tv.pro) | NFL, NBA, Premier League, UFC, MLB, NHL + more |

### AIOLive

- **\uD83D\uDD34 DamiTV Live Now** — All currently live DamiTV events grouped by sport
- **\uD83D\uDCFA DamiTV Live TV** — Live TV channels from DamiTV grouped by country
- **\uD83D\uDD34 CDNLiveTV Live Now** — Channels currently online with active viewers
- **\uD83D\uDCFA CDNLiveTV All Channels** — Browse 762+ channels across 38 countries
- **\uD83C\uDF0D Country Channels** — US, UK, Spain, Germany, Australia, Brazil and more
- **\u26BD Sport Events** — Live & upcoming events by sport (Soccer, Basketball, Tennis, Hockey, MMA, Cricket, Golf, Motorsport, +more)
- **\uD83D\uDD0D Unified Search** — Search across both DamiTV and CDNLiveTV sources
- **\uD83D\uDD04 Dual-source** — DamiTV events use direct HLS (fast), CDNLiveTV uses WebView extraction

### CDNLiveTV

- **📺 All Channels** — Browse 762+ channels sorted by country (US, UK, Spain, Germany, Australia, Brazil, +31 more)
- **🔴 Live Now** — Channels currently online with active viewers
- **⚽ Sport Events** — Live & upcoming events by sport (Soccer, Basketball, Tennis, Hockey, MMA, Cricket, Golf, Motorsport, Handball, Volleyball, Cycling, Darts)
- **🌍 Multi-source** — Same channel/event from different country broadcasts
- **🔍 Search** — Find channels by name across all countries

### DamiTV

- **⚽ Football** — Premier League, La Liga, Bundesliga, Serie A, Champions League
- **🏀 Basketball** — NBA, EuroLeague, international
- **🏈 American Football** — NFL, college
- **⚾ Baseball** — MLB
- **🥊 MMA/Boxing** — UFC, boxing events
- **🏏 Cricket** — International & league matches
- **🏎️ Motor Sports** — F1, MotoGP, rally
- **🏉 Rugby** — International, NRL, Super Rugby
- **🔴 Live Now** — All currently live events in one view
- **📅 All Events** — Browse all upcoming matches

## Install

1. Install Cloudstream3 from [recloudstream.github.io](https://recloudstream.github.io/csdocs/)
2. Settings → Extensions → Add Repository
3. Enter: `https://raw.githubusercontent.com/Lumusi/DaddyLive-Cloudstream/main/repo.json`
4. Install the desired extension(s) from the repository

## Build

```bash
./gradlew make makePluginsJson
```

Artifacts in each `extensions/*/build/` directory. CI auto-builds on push to `main`.

## Repository Structure

```
extensions/
├── aiolive/             # AIOLive extension (All-in-One)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/aiolive/api/
│       ├── AIOLivePlugin.kt
│       ├── AIOLive.kt             # MainAPI (unified search, browse, load, links)
│       ├── AIOLiveExtractor.kt    # ExtractorApi (WebView-based)
│       └── Models.kt              # Merged data models
├── cdnlivetv/           # CDNLiveTV extension
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/cdnlivetv/api/
│       ├── CDNLiveTVPlugin.kt
│       ├── CDNLiveTV.kt          # MainAPI (search, browse, load, links)
│       ├── CDNLiveTVExtractor.kt # ExtractorApi (WebView-based)
│       └── EventsAPI.kt          # Event data models
└── damitv/              # DamiTV extension
    ├── build.gradle.kts
    └── src/main/kotlin/com/damitv/api/
        ├── DamiTVPlugin.kt
        ├── DamiTV.kt             # MainAPI (search, browse, load, links)
        └── Models.kt             # Match & stream data models
```

## How CDNLiveTV Works

1. **Channel list** — `api.cdnlivetv.ru` returns channel metadata (name, country, status, viewers)
2. **Sport events** — `api.cdnlivetv.tv` returns live/upcoming match listings with broadcast sources
3. **Player page** — `cdnlivetv.tv` hosts OPlayer-based streams, extracted via WebView (Cloudflare)

## How DamiTV Works

1. **Match data** — `90min.90minutes.xyz/matches` returns 100 upcoming/live matches
2. **Stream resolution** — Tries multiple sources in order: HLS direct → FAWA → TFliX → generic
3. **Playback** — All stream endpoints are same-origin, accessible via direct HTTP
