package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.ProfileAddonEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonProfileTransferTest {
    private val cinemeta = ProfileAddonEntry("https://v3-cinemeta.strem.io", name = null, enabled = true)
    private val subtitles = ProfileAddonEntry("https://opensubtitles-v3.strem.io", name = "Legendas", enabled = false)

    @Test
    fun appendsAtTheEndKeepingTheTargetListAsItIs() {
        val merged = appendAddonEntry(listOf(cinemeta, subtitles), "https://torrentio.strem.fun/abc", "Torrentio")

        assertEquals(
            listOf(
                cinemeta,
                subtitles,
                ProfileAddonEntry("https://torrentio.strem.fun/abc", name = "Torrentio", enabled = true)
            ),
            merged
        )
    }

    @Test
    fun alreadyInstalledIgnoresCaseTrailingSlashAndManifestSuffix() {
        assertNull(appendAddonEntry(listOf(cinemeta), "https://V3-Cinemeta.strem.io/manifest.json", null))
        assertNull(appendAddonEntry(listOf(cinemeta), "https://v3-cinemeta.strem.io/", null))
    }

    @Test
    fun percentEncodedConfigFromTheDesktopIsTheSameAddon() {
        val configured = ProfileAddonEntry("https://torrentio.strem.fun/providers=yts|qualityfilter=480p", name = null, enabled = true)

        assertNull(
            appendAddonEntry(listOf(configured), "https://torrentio.strem.fun/providers=yts%7Cqualityfilter=480p/manifest.json", null)
        )
    }

    @Test
    fun configuredAddonsAreDifferentAddons() {
        val configured = ProfileAddonEntry("https://torrentio.strem.fun/providers=yts", name = null, enabled = true)

        val merged = appendAddonEntry(listOf(configured), "https://torrentio.strem.fun/providers=eztv/manifest.json", null)

        assertEquals("https://torrentio.strem.fun/providers=eztv", merged?.last()?.url)
    }

    @Test
    fun blankNameIsNotSent() {
        val merged = appendAddonEntry(emptyList(), "https://torrentio.strem.fun", "  ")

        assertNull(merged?.single()?.name)
        assertTrue(sameAddonUrl("https://torrentio.strem.fun/manifest.json", "https://torrentio.strem.fun"))
    }
}
