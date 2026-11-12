package app.castify.domain.detection

import app.castify.domain.inspection.InspectStream
import app.castify.domain.inspection.QualityLabel
import app.castify.domain.model.DetectedVideo
import app.castify.domain.model.MediaStream
import app.castify.domain.model.ObservedRequest
import app.castify.domain.model.StreamDetails
import app.castify.domain.model.StreamType
import app.castify.domain.model.VideoElement
import app.castify.domain.subtitles.SubtitleClassifier
import app.castify.domain.subtitles.SubtitleSource
import kotlinx.coroutines.CoroutineScope
import app.castify.domain.series.EpisodeAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The videos detected on the current page, as listed to the user.
 *
 * Rules:
 * - likely ads are hidden;
 * - a master playlist and its sub-playlists are one video (sub-playlists are hidden);
 * - each new stream is inspected once (HLS/DASH manifest, MP4 header);
 * - progressive files take duration and size from their `<video>` element, else from the
 *   file header (players inside cross-origin frames expose no `<video>` to Castify);
 * - a video with no known duration that is not live is hidden (user rule);
 * - subtitle files of the page (requests and `<track>` elements) are attached to every video;
 * - thumbnail: poster of the matching `<video>`; HLS/DASH only may fall back to a streaming
 *   player's poster, then the page image. A progressive file never borrows another image.
 *
 * Thread-safe: the browser reports from background threads.
 */
class VideoCatalog(
    private val inspect: InspectStream,
    private val scope: CoroutineScope,
    private val classifier: StreamClassifier = StreamClassifier(),
) {
    private data class Page(
        val generation: Int = 0,
        val streams: List<MediaStream> = emptyList(),
        val details: Map<String, StreamDetails> = emptyMap(),
        val elements: Map<String, VideoElement> = emptyMap(),
        val posters: List<String> = emptyList(),
        val pageImage: String? = null,
        val subtitles: List<SubtitleSource> = emptyList(),
        /** Cleaned page title ([PageTitle]), shown on the TV. */
        val title: String? = null,
    )

    private val lock = Any()
    private var page = Page()
    private val _videos = MutableStateFlow<List<DetectedVideo>>(emptyList())
    val videos: StateFlow<List<DetectedVideo>> = _videos.asStateFlow()

    private val _episodeAddress = MutableStateFlow<EpisodeAddress?>(null)
    /**
     * The episode this page plays ([EpisodeAddress]): the page's own address, else the first
     * embedded player address seen (players in frames). Null = not a series episode.
     */
    val episodeAddress: StateFlow<EpisodeAddress?> = _episodeAddress.asStateFlow()

    /** The page's address (call right after [reset]). */
    fun onPageAddress(url: String) { _episodeAddress.value = EpisodeAddress.parse(url) }

    fun onRequest(request: ObservedRequest) {
        if (_episodeAddress.value == null) EpisodeAddress.parse(request.url)?.let { _episodeAddress.compareAndSet(null, it) }
        SubtitleClassifier.classify(request.url, request.mimeType)?.let { onSubtitle(it, fromTrackElement = false); return }
        val stream = classifier.classify(request) ?: return
        if (stream.likelyAd) return
        val (added, generation) = synchronized(lock) {
            val isNew = page.streams.none { it.url == stream.url }
            if (isNew) publish(page.copy(streams = page.streams + stream))
            isNew to page.generation
        }
        if (added) {
            scope.launch {
                val details = inspect(stream)
                mutate { current ->
                    if (current.generation != generation) current
                    else current.copy(details = current.details + (stream.url to details))
                }
            }
        }
    }

    fun onVideoElement(element: VideoElement) {
        mutate { current ->
            if (element.src != null) {
                current.copy(elements = current.elements + (element.src to element))
            } else {
                // No http source = a streaming player (MSE, blob:): its poster belongs to an
                // HLS/DASH stream, never to a progressive file.
                val poster = element.posterUrl?.takeIf { it !in current.posters }
                if (poster == null) current else current.copy(posters = current.posters + poster)
            }
        }
    }

    fun onPageImage(url: String) = mutate { it.copy(pageImage = url) }

    fun onPageTitle(raw: String) = mutate { it.copy(title = PageTitle.clean(raw)) }

    /**
     * A subtitle file of the page. A `<track>` element (language + label set by the site)
     * overrides what was guessed from the address; a guess never overrides a `<track>`.
     */
    fun onSubtitle(source: SubtitleSource, fromTrackElement: Boolean) = mutate { current ->
        val existing = current.subtitles.firstOrNull { it.url == source.url }
        val merged = when {
            existing == null -> current.subtitles + source
            fromTrackElement -> current.subtitles.map {
                if (it.url == source.url) source.copy(language = source.language ?: it.language) else it
            }
            else -> current.subtitles
        }
        current.copy(subtitles = merged)
    }

    /** Called when the user navigates to a new page. */
    fun reset() {
        _episodeAddress.value = null
        mutate { Page(generation = it.generation + 1) }
    }

    private fun mutate(change: (Page) -> Page) = synchronized(lock) { publish(change(page)) }

    /** Must hold [lock]. */
    private fun publish(updated: Page) {
        page = updated
        _videos.value = render(updated)
    }

    private fun render(p: Page): List<DetectedVideo> {
        val children = p.details.values.flatMap { it.childUrls }.toSet()
        return rank(p.streams.filter { it.url !in children }).mapNotNull { stream ->
            val element = p.elements[stream.url]
            val inspected = p.details[stream.url]
            val details = when {
                stream.type != StreamType.PROGRESSIVE -> inspected
                // File: the <video> element wins, the file header (MP4) fills the gaps.
                element?.durationMs != null || inspected != null -> StreamDetails(
                    durationMs = element?.durationMs ?: inspected?.durationMs,
                    qualities = element?.height?.let { QualityLabel.qualities(listOf(element.width to it)) }
                        ?: inspected?.qualities.orEmpty(),
                )
                else -> null // header still being read
            }
            // Known not to be a watchable video: no duration and not live (decoy files,
            // dead links, tiny UI clips). Still being inspected (null) = kept, shown as "…".
            if (details != null && details.durationMs == null && !details.isLive) return@mapNotNull null

            val thumbnail = when (stream.type) {
                StreamType.PROGRESSIVE -> element?.posterUrl
                else -> element?.posterUrl ?: p.posters.firstOrNull() ?: p.pageImage
            }
            DetectedVideo(
                stream = stream.copy(title = stream.title ?: p.title),
                thumbnailUrl = thumbnail,
                details = details,
                subtitles = latestPerLanguage(p.subtitles),
            )
        }
    }

    companion object {
        /**
         * When a page switches movies in place, files of the old and new movie coexist: for a
         * known language (+ label), only the most recently detected file is kept. Files of
         * unknown language are all kept (they may be different languages).
         */
        fun latestPerLanguage(subtitles: List<SubtitleSource>): List<SubtitleSource> {
            val known = subtitles.filter { it.language != null }
                .groupBy { it.language!!.lowercase().substringBefore('-') to it.label?.lowercase() }
                .map { (_, files) -> files.last() }
                .toSet()
            return subtitles.filter { it.language == null || it in known }
        }

        /** Manifests before files, then detection order. */
        fun rank(streams: List<MediaStream>): List<MediaStream> =
            streams.withIndex().sortedWith(
                compareBy<IndexedValue<MediaStream>> { if (it.value.type == StreamType.PROGRESSIVE) 1 else 0 }
                    .thenBy { it.index },
            ).map { it.value }
    }
}
