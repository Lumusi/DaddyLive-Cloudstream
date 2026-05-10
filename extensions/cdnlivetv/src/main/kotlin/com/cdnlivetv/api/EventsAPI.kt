package com.cdnlivetv.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data model for the CDNLiveTV events API.
 *
 * Endpoint example:
 *   https://api.cdnlivetv.tv/api/v1/events/sports/soccer/?user=cdnlivetv&plan=free
 *
 * Note: Events API is on api.cdnlivetv.tv (not .ru which is the channels API)
 *
 * Response structure:
 * {
 *   "Soccer": [ { gameID, homeTeam, awayTeam, ..., channels: [...] }, ... ],
 *   "total_events": 79,
 *   "cached": true,
 *   "timestamp": "..."
 * }
 *
 * Each event can have multiple channel sources from different countries,
 * giving viewers alternative broadcast feeds for the same match.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class Event(
    @JsonProperty("gameID") val gameID: String?,
    @JsonProperty("homeTeam") val homeTeam: String?,
    @JsonProperty("awayTeam") val awayTeam: String?,
    @JsonProperty("homeTeamIMG") val homeTeamIMG: String?,
    @JsonProperty("awayTeamIMG") val awayTeamIMG: String?,
    @JsonProperty("time") val time: String?,
    @JsonProperty("tournament") val tournament: String?,
    @JsonProperty("country") val country: String?,
    @JsonProperty("countryIMG") val countryIMG: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("start") val start: String?,
    @JsonProperty("end") val end: String?,
    @JsonProperty("channels") val channels: List<EventChannel>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EventChannel(
    @JsonProperty("channel_name") val channelName: String?,
    @JsonProperty("channel_code") val channelCode: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("image") val image: String?,
    @JsonProperty("viewers") val viewers: Int?
)