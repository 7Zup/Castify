package app.castify.domain

import app.castify.domain.update.AppVersion
import app.castify.domain.update.ReleaseNote
import app.castify.domain.update.ReleaseNotes
import app.castify.domain.update.SeenVersionStore
import app.castify.domain.update.UpdateCheckThrottle
import app.castify.domain.update.WhatsNew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {

    private class MemoryStore(override var lastSeen: AppVersion? = null) : SeenVersionStore

    private fun v(text: String) = AppVersion.parse(text)!!

    private val notes = listOf(
        ReleaseNote(v("0.6.0"), "Nov 12, 2026", listOf(ReleaseNote.Item("New", "Something"))),
        ReleaseNote(v("0.5.4"), "Nov 12, 2026", listOf(ReleaseNote.Item("Fix", "Something else"))),
        ReleaseNote(v("0.7.0"), "Later", listOf(ReleaseNote.Item("Future", "Not installed"))),
    )

    @Test
    fun `after an update the pop-up shows once`() {
        val store = MemoryStore(lastSeen = v("0.5.4"))
        val whatsNew = WhatsNew(v("0.6.0"), store, wasUpdated = true, notes)
        assertEquals(v("0.6.0"), whatsNew.popup()?.version)
        whatsNew.markSeen()
        assertNull(whatsNew.popup())
        assertNull(WhatsNew(v("0.6.0"), store, wasUpdated = true, notes).popup())
    }

    @Test
    fun `the first version with the pop-up shows it to updated apps, not to fresh installs`() {
        assertEquals(v("0.6.0"), WhatsNew(v("0.6.0"), MemoryStore(), wasUpdated = true, notes).popup()?.version)
        val fresh = MemoryStore()
        assertNull(WhatsNew(v("0.6.0"), fresh, wasUpdated = false, notes).popup())
        assertEquals(v("0.6.0"), fresh.lastSeen)
    }

    @Test
    fun `a version without notes shows nothing, the list stops at the installed version`() {
        assertNull(WhatsNew(v("0.6.1"), MemoryStore(v("0.6.0")), wasUpdated = true, notes).popup())
        assertEquals(listOf(v("0.6.0"), v("0.5.4")), WhatsNew(v("0.6.0"), MemoryStore(), true, notes).all.map { it.version })
    }

    @Test
    fun `shipped notes are newest first and the tv gets its own wording`() {
        val versions = ReleaseNotes.ALL.map { it.version }
        assertEquals(versions.sortedDescending(), versions)
        val series = ReleaseNotes.ALL.first().items.first()
        assertTrue(series.detailFor(tv = true).contains("Episodes"))
        assertFalse(series.detailFor(tv = false).contains("▼"))
    }

    @Test
    fun `the tv checks at most once every ten minutes`() {
        var now = 0L
        val throttle = UpdateCheckThrottle(minIntervalMs = 600_000, clock = { now })
        assertTrue(throttle.due())
        now = 300_000
        assertFalse(throttle.due())
        now = 600_000
        assertTrue(throttle.due())
    }
}
