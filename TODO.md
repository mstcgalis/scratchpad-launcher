# Scratchpad Launcher MR #45012 - Pre-merge Tasks

## From Review Note #3793538966

### [BUG] Fix text size > 1.0 breaking the app grid
- **Status**: ✅ FIXED
- Labels in `homeAppsGrid` use two `layout_weight="1"` columns
- Fixed: Added `maxLines="1"` and `ellipsize="end"` to all homeApp TextViews
- This prevents long labels from wrapping and pushing apps off-screen

### [UPSTREAM] Update MR to v1.1.4+
- **Status**: ✅ FIXED IN CODE (still needs fdroiddata metadata update)
- Upstream tagged v1.1.4–v1.1.6 on 2026-08-19
- **Critical**: v1.1.4 fixes landscape `onPause()` overwriting saved note with literal string "null" (upstream commit 6fc5ba3)
- **Verified**: `app/src/main/java/app/olauncher/ui/HomeFragment.kt:748` now uses `binding.scratchpad?.text?.toString()?.let` (nullable-safe)
- Local code is at v1.1.6 (versionCode 8), past the critical fix
- **Remaining**: Update `fdroiddata/metadata/app.scratchpad.launcher.yml` to point to current commit/version (`v1.1.6` instead of `1.1.3`)

### [METADATA] Fix German metadata
- ✅ `short_description.txt` rewritten to mention scratchpad
- ✅ `full_description.txt` added with German translation

### [BUG] Fix first-run hint layout/behavior
- **Status**: ✅ FIXED
- Hint ("1. Swipe up for all apps, 2. Long press anywhere for settings") overlaps third app row until settings are opened once
- Fixed: `homeAppsLayout` paddingBottom increased from 24dp to 56dp (portrait) and 80dp (landscape) to prevent hint overlap
- Long-press in scratchpad now opens settings instead of selecting text
- Fix: Added `setOnLongClickListener` to scratchpad that opens settings and sets `firstSettingsOpen = false`

## MR Required Items (from MR overview)

### [Required] App compliance with inclusion criteria
- Verify app complies with F-Droid Inclusion Policy
- Confirm no antifeatures (no accounts, no analytics, no network calls except user-tapped outbound links)

### [Required] Original app author notified
- Notify original author (filed GitHub issue #741, awaiting response)

### [Required] Build verification
- Build with `fdroid build` and ensure all pipelines pass
- Reproducible builds enabled (Binaries points to signed APK, AllowedAPKSigningKeys set)

### [Required] Issue tracker and contact info
- IssueTracker: https://github.com/mstcgalis/scratchpad-launcher/issues
- Contact info available for bug reports

### [Strongly Recommended] Releases tagged and auto-update enabled
- AutoUpdateMode: Version / UpdateCheckMode: Tags already set

### [Suggested] Enable Reproducible Builds
- Already configured in metadata

## Status
- Reproducible build: PASS (verified on device, built binary matches reference APK)
- VirusTotal: 0/66 detections
- No INTERNET permission declared
- No dangerous runtime permissions
- German metadata: ✅ FIXED
- First-run hint overlap: ✅ FIXED (layout padding + long-press listener)
- Text size > 1.0 bug: ✅ FIXED (ellipsize + maxLines)
- Upstream update to v1.1.4+: ✅ FIXED IN CODE (v1.1.6, commit 6fc5ba3 applied)
- Save scratchpad to file: ✅ ADDED (new feature)

## New Features

### Save Scratchpad to File
- Added "Save scratchpad" option in Settings
- Saves scratchpad content to `Android/data/app.scratchpad.launcher/files/scratchpad.txt`
- Toast notification confirms save location