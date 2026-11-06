package app.castify.domain

import app.castify.domain.model.CastDevice
import app.castify.domain.model.DeviceKind
import app.castify.domain.model.TvDevice
import app.castify.domain.model.TvStatus
import app.castify.domain.port.CastDeviceScanner
import app.castify.domain.port.TvDiscovery
import app.castify.domain.usecase.ObserveNearbyTvs
import app.castify.domain.usecase.ObserveTvDevices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyTvsTest {

    private val myCast = TvDevice("MyCast", "10.0.0.42", 47800)

    @Test
    fun `a Cast device running Castify is listed once, ready`() {
        val list = ObserveNearbyTvs.merge(listOf(myCast), listOf(CastDevice("MyCast", "10.0.0.42", hasVideo = true)))
        assertEquals(1, list.size)
        assertEquals(TvStatus.READY, list.single().status)
        assertEquals(myCast, list.single().castify)
    }

    @Test
    fun `matching falls back to the name when the addresses differ`() {
        val list = ObserveNearbyTvs.merge(listOf(myCast), listOf(CastDevice("mycast", "fe80::1", hasVideo = true)))
        assertEquals(listOf(TvStatus.READY), list.map { it.status })
    }

    @Test
    fun `a Cast device without Castify is listed as app not launched`() {
        val list = ObserveNearbyTvs.merge(emptyList(), listOf(CastDevice("Living room TV", "10.0.0.60", hasVideo = true)))
        assertEquals(TvStatus.APP_NOT_LAUNCHED, list.single().status)
        assertNull(list.single().castify)
        assertEquals(DeviceKind.TV, list.single().kind)
    }

    @Test
    fun `ready devices come first, then the others, each by name`() {
        val list = ObserveNearbyTvs.merge(
            listOf(TvDevice("zeta", "10.0.0.70", 47800), myCast),
            listOf(
                CastDevice("Bedroom", "10.0.0.50", hasVideo = true),
                CastDevice("MyCast", "10.0.0.42", hasVideo = true),
                CastDevice("Attic", "10.0.0.51", hasVideo = true),
            ),
        )
        assertEquals(listOf("MyCast", "zeta", "Attic", "Bedroom"), list.map { it.name })
    }

    @Test
    fun `audio-only devices are speakers, groups are hidden`() {
        val list = ObserveNearbyTvs.merge(
            emptyList(),
            listOf(
                CastDevice("Kitchen speaker", "10.0.0.80", hasVideo = false),
                CastDevice("Whole house", "10.0.0.80", hasVideo = false, isGroup = true),
            ),
        )
        assertEquals(listOf("Kitchen speaker"), list.map { it.name })
        assertEquals(DeviceKind.SPEAKER, list.single().kind)
    }

    @Test
    fun `a failing Cast scan does not hide the Castify TVs`() = runTest {
        val tvs = object : TvDiscovery { override fun devices(): Flow<List<TvDevice>> = flowOf(listOf(myCast)) }
        val broken = object : CastDeviceScanner {
            override fun devices(): Flow<List<CastDevice>> = flow { throw IllegalStateException("NSD down") }
        }
        val list = ObserveNearbyTvs(ObserveTvDevices(tvs), broken)().first { it.isNotEmpty() }
        assertEquals(listOf("MyCast"), list.map { it.name })
    }
}
