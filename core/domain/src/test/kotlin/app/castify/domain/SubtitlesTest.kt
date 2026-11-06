package app.castify.domain

import app.castify.domain.detection.VideoCatalog
import app.castify.domain.inspection.InspectStream
import app.castify.domain.model.ObservedRequest
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.playback.RemoteControl
import app.castify.domain.playback.RemoteKey
import app.castify.domain.port.ManifestFetcher
import app.castify.domain.subtitles.LanguageName
import app.castify.domain.subtitles.SubtitleClassifier
import app.castify.domain.subtitles.SubtitleFormat
import app.castify.domain.subtitles.SubtitleMenu
import app.castify.domain.subtitles.SubtitleSource
import app.castify.domain.subtitles.SubtitleTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {

    @Test
    fun `language names are in English for every code style`() {
        assertEquals("French", LanguageName.englishName("fr"))
        assertEquals("French", LanguageName.englishName("fr-CA"))
        assertEquals("French", LanguageName.englishName("fra"))
        assertEquals("French", LanguageName.englishName("fre"))
        assertEquals("German", LanguageName.englishName("ger"))
        assertEquals("Spanish", LanguageName.englishName("es_419"))
        assertNull(LanguageName.englishName("und"))
        assertNull(LanguageName.englishName("xx-garbage-123"))
        assertNull(LanguageName.englishName(null))
    }

    @Test
    fun `menu is Off, English first, alphabetical, Unknown last`() {
        val options = SubtitleMenu.options(
            listOf(
                SubtitleTrack("1", "es"),
                SubtitleTrack("2", null),
                SubtitleTrack("3", "fr"),
                SubtitleTrack("4", "en"),
                SubtitleTrack("5", "de"),
                SubtitleTrack("6", "und"),
            ),
        )
        assertEquals(
            listOf("Off", "English", "French", "German", "Spanish", "Unknown 1", "Unknown 2"),
            options.map { it.name },
        )
        assertNull(options.first().trackId)
        assertEquals("4", options[1].trackId)
    }

    @Test
    fun `site labels tell tracks of one language apart, plain names do not repeat`() {
        val options = SubtitleMenu.options(
            listOf(
                SubtitleTrack("a", "en", "English"),
                SubtitleTrack("b", "en", "Forced"),
                SubtitleTrack("c", "en", "English (SDH)"),
                SubtitleTrack("d", "fr"),
                SubtitleTrack("e", "fr"),
            ),
        ).map { it.name }
        assertEquals(listOf("Off", "English", "English (Forced)", "English (SDH)", "French", "French 2"), options)
    }

    @Test
    fun `native language names in labels are not repeated (real Apple test stream)`() {
        val options = SubtitleMenu.options(
            listOf(
                SubtitleTrack("3:0", "en", "English"),
                SubtitleTrack("4:0", "en", "English (Forced)"),
                SubtitleTrack("5:0", "fr", "Français"),
                SubtitleTrack("6:0", "fr", "Français (Forced)"),
                SubtitleTrack("7:0", "es", "Español"),
                SubtitleTrack("9:0", "ja", "日本語"),
                SubtitleTrack("10:0", "ja", "日本語 (Forced)"),
                SubtitleTrack("11:0", "en", "Sintel EN (file)"),
            ),
        ).map { it.name }
        assertEquals(
            listOf(
                "Off", "English", "English (Forced)", "English (Sintel file)",
                "French", "French (Forced)", "Japanese", "Japanese (Forced)", "Spanish",
            ),
            options,
        )
    }

    @Test
    fun `language can come from the label when the code is missing`() {
        val options = SubtitleMenu.options(listOf(SubtitleTrack("x", null, "Français"))).map { it.name }
        assertEquals(listOf("Off", "French"), options)
    }

    @Test
    fun `menu with no tracks only offers Off`() {
        assertEquals(listOf("Off"), SubtitleMenu.options(emptyList()).map { it.name })
    }

    @Test
    fun `subtitle files are recognized with their language guessed from the address`() {
        fun lang(url: String) = SubtitleClassifier.classify(url)?.language
        assertEquals(SubtitleFormat.VTT, SubtitleClassifier.classify("https://cdn.com/subs/movie_fr.vtt")?.format)
        assertEquals(SubtitleFormat.SRT, SubtitleClassifier.classify("https://cdn.com/a/b.srt?token=1")?.format)
        assertEquals(SubtitleFormat.VTT, SubtitleClassifier.classify("https://cdn.com/t/42", "text/vtt")?.format)
        assertEquals("fr", lang("https://cdn.com/subs/movie_fr.vtt"))
        assertEquals("eng", lang("https://cdn.com/movie.eng.srt"))
        assertEquals("en", lang("https://cdn.com/en/subtitles.vtt"))
        assertEquals("es", lang("https://cdn.com/subs/Spanish.vtt"))
        assertNull(lang("https://cdn.com/subs/1.vtt"))
        assertNull(lang("https://cdn.com/subs/track.vtt"))
    }

    @Test
    fun `hls subtitle segments and non subtitles are ignored`() {
        assertNull(SubtitleClassifier.classify("https://cdn.com/subs/sub_00012.vtt"))
        assertNull(SubtitleClassifier.classify("https://cdn.com/subs/segment-123.vtt"))
        assertNull(SubtitleClassifier.classify("https://cdn.com/movie.mp4"))
        assertNull(SubtitleClassifier.classify("blob:https://site.com/1"))
    }

    @Test
    fun `catalog attaches page subtitles to the videos, track element wins over a guess`() = runTest {
        val fetcher = object : ManifestFetcher {
            override suspend fun fetch(url: String, headers: Map<String, String>) =
                Result.success(ManifestFetcher.Fetched(url, "#EXTM3U\n#EXTINF:3600,\na.ts\n#EXT-X-ENDLIST"))
            override suspend fun fetchRange(url: String, headers: Map<String, String>, start: Long, endInclusive: Long) =
                Result.failure<ByteArray>(IllegalStateException())
        }
        val catalog = VideoCatalog(InspectStream(fetcher), this)
        catalog.onRequest(ObservedRequest("https://cdn.com/subs/1.vtt", "https://site.com"))
        catalog.onRequest(ObservedRequest("https://cdn.com/v.m3u8", "https://site.com"))
        testScheduler.advanceUntilIdle()
        assertNull(catalog.videos.value.single().subtitles.single().language)

        catalog.onSubtitle(SubtitleSource("https://cdn.com/subs/1.vtt", SubtitleFormat.VTT, "fr", "Français"), fromTrackElement = true)
        catalog.onRequest(ObservedRequest("https://cdn.com/subs/1.vtt", "https://site.com")) // re-request: guess must not win
        val sub = catalog.videos.value.single().subtitles.single()
        assertEquals("fr", sub.language)
        assertEquals("Français", sub.label)
    }

    @Test
    fun `a page switching movies keeps only the newest file per language (bug 11-05-2026)`() {
        val old = SubtitleSource("https://cdn/old_en.vtt", SubtitleFormat.VTT, "en")
        val oldUnknown = SubtitleSource("https://cdn/old_1.vtt", SubtitleFormat.VTT)
        val new = SubtitleSource("https://cdn/new_en.vtt", SubtitleFormat.VTT, "en")
        val newUnknown = SubtitleSource("https://cdn/new_1.vtt", SubtitleFormat.VTT)
        val sdh = SubtitleSource("https://cdn/new_en_sdh.vtt", SubtitleFormat.VTT, "en-US", "SDH")
        assertEquals(
            listOf(oldUnknown, new, newUnknown, sdh),
            VideoCatalog.latestPerLanguage(listOf(old, oldUnknown, new, newUnknown, sdh)),
        )
    }

    @Test
    fun `new page clears subtitles of the previous one`() = runTest {
        val fetcher = object : ManifestFetcher {
            override suspend fun fetch(url: String, headers: Map<String, String>) =
                Result.failure<ManifestFetcher.Fetched>(IllegalStateException())
            override suspend fun fetchRange(url: String, headers: Map<String, String>, start: Long, endInclusive: Long) =
                Result.failure<ByteArray>(IllegalStateException())
        }
        val catalog = VideoCatalog(InspectStream(fetcher), this)
        catalog.onRequest(ObservedRequest("https://cdn.com/subs/movie1_en.vtt", "https://site.com/movie/1"))
        catalog.reset() // what the browser now also does when a single-page site changes path
        catalog.onRequest(ObservedRequest("https://cdn.com/movie2.m3u8", "https://site.com/movie/2"))
        assertTrue(catalog.videos.value.single().subtitles.isEmpty())
    }

    @Test
    fun `remote down shows the player controls, tv-only commands are flagged`() {
        assertEquals(PlayerCommand.ShowControls, RemoteControl.commandFor(RemoteKey.DOWN))
        assertTrue(PlayerCommand.ShowControls.isTvOnly)
        assertTrue(PlayerCommand.OpenSubtitleMenu.isTvOnly)
        assertTrue(PlayerCommand.ToggleDiagnostics.isTvOnly)
        assertEquals(false, PlayerCommand.SelectSubtitle(null).isTvOnly)
    }
}
