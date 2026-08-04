# Scratchpad Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fork Olauncher (upstream `tanujnotes/Olauncher`, tag `v6.7.19`, GPLv3) and add a persistent top-half scratchpad to the home screen, with the bottom half shrunk to a smaller app area.

**Architecture:** `HomeFragment` (`app/src/main/java/app/olauncher/ui/HomeFragment.kt`) is the real home screen, hosted directly by the nav graph's `mainFragment` destination — there is no `MainFragment`/`ViewPager2` wrapper in the current upstream source (that pattern only existed in an old branch and does not apply here). `fragment_home.xml`'s root `FrameLayout` (id `mainLayout`) stacks all widgets by absolute position with padding-based spacing. This plan re-nests it into a vertical `LinearLayout` with two 50/50-weighted halves: top half gets a new `EditText` (id `scratchpad`); bottom half gets the existing clock/date/screen-time/app-list content, compacted. Persistence follows the codebase's existing pattern exactly: a `Prefs` (`SharedPreferences`) property, but in its own prefs file so it can be excluded from cloud backup independently of the rest of Olauncher's settings.

**Tech Stack:** Kotlin, Android Views + ViewBinding, Jetpack Navigation (single-Activity, fragment-based), `SharedPreferences` via `Prefs.kt`, Gradle 8.11.1 + version catalog (`gradle/libs.versions.toml`), JDK 17, compileSdk/targetSdk 35, minSdk 24.

## Global Constraints

