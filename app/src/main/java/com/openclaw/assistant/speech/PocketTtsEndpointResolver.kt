package com.openclaw.assistant.speech

import java.net.URI

internal object PocketTtsEndpointResolver {
    private const val SERVICE_PATH = "/hey-gpt-tts/v1/tts"

    fun resolveConfiguredUrl(urlInput: String): String? {
        val raw = urlInput.trim().removeSuffix("/")
        if (raw.isEmpty()) return null

        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        if (parsed.scheme?.lowercase() != "https" || parsed.host.isNullOrBlank()) return null
        if (parsed.userInfo != null || parsed.query != null || parsed.fragment != null) return null
        if (parsed.port != -1 && parsed.port !in 1..65535) return null

        val path = when (parsed.path?.removeSuffix("/")) {
            "", "/", "/hey-gpt-tts" -> SERVICE_PATH
            SERVICE_PATH -> SERVICE_PATH
            else -> return null
        }
        return URI("https", null, parsed.host, parsed.port, path, null, null).toASCIIString()
    }

    fun resolve(hostInput: String, configuredPort: Int, configuredTls: Boolean): String? {
        val raw = hostInput.trim().removeSuffix("/")
        if (raw.isEmpty() || configuredPort !in 1..65535) return null

        val parsed = raw.takeIf { it.contains("://") }
            ?.let { runCatching { URI(it) }.getOrNull() }
        val scheme = parsed?.scheme?.lowercase()
        val tls = when (scheme) {
            "https", "wss" -> true
            "http", "ws" -> false
            null -> configuredTls
            else -> return null
        }
        if (!tls) return null

        val host = parsed?.host ?: raw
        if (host.isBlank() || host.contains('/') || host.contains('?') || host.contains('#')) return null
        val port = parsed?.port?.takeIf { it in 1..65535 } ?: configuredPort
        val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val portSuffix = if (port == 443) "" else ":$port"
        return "https://$formattedHost$portSuffix$SERVICE_PATH"
    }
}
