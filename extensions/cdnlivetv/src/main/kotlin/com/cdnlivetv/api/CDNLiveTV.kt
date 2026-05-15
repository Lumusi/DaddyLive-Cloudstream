package com.cdnlivetv.api

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CDNLiveTV (cdnlivetv.tv / api.cdnlivetv.ru) — Live sports streaming provider.
 *
 * Architecture:
 * - Channels API: api.cdnlivetv.ru (unprotected JSON, 762+ channels, 38 countries)
 * - Events API:  api.cdnlivetv.tv (unprotected JSON, live/upcoming events)
 * - Player URL:  cdnlivetv.tv (Cloudflare-protected, OPlayer-based, uses WebView)
 */
class CDNLiveTV : MainAPI() {
    override var mainUrl = "https://api.cdnlivetv.ru/api/v1"
    override var name = "CDNLiveTV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.MightBeNeeded

    companion object {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "application/json, text/html, */*; q=0.01",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "https://cdnlivetv.tv/"
        )
        val posterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Referer" to "https://cdnlivetv.tv/"
        )

        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val EVENTS_BASE_URL = "https://api.cdnlivetv.tv/api/v1"

        @Volatile private var cachedChannels: List<ChannelData>? = null
        @Volatile private var channelCacheTimestamp: Long = 0
        @Volatile private var cachedEvents: Map<String, List<Event>>? = null
        @Volatile private var eventsCacheTimestamp: Long = 0

        private fun channelCacheValid() =
            cachedChannels != null && (System.currentTimeMillis() - channelCacheTimestamp) < CACHE_TTL_MS

        private fun eventsCacheValid() =
            cachedEvents != null && (System.currentTimeMillis() - eventsCacheTimestamp) < CACHE_TTL_MS
    }

    /** Country code → human-readable label */
    private val codeNames = mapOf(
        "us" to "🇺🇸 United States", "gb" to "🇬🇧 United Kingdom",
        "es" to "🇪🇸 Spain", "de" to "🇩🇪 Germany", "au" to "🇦🇺 Australia",
        "fr" to "🇫🇷 France", "it" to "🇮🇹 Italy", "br" to "🇧🇷 Brazil",
        "pl" to "🇵🇱 Poland", "za" to "🇿🇦 South Africa", "gr" to "🇬🇷 Greece",
        "rs" to "🇷🇸 Serbia", "hr" to "🇭🇷 Croatia", "sa" to "🇸🇦 Saudi Arabia",
        "pt" to "🇵🇹 Portugal", "nl" to "🇳🇱 Netherlands", "at" to "🇦🇹 Austria",
        "cz" to "🇨🇿 Czech Republic", "dk" to "🇩🇰 Denmark", "se" to "🇸🇪 Sweden",
        "il" to "🇮🇱 Israel", "ar" to "🇦🇷 Argentina", "mx" to "🇲🇽 Mexico",
        "tr" to "🇹🇷 Turkey", "ro" to "🇷🇴 Romania", "cy" to "🇨🇾 Cyprus",
        "in" to "🇮🇳 India", "be" to "🇧🇪 Belgium", "ae" to "🇦🇪 UAE",
        "hu" to "🇭🇺 Hungary", "uy" to "🇺🇾 Uruguay", "cl" to "🇨🇱 Chile",
        "co" to "🇨🇴 Colombia", "eg" to "🇪🇬 Egypt", "ru" to "🇷🇺 Russia"
    )

    /** Sport slug (lowercase) → display name + API key lookup helper.
     *  The events API returns sport keys in capitalized form ("Soccer", "Basketball", etc.).
     *  We store the display name here and use a helper to derive the API key. */
    private val sportLabels = mapOf(
        "soccer" to "⚽ Soccer",
        "basketball" to "🏀 Basketball",
        "tennis" to "🎾 Tennis",
        "hockey" to "🏒 Hockey",
        "cricket" to "🏏 Cricket",
        "golf" to "⛳ Golf",
        "mma" to "🥊 MMA",
        "motorsport" to "🏎️ Motorsport",
        "cycling" to "🚴 Cycling",
        "volleyball" to "🏐 Volleyball",
        "handball" to "🤾 Handball",
        "darts" to "🎯 Darts"
    )

    /** Derive the API key for a sport slug. The events API returns sport names
     *  in Title Case ("Soccer", "Basketball"), so we capitalize the first letter. */
    private fun apiSportKey(slug: String): String =
        slug.replaceFirstChar { it.uppercase() }

    override val mainPage = mainPageOf(
        "${mainUrl}/channels/?user=cdnlivetv&plan=free" to "📺 All Channels",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=us" to "🇺🇸 US",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=gb" to "🇬🇧 UK",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=es" to "🇪🇸 Spain",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=de" to "🇩🇪 Germany",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=au" to "🇦🇺 Australia",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=br" to "🇧🇷 Brazil",
        "${mainUrl}/channels/?user=cdnlivetv&plan=all" to "🔴 Live Now",
        // --- Sport Events ---
        "sport_soccer" to "⚽ Soccer",
        "sport_basketball" to "🏀 Basketball",
        "sport_tennis" to "🎾 Tennis",
        "sport_hockey" to "🏒 Hockey",
        "sport_motorsport" to "🏎️ Motorsport",
        "sport_handball" to "🤾 Handball",
        "sport_golf" to "⛳ Golf",
        "sport_cricket" to "🏏 Cricket",
        "sport_cycling" to "🚴 Cycling",
        "sport_volleyball" to "🏐 Volleyball",
        "sport_mma" to "🥊 MMA",
        "sport_darts" to "🎯 Darts",
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Main Page
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            when {
                request.data.startsWith("sport_") -> getSportEvents(request.data)
                request.data.contains("plan=all") -> getLiveChannels()
                request.data.contains("&code=") -> getChannelsByCountry(request.data)
                request.data.contains("/channels/") -> getAllChannels()
                else -> newHomePageResponse(list = mutableListOf(), hasNext = false)
            }
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Channel Data
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun fetchAllChannels(): List<ChannelData> {
        if (channelCacheValid()) return cachedChannels!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val text = withContext(Dispatchers.IO) {
            app.get("${mainUrl}/channels/?user=cdnlivetv&plan=free", headers = headers).text
        }
        val response: ChannelResponse = mapper.readValue(text)
        cachedChannels = response.channels
        channelCacheTimestamp = System.currentTimeMillis()
        return response.channels
    }

    private suspend fun getLiveChannels(): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val liveChannels = fetchAllChannels()
                .filter { it.status == "online" && (it.viewers ?: 0) > 0 }
                .sortedByDescending { it.viewers ?: 0 }

            if (liveChannels.isNotEmpty()) {
                val searchItems = liveChannels.mapNotNull { ch ->
                    val name = ch.name ?: return@mapNotNull null
                    val playerUrl = buildPlayerUrl(name, ch.code ?: "us")
                    newLiveSearchResponse(name, playerUrl, TvType.Live) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = posterHeaders
                    }
                }
                items.add(HomePageList("🔴 Live Now", searchItems, isHorizontalImages = false))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getAllChannels(): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val searchItems = fetchAllChannels().mapNotNull { ch ->
                val name = ch.name ?: return@mapNotNull null
                val playerUrl = buildPlayerUrl(name, ch.code ?: "us")
                newLiveSearchResponse(name, playerUrl, TvType.Live) {
                    this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                    this.posterHeaders = posterHeaders
                }
            }
            if (searchItems.isNotEmpty()) {
                items.add(HomePageList("📺 All Channels", searchItems, isHorizontalImages = false))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getChannelsByCountry(url: String): HomePageResponse {
        val code = url.substringAfterLast("code=").substringBefore("&").takeIf { it.isNotBlank() }
            ?: return newHomePageResponse(list = mutableListOf(), hasNext = false)
        val items = mutableListOf<HomePageList>()
        try {
            val filtered = fetchAllChannels().filter {
                (it.code ?: "").equals(code, ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                val searchItems = filtered.mapNotNull { ch ->
                    val name = ch.name ?: return@mapNotNull null
                    val playerUrl = buildPlayerUrl(name, ch.code ?: "us")
                    newLiveSearchResponse(name, playerUrl, TvType.Live) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = posterHeaders
                    }
                }
                items.add(
                    HomePageList(
                        codeNames[code.lowercase()] ?: code.uppercase(),
                        searchItems,
                        isHorizontalImages = false
                    )
                )
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sport Events
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun getSportEvents(identifier: String): HomePageResponse {
        val sportSlug = identifier.removePrefix("sport_").lowercase()
        val label = sportLabels[sportSlug] ?: sportSlug.replaceFirstChar { it.uppercase() }

        return try {
            val allEventsMap = fetchAllEvents()
            val apiKey = apiSportKey(sportSlug)
            // Primary: match by capitalized API key ("Soccer")
            // Fallback: case-insensitive match
            val events = allEventsMap[apiKey]
                ?: allEventsMap.entries.firstOrNull { it.key.equals(apiKey, ignoreCase = true) }?.value
                ?: emptyList()

            val filtered = events
                .filter { it.channels?.isNotEmpty() == true }
                .sortedWith(
                    compareByDescending<Event> { it.status == "live" }
                        .thenBy { it.start ?: "" }
                )

            val searchItems = filtered.mapNotNull { event ->
                val title = "${event.homeTeam ?: "?"} vs ${event.awayTeam ?: "?"}"
                val firstUrl = event.channels?.firstOrNull()?.url
                if (firstUrl.isNullOrBlank()) return@mapNotNull null
                val gameID = event.gameID ?: return@mapNotNull null
                val detailUrl = "https://cdnlivetv.tv/event/watch/$gameID?sport=$sportSlug"

                val statusTag = when (event.status) {
                    "live" -> "🔴 LIVE"
                    "upcoming" -> "⏳ Upcoming"
                    else -> event.status ?: ""
                }
                val tournament = event.tournament?.let { " — $it" } ?: ""

                newLiveSearchResponse("$title $statusTag", detailUrl, TvType.Live) {
                    this.posterUrl = event.countryIMG?.takeIf { it.isNotBlank() }
                        ?: event.homeTeamIMG?.takeIf { it.isNotBlank() }
                    this.posterHeaders = posterHeaders
                }
            }

            newHomePageResponse(
                list = listOf(HomePageList(label, searchItems, isHorizontalImages = false)),
                hasNext = false
            )
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    private suspend fun fetchAllEvents(): Map<String, List<Event>> {
        if (eventsCacheValid()) return cachedEvents!!

        val mapper = jacksonObjectMapper().registerKotlinModule()
        val url = "$EVENTS_BASE_URL/events/sports/?user=cdnlivetv&plan=free"
        val text = withContext(Dispatchers.IO) {
            app.get(url, headers = headers).text
        }

        val raw: Map<String, Any> = mapper.readValue(text)
        val cdnData = raw["cdn-live-tv"] as? Map<String, Any> ?: return emptyMap()

        val result = mutableMapOf<String, List<Event>>()
        cdnData.forEach { (key, value) ->
            if (key !in setOf("total_events", "cached", "timestamp") && value is List<*>) {
                val json = mapper.writeValueAsString(value)
                result[key] = mapper.readValue(json)
            }
        }

        cachedEvents = result
        eventsCacheTimestamp = System.currentTimeMillis()
        return result
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val channels = try {
            if (channelCacheValid()) cachedChannels!! else fetchAllChannels()
        } catch (_: Exception) { return emptyList() }

        return channels
            .filter { (it.name ?: "").lowercase().contains(q) }
            .mapNotNull { ch ->
                val name = ch.name ?: return@mapNotNull null
                val playerUrl = buildPlayerUrl(name, ch.code ?: "us")
                newLiveSearchResponse(name, playerUrl, TvType.Live) {
                    this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                    this.posterHeaders = posterHeaders
                }
            }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ──────────────────────────────────────────────────────────────────────────
    // Load (Detail)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Two URL patterns:
     *   Event:  https://cdnlivetv.tv/event/watch/{gameID}?sport={sport}
     *   Channel: cdnlivetv.tv/api/v1/channels/player/?name=...&code=...
     */
    override suspend fun load(url: String): LoadResponse? {
        return when {
            url.contains("/event/watch/") -> loadEvent(url)
            else -> loadChannel(url)
        }
    }

    private suspend fun loadEvent(url: String): LoadResponse? {
        return try {
            val gameID = url.substringAfterLast("/").substringBefore("?")
            val sport = url.substringAfter("sport=", "").substringBefore("&").takeIf { it.isNotBlank() }
                ?: return null

            val events = fetchAllEvents()
            val apiKey = apiSportKey(sport.lowercase())
            val matchingSport = events[apiKey]
                ?: events.entries.firstOrNull { it.key.equals(apiKey, ignoreCase = true) }?.value
                ?: return null

            val event = matchingSport.find { it.gameID == gameID } ?: return null
            val title = "${event.homeTeam ?: "?"} vs ${event.awayTeam ?: "?"}"
            val totalViewers = event.channels?.sumOf { it.viewers ?: 0 }?.takeIf { it > 0 }

            val description = buildString {
                event.tournament?.let { appendLine("🏆 Tournament: $it") }
                event.country?.let { appendLine("🌍 Country: $it") }
                event.time?.let { appendLine("🕐 Time: $it") }
                event.start?.let { appendLine("📅 Start: $it") }
                appendLine("📊 Status: ${event.status ?: "unknown"}")
                totalViewers?.let { appendLine("👁️ Total viewers: $it") }
                val sourceCount = event.channels?.size ?: 0
                if (sourceCount > 0) appendLine("📡 Available sources: $sourceCount")
            }

            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = event.homeTeamIMG?.takeIf { it.isNotBlank() }
                    ?: event.countryIMG?.takeIf { it.isNotBlank() }
                this.posterHeaders = posterHeaders
                this.plot = description.trim()
                this.tags = listOfNotNull(event.country, event.tournament, event.status)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse? {
        val params = parsePlayerUrl(url)
        val channelName = params.first ?: return null
        val channelCode = params.second ?: "us"

        return try {
            val mapper = jacksonObjectMapper().registerKotlinModule()
            val apiUrl = "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=$channelCode"
            val responseText = withContext(Dispatchers.IO) {
                app.get(apiUrl, headers = headers).text
            }
            val channelResponse: ChannelResponse = mapper.readValue(responseText)

            val channelData = channelResponse.channels.find { ch ->
                (ch.name ?: "").equals(channelName, ignoreCase = true) ||
                    java.net.URLEncoder.encode(ch.name ?: "", "UTF-8")
                        .equals(channelName, ignoreCase = true)
            }

            val title = channelData?.name ?: java.net.URLDecoder.decode(channelName, "UTF-8")
            val status = channelData?.status ?: "unknown"
            val viewers = channelData?.viewers

            val description = buildString {
                appendLine("📊 Status: $status")
                if (viewers != null && viewers > 0) appendLine("👁️ Viewers: $viewers")
                val countryName = channelData?.code?.let { codeNames[it.lowercase()] }
                if (countryName != null) appendLine("🌍 $countryName")
            }

            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = channelData?.image?.takeIf { it.isNotBlank() }
                this.posterHeaders = posterHeaders
                this.plot = description.trim()
            }
        } catch (e: Exception) {
            try {
                newMovieLoadResponse(name, url, TvType.Live, url) {
                    this.posterHeaders = posterHeaders
                }
            } catch (_: Exception) {
                null
            }
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
        return try {
            when {
                data.contains("/event/watch/") -> loadLinksFromEvent(data, subtitleCallback, callback)
                else -> loadLinksFromChannel(data, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** For events: iterate all broadcast sources from the event's channels[] */
    private suspend fun loadLinksFromEvent(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val gameID = url.substringAfterLast("/").substringBefore("?")
        val sport = url.substringAfter("sport=", "").substringBefore("&").takeIf { it.isNotBlank() }
            ?: return false

        val events = fetchAllEvents()
        val apiKey = apiSportKey(sport.lowercase())
        val matchingSport = events[apiKey]
            ?: events.entries.firstOrNull { it.key.equals(apiKey, ignoreCase = true) }?.value
            ?: return false

        val event = matchingSport.find { it.gameID == gameID } ?: return false

        event.channels?.filter { it.channelName?.isNotBlank() == true }?.forEach { ch ->
            try {
                val chName = ch.channelName ?: return@forEach
                val chCode = ch.channelCode ?: "us"
                val sourceLabel = buildSourceLabel(ch)
                val sourceUrl = buildPlayerUrl(chName, chCode)

                loadExtractor(
                    url = sourceUrl,
                    referer = "https://cdnlivetv.tv/",
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        callback(
                            newExtractorLink(
                                source = "${link.source} [$sourceLabel]",
                                name = "${link.name} [$sourceLabel]",
                                url = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                type = link.type,
                                headers = link.headers
                            )
                        )
                    }
                )
            } catch (_: Exception) { /* skip failed source */ }
        }

        return true
    }

    /** For channels: find all country variants of the same channel and extract each */
    private suspend fun loadLinksFromChannel(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val params = parsePlayerUrl(data)
        val channelName = params.first ?: return false

        val channels = fetchAllChannels()
        val matches = channels.filter { ch ->
            ch.name?.equals(channelName, ignoreCase = true) == true
        }

        if (matches.isEmpty()) {
            // No multi-source — use the original URL directly
            return try {
                loadExtractor(
                    url = data,
                    referer = "https://cdnlivetv.tv/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                true
            } catch (_: Exception) {
                false
            }
        }

        matches.forEach { ch ->
            try {
                val chName = ch.name ?: return@forEach
                val chCode = ch.code ?: "us"
                val sourceLabel = codeNames[ch.code?.lowercase()] ?: ch.code?.uppercase() ?: "Unknown"
                val sourceUrl = buildPlayerUrl(chName, chCode)

                loadExtractor(
                    url = sourceUrl,
                    referer = "https://cdnlivetv.tv/",
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        callback(
                            newExtractorLink(
                                source = "${link.source} [$sourceLabel]",
                                name = "${link.name} [$sourceLabel]",
                                url = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                type = link.type,
                                headers = link.headers
                            )
                        )
                    }
                )
            } catch (_: Exception) { /* skip failed source */ }
        }

        return true
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildSourceLabel(channel: EventChannel): String {
        val countryName = channel.channelCode
            ?.let { codeNames[it.lowercase()] }
            ?: channel.channelCode?.uppercase()
            ?: "Unknown"
        val chName = channel.channelName?.takeIf { it.isNotBlank() }
        return if (chName != null) "$chName ($countryName)" else countryName
    }

    private fun buildPlayerUrl(channelName: String, code: String): String {
        return "https://cdnlivetv.tv/api/v1/channels/player/?name=${
            java.net.URLEncoder.encode(channelName, "UTF-8")
        }&code=$code&user=cdnlivetv&plan=free"
    }

    /** Parse ?name=XXX&code=YYY from a player URL */
    private fun parsePlayerUrl(url: String): Pair<String?, String?> {
        return try {
            val uri = java.net.URI(url)
            val params = uri.query?.split("&")?.associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else it to ""
            } ?: emptyMap()
            params["name"] to (params["code"] ?: "us")
        } catch (_: Exception) {
            null to null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Data Models
    // ──────────────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelData(
        @JsonProperty("name") val name: String?,
        @JsonProperty("code") val code: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("viewers") val viewers: Int?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelResponse(
        @JsonProperty("channels") val channels: List<ChannelData>,
        @JsonProperty("total_channels") val totalChannels: Int?
    )
}
