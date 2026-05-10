package com.ppvto
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
import java.util.Base64

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
        @JsonProperty("timestamp") val timestamp: Long?,
        @JsonProperty("READ_ME") val readMe: String?
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

    private fun getApiHeaders(): Map<String, String> {
        val timezone = Base64.getEncoder().encodeToString("Europe/London".toByteArray())
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br",
            "Accept-Language" to "en-GB,en;q=0.6",
            "Content-Type" to "application/json",
            "Origin" to webUrl,
            "Referer" to "$webUrl/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "x-cid" to "55a9253c1c7aca21ef2a060b14839edc13c6e2ed9c7f3ba761c3a1d3bd3dfcde",
            "x-cld" to "60",
            "x-ctd" to timezone,
            "x-fs-client" to "PPV WebClient 086da1e"
        )
    }

    private suspend fun fetchAllStreams(): List<Pair<Stream, String>> {
        return try {
            // Pre-fetch main site for potential DDoS cookies
            try {
                app.get(webUrl, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
                ))
            } catch (e: Exception) {
                Log.d("PpvTo", "Main site fetch failed (non-critical): ${e.message}")
            }

            val headers = getApiHeaders()
            Log.d("PpvTo", "Fetching API: $apiUrl")

            val response = app.get(apiUrl, headers = headers)
            Log.d("PpvTo", "API response code: ${response.code}")
            Log.d("PpvTo", "API response length: ${response.text.length}")

            if (response.code != 200) {
                Log.e("PpvTo", "API returned non-200: ${response.code}")
                return emptyList()
            }

            val text = response.text
            if (text.isBlank()) {
                Log.e("PpvTo", "API returned empty body")
                return emptyList()
            }

            Log.d("PpvTo", "API response preview: ${text.take(500)}")

            val mapper = jacksonObjectMapper().registerKotlinModule()
            val apiResponse: ApiResponse = mapper.readValue(text)

            Log.d("PpvTo", "API success: ${apiResponse.success}")
            Log.d("PpvTo", "Categories count: ${apiResponse.categories?.size ?: 0}")

            val result = mutableListOf<Pair<Stream, String>>()
            apiResponse.categories?.forEach { category ->
                val catName = category.category ?: "Unknown"
                val streams = category.streams ?: emptyList()
                Log.d("PpvTo", "Category '$catName' has ${streams.size} streams")
                streams.forEach { stream ->
                    result.add(Pair(stream, catName))
                }
            }

            Log.d("PpvTo", "Total streams fetched: ${result.size}")
            result
        } catch (e: Exception) {
            Log.e("PpvTo", "fetchAllStreams error: ${e.message}")
            Log.e("PpvTo", "Stack: ${e.stackTraceToString()}")
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
            return "\uD83D\uDD34 Live 24/7 Stream"
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
            currentTime in start..end -> "\uD83D\uDD34 LIVE NOW"
            else -> "Stream ended"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("PpvTo", "getMainPage called: ${request.name} / ${request.data}")
        val allStreams = fetchAllStreams()
        val categoryFilter = request.data

        val filtered = if (categoryFilter == "all") {
            allStreams
        } else {
            allStreams.filter { it.second.equals(categoryFilter, ignoreCase = true) }
        }

        Log.d("PpvTo", "Filtered streams for '${request.name}': ${filtered.size}")

        val items = filtered.mapNotNull { (stream, _) ->
            val name = stream.name ?: return@mapNotNull null
            val id = stream.id ?: return@mapNotNull null
            val href = "$webUrl/stream/$id"
            val posterUrl = buildPosterUrl(stream.poster)

            newLiveSearchResponse(name, href, TvType.Live) {
                this.posterUrl = posterUrl
                this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            }
        }

        Log.d("PpvTo", "HomePage items created: ${items.size}")

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
        Log.d("PpvTo", "search called: $query")
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
                this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("PpvTo", "load called: $url")
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
            this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
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
        Log.d("PpvTo", "loadLinks called: $data")
        val streamId = data.substringAfterLast("/").toIntOrNull() ?: return@withContext false
        val allStreams = fetchAllStreams()
        val (stream, _) = allStreams.find { it.first.id == streamId } ?: return@withContext false

        val iframeUrl = stream.iframe
        Log.d("PpvTo", "iframeUrl: $iframeUrl")

        if (!iframeUrl.isNullOrEmpty()) {
            loadExtractor(
                url = iframeUrl,
                referer = webUrl,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        stream.substreams?.forEach { substream ->
            val subIframe = substream.iframe
            if (!subIframe.isNullOrEmpty()) {
                Log.d("PpvTo", "substream iframe: $subIframe")
                loadExtractor(
                    url = subIframe,
                    referer = webUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        return@withContext true
    }
}