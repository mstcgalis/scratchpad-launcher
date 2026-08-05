package app.olauncher.helper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.Log

/** Marks a span as owned by [MarkdownStyler], so a restyle pass only clears its own spans. */
private interface MarkdownSpan

private class MarkdownStyleSpan(style: Int) : StyleSpan(style), MarkdownSpan
private class MarkdownSizeSpan(scale: Float) : RelativeSizeSpan(scale), MarkdownSpan
private class MarkdownColorSpan(color: Int) : ForegroundColorSpan(color), MarkdownSpan
private class MarkdownStrikeSpan : StrikethroughSpan(), MarkdownSpan

/** Draws a tappable checkbox glyph over a `[ ]`/`[x]` marker range. */
class CheckboxSpan(val checked: Boolean, private val color: Int) : ReplacementSpan(), MarkdownSpan {

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        return (paint.textSize * 1.2f).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val boxSize = paint.textSize * 0.75f
        val boxLeft = x + paint.textSize * 0.1f
        val boxTop = y + paint.fontMetrics.ascent + (paint.textSize - boxSize) / 2f
        val rect = RectF(boxLeft, boxTop, boxLeft + boxSize, boxTop + boxSize)
        val cornerRadius = boxSize * 0.2f

        val boxPaint = Paint(paint).apply {
            isAntiAlias = true
            style = if (checked) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = boxSize * 0.08f
            color = this@CheckboxSpan.color
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)

        if (checked) {
            val checkPaint = Paint(paint).apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = boxSize * 0.12f
                strokeCap = Paint.Cap.ROUND
                color = Color.WHITE
            }
            val path = Path().apply {
                moveTo(rect.left + boxSize * 0.22f, rect.top + boxSize * 0.55f)
                lineTo(rect.left + boxSize * 0.42f, rect.top + boxSize * 0.75f)
                lineTo(rect.left + boxSize * 0.8f, rect.top + boxSize * 0.28f)
            }
            canvas.drawPath(path, checkPaint)
        }
    }
}

/**
 * Applies live markdown styling to a scratchpad [Editable] based on [MarkdownMatcher] results.
 * Purely cosmetic decoration: never touches the underlying text, so failure here can't corrupt
 * what gets saved to prefs. Any exception is swallowed - a bug in styling must never crash the
 * launcher home screen.
 */
object MarkdownStyler {

    private const val TAG = "MarkdownStyler"
    private const val FLAG = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    private const val MARKER_DIM_SCALE = 0.7f

    fun apply(editable: Editable, dimColor: Int, accentColor: Int) {
        try {
            editable.getSpans(0, editable.length, MarkdownSpan::class.java).forEach { editable.removeSpan(it) }

            for (match in MarkdownMatcher.findMatches(editable.toString())) {
                when (match) {
                    is MarkdownMatch.Header -> applyHeader(editable, match, dimColor)
                    is MarkdownMatch.Bold -> applyEmphasis(editable, Typeface.BOLD, match.content, match.openMarker, match.closeMarker, dimColor)
                    is MarkdownMatch.Italic -> applyEmphasis(editable, Typeface.ITALIC, match.content, match.openMarker, match.closeMarker, dimColor)
                    is MarkdownMatch.Bullet -> dim(editable, match.markerRange, dimColor)
                    is MarkdownMatch.Checkbox -> applyCheckbox(editable, match, dimColor, accentColor)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to style scratchpad markdown", e)
        }
    }

    private fun applyHeader(editable: Editable, match: MarkdownMatch.Header, dimColor: Int) {
        val scale = 1.6f - match.level * 0.09f
        editable.setSpan(MarkdownSizeSpan(scale), match.contentRange.start, match.contentRange.end, FLAG)
        editable.setSpan(MarkdownStyleSpan(Typeface.BOLD), match.contentRange.start, match.contentRange.end, FLAG)
        editable.setSpan(MarkdownSizeSpan(MARKER_DIM_SCALE), match.markerRange.start, match.markerRange.end, FLAG)
        editable.setSpan(MarkdownColorSpan(dimColor), match.markerRange.start, match.markerRange.end, FLAG)
    }

    private fun applyEmphasis(
        editable: Editable,
        style: Int,
        content: TextRange,
        openMarker: TextRange,
        closeMarker: TextRange,
        dimColor: Int,
    ) {
        editable.setSpan(MarkdownStyleSpan(style), content.start, content.end, FLAG)
        editable.setSpan(MarkdownColorSpan(dimColor), openMarker.start, openMarker.end, FLAG)
        editable.setSpan(MarkdownColorSpan(dimColor), closeMarker.start, closeMarker.end, FLAG)
    }

    private fun applyCheckbox(editable: Editable, match: MarkdownMatch.Checkbox, dimColor: Int, accentColor: Int) {
        editable.setSpan(CheckboxSpan(match.checked, accentColor), match.markerRange.start, match.markerRange.end, FLAG)
        if (match.checked) {
            editable.setSpan(MarkdownStrikeSpan(), match.contentRange.start, match.contentRange.end, FLAG)
            editable.setSpan(MarkdownColorSpan(dimColor), match.contentRange.start, match.contentRange.end, FLAG)
        }
    }

    private fun dim(editable: Editable, range: TextRange, dimColor: Int) {
        editable.setSpan(MarkdownColorSpan(dimColor), range.start, range.end, FLAG)
    }
}
