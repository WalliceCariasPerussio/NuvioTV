package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.MetaDto

// Some addons answer a throttled request with HTTP 200 and a fake meta whose name is the error
// ("⚠️ Rate limit exceeded — please wait a few minutes before trying again."). Taking it as the
// title renames the item in Continue Watching and on the launcher until the cache expires.
private val addonErrorNamePatterns = listOf(
    Regex("""\brate[ -]?limit""", RegexOption.IGNORE_CASE),
    Regex("""\btoo many requests\b""", RegexOption.IGNORE_CASE),
    Regex("""\bplease (wait|try again)\b""", RegexOption.IGNORE_CASE),
)

internal fun String.looksLikeAddonError(): Boolean =
    addonErrorNamePatterns.any { it.containsMatchIn(this) }

internal fun MetaDto.isAddonErrorPlaceholder(): Boolean = name.looksLikeAddonError()
