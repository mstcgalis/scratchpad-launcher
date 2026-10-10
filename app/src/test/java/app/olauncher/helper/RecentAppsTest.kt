package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAppsTest {
    private val events = listOf("a" to 1L, "b" to 5L, "a" to 9L, "c" to 3L, "d" to 7L, "e" to 2L)

    @Test
    fun newestFirstAndDistinct() =
        assertEquals(listOf("a", "d", "b", "c"), RecentApps.pick(events, emptySet(), 4) { it })

    @Test
    fun skipsExcludedAndUnlaunchable() =
        assertEquals(listOf("b", "c", "e"), RecentApps.pick(events, setOf("a"), 4) { it.takeIf { it != "d" } })

    @Test
    fun respectsLimit() =
        assertEquals(listOf("a"), RecentApps.pick(events, emptySet(), 1) { it })
}