- License: fork stays **GPLv3** (inherited from Olauncher's `LICENSE`). Do not add an AGPL-3.0 header/license file — that's this user's default for *new* public repos, not for a GPLv3 fork.
- `applicationId` stays `app.olauncher` for v1 — no rebrand/rename. (Means this fork can't be installed alongside stock Olauncher on the same device; acceptable since this is a personal daily-driver replacement, not a Play Store release.)
- Landscape layout (`app/src/main/res/layout-land/fragment_home.xml`) is **out of scope** for v1 — left untouched, keeps the old whole-screen-apps layout in landscape.
- The "extreme end" unified emacs-style single-field mode from idea.md is **out of scope** for v1 (per the approved design spec).
- Gesture trade-off (inherent to putting a real `EditText` in the top half, not a bug to fix): the root `mainLayout`'s swipe/tap gesture listener (camera/dialer swipe, swipe-up-for-drawer, swipe-down-for-notifications, double-tap-lock, long-press-for-settings) only fires for touches the `EditText` doesn't consume. Touches starting inside the scratchpad area go to text editing instead. All these gestures remain fully available from the bottom half (both the shared `mainLayout` listener over any gaps, and the per-app `ViewSwipeTouchListener` on each `homeApp1`–`homeApp8` view).
- Clock/date/screen-time widgets move from the top of the screen into the bottom half (per user decision), and shrink: clock text size drops from `@dimen/time_size` (66sp) to `@dimen/text_small` (18sp, same as the date).
- No Robolectric/instrumentation test infrastructure is introduced. The project currently has zero test directories. This plan adds one narrow, justified exception — a pure-Kotlin `Debouncer` class gets a JVM unit test via `kotlinx-coroutines-test` (no Android framework classes involved). The new `Prefs.scratchpadText` property follows the exact same untested pattern as the other ~100 properties already in `Prefs.kt` and is verified manually in Task 7, consistent with how the rest of `Prefs.kt` has always been verified.

---

### Task 1: Import Olauncher v6.7.19 as the fork base

**Files:**
- Create: entire Olauncher source tree at repo root (`app/`, `gradle/`, `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `LICENSE`, `README.md`, `.gitignore` from upstream `app/.gitignore` merged in) — everything from `tanujnotes/Olauncher` at tag `v6.7.19`, excluding its `.git` history.
- Existing files `idea.md` and `docs/` are untouched and must not be overwritten (no path collisions — Olauncher's tree has no `idea.md` or `docs/`).

**Interfaces:**
- Produces: a buildable Android project at repo root, `applicationId "app.olauncher"`, `compileSdk 35`, Gradle 8.11.1, so every later task can reference exact existing file paths (`app/src/main/java/app/olauncher/...`, `app/src/main/res/...`).

- [ ] **Step 1: Clone upstream at the pinned tag into a scratch directory**

```bash
git clone --branch v6.7.19 --depth 1 https://github.com/tanujnotes/Olauncher.git /tmp/olauncher-import
```

- [ ] **Step 2: Copy the source tree into the repo, excluding `.git`**

```bash
rsync -a --exclude='.git' /tmp/olauncher-import/ /Users/admin/src/scratchpad-launcher/
rm -rf /tmp/olauncher-import
```

- [ ] **Step 3: Verify the merge didn't touch `idea.md` or `docs/`**

```bash
git -C /Users/admin/src/scratchpad-launcher status --short
```

Expected: `idea.md` and `docs/` show no changes; every new file under `app/`, `gradle/`, etc. shows as untracked (`??`).

- [ ] **Step 4: Confirm the project builds before making any changes**

```bash
cd /Users/admin/src/scratchpad-launcher && ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If it fails on a clean import, stop and fix the environment (JDK 17 required — `java -version`) before proceeding to Task 2.

- [ ] **Step 5: Commit the import**

```bash
git add -A
git commit -m "$(cat <<'EOF'
Import Olauncher v6.7.19 as fork base

Upstream: https://github.com/tanujnotes/Olauncher, tag v6.7.19, GPLv3.
This is the unmodified base the scratchpad feature builds on top of.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1C4baL8SmSFDBKG64rcYk
EOF
)"
```

---

### Task 2: Reduce default home-app slot count

**Files:**
- Modify: `app/src/main/java/app/olauncher/data/Prefs.kt:163-165`

**Interfaces:**
- Produces: `Prefs.homeAppsNum: Int` now defaults to `3` (was `4`) on first run. Signature unchanged — no other file depends on the default value directly.

- [ ] **Step 1: Change the default**

In `app/src/main/java/app/olauncher/data/Prefs.kt`, change:

```kotlin
    var homeAppsNum: Int
        get() = prefs.getInt(HOME_APPS_NUM, 4)
        set(value) = prefs.edit { putInt(HOME_APPS_NUM, value).apply() }
```

to:

```kotlin
    var homeAppsNum: Int
        get() = prefs.getInt(HOME_APPS_NUM, 3)
        set(value) = prefs.edit { putInt(HOME_APPS_NUM, value).apply() }
```

- [ ] **Step 2: Build to confirm no breakage**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/app/olauncher/data/Prefs.kt
git commit -m "$(cat <<'EOF'
Reduce default home app slot count from 4 to 3

Bottom half is shrinking to make room for the scratchpad; fewer
default slots keeps the compacted app area uncluttered out of the box.
Users can still pick 0-8 via Settings, unchanged.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1C4baL8SmSFDBKG64rcYk
EOF
)"
```

---

### Task 3: Add `Debouncer` helper with a unit test

**Files:**
- Modify: `gradle/libs.versions.toml` — add coroutines version/library entries.
- Modify: `app/build.gradle` — add `kotlinx-coroutines-android` (main) and `kotlinx-coroutines-test` (test) dependencies.
- Create: `app/src/main/java/app/olauncher/helper/Debouncer.kt`
- Test: `app/src/test/java/app/olauncher/helper/DebouncerTest.kt`

**Interfaces:**
- Produces: `class Debouncer(scope: CoroutineScope, delayMillis: Long)` with `fun submit(action: suspend () -> Unit)` and `fun cancel()`. Task 6 (`HomeFragment.kt`) constructs one with `viewLifecycleOwner.lifecycleScope` and calls `submit { ... }` on every scratchpad text change, `cancel()` in `onPause`.

- [ ] **Step 1: Add coroutines to the version catalog**

In `gradle/libs.versions.toml`, add to `[versions]` (keep alphabetical with the existing entries):

```toml
kotlinxCoroutines = "1.9.0"
```

Add to `[libraries]`:

```toml
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
```

- [ ] **Step 2: Wire the new dependencies into `app/build.gradle`**

In the `dependencies { ... }` block, add:

```groovy
    implementation libs.kotlinx.coroutines.android
```

right after `implementation libs.recyclerview`, and add a new block after the closing of the main `dependencies` entries (before the final `}`):

```groovy

    testImplementation libs.kotlinx.coroutines.test
    testImplementation 'junit:junit:4.13.2'
```

- [ ] **Step 3: Write the failing test**

Create `app/src/test/java/app/olauncher/helper/DebouncerTest.kt`:

```kotlin
package app.olauncher.helper

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DebouncerTest {

    @Test
    fun `submit runs action once after the delay`() = runTest {
        val debouncer = Debouncer(this, 300L)
        var callCount = 0

        debouncer.submit { callCount++ }
        assertEquals(0, callCount)

        advanceTimeBy(301L)
        assertEquals(1, callCount)
    }

    @Test
    fun `rapid successive submits only run the last action`() = runTest {
        val debouncer = Debouncer(this, 300L)
        val results = mutableListOf<Int>()

        debouncer.submit { results.add(1) }
        advanceTimeBy(100L)
        debouncer.submit { results.add(2) }
        advanceTimeBy(100L)
        debouncer.submit { results.add(3) }
        advanceTimeBy(301L)

        assertEquals(listOf(3), results)
    }

    @Test
    fun `cancel prevents a pending action from running`() = runTest {
        val debouncer = Debouncer(this, 300L)
        var callCount = 0

        debouncer.submit { callCount++ }
        debouncer.cancel()
        advanceTimeBy(301L)

        assertEquals(0, callCount)
    }
}
```

- [ ] **Step 4: Run the test to verify it fails to compile (no `Debouncer` class yet)**

```bash
./gradlew :app:testDebugUnitTest --tests "app.olauncher.helper.DebouncerTest"
```

Expected: build fails — `unresolved reference: Debouncer`.

- [ ] **Step 5: Implement `Debouncer`**

Create `app/src/main/java/app/olauncher/helper/Debouncer.kt`:

```kotlin
package app.olauncher.helper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Debouncer(private val scope: CoroutineScope, private val delayMillis: Long) {
    private var job: Job? = null

    fun submit(action: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delayMillis)
            action()
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "app.olauncher.helper.DebouncerTest"
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle app/src/main/java/app/olauncher/helper/Debouncer.kt app/src/test/java/app/olauncher/helper/DebouncerTest.kt
git commit -m "$(cat <<'EOF'
Add Debouncer helper for scratchpad autosave

Small coroutine-based debounce utility, unit tested with
kotlinx-coroutines-test. Used by the scratchpad's save-on-type logic
(Task 6) to avoid writing to disk on every keystroke.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1C4baL8SmSFDBKG64rcYk
EOF
)"
```

---

### Task 4: Add scratchpad persistence to `Prefs` + exclude it from backup

**Files:**
- Modify: `app/src/main/java/app/olauncher/data/Prefs.kt`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/main/res/xml/backup_rules.xml`

**Interfaces:**
- Produces: `Prefs.scratchpadText: String` (get/set), backed by its own `SharedPreferences` file `app.olauncher.scratchpad`, default `""`. Task 6 (`HomeFragment.kt`) reads it on view creation and writes it on every debounced text change and in `onPause`.

- [ ] **Step 1: Add the dedicated prefs file and key constant**

In `app/src/main/java/app/olauncher/data/Prefs.kt`, right after line 10 (`private val PREFS_FILENAME = "app.olauncher"`), add:

```kotlin
    private val SCRATCHPAD_PREFS_FILENAME = "app.olauncher.scratchpad"
    private val SCRATCHPAD_TEXT = "SCRATCHPAD_TEXT"
```

- [ ] **Step 2: Add the second `SharedPreferences` instance**

Right after line 121 (`private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILENAME, 0)`), add:

```kotlin
    private val scratchpadPrefs: SharedPreferences = context.getSharedPreferences(SCRATCHPAD_PREFS_FILENAME, 0)
```

- [ ] **Step 3: Add the property**

Near the other `String` properties (e.g. right after `dailyWallpaperUrl`, around line 161), add:

```kotlin
    var scratchpadText: String
        get() = scratchpadPrefs.getString(SCRATCHPAD_TEXT, "").toString()
        set(value) = scratchpadPrefs.edit { putString(SCRATCHPAD_TEXT, value).apply() }
```

- [ ] **Step 4: Exclude the scratchpad prefs file from cloud backup**

In `app/src/main/res/xml/data_extraction_rules.xml`, change:

```xml
    <cloud-backup>
        <!-- TODO: Use <include> and <exclude> to control what is backed up.
        <include .../>
        <exclude .../>
        -->
    </cloud-backup>
```

to:

```xml
    <cloud-backup>
        <exclude domain="sharedpref" path="app.olauncher.scratchpad.xml" />
    </cloud-backup>
```

- [ ] **Step 5: Exclude it from the pre-Android-12 full-backup path too**

In `app/src/main/res/xml/backup_rules.xml`, change:

```xml
<full-backup-content>
    <!--
   <include domain="sharedpref" path="."/>
   <exclude domain="sharedpref" path="device.xml"/>
-->
</full-backup-content>
```

to:

```xml
<full-backup-content>
    <exclude domain="sharedpref" path="app.olauncher.scratchpad.xml" />
</full-backup-content>
```

- [ ] **Step 6: Build to confirm no breakage**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Manual check — confirm the prefs file is excluded from backup**

Install the debug build on a device/emulator, open it, this won't have UI to type into yet (Task 6 wires that up) — skip the runtime check here and fold it into Task 7's full manual pass, which checks the actual file on disk after Task 6 lands.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/olauncher/data/Prefs.kt app/src/main/res/xml/data_extraction_rules.xml app/src/main/res/xml/backup_rules.xml
git commit -m "$(cat <<'EOF'
Add scratchpad text persistence, excluded from backup

New Prefs.scratchpadText lives in its own SharedPreferences file
(app.olauncher.scratchpad) so it can be excluded from cloud/full
backup independently of the rest of Olauncher's settings — the
scratchpad must never leave the device.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1C4baL8SmSFDBKG64rcYk
EOF
)"
```

---

### Task 5: Split `fragment_home.xml` into top (scratchpad) / bottom (apps) halves

**Files:**
- Create: `app/src/main/res/values/strings.xml` addition (one new string).
- Modify: `app/src/main/res/layout/fragment_home.xml` (full re-nest).

**Interfaces:**
- Produces: a new view `binding.scratchpad` (`EditText`, id `scratchpad`) for Task 6 to reference. All existing view ids (`mainLayout`, `lock`, `dateTimeLayout`, `clock`, `date`, `tvScreenTime`, `homeAppsLayout`, `homeApp1`–`homeApp8`, `firstRunTips`, `setDefaultLauncher`) are preserved unchanged, just re-parented — no Kotlin changes needed for those in this task.

- [ ] **Step 1: Add the hint string**

In `app/src/main/res/values/strings.xml`, add (anywhere among the other `<string>` entries):

```xml
    <string name="scratchpad_hint">Write something…</string>
