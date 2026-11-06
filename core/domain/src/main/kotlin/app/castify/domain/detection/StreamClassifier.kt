package app.castify.domain.detection

import app.castify.domain.model.MediaStream
import app.castify.domain.model.ObservedRequest
import app.castify.domain.model.StreamType

/**
 * Decides whether an observed request is a playable stream entry point.
 *
 * Only entry points are kept: HLS/DASH manifests and progressive files. Media segments
 * (.ts, .m4s, .aac…) are dropped, since the player fetches them from the manifest.
 */
class StreamClassifier(private val adHosts: Set<String> = DEFAULT_AD_HOSTS) {

    fun classify(request: ObservedRequest): MediaStream? {
        val url = request.url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val path = pathOf(url).lowercase()
        val mime = request.mimeType?.lowercase()?.substringBefore(';')?.trim()

        if (SEGMENT_EXTENSIONS.any { path.endsWith(it) }) return null

        val type = when {
            path.endsWith(".m3u8") || mime in HLS_MIMES -> StreamType.HLS
            path.endsWith(".mpd") || mime == "application/dash+xml" -> StreamType.DASH
            PROGRESSIVE_EXTENSIONS.any { path.endsWith(it) } -> StreamType.PROGRESSIVE
            mime != null && mime.startsWith("video/") && mime !in SEGMENT_MIMES -> StreamType.PROGRESSIVE
            else -> return null
        }

        return MediaStream(
            url = url,
            type = type,
            pageUrl = request.pageUrl,
            headers = request.headers,
            likelyAd = isAdHost(hostOf(url)),
        )
    }

    private fun isAdHost(host: String): Boolean =
        adHosts.any { host == it || host.endsWith(".$it") }

    companion object {
        private val SEGMENT_EXTENSIONS = listOf(".ts", ".m4s", ".aac", ".m4a", ".vtt", ".key", ".jpg", ".png")
        private val PROGRESSIVE_EXTENSIONS = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v")
        private val HLS_MIMES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl")
        private val SEGMENT_MIMES = setOf("video/mp2t", "video/iso.segment")

        val DEFAULT_AD_HOSTS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "imasdk.googleapis.com", "adnxs.com", "adsrvr.org", "advertising.com",
            "spotxchange.com", "springserve.com", "teads.tv", "smartadserver.com",
            "exoclick.com", "popads.net", "propellerads.com", "juicyads.com",
        )

        internal fun pathOf(url: String): String =
            url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')

        internal fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/').substringBefore(':').substringAfter('@').lowercase()
    }
}
