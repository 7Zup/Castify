package app.castify.domain

import app.castify.domain.playback.DataRules
import org.junit.Assert.assertEquals
import org.junit.Test

class DataRulesTest {

    @Test
    fun `rate cap is 3x the quality, at least 15 Mbps`() {
        assertEquals(18_000_000L, DataRules.maxRateBps(6_000_000))
        assertEquals(15_000_000L, DataRules.maxRateBps(2_500_000))
        assertEquals(15_000_000L, DataRules.maxRateBps(null))
        assertEquals(15_000_000L, DataRules.maxRateBps(-1))
    }
}