```

- [ ] **Step 2: Replace `fragment_home.xml`**

Replace the full contents of `app/src/main/res/layout/fragment_home.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/mainLayout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:animateLayoutChanges="true"
    android:orientation="vertical"
    tools:context=".ui.HomeFragment">

    <!-- Placeholder layout for locking screen-->
    <FrameLayout
        android:id="@+id/lock"
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:contentDescription="@string/lock_layout_description" />

    <!-- Top half: scratchpad -->
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <EditText
            android:id="@+id/scratchpad"
            style="@style/TextMedium"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@android:color/transparent"
            android:gravity="top|start"
            android:hint="@string/scratchpad_hint"
            android:inputType="textMultiLine|textCapSentences"
            android:overScrollMode="never"
            android:padding="24dp"
            android:textColorHint="?attr/primaryColorTrans50" />
    </FrameLayout>

    <!-- Bottom half: clock/date, screen time, app list -->
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <!-- Clock and calendar-->
        <LinearLayout
            android:id="@+id/dateTimeLayout"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="24dp"
            android:layout_marginTop="16dp"
            android:orientation="vertical"
            android:visibility="gone"
            tools:visibility="visible">

            <TextClock
                android:id="@+id/clock"
                style="@style/TextDefault"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:fontFamily="sans-serif-light"
                android:format12Hour="h:mm"
                android:textSize="@dimen/text_small"
                tools:text="02:34" />

            <TextView
                android:id="@+id/date"
                style="@style/TextDefault"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:format12Hour="EEE, dd MMM"
                android:format24Hour="EEE, dd MMM"
                android:paddingHorizontal="3dp"
                android:textSize="@dimen/date_size"
                tools:text="Thu, 30 Dec" />
        </LinearLayout>

        <!-- Screen time-->
        <androidx.appcompat.widget.AppCompatTextView
            android:id="@+id/tvScreenTime"
            style="@style/TextSmall"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="end"
            android:layout_marginHorizontal="10dp"
            android:layout_marginTop="16dp"
            android:gravity="center"
            android:padding="10dp"
            android:textSize="@dimen/date_size"
            android:visibility="gone"
            tools:text="2h 11m"
            tools:visibility="visible" />

        <!-- Home apps-->
        <LinearLayout
            android:id="@+id/homeAppsLayout"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center_vertical"
            android:orientation="vertical"
            android:paddingHorizontal="24dp"
            android:paddingTop="56dp"
            android:paddingBottom="24dp">

            <TextView
                android:id="@+id/homeApp1"
                style="@style/TextLarge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:focusable="true"
                android:hint="@string/app"
                android:paddingVertical="@dimen/home_app_padding_vertical"
                android:tag="@string/tag_1"
                android:visibility="gone"
                tools:visibility="visible" />

            <TextView
                android:id="@+id/homeApp2"
                style="@style/TextLarge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:focusable="true"
                android:hint="@string/app"
                android:paddingVertical="@dimen/home_app_padding_vertical"
                android:tag="@string/tag_2"
                android:visibility="gone"
                tools:visibility="visible" />

            <TextView
                android:id="@+id/homeApp3"
                style="@style/TextLarge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:focusable="true"
                android:hint="@string/app"
                android:paddingVertical="@dimen/home_app_padding_vertical"
                android:tag="@string/tag_3"
                android:visibility="gone"
                tools:visibility="visible" />

            <TextView
                android:id="@+id/homeApp4"
                style="@style/TextLarge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:focusable="true"
                android:hint="@string/app"
                android:paddingVertical="@dimen/home_app_padding_vertical"
                android:tag="@string/tag_4"
                android:visibility="gone"
                tools:visibility="visible" />

            <TextView
                android:id="@+id/homeApp5"
                style="@style/TextLarge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:focusable="true"
                android:hint="@string/app"
                android:paddingVertical="@dimen/home_app_padding_vertical"
                android:tag="@string/tag_5"
                android:visibility="gone"
                tools:visibility="visible" />

            <TextView
                android:id="@+id/homeApp6"
                style="@style/TextLarge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:focusable="true"
                android:hint="@string/app"
                android:paddingVertical="@dimen/home_app_padding_vertical"
                android:tag="@string/tag_6"
                android:visibility="gone"
                tools:visibility="visible" />

            <TextView
                android:id="@+id/homeApp7"
                style="@style/TextLarge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:focusable="true"
                android:hint="@string/app"
                android:paddingVertical="@dimen/home_app_padding_vertical"
                android:tag="@string/tag_7"
                android:visibility="gone"
                tools:visibility="visible" />

            <TextView
                android:id="@+id/homeApp8"
                style="@style/TextLarge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:focusable="true"
                android:hint="@string/app"
                android:paddingVertical="@dimen/home_app_padding_vertical"
                android:tag="@string/tag_8"
                android:visibility="gone"
                tools:visibility="visible" />

        </LinearLayout>

        <TextView
            android:id="@+id/firstRunTips"
            style="@style/TextSmall"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom"
            android:layout_marginStart="24dp"
            android:layout_marginEnd="8dp"
            android:layout_marginBottom="16dp"
            android:text="@string/swipe_up_for_apps"
            android:visibility="gone"
            tools:visibility="visible" />

        <!-- Set default launcher -->
        <TextView
            android:id="@+id/setDefaultLauncher"
            style="@style/TextMedium"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom|center_horizontal"
            android:layout_marginHorizontal="20dp"
            android:layout_marginBottom="16dp"
            android:ellipsize="end"
            android:maxLines="1"
            android:text="@string/set_as_default_launcher_u"
            android:visibility="gone" />
    </FrameLayout>
