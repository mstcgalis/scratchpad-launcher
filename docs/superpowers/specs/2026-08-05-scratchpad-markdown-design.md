# Scratchpad markdown styling — design

Source request: `TODO.md` "features" section — "mardown would be nice".

## Goal

The scratchpad (`app/olauncher/ui/HomeFragment.kt`, top half of home screen) is a
plain-text `EditText` (`fragment_home.xml:24`) whose content is autosaved raw to
`Prefs.scratchpadText`. This adds live inline markdown styling to that field:
type `**bold**` and it renders bold immediately, in place, while still being a
normal editable text field — no separate preview mode, no toggle.

## Scope

Supported syntax (chosen deliberately narrow — this is a scratchpad, not a
document editor):

- `# Header` through `###### Header` (size scales down as `#` count increases)
- `**bold**`
- `*italic*`
- `- item` (bullet list)
- `- [ ] todo` / `- [x] done` (checkbox, tap to toggle)

Explicitly out of scope: links, images, code spans/blocks, blockquotes,
numbered lists, tables, nested lists. Can be added later as additional regex
rules in `MarkdownStyler` if wanted — the architecture doesn't preclude it,
this spec just doesn't cover it.

Raw syntax markers (`#`, `**`, `*`, `- `) stay visible at all times, just
dimmed (small size + reduced-opacity color), rather than fully hiding and
reappearing based on cursor position. This was a deliberate simplicity
tradeoff: full hide-on-blur (Bear/Obsidian style) requires per-line cursor
tracking and reflow on every cursor move, with meaningfully more bug surface
(cursor jumps, marker reveal timing) for a feature whose whole point is "nice
to have" on a lightweight scratchpad.

## Architecture

No new dependency. Everything lives in two new files plus a small change to
existing wiring:

- **`app/olauncher/helper/MarkdownMatcher.kt`** — pure Kotlin, no Android
  imports. Scans a plain `String` with the five regex rules below and returns
  a list of typed matches (marker/content character ranges). Fully unit
  testable on the JVM, same as `DebouncerTest.kt`.

- **`app/olauncher/helper/MarkdownStyler.kt`** — thin Android layer. Takes the
  `Editable` bound to the scratchpad `EditText`, strips spans it previously
  applied (tagged so unrelated spans, e.g. selection highlight, are
  untouched), calls `MarkdownMatcher.findMatches` on the current text, and
  applies fresh spans per match. Wrapped in try/catch that silently no-ops on
  any exception — styling is cosmetic, a bug in it must never crash the
  launcher home screen or corrupt the saved text. Verified manually
  on-device (see Testing).

- **`app/olauncher/ui/MarkdownEditText.kt`** — thin `EditText` subclass,
  replaces the plain `EditText` at `fragment_home.xml:24`. Overrides
  `onTouchEvent` to detect taps landing on a checkbox glyph and toggle
  `[ ]`↔`[x]` directly in the `Editable` before falling through to
  `super.onTouchEvent` for normal text editing.

- **`HomeFragment.initScratchpad()`** (`HomeFragment.kt:264`) — the existing
  `addTextChangedListener` gains a call to `MarkdownStyler.apply(editable)` in
  `afterTextChanged`, alongside the existing debounced save. Also called once
  on load, right after `binding.scratchpad?.setText(prefs.scratchpadText)`, so
  styling appears immediately on fragment (re)create, not just after the next
  edit.

## Data flow

`Prefs.scratchpadText` remains the single source of truth and stores raw
markdown text, completely unchanged from today. Spans are never persisted —
they're pure `Editable` decoration, rebuilt from raw text every time
`MarkdownStyler.apply` runs (on load, and on every `afterTextChanged`).
Because styling only calls `setSpan`/`removeSpan` and never `setText`, the
cursor position is preserved automatically — no manual cursor save/restore
needed.

## Syntax rules

| Syntax | Match | Spans applied |
|---|---|---|
| `# Header` … `###### Header` | line starts with 1-6 `#` + space | `RelativeSizeSpan` (larger for fewer `#`), `StyleSpan(BOLD)` on header text; `#`+space dimmed (`RelativeSizeSpan(0.7)` + `ForegroundColorSpan` at reduced alpha) |
| `**bold**` | `\*\*(.+?)\*\*` | `StyleSpan(BOLD)` on inner text; `**` markers dimmed |
| `*italic*` | `\*(.+?)\*`, excluding ranges already matched as `**bold**` | `StyleSpan(ITALIC)` on inner text; `*` markers dimmed |
| `- item` | line starts `- ` and is not `- [ ] `/`- [x] ` | leading `- ` dimmed (no separate bullet glyph — the dimmed dash itself reads as the marker) |
| `- [ ] todo` | line starts `- [ ] ` | checkbox glyph via custom `ReplacementSpan` (unchecked box) drawn over the `[ ]` region |
| `- [x] done` | line starts `- [x] ` | checkbox glyph (checked box) over `[x]`; `StrikethroughSpan` over rest of line |

Matching runs against the full text on every change (acceptable at scratchpad
text volumes — this is a small note field, not a document editor).

## Checkbox tap-to-toggle

`MarkdownEditText.onTouchEvent`, on `ACTION_UP`: convert tap coordinates to a
text offset via `Layout.getOffsetForHorizontal`/`getLineForVertical`. If that
offset falls inside a checkbox `ReplacementSpan`'s character range, flip the
underlying `[ ]`↔`[x]` text at that range directly on the `Editable` (this
triggers `afterTextChanged` → `MarkdownStyler.apply` restyles it), and consume
the event (return `true`) so focus/cursor placement and the keyboard don't
also react to that tap. Any other tap falls through to
`super.onTouchEvent(event)` for normal cursor placement/selection.

## Error handling

Styling is purely cosmetic and offline (no I/O, no network). Failure mode is
"a line doesn't get styled this pass" — `MarkdownStyler.apply` is wrapped so
any exception is swallowed and logged, never propagated. The raw text in
`Prefs.scratchpadText` is unaffected regardless of styling success, since
saving and styling are independent operations on the same `Editable`.

## Testing

The project has no Robolectric dependency, and `android.text.*` classes
(`SpannableStringBuilder`, `StyleSpan`, etc.) throw in plain JUnit without it.
So the regex/range-matching logic is split into its own pure-Kotlin function
(operating on `String` in, plain data classes out — no Android types) so it's
unit-testable exactly like the existing `DebouncerTest.kt`. The
Android-span-application step that consumes those results is thin and
verified manually.

- Unit tests for the pure matcher: each rule in isolation, multiple rules
  mixed on one line, no false-positive styling on plain text, checkbox
  match reports the correct character ranges.
- Manual on-device check: type each syntax element and confirm live styling;
  tap checkboxes at various positions (glyph edge, text after glyph) and
  confirm only glyph taps toggle; type quickly across styled regions and
  confirm cursor never jumps.
