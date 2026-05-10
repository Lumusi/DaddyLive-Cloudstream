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
 * Embed: https://pooembed.eu/embed/{sport}/{date}/{match}
 * 
 * Uses loadExtractor() for WebView-based stream extraction from
 * the adware-heavy embed player page.
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
            val mapper = jacksonObjectMapper().registerKotlinModule()
            val textdoc = withContext(Dispatchers.IO) {
                app.get(API_URL, headers = headers).text
            }
            val apiResponse = mapper.readValue<StreamsResponse>(textdoc)
            
            val items = mutableListOf<HomePageList>()
            val categoryItems = mutableListOf<LiveSearchResponse>()
            
            apiResponse.streams?.forEach { category ->
                category.streams.forEach { stream ->
                    val title = stream.name ?: return@forEach
                    val url = "$EMBED_URL/${stream.uri_name}"
                    categoryItems.add(
                        newLiveSearchResponse(title, url, TvType.Live) {
                            posterUrl = stream.poster
                            description = "${stream.category_name} • ${stream.tag ?: ""}"
                        }
                    )
                }
            }
            
            items.add(
                HomePageList(
                    name = "Categories",
                    list = categoryItems,
                    isHorizontalImages = false
                )
            )
            
            newHomePageResponse(list = items, hasNext = false)
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val mapper = jacksonObjectMapper().registerKotlinModule()
            val textdoc = withContext(Dispatchers.IO) {
                app.get(API_URL, headers = headers).text
            }
            val apiResponse = mapper.readValue<StreamsResponse>(textdoc)
            
            val results = mutableListOf<SearchResponse>()
            val queryLower = query.lowercase()
            
            apiResponse.streams?.forEach { category ->
                category.streams.forEach { stream ->
                    val name = stream.name ?: return@forEach
                    if (name.lowercase().contains(queryLower) ||
                        (stream.category_name?.lowercase()?.contains(queryLower) == true)) {
                        val url = "$EMBED_URL/${stream.uri_name}"
                        results.add(
                            newLiveSearchResponse(name, url, TvType.Live) {
                                posterUrl = stream.poster
                                description = "${stream.category_name} • ${stream.tag ?: ""}"
                            }
                        )
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
        
        return try {
            val mapper = jacksonObjectMapper().registerKotlinModule()
            val textdoc = withContext(Dispatchers.IO) {
                app.get(API_URL, headers = headers).text
            }
            val apiResponse = mapper.readValue<StreamsResponse>(textdoc)
            
            val stream = apiResponse.streams?.flatMap { it.streams }
                ?.find { it.uri_name == uriName }
            
            stream?.let {
                newMovieLoadResponse(
                    title = it.name ?: "Unknown",
                    url = url,
                    TvType.Live,
                    url
                ) {
                    posterUrl = it.poster
                    description = it.category_name
                }
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
    ): Boolean {
        return try {
            // Use loadExtractor for WebView-based extraction
            // Handles the adware-heavy embed page (pooembed.eu)
            loadExtractor(
                url = data,
                referer = "$mainUrl/",
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}

// API Response Data Classes
@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamsResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("streams") val streams: List<CategoryData>? = null
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
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("uri_name") val uri_name: String? = null,
    @JsonProperty("category_name") val category_name: String? = null,
    @JsonProperty("iframe") val iframe: String? = null
)