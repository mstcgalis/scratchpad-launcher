package app.olauncher.helper.usageStats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.concurrent.TimeUnit

/** Foreground time between [start] and [end], excluding this launcher, screen-off and locked time.
 * Replays the previous day too, so a session that began before midnight is carried over.
 * Ported from BetterLauncher 6ba9d67. */
fun getScreenTime(context: Context, start: Long, end: Long): Long {
    if (end <= start) return 0L
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val counter = ScreenTimeCounter(start, end, context.packageName)
    val events = usageStatsManager.queryEvents(start - TimeUnit.DAYS.toMillis(1), end)
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        counter.accept(event.timeStamp, event.eventType, event.packageName, event.className)
    }
    return counter.finish()
}

/** Counts a single foreground activity at a time, so overlapping or unclosed sessions count once.
 * Event types: 1 resumed, 2 paused, 3 end of day, 4 continue previous day, 15/16 screen on/off,
 * 17/18 keyguard shown/hidden, 23 stopped, 26/27 shutdown/startup. */
internal class ScreenTimeCounter(private val start: Long, private val end: Long, private val launcher: String) {
    private var foreground: Pair<String, String?>? = null
    private var interactive = true
    private var unlocked = true
    private var previous = start
    private var total = 0L

    fun accept(timestamp: Long, type: Int, packageName: String?, className: String?) {
        if (timestamp > end) return
        val time = timestamp.coerceAtLeast(start).coerceAtLeast(previous)
        if (interactive && unlocked && foreground != null && foreground?.first != launcher) total += time - previous
        previous = time
        when (type) {
            1, 4 -> if (packageName != null) foreground = packageName to className
            2, 23 -> if (foreground == (packageName to className)) foreground = null
            3 -> foreground = null
            15 -> interactive = true
            16 -> interactive = false
            17 -> unlocked = false
            18 -> unlocked = true
            26, 27 -> { foreground = null; interactive = false; unlocked = false }
        }
    }

    fun finish(): Long {
        accept(end, 0, null, null)
        return total
    }
}
