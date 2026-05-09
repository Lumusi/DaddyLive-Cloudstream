# DaddyLive-Cloudstream

A Cloudstream3 extension module for streaming live sports from [daddylive.org](https://daddylive.org).

## Features

- Live sports events from daddylive.org
- HLS (m3u8) stream extraction via WebView
- Multi-channel support per event
- Search functionality
- Auto-resolving of stream URLs from embed pages

## Setup

1. Clone this repo
2. Make sure the `DaddyLive` folder is inside Cloudstream's modules directory, e.g.:
   ```
   /sdcard/Android/data/cloud.m3u.cf/files/modules/
   ```
3. Open the Cloudstream app and the provider should appear under **Live**

## How it works

- Fetches live event schedules from `https://daddylive.org/api/events`
- Builds embed URLs from channel data (`/embed/embed.php?id=...&player=1&source=...`)
- Uses a WebView to intercept HLS `.m3u8` stream URLs from the embedded player (embedsports.top)
- Streams are served as M3U8 HLS with browser-mimicking headers to avoid bot detection

## License

For personal use only. Respects the source website's terms of service.