</LinearLayout>
```

Note: `paddingTop`/`paddingBottom` on `homeAppsLayout` and the `marginBottom` on `firstRunTips`/`setDefaultLauncher` are first-pass estimates for the now-much-shorter bottom half — Task 7's manual pass is where these get eyeballed and adjusted on a real device/emulator.

- [ ] **Step 3: Build to confirm no breakage**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` (ViewBinding regenerates `FragmentHomeBinding` with the new `scratchpad` field; all other `binding.xxx` references in `HomeFragment.kt` keep resolving since none of the existing ids changed).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/fragment_home.xml app/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
Split home screen into scratchpad (top) / apps (bottom) halves

Re-nests fragment_home.xml's previously absolutely-stacked children
into a 50/50 vertical split. Top half gets a new borderless EditText
(id scratchpad). Bottom half keeps the existing clock/date/screen-time/
app-list content, all ids unchanged, with clock/date compacted per
design decision (shrunk, moved off the top of the screen).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1C4baL8SmSFDBKG64rcYk
EOF
)"
```

---

### Task 6: Wire scratchpad load/save into `HomeFragment`

**Files:**
- Modify: `app/src/main/java/app/olauncher/ui/HomeFragment.kt`

**Interfaces:**
- Consumes: `Prefs.scratchpadText: String` (Task 4), `Debouncer(scope: CoroutineScope, delayMillis: Long)` with `.submit(action: suspend () -> Unit)` / `.cancel()` (Task 3), `binding.scratchpad: EditText` (Task 5).
- Produces: nothing new consumed elsewhere — this is the last piece of the feature.

- [ ] **Step 1: Add the imports**

In `app/src/main/java/app/olauncher/ui/HomeFragment.kt`, add to the import list (alphabetical, alongside the existing ones):

```kotlin
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import app.olauncher.helper.Debouncer
```

- [ ] **Step 2: Add the `scratchpadDebouncer` field**

Right after the existing field declarations (after `private lateinit var deviceManager: DevicePolicyManager` at line 56), add:

```kotlin
    private lateinit var scratchpadDebouncer: Debouncer
