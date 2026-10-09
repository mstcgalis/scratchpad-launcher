package app.olauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentClockAppearanceBinding
import app.olauncher.helper.ClockAppearance

/** Edits clock/date look; every change is written to [Prefs] immediately and the preview re-applied. */
class ClockAppearanceFragment : BaseFragment() {
    private var _binding: FragmentClockAppearanceBinding? = null
    private val refreshers = mutableListOf<() -> Unit>()
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentClockAppearanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
        prefs = Prefs(requireContext())
        refreshers.clear()

        binding.clockFont.bind(R.string.font, ClockAppearance.fonts, { prefs.clockFont }) { prefs.clockFont = it }
        binding.dateFont.bind(R.string.font, ClockAppearance.fonts, { prefs.dateFont }) { prefs.dateFont = it }
        binding.clockWeight.bind(R.string.weight, ClockAppearance.weights, { prefs.clockWeight }) { prefs.clockWeight = it }
        binding.dateWeight.bind(R.string.weight, ClockAppearance.weights, { prefs.dateWeight }) { prefs.dateWeight = it }
        binding.timeFormat.bind(R.string.time_format, ClockAppearance.hourFormats, { prefs.clockHourFormat }) { prefs.clockHourFormat = it }
        binding.dateFormat.bind(R.string.date_format, ClockAppearance.dateFormats.mapIndexed { i, f -> f.first to i }, { prefs.dateFormat }) { prefs.dateFormat = it }
        binding.clockSize.bind({ prefs.clockSizeOffset }) { prefs.clockSizeOffset = it }
        binding.dateSize.bind({ prefs.dateSizeOffset }) { prefs.dateSizeOffset = it }
        binding.clockSpacing.bind({ prefs.clockLetterSpacing }, ClockAppearance.MIN_SPACING, ClockAppearance.MAX_SPACING) { prefs.clockLetterSpacing = it }
        binding.dateSpacing.bind({ prefs.dateLetterSpacing }, ClockAppearance.MIN_SPACING, ClockAppearance.MAX_SPACING) { prefs.dateLetterSpacing = it }
        binding.lineGap.bind({ prefs.clockDateGap }, 0, ClockAppearance.MAX_GAP) { prefs.clockDateGap = it }

        binding.reset.setOnClickListener {
            prefs.clockFont = "sans"; prefs.dateFont = "sans"
            prefs.clockWeight = 500; prefs.dateWeight = 500
            prefs.clockSizeOffset = 0; prefs.dateSizeOffset = 0
            prefs.clockHourFormat = 0; prefs.dateFormat = 0
            prefs.clockLetterSpacing = 0; prefs.dateLetterSpacing = 0; prefs.clockDateGap = 0
            refresh()
        }
        refresh()
    }

    /** Re-reads every value from [Prefs] into the preview, summaries and sliders. */
    private fun refresh() {
        ClockAppearance.apply(requireContext(), binding.previewClock, binding.previewDate, prefs)
        binding.previewDate.text = ClockAppearance.formatDate(prefs)
        binding.clockSizeValue.text = getString(R.string.size_offset_sp, prefs.clockSizeOffset)
        binding.dateSizeValue.text = getString(R.string.size_offset_sp, prefs.dateSizeOffset)
        binding.clockSpacingValue.text = getString(R.string.letter_spacing_em, prefs.clockLetterSpacing / 100f)
        binding.dateSpacingValue.text = getString(R.string.letter_spacing_em, prefs.dateLetterSpacing / 100f)
        binding.lineGapValue.text = getString(R.string.gap_dp, prefs.clockDateGap)
        refreshers.forEach { it() }
    }

    /** A settings row: [this] is its summary, the row opens a single-choice dialog. */
    private fun <T> TextView.bind(title: Int, options: List<Pair<String, T>>, current: () -> T, save: (T) -> Unit) {
        refreshers += { text = options.firstOrNull { it.second == current() }?.first }
        (parent as View).setOnClickListener {
            requireContext().showChoices(getString(title), options, current()) { save(it); refresh() }
        }
    }

    private fun SeekBar.bind(
        current: () -> Int, min: Int = ClockAppearance.MIN_OFFSET, maxValue: Int = ClockAppearance.MAX_OFFSET, save: (Int) -> Unit,
    ) {
        max = maxValue - min
        refreshers += { progress = current() - min }
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                save(value + min)
                refresh()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
