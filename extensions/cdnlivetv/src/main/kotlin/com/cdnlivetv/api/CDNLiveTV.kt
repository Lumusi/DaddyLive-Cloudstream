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
 * CDNLiveTV (cdnlivetv.tv / api.cdnlivetv.ru) - Live sports streaming provider.
 *
 * Architecture:
 * - API domain: api.cdnlivetv.ru (unprotected JSON API, 762 channels, 38 countries)
 * - Player domain: cdnlivetv.tv (Cloudflare-protected, OPlayer-based)
 *
 * Flow:
 * 1. API (/channels/) provides channel list with metadata (name, code, viewers, image, status)
 * 2. Player URL constructed: /api/v1/channels/player/?name={NAME}&code={CODE}&user=...
 * 3. load() enriches metadata from API (title, poster, description)
 * 4. loadLinks() dispatches the player URL to the WebView extractor
 * 5. WebView renders OPlayer, captures .m3u8 from network requests
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
        @Volatile private var cachedChannels: List<ChannelData>? = null
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

    override val mainPage = mainPageOf(
        "${mainUrl}/channels/?user=cdnlivetv&plan=free" to "📺 All Channels",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=us" to "🇺🇸 US",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=gb" to "🇬🇧 UK",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=es" to "🇪🇸 Spain",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=de" to "🇩🇪 Germany",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=au" to "🇦🇺 Australia",
        "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=br" to "🇧🇷 Brazil",
        "${mainUrl}/channels/?user=cdnlive&plan=all" to "🔴 Live Now",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            when {
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

    private fun buildPlayerUrl(channelName: String, code: String): String {
        return "https://cdnlivetv.tv/api/v1/channels/player/?name=${java.net.URLEncoder.encode(channelName, "UTF-8")}&code=$code&user=cdnlivetv&plan=free"
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
     * Parses the player URL, fetches channel metadata from the API,
     * and returns a LoadResponse with proper title/poster/description.
     */
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val params = parsePlayerUrl(url)
            val channelName = params.first ?: return null
            val channelCode = params.second ?: "us"

            val mapper = jacksonObjectMapper().registerKotlinModule()
            val apiUrl = "${mainUrl}/channels/?user=cdnlivetv&plan=free&code=$channelCode"
            val responseText = app.get(apiUrl, headers = headers).text
            val channelResponse: ChannelResponse = mapper.readValue(responseText)

            // Match by encoded or raw name
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
            // Fallback: return basic response
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
     * Dispatches the player URL directly to the WebView-based extractor.
     * The CDNLiveTVExtractor will render the OPlayer page and intercept the .m3u8 stream.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            loadExtractor(
                url = data,
                referer = "https://cdnlivetv.tv/",
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } catch (e: Exception) {
            false
        }
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