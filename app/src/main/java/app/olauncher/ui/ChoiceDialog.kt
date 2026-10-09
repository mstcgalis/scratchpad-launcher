package app.olauncher.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Standard single-choice settings picker; [onPick] gets the chosen index (skipped if unchanged). */
fun Context.showChoices(title: CharSequence, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, i ->
            dialog.dismiss()
            if (i != checked) onPick(i)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** Pick one of [options] (label to value), pre-selecting [current]. */
fun <T> Context.showChoices(title: CharSequence, options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) =
    showChoices(title, options.map { it.first }, options.indexOfFirst { it.second == current }) { onPick(options[it].second) }
