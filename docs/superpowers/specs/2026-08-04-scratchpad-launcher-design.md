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

- Jetpack **DataStore (Preferences)**, single string key — Room would be overkill for one text blob.
- Save on text-change with ~300ms debounce to avoid excessive IO.
- Flush on `onPause`/`onStop` as a safety net against process death mid-edit.
- **No-leak guarantee**: confirm `android:allowBackup="false"` in the manifest (verify Olauncher's current setting, set explicitly if not already). No share/export intent wired to the scratchpad text. No network permission touches scratchpad data.

## Legibility

Text shadow/outline approach: `Paint.setShadowLayer` (or equivalent) behind the scratchpad's glyphs, no background shape. Works across arbitrary wallpapers without an "ugly bg" box; cheapest to implement and matches idea.md's requirement of no overlay.

## Error handling

- DataStore write failure: keep text in in-memory ViewModel state, retry write, never clear the field on error.
- No new crash surface expected beyond Olauncher's existing lifecycle handling.

## Testing

- Unit test: DataStore repository save/load round-trip and debounce behavior.
- Manual on-device: split-screen layout rendering, legibility check across a few wallpapers (solid dark, solid light, busy photo), reboot-survival check (write text, reboot, confirm persisted).

## Out of scope (v1)

- Unified single-field emacs-style mode (idea.md's "extreme end").
- Cloud sync / backup of scratchpad content.
- Rich text, multiple scratchpad notes, or history.
