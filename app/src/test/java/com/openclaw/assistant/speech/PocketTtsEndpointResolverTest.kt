package com.openclaw.assistant.speech

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PocketTtsEndpointResolverTest {
    @Test
    fun `normalizes the configured Tailscale Serve base URL`() {
        PocketTtsEndpointResolver.resolveConfiguredUrl(
            "https://minipc.example.ts.net/hey-gpt-tts",
        ) shouldBe "https://minipc.example.ts.net/hey-gpt-tts/v1/tts"
    }

    @Test
    fun `accepts the complete configured speech endpoint`() {
        PocketTtsEndpointResolver.resolveConfiguredUrl(
            "https://minipc.example.ts.net/hey-gpt-tts/v1/tts",
        ) shouldBe "https://minipc.example.ts.net/hey-gpt-tts/v1/tts"
    }

    @Test
    fun `rejects unsafe or ambiguous configured URLs`() {
        PocketTtsEndpointResolver.resolveConfiguredUrl("http://minipc.example.ts.net/hey-gpt-tts") shouldBe null
        PocketTtsEndpointResolver.resolveConfiguredUrl("https://user@minipc.example.ts.net/hey-gpt-tts") shouldBe null
        PocketTtsEndpointResolver.resolveConfiguredUrl("https://minipc.example.ts.net/other") shouldBe null
        PocketTtsEndpointResolver.resolveConfiguredUrl("https://minipc.example.ts.net/hey-gpt-tts?voice=other") shouldBe null
    }

    @Test
    fun `builds the Tailscale Serve endpoint from a TLS gateway`() {
        PocketTtsEndpointResolver.resolve(
            hostInput = "minipc.example.ts.net",
            configuredPort = 443,
            configuredTls = true,
        ) shouldBe "https://minipc.example.ts.net/hey-gpt-tts/v1/tts"
    }

    @Test
    fun `normalizes a wss setup URL`() {
        PocketTtsEndpointResolver.resolve(
            hostInput = "wss://minipc.example.ts.net",
            configuredPort = 443,
            configuredTls = false,
        ) shouldBe "https://minipc.example.ts.net/hey-gpt-tts/v1/tts"
    }

    @Test
    fun `preserves a non-default TLS port`() {
        PocketTtsEndpointResolver.resolve(
            hostInput = "https://gateway.example.ts.net:8443",
            configuredPort = 443,
            configuredTls = true,
        ) shouldBe "https://gateway.example.ts.net:8443/hey-gpt-tts/v1/tts"
    }

    @Test
    fun `rejects cleartext gateway endpoints`() {
        PocketTtsEndpointResolver.resolve(
            hostInput = "100.64.0.10",
            configuredPort = 18789,
            configuredTls = false,
        ) shouldBe null
    }

    @Test
    fun `rejects host input containing a path`() {
        PocketTtsEndpointResolver.resolve(
            hostInput = "minipc.example.ts.net/not-a-host",
            configuredPort = 443,
            configuredTls = true,
        ) shouldBe null
    }
}
