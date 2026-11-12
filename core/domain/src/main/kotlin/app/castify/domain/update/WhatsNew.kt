package app.castify.domain.update

/**
 * What changed in one version, written for the user (mockup `docs/mockups/whats-new.html`):
 * 2–4 items in plain words, no technical terms. [Item.tvDetail] replaces [Item.detail] on the
 * TV when the way to use it differs (remote keys instead of taps).
 */
data class ReleaseNote(val version: AppVersion, val date: String, val items: List<Item>) {
    data class Item(val title: String, val detail: String, val tvDetail: String? = null) {
        fun detailFor(tv: Boolean): String = if (tv) tvDetail ?: detail else detail
    }
}

/** Every version's notes, newest first, shipped inside the app (shown offline too). */
object ReleaseNotes {
    private fun v(text: String) = AppVersion.parse(text)!!

    val ALL: List<ReleaseNote> = listOf(
        ReleaseNote(
            v("0.6.0"), "Nov 12, 2026",
            listOf(
                ReleaseNote.Item(
                    "Series, episode after episode",
                    "Change episode and season from your phone or the TV.",
                    tvDetail = "Press ▼, then Episodes, to change episode or season.",
                ),
                ReleaseNote.Item("Next episode", "A button 30 s before the end. Tick Auto next to chain them."),
                ReleaseNote.Item("Updates without the hassle", "The TV looks for a new version each time you come back to it."),
                ReleaseNote.Item("What's new", "This window after each update, and every version in the menu.", tvDetail = "This window after each update. All versions: ▼ then OK on the home screen."),
            ),
        ),
        ReleaseNote(
            v("0.5.4"), "Nov 12, 2026",
            listOf(ReleaseNote.Item("Site players work fully", "Their Settings, Subtitles and fullscreen buttons open again.")),
        ),
        ReleaseNote(
            v("0.5.3"), "Nov 12, 2026",
            listOf(
                ReleaseNote.Item("Quiet phone", "The video on your phone pauses as soon as you cast it."),
                ReleaseNote.Item("Clearer connection", "The Cast icon pulses while connecting, and tells you when the TV doesn't answer."),
                ReleaseNote.Item("A mini player that grows", "Tap the bar at the bottom to open the full remote."),
                ReleaseNote.Item("Back works again", "Even after closing the app, Back brings the previous page."),
            ),
        ),
        ReleaseNote(
            v("0.5.2"), "Nov 6, 2026",
            listOf(ReleaseNote.Item("Smoother updates", "Castify asks for the install permission before downloading.")),
        ),
        ReleaseNote(
            v("0.5.1"), "Nov 6, 2026",
            listOf(ReleaseNote.Item("Update test", "A small version to try updating from inside the app.")),
        ),
        ReleaseNote(
            v("0.5.0"), "Nov 6, 2026",
            listOf(
                ReleaseNote.Item("Updates from the app", "When a new version is out, Castify offers it at launch."),
                ReleaseNote.Item("Version number", "Shown at the bottom right of the home screen."),
            ),
        ),
    )
}

/** The last version whose "What's new" the user saw (app preferences). */
interface SeenVersionStore {
    var lastSeen: AppVersion?
}

/**
 * The "What's new" pop-up and the list of every version.
 *
 * Rules:
 * - the pop-up shows once, on the first launch of a version that has notes, after an update;
 * - a fresh install shows nothing (nothing changed for that user);
 * - [all] lists the notes of the installed version and older ones, newest first.
 *
 * [wasUpdated]: the installed app replaced an older one (not a first install).
 */
class WhatsNew(
    private val current: AppVersion,
    private val store: SeenVersionStore,
    wasUpdated: Boolean,
    notes: List<ReleaseNote> = ReleaseNotes.ALL,
) {
    val all: List<ReleaseNote> = notes.filter { it.version <= current }.sortedByDescending { it.version }

    init {
        // First launch of a fresh install, or of the first version with this feature after an
        // update: only an update deserves the pop-up.
        if (store.lastSeen == null && !wasUpdated) store.lastSeen = current
    }

    /** Null when there is nothing new to show. */
    fun popup(): ReleaseNote? {
        val seen = store.lastSeen
        if (seen != null && seen >= current) return null
        return all.firstOrNull { it.version == current }
    }

    /** OK (or Back) on the pop-up: not shown again for this version. */
    fun markSeen() {
        store.lastSeen = current
    }
}

/**
 * The TV checks for an update each time its home screen shows (on a Chromecast the app is
 * rarely really closed, so "at launch" was not enough: user, 11/12/2026), at most once every
 * [minIntervalMs].
 */
class UpdateCheckThrottle(
    private val minIntervalMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastMs: Long? = null

    @Synchronized
    fun due(): Boolean {
        val now = clock()
        val last = lastMs
        if (last != null && now - last in 0 until minIntervalMs) return false
        lastMs = now
        return true
    }
}
