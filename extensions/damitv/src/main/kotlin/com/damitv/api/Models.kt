package com.damitv.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiMatch(
    @JsonProperty("id") val id: String?,
    @JsonProperty("league") val league: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("category") val category: String?,
    @JsonProperty("date") val date: Long?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("teams") val teams: MatchTeams?,
    @JsonProperty("sources") val sources: List<SourceRef>?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("viewers") val viewers: Int?,
    @JsonProperty("embedUrl") val embedUrl: String?
)

// ── Live TV Channels (from /channels.json) ────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiChannelWrapper(
    @JsonProperty("generated") val generated: String?,
    @JsonProperty("total") val total: Int?,
    @JsonProperty("channels") val channels: List<ApiChannel>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiChannel(
    @JsonProperty("id") val id: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("logo") val logo: String?,
    @JsonProperty("country") val country: ChannelCountry?,
    @JsonProperty("defaultUrl") val defaultUrl: String?,
    @JsonProperty("defaultQuality") val defaultQuality: String?,
    @JsonProperty("qualities") val qualities: List<ChannelQuality>?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("viewers") val viewers: Int?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChannelCountry(
    @JsonProperty("code") val code: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("flag") val flag: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChannelQuality(
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("url") val url: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MatchTeams(
    @JsonProperty("home") val home: MatchTeam?,
    @JsonProperty("away") val away: MatchTeam?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MatchTeam(
    @JsonProperty("name") val name: String?,
    @JsonProperty("badge") val badge: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceRef(
    @JsonProperty("source") val source: String?,
    @JsonProperty("id") val id: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamItem(
    @JsonProperty("id") val id: String?,
    @JsonProperty("streamNo") val streamNo: Int?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("hd") val hd: Boolean?,
    @JsonProperty("embedUrl") val embedUrl: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("viewers") val viewers: Int?
)
