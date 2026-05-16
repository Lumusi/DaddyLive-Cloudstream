package com.damitv.api

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DamiTV (dami-tv.pro) — Free live sports & 24/7 channel streaming.
 *
 * Both channels and events return direct M3U8 URLs (no WebView needed):
 *   Channels: https://dami-tv.pro/cdn-stream/{name}
 *   Events:   https://dami-tv.pro/live-hls/channel/{matchId}/playlist.m3u8
 *
 * Both require Referer: https://dami-tv.pro/ but no Cloudflare session.
 * CDNLiveTV needs WebView extraction because its streams are behind
 * Cloudflare. DamiTV doesn't have that, so we return direct links.
 */
class DamiTV : MainAPI() {
    override var mainUrl = "https://dami-tv.pro"
    override var name = "DamiTV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private companion object {
        const val API = "https://dami-tv.pro/papi"
        const val CACHE_TTL_MS = 3 * 60 * 1000L

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "application/json",
            "Referer" to "https://dami-tv.pro/"
        )

        val posterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Referer" to "https://dami-tv.pro/"
        )

        /** Resolve poster URL: relative paths use dami-tv.pro, absolute pass through. */
        private fun resolvePoster(poster: String?): String? {
            if (poster.isNullOrBlank()) return null
            if (poster.startsWith("http")) return poster
            return "https://dami-tv.pro$poster"
        }

        @Volatile private var cachedMatches: List<ApiMatch>? = null
        @Volatile private var cacheTimestamp: Long = 0

        private fun cacheValid() =
            cachedMatches != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS

        @Volatile private var cachedChannels: List<ApiChannel>? = null
        @Volatile private var channelCacheTimestamp: Long = 0

        private fun channelCacheValid() =
            cachedChannels != null && (System.currentTimeMillis() - channelCacheTimestamp) < CACHE_TTL_MS
    }

    private val categoryIcons = mapOf(
        "football" to "\u26BD",
        "basketball" to "\uD83C\uDFC0",
        "american-football" to "\uD83C\uDFC8",
        "baseball" to "\u26BE",
        "fight" to "\uD83E\uDD4A",
        "cricket" to "\uD83C\uDFCF",
        "motor-sports" to "\uD83C\uDFCE\uFE0F",
        "rugby" to "\uD83C\uDFC9",
        "afl" to "\uD83C\uDFC9"
    )

    // Categories to surface on the main page
    private val homeCategories = listOf(
        "_live" to "\uD83D\uDD34 Live Now",
        "_livetv" to "\uD83D\uDCFA Live TV",
        "football" to "\u26BD Football",
        "basketball" to "\uD83C\uDFC0 Basketball",
        "american-football" to "\uD83C\uDFC8 American Football",
        "baseball" to "\u26BE Baseball",
        "fight" to "\uD83E\uDD4A MMA/Boxing",
        "cricket" to "\uD83C\uDFCF Cricket",
        "motor-sports" to "\uD83C\uDFCE\uFE0F Motor Sports",
        "rugby" to "\uD83C\uDFC9 Rugby",
        "afl" to "\uD83C\uDFC9 AFL",
    )

    override val mainPage = mainPageOf(
        *homeCategories.map { it.first to it.second }.toTypedArray()
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Data
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun fetchAllMatches(): List<ApiMatch> {
        if (cacheValid()) return cachedMatches!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val text = withContext(Dispatchers.IO) {
            app.get("$API/matches/all", headers = headers).text
        }
        val matches: List<ApiMatch> = mapper.readValue(text)
        cachedMatches = matches
        cacheTimestamp = System.currentTimeMillis()
        return matches
    }

    private fun isLive(m: ApiMatch): Boolean =
        m.status == "live" || (m.date != null && m.date <= System.currentTimeMillis())

    private fun matchTitle(m: ApiMatch): String {
        return m.title ?: if (m.teams?.home?.name != null || m.teams?.away?.name != null) {
            "${m.teams.home?.name ?: "?"} vs ${m.teams.away?.name ?: "?"}"
        } else m.league ?: m.category ?: "Unknown"
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main Page
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            when (request.data) {
                "_live" -> buildLivePage()
                "_livetv" -> buildLiveTVPage()
                "_all" -> buildCategoryPage(null)
                else -> buildCategoryPage(request.data)
            }
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    private suspend fun buildLivePage(): HomePageResponse {
        val all = fetchAllMatches()
        val live = all.filter { isLive(it) }
        val items = mutableListOf<HomePageList>()

        if (live.isNotEmpty()) {
            // Group live events by category
            val byCat = live.groupBy { it.category ?: "other" }
            for ((cat, matches) in byCat) {
                val label = categoryIcons[cat]?.let { "$it $cat" } ?: cat
                items.add(HomePageList(
                    label,
                    matches.mapNotNull { it.toSearchResponse() },
                    isHorizontalImages = true
                ))
            }
        }
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun buildCategoryPage(category: String?): HomePageResponse {
        val all = fetchAllMatches()
        val filtered = if (category != null) all.filter { it.category == category }
                        else all

        val items = mutableListOf<HomePageList>()

        // Live events first
        val live = filtered.filter { isLive(it) }
        if (live.isNotEmpty()) {
            items.add(HomePageList(
                "\uD83D\uDD34 Live",
                live.mapNotNull { it.toSearchResponse() },
                isHorizontalImages = true
            ))
        }

        // Upcoming events
        val upcoming = filtered.filter { !isLive(it) }
        if (upcoming.isNotEmpty()) {
            val label = if (category != null)
                "${categoryIcons[category] ?: ""} Upcoming"
            else "All Events"

            items.add(HomePageList(
                label,
                upcoming.mapNotNull { it.toSearchResponse() },
                isHorizontalImages = false
            ))
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    // ── Live TV Channels ──────────────────────────────────────────────────────

    private suspend fun fetchChannels(): List<ApiChannel> {
        if (channelCacheValid()) return cachedChannels!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val text = withContext(Dispatchers.IO) {
            app.get("$mainUrl/channels.json", headers = headers).text
        }
        val wrapper: ApiChannelWrapper = mapper.readValue(text)
        val channels = wrapper.channels ?: emptyList()
        cachedChannels = channels
        channelCacheTimestamp = System.currentTimeMillis()
        return channels
    }

    private suspend fun buildLiveTVPage(): HomePageResponse {
        val channels = fetchChannels()
        // Group by country, sort by count descending
        val grouped = channels.groupBy { it.country?.code ?: "intl" }
            .mapValues { (_, chs) -> chs.sortedBy { it.name } }
        val sorted = grouped.entries.sortedByDescending { (_, chs) -> chs.size }

        val items = sorted.map { (code, chs) ->
            val first = chs.first()
            val flag = first.country?.flag ?: "\uD83C\uDF0D"
            val name = first.country?.name ?: code.uppercase()
            HomePageList(
                "$flag $name (${chs.size})",
                chs.mapNotNull { it.toChannelSearchResponse() },
                isHorizontalImages = true
            )
        }
        return newHomePageResponse(list = items, hasNext = false)
    }

    private fun ApiChannel.toChannelSearchResponse(): LiveSearchResponse? {
        val channelId = id ?: name ?: return null
        val detailUrl = "$mainUrl/channel/${name?.replace(" ", "%20")}"
        return newLiveSearchResponse(name ?: "Channel", detailUrl, TvType.Live) {
            this.posterUrl = logo
            this.posterHeaders = posterHeaders
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val matches = try { fetchAllMatches() } catch (_: Exception) { emptyList() }

        // Search both matches and channels
        val matchResults = matches
            .filter { m ->
                (m.title?.lowercase()?.contains(q) == true) ||
                (m.teams?.home?.name?.lowercase()?.contains(q) == true) ||
                (m.teams?.away?.name?.lowercase()?.contains(q) == true) ||
                (m.league?.lowercase()?.contains(q) == true) ||
                (m.category?.lowercase()?.contains(q) == true)
            }
            .mapNotNull { it.toSearchResponse() }

        val channels = try { fetchChannels() } catch (_: Exception) { emptyList() }
        val channelResults = channels
            .filter { it.name?.lowercase()?.contains(q) == true }
            .mapNotNull { it.toChannelSearchResponse() }

        return matchResults + channelResults
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ──────────────────────────────────────────────────────────────────────────
    // Load (Detail)
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return try {
            // ── Channel detail ────────────────────────────────────────────────
            if (url.contains("$mainUrl/channel/")) {
                val channelName = url.removePrefix("$mainUrl/channel/")
                    .replace("%20", " ")
                    .substringBefore("?").substringBefore("#")
                val channels = fetchChannels()
                val channel = channels.find {
                    it.name.equals(channelName, ignoreCase = true) || it.id == channelName
                } ?: return null

                val flag = channel.country?.flag ?: ""
                val countryName = channel.country?.name ?: channel.country?.code?.uppercase() ?: "Live TV"
                val sourceName = channel.source ?: ""
                val desc = buildString {
                    appendLine("\uD83C\uDF0D Country: $flag $countryName")
                    if (sourceName.isNotBlank()) appendLine("\uD83D\uDCCD Source: $sourceName")
                    channel.defaultQuality?.let { appendLine("\uD83C\uDFA8 Quality: $it") }
                    channel.viewers?.let { if (it > 0) appendLine("\uD83D\uDC65 Viewers: $it") }
                }

                return newMovieLoadResponse(channel.name ?: "Channel", url, TvType.Live, url) {
                    this.posterUrl = channel.logo
                    this.posterHeaders = posterHeaders
                    this.plot = desc.trim()
                    this.tags = listOfNotNull(countryName, sourceName, channel.defaultQuality)
                }
            }

            // ── Event detail ──────────────────────────────────────────────────
            val matchId = url.removePrefix("$mainUrl/event/").substringBefore("?").substringBefore("#")
            val matches = fetchAllMatches()
            val match = matches.find { it.id == matchId } ?: return null

            val title = matchTitle(match)
            val statusEmoji = if (isLive(match)) "\uD83D\uDD34 LIVE" else "\u23F3 Upcoming"

            val desc = buildString {
                appendLine("\uD83D\uDCCA Status: $statusEmoji")
                match.league?.let { appendLine("\uD83C\uDFC6 League: $it") }
                match.category?.let { appendLine("\uD83D\uDCCB Category: $it") }
                match.viewers?.let { if (it > 0) appendLine("\uD83D\uDC65 Viewers: $it") }
                match.sources?.let { sources ->
                    if (sources.size > 1) {
                        appendLine("\uD83D\uDCE1 Sources: ${sources.size} available")
                    }
                }
            }

            // Pass embedUrl as dataUrl for loadLinks (same pattern as CDNLiveTV)
            val dataUrl = match.embedUrl?.takeIf { it.isNotBlank() }
                ?: "$mainUrl/event/$matchId"

            newMovieLoadResponse(title, url, TvType.Live, dataUrl) {
                this.posterUrl = resolvePoster(match.poster)
                this.posterHeaders = posterHeaders
                this.plot = desc.trim()
                this.tags = listOfNotNull(match.category, match.league, match.status)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Links
    // ──────────────────────────────────────────────────────────────────────────
    //
    // Both channels and events return direct M3U8 URLs (no WebView needed):
    //   Channels: https://dami-tv.pro/cdn-stream/{name}
    //   Events:   https://dami-tv.pro/live-hls/channel/{matchId}/playlist.m3u8
    //
    // Both require Referer: https://dami-tv.pro/ but no Cloudflare session.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ── Channel stream ────────────────────────────────────────────────────
        if (data.contains("$mainUrl/channel/")) {
            val channelName = data.removePrefix("$mainUrl/channel/")
                .replace("%20", " ")
                .substringBefore("?").substringBefore("#")
            val channels = try { fetchChannels() } catch (_: Exception) { return false }
            val channel = channels.find {
                it.name.equals(channelName, ignoreCase = true) || it.id == channelName
            } ?: return false

            // Try quality variants first, then default
            val qualityUrls = mutableListOf<Pair<String, String>>()
            channel.qualities?.forEach { q ->
                if (!q.url.isNullOrBlank() && !q.quality.isNullOrBlank()) {
                    qualityUrls.add(q.quality to q.url)
                }
            }
            // Add default as fallback
            val defaultUrl = channel.defaultUrl
                ?: "$mainUrl/cdn-stream/${channel.name?.replace(" ", "%20")}"
            if (defaultUrl != null) {
                qualityUrls.add((channel.defaultQuality ?: "SD") to defaultUrl)
            }

            var foundAny = false
            for ((qualityLabel, streamUrl) in qualityUrls) {
                val encodedUrl = java.net.URLEncoder.encode(streamUrl.removePrefix("$mainUrl"), "UTF-8")
                val playerReferer = "$mainUrl/player/hls/?v=244&url=$encodedUrl&name=Live"

                try {
                    val wrapped = newExtractorLink(
                        source = name,
                        name = "$qualityLabel ${channel.name ?: "Channel"}",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerReferer
                        this.quality = when (qualityLabel.uppercase()) {
                            "HD", "720" -> 720
                            "FHD", "1080" -> 1080
                            "4K", "2160" -> 2160
                            "SD", "480" -> 480
                            else -> Qualities.Unknown.value
                        }
                    }
                    callback(wrapped)
                    foundAny = true
                } catch (_: Exception) { /* skip failed quality */ }
            }
            return foundAny
        }

        // ── Event stream ──────────────────────────────────────────────────────
        // Extract matchId from either URL format
        val matchId = if (data.contains("$mainUrl/event/")) {
            data.removePrefix("$mainUrl/event/").substringBefore("?").substringBefore("#")
        } else if (data.startsWith("https://pooembed.eu/embed/")) {
            data.removePrefix("https://pooembed.eu/embed/").substringBefore("?").substringBefore("#")
        } else {
            return false
        }

        // Fetch match to check for multiple sources
        val matches = try { fetchAllMatches() } catch (_: Exception) { return false }
        val match = matches.find { it.id == matchId }

        // If multiple sources exist, iterate through each (same pattern as CDNLiveTV)
        val sources = match?.sources?.filter { it.id?.isNotBlank() == true }
        if (!sources.isNullOrEmpty()) {
            var foundAny = false
            for (src in sources) {
                val sourceLabel = src.source ?: "Source"
                val sourceId = src.id!!
                val sourceStreamUrl = "$mainUrl/live-hls/channel/$sourceId/playlist.m3u8"
                val encodedUrl = java.net.URLEncoder.encode("/live-hls/channel/$sourceId/playlist.m3u8", "UTF-8")
                val playerReferer = "$mainUrl/player/hls/?v=244&url=$encodedUrl&name=Live"

                try {
                    val wrapped = newExtractorLink(
                        source = "$name [$sourceLabel]",
                        name = "$sourceLabel HLS",
                        url = sourceStreamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerReferer
                        this.quality = Qualities.Unknown.value
                    }
                    callback(wrapped)
                    foundAny = true
                } catch (_: Exception) { /* skip failed source */ }
            }
            if (foundAny) return true
        }

        // Single source: use the primary matchId
        val streamUrl = "$mainUrl/live-hls/channel/$matchId/playlist.m3u8"
        val encodedUrl = java.net.URLEncoder.encode("/live-hls/channel/$matchId/playlist.m3u8", "UTF-8")
        val playerReferer = "$mainUrl/player/hls/?v=244&url=$encodedUrl&name=Live"

        try {
            val wrapped = newExtractorLink(
                source = name,
                name = "Event HLS",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = playerReferer
                this.quality = Qualities.Unknown.value
            }
            callback(wrapped)
            return true
        } catch (_: Exception) {
            // Fallback to WebView extractor
        }

        if (data.startsWith("https://pooembed.eu/embed/")) {
            try {
                loadExtractor(
                    url = data,
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                return true
            } catch (_: Exception) {
                return false
            }
        }

        return false
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun ApiMatch.toSearchResponse(): LiveSearchResponse? {
        val title = matchTitle(this)
        val posterUrl = resolvePoster(poster)
            ?: teams?.home?.badge?.takeIf { it.isNotBlank() }
        val detailUrl = "$mainUrl/event/${id ?: return null}"

        val displayTitle = if (isLive(this@toSearchResponse)) "$title \uD83D\uDD34" else title
        return newLiveSearchResponse(displayTitle, detailUrl, TvType.Live) {
            this.posterUrl = posterUrl
            this.posterHeaders = posterHeaders
        }
    }
}
