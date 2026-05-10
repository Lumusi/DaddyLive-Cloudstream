package com.ppvto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PpvTo : MainAPI() {
    override var mainUrl = "https://api.ppv.to"
    override var name = "PpvTo"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val apiUrl = "https://api.ppv.to/api/streams"
    private val webUrl = "https://ppv.to"

    override val mainPage = mainPageOf(
        "all" to "All Streams",
        "American Football" to "American Football",
        "Baseball" to "Baseball",
        "Basketball" to "Basketball",
        "Combat Sports" to "Combat Sports",
        "Cricket" to "Cricket",
        "Darts" to "Darts",
        "Football" to "Football",
        "Ice Hockey" to "Ice Hockey",
        "24/7 Streams" to "24/7 Streams"
    )

    private data class ApiResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("streams") val categories: List<Category>?,
        @JsonProperty("timestamp") val timestamp: Long?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Category(
        @JsonProperty("category") val category: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("always_live") val alwaysLive: Boolean?,
        @JsonProperty("streams") val streams: List<Stream>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Stream(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("tag") val tag: String?,
        @JsonProperty("source_tag") val sourceTag: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("blurhash") val blurhash: String?,
        @JsonProperty("colors") val colors: List<String>?,
        @JsonProperty("uri_name") val uriName: String?,
        @JsonProperty("starts_at") val startsAt: Long?,
        @JsonProperty("ends_at") val endsAt: Long?,
        @JsonProperty("always_live") val alwaysLive: Int?,
        @JsonProperty("locale") val locale: String?,
        @JsonProperty("category_name") val categoryName: String?,
        @JsonProperty("iframe") val iframe: String?,
        @JsonProperty("viewers") val viewers: String?,
        @JsonProperty("substreams") val substreams: List<Substream>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Substream(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("iframe") val iframe: String?,
        @JsonProperty("viewers") val viewers: String?
    )

    private suspend fun fetchAllStreams(): List<Pair<Stream, String>> {
        return try {
            val response = app.get(apiUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
                "Accept" to "application/json"
            ))
            val mapper = jacksonObjectMapper().registerKotlinModule()
            val apiResponse: ApiResponse = mapper.readValue(response.text)

            val result = mutableListOf<Pair<Stream, String>>()
            apiResponse.categories?.forEach { category ->
                val catName = category.category ?: "Unknown"
                category.streams?.forEach { stream ->
                    result.add(Pair(stream, catName))
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildPosterUrl(poster: String?): String? {
        if (poster.isNullOrEmpty()) return null
        return if (poster.startsWith("http")) {
            poster
        } else {
            "$mainUrl$poster"
        }
    }

    private fun formatTimeDescription(startsAt: Long?, endsAt: Long?, alwaysLive: Int?): String {
        if (alwaysLive == 1) {
            return "🔴 Live 24/7 Stream"
        }

        val currentTime = System.currentTimeMillis() / 1000
        val start = startsAt ?: return "Stream time unknown"
        val end = endsAt ?: 0

        return when {
            currentTime < start -> {
                val diff = start - currentTime
                val hours = diff / 3600
                val minutes = (diff % 3600) / 60
                when {
                    hours > 24 -> "Starts in ${hours / 24}d ${hours % 24}h"
                    hours > 0 -> "Starts in ${hours}h ${minutes}m"
                    else -> "Starts in ${minutes}m"
                }
            }
            currentTime in start..end -> "🔴 LIVE NOW"
            else -> "Stream ended"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allStreams = fetchAllStreams()
        val categoryFilter = request.data

        val filtered = if (categoryFilter == "all") {
            allStreams
        } else {
            allStreams.filter { it.second.equals(categoryFilter, ignoreCase = true) }
        }

        val items = filtered.mapNotNull { (stream, _) ->
            val name = stream.name ?: return@mapNotNull null
            val id = stream.id ?: return@mapNotNull null
            val href = "$webUrl/stream/$id"
            val posterUrl = buildPosterUrl(stream.poster)

            newLiveSearchResponse(name, href, TvType.Live) {
                this.posterUrl = posterUrl
                this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0")
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allStreams = fetchAllStreams()
        val q = query.lowercase().trim()

        val filtered = allStreams.filter { (stream, _) ->
            val nameMatch = stream.name?.lowercase()?.contains(q) == true
            val tagMatch = stream.tag?.lowercase()?.contains(q) == true
            val sourceTagMatch = stream.sourceTag?.lowercase()?.contains(q) == true
            val categoryMatch = stream.categoryName?.lowercase()?.contains(q) == true
            nameMatch || tagMatch || sourceTagMatch || categoryMatch
        }

        return filtered.mapNotNull { (stream, _) ->
            val name = stream.name ?: return@mapNotNull null
            val id = stream.id ?: return@mapNotNull null
            val href = "$webUrl/stream/$id"
            val posterUrl = buildPosterUrl(stream.poster)

            newLiveSearchResponse(name, href, TvType.Live) {
                this.posterUrl = posterUrl
                this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0")
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val streamId = url.substringAfterLast("/").toIntOrNull() ?: return null
        val allStreams = fetchAllStreams()

        val (stream, categoryName) = allStreams.find { it.first.id == streamId } ?: return null
        val title = stream.name ?: "Unknown Stream"
        val posterUrl = buildPosterUrl(stream.poster)
        val timeDesc = formatTimeDescription(stream.startsAt, stream.endsAt, stream.alwaysLive)
        val viewers = stream.viewers?.toIntOrNull() ?: 0
        val viewerText = if (viewers > 0) "\n\nViewers: $viewers" else ""
        val tag = stream.tag ?: stream.sourceTag ?: ""
        val tagText = if (tag.isNotEmpty()) "\nSource: $tag" else ""

        val description = "$timeDesc$tagText$viewerText"
        val tags = listOfNotNull(categoryName, stream.tag, stream.sourceTag).distinct()

        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0")
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val streamId = data.substringAfterLast("/").toIntOrNull() ?: return@withContext false
        val allStreams = fetchAllStreams()
        val (stream, _) = allStreams.find { it.first.id == streamId } ?: return@withContext false

        val iframeUrl = stream.iframe
        if (!iframeUrl.isNullOrEmpty()) {
            loadExtractor(
                url = iframeUrl,
                referer = webUrl,
                subtitleCallback = { sub -> subtitleCallback.invoke(sub) },
                callback = { link -> callback.invoke(link) }
            )
        }

        stream.substreams?.forEach { substream ->
            val subIframe = substream.iframe
            if (!subIframe.isNullOrEmpty()) {
                loadExtractor(
                    url = subIframe,
                    referer = webUrl,
                    subtitleCallback = { sub -> subtitleCallback.invoke(sub) },
                    callback = { link -> callback.invoke(link) }
                )
            }
        }

        return@withContext true
    }
}