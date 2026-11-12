package app.castify.domain.model

import app.castify.domain.series.EpisodeRequest
import app.castify.domain.series.SeriesInfo
import app.castify.domain.subtitles.SubtitleSource
import app.castify.domain.subtitles.SubtitleTrack

/** Media container / streaming protocol of a detected stream. */
enum class StreamType { HLS, DASH, PROGRESSIVE }

/**
 * A network request observed while browsing, before classification.
 * [headers] are the request headers the page sent (Referer, User-Agent, Cookie…).
 */
data class ObservedRequest(
    val url: String,
    val pageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
)

/** A playable stream, with everything the TV needs to fetch it like the page did. */
data class MediaStream(
    val url: String,
    val type: StreamType,
    val pageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    /** True when the stream is likely an ad (known ad host). Such streams are hidden. */
    val likelyAd: Boolean = false,
    /** Video height chosen by the user (fixed quality); null = the player decides. */
    val preferredHeight: Int? = null,
    /** Shown by every phone connected to the TV (remote control, notification). */
    val thumbnailUrl: String? = null,
    /** Subtitle files seen on the page, side-loaded by the TV (off by default). */
    val subtitles: List<SubtitleSource> = emptyList(),
    /** Set when the video is an episode of a known series (episodes, Next on the TV). */
    val series: SeriesInfo? = null,
)

/**
 * What a TV is playing, as seen by any connected phone.
 * [thumbnailHeaders] are the Referer/User-Agent needed to load the thumbnail.
 */
data class NowPlaying(
    val state: PlaybackState,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val isLive: Boolean = false,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val thumbnailHeaders: Map<String, String> = emptyMap(),
    /** How far the TV has downloaded (absolute position), shown as the buffer in progress bars. */
    val bufferedPositionMs: Long = 0,
    /** Text tracks available on the TV; [selectedSubtitleId] null = subtitles off. */
    val subtitles: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleId: String? = null,
    /** TV setting shared by every phone ([app.castify.domain.port.TvSettings.autoNext]). */
    val autoNext: Boolean = false,
    /** Another episode the TV asked for; the first phone to claim it answers. */
    val episodeRequest: EpisodeRequest? = null,
    /**
     * Page of the episode playing (null = not a series): a phone that connects while it plays
     * finds the series from it, without going back to the site.
     */
    val seriesPageUrl: String? = null,
    /** Quality the playing video was sent in (null = player default). */
    val preferredHeight: Int? = null,
    /** Qualities the video offers, best first; [qualityHeight] = the one playing. */
    val qualities: List<Quality> = emptyList(),
    val qualityHeight: Int? = null,
) {
    /** The quality playing, as listed (closest height when the stream reports another one). */
    val currentQuality: Quality?
        get() {
            val height = qualityHeight ?: return null
            return qualities.firstOrNull { it.height == height } ?: qualities.minByOrNull { kotlin.math.abs(it.height - height) }
        }

    /** A video is loaded on the TV (playing, paused or loading). */
    val isActive: Boolean
        get() = state == PlaybackState.PLAYING || state == PlaybackState.PAUSED || state == PlaybackState.BUFFERING

    companion object {
        val IDLE = NowPlaying(PlaybackState.IDLE)

        /** Built on the TV from the player stats and the stream being played. */
        fun from(
            stats: PlaybackStats,
            stream: MediaStream?,
            autoNext: Boolean = false,
            episodeRequest: EpisodeRequest? = null,
        ): NowPlaying =
            if (stream == null) IDLE.copy(autoNext = autoNext, episodeRequest = episodeRequest)
            else NowPlaying(
                autoNext = autoNext,
                episodeRequest = episodeRequest,
                seriesPageUrl = stream.series?.addressUrl,
                qualities = stats.qualities,
                qualityHeight = stats.qualityHeight,
                preferredHeight = stream.preferredHeight,
                state = stats.state,
                positionMs = stats.positionMs,
                durationMs = stats.durationMs,
                isLive = stats.isLive,
                bufferedPositionMs = stats.positionMs + stats.bufferedAheadMs,
                subtitles = stats.subtitles,
                selectedSubtitleId = stats.selectedSubtitleId,
                title = stream.title,
                thumbnailUrl = stream.thumbnailUrl,
                thumbnailHeaders = stream.headers.filterKeys {
                    it.equals("Referer", true) || it.equals("User-Agent", true)
                },
            )
    }
}

