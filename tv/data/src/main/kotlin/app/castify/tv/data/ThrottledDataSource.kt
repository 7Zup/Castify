package app.castify.tv.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Caps the download rate of the player (user rule: the buffer must not take the whole line).
 * One [RateLimiter] is shared by every request of a player; its rate follows the playing
 * quality ([app.castify.domain.playback.DataRules.maxRateBps]).
 */
class RateLimiter(@Volatile var bitsPerSecond: Long) {
    private var nextFreeNanos = System.nanoTime()

    /** Blocks the loader thread so that, over time, bytes flow at [bitsPerSecond]. */
    fun acquire(bytes: Int) {
        if (bytes <= 0) return
        val cost = bytes * 8L * 1_000_000_000L / bitsPerSecond.coerceAtLeast(1)
        val wait: Long
        synchronized(this) {
            val now = System.nanoTime()
            // No credit saved while idle beyond half a second: short bursts only.
            if (nextFreeNanos < now - BURST_NANOS) nextFreeNanos = now - BURST_NANOS
            nextFreeNanos += cost
            wait = nextFreeNanos - now
        }
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }

    private companion object { const val BURST_NANOS = 500_000_000L }
}

@OptIn(UnstableApi::class)
class ThrottledDataSource(private val upstream: DataSource, private val limiter: RateLimiter) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = upstream.read(buffer, offset, length)
        limiter.acquire(read)
        return read
    }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory, private val limiter: RateLimiter) : DataSource.Factory {
        override fun createDataSource(): DataSource = ThrottledDataSource(upstream.createDataSource(), limiter)
    }
}
