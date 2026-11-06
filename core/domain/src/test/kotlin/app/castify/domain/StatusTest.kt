package app.castify.domain

import app.castify.domain.model.PlaybackError
import app.castify.domain.status.PageError
import app.castify.domain.status.PlaybackFailure
import app.castify.domain.status.TvPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusTest {

    @Test
    fun `TV presence follows the network and the last phone call`() {
        assertEquals(TvPresence.OFFLINE, TvPresence.of(online = false, lastPhoneCallAtMs = 9_000, nowMs = 10_000))
        assertEquals(TvPresence.STANDBY, TvPresence.of(online = true, lastPhoneCallAtMs = null, nowMs = 10_000))
        assertEquals(TvPresence.CONNECTED, TvPresence.of(online = true, lastPhoneCallAtMs = 5_000, nowMs = 10_000))
        assertEquals(TvPresence.STANDBY, TvPresence.of(online = true, lastPhoneCallAtMs = 3_000, nowMs = 10_000))
    }

    @Test
    fun `network errors are a lost connection, HTTP and format errors a refused video`() {
        assertEquals(PlaybackFailure.CONNECTION_LOST, PlaybackFailure.of(PlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "x")))
        assertEquals(PlaybackFailure.CONNECTION_LOST, PlaybackFailure.of(PlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", "x")))
        assertEquals(PlaybackFailure.REFUSED, PlaybackFailure.of(PlaybackError("ERROR_CODE_IO_BAD_HTTP_STATUS", "x", httpStatus = 503)))
        assertEquals(PlaybackFailure.REFUSED, PlaybackFailure.of(PlaybackError("ERROR_CODE_DECODING_FAILED", "x")))
        assertEquals(PlaybackFailure.REFUSED, PlaybackFailure.of(null))
    }

    @Test
    fun `page errors depend on the phone being online`() {
        assertEquals(PageError.OFFLINE, PageError.of(connectionFailure = true, online = false))
        assertEquals(PageError.UNREACHABLE, PageError.of(connectionFailure = true, online = true))
        assertNull(PageError.of(connectionFailure = false, online = false))
    }
}
