package com.damitv.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class MatchResponse(
    @JsonProperty("success") val success: Boolean,
    @JsonProperty("total") val total: Int?,
    @JsonProperty("live") val live: Int?,
    @JsonProperty("matches") val matches: List<Match>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Match(
    @JsonProperty("id") val id: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("home") val home: String?,
    @JsonProperty("away") val away: String?,
    @JsonProperty("homeLogo") val homeLogo: String?,
    @JsonProperty("awayLogo") val awayLogo: String?,
    @JsonProperty("league") val league: String?,
    @JsonProperty("leagueLogo") val leagueLogo: String?,
    @JsonProperty("sport") val sport: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("startTime") val startTime: String?,
    @JsonProperty("scores") val scores: MatchScores?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MatchScores(
    @JsonProperty("localteam_score") val homeScore: Int?,
    @JsonProperty("visitorteam_score") val awayScore: Int?,
    @JsonProperty("ht_score") val halfTime: String?,
    @JsonProperty("ft_score") val fullTime: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("stream") val stream: String?
)
