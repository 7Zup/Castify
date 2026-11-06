package app.castify.domain

import app.castify.domain.model.TvDevice
import app.castify.domain.port.TvDiscovery
import app.castify.domain.usecase.ObserveTvDevices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryTest {

    private class Fake(val list: MutableStateFlow<List<TvDevice>>) : TvDiscovery {
        override fun devices(): Flow<List<TvDevice>> = list
    }

    @Test
    fun `same TV seen by two methods is listed once, first method wins`() {
        val udp = listOf(TvDevice("MyCast", "10.0.0.42", 47800))
        val mdns = listOf(TvDevice("MyCast", "fe80::1", 47800), TvDevice("bedroom", "10.0.0.50", 47800))
        val merged = ObserveTvDevices.merge(listOf(udp, mdns))
        assertEquals(listOf("bedroom", "MyCast"), merged.map { it.name })
        assertEquals("10.0.0.42", merged.first { it.name == "MyCast" }.host)
    }

    @Test
    fun `a method that finds nothing does not hide the other`() = runTest {
        val silent = object : TvDiscovery {
            override fun devices(): Flow<List<TvDevice>> = flow { kotlinx.coroutines.awaitCancellation() }
        }
        val udp = Fake(MutableStateFlow(listOf(TvDevice("MyCast", "10.0.0.42", 47800))))
        val result = ObserveTvDevices(udp, silent)().first { it.isNotEmpty() }
        assertEquals("MyCast", result.single().name)
    }
}
