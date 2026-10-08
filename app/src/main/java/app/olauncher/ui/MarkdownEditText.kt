package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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

    /** Pinch-to-zoom callback: cumulative scale since the pinch began, and whether the pinch ended. */
    var onPinch: ((scale: Float, done: Boolean) -> Unit)? = null
    private var pinch = 1f
    private var pinching = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinch = 1f; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinch *= detector.scaleFactor
            onPinch?.invoke(pinch, false)
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { onPinch?.invoke(pinch, true) }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onPinch != null) {
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
            if (!pinching && event.pointerCount > 1) {
                // Second finger: cancel whatever the first one started (cursor, selection, long-press).
                pinching = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
            if (pinching) return true
        }
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
                    // super never sees this UP, so the long-press scheduled on DOWN would fire (opens settings).
                    cancelLongPress()
                    return true
                }
            }
        }
        if (event.action == MotionEvent.ACTION_UP && !isFocused) {
            // Focusing reveals the hidden markdown markers, which reflows the text before super places the
            // cursor; place it where the tap landed in the layout the user actually saw.
            val offset = layout?.let { offsetForTouch(it, event.x, event.y) }
            val handled = super.onTouchEvent(event)
            if (offset != null && isFocused && selectionStart == selectionEnd) setSelection(offset.coerceIn(0, length()))
            return handled
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
