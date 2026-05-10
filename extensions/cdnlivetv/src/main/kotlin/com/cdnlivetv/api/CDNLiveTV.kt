package com.cdnlivetv.api

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * CDNLiveTV (cdnlivetv.tv / api.cdnlivetv.ru) - Live sports streaming provider.
 *
 * Architecture:
 * - API domain: api.cdnlivetv.ru (unprotected JSON API, 762 channels, 38 countries)
 * - Player domain: cdnlivetv.tv (Cloudflare-protected, OPlayer-based)
 * - Events domain: api.cdnlivetv.tv/events/sports/{sport}/
 *
 * Flow (channels):
 * 1. API (/channels/) provides channel list with metadata
 * 2. Player URL constructed: cdnlivetv.tv/api/v1/channels/player/?name=...&code=...
 * 3. load() enriches from API, loadLinks() dispatches to WebView extractor
 *
 * Flow (events):
 * 1. API (/events/sports/{sport}/) provides event list with embedded channel sources
 * 2. Events listed in catalog by sport category
 * 3. load() enriches event with match details, poster, description
 * 4. loadLinks() iterates event.channels[] and dispatches each to WebView extractor
 * 5. User picks preferred source from available feeds
 * 6. WebView renders OPlayer, captures .m3u8 from network requests
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
            "Referer" to "https://streamsports99.ru/"
        )
        val posterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Referer" to "https://cdnlivetv.tv/"
        )

        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val EVENTS_BASE_URL = "https://api.cdnlivetv.tv/api/v1"
        @Volatile private var cachedChannels: List<ChannelData>? = null
        @Volatile private var cachedEvents: Map<String, List<Event>>? = null
        @Volatile private var eventsCacheTimestamp: Long = 0
        @Volatile private var cacheTimestamp: Long = 0

        fun isCacheValid() = cachedChannels != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS
        fun getCachedChannels(): List<ChannelData>? = cachedChannels
        fun updateCache(channels: List<ChannelData>) {
            cachedChannels = channels
            cacheTimestamp = System.currentTimeMillis()
        }
    }

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

    /** Sport slug → display name mapping for the events catalog */
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

    override val mainPage = mainPageOf(
        "${mainUrl}/channels/?user=cdnlivetv&plan=free" to "📺 All Channels",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=us" to "🇺🇸 US",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=gb" to "🇬🇧 UK",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=es" to "🇪🇸 Spain",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=de" to "🇩🇪 Germany",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=au" to "🇦🇺 Australia",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=br" to "🇧🇷 Brazil",
        "${mainUrl}/channels/?user=cdnlive&plan=all" to "🔴 Live Now",
        // --- Sport Events (Custom routing identifiers) ---
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            when {
                request.data.startsWith("sport_") -> getSportEvents(request.data)
                request.data.contains("plan=all") -> getLiveChannels()
                request.data.contains("&code=") -> getChannelsByCountry(request.data)
                request.data.contains("/channels/") -> getAllChannels(request.data)
                else -> newHomePageResponse(list = mutableListOf(), hasNext = false)
            }
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    private suspend fun fetchAllChannels(): List<ChannelData> {
        if (isCacheValid()) return getCachedChannels()!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val textdoc = withContext(Dispatchers.IO) {
            app.get("${mainUrl}/channels/?user=cdnlivetv&plan=free", headers = headers).text
        }
        val response: ChannelResponse = mapper.readValue(textdoc)
        updateCache(response.channels)
        return response.channels
    }

    private suspend fun getLiveChannels(): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val liveChannels = fetchAllChannels()
                .filter { it.status == "online" && (it.viewers ?: 0) > 0 }
                .sortedByDescending { it.viewers ?: 0 }

            if (liveChannels.isNotEmpty()) {
                val dayItems = liveChannels.mapNotNull { ch ->
                    val playerUrl = buildPlayerUrl(ch.name ?: return@mapNotNull null, ch.code ?: "us")
                    newLiveSearchResponse(ch.name, playerUrl, TvType.Live) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = posterHeaders
                    }
                }
                items.add(HomePageList("🔴 Live Now", dayItems, isHorizontalImages = false))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getAllChannels(url: String): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val dayItems = fetchAllChannels().mapNotNull { ch ->
                if (ch.name.isNullOrBlank()) return@mapNotNull null
                val playerUrl = buildPlayerUrl(ch.name, ch.code ?: "us")
                newLiveSearchResponse(ch.name, playerUrl, TvType.Live) {
                    this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                    this.posterHeaders = posterHeaders
                }
            }
            if (dayItems.isNotEmpty()) {
                items.add(HomePageList("📺 All Channels", dayItems, isHorizontalImages = false))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getChannelsByCountry(url: String): HomePageResponse {
        val code = url.substringAfterLast("code=").substringBefore("&").takeIf { it.isNotBlank() }
            ?: return newHomePageResponse(list = mutableListOf(), hasNext = false)
        val items = mutableListOf<HomePageList>()
        try {
            val filtered = fetchAllChannels().filter { (it.code ?: "").equals(code, ignoreCase = true) }
            if (filtered.isNotEmpty()) {
                val dayItems = filtered.mapNotNull { ch ->
                    if (ch.name.isNullOrBlank()) return@mapNotNull null
                    val playerUrl = buildPlayerUrl(ch.name, ch.code ?: "us")
                    newLiveSearchResponse(ch.name, playerUrl, TvType.Live) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = posterHeaders
                    }
                }
                items.add(HomePageList(codeNames[code.lowercase()] ?: code.uppercase(), dayItems, isHorizontalImages = false))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    /**
     * Fetch sport events from the /events/sports/{sport}/ endpoint.
     * Each event contains a channels[] array listing all broadcast sources
     * (typically from different countries), giving the user a source picker.
     */
    private suspend fun getSportEvents(identifier: String): HomePageResponse {
        val sportSlug = identifier.removePrefix("sport_").lowercase()
        val label = sportLabels[sportSlug] ?: sportSlug.replaceFirstChar { it.uppercase() }

        return try {
            val allEventsMap = fetchAllEvents()
            val events = allEventsMap[sportLabels[sportSlug]?.removePrefix("") ?: sportSlug.replaceFirstChar { it.uppercase() }] 
                ?: allEventsMap[sportSlug] 
                ?: emptyList()

            // If the above keys fail, try to find any key that matches the slug case-insensitively
            val finalEvents = if (events.isEmpty()) {
                allEventsMap.entries.firstOrNull { it.key.equals(sportSlug, ignoreCase = true) }?.value ?: emptyList()
            } else events

            val filteredEvents = finalEvents
                .filter { it.channels?.isNotEmpty() == true }
                .sortedWith(
                    compareByDescending<Event> { it.status == "live" }
                        .thenBy { it.start ?: "" }
                )

            val items = filteredEvents.mapNotNull { event ->
                val title = "${event.homeTeam ?: "?"} vs ${event.awayTeam ?: "?"}"
                if (event.channels?.firstOrNull()?.url.isNullOrBlank()) return@mapNotNull null

                val gameID = event.gameID ?: return@mapNotNull null
                val detailUrl = "https://cdnlivetv.tv/event/watch/$gameID?sport=$sportSlug"

                val statusIcon = when (event.status) {
                    "live" -> "🔴 LIVE"
                    "upcoming" -> "⏳ Upcoming"
                    else -> event.status ?: ""
                }
                val tournament = event.tournament?.let { " — $it" } ?: ""
                val country = event.country?.let { " ($it)" } ?: ""

                newLiveSearchResponse(
                    "$title $statusIcon",
                    detailUrl,
                    TvType.Live
                ) {
                    this.posterUrl = event.countryIMG?.takeIf { it.isNotBlank() }
                    this.posterHeaders = posterHeaders
                }
            }

            newHomePageResponse(
                list = listOf(HomePageList(label, items, isHorizontalImages = false)),
                hasNext = false
            )
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    private suspend fun fetchAllEvents(): Map<String, List<Event>> {
        if (cachedEvents != null && (System.currentTimeMillis() - eventsCacheTimestamp) < CACHE_TTL_MS) {
            return cachedEvents!!
        }
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val responseUrl = "$EVENTS_BASE_URL/events/sports/?user=cdnlivetv&plan=free"
        val text = app.get(responseUrl, headers = headers).text

        val raw: Map<String, Any> = mapper.readValue(text)
        val cdnData = raw["cdn-live-tv"] as? Map<String, Any> ?: return emptyMap()
        
        val eventsMap = mutableMapOf<String, List<Event>>()
        cdnData.forEach { (key, value) ->
            if (key !in setOf("total_events", "cached", "timestamp") && value is List<*>) {
                val jsonList = mapper.writeValueAsString(value)
                val events: List<Event> = mapper.readValue(jsonList)
                eventsMap[key] = events
            }
        }
        
        cachedEvents = eventsMap
        eventsCacheTimestamp = System.currentTimeMillis()
        return eventsMap
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()
        try {
            val channels = (if (isCacheValid()) getCachedChannels()!! else fetchAllChannels())
                .filter { (it.name ?: "").lowercase().contains(q) }

            for (ch in channels) {
                val playerUrl = buildPlayerUrl(ch.name ?: continue, ch.code ?: "us")
                results.add(newLiveSearchResponse(ch.name, playerUrl, TvType.Live) {
                    this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                    this.posterHeaders = posterHeaders
                })
            }
        } catch (_: Exception) {}
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    /**
     * Loads event or channel detail.
     *
     * Event URLs (from catalog):  https://cdnlivetv.tv/event/watch/{gameID}?sport={sport}
     * Channel URLs (from browse): cdnlivetv.tv/api/v1/channels/player/?name=...&code=...
     */
    override suspend fun load(url: String): LoadResponse? {
        return when {
            // Event detail page
            url.contains("/event/watch/") -> loadEvent(url)
            // Channel player page
            else -> loadChannel(url)
        }
    }

    private suspend fun loadEvent(url: String): LoadResponse? {
        return try {
            val gameID = url.substringAfterLast("/").substringBefore("?")
            val sport = url.substringAfter("sport=", "").substringBefore("&").takeIf { it.isNotBlank() }
                ?: return null
 
            val events = fetchAllEvents()[sportLabels[sport.lowercase()] ?: sport.replaceFirstChar { it.uppercase() }]
                ?: fetchAllEvents().entries.firstOrNull { it.key.equals(sport, ignoreCase = true) }?.value
                ?: return null
            
            val event = events.find { it.gameID == gameID } ?: return null
 
            val title = "${event.homeTeam ?: "?"} vs ${event.awayTeam ?: "?"}"

            val status = event.status ?: "unknown"
            val viewers = event.channels
                ?.sumOf { it.viewers ?: 0 }
                ?.takeIf { it > 0 }

            val tournament = event.tournament
            val country = event.country
            val time = event.time
            val start = event.start

            val description = buildString {
                if (tournament != null) appendLine("Tournament: $tournament")
                if (country != null) appendLine("Country: $country")
                if (time != null) appendLine("Time: $time")
                if (start != null) appendLine("Start: $start")
                appendLine("Status: $status")
                if (viewers != null) appendLine("Total viewers: $viewers")
                val sourceCount = event.channels?.size ?: 0
                if (sourceCount > 0) appendLine("Available sources: $sourceCount")
            }

            val posterUrl = when {
                event.homeTeamIMG?.isNotBlank() == true -> event.homeTeamIMG
                event.countryIMG?.isNotBlank() == true -> event.countryIMG
                else -> null
            }

            val tags = listOfNotNull(event.country, event.tournament, event.status)

            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeaders
                this.plot = description.trim()
                this.tags = tags
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
            val responseText = app.get(apiUrl, headers = headers).text
            val channelResponse: ChannelResponse = mapper.readValue(responseText)

            val channelData = channelResponse.channels.find { ch ->
                (ch.name ?: "").equals(channelName, ignoreCase = true)
                        || java.net.URLEncoder.encode(ch.name ?: "", "UTF-8")
                    .equals(channelName, ignoreCase = true)
            }

            val title = channelData?.name ?: java.net.URLDecoder.decode(channelName, "UTF-8")
            val status = channelData?.status ?: "unknown"
            val viewers = channelData?.viewers
            val description = "Status: $status" +
                    if (viewers != null && viewers > 0) " | Viewers: $viewers" else ""
            val posterUrl = channelData?.image?.takeIf { it.isNotBlank() }

            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeaders
                this.plot = description
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

    /**
     * Multi-source link extraction.
     *
     * Handles two scenarios:
     * 1. Channel URL (browse/catalog): finds ALL channels with same name across countries,
     *    dispatches each to WebView extractor for source picker.
     * 2. Event URL (event detail): uses the event's channels[] directly from API data,
     *    dispatches each channel's player URL to WebView extractor.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            when {
                data.contains("/event/watch/") -> loadLinksFromEvent(data, subtitleCallback, callback)
                else -> loadLinksFromChannel(data, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Event-based multi-source: uses the event's channels[] from the API.
     * Each channel in the event is dispatched to the WebView extractor.
     */
    private suspend fun loadLinksFromEvent(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val gameID = url.substringAfterLast("/").substringBefore("?")
        val sport = url.substringAfter("sport=", "").substringBefore("&").takeIf { it.isNotBlank() }
            ?: return false
 
        val events = fetchAllEvents()[sportLabels[sport.lowercase()] ?: sport.replaceFirstChar { it.uppercase() }]
            ?: fetchAllEvents().entries.firstOrNull { it.key.equals(sport, ignoreCase = true) }?.value
            ?: return false
        val event = events.find { it.gameID == gameID } ?: return false
        val channels = event.channels?.filter { it?.url?.isNotBlank() == true } ?: return false
 
        return coroutineScope {
            val deferredJobs = channels.mapNotNull { eventChannel ->
                val sourceUrl = eventChannel.url ?: return@mapNotNull null
                async {
                    try {
                        val sourceLabel = buildSourceLabel(eventChannel)
                        var success = false
                        loadExtractor(
                            url = sourceUrl,
                            referer = "https://cdnlivetv.tv/",
                            subtitleCallback = subtitleCallback,
                            callback = { link ->
                                callback(ExtractorLink(
                                    source = "${link.source} [$sourceLabel]",
                                    name = "${link.name} [$sourceLabel]",
                                    url = link.url,
                                    referer = link.referer,
                                    quality = link.quality,
                                    type = link.type,
                                    headers = link.headers
                                ))
                                success = true
                            }
                        )
                        success
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            deferredJobs.awaitAll().any { it }
        }
    }

    /**
     * Channel-based multi-source: finds all channels sharing the same name
     * across different country codes and dispatches each to the extractor.
     */
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
            // No multi-source matches, use the original URL
            return try {
                loadExtractor(
                    url = data,
                    referer = "https://cdnlivetv.tv/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            } catch (_: Exception) {
                false
            }
        }

        return coroutineScope {
            val deferredJobs = matches.mapNotNull { ch ->
                val chName = ch.name ?: return@mapNotNull null
                val sourceUrl = buildPlayerUrl(chName, ch.code ?: "us")
                async {
                    try {
                        val sourceLabel = codeNames[ch.code?.lowercase()] ?: ch.code?.uppercase() ?: "Unknown"
                        var success = false
                        loadExtractor(
                            url = sourceUrl,
                            referer = "https://cdnlivetv.tv/",
                            subtitleCallback = subtitleCallback,
                            callback = { link ->
                                callback(ExtractorLink(
                                    source = "${link.source} [$sourceLabel]",
                                    name = "${link.name} [$sourceLabel]",
                                    url = link.url,
                                    referer = link.referer,
                                    quality = link.quality,
                                    type = link.type,
                                    headers = link.headers
                                ))
                                success = true
                            }
                        )
                        success
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            deferredJobs.awaitAll().any { it }
        }
    }

    private fun buildSourceLabel(channel: EventChannel): String {
        val countryName = channel.channelCode
            ?.let { codeNames[it.lowercase()] }
            ?: channel.channelCode?.uppercase()
            ?: "Unknown"
        val chName = channel.channelName?.takeIf { it.isNotBlank() }
        return if (chName != null) "$chName ($countryName)" else countryName
    }

    private fun buildPlayerUrl(channelName: String, code: String): String {
        return "https://cdnlivetv.tv/api/v1/channels/player/?name=${java.net.URLEncoder.encode(channelName, "UTF-8")}&code=$code&user=cdnlivetv&plan=free"
    }

    /** Parses ?name=XXX&code=YYY from a player URL */
    private fun parsePlayerUrl(url: String): Pair<String?, String?> {
        return try {
            val uri = java.net.URI(url)
            val params = uri.query?.split("&")?.associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else it to ""
            } ?: emptyMap()
            params["name"] to (params["code"] ?: "us")
        } catch (_: Exception) {
            null to null
        }
    }

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