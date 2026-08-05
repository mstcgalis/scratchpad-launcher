package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatEditText
import app.olauncher.helper.CheckboxSpan

/**
 * Scratchpad [AppCompatEditText] that additionally detects taps landing on a rendered
 * [CheckboxSpan] glyph and toggles the underlying `[ ]`/`[x]` markdown text in place.
 * Any other touch falls through to normal cursor placement/selection.
 */
class MarkdownEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val editable = text
            val currentLayout = layout
            if (editable != null && currentLayout != null) {
                val offset = offsetForTouch(currentLayout, event.x, event.y)
                val span = editable.getSpans(offset, offset, CheckboxSpan::class.java)
                    .firstOrNull { editable.getSpanStart(it) <= offset && offset <= editable.getSpanEnd(it) }
                if (span != null) {
                    val toggleIndex = editable.getSpanStart(span) + 1
                    editable.replace(toggleIndex, toggleIndex + 1, if (span.checked) " " else "x")
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun offsetForTouch(currentLayout: android.text.Layout, touchX: Float, touchY: Float): Int {
        val x = (touchX - totalPaddingLeft + scrollX).toInt()
        val y = (touchY - totalPaddingTop + scrollY).toInt()
        val line = currentLayout.getLineForVertical(y)
        return currentLayout.getOffsetForHorizontal(line, x.toFloat())
    }
}
