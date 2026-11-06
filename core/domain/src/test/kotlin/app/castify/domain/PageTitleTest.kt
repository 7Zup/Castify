package app.castify.domain

import app.castify.domain.detection.PageTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTitleTest {

    @Test
    fun `site name and watch-online noise are removed`() {
        assertEquals("Resident Evil (2026)", PageTitle.clean("Watch Resident Evil (2026) Online Free - StreamSite"))
        assertEquals("Resident Evil", PageTitle.clean("Resident Evil | StreamSite"))
        assertEquals("The Bear S03E02", PageTitle.clean("The Bear S03E02 Full Movie HD"))
        assertEquals("Le Comte de Monte-Cristo", PageTitle.clean("Regarder Le Comte de Monte-Cristo en streaming VF"))
    }

    @Test
    fun `empty or meaningless titles give no title`() {
        assertNull(PageTitle.clean(""))
        assertNull(PageTitle.clean("   "))
        assertNull(PageTitle.clean("- 123"))
        assertNull(PageTitle.clean(null))
    }
}
