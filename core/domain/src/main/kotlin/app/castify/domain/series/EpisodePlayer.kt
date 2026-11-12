package app.castify.domain.series

import app.castify.domain.model.DetectedVideo
import app.castify.domain.model.MediaStream
import app.castify.domain.model.PlaybackState
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.port.EpisodeLoader
import app.castify.domain.port.ShowCatalog
import app.castify.domain.session.TvSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone side of TV shows (`docs/features/episodes.md`).
 *
 * Rules:
 * - a video sent from an episode address ([EpisodeAddress]) carries its series ([SeriesInfo]):
 *   the TV then shows its episodes and `Next`;
 * - playing another episode = loading its address out of sight ([EpisodeLoader]), taking the
 *   first watchable video like the user would, and sending it to the TV in the same quality;
 * - a phone that connects while an episode plays gets the series from the TV's status
 *   ([app.castify.domain.model.NowPlaying.seriesPageUrl]): no need to open the site again;
 * - the TV asks for episodes through its status ([app.castify.domain.model.NowPlaying.episodeRequest]):
 *   the first phone to claim the request answers, and tells the TV when it could not find the video;
 * - the next episode is prepared [PREFETCH_BEFORE_END_MS] before the end, so `Next` is quick.
 */
class EpisodePlayer(
    private val catalog: ShowCatalog,
    private val loader: EpisodeLoader,
    private val session: TvSession,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class State(
        val address: EpisodeAddress,
        /** Null when the episode list is unavailable (unknown series, database down). */
        val show: Show?,
        /** Playing on the TV. */
        val current: EpisodeNumber,
        /** Quality the user picked for the first episode, kept for the next ones. */
        val height: Int?,
        val loading: EpisodeNumber? = null,
        val failed: EpisodeNumber? = null,
        /** The TV's setting, as last seen. */
        val autoNext: Boolean = false,
    ) {
        val next: Episode? get() = show?.next(current)
    }

    private val _state = MutableStateFlow<State?>(null)
    /** Null = what the TV plays is not a series this phone sent. */
    val state: StateFlow<State?> = _state.asStateFlow()

    private val shows = mutableMapOf<ShowId, Show>()
    private var playJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetched: Prefetched? = null
    private var handledRequestId: Long? = null

    private class Prefetched(val number: EpisodeNumber, val video: DetectedVideo, val atMs: Long)

    init {
        scope.launch { session.state.collect(::onTvState) }
    }

    /** The series of [address], from the database (cached). Null when unknown or unreachable. */
    suspend fun showOf(address: EpisodeAddress): Show? {
        shows[address.show]?.let { return it }
        val show = withTimeoutOrNull(SHOW_TIMEOUT_MS) { catalog.show(address.show) } ?: return null
        shows[address.show] = show
        return show
    }

    /** [stream] with its series attached, when [address] (the page's episode) is known. */
    suspend fun withSeries(stream: MediaStream, address: EpisodeAddress?): MediaStream {
        address ?: return stream
        val show = showOf(address)
        return stream.withSeries(SeriesInfo(show?.name ?: stream.title ?: "Episode", address.number, show?.episodes.orEmpty(), address.url))
    }

    /** The user sent [stream] to the TV: it is the current episode (or not a series at all). */
    fun onSent(stream: MediaStream, address: EpisodeAddress?, height: Int?) {
        val series = stream.series
        if (address == null || series == null) {
            reset()
            return
        }
        prefetched = null
        _state.value = State(
            address = address,
            show = series.show.takeIf { it.episodes.isNotEmpty() },
            current = series.current,
            height = height,
            autoNext = _state.value?.autoNext ?: session.state.value.nowPlaying?.autoNext ?: false,
        )
    }

    /** Plays episode [number] on the TV (picked on the phone, or asked by the TV). */
    fun play(number: EpisodeNumber, onFailure: () -> Unit = {}) {
        val start = _state.value ?: return
        playJob?.cancel()
        _state.update { it?.copy(loading = number, failed = null) }
        playJob = scope.launch {
            val ok = runCatching { playNow(start, number) }.getOrDefault(false)
            _state.update { s ->
                when {
                    s == null -> null
                    ok -> s.copy(current = number, loading = null, failed = null)
                    else -> s.copy(loading = null, failed = number)
                }
            }
            if (!ok) onFailure()
        }
    }

    fun setAutoNext(enabled: Boolean) {
        _state.update { it?.copy(autoNext = enabled) }
        session.control(PlayerCommand.SetAutoNext(enabled))
    }

    private suspend fun playNow(state: State, number: EpisodeNumber): Boolean {
        val video = prefetched?.takeIf { it.number == number && clock() - it.atMs < PREFETCH_VALID_MS }?.video
            ?: loader.findVideo(state.address.withEpisode(number), FIND_TIMEOUT_MS)
            ?: return false
        prefetched = null
        val episodes = state.show?.episodes.orEmpty()
        val stream = video.stream.copy(thumbnailUrl = video.thumbnailUrl, subtitles = video.subtitles)
            .withSeries(SeriesInfo(state.show?.name ?: "Episode", number, episodes, state.address.withEpisode(number)))
        return session.send(stream, state.height, replace = true) == TvSession.SendResult.Sent
    }

    private fun onTvState(tv: TvSession.State) {
        if (!tv.connected) return reset()
        val now = tv.nowPlaying ?: return
        followTv(now.seriesPageUrl, now.preferredHeight, now.autoNext, active = now.isActive || now.state == PlaybackState.ENDED)
        val current = _state.value ?: return
        if (current.autoNext != now.autoNext) _state.update { it?.copy(autoNext = now.autoNext) }
        now.episodeRequest?.let { request ->
            if (request.id != handledRequestId) {
                handledRequestId = request.id
                scope.launch {
                    if (session.claimEpisode(request.id)) {
                        play(request.number) { session.control(PlayerCommand.EpisodeFailed(request.id)) }
                    }
                }
            }
        }
        // Prepare the next episode near the end of this one.
        val duration = now.durationMs ?: return
        val next = current.next ?: return
        val nearEnd = now.state == PlaybackState.PLAYING && duration - now.positionMs in 0..PREFETCH_BEFORE_END_MS
        if (nearEnd && prefetched?.number != next.number && prefetchJob?.isActive != true && current.loading == null) {
            prefetchJob = scope.launch {
                val video = loader.findVideo(current.address.withEpisode(next.number), FIND_TIMEOUT_MS) ?: return@launch
                prefetched = Prefetched(next.number, video, clock())
            }
        }
    }

    /**
     * What the TV plays decides: an episode this phone did not send (another phone, or this
     * phone before a restart) is adopted from its page; another video ends the series.
     */
    private fun followTv(pageUrl: String?, height: Int?, autoNext: Boolean, active: Boolean) {
        val current = _state.value
        if (current?.loading != null) return // our own send is on its way
        if (pageUrl == null) {
            if (active && current != null) reset()
            return
        }
        if (current != null && current.address.url == pageUrl) return
        val address = EpisodeAddress.parse(pageUrl) ?: return
        if (current != null && current.address.show == address.show) {
            // Same series, another episode (sent by another phone).
            _state.value = current.copy(address = address, current = address.number, failed = null)
            return
        }
        prefetched = null
        _state.value = State(address = address, show = shows[address.show], current = address.number, height = height, autoNext = autoNext)
        if (shows[address.show] == null) scope.launch {
            val show = showOf(address)
            _state.update { s -> if (s?.address?.show == address.show) s.copy(show = show) else s }
        }
    }

    private fun reset() {
        if (_state.value == null) return
        playJob?.cancel()
        prefetchJob?.cancel()
        prefetched = null
        _state.value = null
    }

    companion object {
        const val SHOW_TIMEOUT_MS = 6_000L
        const val FIND_TIMEOUT_MS = 45_000L
        const val PREFETCH_BEFORE_END_MS = 150_000L
        /** Video links of sites expire: an older prepared episode is looked up again. */
        const val PREFETCH_VALID_MS = 10 * 60_000L

        /** `Show · S5 E2 · Episode title` on the TV. */
        fun MediaStream.withSeries(series: SeriesInfo): MediaStream {
            val episodeTitle = series.currentEpisode?.title
            return copy(series = series, title = listOfNotNull(series.title, episodeTitle).joinToString(" · "))
        }
    }
}
