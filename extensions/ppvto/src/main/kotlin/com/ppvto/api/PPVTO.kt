package com.ppvto
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

    // Simplified internal data class to avoid Jackson mapping failures
    data class PpvStream(
        val id: Int?,
        val name: String?,
        val tag: String?,
        val sourceTag: String?,
        val poster: String?,
        val uriName: String?,
        val startsAt: Long?,
        val endsAt: Long?,
        val alwaysLive: Int?,
        val categoryName: String?,
        val iframe: String?,
        val viewers: String?,
        val substreams: List<Map<String, Any?>>?
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

    private suspend fun fetchAllStreams(): List<Pair<PpvStream, String>> {
        return try {
            // Pre-fetch main site for potential DDoS/WAF cookies
            try {
                app.get(webUrl, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
                ), timeout = 10000)
            } catch (e: Exception) {
                Log.d("PpvTo", "Main site fetch skipped: ${e.message}")
            }

            val response = app.get(apiUrl, headers = getApiHeaders(), timeout = 15000)
            Log.d("PpvTo", "API Status: ${response.code}")
            
            if (response.code != 200) {
                Log.e("PpvTo", "API failed with code: ${response.code}")
                return emptyList()
            }

            val rawJson = response.text.trim()
            if (rawJson.isBlank()) {
                Log.e("PpvTo", "API returned empty body")
                return emptyList()
            }

            Log.d("PpvTo", "RAW JSON PREVIEW: ${rawJson.take(300)}...")

            val mapper = jacksonObjectMapper().registerKotlinModule()
            val root = mapper.readTree(rawJson)
            
            // Handle both flat and nested structures
            val streamsNode = root.get("streams") ?: root.get("data") ?: root.get("results")
            if (streamsNode == null || streamsNode.isNull) {
                Log.e("PpvTo", "No 'streams'/'data'/'results' key found in JSON")
                return emptyList()
            }

            val result = mutableListOf<Pair<PpvStream, String>>()

            if (streamsNode.isArray) {
                // Flat array of streams
                for (node in streamsNode) {
                    val stream = mapper.treeToValue(node, PpvStream::class.java)
                    val category = stream.categoryName ?: "Uncategorized"
                    result.add(Pair(stream, category))
                }
            } else if (streamsNode.isObject) {
                // Nested: { "categoryName": [ streams ] }
                streamsNode.fields().forEach { (catName, catArray) ->
                    if (catArray.isArray) {
                        for (node in catArray) {
                            val stream = mapper.treeToValue(node, PpvStream::class.java)
                            result.add(Pair(stream, catName))
                        }
                    }
                }
            }

            Log.d("PpvTo", "Successfully parsed ${result.size} streams")
            result
        } catch (e: Exception) {
            Log.e("PpvTo", "fetchAllStreams crashed: ${e.message}")
            Log.e("PpvTo", "Stack: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    private fun buildPosterUrl(poster: String?): String? {
        if (poster.isNullOrEmpty()) return null
        return if (poster.startsWith("http")) poster else "$mainUrl$poster"
    }

    private fun formatTimeDescription(startsAt: Long?, endsAt: Long?, alwaysLive: Int?): String {
        if (alwaysLive == 1) return "🔴 Live 24/7 Stream"
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
        Log.d("PpvTo", "Loading page: ${request.name} (${request.data})")
        val allStreams = fetchAllStreams()
        
        val filtered = if (request.data == "all") {
            allStreams
        } else {
            allStreams.filter { it.second.equals(request.data, ignoreCase = true) }
        }

        val items = filtered.mapNotNull { (stream, _) ->
            val name = stream.name ?: return@mapNotNull null
            val id = stream.id ?: return@mapNotNull null
            val href = "$webUrl/stream/$id"
            
            newLiveSearchResponse(name, href, TvType.Live) {
                this.posterUrl = buildPosterUrl(stream.poster)
                this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            }
        }

        Log.d("PpvTo", "HomePageList contains ${items.size} items")
        
        // Cloudstream expects List<HomePageList>
        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allStreams = fetchAllStreams()
        val q = query.lowercase().trim()

        return allStreams.filter { (stream, _) ->
            stream.name?.lowercase()?.contains(q) == true ||
            stream.tag?.lowercase()?.contains(q) == true ||
            stream.sourceTag?.lowercase()?.contains(q) == true ||
            stream.categoryName?.lowercase()?.contains(q) == true
        }.mapNotNull { (stream, _) ->
            val name = stream.name ?: return@mapNotNull null
            val id = stream.id ?: return@mapNotNull null
            val href = "$webUrl/stream/$id"
            
            newLiveSearchResponse(name, href, TvType.Live) {
                this.posterUrl = buildPosterUrl(stream.poster)
                this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val streamId = url.substringAfterLast("/").toIntOrNull() ?: return null
        val allStreams = fetchAllStreams()
        val (stream, categoryName) = allStreams.find { it.first.id == streamId } ?: return null
        
        val title = stream.name ?: "Unknown Stream"
        val timeDesc = formatTimeDescription(stream.startsAt, stream.endsAt, stream.alwaysLive)
        val viewers = stream.viewers?.toIntOrNull() ?: 0
        val viewerText = if (viewers > 0) "\n\n👥 Viewers: $viewers" else ""
        val tagText = if (!stream.tag.isNullOrBlank()) "\n🏷️ Source: ${stream.tag}" else ""
        
        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = buildPosterUrl(stream.poster)
            this.posterHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            this.plot = "$timeDesc$tagText$viewerText"
            this.tags = listOfNotNull(categoryName, stream.tag, stream.sourceTag).distinct()
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

        fun processIframe(iframe: String?) {
            if (!iframe.isNullOrBlank()) {
                loadExtractor(url = iframe, referer = webUrl, subtitleCallback = subtitleCallback, callback = callback)
            }
        }

        processIframe(stream.iframe)
        stream.substreams?.forEach { sub ->
            processIframe(sub["iframe"] as? String)
        }

        return@withContext true
    }
}