```

- [ ] **Step 3: Initialize it and wire the scratchpad in `onViewCreated`**

In `onViewCreated`, right after the existing `deviceManager = ...` assignment (line 73) and before `initObservers()` (line 75), add:

```kotlin
        scratchpadDebouncer = Debouncer(viewLifecycleOwner.lifecycleScope, 300L)
        initScratchpad()
```

- [ ] **Step 4: Add the `initScratchpad` function**

Add this new private function, right after `initClickListeners()` (after line 256, before `setHomeAlignment`):

```kotlin
    private fun initScratchpad() {
        binding.scratchpad.setText(prefs.scratchpadText)
        binding.scratchpad.addTextChangedListener(afterTextChanged = { editable ->
            val text = editable?.toString().orEmpty()
            scratchpadDebouncer.submit { prefs.scratchpadText = text }
        })
    }
```

- [ ] **Step 5: Flush on pause so a process kill never drops the last keystrokes**

Add this override right before `onDestroyView` (before line 731):

```kotlin
    override fun onPause() {
        super.onPause()
        scratchpadDebouncer.cancel()
        prefs.scratchpadText = binding.scratchpad.text.toString()
    }
```

- [ ] **Step 6: Build**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/olauncher/ui/HomeFragment.kt
git commit -m "$(cat <<'EOF'
Wire scratchpad load/save into HomeFragment

Loads Prefs.scratchpadText into the EditText on view creation, saves
on every text change debounced 300ms via Debouncer, and flushes
immediately on onPause so process death mid-edit can't drop text.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01W1C4baL8SmSFDBKG64rcYk
EOF
)"
```

