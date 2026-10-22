package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.ui.screens.stream.streamLanguageCode
import kotlinx.coroutines.flow.update

// Fork: releases often tag every audio track with the original language and name the dub only in
// the title ("PT-BR E-AC-3 2.0" tagged jpn), so a language the title names wins over the tag.

private val TitleVariantPatterns = listOf(
    "pt-br" to titlePattern(
        "pt[-_ ]?br", "pob", "dublad[oa]", "brasileiro", "brazilian",
        "portugu[eê]s[ea]? ?\\(?(do )?(brasil|brazil|br)\\)?",
    ),
    "pt" to titlePattern("pt[-_]?pt", "portugu[eê]s[ea]? ?\\(?(de )?portugal\\)?", "european portuguese"),
    "es-419" to titlePattern("es[-_]?(419|la|lat)", "latino", "latam", "latin american", "espanhol ?\\(la\\)"),
)

// Codec names holding a language code: DTS-ES (Spanish), HE-AAC (Hebrew), DTS-HD HR (Croatian).
private val TitleCodecPattern = titlePattern("dts[-_ ]?es", "he[-_ ]?aac(v2)?", "aac[-_ ]?he(v2)?", "dts[-_ ]?hd[-_ ]?hr")

// Accents written as a separate combining mark ("e" + U+0302), dropped so "Português" still reads as one word.
private val CombiningMarks = '̀'..'ͯ'

private val TitleWordPattern = Regex("[A-Za-zÀ-ɏ]+")

// Names streamLanguageCode doesn't know: Portuguese written without accents, and Spanish.
private val TitleLanguageNames = mapOf(
    "japones" to "ja",
    "japonés" to "ja",
    "inglés" to "en",
    "frances" to "fr",
    "francés" to "fr",
    "alemao" to "de",
    "alemán" to "de",
    "chines" to "zh",
    "español" to "es",
)

// Only the languages the settings offer: any ISO code would read "CH" (6CH) or "SD" as a language.
private val TitleLanguageCodes: Set<String> = AVAILABLE_SUBTITLE_LANGUAGES.map { it.code.lowercase() }.toSet()

private fun titlePattern(vararg tokens: String): Regex =
    Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|$)")

/**
 * The one language [title] names ("PT-BR", "JPN", "Japanese", "Japonês", "Dublado"), or null when it
 * names none or several. Two- and three-letter words count only in capitals: in plain words, "de" or
 * "no" are not languages.
 */
internal fun audioTitleLanguage(title: String?): String? {
    if (title.isNullOrBlank()) return null
    val text = title.filterNot { it in CombiningMarks }.toCharArray()
    // Char by char, so positions in the lowercase copy match the title's.
    val lower = CharArray(text.size) { text[it].lowercaseChar() }
    fun blank(range: IntRange) = range.forEach { text[it] = ' '; lower[it] = ' ' }

    TitleCodecPattern.findAll(lower.concatToString()).forEach { blank(it.range) }
    val found = mutableSetOf<String>()
    TitleVariantPatterns.forEach { (code, pattern) ->
        pattern.findAll(lower.concatToString()).forEach { match ->
            found += code
            blank(match.range)
        }
    }
    TitleWordPattern.findAll(text.concatToString()).forEach { match ->
        val word = match.value
        val code = if (word.length <= 3) {
            if (word.length >= 2 && word.all { it.isUpperCase() }) streamLanguageCode(word.lowercase()) else null
        } else {
            TitleLanguageNames[word.lowercase()] ?: streamLanguageCode(word.lowercase())
        }
        if (code != null && code in TitleLanguageCodes) found += code
    }
    // A variant also names its base language ("PT-BR Português").
    return found.filterNot { base -> found.any { it != base && it.substringBefore('-') == base } }.singleOrNull()
}

/** The language heard on this track: the one its title names, else its tag. */
internal fun TrackInfo.spokenLanguage(): String? =
    audioTitleLanguage(name) ?: streamLanguageCode(language)

/** True when the title names a language the tag doesn't say ("PT-BR" tagged jpn or por), or there is no tag. */
internal fun TrackInfo.titleOverridesTag(): Boolean {
    val titled = audioTitleLanguage(name) ?: return false
    val tagged = streamLanguageCode(language) ?: return true
    return titled != tagged
}

/**
 * Position of the first track speaking one of [languages], in their order (by [spokenLanguage]): the
 * same variant first ("pt-br" before "pt"), then the same base language; -1 when none does.
 */
internal fun findTitledAudioTrackIndex(tracks: List<TrackInfo>, languages: List<String>): Int {
    val spoken = tracks.map { it.spokenLanguage() }
    for (language in languages) {
        val target = streamLanguageCode(language) ?: continue
        val sameVariant = spoken.indexOf(target)
        if (sameVariant >= 0) return sameVariant
        val sameBase = spoken.indexOfFirst { it != null && sameLanguage(it, target) }
        if (sameBase >= 0) return sameBase
    }
    return -1
}

/**
 * The engines choose the audio by tag (ExoPlayer's preferred languages, mpv's alang). When a title
 * names another language than its tag, the track is picked here, once per stream. A saved or
 * requested choice is left to the restore pass that follows.
 */
internal fun PlayerRuntimeController.applyTitledAudioTrack(audioTracks: List<TrackInfo>) {
    if (audioTracks.isEmpty() || titledAudioStreamUrl == currentStreamUrl) return
    titledAudioStreamUrl = currentStreamUrl
    if (persistedTrackPreference?.audio != null || pendingEngineSwitchTrackPreference != null) return
    if (audioTracks.none { it.titleOverridesTag() }) return
    val index = findTitledAudioTrackIndex(audioTracks, mpvPreferredAudioLanguages)
    if (index < 0 || audioTracks[index].isSelected) return
    selectAudioTrack(index)
    _uiState.update { it.copy(selectedAudioTrackIndex = index) }
}

/**
 * A saved audio choice matched by language alone can land on the wrong track when a title names
 * another language than the tags: the track heard in the saved language wins over [matched].
 */
internal fun titledSavedAudioTrackIndex(
    tracks: List<TrackInfo>,
    saved: PlayerRuntimeController.RememberedTrackSelection,
    matched: Int,
): Int {
    val language = audioTitleLanguage(saved.name) ?: streamLanguageCode(saved.language) ?: return matched
    if (tracks.getOrNull(matched)?.spokenLanguage() == language) return matched
    return findTitledAudioTrackIndex(tracks, listOf(language)).takeIf { it >= 0 } ?: matched
}
