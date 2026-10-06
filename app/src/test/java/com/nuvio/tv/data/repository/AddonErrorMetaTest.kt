package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonErrorMetaTest {

    @Test
    fun `rate limit placeholder is an addon error`() {
        assertTrue("⚠️ Rate limit exceeded — please wait a few minutes before trying again.".looksLikeAddonError())
        assertTrue("429 Too Many Requests".looksLikeAddonError())
        assertTrue("Ratelimit hit".looksLikeAddonError())
    }

    @Test
    fun `real titles are kept`() {
        assertFalse("Hell Mode: O Gamer Hardcore Domina Outro Mundo com um Balanceamento Lixo".looksLikeAddonError())
        assertFalse("Corrida Contra o Tempo".looksLikeAddonError())
        assertFalse("The Limit".looksLikeAddonError())
        assertFalse("Rate My Plate".looksLikeAddonError())
    }
}
