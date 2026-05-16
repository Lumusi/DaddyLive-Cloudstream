package com.xdmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

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

    private val mapper = jacksonObjectMapper()

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

    data class XDMoviesSearchData(
        val url: String,
        val type: String,
        val title: String
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

    private suspend fun fetchPage(pageNo: Int): List<XDMoviesItem> {
        val response = app.get("$mainUrl/page?no=$pageNo")
        val parsed = mapper.readValue(response.text, XDMoviesPageResponse::class.java)
        return parsed.items ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = fetchPage(page)
        val home = items.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val detailUrl = getRealDetailUrl(item) ?: return@mapNotNull null
            val posterUrl = item.imageUrl ?: item.poster
            val itemType = normalizeType(item.type)
            val tvType = if (itemType == "series") TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, XDMoviesSearchData(detailUrl, itemType, title).toJson()) {
                this.posterUrl = posterUrl
                this.type = tvType
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = "Latest",
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
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
                val queryWords = queryLower.split("\\s+".toRegex()).filter { it.length > 2 }
                val matchesQuery = queryWords.any { titleLower.contains(it) } ||
                                   titleLower.contains(queryLower)

                if (matchesQuery) {
                    seenUrls.add(detailUrl)
                    val posterUrl = item.imageUrl ?: item.poster
                    val tvType = if (itemType == "series") TvType.TvSeries else TvType.Movie

                    results.add(newMovieSearchResponse(title, XDMoviesSearchData(detailUrl, itemType, title).toJson()) {
                        this.posterUrl = posterUrl
                        this.type = tvType
                        year = item.year
                    })
                }
            }

            page++
        }

        return results
    }

    private fun extractDownloadLinks(data: Map<String, Any?>, detailUrl: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        val seenUrls = mutableSetOf<String>()

        fun traverse(node: Any?, context: MutableMap<String, String> = mutableMapOf()) {
            when (node) {
                is Map<*, *> -> {
                    val stringMap = node.mapKeys { it.key.toString() }
                        .mapValues { it.value }

                    val newContext = context.toMutableMap()

                    for ((key, value) in stringMap) {
                        when (value) {
                            is String -> {
                                if (value.startsWith("http://") || value.startsWith("https://")) {
                                    val keyLower = key.lowercase()
                                    val isLinkKey = listOf("url", "href", "link", "download", "file", "stream", "source", "watch", "play", "server").any { keyLower.contains(it) }
                                    val isPathKey = listOf("season", "episode", "download", "link", "quality", "server", "source", "stream", "audio", "language", "file", "watch", "play").any { keyLower.contains(it) }

                                    if ((isLinkKey || isPathKey) && !seenUrls.contains(value)) {
                                        val isImage = listOf("jpg", "jpeg", "png", "gif", "webp", "svg", "avif").any { ext ->
                                            value.lowercase().contains(".$ext") || value.lowercase().contains(".$ext?")
                                        }
                                        val isTmdbImage = value.contains("image.tmdb.org")
                                        val isSelfUrl = value.contains("xdmovies-api.hdmovielover.workers.dev")

                                        if (!isImage && !isTmdbImage && !isSelfUrl) {
                                            seenUrls.add(value)
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
                                                ExtractorLink(
                                                    source = name,
                                                    name = labelParts.joinToString(" | ").takeIf { it.isNotBlank() } ?: "Download",
                                                    url = value,
                                                    referer = "",
                                                    quality = Qualities.P1080.value,
                                                    type = INFER_TYPE
                                                )
                                            )
                                        }
                                    }
                                } else {
                                    val keyLower = key.lowercase()
                                    if (listOf("title", "name", "quality", "resolution", "language", "languages", "season", "episode", "size").any { keyLower.contains(it) }) {
                                        newContext[keyLower] = value
                                    }
                                }
                            }
                            is List<*> -> {
                                val keyLower = key.lowercase()
                                val isPathKey = listOf("season", "episode", "download", "link", "quality", "server", "source", "stream", "audio", "language", "file", "watch", "play").any { keyLower.contains(it) }
                                if (isPathKey) {
                                    val listContext = context.toMutableMap()
                                    listContext["section"] = humanizeKey(key)
                                    for (listItem in value) {
                                        if (listItem is String && (listItem.startsWith("http://") || listItem.startsWith("https://"))) {
                                            if (!seenUrls.contains(listItem)) {
                                                seenUrls.add(listItem)
                                                val labelParts = mutableListOf<String>()
                                                context["title"]?.let { labelParts.add(it) }
                                                context["quality"]?.let { labelParts.add(it) }
                                                listContext["section"]?.let { labelParts.add(it) }

                                                links.add(
                                                    ExtractorLink(
                                                        source = name,
                                                        name = labelParts.joinToString(" | ").takeIf { it.isNotBlank() } ?: "Download",
                                                        url = listItem,
                                                        referer = "",
                                                        quality = Qualities.P1080.value,
                                                        type = INFER_TYPE
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    value.forEach { traverse(it, context.toMutableMap()) }
                                }
                            }
                            else -> {
                                traverse(value, newContext)
                            }
                        }
                    }

                    for ((key, value) in stringMap) {
                        if (value is Map<*, *> || value is List<*>) {
                            val keyLower = key.lowercase()
                            val childContext = newContext.toMutableMap()
                            if (listOf("season", "episode", "download", "link", "quality", "server", "source", "stream", "audio", "language", "file", "watch", "play").any { keyLower.contains(it) }) {
                                childContext["section"] = humanizeKey(key)
                            }
                            traverse(value, childContext)
                        }
                    }
                }
                is List<*> -> {
                    for (item in node) {
                        if (item is String && (item.startsWith("http://") || item.startsWith("https://"))) {
                            if (!seenUrls.contains(item)) {
                                seenUrls.add(item)
                                links.add(
                                    ExtractorLink(
                                        source = name,
                                        name = context["title"] ?: "Download",
                                        url = item,
                                        referer = "",
                                        quality = Qualities.P1080.value,
                                        type = INFER_TYPE
                                    )
                                )
                            }
                        } else {
                            traverse(item, context)
                        }
                    }
                }
            }
        }

        traverse(data)
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

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<XDMoviesSearchData>(url)
        val detailUrl = data.url
        val type = data.type
        val title = data.title

        val endpoint = if (normalizeType(type) == "series") "series" else "movie"
        val detailResponse = app.get("$mainUrl/$endpoint/details?url=${encodeUrl(detailUrl)}")
        val detailJson = mapper.readTree(detailResponse.text)

        val detailTitle = detailJson.get("title")?.asText()
            ?: detailJson.get("name")?.asText()
            ?: detailJson.get("movieTitle")?.asText()
            ?: detailJson.get("seriesTitle")?.asText()
            ?: title

        val posterUrl = detailJson.get("image_url")?.asText()
            ?: detailJson.get("poster")?.asText()
            ?: detailJson.get("poster_url")?.asText()

        val plot = detailJson.get("description")?.asText()
            ?: detailJson.get("plot")?.asText()
            ?: detailJson.get("overview")?.asText()

        val year = detailJson.get("year")?.asInt()
        val rating = detailJson.get("rating")?.asText()?.toRatingInt()
        val duration = detailJson.get("duration")?.asText()
        val trailer = detailJson.get("trailer_url")?.asText()
            ?: detailJson.get("trailer")?.asText()

        val tags = mutableListOf<String>()
        val cast = mutableListOf<String>()

        detailJson.get("genre")?.asText()?.let { tags.addAll(it.split(",").map { g -> g.trim() }) }
        detailJson.get("cast")?.asText()?.let { cast.addAll(it.split(",").map { c -> c.trim() }) }

        if (normalizeType(type) == "movie") {
            return newMovieLoadResponse(
                name = detailTitle,
                dataUrl = url,
                type = TvType.Movie,
                dataUrl
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.rating = rating
                this.duration = duration
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
                                newEpisode(
                                    XDMoviesSearchData(
                                        epDetailUrl ?: detailUrl,
                                        "series",
                                        "$detailTitle S${seasonNum}E${epNum}"
                                    ).toJson()
                                ) {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                }
                            )
                        }
                    } else {
                        episodes.add(
                            newEpisode(
                                XDMoviesSearchData(detailUrl, "series", "$detailTitle Season $seasonNum").toJson()
                            ) {
                                this.season = seasonNum
                            }
                        )
                    }
                }
            } else {
                episodes.add(
                    newEpisode(url)
                )
            }

            return newTvSeriesLoadResponse(
                name = detailTitle,
                dataUrl = url,
                type = TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.rating = rating
                this.tags = tags
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val searchData = parseJson<XDMoviesSearchData>(data)
        val detailUrl = searchData.url
        val type = searchData.type

        val endpoint = if (normalizeType(type) == "series") "series" else "movie"
        val response = app.get("$mainUrl/$endpoint/details?url=${encodeUrl(detailUrl)}")

        try {
            val jsonNode = mapper.readTree(response.text)
            val jsonMap = mapper.convertValue(jsonNode, Map::class.java) as Map<String, Any?>

            val links = extractDownloadLinks(jsonMap, detailUrl)
            links.forEach { callback(it) }
            return links.isNotEmpty()
        } catch (e: Exception) {
            return false
        }
    }
}
