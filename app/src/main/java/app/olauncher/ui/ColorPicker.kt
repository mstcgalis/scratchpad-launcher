package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R

/** Hue/saturation/brightness sliders plus a hex field; [onPick] gets an opaque colour, or [default] on reset. */
fun showColorPicker(
    context: Context,
    initial: Int,
    default: Int,
    title: Int = R.string.scratchpad_accent,
    previewText: Int = R.string.scratchpad_accent_preview,
    underline: Boolean = true,
    onPick: (Int) -> Unit,
) {
    val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    val dp = context.resources.displayMetrics.density
    var syncing = false

    val preview = TextView(context).apply {
        setText(previewText)
        if (underline) paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, (12 * dp).toInt())
    }
    val hex = EditText(context).apply {
        filters = arrayOf(InputFilter.LengthFilter(7))
        isSingleLine = true
        gravity = Gravity.CENTER
    }
    val bars = List(3) { SeekBar(context).apply { setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt()) } }
    val (hue, sat, value) = bars
    hue.max = 360; sat.max = 100; value.max = 100
    hue.progressDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) },
    ).apply { cornerRadius = 4 * dp; setSize(0, (8 * dp).toInt()) }

    fun color() = Color.HSVToColor(hsv)
    fun refresh(fromHex: Boolean = false) {
        syncing = true
        preview.setTextColor(color())
        hue.progress = hsv[0].toInt(); sat.progress = (hsv[1] * 100).toInt(); value.progress = (hsv[2] * 100).toInt()
        if (!fromHex) hex.setText(String.format("#%06X", color() and 0xFFFFFF))
        syncing = false
    }

    bars.forEachIndexed { i, bar ->
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                hsv[i] = if (i == 0) progress.toFloat() else progress / 100f
                refresh()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }
    hex.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            if (syncing) return
            val text = s.toString().removePrefix("#")
            if (text.length != 6) return
            val parsed = text.toIntOrNull(16) ?: return
            Color.colorToHSV(parsed or 0xFF000000.toInt(), hsv)
            refresh(fromHex = true)
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    })
    refresh()

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), 0)
        addView(preview)
        bars.forEach { addView(it) }
        addView(hex)
    }
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(content)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(R.string.accent_default) { _, _ -> onPick(default) }
        .setPositiveButton(android.R.string.ok) { _, _ -> onPick(color()) }
        .show()
}
