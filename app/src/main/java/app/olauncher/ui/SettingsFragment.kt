package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.DEFAULT_LINK_COLOR
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.Backup
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isTablet
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openUrl
import app.olauncher.helper.rateApp
import app.olauncher.helper.ScratchpadSync
import app.olauncher.helper.shareApp
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin

class SettingsFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.isOlauncherDefault()

        deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireContext(), DeviceAdmin::class.java)
        checkAdminPermission()

        binding.homeAppsNum.text = prefs.homeAppsNum.toString()
        populateKeyboardText()
        populateScratchpadOptions()
        populateTextColour()
        binding.appIcons.isChecked = prefs.showAppIcons
        populateScreenTimeOnOff()
        populateLockSettings()
        // Home button for recents feature disabled
        // populateHomeButtonRecents()
        populateAppThemeText()
        populateTextSize()
        populateScratchpadSize()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeApps()
        populateSwipeDownAction()
        populateActionHints()
        binding.appVersion.text = "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}"
        initClickListeners()
        initObservers()
    }

    private val exportScratchpad = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        viewModel.isPickingDocument = false
        uri ?: return@registerForActivityResult
        val backup = Backup.encode(prefs.allSettings, prefs.scratchpadText)
        val ok = runCatching {
            requireContext().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(backup.toByteArray()) }
        }.isSuccess
        requireContext().showToast(getString(if (ok) R.string.backup_saved else R.string.backup_failed))
    }

    private val importScratchpad = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.isPickingDocument = false
        uri ?: return@registerForActivityResult
        val context = requireContext()
        // Strict UTF-8 decode and a size cap reject binaries and huge files before they replace the note.
        val text = runCatching {
            val buf = ByteArray(MAX_RESTORE_BYTES + 1)
            val size = context.contentResolver.openInputStream(uri)!!.use { input ->
                var n = 0
                while (n < buf.size) n += input.read(buf, n, buf.size - n).takeIf { it >= 0 } ?: break
                n
            }
            require(size <= MAX_RESTORE_BYTES)
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(buf, 0, size)).toString().removePrefix("\uFEFF").also { require('\u0000' !in it) }
        }.getOrNull()
        // A full backup restores settings and apps too; any other text file (e.g. an old scratchpad.md backup) is just the note.
        val backup = text?.let { runCatching { Backup.decode(it) } }
        if (text == null || backup?.isFailure == true) return@registerForActivityResult context.showToast(getString(R.string.restore_failed))
        val full = backup?.getOrNull()
        AlertDialog.Builder(context)
            .setMessage(if (full != null) R.string.restore_full_confirmation else R.string.restore_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_scratchpad) { _, _ ->
                val note = full?.scratchpad ?: text
                prefs.scratchpadText = note
                ScratchpadSync.write(context, prefs, note)
                if (full != null) {
                    prefs.replaceSettings(full.settings)
                    AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
                    requireActivity().recreate()
                }
            }
            .show()
    }

    private val pickSyncFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.isPickingDocument = false
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.syncFolderUri = uri.toString()
        prefs.syncLastModified = 0L
        // Blank note adopts an existing file; otherwise the note is written out.
        if (prefs.scratchpadText.isBlank()) ScratchpadSync.readIfChanged(requireContext(), prefs)?.let { prefs.scratchpadText = it }
        else ScratchpadSync.write(requireContext(), prefs, prefs.scratchpadText)
        requireContext().showToast(getString(R.string.sync_folder_set))
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.olauncherHiddenApps, R.id.hiddenApps -> showHiddenApps()
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.toggleLock -> toggleLockMode()
            // Home button for recents feature disabled
            // R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.scratchpadFont -> pick(R.string.scratchpad_font, SCRATCHPAD_FONTS.map { getString(it.value) to it.key }, prefs.scratchpadFont) {
                prefs.scratchpadFont = it
                populateScratchpadOptions()
            }
            R.id.scratchpadAccent -> {
                showColorPicker(requireContext(), prefs.scratchpadAccent, DEFAULT_LINK_COLOR) {
                    prefs.scratchpadAccent = it
                    populateScratchpadOptions()
                }
            }
            R.id.textColour -> {
                val theme = requireContext().getColorFromAttr(R.attr.primaryColor)
                showColorPicker(
                    requireContext(), prefs.homeTextColor.takeIf { it != 0 } ?: theme, 0,
                    R.string.text_colour, R.string.text_colour_preview, underline = false,
                ) {
                    prefs.homeTextColor = it
                    populateTextColour()
                }
            }
            R.id.appIcons -> {
                prefs.showAppIcons = !prefs.showAppIcons
                binding.appIcons.isChecked = prefs.showAppIcons
            }
            R.id.formatToolbarToggle -> {
                prefs.formatToolbar = !prefs.formatToolbar
                populateScratchpadOptions()
            }
            R.id.homeAppsNum -> pick(R.string.apps_on_home_screen, (0..8).map { "$it" to it }, prefs.homeAppsNum) { updateHomeAppsNum(it) }
            R.id.alignment -> pick(
                R.string.home_layout_alignment,
                listOf(getString(R.string.left) to Gravity.START, getString(R.string.center) to Gravity.CENTER, getString(R.string.right) to Gravity.END),
                prefs.homeAlignment,
            ) { viewModel.updateHomeAlignment(it) }
            R.id.alignmentBottom -> updateHomeBottomAlignment()
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> pick(
                R.string.show_date_time,
                listOf(getString(R.string.on) to Constants.DateTime.ON, getString(R.string.off) to Constants.DateTime.OFF, getString(R.string.date_only) to Constants.DateTime.DATE_ONLY),
                prefs.dateTimeVisibility,
            ) { toggleDateTime(it) }
            R.id.appThemeText -> pick(
                R.string.theme_mode,
                listOf(
                    getString(R.string.system_default) to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                    getString(R.string.light) to AppCompatDelegate.MODE_NIGHT_NO,
                    getString(R.string.dark) to AppCompatDelegate.MODE_NIGHT_YES,
                ),
                prefs.appTheme,
            ) { updateTheme(it) }
            R.id.textSizeValue -> pick(R.string.text_size, scales(if (isTablet(requireContext())) 2.0f else 1.5f), prefs.textSizeScale) {
                prefs.textSizeScale = it
                requireActivity().recreate()
            }
            R.id.clockAppearance -> findNavController().navigate(R.id.action_settingsFragment_to_clockAppearanceFragment)
            // Applied when the home view is rebuilt on return, so no activity recreate needed.
            R.id.scratchpadSizeValue -> pick(R.string.scratchpad_text_size, scales(2.0f), prefs.scratchpadTextScale) {
                prefs.scratchpadTextScale = it
                populateScratchpadSize()
            }

            R.id.tvGestures -> binding.flSwipeDown.visibility = View.VISIBLE

            R.id.swipeLeftApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_LEFT_APP)
            R.id.swipeRightApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_RIGHT_APP)
            R.id.swipeDownAction -> pick(
                R.string.swipe_down_for,
                listOf(getString(R.string.notifications) to Constants.SwipeDownAction.NOTIFICATIONS, getString(R.string.search) to Constants.SwipeDownAction.SEARCH),
                prefs.swipeDownAction,
            ) { updateSwipeDownAction(it) }

            R.id.aboutOlauncher -> {
                prefs.aboutClicked = true
                requireContext().openUrl(Constants.URL_ABOUT)
            }

            R.id.share -> requireActivity().shareApp()
            R.id.syncFolder -> {
                viewModel.isPickingDocument = true
                pickSyncFolder.launch(null)
            }
            R.id.backupScratchpad -> {
                viewModel.isPickingDocument = true
                exportScratchpad.launch("scratchpad-launcher-backup.json")
            }
            R.id.restoreScratchpad -> {
                viewModel.isPickingDocument = true
                importScratchpad.launch(arrayOf("*/*"))
            }
            R.id.rate -> {
                prefs.rateClicked = true
                requireActivity().rateApp()
            }

            R.id.github -> requireContext().openUrl(Constants.URL_GITHUB)
            R.id.privacy -> requireContext().openUrl(Constants.URL_PRIVACY)
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.alignment -> {
                prefs.appLabelAlignment = prefs.homeAlignment
                findNavController().navigate(R.id.action_settingsFragment_to_appListFragment)
                requireContext().showToast(getString(R.string.alignment_changed))
            }

            R.id.swipeLeftApp -> toggleSwipeLeft()
            R.id.swipeRightApp -> toggleSwipeRight()
            R.id.toggleLock -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            R.id.syncFolder -> {
                prefs.syncFolderUri = ""
                requireContext().showToast(getString(R.string.sync_folder_cleared))
            }
        }
        return true
    }

    private fun initClickListeners() {
        binding.olauncherHiddenApps.setOnClickListener(this)
        binding.hiddenApps.setOnClickListener(this)
        binding.scrollLayout.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.aboutOlauncher.setOnClickListener(this)
        // Rows are tapped as a whole; the value view inside keeps the id onClick dispatches on.
        listOf(
            binding.autoShowKeyboard, binding.scratchpadFont, binding.scratchpadAccent, binding.formatToolbarToggle,
            binding.toggleLock, binding.homeAppsNum, binding.screenTimeOnOff, binding.alignment, binding.statusBar,
            binding.dateTime, binding.swipeLeftApp, binding.swipeRightApp, binding.swipeDownAction, binding.appThemeText,
            binding.textSizeValue, binding.scratchpadSizeValue, binding.alignmentBottom, binding.textColour, binding.appIcons,
        ).forEach { v -> (v.parent as View).setOnClickListener { onClick(v) } }
        listOf(binding.alignment, binding.swipeLeftApp, binding.swipeRightApp, binding.toggleLock)
            .forEach { v -> (v.parent as View).setOnLongClickListener { onLongClick(v) } }
        // Home button for recents feature disabled
        // binding.homeButtonRecents.setOnClickListener(this)
        binding.clockAppearance.setOnClickListener(this)

        binding.syncFolder.setOnClickListener(this)
        binding.backupScratchpad.setOnClickListener(this)
        binding.restoreScratchpad.setOnClickListener(this)
        binding.syncFolder.setOnLongClickListener(this)
        binding.share.setOnClickListener(this)
        binding.rate.setOnClickListener(this)
        binding.github.setOnClickListener(this)
        binding.privacy.setOnClickListener(this)
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            viewModel.showDialog.postValue(Constants.Dialog.ABOUT)
            prefs.firstSettingsOpen = false
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
                prefs.toShowHintCounter += 1
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
        viewModel.updateSwipeApps.observe(viewLifecycleOwner) {
            populateSwipeApps()
        }
    }

    private fun toggleSwipeLeft() {
        prefs.swipeLeftEnabled = !prefs.swipeLeftEnabled
        populateSwipeApps()
        requireContext().showToast(getString(if (prefs.swipeLeftEnabled) R.string.swipe_left_app_enabled else R.string.swipe_left_app_disabled))
    }

    private fun toggleSwipeRight() {
        prefs.swipeRightEnabled = !prefs.swipeRightEnabled
        populateSwipeApps()
        requireContext().showToast(getString(if (prefs.swipeRightEnabled) R.string.swipe_right_app_enabled else R.string.swipe_right_app_disabled))
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) showStatusBar() else hideStatusBar()
        binding.statusBar.isChecked = prefs.showStatusBar
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
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

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private fun checkAdminPermission() {
        val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            prefs.lockModeOn = isAdmin
    }

    private fun showAccessibilityDisclosure() {
        val enabled = isAccessServiceEnabled(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.gestures)
            .setMessage(R.string.accessibility_disclosure)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.not_working) { _, _ -> requireContext().openUrl(Constants.URL_DOUBLE_TAP) }
            .setPositiveButton(if (enabled) R.string.disable else R.string.enable) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun toggleLockMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!prefs.lockModeOn && !isAccessServiceEnabled(requireContext())) {
                showAccessibilityDisclosure()
                return
            }
            prefs.lockModeOn = !prefs.lockModeOn
        } else {
            val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
            if (isAdmin) {
                removeActiveAdmin("Admin permission removed.")
                prefs.lockModeOn = false
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_permission_message)
                )
                requireActivity().startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        populateLockSettings()
    }

    private fun removeActiveAdmin(toastMessage: String? = null) {
        try {
            deviceManager.removeActiveAdmin(componentName) // for backward compatibility
            requireContext().showToast(toastMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateHomeAppsNum(num: Int) {
        binding.homeAppsNum.text = num.toString()
        prefs.homeAppsNum = num
        viewModel.refreshHome(true)
    }

    private fun <T> pick(title: Int, options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) =
        requireContext().showChoices(getString(title), options, current, onPick)

    /** 0.5 up to [max] in 0.1 steps, labelled like the row summary. */
    private fun scales(max: Float) = (5..Math.round(max * 10)).map { String.format("%.1f", it / 10f) to it / 10f }

    private fun toggleKeyboardText() {
        if (prefs.autoShowKeyboard && prefs.keyboardMessageShown.not()) {
            viewModel.showDialog.postValue(Constants.Dialog.KEYBOARD)
            prefs.keyboardMessageShown = true
        } else {
            prefs.autoShowKeyboard = !prefs.autoShowKeyboard
            populateKeyboardText()
        }
    }

    private fun updateTheme(appTheme: Int) {
        prefs.appTheme = appTheme
        populateAppThemeText(appTheme)
        requireActivity().recreate()
    }

    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> binding.appThemeText.text = getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> binding.appThemeText.text = getString(R.string.light)
            else -> binding.appThemeText.text = getString(R.string.system_default)
        }
    }

    private fun populateScratchpadSize() {
        val formatted = String.format("%.1f", prefs.scratchpadTextScale)
        binding.scratchpadSizeValue.text = formatted
    }

    private fun populateTextSize() {
        val formatted = String.format("%.1f", prefs.textSizeScale)
        binding.textSizeValue.text = formatted
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            binding.screenTimeOnOff.isChecked = requireContext().appUsagePermissionGranted()
        else binding.screenTimeLayout.visibility = View.GONE
    }

    private fun populateScratchpadOptions() {
        binding.scratchpadFont.setText(SCRATCHPAD_FONTS[prefs.scratchpadFont] ?: R.string.font_sans)
        binding.scratchpadAccent.text = String.format("#%06X", prefs.scratchpadAccent and 0xFFFFFF)
        binding.scratchpadAccent.setTextColor(prefs.scratchpadAccent)
        binding.formatToolbarToggle.isChecked = prefs.formatToolbar
    }

    private fun populateTextColour() {
        val color = prefs.homeTextColor
        binding.textColour.text = if (color == 0) getString(R.string.accent_default) else String.format("#%06X", color and 0xFFFFFF)
        binding.textColour.setTextColor(if (color == 0) requireContext().getColorFromAttr(R.attr.primaryColorTrans80) else color)
    }

    private fun populateKeyboardText() {
        binding.autoShowKeyboard.isChecked = prefs.autoShowKeyboard
    }

    private fun updateHomeBottomAlignment() {
        if (viewModel.isOlauncherDefault.value != true) {
            requireContext().showToast(getString(R.string.please_set_olauncher_as_default_first), Toast.LENGTH_LONG)
            return
        }
        prefs.homeBottomAlignment = !prefs.homeBottomAlignment
        populateAlignment()
        viewModel.updateHomeAlignment(prefs.homeAlignment)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.CENTER -> binding.alignment.text = getString(R.string.center)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
        binding.alignmentBottom.isChecked = prefs.homeBottomAlignment
    }

    // Home button for recents feature disabled
    // private fun toggleHomeButtonRecents() {
    //     if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(requireContext())) {
    //         toggleAccessibilityVisibility(true)
    //         return
    //     }
    //     prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
    //     populateHomeButtonRecents()
    // }

    // private fun populateHomeButtonRecents() {
    //     binding.homeButtonRecents.text = getString(
    //         if (prefs.homeButtonShowRecents && isAccessServiceEnabled(requireContext())) R.string.on
    //         else R.string.off
    //     )
    // }

    private fun populateLockSettings() {
        binding.toggleLock.isChecked = prefs.lockModeOn &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || isAccessServiceEnabled(requireContext()))
    }

    private fun populateSwipeDownAction() {
        binding.swipeDownAction.text = when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> getString(R.string.notifications)
            else -> getString(R.string.search)
        }
    }

    private fun updateSwipeDownAction(swipeDownFor: Int) {
        prefs.swipeDownAction = swipeDownFor
        populateSwipeDownAction()
    }

    private fun populateSwipeApps() {
        binding.swipeLeftApp.text = prefs.appNameSwipeLeft
        binding.swipeRightApp.text = prefs.appNameSwipeRight
        binding.swipeLeftApp.alpha = if (prefs.swipeLeftEnabled) 1f else 0.5f
        binding.swipeRightApp.alpha = if (prefs.swipeRightEnabled) 1f else 0.5f
    }

