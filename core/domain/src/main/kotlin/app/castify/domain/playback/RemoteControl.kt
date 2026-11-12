package app.castify.domain.playback

/**
 * Keys of the Chromecast with Google TV remote that Castify uses.
 * The remote has no media keys: everything goes through the D-pad, OK and Back.
 */
enum class RemoteKey { OK, LEFT, RIGHT, UP, DOWN, BACK }

/** Commands applied to the TV player, from the TV remote or from a phone. */
sealed interface PlayerCommand {
    data object TogglePause : PlayerCommand
    data class SeekBy(val deltaMs: Long) : PlayerCommand
    data class SeekTo(val positionMs: Long) : PlayerCommand
    data object Stop : PlayerCommand
    /** Shows the subtitle track [trackId]; null = subtitles off. */
    data class SelectSubtitle(val trackId: String?) : PlayerCommand
    /** TV remote only: shows/hides the diagnostics overlay. Never sent by phones. */
    data object ToggleDiagnostics : PlayerCommand
    /** TV remote only: opens the subtitle menu (from the `CC` button). Never sent by phones. */
    data object OpenSubtitleMenu : PlayerCommand
    /** TV remote only: shows the player controls (Netflix style). Never sent by phones. */
    data object ShowControls : PlayerCommand
    /** Turns the TV's Auto next setting on or off (phone checkbox). */
    data class SetAutoNext(val enabled: Boolean) : PlayerCommand
    /** The phone could not find the video of the episode the TV asked for ([requestId]). */
    data class EpisodeFailed(val requestId: Long) : PlayerCommand
    /** Plays the video in the quality of [height] (one of [app.castify.domain.model.NowPlaying.qualities]). */
    data class SelectQuality(val height: Int) : PlayerCommand
    /** A phone takes the TV's episode request [requestId]; only the first one gets it. */
    data class ClaimEpisode(val requestId: Long) : PlayerCommand

    /** Commands that only change the TV screen, not the player. */
    val isTvOnly: Boolean get() = this == ToggleDiagnostics || this == OpenSubtitleMenu || this == ShowControls
}

/**
 * Maps remote keys to player commands while a video is on screen and the player controls
 * are hidden (shortcuts). ▼ shows the controls; inside them, keys move the focus (TV UI).
 * Back stops the video (Home / leaving the app stops it too).
 */
object RemoteControl {
    const val SEEK_STEP_MS = 10_000L

    fun commandFor(key: RemoteKey): PlayerCommand? = when (key) {
        RemoteKey.OK -> PlayerCommand.TogglePause
        RemoteKey.LEFT -> PlayerCommand.SeekBy(-SEEK_STEP_MS)
        RemoteKey.RIGHT -> PlayerCommand.SeekBy(SEEK_STEP_MS)
        RemoteKey.UP -> PlayerCommand.ToggleDiagnostics
        RemoteKey.BACK -> PlayerCommand.Stop
        RemoteKey.DOWN -> PlayerCommand.ShowControls
    }
}
