package app.castify.protocol

import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.PlaybackState
import app.castify.domain.model.StreamType
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.series.Episode
import app.castify.domain.series.EpisodeNumber
import app.castify.domain.series.EpisodeRequest
import app.castify.domain.series.SeriesInfo
import app.castify.domain.series.Show
import app.castify.domain.subtitles.SubtitleFormat
import app.castify.domain.subtitles.SubtitleSource
import app.castify.domain.subtitles.SubtitleTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phone -> TV protocol, v1 of the wire format.
 *
 * The TV app serves plain HTTP on the LAN and advertises itself with mDNS/NSD.
 * - `POST /play` with a [PlayRequest] JSON body → 200 when playback was started.
 * - `GET /status` → [StatusResponse].
 * - `POST /control` with a [ControlRequest] → 200, 400 if invalid.
 */
object CastifyProtocol {
    const val SERVICE_TYPE = "_castify._tcp."
    const val DEFAULT_PORT = 47_800
    const val PATH_PLAY = "/play"
    const val PATH_STATUS = "/status"
    const val PATH_CONTROL = "/control"
    const val VERSION = 1

    /**
     * mDNS service names must not reuse the TV's own name: Google TV already advertises
     * other services under it and some Android versions reject the duplicate
     * (NameConflictException). The suffix makes ours unique; phones strip it for display.
     */
    private const val SERVICE_NAME_SUFFIX = " · Castify"
    private const val MAX_SERVICE_NAME_BYTES = 63

    fun serviceNameFor(deviceName: String): String {
        var base = deviceName.trim().ifEmpty { "TV" }
        while ((base + SERVICE_NAME_SUFFIX).toByteArray().size > MAX_SERVICE_NAME_BYTES) base = base.dropLast(1)
        return base + SERVICE_NAME_SUFFIX
    }

    fun displayNameOf(serviceName: String): String =
        serviceName.removeSuffix(SERVICE_NAME_SUFFIX).ifEmpty { serviceName }

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * UDP discovery, next to mDNS (Android's mDNS resolve can get stuck until the app is
     * killed). The phone broadcasts [DISCOVERY_QUERY] to UDP [DISCOVERY_PORT]; each TV answers
     * to the sender with a [DiscoveryReply]. The reply's source address is the TV's IP.
     */
    const val DISCOVERY_PORT = 47_801
    const val DISCOVERY_QUERY = "CASTIFY_DISCOVER_1"

    fun discoveryReply(deviceName: String, port: Int = DEFAULT_PORT): ByteArray =
        json.encodeToString(DiscoveryReply.serializer(), DiscoveryReply(name = deviceName.trim().ifEmpty { "TV" }, port = port))
            .toByteArray()

    /** Null when the packet is not a Castify reply. */
    fun parseDiscoveryReply(bytes: ByteArray, length: Int = bytes.size): DiscoveryReply? =
        runCatching { json.decodeFromString(DiscoveryReply.serializer(), String(bytes, 0, length)) }
            .getOrNull()?.takeIf { it.app == DiscoveryReply.APP && it.port in 1..65_535 }

    fun isDiscoveryQuery(bytes: ByteArray, length: Int = bytes.size): Boolean =
        String(bytes, 0, length).trim() == DISCOVERY_QUERY
}

@Serializable
data class DiscoveryReply(
    val app: String = APP,
    val version: Int = CastifyProtocol.VERSION,
    val name: String,
    val port: Int,
) {
    companion object { const val APP = "castify-tv" }
}

@Serializable
data class PlayRequest(
    val version: Int = CastifyProtocol.VERSION,
    val url: String,
    val type: String,
    val pageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    /** Fixed video height picked on the phone; null = player default. */
    val preferredHeight: Int? = null,
    /** Lets every connected phone show the video. */
    val thumbnailUrl: String? = null,
    /** Subtitle files of the page; the TV side-loads them, off by default. */
    val subtitles: List<SubtitleFile> = emptyList(),
    /** Set when the video is an episode of a known series. */
    val series: SeriesPayload? = null,
)

@Serializable
data class SeriesPayload(
    val show: String,
    val season: Int,
    val episode: Int,
    val episodes: List<EpisodePayload> = emptyList(),
    /** Episode address, reported back in the status for phones that connect later. */
    val address: String? = null,
)

