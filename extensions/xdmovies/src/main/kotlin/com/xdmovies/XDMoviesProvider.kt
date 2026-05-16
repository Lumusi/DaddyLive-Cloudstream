package com.xdmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class XDMoviesProvider : MainAPI() {
    override var mainUrl = "https://xdmovies-api.hdmovielover.workers.dev"
    override var name = "XDMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val mapper = jacksonObjectMapper().registerKotlinModule()

    data class XDMoviesItem(
        @JsonProperty("title") val title: String?,
        @JsonProperty("detail_url") val detailUrl: String?,
        @JsonProperty("detailUrl") val detailUrlCamel: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("image_url") val imageUrl: String?,
        @JsonProperty("poster") val poster: String?
    )

    data class XDMoviesPageResponse(
        @JsonProperty("items") val items: List<XDMoviesItem>?
    )

    private fun normalizeType(type: String?): String {
        return when (type?.lowercase()) {
            "series", "show", "tv" -> "series"
            else -> "movie"
        }
    }

    private fun getRealDetailUrl(item: XDMoviesItem): String? {
        return item.detailUrl ?: item.detailUrlCamel ?: item.url
    }

    private fun encodeData(detailUrl: String, type: String): String {
        return "$mainUrl/xddata/${java.net.URLEncoder.encode(detailUrl, "UTF-8")}?type=$type"
    }

    private fun decodeData(url: String): Pair<String, String>? {
        return try {
            val withoutPrefix = url.removePrefix("$mainUrl/xddata/")
            val encodedUrl = withoutPrefix.substringBefore("?")
            val type = withoutPrefix.substringAfter("type=", "movie")
            val decoded = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            decoded to type
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchPage(pageNo: Int): List<XDMoviesItem> {
        val response = withContext(Dispatchers.IO) {
            app.get("$mainUrl/page?no=$pageNo").text
        }
        val parsed: XDMoviesPageResponse = mapper.readValue(response)
        return parsed.items ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val items = fetchPage(page)
            val home = items.mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val detailUrl = getRealDetailUrl(item) ?: return@mapNotNull null
                val posterUrl = item.imageUrl ?: item.poster
                val itemType = normalizeType(item.type)
                val tvType = if (itemType == "series") TvType.TvSeries else TvType.Movie
                val dataUrl = encodeData(detailUrl, itemType)

                newMovieSearchResponse(title, dataUrl, tvType) {
                    this.posterUrl = posterUrl
                    this.type = tvType
                    this.year = item.year
                }
            }

            newHomePageResponse(
                list = listOf(HomePageList("Latest", home, isHorizontalImages = false)),
                hasNext = home.isNotEmpty()
            )
        } catch (e: Exception) {
            newHomePageResponse(list = mutableListOf(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()
        val queryLower = query.lowercase()
        var page = 1
        val maxPages = 80
        var consecutiveEmpty = 0

        while (page <= maxPages && consecutiveEmpty < 3) {
            val items = try {
                fetchPage(page)
            } catch (e: Exception) {
                break
            }

            if (items.isEmpty()) {
                consecutiveEmpty++
                page++
                continue
            }

            consecutiveEmpty = 0

            for (item in items) {
                val title = item.title ?: continue
                val detailUrl = getRealDetailUrl(item) ?: continue
                val itemType = normalizeType(item.type)

                if (seenUrls.contains(detailUrl)) continue

                val titleLower = title.lowercase()
                val queryWords = queryLower.split(Regex("\\s+")).filter { it.length > 2 }
                val matchesQuery = queryWords.any { titleLower.contains(it) } ||
                                   titleLower.contains(queryLower)

                if (matchesQuery) {
                    seenUrls.add(detailUrl)
                    val posterUrl = item.imageUrl ?: item.poster
                    val tvType = if (itemType == "series") TvType.TvSeries else TvType.Movie
                    val dataUrl = encodeData(detailUrl, itemType)

                    results.add(newMovieSearchResponse(title, dataUrl, tvType) {
                        this.posterUrl = posterUrl
                        this.type = tvType
                        this.year = item.year
                    })
                }
            }

            page++
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val data = decodeData(url) ?: return null
            val detailUrl = data.first
            val type = data.second

            val endpoint = if (normalizeType(type) == "series") "series" else "movie"
            val detailResponse = withContext(Dispatchers.IO) {
                app.get("$mainUrl/$endpoint/details?url=${java.net.URLEncoder.encode(detailUrl, "UTF-8")}")
            }
            val detailJson = mapper.readTree(detailResponse.text)

            val detailTitle = detailJson.get("title")?.asText()
                ?: detailJson.get("name")?.asText()
                ?: detailJson.get("movieTitle")?.asText()
                ?: detailJson.get("seriesTitle")?.asText()
                ?: "Unknown"

            val posterUrl = detailJson.get("image_url")?.asText()
                ?: detailJson.get("poster")?.asText()
                ?: detailJson.get("poster_url")?.asText()

            val plot = detailJson.get("description")?.asText()
                ?: detailJson.get("plot")?.asText()
                ?: detailJson.get("overview")?.asText()

            val year = detailJson.get("year")?.asInt()
            val ratingStr = detailJson.get("rating")?.asText()
            val score = ratingStr?.toFloatOrNull()?.let { if (it > 10) it / 10f else it }
            val duration = detailJson.get("duration")?.asText()
            val trailer = detailJson.get("trailer_url")?.asText()
                ?: detailJson.get("trailer")?.asText()

            val tags = mutableListOf<String>()
            detailJson.get("genre")?.asText()?.let { tags.addAll(it.split(",").map { g -> g.trim() }) }

            if (normalizeType(type) == "movie") {
                newMovieLoadResponse(detailTitle, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.score = score
                    this.duration = duration?.toIntOrNull()?.times(60)
                    this.tags = tags
                    addTrailer(trailer)
                }
            } else {
                val episodes = mutableListOf<Episode>()

                val seasons = detailJson.get("seasons")
                if (seasons != null && seasons.isArray) {
                    for (seasonNode in seasons) {
                        val seasonNum = seasonNode.get("season")?.asInt()
                            ?: seasonNode.get("season_number")?.asInt()
                            ?: 1

                        val episodeList = seasonNode.get("episodes")
                        if (episodeList != null && episodeList.isArray) {
                            for (epNode in episodeList) {
                                val epNum = epNode.get("episode")?.asInt()
                                    ?: epNode.get("episode_number")?.asInt()
                                    ?: 1

                                val epTitle = epNode.get("title")?.asText()
                                val epDetailUrl = epNode.get("detail_url")?.asText()
                                    ?: epNode.get("url")?.asText()

                                episodes.add(
                                    newEpisode(encodeData(epDetailUrl ?: detailUrl, "series")) {
                                        this.name = epTitle
                                        this.season = seasonNum
                                        this.episode = epNum
                                    }
                                )
                            }
                        } else {
                            episodes.add(
                                newEpisode(encodeData(detailUrl, "series")) {
                                    this.season = seasonNum
                                }
                            )
                        }
                    }
                } else {
                    episodes.add(newEpisode(url))
                }

                newTvSeriesLoadResponse(detailTitle, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.score = score
                    this.tags = tags
                    addTrailer(trailer)
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
            val decoded = decodeData(data) ?: return false
            val detailUrl = decoded.first
            val type = decoded.second

            val endpoint = if (normalizeType(type) == "series") "series" else "movie"
            val response = withContext(Dispatchers.IO) {
                app.get("$mainUrl/$endpoint/details?url=${java.net.URLEncoder.encode(detailUrl, "UTF-8")}")
            }

            val jsonNode = mapper.readTree(response.text)
            val links = extractLinks(jsonNode)

            for (link in links) {
                val wrapped = newExtractorLink(
                    source = name,
                    name = link.name,
                    url = link.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                }
                callback(wrapped)
            }

            links.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    data class StreamLink(
        val name: String,
        val url: String,
        val referer: String = "",
        val quality: Int = Qualities.Unknown.value
    )

    private fun extractLinks(node: com.fasterxml.jackson.databind.JsonNode): List<StreamLink> {
        val links = mutableListOf<StreamLink>()
        val seenUrls = mutableSetOf<String>()

        fun traverse(current: com.fasterxml.jackson.databind.JsonNode, context: MutableMap<String, String> = mutableMapOf()) {
            when {
                current.isObject -> {
                    val newContext = context.toMutableMap()

                    for (field in current.fields()) {
                        val key = field.key
                        val value = field.value
                        val keyLower = key.lowercase()

                        when {
                            value.isTextual -> {
                                val text = value.asText()
                                if (text.startsWith("http://") || text.startsWith("https://")) {
                                    val isLinkKey = listOf("url", "href", "link", "download", "file", "stream", "source", "watch", "play", "server").any { keyLower.contains(it) }
                                    val isPathKey = listOf("season", "episode", "download", "link", "quality", "server", "source", "stream", "audio", "language", "file", "watch", "play").any { keyLower.contains(it) }

                                    if ((isLinkKey || isPathKey) && !seenUrls.contains(text)) {
                                        val isImage = listOf("jpg", "jpeg", "png", "gif", "webp", "svg", "avif").any { ext ->
                                            text.lowercase().contains(".$ext") || text.lowercase().contains(".$ext?")
                                        }
                                        val isTmdbImage = text.contains("image.tmdb.org")
                                        val isSelfUrl = text.contains("xdmovies-api.hdmovielover.workers.dev")

                                        if (!isImage && !isTmdbImage && !isSelfUrl) {
                                            seenUrls.add(text)
                                            val labelParts = mutableListOf<String>()
                                            context["title"]?.let { labelParts.add(it) }
                                            context["quality"]?.let { labelParts.add(it) }
                                            context["resolution"]?.let { labelParts.add(it) }
                                            context["language"]?.let { labelParts.add(it) }
                                            context["languages"]?.let { labelParts.add(it) }
                                            context["season"]?.let { labelParts.add("S$it") }
                                            context["episode"]?.let { labelParts.add("E$it") }
                                            labelParts.add(humanizeKey(key))

                                            links.add(
                                                StreamLink(
                                                    name = labelParts.joinToString(" | ").takeIf { it.isNotBlank() } ?: "Download",
                                                    url = text
                                                )
                                            )
                                        }
                                    }
                                } else {
                                    if (listOf("title", "name", "quality", "resolution", "language", "languages", "season", "episode", "size").any { keyLower.contains(it) }) {
                                        newContext[keyLower] = text
                                    }
                                }
                            }
                            value.isArray -> {
                                val isPathKey = listOf("season", "episode", "download", "link", "quality", "server", "source", "stream", "audio", "language", "file", "watch", "play").any { keyLower.contains(it) }
                                if (isPathKey) {
                                    val listContext = context.toMutableMap()
                                    listContext["section"] = humanizeKey(key)
                                    for (elem in value) {
                                        if (elem.isTextual) {
                                            val text = elem.asText()
                                            if ((text.startsWith("http://") || text.startsWith("https://")) && !seenUrls.contains(text)) {
                                                seenUrls.add(text)
                                                val labelParts = mutableListOf<String>()
                                                context["title"]?.let { labelParts.add(it) }
                                                context["quality"]?.let { labelParts.add(it) }
                                                listContext["section"]?.let { labelParts.add(it) }

                                                links.add(
                                                    StreamLink(
                                                        name = labelParts.joinToString(" | ").takeIf { it.isNotBlank() } ?: "Download",
                                                        url = text
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    for (elem in value) {
                                        traverse(elem, context.toMutableMap())
                                    }
                                }
                            }
                            value.isObject || value.isArray -> {
                                val childContext = newContext.toMutableMap()
                                if (listOf("season", "episode", "download", "link", "quality", "server", "source", "stream", "audio", "language", "file", "watch", "play").any { keyLower.contains(it) }) {
                                    childContext["section"] = humanizeKey(key)
                                }
                                traverse(value, childContext)
                            }
                        }
                    }
                }
                current.isArray -> {
                    for (elem in current) {
                        if (elem.isTextual) {
                            val text = elem.asText()
                            if ((text.startsWith("http://") || text.startsWith("https://")) && !seenUrls.contains(text)) {
                                seenUrls.add(text)
                                links.add(
                                    StreamLink(
                                        name = context["title"] ?: "Download",
                                        url = text
                                    )
                                )
                            }
                        } else {
                            traverse(elem, context)
                        }
                    }
                }
            }
        }

        traverse(node)
        return links
    }

    private fun humanizeKey(key: String): String {
        return key
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
