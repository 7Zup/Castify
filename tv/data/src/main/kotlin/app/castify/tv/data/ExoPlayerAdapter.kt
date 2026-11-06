package app.castify.tv.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import app.castify.domain.subtitles.SubtitleFormat
import app.castify.domain.subtitles.SubtitleTrack
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import app.castify.domain.model.MediaStream
import app.castify.domain.model.PlaybackError
import app.castify.domain.model.PlaybackState
import app.castify.domain.model.PlaybackStats
import app.castify.domain.model.StreamType
import app.castify.domain.playback.BufferPolicy
import app.castify.domain.playback.DataRules
import app.castify.domain.port.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Media3 ExoPlayer behind the domain [Player] port.
 *
 * A new ExoPlayer is built for each stream, because the buffer policy and the request
 * headers (Referer, Cookie, User-Agent captured on the phone) are per stream.
 * Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class ExoPlayerAdapter(private val context: Context) : Player {

    private val _stats = MutableStateFlow(PlaybackStats())
    override val stats: StateFlow<PlaybackStats> = _stats.asStateFlow()

    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var hasStarted = false
    private var seeking = false
    private var selector: DefaultTrackSelector? = null
    // Download rate cap of the current player (DataRules): follows the playing quality.
    private var limiter = RateLimiter(DataRules.maxRateBps(null))
    /** Track id ("group:index") → (track group, index), rebuilt on each tracks change. */
    private var textTracks: Map<String, Pair<TrackGroup, Int>> = emptyMap()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            player?.let { p ->
                _stats.update {
                    it.copy(
                        positionMs = p.currentPosition,
                        durationMs = p.duration.takeIf { d -> d > 0 && !p.isCurrentMediaItemLive },
                        isLive = p.isCurrentMediaItemLive,
                        bufferedAheadMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0),
                    )
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    /** Binds the video surface. The view lives in the UI; only the surface is shared. */
    fun attach(playerView: PlayerView) {
        view = playerView
        playerView.useController = false
        playerView.player = player
    }

    fun detach() {
        view?.player = null
        view = null
    }

    override fun play(stream: MediaStream, policy: BufferPolicy, startPositionMs: Long) {
        releasePlayer()
        hasStarted = false
        _stats.value = PlaybackStats(state = PlaybackState.BUFFERING, format = stream.type.name)

        val headers = stream.headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Range", true) }
        val userAgent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
            .apply { if (userAgent != null) setUserAgent(userAgent) }

        limiter = RateLimiter(DataRules.maxRateBps(null))
        _stats.update { it.copy(rateCapKbps = limiter.bitsPerSecond / 1_000) }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                policy.minBufferMs,
                policy.maxBufferMs,
                policy.startPlaybackMs,
                policy.resumeAfterStallMs,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Fixed quality picked on the phone: lock video tracks to that height. Audio tracks
        // are untouched, so separate HLS audio renditions keep working.
        // Subtitles are off when a video starts (user rule): text tracks disabled.
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .apply {
                    stream.preferredHeight?.let { height ->
                        setMinVideoSize(0, height)
                        setMaxVideoSize(Int.MAX_VALUE, height)
                    }
                }
                .build()
        }
        selector = trackSelector

        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ThrottledDataSource.Factory(http, limiter)))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
        exo.addListener(playerListener)
        exo.addAnalyticsListener(analyticsListener)
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(stream.url)
                .setMimeType(mimeTypeOf(stream.type))
                // Subtitle files seen on the page, fetched with the same headers as the video.
                .setSubtitleConfigurations(
                    stream.subtitles.mapIndexed { index, sub ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                            .setId("file-$index")
                            .setMimeType(mimeTypeOf(sub.format))
                            .setLanguage(sub.language)
                            .setLabel(sub.label)
                            .build()
                    },
                )
                .build(),
        )
        if (startPositionMs > 0) exo.seekTo(startPositionMs) // Retry after a failure: same place
        exo.playWhenReady = true
        exo.prepare()
        player = exo
        view?.player = exo
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    override fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        seeking = true
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, duration))
    }

    override fun seekTo(positionMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        seeking = true
        p.seekTo(positionMs.coerceIn(0, duration))
    }

    override fun selectSubtitle(trackId: String?) {
        val trackSelector = selector ?: return
        val builder = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val target = trackId?.let(textTracks::get)
        trackSelector.parameters = if (target == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(target.first, target.second))
                .build()
        }
    }

    override fun stop() {
        releasePlayer()
        _stats.value = PlaybackStats(state = PlaybackState.IDLE)
    }

    override fun release() {
        releasePlayer()
        detach()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(ticker)
        view?.player = null
        player?.release()
        player = null
        selector = null
        textTracks = emptyMap()
    }

    private val playerListener = object : Media3Player.Listener {
        override fun onEvents(player: Media3Player, events: Media3Player.Events) {
            val state = when {
                player.playerError != null -> PlaybackState.ERROR
                player.playbackState == Media3Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                player.playbackState == Media3Player.STATE_ENDED -> PlaybackState.ENDED
                player.playbackState == Media3Player.STATE_IDLE -> PlaybackState.IDLE
                player.isPlaying -> PlaybackState.PLAYING
                else -> PlaybackState.PAUSED
            }
            _stats.update { it.copy(state = state) }
        }

        /** Lists text tracks (stream + side-loaded files) and which one is shown. */
        override fun onTracksChanged(tracks: Tracks) {
            val map = linkedMapOf<String, Pair<TrackGroup, Int>>()
            val list = mutableListOf<SubtitleTrack>()
            var selected: String? = null
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val format = group.getTrackFormat(i)
                    val id = "$groupIndex:$i"
                    map[id] = group.mediaTrackGroup to i
                    list += SubtitleTrack(id, format.language?.takeIf { it != "und" }, format.label)
                    if (group.isTrackSelected(i)) selected = id
                }
            }
            textTracks = map
            _stats.update { it.copy(subtitles = list, selectedSubtitleId = selected) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Media3Player.STATE_READY -> { hasStarted = true; seeking = false }
                Media3Player.STATE_BUFFERING -> when {
                    seeking -> seeking = false // buffering after a seek is not a stall
                    hasStarted -> _stats.update { it.copy(rebufferCount = it.rebufferCount + 1) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val httpStatus = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            _stats.update {
                it.copy(
                    state = PlaybackState.ERROR,
                    error = PlaybackError(
                        code = error.errorCodeName,
                        message = error.cause?.message ?: error.message ?: "Playback failed",
                        httpStatus = httpStatus,
                    ),
                )
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            _stats.update { it.copy(bandwidthKbps = bitrateEstimate / 1_000) }
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            val type = _stats.value.format?.substringBefore(" ·") ?: ""
            val size = if (format.width > 0) " · ${format.width}x${format.height}" else ""
            limiter.bitsPerSecond = DataRules.maxRateBps(format.bitrate.takeIf { it > 0 }?.toLong())
            _stats.update { it.copy(rateCapKbps = limiter.bitsPerSecond / 1_000) }
            val bitrate = if (format.bitrate > 0) " · %.1f Mbps".format(format.bitrate / 1_000_000.0) else ""
            _stats.update { it.copy(format = "$type$size$bitrate") }
        }
    }

    private fun mimeTypeOf(format: SubtitleFormat): String = when (format) {
        SubtitleFormat.VTT -> MimeTypes.TEXT_VTT
        SubtitleFormat.SRT -> MimeTypes.APPLICATION_SUBRIP
        SubtitleFormat.SSA -> MimeTypes.TEXT_SSA
        SubtitleFormat.TTML -> MimeTypes.APPLICATION_TTML
    }

    private fun mimeTypeOf(type: StreamType): String? = when (type) {
        StreamType.HLS -> MimeTypes.APPLICATION_M3U8
        StreamType.DASH -> MimeTypes.APPLICATION_MPD
        StreamType.PROGRESSIVE -> null
    }
}
