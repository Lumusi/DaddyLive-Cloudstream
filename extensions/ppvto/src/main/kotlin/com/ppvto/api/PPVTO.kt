package com.ppvto.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PPV.to - Sports streaming portal
 * 
 * API: https://api.ppv.st/api/streams
 * Returns: JSON with categories and streams containing iframe embeds
 * 
 * Embed player: https://pooembed.eu/embed/{sport}/{date}/{match}
 * 
 * Note: Site may be blocked in some regions (Virgin Media court order in UK)
 */
class PPVTO : MainAPI() {
    override var mainUrl = "https://ppv.to"
    override var name = "PPV.to"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.MightBeNeeded

    companion object {
        private const val API_URL = "https://api.ppv.st/api/streams"
        private const val EMBED_URL = "https://pooembed.eu/embed"
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            "Accept" to "application/json",
            "Referer" to "https://ppv.to/"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "🏠 Home",
        "$mainUrl/live" to "🔴 Live Now",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = app.get(API_URL, headers = headers)
            val apiResponse = jacksonObjectMapper().registerKotlinModule()
                .readValue<StreamsResponse>(response)
            
            val items = mutableListOf<HomePageItem>()
            apiResponse.streams.forEach { category ->
                category.streams.forEach { stream ->
                    items.add(HomePageItem(
                        title = stream.name ?: continue,
                        synopsis = "${stream.category_name} • ${stream.tag ?: ""}",
                        poster = stream.poster ?: "",
                        url = "${EMBED_URL}/${stream.uri_name}",
                        id = stream.id.toString()
                    ))
                }
            }
            
            newHomePageResponse(list = items, hasNext = false)
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.get(API_URL, headers = headers)
            val apiResponse = jacksonObjectMapper().registerKotlinModule()
                .readValue<StreamsResponse>(response)
            
            val results = mutableListOf<SearchResponse>()
            val queryLower = query.lowercase()
            
            apiResponse.streams.forEach { category ->
                category.streams.forEach { stream ->
                    if (stream.name?.lowercase()?.contains(queryLower) == true ||
                        stream.category_name?.lowercase()?.contains(queryLower) == true) {
                        results.add(SearchResponse(
                            extractor = name,
                            id = stream.id.toString(),
                            title = stream.name ?: continue,
                            description = "${stream.category_name} • ${stream.tag ?: ""}",
                            poster = stream.poster ?: "",
                            url = "${EMBED_URL}/${stream.uri_name}"
                        ))
                    }
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val uriName = url.substringAfter("$EMBED_URL/")
        val parts = uriName.split("/")
        
        return try {
            val response = app.get(API_URL, headers = headers)
            val apiResponse = jacksonObjectMapper().registerKotlinModule()
                .readValue<StreamsResponse>(response)
            
            val stream = apiResponse.streams
                .flatMap { it.streams }
                .find { it.uri_name == uriName }
            
            stream?.let {
                LoadResponse(
                    extractor = name,
                    id = it.id.toString(),
                    title = it.name ?: "Unknown",
                    description = it.category_name,
                    poster = it.poster ?: "",
                    url = url,
                    isLive = it.always_live == 1,
                    duration = 0L
                )
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
        try {
            // Use WebView extraction for the embed page
            val iframeUrl = data
            
            loadExtractor(
                url = iframeUrl,
                referer = mainUrl,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return@withContext true
        } catch (e: Exception) {
            return@withContext false
        }
    }
}

// API Response Data Classes
@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamsResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("output") val output: List<CategoryData>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CategoryData(
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("always_live") val always_live: Boolean? = null,
    @JsonProperty("streams") val streams: List<StreamData> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamData(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("tag") val tag: String? = null,
    @JsonProperty("source_tag") val source_tag: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("blurhash") val blurhash: String? = null,
    @JsonProperty("colors") val colors: List<String> = emptyList(),
    @JsonProperty("uri_name") val uri_name: String? = null,
    @JsonProperty("starts_at") val starts_at: Long = 0,
    @JsonProperty("ends_at") val ends_at: Long = 0,
    @JsonProperty("always_live") val always_live: Int = 0,
    @JsonProperty("locale") val locale: String? = null,
    @JsonProperty("category_name") val category_name: String? = null,
    @JsonProperty("iframe") val iframe: String? = null,
    @JsonProperty("viewers") val viewers: String? = null,
    @JsonProperty("substreams") val substreams: List<Any> = emptyList()
)