package com.daddylive

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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

    companion object {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "application/json, text/html, */*; q=0.01",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "https://daddylive.org/"
        )
        val posterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Referer" to "https://daddylive.org/"
        )
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/api/events" to "All Events",
    )

    private fun getPosterUrl(poster: String?, eventId: String?): String? {
        return when {
            poster.isNullOrEmpty() -> eventId?.let { "${mainUrl}/api/images/badge/${it}.webp" }
            poster.startsWith("/") || poster.startsWith("http") -> "${mainUrl}${poster}"
            else -> poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = jacksonObjectMapper().registerKotlinModule()

        val textdoc = withContext(Dispatchers.IO) {
            app.get(request.data, headers = headers).text
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

                    val channelId = firstChannel?.channel_id ?: continue
                    val href = "${mainUrl}/embed/embed.php?id=${channelId}&player=1&source=${source}"

                    dayItems.add(
                        newLiveSearchResponse(title, href, TvType.Live) {
                            this.posterUrl = getPosterUrl(event.poster, event.id)
                            this.posterHeaders = posterHeaders
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
            app.get("${mainUrl}/api/events", headers = headers).text
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
                    val href = "${mainUrl}/embed/embed.php?id=${channelId}&player=1&source=${source}"

                    results.add(
                        newLiveSearchResponse(title, href, TvType.Live) {
                            this.posterUrl = getPosterUrl(event.poster, event.id)
                            this.posterHeaders = posterHeaders
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
        val idParam = url.substringAfter("id=", "").substringBefore("&")
        val sourceParam = url.substringAfter("source=", "").substringBefore("&")
        if (idParam.isEmpty()) return null

        val mapper = jacksonObjectMapper().registerKotlinModule()
        val textdoc = withContext(Dispatchers.IO) {
            app.get("${mainUrl}/api/events", headers = headers).text
        }

        val events: List<DayEvents> = mapper.readValue(textdoc)

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
                        this.posterUrl = getPosterUrl(event.poster, event.id)
                        this.posterHeaders = posterHeaders
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