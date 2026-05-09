package com.sportscdnext

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
 * SportsBite (livetv.moviebite.cc / sportsbite.cv) - Live sports streaming provider.
 * Real JSON API: channels.json (314 channels), adminlinks.json (live events).
 * Channel types: sports, news, kids, fun
 * Stream delivery: direct HLS proxy (store.sportsbite.online) or iframe embeds (wikisport.club, dlhd.link)
 */
class SportsBite : MainAPI() {
    override var mainUrl = "https://livetv.moviebite.cc"
    override var name = "SportsBite"
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
            "Referer" to "https://livetv.moviebite.cc/"
        )
        val posterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Referer" to "https://livetv.moviebite.cc/"
        )

        // Cache to avoid re-fetching all 314 channels on every search/main page load
        @Volatile
        private var cachedChannels: List<ChannelData>? = null
        @Volatile
        private var cacheTimestamp: Long = 0
        private const val CACHE_TTL_MS: Long = 5 * 60 * 1000L // 5 minutes

        fun isCacheValid(): Boolean {
            return cachedChannels != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS
        }

        fun getCachedChannels(): List<ChannelData>? = cachedChannels

        fun updateCache(channels: List<ChannelData>) {
            cachedChannels = channels
            cacheTimestamp = System.currentTimeMillis()
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/channels.json?type=sports" to "🏈 Sports",
        "${mainUrl}/channels.json?type=news" to "📰 News",
        "${mainUrl}/channels.json?type=kids" to "👶 Kids",
        "${mainUrl}/channels.json?type=fun" to "🎮 Fun",
        "${mainUrl}/channels.json?type=all" to "📺 All Channels",
        "${mainUrl}/api/v1/matches/live" to "🔴 Live Now",
        "${mainUrl}/adminlinks.json" to "⚡ Admin Links",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val items = mutableListOf<HomePageList>()

        return try {
            when {
                url.contains("matches/live") -> getLiveMatches()
                url.contains("channels.json") -> getChannelsList(url)
                url.contains("adminlinks.json") -> getAdminLinks()
                else -> newHomePageResponse(list = items, hasNext = false)
            }
        } catch (e: Exception) {
            newHomePageResponse(list = items, hasNext = false)
        }
    }

    private suspend fun fetchAllChannels(): List<ChannelData> {
        // Return cached if valid
        if (isCacheValid()) {
            return getCachedChannels()!!
        }

        val mapper = jacksonObjectMapper().registerKotlinModule()
        val textdoc = withContext(Dispatchers.IO) {
            app.get("${mainUrl}/channels.json", headers = headers).text
        }
        val channels: List<ChannelData> = mapper.readValue(textdoc)
        updateCache(channels)
        return channels
    }

    private suspend fun getLiveMatches(): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val mapper = jacksonObjectMapper().registerKotlinModule()

        try {
            val textdoc = withContext(Dispatchers.IO) {
                app.get("${mainUrl}/api/v1/matches/live", headers = headers).text
            }
            val matches: List<LiveMatch> = mapper.readValue(textdoc)

            val liveItems = mutableListOf<LiveSearchResponse>()
            for (match in matches) {
                val matchId = match.id ?: continue
                val title = match.title ?: "${match.homeTeam ?: "?"} vs ${match.awayTeam ?: "?"}"

                liveItems.add(
                    newLiveSearchResponse(title, "${mainUrl}/match/$matchId", TvType.Live) {
                        this.posterUrl = match.image
                        this.posterHeaders = posterHeaders
                    }
                )
            }

            if (liveItems.isNotEmpty()) {
                items.add(
                    HomePageList(
                        name = "🔴 Live Now",
                        list = liveItems,
                        isHorizontalImages = false
                    )
                )
            }
        } catch (e: Exception) {
            // Fallback to channel list
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getChannelsList(url: String): HomePageResponse {
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val items = mutableListOf<HomePageList>()

        try {
            // Use cached channels if available (from /channels.json without type filter)
            var channels = if (url.contains("type=")) {
                val typeParam = url.substringAfter("type=").substringBefore("&")
                val allChannels = fetchAllChannels()
                if (typeParam == "all") allChannels
                else allChannels.filter { (it.type ?: "").equals(typeParam, ignoreCase = true) }
            } else {
                fetchAllChannels()
            }

            // Fallback: fetch directly if cache was empty
            if (channels.isEmpty()) {
                val textdoc = withContext(Dispatchers.IO) {
                    app.get(url, headers = headers).text
                }
                channels = mapper.readValue(textdoc)
            }

            val channelGroups = channels.groupBy { it.type ?: "other" }

            for ((groupName, channelList) in channelGroups) {
                val dayItems = mutableListOf<LiveSearchResponse>()
                for (ch in channelList) {
                    // Skip channels with no stream source at all
                    if (ch.stream_url.isNullOrBlank() && ch.iframe_url.isNullOrBlank()) continue

                    // Prefer iframe URL (WebView extraction is more reliable than double-proxy)
                    val primaryUrl = ch.iframe_url?.takeIf { it.isNotBlank() } ?: ch.stream_url
                    val streamUrl = primaryUrl ?: continue

                    dayItems.add(
                        newLiveSearchResponse(ch.name ?: "Unknown", streamUrl, TvType.Live) {
                            this.posterUrl = ch.image?.takeIf { it.isNotBlank() }
                            this.posterHeaders = posterHeaders
                        }
                    )
                }

                if (dayItems.isNotEmpty()) {
                    items.add(
                        HomePageList(
                            name = groupName.replaceFirstChar { it.uppercase() },
                            list = dayItems,
                            isHorizontalImages = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback: try parsing HTML
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    private suspend fun getAdminLinks(): HomePageResponse {
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val items = mutableListOf<HomePageList>()

        try {
            val textdoc = withContext(Dispatchers.IO) {
                app.get("${mainUrl}/adminlinks.json", headers = headers).text
            }
            val links: List<AdminLink> = mapper.readValue(textdoc)

            val dayItems = mutableListOf<LiveSearchResponse>()
            for (link in links) {
                val hlsUrl = link.hls1 ?: link.hls2 ?: continue
                val title = link.name?.ifEmpty { null } ?: "Live Stream"

                dayItems.add(
                    newLiveSearchResponse(title, hlsUrl, TvType.Live) {
                        this.posterHeaders = posterHeaders
                    }
                )
            }

            if (dayItems.isNotEmpty()) {
                items.add(
                    HomePageList(
                        name = "⚡ Admin Links",
                        list = dayItems,
                        isHorizontalImages = false
                    )
                )
            }
        } catch (e: Exception) {
            // No data
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()

        try {
            // Use cached channels if valid to avoid hitting the API on every keystroke
            val channels = if (isCacheValid()) {
                getCachedChannels()!!
            } else {
                fetchAllChannels()
            }.filter { ch ->
                val name = ch.name ?: return@filter false
                name.lowercase().contains(q)
            }

            for (ch in channels) {
                val primaryUrl = ch.iframe_url?.takeIf { it.isNotBlank() } ?: ch.stream_url
                val streamUrl = primaryUrl ?: continue

                results.add(
                    newLiveSearchResponse(ch.name ?: "Unknown", streamUrl, TvType.Live) {
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
                "SportsBite Live Stream",
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var handled = false

        // 1. Handle SportsBite proxy URLs directly (store.sportsbite.online)
        if (data.contains("store.sportsbite.online/api/proxy/hls") ||
            data.contains("store.sportsbite.online/proxy/hls")) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "SportsBite HD",
                    url = data,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer" to "$mainUrl/",
                        "Origin" to "$mainUrl/"
                    )
                }
            )
            handled = true
        }

        // 2. Handle direct m3u8 links (admin links, cdnlivetv edge URLs, cloudfront, wurl, amagi, etc.)
        if (!handled && data.endsWith(".m3u8", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Direct HLS",
                    url = data,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Origin" to "$mainUrl/",
                        "Referer" to "$mainUrl/"
                    )
                }
            )
            handled = true
        }

        // 3. Handle iframe URLs from wikisport.club / dlhd.link -- feed to WebView extractor
        if (!handled) {
            try {
                loadExtractor(
                    url = data,
                    referer = "$mainUrl/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            } catch (e: Exception) {
                // Extractor handles its own fallbacks
            }
            handled = true
        }

        handled
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelData(
        @JsonProperty("name") val name: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("stream_url") val stream_url: String?,
        @JsonProperty("iframe_url") val iframe_url: String?,
        @JsonProperty("image") val image: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LiveMatch(
        @JsonProperty("id") val id: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("homeTeam") val homeTeam: String?,
        @JsonProperty("awayTeam") val awayTeam: String?,
        @JsonProperty("sportType") val sportType: String?,
        @JsonProperty("time") val time: String?,
        @JsonProperty("image") val image: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AdminLink(
        @JsonProperty("id") val id: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("hls1") val hls1: String?,
        @JsonProperty("hls2") val hls2: String?,
        @JsonProperty("iframe1") val iframe1: String?,
        @JsonProperty("iframe2") val iframe2: String?
    )
}