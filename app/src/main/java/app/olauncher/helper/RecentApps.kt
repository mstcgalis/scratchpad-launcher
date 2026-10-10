package app.olauncher.helper

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process

object RecentApps {
    private const val WINDOW_MILLIS = 24 * 60 * 60 * 1000L

    /** First [n] distinct packages, newest first, that aren't in [exclude] and [resolve] to something launchable. */
    fun <T : Any> pick(events: List<Pair<String, Long>>, exclude: Set<String>, n: Int, resolve: (String) -> T?): List<T> =
        events.sortedByDescending { it.second }.asSequence()
            .map { it.first }.distinct().filter { it !in exclude }
            .mapNotNull(resolve).take(n).toList()

    /** Apps the user brought to the foreground in the last day; needs usage access, empty without it. */
    fun query(context: Context, exclude: Set<String>, n: Int): List<LauncherActivityInfo> {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - WINDOW_MILLIS, end) ?: return emptyList()
        val resumed = ArrayList<Pair<String, Long>>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION") // ACTIVITY_RESUMED (API 29) has the same value
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) resumed += event.packageName to event.timeStamp
        }
        return pick(resumed, exclude + context.packageName, n) {
            runCatching { launcherApps.getActivityList(it, Process.myUserHandle()).firstOrNull() }.getOrNull()
        }
    }
}
