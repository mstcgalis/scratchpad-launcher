package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSlotTest {
    @Test fun everySlotRoundTripsAndAvoidsOtherFlags() {
        for (slot in 1..Constants.HOME_PAGE_SLOTS) assertEquals(slot, Constants.homeSlot(Constants.homeSlotFlag(slot)))
        listOf(Constants.FLAG_SET_SWIPE_LEFT_APP, Constants.FLAG_SET_CLOCK_APP, Constants.FLAG_SET_SCREEN_TIME_APP, Constants.FLAG_LAUNCH_APP)
            .forEach { assertNull(Constants.homeSlot(it)) }
    }
}
