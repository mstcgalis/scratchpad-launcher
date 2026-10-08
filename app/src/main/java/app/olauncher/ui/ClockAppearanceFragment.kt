package app.olauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentClockAppearanceBinding
import app.olauncher.helper.ClockAppearance

/** Edits clock/date look; every change is written to [Prefs] immediately and the preview re-applied. */
class ClockAppearanceFragment : BaseFragment() {
    private var _binding: FragmentClockAppearanceBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private var loading = true

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

        binding.clockFont.bind(ClockAppearance.fonts, { prefs.clockFont }) { prefs.clockFont = it }
        binding.dateFont.bind(ClockAppearance.fonts, { prefs.dateFont }) { prefs.dateFont = it }
        binding.clockWeight.bind(ClockAppearance.weights, { prefs.clockWeight }) { prefs.clockWeight = it }
        binding.dateWeight.bind(ClockAppearance.weights, { prefs.dateWeight }) { prefs.dateWeight = it }
        binding.timeFormat.bind(ClockAppearance.hourFormats, { prefs.clockHourFormat }) { prefs.clockHourFormat = it }
        binding.dateFormat.bind(ClockAppearance.dateFormats.mapIndexed { i, f -> f.first to i }, { prefs.dateFormat }) { prefs.dateFormat = it }
        binding.clockSize.bind({ prefs.clockSizeOffset }) { prefs.clockSizeOffset = it }
        binding.dateSize.bind({ prefs.dateSizeOffset }) { prefs.dateSizeOffset = it }
        binding.clockSpacing.bind({ prefs.clockLetterSpacing }, ClockAppearance.MIN_SPACING, ClockAppearance.MAX_SPACING) { prefs.clockLetterSpacing = it }
        binding.dateSpacing.bind({ prefs.dateLetterSpacing }, ClockAppearance.MIN_SPACING, ClockAppearance.MAX_SPACING) { prefs.dateLetterSpacing = it }
        binding.lineGap.bind({ prefs.clockDateGap }, 0, ClockAppearance.MAX_GAP) { prefs.clockDateGap = it }

        binding.back.setOnClickListener { findNavController().navigateUp() }
        binding.reset.setOnClickListener {
            prefs.clockFont = "sans"; prefs.dateFont = "sans"
            prefs.clockWeight = 500; prefs.dateWeight = 500
            prefs.clockSizeOffset = 0; prefs.dateSizeOffset = 0
            prefs.clockHourFormat = 0; prefs.dateFormat = 0
            prefs.clockLetterSpacing = 0; prefs.dateLetterSpacing = 0; prefs.clockDateGap = 0
            loading = true
            listOf(binding.clockFont, binding.dateFont, binding.clockWeight, binding.dateWeight, binding.timeFormat, binding.dateFormat)
                .forEach { it.setSelection(0) }
            binding.clockWeight.setSelection(1); binding.dateWeight.setSelection(1)
            binding.clockSize.progress = -ClockAppearance.MIN_OFFSET; binding.dateSize.progress = -ClockAppearance.MIN_OFFSET
            binding.clockSpacing.progress = -ClockAppearance.MIN_SPACING; binding.dateSpacing.progress = -ClockAppearance.MIN_SPACING
            binding.lineGap.progress = 0
            loading = false
            refresh()
        }
        refresh()
        loading = false
    }

    private fun refresh() {
        ClockAppearance.apply(requireContext(), binding.previewClock, binding.previewDate, prefs)
        binding.previewDate.text = ClockAppearance.formatDate(prefs)
        binding.clockSizeValue.text = getString(R.string.size_offset_sp, prefs.clockSizeOffset)
        binding.dateSizeValue.text = getString(R.string.size_offset_sp, prefs.dateSizeOffset)
        binding.clockSpacingValue.text = getString(R.string.letter_spacing_em, prefs.clockLetterSpacing / 100f)
        binding.dateSpacingValue.text = getString(R.string.letter_spacing_em, prefs.dateLetterSpacing / 100f)
        binding.lineGapValue.text = getString(R.string.gap_dp, prefs.clockDateGap)
    }

    private fun <T> Spinner.bind(options: List<Pair<String, T>>, current: () -> T, save: (T) -> Unit) {
        adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options.map { it.first })
        setSelection(options.indexOfFirst { it.second == current() }.coerceAtLeast(0))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (loading) return
                save(options[position].second)
                refresh()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun SeekBar.bind(
        current: () -> Int, min: Int = ClockAppearance.MIN_OFFSET, maxValue: Int = ClockAppearance.MAX_OFFSET, save: (Int) -> Unit,
    ) {
        max = maxValue - min
        progress = current() - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (loading || !fromUser) return
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