//    private fun populateDigitalWellbeing() {
//        binding.digitalWellbeing.isVisible = requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_PACKAGE_NAME).not()
//                && requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME).not()
//                && prefs.hideDigitalWellbeing.not()
//    }

    private fun showAppListIfEnabled(flag: Int) {
        if ((flag == Constants.FLAG_SET_SWIPE_LEFT_APP) and !prefs.swipeLeftEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        if ((flag == Constants.FLAG_SET_SWIPE_RIGHT_APP) and !prefs.swipeRightEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        viewModel.getAppList(true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to flag)
        )
    }

    private fun populateActionHints() {
        if (prefs.aboutClicked.not())
            binding.aboutOlauncher.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0)
        if (viewModel.isOlauncherDefault.value != true) return
        if (prefs.rateClicked.not() && prefs.toShowHintCounter > Constants.HINT_RATE_US && prefs.toShowHintCounter < Constants.HINT_RATE_US + 100)
            binding.rate.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.arrow_down_float, 0, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        viewModel.checkForMessages.call()
        super.onDestroy()
    }
}
private const val MAX_RESTORE_BYTES = 1024 * 1024

/** Typeface family -> label, in cycle order. */
private val SCRATCHPAD_FONTS = linkedMapOf("sans-serif" to R.string.font_sans, "serif" to R.string.font_serif, "monospace" to R.string.font_mono)
