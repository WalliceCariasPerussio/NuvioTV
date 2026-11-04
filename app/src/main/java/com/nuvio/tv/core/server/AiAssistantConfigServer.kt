package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.core.ai.AiProvider
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

/**
 * Página para colar a chave de API do assistente pelo celular (digitar com o controle é ruim). Só
 * fica no ar enquanto o QR code está na tela. A página nunca recebe a chave já salva: o GET só diz
 * qual provedor está escolhido e se cada um já tem chave.
 */
class AiAssistantConfigServer(
    private val stateProvider: () -> AiAssistantWebState,
    private val onKeyReceived: (AiProvider, String) -> Unit,
    private val context: Context? = null,
    port: Int = 8110,
    /** Vai no endereço do QR: só quem leu este QR consegue ler o estado ou mandar a chave. */
    val token: String = newToken(),
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == "/" -> newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            AiAssistantWebPage.html(context),
        )
        session.method == Method.GET && session.uri == "/api/state" ->
            if (!hasToken(session)) forbidden() else json(
                Response.Status.OK,
                json.encodeToString(AiAssistantWebState.serializer(), stateProvider()),
            )
        session.method == Method.POST && session.uri == "/api/key" -> handleKey(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun hasToken(session: IHTTPSession): Boolean =
        session.parameters["t"]?.firstOrNull() == token

    private fun forbidden(): Response = json(Response.Status.FORBIDDEN, """{"error":"expired"}""")

    private fun handleKey(session: IHTTPSession): Response {
        if (!hasToken(session)) return forbidden()
        val request = runCatching {
            json.decodeFromString(AiAssistantKeyRequest.serializer(), readUtf8Body(session))
        }.getOrNull()
        val provider = request?.provider?.let { id -> AiProvider.entries.firstOrNull { it.id == id } }
        val apiKey = request?.apiKey?.trim().orEmpty()
        if (provider == null || apiKey.isEmpty() || apiKey.length > MAX_KEY_LENGTH) {
            return json(Response.Status.BAD_REQUEST, """{"error":"invalid"}""")
        }
        onKeyReceived(provider, apiKey)
        return json(Response.Status.OK, """{"status":"saved"}""")
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun readUtf8Body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (length <= 0 || length > MAX_BODY_BYTES) return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, StandardCharsets.UTF_8)
    }

    companion object {
        private const val MAX_KEY_LENGTH = 1_000
        private const val MAX_BODY_BYTES = 8_192
        private val json = Json { ignoreUnknownKeys = true }

        private fun newToken(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun startOnAvailablePort(
            stateProvider: () -> AiAssistantWebState,
            onKeyReceived: (AiProvider, String) -> Unit,
            context: Context? = null,
            startPort: Int = 8110,
            maxAttempts: Int = 10,
        ): AiAssistantConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = AiAssistantConfigServer(stateProvider, onKeyReceived, context, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}

@Serializable
data class AiAssistantWebState(
    val provider: String,
    /** Provedores que já têm chave salva nesta TV (só o id, nunca a chave). */
    val configured: List<String>,
)

@Serializable
internal data class AiAssistantKeyRequest(
    val provider: String? = null,
    val apiKey: String? = null,
)
