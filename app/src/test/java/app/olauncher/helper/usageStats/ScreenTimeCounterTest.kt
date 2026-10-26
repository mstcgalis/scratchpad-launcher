package app.olauncher.helper.usageStats

import org.junit.Assert.assertEquals
import org.junit.Test

// Cases from BetterLauncher's ScreenTimeRegression (6ba9d67)
class ScreenTimeCounterTest {
    private fun count(start: Long = 0, end: Long = 100, events: ScreenTimeCounter.() -> Unit): Long =
        ScreenTimeCounter(start, end, "launcher").apply(events).finish()

    @Test fun emptyHistoryCountsNothing() = assertEquals(0L, count { })

    @Test fun overlappingPackagesCountOnce() = assertEquals(30L, count {
        accept(10, 1, "a", "A")
        accept(20, 1, "b", "B") // missing pause for A
        accept(30, 2, "a", "A") // delayed pause must not stop B
        accept(40, 2, "b", "B")
    })

    @Test fun screenOffExcluded() = assertEquals(30L, count {
        accept(0, 1, "a", "A")
        accept(10, 1, "a", "A") // duplicate resume
        accept(20, 16, null, null)
        accept(80, 15, null, null)
        accept(90, 2, "a", "A")
    })

    @Test fun lockedExcluded() = assertEquals(40L, count {
        accept(0, 1, "a", "A")
        accept(20, 17, null, null)
        accept(80, 18, null, null)
    })

    @Test fun midnightCarryOverClipped() = assertEquals(20L, count(start = 50) {
        accept(10, 1, "a", "A")
        accept(70, 2, "a", "A")
        accept(80, 23, "a", "A")
    })

    @Test fun launcherAndRebootExcluded() = assertEquals(20L, count {
        accept(10, 2, "a", "A")
        accept(20, 1, "launcher", "Home")
        accept(40, 1, "a", "A")
        accept(60, 26, null, null)
        accept(80, 27, null, null)
    })

    @Test fun activityTransitionsCountOnce() = assertEquals(40L, count {
        accept(10, 1, "a", "A")
        accept(20, 1, "a", "B")
        accept(30, 23, "a", "A")
        accept(50, 2, "a", "B")
    })

    @Test fun previousDayAppOnSleepingPhoneIgnored() = assertEquals(0L, count(start = 50) {
        accept(10, 1, "a", "A")
        accept(20, 16, null, null)
    })

    @Test fun sleepAfterUseNotCounted() {
        val hour = 3_600_000L
        assertEquals(3 * hour, count(end = 10 * hour) {
            accept(0, 1, "a", "A")
            accept(hour, 1, "b", "B")
            accept(3 * hour, 16, null, null) // missing pause at screen-off
        })
    }
}