---

### Task 7: Manual on-device verification pass

**Files:** none — this task only runs the app and checks behavior. No commit at the end unless a bug is found and fixed (in which case, fix it in the relevant task's file, commit that fix separately with its own descriptive message).

- [ ] **Step 1: Install on a device or emulator**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Set as default launcher and confirm layout**

Press the home button, follow the "set as default launcher" prompt. Confirm: top half is the scratchpad (blank, hint text "Write something…" visible), bottom half shows 3 empty app slots (long-press to assign apps) plus clock/date near the top of the bottom half.

- [ ] **Step 3: Legibility check across wallpapers**

Set three different wallpapers in turn — solid dark, solid light, a busy/high-contrast photo. Type text into the scratchpad each time. Confirm the shadowed text (`@style/TextMedium`, inherited `TextDefault` shadow) stays readable on all three without any background box behind it.

- [ ] **Step 4: Reboot-survival check**

Type distinctive text into the scratchpad (e.g. "reboot-test-12345"). Reboot the device. Reopen the launcher. Confirm the text is still there.

- [ ] **Step 5: No-leak check**

```bash
adb shell run-as app.olauncher cat /data/data/app.olauncher/shared_prefs/app.olauncher.scratchpad.xml
```

Expected: file exists and contains the typed text. Then confirm backup exclusion:

```bash
adb shell bmgr backupnow app.olauncher
adb shell dumpsys backup | grep -A5 app.olauncher
```

(Exact output format varies by Android version/backup transport — the goal is confirming no error implies the exclude rule was rejected; a full audit of transport-level payload contents is out of scope for this manual pass. If the device has no backup transport configured, skip this sub-check — the rule is a manifest-level declaration, verified by Task 4's XML being syntactically valid, which the Task 4 build already confirmed.)

- [ ] **Step 6: Gesture sanity check**

From the bottom half (over an app label or a gap), confirm swipe-up still opens the app drawer, swipe-down still opens notifications/search, swipe-left/right still trigger camera/dialer shortcuts (if configured), double-tap still locks (if lock mode is on), and long-press still opens Settings. Confirm that starting these same gestures from inside the scratchpad text area instead edits/selects text, as expected per the documented trade-off.

- [ ] **Step 7: Adjust spacing if needed**

If the bottom half looks cramped or the clock/date/first-run-tip text overlaps the app list on the test device, adjust the margin/padding values from Task 5 (`homeAppsLayout` padding, `firstRunTips`/`setDefaultLauncher` margins) directly in `fragment_home.xml` and re-run Steps 1-2. Commit any adjustment as its own small commit, e.g. `Tune bottom-half spacing after on-device check`.
