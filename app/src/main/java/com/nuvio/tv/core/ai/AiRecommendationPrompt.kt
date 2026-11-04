package com.nuvio.tv.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal enum class AiTitleType(val wireName: String) {
    Movie("movie"),
    Series("series"),
}

internal data class AiSuggestedTitle(
    val title: String,
    val year: Int?,
    val type: AiTitleType,
)

/** O que o perfil já assistiu, só o que o modelo precisa saber. */
internal data class AiWatchedTitle(
    val name: String,
    val isSeries: Boolean,
    val year: String?,
    val watchedAt: Long,
)

internal data class AiRecommendationReply(
    val reply: String,
    val titles: List<AiSuggestedTitle>,
)

internal object AiRecommendationPrompt {
    const val MAX_TITLES = 15
    private const val MAX_WATCHED_IN_CONTEXT = 150

    val systemPrompt: String = """
        Você é o assistente de recomendações do Nuvio, um app para assistir filmes, séries e animes.
        A pessoa descreve o que quer ver e você responde com uma frase curta em português do Brasil e
        uma lista de títulos, na ordem em que você recomenda.

        Regras:
        - Só títulos que existem de verdade. Escreva o título original ou o mais conhecido
          internacionalmente (como aparece no TMDB), não uma tradução livre.
        - "year" é o ano de lançamento; para série, o ano da primeira temporada.
        - "type" é "movie" para filme (inclusive filme de anime) e "series" para série ou anime em
          episódios.
        - Entre 6 e $MAX_TITLES títulos (nunca mais de $MAX_TITLES), a não ser que a pessoa peça menos.
        - "+18", "mais 18", "maiores de 18" e "adulto" falam de classificação etária (conteúdo
          adulto), não de quantidade.
        - Evite o que a pessoa já assistiu (lista no contexto), a não ser que ela peça.
        - Não repita títulos que você já sugeriu nesta conversa, a não ser que a pessoa peça.
        - Pedidos como "só filmes" ou "mais curtos" refinam a lista anterior da conversa.
        - "Um deles", "desses" ou "da lista" se referem à última lista que você mandou.
        - Se o pedido não for sobre o que assistir, responda em uma frase e devolva a lista vazia.
        - "reply" tem no máximo duas frases e não repete os nomes da lista.

        Responda só com um objeto JSON neste formato:
        {"reply": "texto", "titles": [{"title": "nome", "year": 2010, "type": "series"}]}
    """.trimIndent()

    val responseSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reply") { put("type", "string") }
            putJsonObject("titles") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("year") { put("type", "integer") }
                        putJsonObject("type") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(AiTitleType.Movie.wireName)
                                add(AiTitleType.Series.wireName)
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("title")
                        add("year")
                        add("type")
                    }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") {
            add("reply")
            add("titles")
        }
        put("additionalProperties", false)
    }

    /** O contexto (o que já foi assistido) vai junto da última mensagem, não na instrução fixa. */
    fun buildUserTurn(request: String, watched: List<AiWatchedTitle>): String {
        val names = watchedContextLines(watched)
        if (names.isEmpty()) return request.trim()
        return buildString {
            append(request.trim())
            append("\n\n<contexto>\nJá assistidos neste perfil (mais recentes primeiro):\n")
            names.forEach { append("- ").append(it).append('\n') }
            append("</contexto>")
        }
    }

    internal fun watchedContextLines(watched: List<AiWatchedTitle>): List<String> {
        val seen = mutableSetOf<String>()
        return watched
            .sortedByDescending { it.watchedAt }
            .mapNotNull { item ->
                val name = item.name.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val isSeries = item.isSeries
                val key = "${name.lowercase()}|$isSeries"
                if (!seen.add(key)) return@mapNotNull null
                val year = item.year?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                buildString {
                    append(name)
                    if (year != null) append(" (").append(year).append(')')
                    append(if (isSeries) " [série]" else " [filme]")
                }
            }
            .take(MAX_WATCHED_IN_CONTEXT)
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Lê a resposta do modelo; aceita JSON dentro de ``` e texto antes ou depois do objeto. */
    fun parseReply(text: String): AiRecommendationReply? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { lenientJson.parseToJsonElement(text.substring(start, end + 1)) }
            .getOrNull() as? JsonObject ?: return null
        val reply = (root["reply"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val list = (root["titles"] ?: root["items"] ?: root["results"]) as? JsonArray
        val titles = list.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = (obj["title"] ?: obj["name"])
                .let { it as? JsonPrimitive }?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val yearPrimitive = obj["year"] as? JsonPrimitive
            val year = yearPrimitive?.intOrNull
                ?: yearPrimitive?.contentOrNull?.take(4)?.toIntOrNull()
            val type = when ((obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim()) {
                "series", "tv", "show", "anime", "serie", "série" -> AiTitleType.Series
                else -> AiTitleType.Movie
            }
            AiSuggestedTitle(title = title, year = year?.takeIf { it in 1870..2100 }, type = type)
        }
        if (reply.isEmpty() && titles.isEmpty()) return null
        return AiRecommendationReply(reply = reply, titles = titles)
    }

    /** Versão compacta da resposta, guardada no histórico para as próximas mensagens. */
    fun encodeForHistory(reply: AiRecommendationReply): String = buildJsonObject {
        put("reply", reply.reply)
        put("titles", buildJsonArray {
            reply.titles.forEach { title ->
                add(buildJsonObject {
                    put("title", title.title)
                    title.year?.let { put("year", it) }
                    put("type", title.type.wireName)
                })
            }
        })
    }.toString()
}
