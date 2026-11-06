package app.castify.domain.port

import app.castify.domain.model.CastDevice
import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.PlaybackStats
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.model.TvDevice
import app.castify.domain.playback.BufferPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// ---- Phone side ----

/** Finds Castify TV apps on the local network. */
interface TvDiscovery {
    /** Emits the current list of reachable TVs; discovery runs while collected. */
    fun devices(): Flow<List<TvDevice>>
}

/** Finds every Google Cast device on the local network, whether Castify runs on it or not. */
interface CastDeviceScanner {
    /** Emits the current list; scanning runs while collected. */
    fun devices(): Flow<List<CastDevice>>
}

/** Talks to a Castify TV app. */
interface TvClient {
    suspend fun play(device: TvDevice, stream: MediaStream): Result<Unit>
    suspend fun status(device: TvDevice): Result<NowPlaying>
    /** [command] is never a TV-only command ([PlayerCommand.isTvOnly]). */
    suspend fun control(device: TvDevice, command: PlayerCommand): Result<Unit>
}

/** Downloads manifests (HLS/DASH) and small byte ranges of media files, with the page headers. */
interface ManifestFetcher {
    data class Fetched(val finalUrl: String, val body: String)
    suspend fun fetch(url: String, headers: Map<String, String>): Result<Fetched>

    /**
     * Bytes [start]..[endInclusive] of [url] (HTTP Range). Fewer bytes when the file ends
     * earlier; failure if the server ignores ranges.
     */
    suspend fun fetchRange(url: String, headers: Map<String, String>, start: Long, endInclusive: Long): Result<ByteArray>
}

// ---- TV side ----

/** Serves phones on the LAN: play requests, status, playback commands. */
interface CastReceiver {
    val playRequests: SharedFlow<MediaStream>
    val commands: SharedFlow<PlayerCommand>
    /** When a phone last called the TV (status, control or play), epoch ms; null = never. */
    val lastPhoneCallAtMs: StateFlow<Long?>
    /** [status] is read on each phone poll, from a background thread. */
    fun start(deviceName: String, status: () -> NowPlaying)
    fun stop()
}

/** Whether the device has a working network (phone: internet; TV: Wi-Fi / Ethernet). */
interface NetworkMonitor {
    val online: StateFlow<Boolean>
    /** Check again now (Retry). */
    fun refresh() {}
}

/** Video player running on the TV. */
interface Player {
    val stats: StateFlow<PlaybackStats>
    /** [startPositionMs] > 0 = start there (Retry after a failure). */
    fun play(stream: MediaStream, policy: BufferPolicy, startPositionMs: Long = 0)
    fun togglePause()
    fun seekBy(deltaMs: Long)
    fun seekTo(positionMs: Long)
    /** Null = subtitles off. Text tracks are off when a stream starts. */
    fun selectSubtitle(trackId: String?)
    fun stop()
    fun release()
}
