package com.nuvio.tv.core.ai

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Transforma os títulos sugeridos pelo modelo em cartazes do TMDB, como os itens TMDB das coleções
 * (`tmdb:<id>`). Título que o TMDB não acha fica de fora.
 */
internal object AiTitleResolver {
    private const val TAG = "AiTitleResolver"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(titles: List<AiSuggestedTitle>, language: String): List<MetaPreview> = coroutineScope {
        val apiKey = BuildConfig.TMDB_API_KEY.takeIf(String::isNotBlank) ?: return@coroutineScope emptyList()
        val lang = language.ifBlank { "en" }
        titles
            .take(AiRecommendationPrompt.MAX_TITLES)
            .map { title -> async { resolveOne(title, apiKey, lang) } }
            .awaitAll()
            .filterNotNull()
            // Filme e série do TMDB podem ter o mesmo número: o tipo faz parte da identidade.
            .distinctBy { it.rawType to it.id }
    }

    private suspend fun resolveOne(title: AiSuggestedTitle, apiKey: String, language: String): MetaPreview? {
        val endpoint = if (title.type == AiTitleType.Series) "search/tv" else "search/movie"
        val yearParam = if (title.type == AiTitleType.Series) "first_air_date_year" else "primary_release_year"
        val baseQuery = mapOf(
            "query" to URLEncoder.encode(title.title, "UTF-8").replace("+", "%20"),
            "language" to language,
            "include_adult" to "false",
        )
        val typed = title.year
            ?.let { year -> search(endpoint, apiKey, baseQuery + (yearParam to year.toString())) }
            ?.let { results -> pickBestTmdbMatch(results, title.title, TmdbSearchResult::names, TmdbSearchResult::votes) }
            ?: search(endpoint, apiKey, baseQuery)
                ?.let { results -> pickBestTmdbMatch(results, title.title, TmdbSearchResult::names, TmdbSearchResult::votes) }
        if (typed != null) return typed.toMetaPreview(title.type, suggestedName = title.title)

        // O modelo às vezes troca filme por série (comum em anime): tenta a busca geral.
        val multi = search("search/multi", apiKey, baseQuery)
            ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            ?.let { results ->
                val sameYear = results.filter { result -> title.year != null && result.year() == title.year }
                pickBestTmdbMatch(sameYear.ifEmpty { results }, title.title, TmdbSearchResult::names, TmdbSearchResult::votes)
            }
            ?: return null
        return multi.toMetaPreview(
            type = if (multi.mediaType == "tv") AiTitleType.Series else AiTitleType.Movie,
            suggestedName = title.title,
        )
    }

    private suspend fun search(
        endpoint: String,
        apiKey: String,
        query: Map<String, String>,
    ): List<TmdbSearchResult>? = try {
        val params = (mapOf("api_key" to apiKey) + query).entries.joinToString("&") { (key, value) -> "$key=$value" }
        val response = AiHttp.get(AiHttpRequest("https://api.themoviedb.org/3/$endpoint?$params", emptyMap(), ""))
        if (response.status !in 200..299) {
            Log.w(TAG, "TMDB $endpoint respondeu ${response.status}")
            null
        } else {
            json.decodeFromString<TmdbSearchResponse>(response.body).results.filter { it.id > 0 }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Log.w(TAG, "TMDB $endpoint falhou: ${error.message}")
        null
    }
}

@Serializable
private data class TmdbSearchResponse(
    val results: List<TmdbSearchResult> = emptyList(),
)

@Serializable
private data class TmdbSearchResult(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
) {
    fun year(): Int? = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()

    fun names(): List<String?> = listOf(title, name, originalTitle, originalName)

    fun votes(): Int = voteCount ?: 0

    fun toMetaPreview(type: AiTitleType, suggestedName: String): MetaPreview? {
        val tmdbName = names().firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: return null
        val contentType = if (type == AiTitleType.Series) ContentType.SERIES else ContentType.MOVIE
        val date = (releaseDate ?: firstAirDate)?.takeIf(String::isNotBlank)
        return MetaPreview(
            id = "tmdb:$id",
            type = contentType,
            rawType = type.wireName,
            name = aiDisplayName(tmdbName = tmdbName, suggestedName = suggestedName),
            poster = tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780"),
            posterShape = PosterShape.POSTER,
            background = tmdbImageUrl(backdropPath, "w1280"),
            logo = null,
            description = overview?.trim()?.takeIf(String::isNotEmpty),
            releaseInfo = date?.take(4),
            released = date,
            imdbRating = voteAverage?.takeIf { it > 0.0 }?.toFloat(),
            voteCount = voteCount,
            genres = emptyList(),
        )
    }
}

/**
 * Escolhe entre os primeiros resultados da busca. Com o idioma `pt`, o TMDB põe na frente o derivado
 * que tem nome traduzido ("Overlord: Play Play Pleiades", 0 votos) e deixa atrás a série principal
 * (nome só em japonês, 1143 votos). Vale o nome exato; sem nome exato, o mais votado.
 */
internal fun <T> pickBestTmdbMatch(
    results: List<T>,
    query: String,
    names: (T) -> List<String?>,
    votes: (T) -> Int,
    candidates: Int = 3,
): T? {
    val top = results.take(candidates)
    if (top.isEmpty()) return null
    val wanted = query.normalizedTitle()
    val exact = top.filter { result -> names(result).any { it != null && it.normalizedTitle() == wanted } }
    return exact.ifEmpty { top }.maxByOrNull(votes)
}

private fun String.normalizedTitle(): String =
    lowercase().filter { it.isLetterOrDigit() }

/**
 * Sem tradução no idioma pedido, o TMDB devolve o nome original (屍鬼, コープスパーティー). Nesse
 * caso o cartaz mostra o nome que o modelo sugeriu ("Shiki", "Corpse Party").
 */
internal fun aiDisplayName(tmdbName: String, suggestedName: String): String {
    val suggested = suggestedName.trim()
    return if (!tmdbName.hasLatinLetter() && suggested.hasLatinLetter()) suggested else tmdbName
}

private fun String.hasLatinLetter(): Boolean = any { it in 'A'..'Z' || it in 'a'..'z' || it in 'À'..'ÿ' }

private fun tmdbImageUrl(path: String?, size: String): String? =
    path?.trim()?.takeIf(String::isNotEmpty)?.let { "https://image.tmdb.org/t/p/$size$it" }
