package app.olauncher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatEditText
import app.olauncher.helper.CheckboxSpan
import app.olauncher.helper.LinkSpan

/**
 * Scratchpad [AppCompatEditText] that additionally detects taps landing on a rendered
 * [CheckboxSpan] glyph and toggles the underlying `[ ]`/`[x]` markdown text in place.
 * While not editing, a tap on a [LinkSpan] opens the link instead of focusing.
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
    /** The touch went down on a checkbox or link: its long-press must not open settings (or select text). */
    private var downOnTapTarget = false
    /** The touch moved past the slop while not editing: it's a scroll, not a tap. */
    private var dragged = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
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
                cancelTouch(event)
            }
            if (pinching) return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnTapTarget = checkboxAt(event) != null || linkAt(event) != null
            dragged = false
            downX = event.x
            downY = event.y
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !isFocused && !dragged &&
            Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop
        ) {
            // Scrolling the note must not focus it on release (cursor, raw markers, jump to the cursor):
            // cancel the press so UP doesn't take focus. Later MOVEs still scroll the text.
            dragged = true
            cancelTouch(event)
        }
        if (event.action == MotionEvent.ACTION_UP && !dragged) {
            checkboxAt(event)?.let { span ->
                val editable = text!!
                val toggleIndex = editable.getSpanStart(span) + 1
                editable.replace(toggleIndex, toggleIndex + 1, if (span.checked) " " else "x")
                // super never sees this UP: cancel the long-press it scheduled on DOWN (opens settings) and its
                // pressed state (a pressed EditText draws its cursor even unfocused).
                cancelTouch(event)
                return true
            }
            linkAt(event)?.let {
                openLink(it.url)
                cancelTouch(event)
                return true
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

    private fun cancelTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    // A slow tap can outlast the long-press timeout before UP arrives; never treat it as a long-press.
    override fun performLongClick(): Boolean = downOnTapTarget || super.performLongClick()

    // By drawn position, not text offset: the hidden zero-width "- " before the box would swallow taps on its left half.
    // Half a box of slack on each side for fingers.
    private fun checkboxAt(event: MotionEvent): CheckboxSpan? {
        val editable = text ?: return null
        val currentLayout = layout ?: return null
        val x = event.x - totalPaddingLeft + scrollX
        val line = currentLayout.getLineForVertical((event.y - totalPaddingTop + scrollY).toInt())
        return editable.getSpans(currentLayout.getLineStart(line), currentLayout.getLineEnd(line), CheckboxSpan::class.java).firstOrNull {
            val start = currentLayout.getPrimaryHorizontal(editable.getSpanStart(it))
            val end = currentLayout.getPrimaryHorizontal(editable.getSpanEnd(it))
            val slack = (end - start) / 2
            x in start - slack..end + slack
        }
    }

    private fun linkAt(event: MotionEvent): LinkSpan? {
        if (isFocused) return null
        val editable = text ?: return null
        val offset = layout?.let { offsetForTouch(it, event.x, event.y) } ?: return null
        return editable.getSpans(offset, offset, LinkSpan::class.java)
            .firstOrNull { editable.getSpanStart(it) <= offset && offset < editable.getSpanEnd(it) }
    }

    private fun openLink(url: String) {
        val uri = Uri.parse(url).let { if (it.scheme == null) Uri.parse("https://$url") else it }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun offsetForTouch(currentLayout: android.text.Layout, touchX: Float, touchY: Float): Int {
        val x = (touchX - totalPaddingLeft + scrollX).toInt()
        val y = (touchY - totalPaddingTop + scrollY).toInt()
        val line = currentLayout.getLineForVertical(y)
        return currentLayout.getOffsetForHorizontal(line, x.toFloat())
    }
}