@Serializable
data class EpisodePayload(val season: Int, val episode: Int, val title: String? = null, val runtimeMin: Int? = null, val imageUrl: String? = null)

@Serializable
data class EpisodeRequestPayload(val id: Long, val season: Int, val episode: Int)

@Serializable
data class SubtitleFile(val url: String, val format: String, val language: String? = null, val label: String? = null)

@Serializable
data class SubtitleTrackInfo(val id: String, val language: String? = null, val label: String? = null)

/** `GET /status`: what the TV plays right now. */
@Serializable
data class StatusResponse(
    val state: String,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val isLive: Boolean = false,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val thumbnailHeaders: Map<String, String> = emptyMap(),
    val bufferedPositionMs: Long = 0,
    val subtitles: List<SubtitleTrackInfo> = emptyList(),
    /** Null = subtitles off. */
    val selectedSubtitleId: String? = null,
    val autoNext: Boolean = false,
    /** Another episode the TV asked for (answered by the first phone to claim it). */
    val episodeRequest: EpisodeRequestPayload? = null,
    val seriesPageUrl: String? = null,
    val preferredHeight: Int? = null,
)

/**
 * `POST /control`: one playback command. [action] is one of [ControlRequest.Companion] constants.
 * `subtitles`: [trackId] = track to show, null = subtitles off. `autoNext`: [enabled].
 * `episodeFailed`: [valueMs] = id of the TV's episode request.
 */
@Serializable
data class ControlRequest(val action: String, val valueMs: Long? = null, val trackId: String? = null, val enabled: Boolean? = null) {
    companion object {
        const val TOGGLE = "toggle"
        const val SEEK_BY = "seekBy"
        const val SEEK_TO = "seekTo"
        const val STOP = "stop"
        const val SUBTITLES = "subtitles"
        const val AUTO_NEXT = "autoNext"
        const val EPISODE_FAILED = "episodeFailed"
        /** 409 when another phone claimed the request first. */
        const val CLAIM_EPISODE = "claimEpisode"
    }
}

@Serializable
data class ErrorResponse(val error: String)

fun NowPlaying.toStatusResponse() = StatusResponse(
    state = state.name,
    positionMs = positionMs,
    durationMs = durationMs,
    isLive = isLive,
    title = title,
    thumbnailUrl = thumbnailUrl,
    thumbnailHeaders = thumbnailHeaders,
    bufferedPositionMs = bufferedPositionMs,
    subtitles = subtitles.map { SubtitleTrackInfo(it.id, it.language, it.label) },
    selectedSubtitleId = selectedSubtitleId,
    autoNext = autoNext,
    episodeRequest = episodeRequest?.let { EpisodeRequestPayload(it.id, it.number.season, it.number.episode) },
    seriesPageUrl = seriesPageUrl,
    preferredHeight = preferredHeight,
)

fun StatusResponse.toNowPlaying() = NowPlaying(
    state = PlaybackState.entries.firstOrNull { it.name == state } ?: PlaybackState.IDLE,
    positionMs = positionMs,
    durationMs = durationMs,
    isLive = isLive,
    title = title,
    thumbnailUrl = thumbnailUrl,
    thumbnailHeaders = thumbnailHeaders,
    bufferedPositionMs = bufferedPositionMs,
    subtitles = subtitles.map { SubtitleTrack(it.id, it.language, it.label) },
    selectedSubtitleId = selectedSubtitleId,
    autoNext = autoNext,
    episodeRequest = episodeRequest?.let { EpisodeRequest(it.id, EpisodeNumber(it.season, it.episode)) },
    seriesPageUrl = seriesPageUrl?.takeIf { it.startsWith("http") },
    preferredHeight = preferredHeight?.takeIf { it > 0 },
)

