package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.content.res.Resources
import android.os.BatteryManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.core.view.setPadding
import androidx.core.view.doOnLayout
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.RECENTS_LEFT
import app.olauncher.data.RECENTS_OFF
import app.olauncher.helper.RecentApps
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.ClockAppearance
import app.olauncher.helper.applyAppLabelStyle
import app.olauncher.helper.MarkdownAction
import app.olauncher.helper.MarkdownFormatter
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.ScratchpadSync
import app.olauncher.helper.Debouncer
import app.olauncher.helper.MarkdownStyler
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.openSearch
import app.olauncher.helper.setLeadingIcon
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var scratchpadDebouncer: Debouncer
    private val widgetHost by lazy { AppWidgetHost(requireContext().applicationContext, Constants.WIDGET_HOST_ID) }
    private var syncPollJob: Job? = null
    private var recentApps: List<LauncherActivityInfo> = emptyList()
    private val recentsOn get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && prefs.recentAppsColumn != RECENTS_OFF
    private val slots get() = with(binding) { listOf(homeApp1, homeApp2, homeApp3, homeApp4, homeApp5, homeApp6, homeApp7, homeApp8) }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        scratchpadDebouncer = Debouncer(viewLifecycleOwner.lifecycleScope, 300L)
        applyLandscape()
        binding.homeAppsLayout.updatePadding(bottom = prefs.homeBottomMargin.dpToPx())
        initScratchpad()
        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        applyHomeTextColor()
        populateWidget()
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        widgetHost.stopListening()
    }

    private fun populateWidget() {
        val context = requireContext()
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(prefs.homeWidgetId)
        binding.widgetFrame.isVisible = info != null
        if (info == null) return
        val widget = widgetHost.createView(context.applicationContext, prefs.homeWidgetId, info)
        // Give it the height of the home-screen rows it asks for, not its bare minimum (which looks squashed).
        // ponytail: fixed 100dp row, launcher cells vary ~90-120dp; measure a real grid if widgets look off.
        val rows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) info.targetCellHeight
        else (info.minHeight / resources.displayMetrics.density + 30).toInt() / 70
        val height = maxOf(info.minHeight, (rows.coerceAtLeast(1) * 100).dpToPx())
        binding.widgetFrame.addView(widget, ViewGroup.LayoutParams.MATCH_PARENT, height)
        // Widgets lay out for the size the host reports (dp); unreported, they shrink to their minimum width.
        widget.doOnLayout {
            val density = resources.displayMetrics.density
            val w = (it.width / density).toInt()
            val h = (it.height / density).toInt()
            @Suppress("DEPRECATION")
            widget.updateAppWidgetSize(null, w, h, w, h)
        }
    }

    /** Only tablets rotate (phones are locked to portrait): note on the left, clock and apps bottom right. */
    private fun applyLandscape() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        binding.homeColumns.orientation = LinearLayout.HORIZONTAL
        binding.scratchpadFrame.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        binding.homeControls.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        (binding.homeAppsLayout.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.BOTTOM
    }

    /** Custom text colour over every home label except the format toolbar, which follows the keyboard. */
    private fun applyHomeTextColor() {
        val color = prefs.homeTextColor.takeIf { it != 0 } ?: return
        fun walk(view: View) {
            if (view === binding.formatToolbar || view === binding.widgetFrame) return
            if (view is TextView) {
                view.setTextColor(color)
                view.setHintTextColor(ColorUtils.setAlphaComponent(color, 0x80))
            }
            if (view is ViewGroup) view.children.forEach(::walk)
        }
        walk(binding.root)
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen(false)
        viewModel.isOlauncherDefault()
        ScratchpadSync.readIfChanged(requireContext(), prefs)?.let {
            prefs.scratchpadText = it
            binding.scratchpad.setText(it)
        }
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()

        // SAF tree URIs (Syncthing's folder) don't support change notifications, so poll while visible.
        syncPollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(3000)
                if (binding.scratchpad.isFocused) continue
                ScratchpadSync.readIfChanged(requireContext(), prefs)?.let {
                    prefs.scratchpadText = it
                    binding.scratchpad.setText(it)
                }
            }
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    if (recentsOn && appLocation > 4) launchRecentApp(appLocation - 5)
                    else homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        // Recent slots aren't pinnable.
        if (recentsOn && (view.tag?.toString()?.toIntOrNull() ?: 0) > 4) return true
        when (view.id) {
            R.id.homeApp1, R.id.homeApp2, R.id.homeApp3, R.id.homeApp4, R.id.homeApp5, R.id.homeApp6, R.id.homeApp7, R.id.homeApp8 -> {
                val slot = view.tag.toString().toInt()
                showAppList(slot, prefs.getAppName(slot).isNotEmpty(), true)
            }
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        slots.forEach { it.setOnTouchListener(getViewSwipeTouchListener(context, it)) }
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)

        // These fire only on d-pad/keyboard events; touch is consumed by ViewSwipeTouchListener
        slots.forEach {
            it.setOnClickListener(this)
            it.setOnLongClickListener(this)
        }
    }

    private fun initScratchpad() {
        applyScratchpadTypography(prefs.scratchpadTextScale)
        binding.scratchpad.onPinch = { scale, done ->
            val target = (prefs.scratchpadTextScale * scale).coerceIn(0.5f, 5.0f)
            if (done) {
                prefs.scratchpadTextScale = Math.round(target * 10f) / 10f
                applyScratchpadTypography(prefs.scratchpadTextScale)
            } else applyScratchpadTypography(target)
        }
        binding.scratchpad.setText(prefs.scratchpadText)
        binding.scratchpad.text?.let { styleScratchpadMarkdown(it) }
        var typedNewline = -1
        binding.scratchpad.addTextChangedListener(
            onTextChanged = { s, start, before, count ->
                typedNewline = if (count == 1 && before == 0 && s?.getOrNull(start) == '\n') start else -1
            },
            afterTextChanged = { editable ->
                if (editable != null && typedNewline >= 0 && binding.scratchpad.isFocused) {
                    val newline = typedNewline
                    typedNewline = -1
                    // Re-enters this listener, which saves and restyles the continued text.
                    MarkdownFormatter.continueList(editable.toString(), newline)?.let { edit ->
                        editable.replace(edit.start, edit.end, edit.replacement)
                        binding.scratchpad.setSelection(edit.selectionStart)
                        return@addTextChangedListener
                    }
                }
                val text = editable?.toString().orEmpty()
                scratchpadDebouncer.submit { prefs.scratchpadText = text }
                editable?.let { styleScratchpadMarkdown(it) }
            },
        )
        // While editing, long-press belongs to text selection/paste.
        binding.scratchpad.setOnLongClickListener { pad ->
            if (pad.isFocused) return@setOnLongClickListener false
            prefs.firstSettingsOpen = false
            findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            true
        }
        // Markers are hidden unless the note is being edited; leaving the keyboard hides them again.
        // Focusing makes the box scroll to reveal the cursor; put the view back where it was if nothing was typed.
        var scrollOnFocus = 0
        var scrolledWhileEditing = false
        var textOnFocus = 0
        binding.scratchpad.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                scrollOnFocus = v.scrollY
                scrolledWhileEditing = false
                textOnFocus = binding.scratchpad.text.toString().hashCode()
            }
            binding.scratchpad.text?.let { styleScratchpadMarkdown(it) }
        }
        binding.scratchpad.let { pad ->
            // A drag that scrolled the note while editing is the user's own scroll: keep it on leaving
            var scrollOnDown = 0
            // The note covers the top half, so home gestures also work on it while it's not being edited:
            // sideways always, up/down once the note can't scroll further that way.
            var flung = false
            val noteGestures = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    flung = true
                    when {
                        abs(dx) > abs(dy) && abs(dx) > SWIPE_MIN_PX -> if (dx < 0) noteSwipe(left = true) else noteSwipe(left = false)
                        dy < -SWIPE_MIN_PX && !pad.canScrollVertically(1) -> showAppList(Constants.FLAG_LAUNCH_APP)
                        dy > SWIPE_MIN_PX && !pad.canScrollVertically(-1) -> swipeDownAction()
                        else -> flung = false
                    }
                    return flung
                }
            })
            pad.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { scrollOnDown = pad.scrollY; flung = false }
                    MotionEvent.ACTION_UP -> if (pad.isFocused && pad.scrollY != scrollOnDown) scrolledWhileEditing = true
                }
                if (!pad.isFocused) noteGestures.onTouchEvent(event)
                // A swipe that did something must not also focus the note (and raise the keyboard).
                if (flung && event.actionMasked == MotionEvent.ACTION_UP) {
                    pad.onTouchEvent(MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL })
                    return@setOnTouchListener true
                }
                false
            }
            fun leaveEditing() {
                if (!pad.isFocused) return
                binding.mainLayout.requestFocus()
                pad.post { if (!scrolledWhileEditing && pad.text.toString().hashCode() == textOnFocus) pad.scrollTo(0, scrollOnFocus) }
            }
            // A back swipe reports the keyboard hidden as soon as its hide animation starts; moving focus then
            // makes the keyboard bounce back up. So leave editing only once the keyboard animation has ended.
            var keyboardAnimating = false
            var keyboardWasVisible = false
            ViewCompat.setWindowInsetsAnimationCallback(pad, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) keyboardAnimating = true
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>) = insets
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                    keyboardAnimating = false
                    if (ViewCompat.getRootWindowInsets(pad)?.isVisible(WindowInsetsCompat.Type.ime()) != true) leaveEditing()
                }
            })
            val basePaddingBottom = pad.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(pad) { _, insets ->
                val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
                // The window doesn't resize, so a keyboard taller than the bottom half covers the note's last
                // lines. Pad the note above the keyboard (and toolbar) so the cursor line scrolls into view.
                val keyboardTop = pad.rootView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom -
                    (if (visible && prefs.formatToolbar) binding.formatToolbar.height else 0)
                val padBottom = IntArray(2).also { pad.getLocationInWindow(it) }[1] + pad.height
                val covered = if (visible) (padBottom - keyboardTop).coerceAtLeast(0) else 0
                if (pad.paddingBottom != basePaddingBottom + covered) {
                    pad.updatePadding(bottom = basePaddingBottom + covered)
                    if (visible) pad.post { pad.bringPointIntoView(pad.selectionEnd.coerceAtLeast(0)) }
                }
                // Keyboard hidden without an animation
                if (keyboardWasVisible && !visible && !keyboardAnimating) leaveEditing()
                keyboardWasVisible = visible
                insets
            }
        }
        initFormatToolbar()
    }

    /** Opt-in toolbar: sits above the keyboard, and the bottom half steps aside so the note keeps room. */
    private fun initFormatToolbar() {
        val scratchpad = binding.scratchpad
        val toolbar = binding.formatToolbar
        if (!prefs.formatToolbar) return
        // Follow the system light/dark setting (like the keyboard), not the launcher's own theme mode
        val systemDark = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        toolbar.setBackgroundColor(if (systemDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        val textColor = if (systemDark) Color.WHITE else Color.BLACK
        ((toolbar.getChildAt(0)) as ViewGroup).children.forEach { (it as? TextView)?.setTextColor(textColor) }

        // The layout never resizes: the toolbar just rides the keyboard's top edge over the bottom half,
        // so closing the keyboard can't shift the note or the clock. ponytail: a keyboard taller than the
        // bottom half would cover the last note lines; fall back to resizing if that matters.
        val root = binding.mainLayout
        val frame = toolbar.parent as View
        fun follow(imeBottom: Int) {
            val frameBottom = IntArray(2).also { frame.getLocationInWindow(it) }[1] + frame.height
            toolbar.translationY = (root.rootView.height - imeBottom - frameBottom).toFloat()
            // Hidden until the keyboard actually starts rising, so it never sits alone at the screen bottom
            toolbar.alpha = if (imeBottom > 0) 1f else 0f
        }
        ViewCompat.setWindowInsetsAnimationCallback(root, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                follow(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                return insets
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            toolbar.isVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            follow(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        binding.formatDone.setOnClickListener { scratchpad.hideKeyboard() }
        binding.formatClearDone.setOnClickListener {
            val editable = scratchpad.text ?: return@setOnClickListener
            val cleared = MarkdownFormatter.clearDone(editable.toString())
            if (cleared == editable.toString()) return@setOnClickListener
            val cursor = scratchpad.selectionStart
            editable.replace(0, editable.length, cleared)
            scratchpad.setSelection(cursor.coerceIn(0, cleared.length))
        }
        mapOf(
            binding.formatBold to MarkdownAction.BOLD,
            binding.formatItalic to MarkdownAction.ITALIC,
            binding.formatUnderline to MarkdownAction.UNDERLINE,
            binding.formatStrike to MarkdownAction.STRIKE,
            binding.formatHighlight to MarkdownAction.HIGHLIGHT,
            binding.formatCode to MarkdownAction.CODE,
            binding.formatLink to MarkdownAction.LINK,
            binding.formatHeading to MarkdownAction.HEADING,
            binding.formatBullet to MarkdownAction.BULLET,
            binding.formatCheckbox to MarkdownAction.CHECKBOX,
            binding.formatIndent to MarkdownAction.INDENT,
            binding.formatOutdent to MarkdownAction.OUTDENT,
        ).forEach { (button, action) ->
            button?.setOnClickListener {
                val editable = scratchpad.text ?: return@setOnClickListener
                val start = minOf(scratchpad.selectionStart, scratchpad.selectionEnd).coerceAtLeast(0)
                val end = maxOf(scratchpad.selectionStart, scratchpad.selectionEnd).coerceAtLeast(0)
                val edit = MarkdownFormatter.edit(editable.toString(), start, end, action)
                editable.replace(edit.start, edit.end, edit.replacement)
                scratchpad.setSelection(edit.selectionStart, edit.selectionEnd)
            }
        }
    }

    /**
     * Own size setting: undo the global launcher scale (still honours system font size).
     * Below 1.0 the note gets medium weight and a touch of tracking, so small text keeps its strokes.
     */
    private fun applyScratchpadTypography(scale: Float) {
        val pad = binding.scratchpad
        val small = scale < 1f
        pad.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * scale / prefs.textSizeScale)
        val base = Typeface.create(prefs.scratchpadFont, Typeface.NORMAL)
        pad.typeface = when {
            !small -> base
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Typeface.create(base, 500, false)
            prefs.scratchpadFont == "sans-serif" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> base
        }
        pad.letterSpacing = if (small) 0.02f else 0f
        pad.setLineSpacing(0f, prefs.scratchpadLineSpacing)
        // Halo scales with the glyphs (3px at the default 17sp on xxhdpi) instead of a fixed blur.
        pad.setShadowLayer(pad.textSize * 0.06f, 0f, 0f, pad.shadowColor)
    }

    private fun styleScratchpadMarkdown(editable: Editable) {
        val accentColor = prefs.homeTextColor.takeIf { it != 0 } ?: requireContext().getColorFromAttr(R.attr.primaryColor)
        val dimColor = if (prefs.homeTextColor != 0) ColorUtils.setAlphaComponent(accentColor, 0x80)
        else requireContext().getColorFromAttr(R.attr.primaryColorTrans50)
        MarkdownStyler.apply(editable, dimColor, accentColor, prefs.scratchpadAccent, binding.scratchpad.isFocused)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity
        binding.homeAppsGridContainer.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        slots.forEach { it.gravity = horizontalGravity }
    }

    /** Sideways swipe on the note: same as on the rest of home. */
    private fun noteSwipe(left: Boolean) {
        if (left) openSwipeLeftApp() else openSwipeRightApp()
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

        ClockAppearance.apply(requireContext(), binding.clock, binding.date, prefs)
        var dateText = ClockAppearance.formatDate(prefs)

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return
        if (!prefs.screenTimeVisible) {
            binding.tvScreenTime.visibility = View.GONE
            return
        }

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = if (recentsOn) prefs.homeAppsNum.coerceAtMost(4) else prefs.homeAppsNum
        if (recentsOn) populateRecentApps()
        slots.forEach { it.applyAppLabelStyle(prefs, R.dimen.home_app_padding_vertical) }
        if (homeAppsNum == 0) return

        slots.take(homeAppsNum).forEachIndexed { i, view ->
            val location = i + 1
            view.visibility = View.VISIBLE
            if (!setHomeAppText(view, prefs.getAppName(location), prefs.getAppPackage(location), prefs.getAppUser(location), prefs.getIsShortcut(location), prefs.getShortcutId(location)))
                prefs.clearHomeApp(location)
        }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)
        val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        textView.setLeadingIcon(null)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            // Pinned shortcuts only exist on API 25+.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                textView.text = ""
                return false
            }
            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                val shortcut = shortcuts?.firstOrNull { it.id == shortcutId }
                if (shortcut != null) {
                    textView.text = appName
                    if (prefs.showAppIcons) textView.setLeadingIcon(launcherApps.getShortcutBadgedIconDrawable(shortcut, resources.displayMetrics.densityDpi))
                    return true
                }
                textView.text = ""
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            // ponytail: icons load on the main thread each resume; fine for <=8 apps, cache if it ever stutters.
            if (prefs.showAppIcons) textView.setLeadingIcon(runCatching { launcherApps.getActivityList(packageName, userHandle).firstOrNull()?.getBadgedIcon(0) }.getOrNull())
            return true
        }
        textView.text = ""
        return false
    }

    /** Slots 5–8 list recently used apps; "left column" just moves the pinned column to the right. */
    private fun populateRecentApps() {
        binding.homeAppsGrid.let { grid ->
            val pinnedColumn = binding.homeApp1.parent as View
            val index = if (prefs.recentAppsColumn == RECENTS_LEFT) 1 else 0
            if (grid.indexOfChild(pinnedColumn) != index) {
                grid.removeView(pinnedColumn)
                grid.addView(pinnedColumn, index)
            }
        }
        val slots = listOf(binding.homeApp5, binding.homeApp6, binding.homeApp7, binding.homeApp8)
        val exclude = (1..prefs.homeAppsNum.coerceAtMost(4)).map { prefs.getAppPackage(it) }.toSet() +
            prefs.hiddenApps.map { it.substringBefore('|') }
        val context = requireContext().applicationContext
        val showIcons = prefs.showAppIcons
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching { RecentApps.query(context, exclude, slots.size) }.getOrDefault(emptyList())
                    .map { it to if (showIcons) it.getBadgedIcon(0) else null }
            }
            recentApps = apps.map { it.first }
            slots.forEachIndexed { i, slot ->
                val (app, icon) = apps.getOrNull(i) ?: (null to null)
                slot.isVisible = app != null
                slot.text = app?.label
                slot.alpha = RECENT_APP_ALPHA
                slot.setLeadingIcon(icon)
            }
        }
    }

    private fun launchRecentApp(index: Int) {
        val app = recentApps.getOrNull(index) ?: return
        launchApp(app.label.toString(), app.componentName.packageName, app.componentName.className, app.user.toString())
    }

    private fun hideHomeApps() = slots.forEach { it.visibility = View.GONE }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.SEARCH -> openSearch(requireContext())
            else -> expandNotificationDrawer(requireContext())
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        syncPollJob?.cancel()
        scratchpadDebouncer.cancel()
        binding.scratchpad.text?.toString()?.let {
            prefs.scratchpadText = it
            ScratchpadSync.write(requireContext(), prefs, it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Matches OnSwipeTouchListener's distance threshold. */
private const val SWIPE_MIN_PX = 100

/** Recents read as a quieter column next to the pinned apps. */
private const val RECENT_APP_ALPHA = 0.6f
