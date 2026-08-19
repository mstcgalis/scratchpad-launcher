# Scratchpad Launcher

Minimal Android launcher: top half is a persistent plain-text scratchpad, bottom half a small fixed app grid. Fork of [Olauncher](https://github.com/tanujnotes/Olauncher) — package/namespace is still `app.olauncher`, applicationId is `app.scratchpad.launcher`. See `README.md` for product description, `idea.md` for original design notes.

## Build

Single Gradle module (`app`), Kotlin, no CI configured. Use `just` (justfile at repo root):

- `just build` — assembleDebug
- `just test` — unit tests only (`app/src/test`), fast, no device needed
- `just lint` — Android lint
- `just run` — install debug build on connected device/emulator and launch it
- `just clean`

Java 21 / compileSdk 35 / minSdk 24. No ktlint/detekt configured — `just lint` is Android Lint only.

## Source layout

`app/src/main/java/app/olauncher/`
- `MainActivity.kt`, `MainViewModel.kt` — app entry point, shared view model
- `data/Prefs.kt` — all settings storage (SharedPreferences wrapper); scratchpad text lives in its own prefs file (`app.scratchpad.launcher.scratchpad`), excluded from Android auto-backup
- `data/Constants.kt`, `data/AppModel.kt` — shared constants/models
- `ui/HomeFragment.kt` — the scratchpad + app grid screen, largest/most central file
- `ui/SettingsFragment.kt` — settings screen
- `ui/AppDrawerFragment.kt`, `ui/AppDrawerAdapter.kt` — app picker
- `ui/MarkdownEditText.kt` + `helper/MarkdownStyler.kt` + `helper/MarkdownMatcher.kt` — lightweight markdown rendering inside the scratchpad text box
- `helper/` — misc utilities (usage stats, accessibility service, extensions)
- `listener/` — touch/swipe gesture listeners, device admin

Layouts are duplicated per orientation: `res/layout/` (portrait) and `res/layout-land/` (landscape) — changes to `fragment_home.xml` usually need mirroring in both, and this has been a recurring bug source (see recent git log for landscape/scratchpad fixes).

## Conventions

- License: repo is GPLv3 (inherited from Olauncher) — do NOT relicense to AGPL despite the user's general default preference for new repos.
- Scratchpad text must never leave the device and must survive reboots — no analytics/network calls on that path.
- Keep changes scoped; this is a small personal fork, not upstream Olauncher — don't reintroduce upstream features that were deliberately cut (reduced app grid, etc).