/** One quality level of an adaptive stream (HLS variant / DASH representation). */
data class Variant(
    val width: Int?,
    val height: Int?,
    val bandwidth: Long?,
    val url: String,
)

/**
 * A quality the user can pick. [height] is the real encoded height (sent to the TV to lock
 * playback); [label] is the usual name (see `QualityLabel`): a 1920x800 widescreen movie
 * is "1080p", not "800p".
 */
data class Quality(val height: Int, val label: String)

/** What inspecting a stream's manifest tells us. */
data class StreamDetails(
    val durationMs: Long? = null,
    val isLive: Boolean = false,
    /** Available qualities, best first, one per label (e.g. 1080p, 720p, 480p). */
    val qualities: List<Quality> = emptyList(),
    /** Sub-playlists referenced by a master playlist: they are not separate videos. */
    val childUrls: Set<String> = emptySet(),
)

/** A `<video>` element seen in the page (direct source, poster, size, duration). */
data class VideoElement(
    val src: String?,
    val posterUrl: String?,
    val durationMs: Long?,
    val height: Int?,
    val width: Int? = null,
)

/** A detected video as shown in the list. */
data class DetectedVideo(
    val stream: MediaStream,
    val thumbnailUrl: String?,
    /** Null while the manifest is being inspected. */
    val details: StreamDetails?,
    /** Subtitle files of the page, sent with the video. */
    val subtitles: List<SubtitleSource> = emptyList(),
)

/** A Castify TV app found on the local network. */
data class TvDevice(
    val name: String,
    val host: String,
    val port: Int,
) {
    val id: String get() = "$host:$port"
}

/** Any Google Cast device on the Wi-Fi (TV, plain Chromecast, smart TV, speaker), Castify or not. */
data class CastDevice(
    val name: String,
    val host: String,
    /** False for audio-only devices (speakers). */
    val hasVideo: Boolean,
    /** A speaker group: not a real device, never listed. */
    val isGroup: Boolean = false,
)

enum class DeviceKind { TV, SPEAKER }

/** Whether the user can play on a listed device right now. */
enum class TvStatus { READY, NOT_RESPONDING, APP_NOT_LAUNCHED }

/** One line of the "Play on" list. [castify] is set when the Castify TV app answers. */
data class NearbyTv(
    val name: String,
    val kind: DeviceKind,
    val castify: TvDevice?,
    val host: String,
    /** False = the Castify TV was found but does not answer right now ("Not responding"). */
    val responding: Boolean = true,
) {
    val status: TvStatus get() = when {
        castify == null -> TvStatus.APP_NOT_LAUNCHED
        !responding -> TvStatus.NOT_RESPONDING
        else -> TvStatus.READY
    }
    val id: String get() = castify?.id ?: host
}

/** Playback state on the TV, as shown by the diagnostic overlay. */
enum class PlaybackState { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

data class PlaybackStats(
    val state: PlaybackState = PlaybackState.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val isLive: Boolean = false,
    /** How much media is downloaded ahead of the playhead. */
    val bufferedAheadMs: Long = 0,
    /** Network throughput estimate, in kilobits per second. */
    val bandwidthKbps: Long? = null,
    /** Download rate cap applied by the TV ([app.castify.domain.playback.DataRules]), kbps. */
    val rateCapKbps: Long? = null,
    /** Number of times playback stalled after it had started. */
    val rebufferCount: Int = 0,
    /** Human readable format, e.g. "HLS · 1920x1080 · 4.2 Mbps". */
    val format: String? = null,
    val error: PlaybackError? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    /** Null = subtitles off. */
    val selectedSubtitleId: String? = null,
    /** Qualities the video offers, best first (one when the site has only one). */
    val qualities: List<Quality> = emptyList(),
    /** Height of the video playing now; null until it is known. */
    val qualityHeight: Int? = null,
)

data class PlaybackError(
    val code: String,
    val message: String,
    /** HTTP status returned by the server, when the failure is an HTTP error. */
    val httpStatus: Int? = null,
)
