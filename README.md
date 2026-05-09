# SportCDN-Cloudstream

Multi-extension Cloudstream3 provider monorepo for live sports streaming.

## Extensions

| Extension | Package | Source |
|-----------|---------|--------|
| **DaddyLive** | `com.daddylive` | [daddylive.org](https://daddylive.org) |
| **SportsBite** | `com.sportscdnext` | [livetv.moviebite.cc](https://livetv.moviebite.cc) |

## Structure

```
extensions/
├── daddylive/          # DaddyLive scraper (daddylive.org)
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── kotlin/com/daddylive/
│   │       ├── DaddyLivePlugin.kt
│   │       ├── DaddyLive.kt
│   │       └── DaddyLiveExtractor.kt
└── sportscdnext/       # SportsBite scraper (livetv.moviebite.cc)
    ├── build.gradle.kts
    ├── src/main/
    │   ├── AndroidManifest.xml
    │   └── kotlin/com/sportscdnext/
    │       ├── SportsBitePlugin.kt
    │       ├── SportsBite.kt
    │       └── SportsBiteExtractor.kt
```

## Adding a New Extension

1. Create `extensions/<name>/` with `build.gradle.kts`, `AndroidManifest.xml`, and Kotlin sources
2. Set `status = 1` in `cloudstream {}` block to enable (or `0` to disable without deleting)
3. Run `./gradlew make makePluginsJson` — `settings.gradle.kts` auto-discovers new modules
4. Commit and CI builds all enabled extensions automatically

## Build

```bash
./gradlew make makePluginsJson
```

## Setup

- `.cs3` artifacts are generated per extension in `extensions/*/build/`
- `plugins.json` and `repo.json` are updated by CI on the `builds` branch
- Import the repo URL in the Cloudstream3 app to install extensions

## License

For personal use only. Respects the source websites' terms of service.