/** Returns null for [PlayerCommand.ToggleDiagnostics] (TV remote only, never sent). */
fun PlayerCommand.toControlRequest(): ControlRequest? = when (this) {
    PlayerCommand.TogglePause -> ControlRequest(ControlRequest.TOGGLE)
    is PlayerCommand.SeekBy -> ControlRequest(ControlRequest.SEEK_BY, deltaMs)
    is PlayerCommand.SeekTo -> ControlRequest(ControlRequest.SEEK_TO, positionMs)
    PlayerCommand.Stop -> ControlRequest(ControlRequest.STOP)
    is PlayerCommand.SelectSubtitle -> ControlRequest(ControlRequest.SUBTITLES, trackId = trackId)
    is PlayerCommand.SetAutoNext -> ControlRequest(ControlRequest.AUTO_NEXT, enabled = enabled)
    is PlayerCommand.EpisodeFailed -> ControlRequest(ControlRequest.EPISODE_FAILED, valueMs = requestId)
    is PlayerCommand.ClaimEpisode -> ControlRequest(ControlRequest.CLAIM_EPISODE, valueMs = requestId)
    PlayerCommand.ToggleDiagnostics, PlayerCommand.OpenSubtitleMenu, PlayerCommand.ShowControls -> null
}

/** Returns null when the request is invalid (unknown action, missing value). */
fun ControlRequest.toPlayerCommand(): PlayerCommand? = when (action) {
    ControlRequest.TOGGLE -> PlayerCommand.TogglePause
    ControlRequest.SEEK_BY -> valueMs?.let { PlayerCommand.SeekBy(it) }
    ControlRequest.SEEK_TO -> valueMs?.takeIf { it >= 0 }?.let { PlayerCommand.SeekTo(it) }
    ControlRequest.STOP -> PlayerCommand.Stop
    ControlRequest.SUBTITLES -> PlayerCommand.SelectSubtitle(trackId?.takeIf { it.isNotBlank() })
    ControlRequest.AUTO_NEXT -> enabled?.let { PlayerCommand.SetAutoNext(it) }
    ControlRequest.EPISODE_FAILED -> valueMs?.let { PlayerCommand.EpisodeFailed(it) }
    ControlRequest.CLAIM_EPISODE -> valueMs?.let { PlayerCommand.ClaimEpisode(it) }
    else -> null
}

fun MediaStream.toPlayRequest() = PlayRequest(
    url = url,
    type = type.name,
    pageUrl = pageUrl,
    headers = headers,
    title = title,
    preferredHeight = preferredHeight,
    thumbnailUrl = thumbnailUrl,
    subtitles = subtitles.map { SubtitleFile(it.url, it.format.name, it.language, it.label) },
    series = series?.let { info ->
        SeriesPayload(
            show = info.showName,
            season = info.current.season,
            episode = info.current.episode,
            episodes = info.episodes.map { EpisodePayload(it.number.season, it.number.episode, it.title, it.runtimeMin, it.imageUrl) },
            address = info.addressUrl,
        )
    },
)

/** Returns null when the request is not playable (unknown type, non-HTTP URL). */
fun PlayRequest.toMediaStream(): MediaStream? {
    val streamType = StreamType.entries.firstOrNull { it.name == type } ?: return null
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    return MediaStream(
        url = url,
        type = streamType,
        pageUrl = pageUrl,
        headers = headers,
        title = title,
        preferredHeight = preferredHeight?.takeIf { it > 0 },
        thumbnailUrl = thumbnailUrl?.takeIf { it.startsWith("http") },
        // Unknown formats or non-HTTP files are dropped, not fatal.
        subtitles = subtitles.mapNotNull { file ->
            val format = SubtitleFormat.entries.firstOrNull { it.name == file.format } ?: return@mapNotNull null
            if (!file.url.startsWith("http")) return@mapNotNull null
            SubtitleSource(file.url, format, file.language, file.label)
        },
        series = series?.takeIf { it.season > 0 && it.episode > 0 }?.let { payload ->
            SeriesInfo(
                showName = payload.show,
                current = EpisodeNumber(payload.season, payload.episode),
                episodes = Show.of(
                    payload.show,
                    payload.episodes.map { Episode(EpisodeNumber(it.season, it.episode), it.title, it.runtimeMin, it.imageUrl?.takeIf { u -> u.startsWith("http") }) },
                ).episodes,
                addressUrl = payload.address?.takeIf { it.startsWith("http") },
            )
        },
    )
}
