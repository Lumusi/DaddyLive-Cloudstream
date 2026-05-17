package com.aiolive.api

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AIOLive : MainAPI() {
    override var mainUrl = "https://dami-tv.pro"
    override var name = "AIOLive"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private companion object {
        const val DAMI_API = "https://dami-tv.pro/papi"
        const val CDN_CHANNELS_API = "https://api.cdnlivetv.ru/api/v1"
        const val CDN_EVENTS_API = "https://api.cdnlivetv.tv/api/v1"
        const val CDN_PLAYER_URL = "https://cdnlivetv.tv"
        const val CACHE_TTL_MS = 3 * 60 * 1000L

        val damiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "application/json",
            "Referer" to "https://dami-tv.pro/"
        )

        val cdnHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "application/json, text/html, */*; q=0.01",
            "Referer" to "https://cdnlivetv.tv/"
        )

        val posterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Referer" to "https://dami-tv.pro/"
        )

        val cdnPosterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Referer" to "https://cdnlivetv.tv/"
        )

        val cdnStreamingHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "Origin" to "https://cdnlivetv.tv",
            "Referer" to "https://cdnlivetv.tv/",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        @Volatile private var cachedDamiMatches: List<ApiMatch>? = null
        @Volatile private var damiMatchesCacheTimestamp: Long = 0
        @Volatile private var cachedDamiChannels: List<ApiChannel>? = null
        @Volatile private var damiChannelsCacheTimestamp: Long = 0
        @Volatile private var cachedCdnChannels: List<CdnChannelData>? = null
        @Volatile private var cdnChannelsCacheTimestamp: Long = 0
        @Volatile private var cachedCdnEvents: Map<String, List<Event>>? = null
        @Volatile private var cdnEventsCacheTimestamp: Long = 0

        private fun damiMatchesCacheValid() =
            cachedDamiMatches != null && (System.currentTimeMillis() - damiMatchesCacheTimestamp) < CACHE_TTL_MS

        private fun damiChannelsCacheValid() =
            cachedDamiChannels != null && (System.currentTimeMillis() - damiChannelsCacheTimestamp) < CACHE_TTL_MS

        private fun cdnChannelsCacheValid() =
            cachedCdnChannels != null && (System.currentTimeMillis() - cdnChannelsCacheTimestamp) < CACHE_TTL_MS

        private fun cdnEventsCacheValid() =
            cachedCdnEvents != null && (System.currentTimeMillis() - cdnEventsCacheTimestamp) < CACHE_TTL_MS
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

    private val cdnCodeNames = mapOf(
        "us" to "\uD83C\uDDFA\uD83C\uDDF8 United States", "gb" to "\uD83C\uDDEC\uD83C\uDDE7 United Kingdom",
        "es" to "\uD83C\uDDEA\uD83C\uDDF8 Spain", "de" to "\uD83C\uDDE9\uD83C\uDDEA Germany", "au" to "\uD83C\uDDE6\uD83C\uDDFA Australia",
        "fr" to "\uD83C\uDDEB\uD83C\uDDF7 France", "it" to "\uD83C\uDDEE\uD83C\uDDF9 Italy", "br" to "\uD83C\uDDE7\uD83C\uDDF7 Brazil",
        "pl" to "\uD83C\uDDF5\uD83C\uDDF1 Poland", "za" to "\uD83C\uDDFF\uD83C\uDDE6 South Africa", "gr" to "\uD83C\uDDEC\uD83C\uDDF7 Greece",
        "rs" to "\uD83C\uDDF7\uD83C\uDDF8 Serbia", "hr" to "\uD83C\uDDED\uD83C\uDDF7 Croatia", "sa" to "\uD83C\uDDF8\uD83C\uDDE6 Saudi Arabia",
        "pt" to "\uD83C\uDDF5\uD83C\uDDF9 Portugal", "nl" to "\uD83C\uDDF3\uD83C\uDDF1 Netherlands", "at" to "\uD83C\uDDE6\uD83C\uDDF9 Austria",
        "cz" to "\uD83C\uDDE8\uD83C\uDDFF Czech Republic", "dk" to "\uD83C\uDDE9\uD83C\uDDF0 Denmark", "se" to "\uD83C\uDDF8\uD83C\uDDEA Sweden",
        "il" to "\uD83C\uDDEE\uD83C\uDDF1 Israel", "ar" to "\uD83C\uDDE6\uD83C\uDDF7 Argentina", "mx" to "\uD83C\uDDF2\uD83C\uDDFD Mexico",
        "tr" to "\uD83C\uDDF9\uD83C\uDDF7 Turkey", "ro" to "\uD83C\uDDF7\uD83C\uDDF4 Romania", "cy" to "\uD83C\uDDE8\uD83C\uDDFE Cyprus",
        "in" to "\uD83C\uDDEE\uD83C\uDDF3 India", "be" to "\uD83C\uDDE7\uD83C\uDDEA Belgium", "ae" to "\uD83C\uDDE6\uD83C\uDDEA UAE",
        "hu" to "\uD83C\uDDED\uD83C\uDDFA Hungary", "uy" to "\uD83C\uDDFA\uD83C\uDDFE Uruguay", "cl" to "\uD83C\uDDE8\uD83C\uDDF1 Chile",
        "co" to "\uD83C\uDDE8\uD83C\uDDF4 Colombia", "eg" to "\uD83C\uDDEA\uD83C\uDDEC Egypt", "ru" to "\uD83C\uDDF7\uD83C\uDDFA Russia"
    )

    private val sportLabels = mapOf(
        "soccer" to "\u26BD Soccer",
        "basketball" to "\uD83C\uDFC0 Basketball",
        "tennis" to "\uD83C\uDFBE Tennis",
        "hockey" to "\uD83C\uDFD2 Hockey",
        "cricket" to "\uD83C\uDFCF Cricket",
        "golf" to "\u26F3 Golf",
        "mma" to "\uD83E\uDD4A MMA",
        "motorsport" to "\uD83C\uDFCE\uFE0F Motorsport",
        "cycling" to "\uD83D\uDEB4 Cycling",
        "volleyball" to "\uD83C\uDFD0 Volleyball",
        "handball" to "\uD83E\uDD3E Handball",
        "darts" to "\uD83C\uDFAF Darts"
    )

    private fun apiSportKey(slug: String): String =
        slug.replaceFirstChar { it.uppercase() }

    override val mainPage = mainPageOf(
        "dami_live" to "\uD83D\uDD34 DamiTV Live Now",
        "dami_livetv" to "\uD83D\uDCFA DamiTV Live TV",
        "cdn_live" to "\uD83D\uDD34 CDNLiveTV Live Now",
        "cdn_all" to "\uD83D\uDCFA CDNLiveTV All Channels",
        "cdn_us" to "\uD83C\uDDFA\uD83C\uDDF8 US Channels",
        "cdn_gb" to "\uD83C\uDDEC\uD83C\uDDE7 UK Channels",
        "cdn_es" to "\uD83C\uDDEA\uD83C\uDDF8 Spain Channels",
        "cdn_de" to "\uD83C\uDDE9\uD83C\uDDEA Germany Channels",
        "cdn_au" to "\uD83C\uDDE6\uD83C\uDDFA Australia Channels",
        "cdn_br" to "\uD83C\uDDE7\uD83C\uDDF7 Brazil Channels",
        "sport_soccer" to "\u26BD Soccer Events",
        "sport_basketball" to "\uD83C\uDFC0 Basketball Events",
        "sport_tennis" to "\uD83C\uDFBE Tennis Events",
        "sport_hockey" to "\uD83C\uDFD2 Hockey Events",
        "sport_mma" to "\uD83E\uDD4A MMA Events",
        "sport_cricket" to "\uD83C\uDFCF Cricket Events",
        "sport_golf" to "\u26F3 Golf Events",
        "sport_motorsport" to "\uD83C\uDFCE\uFE0F Motorsport Events",
    )

    private suspend fun fetchDamiMatches(): List<ApiMatch> {
        if (damiMatchesCacheValid()) return cachedDamiMatches!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val text = withContext(Dispatchers.IO) {
            app.get("$DAMI_API/matches/all", headers = damiHeaders).text
        }
        val matches: List<ApiMatch> = mapper.readValue(text)
        cachedDamiMatches = matches
        damiMatchesCacheTimestamp = System.currentTimeMillis()
        return matches
    }

    private fun isDamiLive(m: ApiMatch): Boolean =
        m.status == "live" || (m.date != null && m.date <= System.currentTimeMillis())

    private fun damiMatchTitle(m: ApiMatch): String {
        return m.title ?: if (m.teams?.home?.name != null || m.teams?.away?.name != null) {
            "${m.teams.home?.name ?: "?"} vs ${m.teams.away?.name ?: "?"}"
        } else m.league ?: m.category ?: "Unknown"
    }

    private suspend fun fetchDamiChannels(): List<ApiChannel> {
        if (damiChannelsCacheValid()) return cachedDamiChannels!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val text = withContext(Dispatchers.IO) {
            app.get("$mainUrl/channels.json", headers = damiHeaders).text
        }
        val wrapper: ApiChannelWrapper = mapper.readValue(text)
        val channels = wrapper.channels ?: emptyList()
        cachedDamiChannels = channels
        damiChannelsCacheTimestamp = System.currentTimeMillis()
        return channels
    }

    private suspend fun fetchCdnChannels(): List<CdnChannelData> {
        if (cdnChannelsCacheValid()) return cachedCdnChannels!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val text = withContext(Dispatchers.IO) {
            app.get("$CDN_CHANNELS_API/channels/?user=cdnlivetv&plan=free", headers = cdnHeaders).text
        }
        val response: CdnChannelResponse = mapper.readValue(text)
        cachedCdnChannels = response.channels
        cdnChannelsCacheTimestamp = System.currentTimeMillis()
        return response.channels
    }

    private suspend fun fetchCdnEvents(): Map<String, List<Event>> {
        if (cdnEventsCacheValid()) return cachedCdnEvents!!
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val url = "$CDN_EVENTS_API/events/sports/?user=cdnlivetv&plan=free"
        val text = withContext(Dispatchers.IO) {
            app.get(url, headers = cdnHeaders).text
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
        cachedCdnEvents = result
        cdnEventsCacheTimestamp = System.currentTimeMillis()
        return result
    }

    private fun resolveDamiPoster(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        if (poster.startsWith("http")) return poster
        return "$mainUrl$poster"
    }

    private fun buildCdnPlayerUrl(channelName: String, code: String): String {
        return "$CDN_PLAYER_URL/api/v1/channels/player/?name=${
            java.net.URLEncoder.encode(channelName, "UTF-8")
        }&code=$code&user=cdnlivetv&plan=free"
    }

    private fun parseCdnPlayerUrl(url: String): Pair<String?, String?> {
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            when (request.data) {
                "dami_live" -> buildDamiLivePage()
                "dami_livetv" -> buildDamiLiveTVPage()
                "cdn_live" -> buildCdnLivePage()
                "cdn_all" -> buildCdnAllPage()
                "cdn_us", "cdn_gb", "cdn_es", "cdn_de", "cdn_au", "cdn_br" ->
                    buildCdnCountryPage(request.data.removePrefix("cdn_"))
                else -> when {
                    request.data.startsWith("sport_") -> buildCdnSportPage(request.data)
                    else -> newHomePageResponse(list = mutableListOf(), hasNext = false)
                }
            }
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    private suspend fun buildDamiLivePage(): HomePageResponse {
        val all = fetchDamiMatches()
        val live = all.filter { isDamiLive(it) }
        val items = mutableListOf<HomePageList>()

        if (live.isNotEmpty()) {
            val byCat = live.groupBy { it.category ?: "other" }
            for ((cat, matches) in byCat) {
                val label = categoryIcons[cat]?.let { "$it $cat" } ?: cat
                items.add(HomePageList(
                    label,
                    matches.mapNotNull { it.toDamiSearchResponse() },
                    isHorizontalImages = true
                ))
            }
        }
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun buildDamiLiveTVPage(): HomePageResponse {
        val channels = fetchDamiChannels()
        val grouped = channels.groupBy { it.country?.code ?: "intl" }
            .mapValues { (_, chs) -> chs.sortedBy { it.name } }
        val sorted = grouped.entries.sortedByDescending { (_, chs) -> chs.size }

        val items = sorted.map { (code, chs) ->
            val first = chs.first()
            val flag = first.country?.flag ?: "\uD83C\uDF0D"
            val name = first.country?.name ?: code.uppercase()
            HomePageList(
                "$flag $name (${chs.size})",
                chs.mapNotNull { it.toDamiChannelSearchResponse() },
                isHorizontalImages = true
            )
        }
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun buildCdnLivePage(): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val liveChannels = fetchCdnChannels()
                .filter { it.status == "online" && (it.viewers ?: 0) > 0 }
                .sortedByDescending { it.viewers ?: 0 }

            if (liveChannels.isNotEmpty()) {
                val searchItems = liveChannels.mapNotNull { ch ->
                    val name = ch.name ?: return@mapNotNull null
                    val playerUrl = buildCdnPlayerUrl(name, ch.code ?: "us")
                    newLiveSearchResponse(name, playerUrl, TvType.Live) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = cdnPosterHeaders
                    }
                }
                items.add(HomePageList("\uD83D\uDD34 Live Now", searchItems, isHorizontalImages = false))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun buildCdnAllPage(): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val searchItems = fetchCdnChannels().mapNotNull { ch ->
                val name = ch.name ?: return@mapNotNull null
                val playerUrl = buildCdnPlayerUrl(name, ch.code ?: "us")
                newLiveSearchResponse(name, playerUrl, TvType.Live) {
                    this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                    this.posterHeaders = cdnPosterHeaders
                }
            }
            if (searchItems.isNotEmpty()) {
                items.add(HomePageList("\uD83D\uDCFA All Channels", searchItems, isHorizontalImages = false))
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun buildCdnCountryPage(code: String): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        try {
            val filtered = fetchCdnChannels().filter {
                (it.code ?: "").equals(code, ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                val searchItems = filtered.mapNotNull { ch ->
                    val name = ch.name ?: return@mapNotNull null
                    val playerUrl = buildCdnPlayerUrl(name, ch.code ?: "us")
                    newLiveSearchResponse(name, playerUrl, TvType.Live) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = cdnPosterHeaders
                    }
                }
                items.add(
                    HomePageList(
                        cdnCodeNames[code.lowercase()] ?: code.uppercase(),
                        searchItems,
                        isHorizontalImages = false
                    )
                )
            }
        } catch (_: Exception) {}
        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun buildCdnSportPage(identifier: String): HomePageResponse {
        val sportSlug = identifier.removePrefix("sport_").lowercase()
        val label = sportLabels[sportSlug] ?: sportSlug.replaceFirstChar { it.uppercase() }

        return try {
            val allEventsMap = fetchCdnEvents()
            val apiKey = apiSportKey(sportSlug)
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
                val detailUrl = "$CDN_PLAYER_URL/event/watch/$gameID?sport=$sportSlug"

                val statusTag = when (event.status) {
                    "live" -> "\uD83D\uDD34 LIVE"
                    "upcoming" -> "\u23F3 Upcoming"
                    else -> event.status ?: ""
                }

                newLiveSearchResponse("$title $statusTag", detailUrl, TvType.Live) {
                    this.posterUrl = event.countryIMG?.takeIf { it.isNotBlank() }
                        ?: event.homeTeamIMG?.takeIf { it.isNotBlank() }
                    this.posterHeaders = cdnPosterHeaders
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

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()

        val damiMatches = try { fetchDamiMatches() } catch (_: Exception) { emptyList() }
        results.addAll(
            damiMatches
                .filter { m ->
                    (m.title?.lowercase()?.contains(q) == true) ||
                    (m.teams?.home?.name?.lowercase()?.contains(q) == true) ||
                    (m.teams?.away?.name?.lowercase()?.contains(q) == true) ||
                    (m.league?.lowercase()?.contains(q) == true) ||
                    (m.category?.lowercase()?.contains(q) == true)
                }
                .mapNotNull { it.toDamiSearchResponse() }
        )

        val damiChannels = try { fetchDamiChannels() } catch (_: Exception) { emptyList() }
        results.addAll(
            damiChannels
                .filter { it.name?.lowercase()?.contains(q) == true }
                .mapNotNull { it.toDamiChannelSearchResponse() }
        )

        val cdnChannels = try { fetchCdnChannels() } catch (_: Exception) { emptyList() }
        results.addAll(
            cdnChannels
                .filter { (it.name ?: "").lowercase().contains(q) }
                .mapNotNull { ch ->
                    val name = ch.name ?: return@mapNotNull null
                    val playerUrl = buildCdnPlayerUrl(name, ch.code ?: "us")
                    newLiveSearchResponse(name, playerUrl, TvType.Live) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = cdnPosterHeaders
                    }
                }
        )

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return when {
            url.contains("$mainUrl/channel/") -> loadDamiChannel(url)
            url.contains("$mainUrl/event/") -> loadDamiEvent(url)
            url.contains("$CDN_PLAYER_URL/event/watch/") -> loadCdnEvent(url)
            else -> loadCdnChannel(url)
        }
    }

    private suspend fun loadDamiChannel(url: String): LoadResponse? {
        return try {
            val channelName = url.removePrefix("$mainUrl/channel/")
                .replace("%20", " ")
                .substringBefore("?").substringBefore("#")
            val channels = fetchDamiChannels()
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
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadDamiEvent(url: String): LoadResponse? {
        return try {
            val matchId = url.removePrefix("$mainUrl/event/").substringBefore("?").substringBefore("#")
            val matches = fetchDamiMatches()
            val match = matches.find { it.id == matchId } ?: return null

            val title = damiMatchTitle(match)
            val statusEmoji = if (isDamiLive(match)) "\uD83D\uDD34 LIVE" else "\u23F3 Upcoming"

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

            val embedUrl = match.embedUrl?.takeIf { it.isNotBlank() }
            val dataUrl = if (embedUrl != null) {
                "$mainUrl/event/$matchId?embed=${java.net.URLEncoder.encode(embedUrl, "UTF-8")}"
            } else {
                "$mainUrl/event/$matchId"
            }

            newMovieLoadResponse(title, url, TvType.Live, dataUrl) {
                this.posterUrl = resolveDamiPoster(match.poster)
                this.posterHeaders = posterHeaders
                this.plot = desc.trim()
                this.tags = listOfNotNull(match.category, match.league, match.status)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadCdnEvent(url: String): LoadResponse? {
        return try {
            val gameID = url.substringAfterLast("/").substringBefore("?")
            val sport = url.substringAfter("sport=", "").substringBefore("&").takeIf { it.isNotBlank() }
                ?: return null

            val events = fetchCdnEvents()
            val apiKey = apiSportKey(sport.lowercase())
            val matchingSport = events[apiKey]
                ?: events.entries.firstOrNull { it.key.equals(apiKey, ignoreCase = true) }?.value
                ?: return null

            val event = matchingSport.find { it.gameID == gameID } ?: return null
            val title = "${event.homeTeam ?: "?"} vs ${event.awayTeam ?: "?"}"
            val totalViewers = event.channels?.sumOf { it.viewers ?: 0 }?.takeIf { it > 0 }

            val description = buildString {
                event.tournament?.let { appendLine("\uD83C\uDFC6 Tournament: $it") }
                event.country?.let { appendLine("\uD83C\uDF0D Country: $it") }
                event.time?.let { appendLine("\uD83D\uDD50 Time: $it") }
                event.start?.let { appendLine("\uD83D\uDCC5 Start: $it") }
                appendLine("\uD83D\uDCCA Status: ${event.status ?: "unknown"}")
                totalViewers?.let { appendLine("\uD83D\uDC41\uFE0F Total viewers: $it") }
                val sourceCount = event.channels?.size ?: 0
                if (sourceCount > 0) appendLine("\uD83D\uDCE1 Available sources: $sourceCount")
            }

            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = event.homeTeamIMG?.takeIf { it.isNotBlank() }
                    ?: event.countryIMG?.takeIf { it.isNotBlank() }
                this.posterHeaders = cdnPosterHeaders
                this.plot = description.trim()
                this.tags = listOfNotNull(event.country, event.tournament, event.status)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadCdnChannel(url: String): LoadResponse? {
        val params = parseCdnPlayerUrl(url)
        val channelName = params.first ?: return null
        val channelCode = params.second ?: "us"

        return try {
            val mapper = jacksonObjectMapper().registerKotlinModule()
            val apiUrl = "$CDN_CHANNELS_API/channels/?user=cdnlivetv&plan=free&code=$channelCode"
            val responseText = withContext(Dispatchers.IO) {
                app.get(apiUrl, headers = cdnHeaders).text
            }
            val channelResponse: CdnChannelResponse = mapper.readValue(responseText)

            val channelData = channelResponse.channels.find { ch ->
                (ch.name ?: "").equals(channelName, ignoreCase = true) ||
                    java.net.URLEncoder.encode(ch.name ?: "", "UTF-8")
                        .equals(channelName, ignoreCase = true)
            }

            val title = channelData?.name ?: java.net.URLDecoder.decode(channelName, "UTF-8")
            val status = channelData?.status ?: "unknown"
            val viewers = channelData?.viewers

            val description = buildString {
                appendLine("\uD83D\uDCCA Status: $status")
                if (viewers != null && viewers > 0) appendLine("\uD83D\uDC41\uFE0F Viewers: $viewers")
                val countryName = channelData?.code?.let { cdnCodeNames[it.lowercase()] }
                if (countryName != null) appendLine("\uD83C\uDF0D $countryName")
            }

            newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = channelData?.image?.takeIf { it.isNotBlank() }
                this.posterHeaders = cdnPosterHeaders
                this.plot = description.trim()
            }
        } catch (e: Exception) {
            try {
                newMovieLoadResponse(name, url, TvType.Live, url) {
                    this.posterHeaders = cdnPosterHeaders
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            data.contains("$mainUrl/channel/") -> loadDamiChannelLinks(data, subtitleCallback, callback)
            data.contains("$mainUrl/event/") -> loadDamiEventLinks(data, subtitleCallback, callback)
            data.contains("$CDN_PLAYER_URL/event/watch/") -> loadCdnEventLinks(data, subtitleCallback, callback)
            else -> loadCdnChannelLinks(data, subtitleCallback, callback)
        }
    }

    private suspend fun loadDamiChannelLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channelName = data.removePrefix("$mainUrl/channel/")
            .replace("%20", " ")
            .substringBefore("?").substringBefore("#")
        val channels = try { fetchDamiChannels() } catch (_: Exception) { return false }
        val channel = channels.find {
            it.name.equals(channelName, ignoreCase = true) || it.id == channelName
        } ?: return false

        val qualityUrls = mutableListOf<Pair<String, String>>()
        channel.qualities?.forEach { q ->
            if (!q.url.isNullOrBlank() && !q.quality.isNullOrBlank()) {
                qualityUrls.add(q.quality to q.url)
            }
        }
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
            } catch (_: Exception) { }
        }
        return foundAny
    }

    private suspend fun loadDamiEventLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.removePrefix("$mainUrl/event/").substringBefore("?").substringBefore("#")
        val embedParam = data.substringAfter("embed=", "").substringBefore("&").takeIf { it.isNotBlank() }
        val embedUrl = embedParam?.let { java.net.URLDecoder.decode(it, "UTF-8") }

        // Primary: Use embed URL with loadExtractor (like Streamed approach)
        // Embed sites serve true live HLS playlists without #EXT-X-ENDLIST
        if (!embedUrl.isNullOrBlank()) {
            try {
                loadExtractor(
                    url = embedUrl,
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                return true
            } catch (_: Exception) { }
        }

        // Fallback: Direct HLS URLs from DamiTV CDN (may have #EXT-X-ENDLIST)
        val matches = try { fetchDamiMatches() } catch (_: Exception) { return false }
        val match = matches.find { it.id == matchId }

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
                } catch (_: Exception) { }
            }
            if (foundAny) return true
        }

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
        } catch (_: Exception) { }

        return false
    }

    private suspend fun loadCdnEventLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pathBeforeQuery = url.substringBefore("?")
        val gameID = pathBeforeQuery.substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: return false
        val sport = url.substringAfter("sport=", "").substringBefore("&").takeIf { it.isNotBlank() }
            ?: return false

        val events = fetchCdnEvents()
        val apiKey = apiSportKey(sport.lowercase())
        val matchingSport = events[apiKey]
            ?: events.entries.firstOrNull { it.key.equals(apiKey, ignoreCase = true) }?.value
            ?: return false

        val event = matchingSport.find { it.gameID == gameID } ?: return false
        val channels = event.channels?.filter { it.channelName?.isNotBlank() == true } ?: return false

        var foundAny = false
        for (ch in channels) {
            try {
                val chName = ch.channelName ?: continue
                val chCode = ch.channelCode ?: "us"
                val countryName = ch.channelCode
                    ?.let { cdnCodeNames[it.lowercase()] }
                    ?: ch.channelCode?.uppercase()
                    ?: "Unknown"
                val sourceLabel = if (chName.isNotBlank()) "$chName ($countryName)" else countryName

                val directUrl = ch.url?.takeIf { it.isNotBlank() }

                if (directUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name [$sourceLabel]",
                            name = "Direct [$sourceLabel]",
                            url = directUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = "$CDN_PLAYER_URL/"
                            this.headers = cdnStreamingHeaders
                        }
                    )
                    foundAny = true
                } else {
                    // Fallback: CDN player page + WebView extraction
                    val sourceUrl = buildCdnPlayerUrl(chName, chCode)
                    loadExtractor(
                        url = sourceUrl,
                        subtitleCallback = subtitleCallback,
                        callback = { link ->
                            val wrapped = newExtractorLink(
                                source = "${link.source} [$sourceLabel]",
                                name = "${link.name} [$sourceLabel]",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                            }
                            callback(wrapped)
                        }
                    )
                    foundAny = true
                }
            } catch (_: Exception) { }
        }

        return foundAny
    }

    private suspend fun loadCdnChannelLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val params = parseCdnPlayerUrl(data)
        val channelName = params.first ?: return false

        val channels = fetchCdnChannels()
        val matches = channels.filter { ch ->
            ch.name?.equals(channelName, ignoreCase = true) == true
        }

        if (matches.isEmpty()) {
            return try {
                loadExtractor(
                    url = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                true
            } catch (_: Exception) {
                false
            }
        }

        for (ch in matches) {
            try {
                val chName = ch.name ?: continue
                val chCode = ch.code ?: "us"
                val sourceLabel = cdnCodeNames[ch.code?.lowercase()] ?: ch.code?.uppercase() ?: "Unknown"

                val directUrl = ch.url?.takeIf { it.isNotBlank() }

                if (directUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$sourceLabel ${chName}",
                            url = directUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = "$CDN_PLAYER_URL/"
                            this.headers = cdnStreamingHeaders
                        }
                    )
                } else {
                    // Fallback: CDN player page + WebView extraction
                    val sourceUrl = buildCdnPlayerUrl(chName, chCode)
                    loadExtractor(
                        url = sourceUrl,
                        subtitleCallback = subtitleCallback,
                        callback = { link ->
                            val wrapped = newExtractorLink(
                                source = "${link.source} [$sourceLabel]",
                                name = "${link.name} [$sourceLabel]",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                            }
                            callback(wrapped)
                        }
                    )
                }
            } catch (_: Exception) { }
        }

        return true
    }

    private fun ApiMatch.toDamiSearchResponse(): LiveSearchResponse? {
        val title = damiMatchTitle(this)
        val posterUrl = resolveDamiPoster(poster)
            ?: teams?.home?.badge?.takeIf { it.isNotBlank() }
        val detailUrl = "$mainUrl/event/${id ?: return null}"

        val displayTitle = if (isDamiLive(this@toDamiSearchResponse)) "$title \uD83D\uDD34" else title
        return newLiveSearchResponse(displayTitle, detailUrl, TvType.Live) {
            this.posterUrl = posterUrl
            this.posterHeaders = posterHeaders
        }
    }

    private fun ApiChannel.toDamiChannelSearchResponse(): LiveSearchResponse? {
        val channelId = id ?: name ?: return null
        val detailUrl = "$mainUrl/channel/${name?.replace(" ", "%20")}"
        return newLiveSearchResponse(name ?: "Channel", detailUrl, TvType.Live) {
            this.posterUrl = logo
            this.posterHeaders = posterHeaders
        }
    }
}
