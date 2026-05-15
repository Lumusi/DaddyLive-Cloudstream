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
 * DamiTV (dami-tv.pro) — Free live sports & 24/7 channel streaming.
 *
 * Data:  /papi/matches/all  →  full enriched match objects with embedUrl, sources, posters
 * HLS:   /live-hls/channel/{id}/playlist.m3u8  →  direct HLS stream (works for live matches)
 * PPV:   /papi/stream/ppv/{id}  →  returns array of { embedUrl, language, hd, viewers }
 * FAWA:  /papi/fawa/stream/{id}  →  alternative source
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

        @Volatile private var cachedMatches: List<ApiMatch>? = null
        @Volatile private var cacheTimestamp: Long = 0

        private fun cacheValid() =
            cachedMatches != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS
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
        "afl" to "\uD83C\uDFC9",
        "24/7-streams" to "\uD83D\uDCFA"
    )

    // Categories to surface on the main page
    private val homeCategories = listOf(
        "_live" to "\uD83D\uDD34 Live Now",
        "24/7-streams" to "\uD83D\uDCFA 24/7 Channels",
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
        homeCategories.map { it.first to it.second }
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

    // ──────────────────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val matches = try { fetchAllMatches() } catch (_: Exception) { return emptyList() }
        return matches
            .filter { m ->
                (m.title?.lowercase()?.contains(q) == true) ||
                (m.teams?.home?.name?.lowercase()?.contains(q) == true) ||
                (m.teams?.away?.name?.lowercase()?.contains(q) == true) ||
                (m.league?.lowercase()?.contains(q) == true) ||
                (m.category?.lowercase()?.contains(q) == true)
            }
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ──────────────────────────────────────────────────────────────────────────
    // Load (Detail)
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val matchId = url.substringAfterLast("/").substringBefore("?")
            val matches = fetchAllMatches()
            val match = matches.find { it.id == matchId } ?: return null

            val title = matchTitle(match)
            val statusEmoji = if (isLive(match)) "\uD83D\uDD34 LIVE" else "\u23F3 Upcoming"

            val desc = buildString {
                appendLine("\uD83D\uDCCA Status: $statusEmoji")
                match.league?.let { appendLine("\uD83C\uDFC6 League: $it") }
                match.category?.let { appendLine("\uD83D\uDCCB Category: $it") }
                match.viewers?.let { if (it > 0) appendLine("\uD83D\uDC65 Viewers: $it") }
            }

            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = match.poster?.let { "https://streamed.pk$it" }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.substringAfterLast("/").substringBefore("?")
        val matches = try { fetchAllMatches() } catch (_: Exception) { return false }
        val match = matches.find { it.id == matchId } ?: return false

        var foundAny = false

        // Source 1: Direct HLS stream (best quality, ad-free)
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

        // Source 2: PPV stream endpoint (resolves to embed URL)
        if (!foundAny && match.sources?.isNotEmpty() == true) {
            for (src in match.sources) {
                try {
                    val streamUrl = "$API/stream/${src.source}/${src.id}"
                    val text = withContext(Dispatchers.IO) {
                        app.get(streamUrl, headers = headers).text
                    }
                    val mapper = jacksonObjectMapper()
                    val items: List<StreamItem> = mapper.readValue(text)
                    for (item in items) {
                        val embed = item.embedUrl ?: continue
                        val label = item.language?.takeIf { it.isNotBlank() } ?: "PPV"
                        val hd = if (item.hd == true) "HD" else ""
                        val name = buildString { append("PPV $label"); if (hd.isNotEmpty()) append(" $hd") }

                        // Use loadExtractor so the embed is parsed by Cloudstream
                        val wrapped = runBlocking {
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = embed,
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
                if (foundAny) break
            }
        }

        // Source 3: Direct embedUrl (if no sources array)
        if (!foundAny && match.embedUrl?.isNotBlank() == true) {
            try {
                val wrapped = runBlocking {
                    newExtractorLink(
                        source = name,
                        name = "Embed",
                        url = match.embedUrl,
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
            } catch (_: Exception) {}
        }

        return foundAny
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun ApiMatch.toSearchResponse(): LiveSearchResponse? {
        val title = matchTitle(this)
        val posterUrl = poster?.let { "https://streamed.pk$it" }
            ?: teams?.home?.badge?.takeIf { it.isNotBlank() }
        val detailUrl = "$mainUrl/event/${id ?: return null}"

        return newLiveSearchResponse(title, detailUrl, TvType.Live) {
            this.posterUrl = posterUrl
            if (isLive(this@toSearchResponse)) this.name += " \uD83D\uDD34"
        }
    }
}
