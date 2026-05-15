package com.damitv.api

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * DamiTV (dami-tv.pro) — Free live sports streaming.
 *
 * Architecture:
 * - Match data: 90min.90minutes.xyz/matches (public JSON API, 100 matches)
 * - Stream sources tried in order:
 *     1. HLS Live:  dami-tv.pro/live-hls/channel/{id}/playlist.m3u8
 *     2. FAWA:      dami-tv.pro/papi/fawa/stream/{id}  →  { success, stream }
 *     3. TFliX:     dami-tv.pro/tflix/find?home=...&away=...
 *     4. Generic:   dami-tv.pro/papi/stream/{source}/{id}
 * - All stream endpoints are same-origin, accessible via direct HTTP
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
        const val MATCHES_API = "https://90min.90minutes.xyz/matches"
        const val CACHE_TTL_MS = 3 * 60 * 1000L

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "application/json",
            "Referer" to "https://dami-tv.pro/"
        )

        @Volatile private var cachedMatches: List<Match>? = null
        @Volatile private var cacheTimestamp: Long = 0

        private fun cacheValid() =
            cachedMatches != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS
    }

    /** Sport slug set for "featured" top-level categories in the app */
    private val featuredSports = listOf(
        "football", "basketball", "american-football", "baseball",
        "fight", "cricket", "motor-sports", "rugby", "afl"
    )

    /** Sport slug → display label */
    private val sportLabels = mapOf(
        "football" to "⚽ Football",
        "basketball" to "🏀 Basketball",
        "american-football" to "🏈 American Football",
        "baseball" to "⚾ Baseball",
        "fight" to "🥊 MMA/Boxing",
        "cricket" to "🏏 Cricket",
        "motor-sports" to "🏎️ Motor Sports",
        "rugby" to "🏉 Rugby",
        "afl" to "🏉 AFL",
        "24/7-streams" to "📺 24/7 Streams",
        "autoracing" to "🏎️ Auto Racing",
        "tennis" to "🎾 Tennis",
        "cycling" to "🚴 Cycling",
        "darts" to "🎯 Darts",
        "mma" to "🥊 MMA"
    )

    override val mainPage = mainPageOf(
        // Featured sports as main page categories
        "cat_football" to "⚽ Football",
        "cat_basketball" to "🏀 Basketball",
        "cat_american-football" to "🏈 American Football",
        "cat_baseball" to "⚾ Baseball",
        "cat_fight" to "🥊 MMA/Boxing",
        "cat_cricket" to "🏏 Cricket",
        "cat_motor-sports" to "🏎️ Motor Sports",
        "cat_rugby" to "🏉 Rugby",
        "cat_afl" to "🏉 AFL",
        "cat_24/7-streams" to "📺 24/7 Streams",
        // Special: all live events
        "_live" to "🔴 Live Now",
        // Special: all matches sorted by time
        "_all" to "📅 All Events",
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Data Fetching
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun fetchAllMatches(): List<Match> {
        if (cacheValid()) return cachedMatches!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val text = withContext(Dispatchers.IO) {
            app.get(MATCHES_API, headers = headers).text
        }
        val response: MatchResponse = mapper.readValue(text)
        val matches = response.matches ?: emptyList()
        cachedMatches = matches
        cacheTimestamp = System.currentTimeMillis()
        return matches
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main Page
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            when {
                request.data == "_live" -> getLiveEvents()
                request.data == "_all" -> getAllEvents()
                request.data.startsWith("cat_") -> {
                    val sport = request.data.removePrefix("cat_")
                    getEventsBySport(sport)
                }
                else -> newHomePageResponse(list = mutableListOf(), hasNext = false)
            }
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    private suspend fun getLiveEvents(): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val all = fetchAllMatches()
            val liveEvents = all.filter { it.status == "live" && it.home?.isNotBlank() == true }
            if (liveEvents.isNotEmpty()) {
                items.add(HomePageList(
                    "🔴 Live Now",
                    liveEvents.mapNotNull { it.toSearchResponse() },
                    isHorizontalImages = false
                ))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getAllEvents(): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val all = fetchAllMatches().filter { it.home?.isNotBlank() == true }
            if (all.isNotEmpty()) {
                items.add(HomePageList(
                    "📅 All Events",
                    all.mapNotNull { it.toSearchResponse() },
                    isHorizontalImages = false
                ))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getEventsBySport(sportSlug: String): HomePageResponse {
        val label = sportLabels[sportSlug] ?: sportSlug.replaceFirstChar { it.uppercase() }
        val items = mutableListOf<HomePageList>()
        try {
            val all = fetchAllMatches()
            val filtered = all
                .filter { it.sport == sportSlug && it.home?.isNotBlank() == true }
                .sortedWith(
                    compareByDescending<Match> { it.status == "live" }
                        .thenBy { it.startTime ?: "" }
                )

            if (filtered.isNotEmpty()) {
                items.add(HomePageList(
                    label,
                    filtered.mapNotNull { it.toSearchResponse() },
                    isHorizontalImages = false
                ))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val matches = try {
            fetchAllMatches()
        } catch (_: Exception) { return emptyList() }

        return matches
            .filter { m ->
                (m.name?.lowercase()?.contains(q) == true) ||
                (m.home?.lowercase()?.contains(q) == true) ||
                (m.away?.lowercase()?.contains(q) == true) ||
                (m.league?.lowercase()?.contains(q) == true)
            }
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ──────────────────────────────────────────────────────────────────────────
    // Load (Match Detail)
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val matchId = url.substringAfterLast("/").substringBefore("?")
            val matches = fetchAllMatches()
            val match = matches.find { it.id == matchId } ?: return null

            val title = "${match.home ?: "?"} vs ${match.away ?: "?"}"
            val statusEmoji = when (match.status) {
                "live" -> "🔴 LIVE"
                else -> "⏳ ${match.status?.replaceFirstChar { it.uppercase() } ?: "Upcoming"}"
            }

            val desc = buildString {
                appendLine("📊 Status: $statusEmoji")
                match.league?.let { appendLine("🏆 League: $it") }
                match.scores?.let { s ->
                    if (s.homeScore != null || s.awayScore != null) {
                        appendLine("⚽ Score: ${s.homeScore ?: "?"} - ${s.awayScore ?: "?"}")
                    }
                    s.halfTime?.let { if (it != "0-0") appendLine("⏱️ HT: $it") }
                }
            }

            val posterUrl = match.homeLogo?.takeIf { it.isNotBlank() }
                ?: match.leagueLogo?.takeIf { it.isNotBlank() }

            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = posterUrl
                this.plot = desc.trim()
                this.tags = listOfNotNull(match.sport, match.league, match.status)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Link Extraction
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.substringAfterLast("/").substringBefore("?")
        val matches = try { fetchAllMatches() } catch (_: Exception) { return false }
        val match = matches.find { it.id == matchId } ?: return false
        val isLive = match.status == "live"

        var foundAny = false

        // Source 1: HLS Live (direct m3u8, only for live matches)
        if (isLive) {
            val hlsUrl = "$mainUrl/live-hls/channel/$matchId/playlist.m3u8"
            try {
                val check = withContext(Dispatchers.IO) {
                    app.get(hlsUrl, headers = headers).text
                }
                if (check.isNotBlank() && check.contains("#EXT")) {
                    val wrapped = runBlocking {
                        newExtractorLink(
                            source = name,
                            name = "HD HLS",
                            url = hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to headers["User-Agent"]!!,
                                "Referer" to mainUrl
                            )
                        }
                    }
                    callback(wrapped)
                    foundAny = true
                }
            } catch (_: Exception) {}
        }

        // Source 2: FAWA stream (JSON endpoint)
        if (!foundAny) {
            try {
                val fawaUrl = "$mainUrl/papi/fawa/stream/$matchId"
                val text = withContext(Dispatchers.IO) {
                    app.get(fawaUrl, headers = headers).text
                }
                val mapper = jacksonObjectMapper()
                val fawaRes = mapper.readValue<StreamResponse>(text)
                if (fawaRes.success == true && fawaRes.stream?.isNotBlank() == true) {
                    val wrapped = runBlocking {
                        newExtractorLink(
                            source = name,
                            name = "FAWA",
                            url = fawaRes.stream,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to headers["User-Agent"]!!,
                                "Referer" to mainUrl
                            )
                        }
                    }
                    callback(wrapped)
                    foundAny = true
                }
            } catch (_: Exception) {}
        }

        // Source 3: TFliX search (name-based lookup)
        if (!foundAny) {
            try {
                val home = java.net.URLEncoder.encode(match.home ?: "", "UTF-8")
                val away = java.net.URLEncoder.encode(match.away ?: "", "UTF-8")
                val cat = java.net.URLEncoder.encode(match.sport ?: "", "UTF-8")
                val tflixUrl = "$mainUrl/tflix/find?home=$home&away=$away&category=$cat"
                val text = withContext(Dispatchers.IO) {
                    app.get(tflixUrl, headers = headers).text
                }
                val mapper = jacksonObjectMapper()
                val tflixData = mapper.readValue<Map<String, Any>>(text)
                if (tflixData["found"] == true) {
                    @Suppress("UNCHECKED_CAST")
                    val sources = tflixData["sources"] as? List<Map<String, Any>> ?: emptyList()
                    for ((i, src) in sources.withIndex()) {
                        val playUrl = (src["playUrl"] as? String) ?: continue
                        val fullUrl = if (playUrl.startsWith("http")) playUrl
                            else "$mainUrl$playUrl"
                        val label = (src["name"] as? String) ?: "Source ${i + 1}"
                        val wrapped = runBlocking {
                            newExtractorLink(
                                source = name,
                                name = "TFliX $label",
                                url = fullUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to headers["User-Agent"]!!,
                                    "Referer" to mainUrl
                                )
                            }
                        }
                        callback(wrapped)
                        foundAny = true
                    }
                }
            } catch (_: Exception) {}
        }

        // Source 4: Generic stream endpoint (try common sources)
        if (!foundAny) {
            for (source in listOf("fawa", "hls-live", "ppv-embed", "90sport")) {
                try {
                    val streamUrl = "$mainUrl/papi/stream/$source/$matchId"
                    val text = withContext(Dispatchers.IO) {
                        app.get(streamUrl, headers = headers).text
                    }
                    if (text.isNotBlank() && text != "[]" && text != "{}") {
                        val mapper = jacksonObjectMapper()
                        val data = mapper.readValue<List<Map<String, Any>>>(text)
                        for (item in data) {
                            val url = (item["url"] as? String) ?: continue
                            val label = (item["label"] as? String) ?: source
                            val wrapped = runBlocking {
                                newExtractorLink(
                                    source = name,
                                    name = label,
                                    url = url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "User-Agent" to headers["User-Agent"]!!,
                                        "Referer" to mainUrl
                                    )
                                }
                            }
                            callback(wrapped)
                            foundAny = true
                        }
                    }
                } catch (_: Exception) {}
                if (foundAny) break
            }
        }

        return foundAny
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Convert a Match object into a Cloudstream SearchResponse */
    private fun Match.toSearchResponse(): LiveSearchResponse? {
        val h = home ?: return null
        val a = away ?: return null
        val title = buildString {
            append("$h vs $a")
            scores?.let { s ->
                if (s.homeScore != null || s.awayScore != null) {
                    append(" (${s.homeScore ?: "?"}-${s.awayScore ?: "?"})")
                }
            }
            if (status == "live") append(" 🔴")
        }
        val detailUrl = "$mainUrl/event/${id ?: return null}"

        return newLiveSearchResponse(title, detailUrl, TvType.Live) {
            this.posterUrl = homeLogo?.takeIf { it.isNotBlank() }
                ?: leagueLogo?.takeIf { it.isNotBlank() }
        }
    }
}
