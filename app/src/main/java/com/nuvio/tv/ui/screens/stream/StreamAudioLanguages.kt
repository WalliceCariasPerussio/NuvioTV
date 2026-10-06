package com.nuvio.tv.ui.screens.stream

import android.os.LocaleList
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.normalizeLanguageCode
import com.nuvio.tv.ui.screens.player.resolvePreferredAudioLanguages
import java.util.Locale

// Audio language and quality of a stream, read from its text: AIOStreams' parsed languages,
// the "🔊" line written by nuvio-providers, or Torrentio's flags. Codes are normalized so the
// same language from different addons is counted once. Ported from the desktop app
// (NuvioDesktop features/streams/StreamAudioQualityFilter.kt).

internal const val StreamLanguageUnknown = "unknown"

private const val AudioMarker = "🔊"
private const val SubtitleMarker = "💬"
private const val OtherTracksMarker = "🌐"

enum class StreamQualityBucket { UHD_4K, QHD_1440, FHD_1080, HD_720, SD, OTHER }

internal data class StreamTraits(
    val audioLanguages: List<String>,
    val quality: StreamQualityBucket,
)

/** Labels nuvio-providers writes in the "🔊" line (tracks.js `LANG_NAMES`). */
private val ProviderLanguageNames = mapOf(
    "pt-br" to "pt-br",
    "pt-pt" to "pt",
    "português" to "pt",
    "portugues" to "pt",
    "inglês" to "en",
    "ingles" to "en",
    "japonês" to "ja",
    "espanhol" to "es",
    "espanhol (la)" to "es-419",
    "italiano" to "it",
    "francês" to "fr",
    "alemão" to "de",
    "russo" to "ru",
    "hindi" to "hi",
    "tâmil" to "ta",
    "telugu" to "te",
    "coreano" to "ko",
    "chinês" to "zh",
    "polonês" to "pl",
    "húngaro" to "hu",
    "tcheco" to "cs",
    "eslovaco" to "sk",
    "ucraniano" to "uk",
    "árabe" to "ar",
    "turco" to "tr",
    "sueco" to "sv",
    "holandês" to "nl",
    "hebraico" to "he",
    "tailandês" to "th",
    "vietnamita" to "vi",
    "indonésio" to "id",
)

/** Regional variants the TV's normalizeLanguageCode leaves untouched. */
private val RegionalLanguageAliases = mapOf(
    "pt_br" to "pt-br",
    "ptbr" to "pt-br",
    "pob" to "pt-br",
    "brazilian" to "pt-br",
    "portuguese (brazil)" to "pt-br",
    "pt_pt" to "pt",
    "es-la" to "es-419",
    "es_419" to "es-419",
    "es-lat" to "es-419",
    "latino" to "es-419",
)

private val FlagLanguages = mapOf(
    "🇬🇧" to "en",
    "🇺🇸" to "en",
    "🇧🇷" to "pt-br",
    "🇵🇹" to "pt",
    "🇯🇵" to "ja",
    "🇪🇸" to "es",
    "🇲🇽" to "es-419",
    "🇮🇹" to "it",
    "🇫🇷" to "fr",
    "🇩🇪" to "de",
    "🇷🇺" to "ru",
    "🇰🇷" to "ko",
    "🇨🇳" to "zh",
    "🇵🇱" to "pl",
    "🇺🇦" to "uk",
    "🇮🇳" to "hi",
    "🇹🇷" to "tr",
    "🇳🇱" to "nl",
    "🇸🇪" to "sv",
)

private val IsoLanguages: Set<String> = Locale.getISOLanguages().toSet()

// Last resort for streams without a "🔊" line or flags. "Dual Áudio" only counts with the
// accent: that is how the Brazilian providers name PT-BR + original releases.
private val DubbedPtBrPattern = Regex("(^|[^a-z0-9])(dublado|dublada|pt-?br|nacional|dual áudio)([^a-z0-9]|$)")
private val WithoutPtBrPattern = Regex("(^|[^a-z0-9])(sem|no|without)[ -]+(pt-?br|dublagem|portugu)")

// Same tokens as the desktop's DebridStreamMetadata.resolutionValue, compiled once.
private val ResolutionPatterns = listOf(
    StreamQualityBucket.UHD_4K to resolutionPattern("2160p?", "4k", "uhd"),
    StreamQualityBucket.QHD_1440 to resolutionPattern("1440p?", "2k"),
    StreamQualityBucket.FHD_1080 to resolutionPattern("1080p?", "fhd"),
    StreamQualityBucket.HD_720 to resolutionPattern("720p?", "hd"),
    StreamQualityBucket.SD to resolutionPattern("576p?", "480p?", "360p?", "sd"),
)

private fun resolutionPattern(vararg tokens: String): Regex =
    Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|$)")

