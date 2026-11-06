package app.castify.domain.playback

/**
 * How fast the device that buffers the video may download (user rule, 11/06/2026: the
 * buffer must not take the whole line). Today that is the TV app; later, in Universal mode
 * (Chromecast without the Castify app), the phone too, only once the video is sent.
 *
 * The phone never blocks anything in the page: its own player is only kept from playing for
 * nothing (paused in the background and when the video is sent to the TV).
 */
object DataRules {

    const val RATE_FACTOR = 3
    const val MIN_RATE_BPS = 15_000_000L

    /** 3× the playing quality's bitrate, at least 15 Mbps (unknown bitrate = the minimum). */
    fun maxRateBps(videoBitrateBps: Long?): Long =
        maxOf(MIN_RATE_BPS, (videoBitrateBps ?: 0L).coerceAtLeast(0L) * RATE_FACTOR)
}
