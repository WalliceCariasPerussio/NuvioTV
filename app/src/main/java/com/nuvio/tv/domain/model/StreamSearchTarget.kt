package com.nuvio.tv.domain.model

/** One stream search: the same title or episode asked of every installed source. */
data class StreamSearchTarget(
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?
)
