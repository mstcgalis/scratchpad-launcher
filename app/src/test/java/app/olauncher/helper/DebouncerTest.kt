package app.olauncher.helper

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DebouncerTest {

    @Test
    fun `submit runs action once after the delay`() = runTest {
        val debouncer = Debouncer(this, 300L)
        var callCount = 0

        debouncer.submit { callCount++ }
        assertEquals(0, callCount)

        advanceTimeBy(301L)
        assertEquals(1, callCount)
    }

    @Test
    fun `rapid successive submits only run the last action`() = runTest {
        val debouncer = Debouncer(this, 300L)
        val results = mutableListOf<Int>()

        debouncer.submit { results.add(1) }
        advanceTimeBy(100L)
        debouncer.submit { results.add(2) }
        advanceTimeBy(100L)
        debouncer.submit { results.add(3) }
        advanceTimeBy(301L)

        assertEquals(listOf(3), results)
    }

    @Test
    fun `cancel prevents a pending action from running`() = runTest {
        val debouncer = Debouncer(this, 300L)
        var callCount = 0

        debouncer.submit { callCount++ }
        debouncer.cancel()
        advanceTimeBy(301L)

        assertEquals(0, callCount)
    }
}
