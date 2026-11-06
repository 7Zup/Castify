package app.castify.domain.inspection

import app.castify.domain.model.MediaStream
import app.castify.domain.model.StreamDetails
import app.castify.domain.model.StreamType
import app.castify.domain.port.ManifestFetcher

/**
 * Reads a stream's manifest to learn its duration, live flag and qualities.
 * Progressive MP4/MOV files: duration and size read from the file header ([Mp4Probe]).
 * Never throws: an unreadable manifest/file gives empty details.
 */
class InspectStream(private val fetcher: ManifestFetcher) {

    private val mp4 = Mp4Probe(fetcher)

    suspend operator fun invoke(stream: MediaStream): StreamDetails = when (stream.type) {
        StreamType.HLS -> inspectHls(stream)
        StreamType.DASH -> inspectDash(stream)
        StreamType.PROGRESSIVE -> inspectFile(stream)
    }

    /** MP4/MOV only: their header gives duration and size. Other files (WebM…) → empty. */
    private suspend fun inspectFile(stream: MediaStream): StreamDetails {
        val path = stream.url.substringBefore('?').lowercase()
        if (MP4_EXTENSIONS.none { path.endsWith(it) }) return StreamDetails()
        val info = mp4.probe(stream.url, stream.headers) ?: return StreamDetails()
        return StreamDetails(
            durationMs = info.durationMs,
            qualities = info.height?.let { QualityLabel.qualities(listOf(info.width to it)) }.orEmpty(),
        )
    }

    private suspend fun inspectHls(stream: MediaStream): StreamDetails {
        val page = fetcher.fetch(stream.url, stream.headers).getOrNull() ?: return StreamDetails()
        return when (val playlist = HlsPlaylistParser.parse(page.body, page.finalUrl)) {
            null -> StreamDetails()
            is HlsPlaylistParser.Media -> playlist.toDetails()
            is HlsPlaylistParser.Master -> {
                // Duration lives in the media playlists: read the first variant.
                val media = playlist.variants.firstOrNull()?.let { variant ->
                    fetcher.fetch(variant.url, stream.headers).getOrNull()
                        ?.let { HlsPlaylistParser.parse(it.body, it.finalUrl) as? HlsPlaylistParser.Media }
                }
                StreamDetails(
                    durationMs = media?.takeUnless { it.isLive }?.durationMs,
                    isLive = media?.isLive ?: false,
                    qualities = QualityLabel.qualities(
                        playlist.variants.mapNotNull { v -> v.height?.let { v.width to it } },
                    ),
                    childUrls = playlist.childUrls,
                )
            }
        }
    }

    private suspend fun inspectDash(stream: MediaStream): StreamDetails {
        val page = fetcher.fetch(stream.url, stream.headers).getOrNull() ?: return StreamDetails()
        val manifest = DashManifestParser.parse(page.body) ?: return StreamDetails()
        return StreamDetails(
            durationMs = manifest.durationMs,
            isLive = manifest.isLive,
            qualities = QualityLabel.qualities(manifest.sizes),
        )
    }

    private fun HlsPlaylistParser.Media.toDetails() =
        StreamDetails(durationMs = if (isLive) null else durationMs, isLive = isLive)

    private companion object {
        val MP4_EXTENSIONS = listOf(".mp4", ".m4v", ".mov")
    }
}
