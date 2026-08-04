# Scratchpad Launcher — Design

## Goal

Fork [Olauncher](https://github.com/tanujnotes/Olauncher) (Kotlin, MVVM, GPLv3) and add a persistent scratchpad to the home screen: a text box that's always visible, survives reboots, and never leaves the device. Bottom half keeps a shrunk version of Olauncher's existing app list.

Stretch goal, explicitly out of scope for v1: a unified emacs-style single text field where typing an app name launches it (clearing the field) and typing anything else leaves it as a note. v1 ships the split-screen layout only.

## Prior art

No existing launcher combines a persistent scratchpad with app launching. Closest relatives:
- **inkOS** / **Text Launcher** / **Fokus Launcher** — text-based launchers, no scratchpad.
- **Omni-Notes**, **Google Keep** — scratchpad/note widgets, but standalone apps, not launcher-integrated.

Olauncher (GPLv3, Kotlin, MVVM) is the chosen fork base per idea.md.

## License

Fork inherits **GPLv3** from Olauncher (copyleft — cannot relicense AGPL-3.0, which is this user's usual default for new public repos).

## Architecture

Split Olauncher's home screen (`HomeFragment`) into two vertical panes:

- **Top half — `ScratchpadView`**: borderless `EditText`, transparent background, `setShadowLayer` applied to glyphs for legibility against any wallpaper (no background box/scrim).
- **Bottom half — app area**: reuses Olauncher's existing app-list adapter/RecyclerView unchanged in logic, resized to a smaller height, with the default curated-slot count reduced (e.g. from ~6 to 3-4). Swipe-up still opens the full app drawer for everything else.

## Persistence

- **`SharedPreferences` via `Prefs.kt`** (not DataStore — corrected after reading the actual Olauncher source; the whole codebase persists settings through a single `Prefs` wrapper class around `SharedPreferences`, and the scratchpad follows that exact existing pattern rather than introducing a new persistence library). Its own dedicated `SharedPreferences` file (`app.olauncher.scratchpad`), separate from Olauncher's main settings file, so it can be excluded from backup independently.
- Save on text-change with ~300ms debounce to avoid excessive IO.
- Flush on `onPause` as a safety net against process death mid-edit.
- **No-leak guarantee**: Olauncher's manifest has `android:allowBackup="true"` (needed for its other settings) — rather than disabling backup app-wide, the scratchpad's dedicated prefs file is explicitly excluded via `<exclude domain="sharedpref" path="app.olauncher.scratchpad.xml">` in both `data_extraction_rules.xml` (Android 12+ cloud backup) and `backup_rules.xml` (pre-12 full backup). No share/export intent wired to the scratchpad text. No network permission touches scratchpad data.

## Legibility

Text shadow/outline approach: `Paint.setShadowLayer` (or equivalent) behind the scratchpad's glyphs, no background shape. Works across arbitrary wallpapers without an "ugly bg" box; cheapest to implement and matches idea.md's requirement of no overlay.

## Error handling

- `SharedPreferences` writes are synchronous to the in-memory map and asynchronous to disk (`apply()`), so the on-screen `EditText` state is never at risk from a write failure — nothing to retry or roll back.
- No new crash surface expected beyond Olauncher's existing lifecycle handling.

## Testing

- Unit test: the debounce helper's save-scheduling logic (pure Kotlin, `kotlinx-coroutines-test`, no Android framework needed).
- The `Prefs.scratchpadText` round-trip itself is verified manually, not unit tested — it's a one-line `SharedPreferences` get/set identical in shape to the ~100 other properties already in `Prefs.kt`, none of which have ever had unit tests (project has zero test infrastructure prior to this feature). Adding Robolectric solely for this one property was judged disproportionate; manual verification matches how the rest of `Prefs.kt` has always been verified.
- Manual on-device: split-screen layout rendering, legibility check across a few wallpapers (solid dark, solid light, busy photo), reboot-survival check (write text, reboot, confirm persisted), backup-exclusion check.

## Out of scope (v1)

- Unified single-field emacs-style mode (idea.md's "extreme end").
- Cloud sync / backup of scratchpad content.
- Rich text, multiple scratchpad notes, or history.
