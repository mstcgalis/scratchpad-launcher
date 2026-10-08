package app.olauncher.helper

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.TextClock
import android.widget.TextView
import app.olauncher.data.Prefs
import app.olauncher.ui.FittingTextClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Per-element clock/date styling. System fonts only; sizes are sp offsets from the layout's own size. */
object ClockAppearance {
    val fonts = listOf("Sans serif" to "sans", "Serif" to "serif", "Monospace" to "mono", "Condensed" to "condensed")
    val weights = listOf("Regular" to 400, "Medium" to 500, "Bold" to 700)
    val hourFormats = listOf("System" to 0, "12-hour" to 1, "24-hour" to 2)
    val dateFormats = listOf(
        "Thu, 1 Oct" to "EEE, d MMM",
        "Thursday, 1 October" to "EEEE, d MMMM",
        "1 October 2026" to "d MMMM yyyy",
        "Oct 1" to "MMM d",
        "2026-10-01" to "yyyy-MM-dd",
    )
    const val MIN_OFFSET = -20
    const val MAX_OFFSET = 40

    fun apply(context: Context, clock: FittingTextClock, date: TextView, prefs: Prefs) {
        clock.typeface = typeface(prefs.clockFont, prefs.clockWeight)
        date.typeface = typeface(prefs.dateFont, prefs.dateWeight)
        clock.setPreferredSizePx(sizePx(context, clock, prefs.clockSizeOffset))
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx(context, date, prefs.dateSizeOffset))
        applyHourFormat(clock, prefs.clockHourFormat)
    }

    fun formatDate(prefs: Prefs, now: Date = Date()): String =
        SimpleDateFormat(dateFormats[prefs.dateFormat.coerceIn(dateFormats.indices)].second, Locale.getDefault())
            .format(now).replace(".,", ",")

    private fun applyHourFormat(clock: TextClock, mode: Int) {
        val (f12, f24) = when (mode) {
            1 -> "h:mm" to "h:mm"
            2 -> "HH:mm" to "HH:mm"
            else -> "h:mm" to "HH:mm"
        }
        clock.format12Hour = f12
        clock.format24Hour = f24
    }

    /** The view's size as inflated (first call) plus the user's offset; remembered so reapplying doesn't compound. */
    private fun sizePx(context: Context, view: TextView, offsetSp: Int): Float {
        val base = (view.getTag(view.id) as? Float) ?: view.textSize.also { view.setTag(view.id, it) }
        val offset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, offsetSp.toFloat(), context.resources.displayMetrics)
        return base + offset
    }

    private fun typeface(font: String, weight: Int): Typeface {
        val family = when (font) {
            "serif" -> "serif"
            "mono" -> "monospace"
            "condensed" -> "sans-serif-condensed"
            else -> "sans-serif"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.create(family, Typeface.NORMAL), weight, false)
        else Typeface.create(if (weight == 500 && font == "sans") "sans-serif-medium" else family, if (weight == 700) Typeface.BOLD else Typeface.NORMAL)
    }
}
