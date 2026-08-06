# Scratchpad Launcher

A minimal Android home screen that's also a piece of paper.

The top half is a plain text box that's always there — write something down without opening an app, and it survives reboots. The bottom half is a small, fixed list of apps. Nothing else.

### Install

Debug builds are attached to [GitHub Releases](https://github.com/mstcgalis/scratchpad-launcher/releases). F-Droid submission is pending — see [Status](#status) below.

### Why

Sometimes you just want to write something down and have it always visible without opening any app. This is a fork of [Olauncher](https://github.com/tanujnotes/Olauncher) — a minimal, ad-free Android launcher — with the app grid cut down and a persistent scratchpad put in its place.

### Privacy

No accounts, no analytics, no network calls except the optional daily-wallpaper feature (off by default) and outbound links you tap yourself. Scratchpad text is stored locally in its own preferences file and excluded from Android auto-backup, so it never leaves the device.

### Status

This is a personal fork, freshly rebranded from Olauncher (new `applicationId`, own icon). F-Droid metadata is prepared; not yet submitted to `fdroiddata`.

### License

[GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html) — inherited from the upstream [Olauncher](https://github.com/tanujnotes/Olauncher) project by [tanujnotes](https://github.com/tanujnotes), licensed the same way. Source changes in this fork are on top of Olauncher v6.7.19.
