package com.daddylive

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DaddyLive : MainAPI() {
    override var mainUrl = "https://daddylive.org"
    override var name = "DaddyLive"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/api/events" to "All Events",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = jacksonObjectMapper().registerKotlinModule()

        val textdoc = withContext(Dispatchers.IO) {
            app.get(request.data).text
        }

        val events: List<DayEvents> = mapper.readValue(textdoc)

        val items = mutableListOf<HomePageList>()

        for (dayData in events) {
            val dayName = dayData.day ?: "Unknown"
            val dayItems = mutableListOf<LiveSearchResponse>()

            for ((_, eventList) in dayData.categories ?: emptyMap()) {
                for (event in eventList) {
                    val title = event.event ?: continue
                    val firstChannel = event.channels?.firstOrNull()
                    val source = event.source ?: "tv"

                    // Build embed URL from channel_id
                    val channelId = firstChannel?.channel_id ?: continue
                    val encodedId = java.net.URLEncoder.encode(channelId, "UTF-8")
                    val href = "${mainUrl}/embed/embed.php?id=${encodedId}&player=1&source=${source}"

                    dayItems.add(
                        newLiveSearchResponse(title, href, TvType.Live) {
                            this.posterUrl = null
                        }
                    )
                }
            }

            if (dayItems.isNotEmpty()) {
                items.add(
                    HomePageList(
                        name = dayName,
                        list = dayItems,
                        isHorizontalImages = false
                    )
                )
            }
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mapper = jacksonObjectMapper().registerKotlinModule()
        val textdoc = withContext(Dispatchers.IO) {
            app.get("${mainUrl}/api/events").text
        }

        val events: List<DayEvents> = mapper.readValue(textdoc)
        val q = query.lowercase().trim()

        val results = mutableListOf<SearchResponse>()

        for (dayData in events) {
            for ((_, eventList) in dayData.categories ?: emptyMap()) {
                for (event in eventList) {
                    val title = event.event ?: continue
                    if (!title.lowercase().contains(q)) continue

                    val firstChannel = event.channels?.firstOrNull()
                    val source = event.source ?: "tv"
                    val channelId = firstChannel?.channel_id ?: continue
                    val encodedId = java.net.URLEncoder.encode(channelId, "UTF-8")
                    val href = "${mainUrl}/embed/embed.php?id=${encodedId}&player=1&source=${source}"

                    results.add(
                        newLiveSearchResponse(title, href, TvType.Live) {
                            this.posterUrl = null
                        }
                    )
                }
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        // Extract channel_id and source from the embed URL
        // URL format: ...embed.php?id=CHANNEL_ID&player=1&source=SOURCE
        val idParam = url.substringAfter("id=", "").substringBefore("&")
        val sourceParam = url.substringAfter("source=", "").substringBefore("&")
        if (idParam.isEmpty()) return null

        val mapper = jacksonObjectMapper().registerKotlinModule()
        val textdoc = withContext(Dispatchers.IO) {
            app.get("${mainUrl}/api/events").text
        }

        val events: List<DayEvents> = mapper.readValue(textdoc)

        // Find the matching event by searching all channels
        for (dayData in events) {
            for ((_, eventList) in dayData.categories ?: emptyMap()) {
                for (event in eventList) {
                    val matchingChannel = event.channels?.find {
                        it.channel_id == idParam
                    } ?: continue

                    val title = event.event ?: "Unknown"
                    val channelName = matchingChannel.channel_name ?: "Unknown"
                    val fullTitle = "$title - $channelName"
                    val description = event.time?.let { "Scheduled: $it" } ?: ""

                    return newMovieLoadResponse(fullTitle, url, TvType.Live, url) {
                        this.plot = description
                        this.tags = listOfNotNull(event.source)
                    }
                }
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // The data URL is the embed URL, which renders an iframe that contains the actual player
        // We pass it to the WebView extractor to resolve the actual m3u8 stream
        loadExtractor(
            url = data,
            referer = "$mainUrl/",
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DayEvents(
        @JsonProperty("day") val day: String?,
        @JsonProperty("categories") val categories: Map<String, List<Event>>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Event(
        @JsonProperty("time") val time: String?,
        @JsonProperty("event") val event: String?,
        @JsonProperty("channels") val channels: List<Channel>?,
        @JsonProperty("source") val source: String?,
        @JsonProperty("channels2") val channels2: List<Channel>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Channel(
        @JsonProperty("channel_name") val channel_name: String?,
        @JsonProperty("channel_id") val channel_id: String?,
        @JsonProperty("url") val url: String?
    )
}