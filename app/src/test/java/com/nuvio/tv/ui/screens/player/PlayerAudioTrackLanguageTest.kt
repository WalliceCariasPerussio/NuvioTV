package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAudioTrackLanguageTest {

    // A dual audio release that tags both tracks with the original language (names as ExoPlayer builds them).
    private val japanese = TrackInfo(index = 0, name = "JPN FLAC 2.0 (FLAC Stereo)", language = "jpn", trackId = "1")
    private val dubbed = TrackInfo(index = 1, name = "PT-BR E-AC-3 2.0 (E-AC-3 Stereo)", language = "jpn", trackId = "2")

    @Test
    fun `the title's language wins over the tag`() {
        val tracks = listOf(japanese, dubbed)

        assertEquals(1, findTitledAudioTrackIndex(tracks, listOf("pt-BR")))
        assertEquals(1, findTitledAudioTrackIndex(tracks, listOf("pt-br", "ja")))
        assertEquals(0, findTitledAudioTrackIndex(tracks, listOf("ja")))
        assertEquals(-1, findTitledAudioTrackIndex(tracks, listOf("en")))
    }

    @Test
    fun `the same variant comes before the base language`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Português (Portugal)", language = "por"),
            TrackInfo(index = 1, name = "Português (Brasil)", language = "por"),
        )

        assertEquals(1, findTitledAudioTrackIndex(tracks, listOf("pt-br")))
        assertEquals(0, findTitledAudioTrackIndex(tracks, listOf("pt")))
    }

    @Test
    fun `a saved dub is found when the next episode renames its tracks`() {
        val saved = PlayerRuntimeController.RememberedTrackSelection(
            language = "jpn",
            name = "PT-BR E-AC-3 2.0 (E-AC-3 Stereo)",
            trackId = "2",
        )
        val nextEpisode = listOf(
            TrackInfo(index = 0, name = "PT-BR AAC 2.0 (AAC Stereo)", language = "jpn", trackId = "1"),
            TrackInfo(index = 1, name = "JPN AAC 2.0 (AAC Stereo)", language = "jpn", trackId = "2"),
        )

        // The language fallback lands on the first jpn track; the title says the dub is the other one.
        assertEquals(0, titledSavedAudioTrackIndex(nextEpisode, saved, matched = 1))
        assertEquals(0, titledSavedAudioTrackIndex(nextEpisode, saved, matched = 0))
    }

    @Test
    fun `the title overrides the tag only when it says something else`() {
        assertTrue(dubbed.titleOverridesTag())
        assertTrue(TrackInfo(index = 0, name = "Português (Brasil)", language = "por").titleOverridesTag())
        assertFalse(japanese.titleOverridesTag())
        assertFalse(TrackInfo(index = 0, name = "English (AAC Stereo)", language = "eng").titleOverridesTag())
        assertFalse(TrackInfo(index = 0, name = "Stereo", language = "eng").titleOverridesTag())
    }

    @Test
    fun `titles naming a language`() {
        assertEquals("pt-br", audioTitleLanguage("PT-BR E-AC-3 2.0 (E-AC-3 Stereo)"))
        assertEquals("ja", audioTitleLanguage("JPN FLAC 2.0"))
        assertEquals("pt-br", audioTitleLanguage("Português (Brasil)"))
        assertEquals("pt-br", audioTitleLanguage("Dublado"))
        assertEquals("pt", audioTitleLanguage("Português"))
        assertEquals("ja", audioTitleLanguage("Japonês"))
        assertEquals("ja", audioTitleLanguage("Japanese"))
        assertEquals("en", audioTitleLanguage("ENG DTS-HD MA 5.1"))
        assertEquals("es-419", audioTitleLanguage("Español Latino"))
        assertEquals("pt-br", audioTitleLanguage("Português (Brasil)"))
    }

    @Test
    fun `titles without one language name none`() {
        assertNull(audioTitleLanguage(null))
        assertNull(audioTitleLanguage("Stereo"))
        assertNull(audioTitleLanguage("Japanese / English"))
        assertNull(audioTitleLanguage("DTS-ES 6.1"))
        assertNull(audioTitleLanguage("AAC-HE 2.0"))
        assertNull(audioTitleLanguage("DTS-HD HR 5.1"))
        assertNull(audioTitleLanguage("AAC 6CH"))
        assertNull(audioTitleLanguage("Comentário do diretor"))
    }
}