/** Normalized audio language code ("pt-br", "pt", "es-419", "en"...), or null when [raw] is not a language. */
internal fun streamLanguageCode(raw: String?): String? {
    val cleaned = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val named = ProviderLanguageNames[cleaned] ?: RegionalLanguageAliases[cleaned] ?: cleaned
    val code = (RegionalLanguageAliases[named] ?: normalizeLanguageCode(named) ?: return null)
        .replace('_', '-')
    return code.takeIf { it.substringBefore('-') in IsoLanguages }
}

internal fun Stream.streamTraits(): StreamTraits = StreamTraits(
    audioLanguages = detectAudioLanguages(),
    quality = detectQuality(),
)

private fun Stream.detectAudioLanguages(): List<String> {
    clientResolve?.stream?.raw?.parsed?.languages
        ?.mapNotNull(::streamLanguageCode)
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    val lines = listOfNotNull(name, title, description).flatMap { it.lines() }
    lines.firstOrNull { AudioMarker in it }?.let { line ->
        return line.substringAfter(AudioMarker)
            .split('+')
            .mapNotNull { part ->
                streamLanguageCode(part.replace("(original)", "").trim().trimEnd('?'))
            }
            .distinct()
    }

    val flagged = lines
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith(SubtitleMarker) || trimmed.startsWith(OtherTracksMarker)
        }
        .flatMap { line -> flagsIn(line).mapNotNull(FlagLanguages::get) }
        .distinct()
    if (flagged.isNotEmpty()) return flagged

    val text = listOfNotNull(name, title, behaviorHints?.filename).joinToString(" ").lowercase()
    if (WithoutPtBrPattern.containsMatchIn(text)) return emptyList()
    return if (DubbedPtBrPattern.containsMatchIn(text)) listOf("pt-br") else emptyList()
}

/**
 * Flags of [line], in order. A flag is a pair of regional indicator letters, read in aligned pairs:
 * two flags side by side hold a false one across them (🇬🇧🇷🇺 has B+R, Brazil, in the middle).
 */
private fun flagsIn(line: String): List<String> {
    val flags = mutableListOf<String>()
    var pending: Int? = null
    var index = 0
    while (index < line.length) {
        val codePoint = line.codePointAt(index)
        index += Character.charCount(codePoint)
        if (codePoint !in 0x1F1E6..0x1F1FF) {
            pending = null
            continue
        }
        val first = pending
        if (first == null) {
            pending = codePoint
        } else {
            flags += String(intArrayOf(first, codePoint), 0, 2)
            pending = null
        }
    }
    return flags
}

private fun Stream.detectQuality(): StreamQualityBucket {
    val parsed = clientResolve?.stream?.raw?.parsed
    val candidates = listOfNotNull(
        parsed?.resolution,
        parsed?.quality,
        quality,
        name,
        listOfNotNull(title, description, behaviorHints?.filename).joinToString(" "),
    )
    for (candidate in candidates) {
        val text = candidate.lowercase()
        ResolutionPatterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(text) }
            ?.let { return it.first }
    }
    return when {
        qualityValue >= 2160 -> StreamQualityBucket.UHD_4K
        qualityValue >= 1440 -> StreamQualityBucket.QHD_1440
        qualityValue >= 1080 -> StreamQualityBucket.FHD_1080
        qualityValue >= 720 -> StreamQualityBucket.HD_720
        qualityValue > 0 -> StreamQualityBucket.SD
        else -> StreamQualityBucket.OTHER
    }
}

internal fun languageCounts(traits: List<StreamTraits>): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    traits.forEach { streamTraits ->
        streamTraits.audioLanguages.ifEmpty { listOf(StreamLanguageUnknown) }.forEach { key ->
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    return counts
}

/** Identified languages, most streams first. */
internal fun languageKeysByCount(counts: Map<String, Int>): List<String> =
    counts.entries
        .filter { it.key != StreamLanguageUnknown }
        .sortedByDescending { it.value }
        .map { it.key }

/** The bucket a preferred language selects: exact code first, then the same base language. */
internal fun matchingLanguageKey(keysByCount: List<String>, target: String): String? =
    keysByCount.firstOrNull { it == target }
        ?: keysByCount.firstOrNull { it.substringBefore('-') == target.substringBefore('-') }

/** Preferred audio languages in order (primary, then secondary), as normalized codes. */
internal fun preferredAudioTargets(settings: PlayerSettings, contentOriginalLanguage: String?): List<String> {
    val locales = LocaleList.getDefault()
    val deviceLanguages = (0 until locales.size()).map { locales[it].toLanguageTag() }
    return resolvePreferredAudioLanguages(
        preferredAudioLanguage = settings.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
        deviceLanguages = deviceLanguages,
        contentOriginalLanguage = contentOriginalLanguage,
    ).mapNotNull(::streamLanguageCode).distinct()
}

/** Chip/summary label: "PT-BR", the localized language name, or the given fallbacks. */
internal fun streamLanguageLabel(key: String, portugueseLabel: String, unknownLabel: String): String = when (key) {
    "pt-br" -> "PT-BR"
    "pt" -> portugueseLabel
    StreamLanguageUnknown -> unknownLabel
    else -> Locale.forLanguageTag(key)
        .getDisplayName(Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
