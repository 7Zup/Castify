package app.castify.domain

import app.castify.domain.detection.StreamClassifier
import app.castify.domain.model.ObservedRequest
import app.castify.domain.model.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDetectionTest {

    private val classifier = StreamClassifier()
    private fun req(url: String, mime: String? = null) = ObservedRequest(url, "https://site.com/watch", mimeType = mime)

    @Test
    fun `detects HLS DASH and progressive by extension`() {
        assertEquals(StreamType.HLS, classifier.classify(req("https://cdn.com/a/master.m3u8?token=x"))?.type)
        assertEquals(StreamType.DASH, classifier.classify(req("https://cdn.com/manifest.mpd"))?.type)
        assertEquals(StreamType.PROGRESSIVE, classifier.classify(req("https://cdn.com/movie.MP4"))?.type)
    }

    @Test
    fun `detects by mime type when the url has no extension`() {
        assertEquals(StreamType.HLS, classifier.classify(req("https://cdn.com/play/123", "application/vnd.apple.mpegurl"))?.type)
        assertEquals(StreamType.PROGRESSIVE, classifier.classify(req("https://cdn.com/stream?id=1", "video/mp4"))?.type)
    }

    @Test
    fun `ignores segments, blobs and non media`() {
        assertNull(classifier.classify(req("https://cdn.com/seg-001.ts")))
        assertNull(classifier.classify(req("https://cdn.com/chunk.m4s")))
        assertNull(classifier.classify(req("https://cdn.com/seg", "video/mp2t")))
        assertNull(classifier.classify(req("blob:https://site.com/1234")))
        assertNull(classifier.classify(req("https://site.com/app.js")))
    }

    @Test
    fun `flags known ad hosts including subdomains`() {
        assertTrue(classifier.classify(req("https://pubads.g.doubleclick.net/ad.mp4"))!!.likelyAd)
        assertEquals(false, classifier.classify(req("https://notdoubleclick.net/v.mp4"))!!.likelyAd)
    }

    @Test
    fun `keeps request headers`() {
        val headers = mapOf("Referer" to "https://site.com/")
        val stream = classifier.classify(ObservedRequest("https://cdn.com/v.m3u8", "https://site.com", headers))
        assertEquals(headers, stream?.headers)
    }
}
