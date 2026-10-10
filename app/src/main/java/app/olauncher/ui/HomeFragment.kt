package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.content.res.Resources
import android.os.BatteryManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.view.Gravity
import android.view.LayoutInflater
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
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
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var scratchpadDebouncer: Debouncer
    private var syncPollJob: Job? = null
    private var recentApps: List<LauncherActivityInfo> = emptyList()
    private val recentsOn get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && prefs.recentAppsColumn != RECENTS_OFF

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
        initScratchpad()
        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        applyHomeTextColor()
    }

    /** Custom text colour over every home label except the format toolbar, which follows the keyboard. */
    private fun applyHomeTextColor() {
        val color = prefs.homeTextColor.takeIf { it != 0 } ?: return
        fun walk(view: View) {
            if (view === binding.formatToolbar) return
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
            binding.scratchpad?.setText(it)
        }
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()

        // SAF tree URIs (Syncthing's folder) don't support change notifications, so poll while visible.
        syncPollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(3000)
                if (binding.scratchpad?.isFocused == true) continue
                ScratchpadSync.readIfChanged(requireContext(), prefs)?.let {
                    prefs.scratchpadText = it
                    binding.scratchpad?.setText(it)
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
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
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
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
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
        binding.homeApp1.setOnClickListener(this)
        binding.homeApp2.setOnClickListener(this)
        binding.homeApp3.setOnClickListener(this)
        binding.homeApp4.setOnClickListener(this)
        binding.homeApp5.setOnClickListener(this)
        binding.homeApp6.setOnClickListener(this)
        binding.homeApp7.setOnClickListener(this)
        binding.homeApp8.setOnClickListener(this)
        binding.homeApp1.setOnLongClickListener(this)
        binding.homeApp2.setOnLongClickListener(this)
        binding.homeApp3.setOnLongClickListener(this)
        binding.homeApp4.setOnLongClickListener(this)
        binding.homeApp5.setOnLongClickListener(this)
        binding.homeApp6.setOnLongClickListener(this)
        binding.homeApp7.setOnLongClickListener(this)
        binding.homeApp8.setOnLongClickListener(this)
    }

    private fun initScratchpad() {
        applyScratchpadTypography(prefs.scratchpadTextScale)
        binding.scratchpad?.onPinch = { scale, done ->
            val target = (prefs.scratchpadTextScale * scale).coerceIn(0.5f, 2.0f)
            if (done) {
                prefs.scratchpadTextScale = Math.round(target * 10f) / 10f
                applyScratchpadTypography(prefs.scratchpadTextScale)
            } else applyScratchpadTypography(target)
        }
        binding.scratchpad?.setText(prefs.scratchpadText)
        binding.scratchpad?.text?.let { styleScratchpadMarkdown(it) }
        var typedNewline = -1
        binding.scratchpad?.addTextChangedListener(
            onTextChanged = { s, start, before, count ->
                typedNewline = if (count == 1 && before == 0 && s?.getOrNull(start) == '\n') start else -1
            },
            afterTextChanged = { editable ->
                if (editable != null && typedNewline >= 0 && binding.scratchpad?.isFocused == true) {
                    val newline = typedNewline
                    typedNewline = -1
                    // Re-enters this listener, which saves and restyles the continued text.
                    MarkdownFormatter.continueList(editable.toString(), newline)?.let { edit ->
                        editable.replace(edit.start, edit.end, edit.replacement)
                        binding.scratchpad?.setSelection(edit.selectionStart)
                        return@addTextChangedListener
                    }
                }
                val text = editable?.toString().orEmpty()
                scratchpadDebouncer.submit { prefs.scratchpadText = text }
                editable?.let { styleScratchpadMarkdown(it) }
            },
        )
        // While editing, long-press belongs to text selection/paste.
        binding.scratchpad?.setOnLongClickListener { pad ->
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
        binding.scratchpad?.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                scrollOnFocus = v.scrollY
                scrolledWhileEditing = false
                textOnFocus = binding.scratchpad?.text.toString().hashCode()
            }
            binding.scratchpad?.text?.let { styleScratchpadMarkdown(it) }
        }
        binding.scratchpad?.let { pad ->
            // A drag that scrolled the note while editing is the user's own scroll: keep it on leaving
            var scrollOnDown = 0
            pad.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> scrollOnDown = pad.scrollY
                    MotionEvent.ACTION_UP -> if (pad.isFocused && pad.scrollY != scrollOnDown) scrolledWhileEditing = true
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
            ViewCompat.setOnApplyWindowInsetsListener(pad) { _, insets ->
                val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
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
        val scratchpad = binding.scratchpad ?: return
        val toolbar = binding.formatToolbar ?: return
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
        binding.formatDone?.setOnClickListener { scratchpad.hideKeyboard() }
        binding.formatClearDone?.setOnClickListener {
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
        val pad = binding.scratchpad ?: return
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
        // Halo scales with the glyphs (3px at the default 17sp on xxhdpi) instead of a fixed blur.
        pad.setShadowLayer(pad.textSize * 0.06f, 0f, 0f, pad.shadowColor)
    }

    private fun styleScratchpadMarkdown(editable: Editable) {
        val accentColor = prefs.homeTextColor.takeIf { it != 0 } ?: requireContext().getColorFromAttr(R.attr.primaryColor)
        val dimColor = if (prefs.homeTextColor != 0) ColorUtils.setAlphaComponent(accentColor, 0x80)
        else requireContext().getColorFromAttr(R.attr.primaryColorTrans50)
        MarkdownStyler.apply(editable, dimColor, accentColor, prefs.scratchpadAccent, binding.scratchpad?.isFocused == true)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity
        binding.homeAppsGridContainer.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
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
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1, prefs.isShortcut1, prefs.shortcutId1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2, prefs.isShortcut2, prefs.shortcutId2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3, prefs.isShortcut3, prefs.shortcutId3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4, prefs.isShortcut4, prefs.shortcutId4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5, prefs.isShortcut5, prefs.shortcutId5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) return

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp6, prefs.appName6, prefs.appPackage6, prefs.appUser6, prefs.isShortcut6, prefs.shortcutId6)) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) return

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp7, prefs.appName7, prefs.appPackage7, prefs.appUser7, prefs.isShortcut7, prefs.shortcutId7)) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) return

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp8, prefs.appName8, prefs.appPackage8, prefs.appUser8, prefs.isShortcut8, prefs.shortcutId8)) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
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
        setHomeAppIcon(textView, null)

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
                    if (prefs.showAppIcons) setHomeAppIcon(textView, launcherApps.getShortcutBadgedIconDrawable(shortcut, resources.displayMetrics.densityDpi))
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
            if (prefs.showAppIcons) setHomeAppIcon(textView, runCatching { launcherApps.getActivityList(packageName, userHandle).firstOrNull()?.getBadgedIcon(0) }.getOrNull())
            return true
        }
        textView.text = ""
        return false
    }

    /** Slots 5–8 list recently used apps; "left column" just moves the pinned column to the right. */
    private fun populateRecentApps() {
        binding.homeAppsGrid?.let { grid ->
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
                setHomeAppIcon(slot, icon)
            }
        }
    }

    private fun launchRecentApp(index: Int) {
        val app = recentApps.getOrNull(index) ?: return
        launchApp(app.label.toString(), app.componentName.packageName, app.componentName.className, app.user.toString())
    }

    /** Launcher icon before the label, sized to the text; null clears it. */
    private fun setHomeAppIcon(textView: TextView, icon: Drawable?) {
        val size = (textView.textSize * 1.2f).toInt()
        textView.setCompoundDrawablesRelative(icon?.mutate()?.apply { setBounds(0, 0, size, size) }, null, null, null)
        textView.compoundDrawablePadding = (textView.textSize * 0.5f).toInt()
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
    }

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
        binding.scratchpad?.text?.toString()?.let {
            prefs.scratchpadText = it
            ScratchpadSync.write(requireContext(), prefs, it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Recents read as a quieter column next to the pinned apps. */
private const val RECENT_APP_ALPHA = 0.6f
