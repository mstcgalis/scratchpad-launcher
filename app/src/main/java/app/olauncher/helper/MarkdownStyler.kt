package app.olauncher.helper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Log
import androidx.core.graphics.ColorUtils

/** Marks a span as owned by [MarkdownStyler], so a restyle pass only clears its own spans. */
private interface MarkdownSpan

private class MarkdownStyleSpan(style: Int) : StyleSpan(style), MarkdownSpan
private class MarkdownSizeSpan(scale: Float) : RelativeSizeSpan(scale), MarkdownSpan
private class MarkdownColorSpan(color: Int) : ForegroundColorSpan(color), MarkdownSpan
private class MarkdownStrikeSpan : StrikethroughSpan(), MarkdownSpan
private class MarkdownUnderlineSpan : UnderlineSpan(), MarkdownSpan
private class MarkdownHighlightSpan(color: Int) : BackgroundColorSpan(color), MarkdownSpan
private class MarkdownCodeSpan : TypefaceSpan("monospace"), MarkdownSpan

/** Underlines a link's text; [MarkdownEditText][app.olauncher.ui.MarkdownEditText] opens [url] on tap. */
class LinkSpan(val url: String) : UnderlineSpan(), MarkdownSpan

private class HiddenMarkerSpan : ReplacementSpan(), MarkdownSpan {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
}

private class BulletMarkerSpan : ReplacementSpan(), MarkdownSpan {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = paint.measureText("• ").toInt()
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        canvas.drawText("• ", x, y.toFloat(), paint)
    }
}

/** Draws a tappable checkbox glyph over a `[ ]`/`[x]` marker range. */
class CheckboxSpan(val checked: Boolean, private val color: Int) : ReplacementSpan(), MarkdownSpan {
    private val bounds = Rect()

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
        paint.getTextBounds("H", 0, 1, bounds)
        val boxTop = y + bounds.exactCenterY() - boxSize / 2f
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
    private const val HIGHLIGHT_ALPHA = 0x40

    fun apply(editable: Editable, dimColor: Int, accentColor: Int, linkColor: Int, editing: Boolean) {
        try {
            editable.getSpans(0, editable.length, MarkdownSpan::class.java).forEach { editable.removeSpan(it) }

            for (match in MarkdownMatcher.findMatches(editable.toString())) {
                when (match) {
                    is MarkdownMatch.Header -> {
                        applyHeader(editable, match, dimColor)
                        val shade = ColorUtils.blendARGB(accentColor, dimColor, (match.level - 1) * 0.12f)
                        editable.setSpan(MarkdownColorSpan(shade), match.contentRange.start, match.contentRange.end, FLAG)
                        if (!editing) hide(editable, match.markerRange)
                    }
                    is MarkdownMatch.Inline -> {
                        val span = when (match.style) {
                            InlineStyle.BOLD -> MarkdownStyleSpan(Typeface.BOLD)
                            InlineStyle.ITALIC -> MarkdownStyleSpan(Typeface.ITALIC)
                            InlineStyle.UNDERLINE -> MarkdownUnderlineSpan()
                            InlineStyle.STRIKE -> MarkdownStrikeSpan()
                            InlineStyle.HIGHLIGHT -> MarkdownHighlightSpan(ColorUtils.setAlphaComponent(accentColor, HIGHLIGHT_ALPHA))
                            InlineStyle.CODE -> MarkdownCodeSpan()
                        }
                        applyEmphasis(editable, span, match.content, match.openMarker, match.closeMarker, dimColor)
                    }
                    is MarkdownMatch.Link -> {
                        applyEmphasis(editable, LinkSpan(match.url), match.content, match.openMarker, match.closeMarker, dimColor)
                        editable.setSpan(MarkdownColorSpan(linkColor), match.content.start, match.content.end, FLAG)
                    }
                    is MarkdownMatch.Escape -> dim(editable, match.markerRange, dimColor)
                    is MarkdownMatch.Bullet -> {
                        if (editing) dim(editable, match.markerRange, dimColor)
                        else editable.setSpan(BulletMarkerSpan(), match.markerRange.start, match.markerRange.end, FLAG)
                    }
                    is MarkdownMatch.Checkbox -> {
                        applyCheckbox(editable, match, dimColor, accentColor, editing)
                        if (!editing) hide(editable, TextRange(match.markerRange.start - 2, match.markerRange.start))
                    }
                }
                if (!editing) {
                    when (match) {
                        is MarkdownMatch.Inline -> { hide(editable, match.openMarker); hide(editable, match.closeMarker) }
                        is MarkdownMatch.Link -> { hide(editable, match.openMarker); hide(editable, match.closeMarker) }
                        is MarkdownMatch.Escape -> hide(editable, match.markerRange)
                        else -> Unit
                    }
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
        span: MarkdownSpan,
        content: TextRange,
        openMarker: TextRange,
        closeMarker: TextRange,
        dimColor: Int,
    ) {
        editable.setSpan(span, content.start, content.end, FLAG)
        dim(editable, openMarker, dimColor)
        dim(editable, closeMarker, dimColor)
    }

    private fun applyCheckbox(editable: Editable, match: MarkdownMatch.Checkbox, dimColor: Int, accentColor: Int, editing: Boolean) {
        if (editing) dim(editable, match.markerRange, dimColor)
        else editable.setSpan(CheckboxSpan(match.checked, accentColor), match.markerRange.start, match.markerRange.end, FLAG)
        if (match.checked) {
            editable.setSpan(MarkdownStrikeSpan(), match.contentRange.start, match.contentRange.end, FLAG)
            editable.setSpan(MarkdownColorSpan(dimColor), match.contentRange.start, match.contentRange.end, FLAG)
        }
    }

    // Bare URLs have empty markers, and zero-length EXCLUSIVE_EXCLUSIVE spans throw.
    private fun dim(editable: Editable, range: TextRange, dimColor: Int) {
        if (range.start == range.end) return
        editable.setSpan(MarkdownColorSpan(dimColor), range.start, range.end, FLAG)
    }

    private fun hide(editable: Editable, range: TextRange) {
        if (range.start == range.end) return
        editable.setSpan(HiddenMarkerSpan(), range.start, range.end, FLAG)
    }
}
