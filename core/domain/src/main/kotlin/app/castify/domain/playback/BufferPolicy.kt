package app.castify.domain.playback

/**
 * How much media the TV player keeps ahead of the playhead.
 *
 * v0: a fixed policy, more generous than the player defaults (50 s max), to compare with
 * Web Video Caster. The smart "buffer everything after 1 min of watching" policy is v2.
 */
data class BufferPolicy(
    /** Player tries to keep at least this much buffered. */
    val minBufferMs: Int,
    /** Player stops downloading once this much is buffered. */
    val maxBufferMs: Int,
    /** Buffered media needed to start playback. */
    val startPlaybackMs: Int,
    /** Buffered media needed to resume after a stall. */
    val resumeAfterStallMs: Int,
) {
    init {
        require(startPlaybackMs in 0..minBufferMs) { "startPlaybackMs must be <= minBufferMs" }
        require(resumeAfterStallMs in 0..minBufferMs) { "resumeAfterStallMs must be <= minBufferMs" }
        require(minBufferMs <= maxBufferMs) { "minBufferMs must be <= maxBufferMs" }
    }

    companion object {
        val V0 = BufferPolicy(
            minBufferMs = 60_000,
            maxBufferMs = 120_000,
            startPlaybackMs = 2_500,
            resumeAfterStallMs = 10_000,
        )
    }
}
