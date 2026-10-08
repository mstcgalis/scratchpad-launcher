package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat

class ClockAppearanceTest {
    @Test
    fun `every date pattern is valid and the default matches the old hardcoded one`() {
        ClockAppearance.dateFormats.forEach { SimpleDateFormat(it.second) }
        assertEquals("EEE, d MMM", ClockAppearance.dateFormats.first().second)
    }

    @Test
    fun `size offsets bracket zero and weights include medium default`() {
        assertTrue(ClockAppearance.MIN_OFFSET < 0 && ClockAppearance.MAX_OFFSET > 0)
        assertTrue(500 in ClockAppearance.weights.map { it.second })
    }
}
