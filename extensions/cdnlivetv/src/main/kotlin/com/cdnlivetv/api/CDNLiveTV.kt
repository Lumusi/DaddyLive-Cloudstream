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
 * JSON API: /api/v1/channels/?user=cdnlivetv&plan=free (762 channels, 38 country codes)
 * Player page: /api/v1/channels/player/?name=...&code=... (OPlayer iframe, needs WebView)
 * Stream delivery: iframe embeds (wikisport.club, dlhd.link, etc.) or direct HLS
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

        // Cache to avoid re-fetching all 762 channels on every action
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        @Volatile
        private var cachedChannels: List<ChannelData>? = null
        @Volatile
        private var cacheTimestamp: Long = 0

        fun isCacheValid(): Boolean {
            return cachedChannels != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS
        }

        fun getCachedChannels(): List<ChannelData>? = cachedChannels

        fun updateCache(channels: List<ChannelData>) {
            cachedChannels = channels
            cacheTimestamp = System.currentTimeMillis()
        }
    }

    // Country code -> friendly name for main page sections
    private val codeNames = mapOf(
        "us" to "🇺🇸 United States",
        "gb" to "🇬🇧 United Kingdom",
        "es" to "🇪🇸 Spain",
        "de" to "🇩🇪 Germany",
        "au" to "🇦🇺 Australia",
        "fr" to "🇫🇷 France",
        "it" to "🇮🇹 Italy",
        "br" to "🇧🇷 Brazil",
        "pl" to "🇵🇱 Poland",
        "za" to "🇿🇦 South Africa",
        "gr" to "🇬🇷 Greece",
        "rs" to "🇷🇸 Serbia",
        "hr" to "🇭🇷 Croatia",
        "sa" to "🇸🇦 Saudi Arabia",
        "pt" to "🇵🇹 Portugal",
        "nl" to "🇳🇱 Netherlands",
        "at" to "🇦🇹 Austria",
        "cz" to "🇨🇿 Czech Republic",
        "dk" to "🇩🇰 Denmark",
        "se" to "🇸🇪 Sweden",
        "il" to "🇮🇱 Israel",
        "ar" to "🇦🇷 Argentina",
        "mx" to "🇲🇽 Mexico",
        "tr" to "🇹🇷 Turkey",
        "ro" to "🇷🇴 Romania",
        "cy" to "🇨🇾 Cyprus",
        "in" to "🇮🇳 India",
        "be" to "🇧🇪 Belgium",
        "ae" to "🇦🇪 UAE",
        "hu" to "🇭🇺 Hungary",
        "uy" to "🇺🇾 Uruguay",
        "cl" to "🇨🇱 Chile",
        "co" to "🇨🇴 Colombia",
        "eg" to "🇪🇬 Egypt",
        "ru" to "🇷🇺 Russia"
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
        val url = request.data
        val items = mutableListOf<HomePageList>()

        return try {
            when {
                url.contains("plan=all") -> getLiveChannels()
                url.contains("&code=") -> getChannelsByCountry(url)
                url.contains("/channels/") -> getAllChannels(url)
                else -> newHomePageResponse(list = items, hasNext = false)
            }
        } catch (e: Exception) {
            newHomePageResponse(list = items, hasNext = false)
        }
    }

    private suspend fun fetchAllChannels(): List<ChannelData> {
        if (isCacheValid()) {
            return getCachedChannels()!!
        }

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
        val mapper = jacksonObjectMapper().registerKotlinModule()

        try {
            val allChannels = fetchAllChannels()
            val liveChannels = allChannels.filter { it.status == "online" && it.viewers ?: 0 > 0 }
                .sortedByDescending { it.viewers ?: 0 }

            if (liveChannels.isNotEmpty()) {
                val dayItems = mutableListOf<LiveSearchResponse>()
                for (ch in liveChannels) {
                    val playerUrl = "https://cdnlivetv.tv/api/v1/channels/player/?name=${java.net.URLEncoder.encode(ch.name ?: continue, "UTF-8")}&code=${ch.code ?: "us"}&user=cdnlivetv&plan=free"
                    dayItems.add(
                        newLiveSearchResponse(
                            ch.name ?: "Unknown",
                            playerUrl,
                            TvType.Live
                        ) {
                            this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                            this.posterHeaders = posterHeaders
                        }
                    )
                }

                items.add(
                    HomePageList(
                        name = "🔴 Live Now",
                        list = dayItems,
                        isHorizontalImages = false
                    )
                )
            }
        } catch (e: Exception) {
            // Fallback
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getAllChannels(url: String): HomePageResponse {
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val items = mutableListOf<HomePageList>()

        try {
            val channels = fetchAllChannels()
            val dayItems = mutableListOf<LiveSearchResponse>()

            for (ch in channels) {
                if (ch.name.isNullOrBlank()) continue
                val playerUrl = "https://cdnlivetv.tv/api/v1/channels/player/?name=${java.net.URLEncoder.encode(ch.name, "UTF-8")}&code=${ch.code ?: "us"}&user=cdnlivetv&plan=free"

                dayItems.add(
                    newLiveSearchResponse(
                        ch.name ?: "Unknown",
                        playerUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = posterHeaders
                    }
                )
            }

            if (dayItems.isNotEmpty()) {
                items.add(
                    HomePageList(
                        name = "📺 All Channels",
                        list = dayItems,
                        isHorizontalImages = false
                    )
                )
            }
        } catch (e: Exception) {
            // Fallback
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getChannelsByCountry(url: String): HomePageResponse {
        val code = url.substringAfterLast("code=").substringBefore("&").takeIf { it.isNotBlank() } ?: return newHomePageResponse(list = mutableListOf(), hasNext = false)

        val mapper = jacksonObjectMapper().registerKotlinModule()
        val items = mutableListOf<HomePageList>()

        try {
            val allChannels = fetchAllChannels()
            val filtered = allChannels.filter { (it.code ?: "").equals(code, ignoreCase = true) }

            if (filtered.isNotEmpty()) {
                val dayItems = mutableListOf<LiveSearchResponse>()
                for (ch in filtered) {
                    if (ch.name.isNullOrBlank()) continue
                    val playerUrl = "https://cdnlivetv.tv/api/v1/channels/player/?name=${java.net.URLEncoder.encode(ch.name, "UTF-8")}&code=${ch.code ?: "us"}&user=cdnlivetv&plan=free"

                    dayItems.add(
                        newLiveSearchResponse(
                            ch.name ?: "Unknown",
                            playerUrl,
                            TvType.Live
                        ) {
                            this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                            this.posterHeaders = posterHeaders
                        }
                    )
                }

                val countryName = codeNames[code.lowercase()] ?: code.uppercase()
                items.add(
                    HomePageList(
                        name = countryName,
                        list = dayItems,
                        isHorizontalImages = false
                    )
                )
            }
        } catch (e: Exception) {
            // Fallback
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()

        try {
            val channels = if (isCacheValid()) {
                getCachedChannels()!!
            } else {
                fetchAllChannels()
            }.filter { ch ->
                val name = ch.name ?: return@filter false
                name.lowercase().contains(q)
            }

            for (ch in channels) {
                val playerUrl = "https://cdnlivetv.tv/api/v1/channels/player/?name=${java.net.URLEncoder.encode(ch.name ?: continue, "UTF-8")}&code=${ch.code ?: "us"}&user=cdnlivetv&plan=free"

                results.add(
                    newLiveSearchResponse(
                        ch.name ?: "Unknown",
                        playerUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                        this.posterHeaders = posterHeaders
                    }
                )
            }
        } catch (e: Exception) {
            // Search failed
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            newMovieLoadResponse(
                name,
                url,
                TvType.Live,
                url
            ) {
                this.posterHeaders = posterHeaders
            }
        } catch (e: Exception) {
            null
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