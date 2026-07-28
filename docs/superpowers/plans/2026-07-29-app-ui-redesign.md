# UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the existing Compose UI (Home, Import, Settings, Route editor, Onboarding) within Material 3 for visual hierarchy, status semantics, and real empty/loading states — plus one new capability: a Home "re-scan last 24h" action with an in-app result.

**Architecture:** Pure Material 3 with dynamic color retained. Two cross-cutting systems are added first: (1) a `StatusColors` theme extension + a pure `MessageStatus → SemanticColor` mapping, and (2) a backfill-telemetry seam so a running/finished backfill can drive in-app UI (Home re-scan result + Import progress), not just a notification. Everything else re-lays-out existing, already-tested ViewModels; new logic lives in ViewModels and pure helpers that are unit-tested against real Room DBs and capturing fakes (the codebase's established pattern). Composables consume those tested seams and are not unit-tested (there is no Compose UI-test harness, matching the existing screens).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3, BOM `2026.05.01`), Hilt, Room, WorkManager, Coroutines. Tests: JUnit4 + Robolectric 4.15.1 + `kotlinx-coroutines-test` + `androidx.work:work-testing` (all JVM unit tests under `app/src/test`).

## Global Constraints

- **Material 3 only.** No new UI dependency. Specifically: **no `androidx.navigation`** — extend the existing hand-rolled `Screen` stack in `PennyWiseApp`.
- **Dynamic color (Material You) is retained** on API 31+, baseline scheme below (see `PennyWiseTheme`).
- **No changes to the capture pipeline (parse/classify/post), the Room schema, or the YNAB client.** Adding WorkManager `setProgress`/output-data plumbing to `BackfillWorker` and read-only count queries to `SnapshotDao` is allowed (it is UI telemetry / read glue, not pipeline or schema change).
- **Status colors are derived to be legible against both dynamic light and dynamic dark schemes.** Do **not** hardcode the illustrative mockup hexes. A `StatusColors` holder provided through the theme is the expected shape.
- **Currency-mismatch shares the amber "warning" treatment in lists but is NOT its own Home stat tile** (rare edge case) — decision confirmed here, do not add a fourth tile.
- **Five real statuses, fixed set** (`MessageStatus`, do not extend): `POSTED`, `SKIPPED_UNROUTED`, `SKIPPED_CURRENCY_MISMATCH`, `SKIPPED_NON_TRANSACTION`, `FAILED`.
- **Semantic color map:** `POSTED`→success (green), `SKIPPED_UNROUTED`→warning (amber), `SKIPPED_CURRENCY_MISMATCH`→warning (amber), `FAILED`→error (red), `SKIPPED_NON_TRANSACTION`→neutral (grey).
- **Tests run on the JVM** via Robolectric (`@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])`); ViewModel tests use a real in-memory Room DB (`Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`) and capturing fake seams — never a mirror of the VM. Build/verify command for the whole suite: `./gradlew :app:testDebugUnitTest`. For a single class: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.<Class>"`.

---

## File Structure

New files:
- `app/src/main/kotlin/com/pennywiseai/ynab/ui/theme/StatusColors.kt` — semantic color holder + `LocalStatusColors` + light/dark palettes.
- `app/src/main/kotlin/com/pennywiseai/ynab/ui/common/StatusSemantics.kt` — pure `SemanticColor` enum + `semanticColor(status)`, composable color resolvers, `StatusPill`.
- `app/src/main/kotlin/com/pennywiseai/ynab/ui/common/TimeText.kt` — pure `relativeTime` / `absoluteTime`.
- `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillRun.kt` — `sealed interface BackfillRun`.
- `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillObserver.kt` + `BackfillCanceller.kt` — telemetry/cancel seams.
- `app/src/main/kotlin/com/pennywiseai/ynab/ui/home/` — `HomeScreen.kt`, `HomeViewModel.kt` (renamed from `ui/history`, plus `MessageRetrier`).

Modified files:
- `ui/theme/Theme.kt` (provide `LocalStatusColors`), `ui/nav/Screen.kt` (`History`→`Home`), `ui/PennyWiseApp.kt` (tab rename + Home wiring), `ui/rules/UiModule.kt` (new provides + moved `MessageRetrier` package).
- `capture/CaptureScheduler.kt` (`backfillRun()` replaces `backfillStatus()`), `capture/BackfillWorker.kt` (publish progress + output).
- `data/local/dao/SnapshotDao.kt` (count queries), `ui/settings/SettingsViewModel.kt` + `SettingsScreen.kt`, `ui/rules/RulesList` (in `RulesScreen.kt`), `ui/rules/RuleEditorScreen.kt`, `ui/onboarding/OnboardingScreen.kt`, `ui/backfill/BackfillScreen.kt` + `BackfillViewModel.kt`.

Deleted files:
- `ui/history/HistoryScreen.kt`, `ui/history/HistoryViewModel.kt` (moved to `ui/home`), `app/src/test/kotlin/.../ui/history/HistoryViewModelTest.kt` (moved to `ui/home`).

---

### Task 1: Status color system (theme extension + pure semantic mapping)

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/ui/theme/StatusColors.kt`
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/ui/common/StatusSemantics.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/theme/Theme.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/ui/common/StatusSemanticsTest.kt`

**Interfaces:**
- Produces: `enum class SemanticColor { SUCCESS, WARNING, ERROR, NEUTRAL }`; `fun semanticColor(status: MessageStatus): SemanticColor`; `@Composable fun statusContainerColor(status): Color`; `@Composable fun statusContentColor(status): Color`; `@Composable fun StatusPill(status: MessageStatus, modifier: Modifier = Modifier)`. `data class StatusColors(...)`; `val LocalStatusColors: ProvidableCompositionLocal<StatusColors>`; `fun statusColors(darkTheme: Boolean): StatusColors`.

- [ ] **Step 1: Write the failing test for the pure semantic mapping**

Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/common/StatusSemanticsTest.kt`:

```kotlin
package com.pennywiseai.ynab.ui.common

import com.pennywiseai.ynab.data.local.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusSemanticsTest {

    @Test
    fun `posted is success`() {
        assertEquals(SemanticColor.SUCCESS, semanticColor(MessageStatus.POSTED))
    }

    @Test
    fun `unrouted and currency-mismatch are both warnings`() {
        assertEquals(SemanticColor.WARNING, semanticColor(MessageStatus.SKIPPED_UNROUTED))
        assertEquals(SemanticColor.WARNING, semanticColor(MessageStatus.SKIPPED_CURRENCY_MISMATCH))
    }

    @Test
    fun `failed is error`() {
        assertEquals(SemanticColor.ERROR, semanticColor(MessageStatus.FAILED))
    }

    @Test
    fun `non-transaction noise is neutral`() {
        assertEquals(SemanticColor.NEUTRAL, semanticColor(MessageStatus.SKIPPED_NON_TRANSACTION))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.common.StatusSemanticsTest"`
Expected: FAIL — `SemanticColor` / `semanticColor` unresolved (compile error).

- [ ] **Step 3: Create the StatusColors theme extension**

Create `app/src/main/kotlin/com/pennywiseai/ynab/ui/theme/StatusColors.kt`:

```kotlin
package com.pennywiseai.ynab.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors M3 has no role for (success / warning). Error stays on the
 * built-in `colorScheme.error`. These are FIXED accessible green/amber tones chosen to stay
 * legible over both dynamic light and dynamic dark surfaces — NOT the illustrative mockup
 * hexes. Two palettes; [statusColors] picks by theme. Provided via [LocalStatusColors].
 */
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

// Green ~ M3 tonal palette tones 40/100/90/10 (light) and 80/20/30/90 (dark).
// Amber/brown-orange chosen with the same contrast targets.
private val LightStatusColors = StatusColors(
    success = Color(0xFF2E6B33),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFB2F0B4),
    onSuccessContainer = Color(0xFF00210A),
    warning = Color(0xFF7A5900),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF261A00),
)

private val DarkStatusColors = StatusColors(
    success = Color(0xFF97D89A),
    onSuccess = Color(0xFF003914),
    successContainer = Color(0xFF14531F),
    onSuccessContainer = Color(0xFFB2F0B4),
    warning = Color(0xFFF4BD48),
    onWarning = Color(0xFF412D00),
    warningContainer = Color(0xFF5D4200),
    onWarningContainer = Color(0xFFFFDEA6),
)

fun statusColors(darkTheme: Boolean): StatusColors =
    if (darkTheme) DarkStatusColors else LightStatusColors

/** Absent an explicit provider, default to the light palette (theme always provides one). */
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
```

- [ ] **Step 4: Provide LocalStatusColors from the theme**

In `app/src/main/kotlin/com/pennywiseai/ynab/ui/theme/Theme.kt`, replace the final `MaterialTheme(...)` call so it also provides the status palette. New file body:

```kotlin
package com.pennywiseai.ynab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * The app's single Material 3 theme. Uses dynamic color on API 31+ (personal tool —
 * matching the device wallpaper is the cheapest way to look native) and falls back to
 * the M3 baseline scheme below 31. Also provides [LocalStatusColors] — the success/warning
 * semantic colors M3's scheme lacks — selected for the active light/dark mode.
 */
@Composable
fun PennyWiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalStatusColors provides statusColors(darkTheme)) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
```

- [ ] **Step 5: Create the pure mapping + composable resolvers + StatusPill**

Create `app/src/main/kotlin/com/pennywiseai/ynab/ui/common/StatusSemantics.kt`:

```kotlin
package com.pennywiseai.ynab.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.ui.theme.LocalStatusColors

/** The four semantic buckets the five real statuses collapse into. */
enum class SemanticColor { SUCCESS, WARNING, ERROR, NEUTRAL }

/** Pure status -> semantic bucket (see Global Constraints). Unit-tested in StatusSemanticsTest. */
fun semanticColor(status: MessageStatus): SemanticColor = when (status) {
    MessageStatus.POSTED -> SemanticColor.SUCCESS
    MessageStatus.SKIPPED_UNROUTED -> SemanticColor.WARNING
    MessageStatus.SKIPPED_CURRENCY_MISMATCH -> SemanticColor.WARNING
    MessageStatus.FAILED -> SemanticColor.ERROR
    MessageStatus.SKIPPED_NON_TRANSACTION -> SemanticColor.NEUTRAL
}

@Composable
fun statusContainerColor(status: MessageStatus): Color = when (semanticColor(status)) {
    SemanticColor.SUCCESS -> LocalStatusColors.current.successContainer
    SemanticColor.WARNING -> LocalStatusColors.current.warningContainer
    SemanticColor.ERROR -> MaterialTheme.colorScheme.errorContainer
    SemanticColor.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
fun statusContentColor(status: MessageStatus): Color = when (semanticColor(status)) {
    SemanticColor.SUCCESS -> LocalStatusColors.current.onSuccessContainer
    SemanticColor.WARNING -> LocalStatusColors.current.onWarningContainer
    SemanticColor.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    SemanticColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Compact status chip: filled container + label, colored by [semanticColor]. */
@Composable
fun StatusPill(status: MessageStatus, modifier: Modifier = Modifier) {
    Text(
        text = statusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        color = statusContentColor(status),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(statusContainerColor(status))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.common.StatusSemanticsTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/ui/theme/StatusColors.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/theme/Theme.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/common/StatusSemantics.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/common/StatusSemanticsTest.kt
git commit -m "feat: status color system — StatusColors theme extension + semantic status mapping + StatusPill"
```

---

### Task 2: Relative/absolute time helpers (Home header)

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/ui/common/TimeText.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/ui/common/TimeTextTest.kt`

**Interfaces:**
- Produces: `fun relativeTime(nowMillis: Long, thenMillis: Long): String`; `fun absoluteTime(thenMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/common/TimeTextTest.kt`:

```kotlin
package com.pennywiseai.ynab.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class TimeTextTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `under a minute reads just now`() {
        assertEquals("just now", relativeTime(nowMillis = 10_000, thenMillis = 10_000))
        assertEquals("just now", relativeTime(nowMillis = 59_000, thenMillis = 0))
    }

    @Test
    fun `minutes hours and days are singular and plural`() {
        assertEquals("1 minute ago", relativeTime(nowMillis = minute, thenMillis = 0))
        assertEquals("2 minutes ago", relativeTime(nowMillis = 2 * minute, thenMillis = 0))
        assertEquals("1 hour ago", relativeTime(nowMillis = hour, thenMillis = 0))
        assertEquals("3 hours ago", relativeTime(nowMillis = 3 * hour, thenMillis = 0))
        assertEquals("1 day ago", relativeTime(nowMillis = day, thenMillis = 0))
        assertEquals("5 days ago", relativeTime(nowMillis = 5 * day, thenMillis = 0))
    }

    @Test
    fun `absoluteTime renders wall-clock time in the given zone`() {
        // 1970-01-01T09:12:00 in UTC = 9*3600 + 12*60 = 33120 s = 33_120_000 ms.
        assertEquals("9:12 AM", absoluteTime(thenMillis = 33_120_000L, zone = ZoneId.of("UTC")))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.common.TimeTextTest"`
Expected: FAIL — `relativeTime` / `absoluteTime` unresolved.

- [ ] **Step 3: Implement the helpers**

Create `app/src/main/kotlin/com/pennywiseai/ynab/ui/common/TimeText.kt`:

```kotlin
package com.pennywiseai.ynab.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/**
 * Coarse "time since" for the Home header ("2 minutes ago"). Pure so it is unit-tested
 * without a clock: the caller passes both `now` and `then`. Anything under a minute is
 * "just now"; otherwise the largest whole unit (minutes < hours < days) wins.
 */
fun relativeTime(nowMillis: Long, thenMillis: Long): String {
    val delta = (nowMillis - thenMillis).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> "just now"
        hours < 1 -> "$minutes ${plural(minutes, "minute")} ago"
        days < 1 -> "$hours ${plural(hours, "hour")} ago"
        else -> "$days ${plural(days, "day")} ago"
    }
}

/** Wall-clock time of an instant in [zone], e.g. "9:12 AM" — the header's secondary line. */
fun absoluteTime(thenMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(thenMillis).atZone(zone).format(CLOCK_FORMAT)

private fun plural(n: Long, unit: String) = if (n == 1L) unit else "${unit}s"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.common.TimeTextTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/ui/common/TimeText.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/common/TimeTextTest.kt
git commit -m "feat: relativeTime/absoluteTime helpers for the Home header"
```

---

### Task 3: Backfill telemetry seam (worker publishes progress + result; scheduler + observer)

This is the one genuinely new plumbing: a running/finished backfill must drive in-app UI (Home re-scan result in Task 4, Import progress in Task 5), not only the notification. The worker already computes `(done, total)` and a `BackfillSummary`; here it also publishes them via WorkManager `setProgress` / output data, and the scheduler maps `WorkInfo` to a `BackfillRun` model exposed through narrow seams.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillRun.kt`
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillObserver.kt`
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillCanceller.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillWorker.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/UiModule.kt`
- Modify (tests): `app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillWorkerTest.kt`, `app/src/test/kotlin/com/pennywiseai/ynab/capture/CaptureSchedulerTest.kt`

**Interfaces:**
- Consumes: `BackfillWorker` companion keys `KEY_FROM`/`KEY_TO`/`WORK_NAME`; `CaptureScheduler.enqueueBackfill`/`cancelBackfill`; `BackfillSummary(posted, skipped, failed)`.
- Produces: `sealed interface BackfillRun { data object Idle; data class Running(val done: Int, val total: Int); data class Done(val posted: Int, val skipped: Int, val failed: Int) }`; `CaptureScheduler.backfillRun(): Flow<BackfillRun>` (replaces `backfillStatus(): Flow<Boolean>`); `fun interface BackfillObserver { fun status(): Flow<BackfillRun> }`; `fun interface BackfillCanceller { fun cancel() }`. New `BackfillWorker` companion keys: `KEY_PROGRESS_DONE`, `KEY_PROGRESS_TOTAL`, `KEY_RESULT_POSTED`, `KEY_RESULT_SKIPPED`, `KEY_RESULT_FAILED`.

- [ ] **Step 1: Write the failing worker output-data test**

In `app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillWorkerTest.kt`, add this test (the existing tests stay unchanged):

```kotlin
    @Test
    fun `success output carries the run tally`() = runTest {
        val reader = FakeSmsInboxReader(listOf(RawSms("VM-HDFCBK", "HDFC a", 10L), RawSms("VM-HDFCBK", "HDFC b", 20L)))
        val result = worker(reader).doWork()

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, output.getInt(BackfillWorker.KEY_RESULT_POSTED, -1))
        assertEquals(0, output.getInt(BackfillWorker.KEY_RESULT_SKIPPED, -1))
        assertEquals(0, output.getInt(BackfillWorker.KEY_RESULT_FAILED, -1))
    }
```

(`ListenableWorker` is already imported in this file.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.capture.BackfillWorkerTest"`
Expected: FAIL — `KEY_RESULT_POSTED` unresolved, and (once added) the output ints are `-1` because the worker returns bare `Result.success()`.

- [ ] **Step 3: Publish progress + result from the worker**

In `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillWorker.kt`, add `import androidx.work.workDataOf`, then update `doWork()` to publish progress inside the throttle block and put the tally in the success output. Replace the body from `val messages = ...` onward:

```kotlin
        val messages = reader.read(from, to)
        var lastShown = 0
        val summary = processor.run(
            messages = messages,
            onProgress = { done, total ->
                if (done == total || done - lastShown >= progressStep(total)) {
                    lastShown = done
                    setForeground(notifier.backfillForegroundInfo(done, total))
                    // In-app telemetry: same throttle as the notification (Task 3). Observed
                    // by the Home re-scan result and the Import progress bar.
                    setProgress(workDataOf(KEY_PROGRESS_DONE to done, KEY_PROGRESS_TOTAL to total))
                }
            },
            isCancelled = { isStopped },
        )

        notifier.notifyBackfillSummary(summary)
        return Result.success(
            workDataOf(
                KEY_RESULT_POSTED to summary.posted,
                KEY_RESULT_SKIPPED to summary.skipped,
                KEY_RESULT_FAILED to summary.failed,
            ),
        )
```

And add the new keys to the companion object (alongside the existing ones):

```kotlin
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_RESULT_POSTED = "result_posted"
        const val KEY_RESULT_SKIPPED = "result_skipped"
        const val KEY_RESULT_FAILED = "result_failed"
```

- [ ] **Step 4: Run the worker test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.capture.BackfillWorkerTest"`
Expected: PASS (all tests, including the existing throttle/summary ones).

- [ ] **Step 5: Add the BackfillRun model**

Create `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillRun.kt`:

```kotlin
package com.pennywiseai.ynab.capture

/**
 * In-app view of the unique backfill work's lifecycle, mapped from WorkManager's WorkInfo by
 * [CaptureScheduler.backfillRun]. Drives the Import progress bar and the Home re-scan result.
 * [Done] carries the terminal tally from the worker's output data.
 */
sealed interface BackfillRun {
    data object Idle : BackfillRun
    data class Running(val done: Int, val total: Int) : BackfillRun
    data class Done(val posted: Int, val skipped: Int, val failed: Int) : BackfillRun
}
```

- [ ] **Step 6: Rewrite the CaptureScheduler test for the richer flow**

Replace the three `backfillStatus` tests in `app/src/test/kotlin/com/pennywiseai/ynab/capture/CaptureSchedulerTest.kt` with `backfillRun` equivalents (imports: add `com.pennywiseai.ynab.capture.BackfillRun`, `org.junit.Assert.assertEquals`, `org.junit.Assert.assertTrue`):

```kotlin
    @Test
    fun `backfillRun is Idle with no work`() = runTest {
        assertEquals(BackfillRun.Idle, scheduler.backfillRun().first())
    }

    @Test
    fun `backfillRun is Running while a backfill is enqueued`() = runTest {
        // The request carries a CONNECTED constraint; the test WorkManager leaves it
        // ENQUEUED (constraints unmet), so it maps to Running (progress not yet reported = 0/0).
        scheduler.enqueueBackfill(0L, 100L)
        assertTrue(scheduler.backfillRun().first() is BackfillRun.Running)
    }

    @Test
    fun `retryMessage enqueues the backfill work`() = runTest {
        scheduler.retryMessage(1234L)
        assertTrue(scheduler.backfillRun().first() is BackfillRun.Running)
    }
```

- [ ] **Step 7: Run the scheduler test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.capture.CaptureSchedulerTest"`
Expected: FAIL — `backfillRun` unresolved.

- [ ] **Step 8: Replace `backfillStatus()` with `backfillRun()` in the scheduler**

In `app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt`, add imports `androidx.work.Data` (not needed) — actually only replace the `backfillStatus()` method. Add `import androidx.work.WorkInfo` is already present. Replace the whole `backfillStatus()` function with:

```kotlin
    /**
     * Maps the unique backfill work to a [BackfillRun] for in-app UI. ENQUEUED/RUNNING/BLOCKED
     * -> Running(done,total) read from live progress data (0/0 until the worker reports).
     * SUCCEEDED -> Done(tally) from the worker's output data. FAILED/CANCELLED and "no work"
     * -> Idle. First (most recent) info wins; unique work usually has exactly one.
     */
    fun backfillRun(): Flow<BackfillRun> =
        workManager.getWorkInfosForUniqueWorkFlow(BackfillWorker.WORK_NAME).map { infos ->
            val info = infos.firstOrNull() ?: return@map BackfillRun.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                    BackfillRun.Running(
                        done = info.progress.getInt(BackfillWorker.KEY_PROGRESS_DONE, 0),
                        total = info.progress.getInt(BackfillWorker.KEY_PROGRESS_TOTAL, 0),
                    )
                WorkInfo.State.SUCCEEDED -> BackfillRun.Done(
                    posted = info.outputData.getInt(BackfillWorker.KEY_RESULT_POSTED, 0),
                    skipped = info.outputData.getInt(BackfillWorker.KEY_RESULT_SKIPPED, 0),
                    failed = info.outputData.getInt(BackfillWorker.KEY_RESULT_FAILED, 0),
                )
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> BackfillRun.Idle
            }
        }
```

- [ ] **Step 9: Run the scheduler test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.capture.CaptureSchedulerTest"`
Expected: PASS.

- [ ] **Step 10: Add the observer + canceller seams and wire them in UiModule**

Create `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillObserver.kt`:

```kotlin
package com.pennywiseai.ynab.ui.backfill

import com.pennywiseai.ynab.capture.BackfillRun
import kotlinx.coroutines.flow.Flow

/**
 * Narrow seam onto the backfill lifecycle so view models depend on this, not the whole
 * CaptureScheduler, and tests can drive it with a fake Flow. Provided by UiModule from
 * CaptureScheduler::backfillRun. Consumed by HomeViewModel (re-scan result) and BackfillViewModel.
 */
fun interface BackfillObserver {
    fun status(): Flow<BackfillRun>
}
```

Create `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillCanceller.kt`:

```kotlin
package com.pennywiseai.ynab.ui.backfill

/** Seam onto CaptureScheduler::cancelBackfill so BackfillViewModel stays unit-testable. */
fun interface BackfillCanceller {
    fun cancel()
}
```

In `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/UiModule.kt`, add providers (and the imports `com.pennywiseai.ynab.ui.backfill.BackfillObserver`, `com.pennywiseai.ynab.ui.backfill.BackfillCanceller`):

```kotlin
    @Provides
    @Singleton
    fun provideBackfillObserver(scheduler: CaptureScheduler): BackfillObserver =
        BackfillObserver { scheduler.backfillRun() }

    @Provides
    @Singleton
    fun provideBackfillCanceller(scheduler: CaptureScheduler): BackfillCanceller =
        BackfillCanceller { scheduler.cancelBackfill() }
```

- [ ] **Step 11: Run the full suite to confirm nothing else referenced `backfillStatus`**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. (If `BackfillViewModel` still fails to compile because it calls `scheduler.backfillStatus()`, that is fixed in Task 5; if the build breaks here, temporarily this task's compile depends on Task 5 — to keep tasks independent, leave `BackfillViewModel` using `scheduler` but change its `running` flow to `scheduler.backfillRun().map { it is BackfillRun.Running }` inline now, then Task 5 replaces the whole VM.) Apply that one-line stopgap in `BackfillViewModel.running` if needed so the module compiles.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillRun.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillWorker.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillObserver.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillCanceller.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/UiModule.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillWorkerTest.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/capture/CaptureSchedulerTest.kt
git commit -m "feat: backfill telemetry — worker publishes progress+tally; scheduler exposes BackfillRun via observer/canceller seams"
```

---

### Task 4: Home screen (rename History→Home; stats strip; re-scan; empty state)

Replaces the flat History list. Renames the `ui/history` package to `ui/home` and the nav tab, adds a stats strip (Posted/Failed/Unrouted counts + last-activity header) and the re-scan-last-24h action with an in-app result.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/ui/home/HomeViewModel.kt` (moved + extended from `ui/history/HistoryViewModel.kt`)
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/ui/home/HomeScreen.kt` (moved + redesigned from `ui/history/HistoryScreen.kt`)
- Delete: `app/src/main/kotlin/com/pennywiseai/ynab/ui/history/HistoryViewModel.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/ui/history/HistoryScreen.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/nav/Screen.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/ui/PennyWiseApp.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/UiModule.kt`
- Test: Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/home/HomeViewModelTest.kt` (moved + extended); Delete `app/src/test/kotlin/com/pennywiseai/ynab/ui/history/HistoryViewModelTest.kt`

**Interfaces:**
- Consumes: `ProcessedMessageDao.observeAll`/`observeByStatus`; `MessageStatus`; `BackfillEnqueuer` (Task 7 seam, already exists); `BackfillObserver`/`BackfillRun` (Task 3); `MessageRetrier`; `StatusPill`/`relativeTime`/`absoluteTime`/`formatAmount`.
- Produces: `data class HomeStats(val posted: Int, val failed: Int, val unrouted: Int, val lastActivityMillis: Long?)`; `sealed interface RescanState { data object Idle; data object Running; data class Result(val imported: Int) }`; `HomeViewModel.stats: StateFlow<HomeStats>`, `HomeViewModel.rescanState: StateFlow<RescanState>`, `HomeViewModel.rescan()`; `Screen.Home` (was `Screen.History`); `MessageRetrier` now in package `com.pennywiseai.ynab.ui.home`.

- [ ] **Step 1: Move the failing test into ui/home and add stats + rescan cases**

Delete `app/src/test/kotlin/com/pennywiseai/ynab/ui/history/HistoryViewModelTest.kt`. Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/home/HomeViewModelTest.kt`:

```kotlin
package com.pennywiseai.ynab.ui.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.ui.backfill.BackfillObserver
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var db: PennyWiseDatabase
    private val dispatcher = StandardTestDispatcher()

    private var capturedFrom: Long? = null
    private var capturedTo: Long? = null
    private val runFlow = MutableStateFlow<BackfillRun>(BackfillRun.Idle)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun row(id: String, status: MessageStatus, ts: Long) = ProcessedMessageEntity(
        importId = id, sender = "s", bankName = "HDFC Bank", last4 = "1234",
        amount = BigDecimal.ONE, currency = "INR", status = status, error = null, timestamp = ts,
    )

    private fun vm(now: Long = 10_000_000L) = HomeViewModel(
        dao = db.processedMessageDao(),
        retrier = MessageRetrier {},
        enqueuer = BackfillEnqueuer { from, to -> capturedFrom = from; capturedTo = to },
        observer = BackfillObserver { runFlow },
        now = { now },
    )

    @Test
    fun `stats count each status and report the newest timestamp`() = runTest {
        val dao = db.processedMessageDao()
        dao.upsert(row("a", MessageStatus.POSTED, ts = 100))
        dao.upsert(row("b", MessageStatus.POSTED, ts = 300))
        dao.upsert(row("c", MessageStatus.FAILED, ts = 200))
        dao.upsert(row("d", MessageStatus.SKIPPED_UNROUTED, ts = 150))
        dao.upsert(row("e", MessageStatus.SKIPPED_NON_TRANSACTION, ts = 250))
        val vm = vm()
        advanceUntilIdle()

        val stats = vm.stats.first { it.lastActivityMillis != null }
        assertEquals(2, stats.posted)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.unrouted) // non-transaction noise is NOT counted here
        assertEquals(300L, stats.lastActivityMillis)
    }

    @Test
    fun `rescan enqueues the last 24h window`() = runTest {
        val now = 100_000_000L
        vm(now = now).rescan()
        advanceUntilIdle()
        assertEquals(now - 24L * 60 * 60 * 1000, capturedFrom)
        assertEquals(now, capturedTo)
    }

    @Test
    fun `rescan surfaces the imported count once the run finishes`() = runTest {
        val vm = vm()
        vm.rescan()
        advanceUntilIdle()
        assertEquals(RescanState.Running, vm.rescanState.value)

        runFlow.value = BackfillRun.Running(done = 1, total = 3)
        advanceUntilIdle()
        runFlow.value = BackfillRun.Done(posted = 3, skipped = 0, failed = 0)
        advanceUntilIdle()

        assertEquals(RescanState.Result(imported = 3), vm.rescanState.value)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.home.HomeViewModelTest"`
Expected: FAIL — `HomeViewModel` / `RescanState` / `HomeStats` unresolved.

- [ ] **Step 3: Create HomeViewModel (moved + extended) and delete the old HistoryViewModel**

Create `app/src/main/kotlin/com/pennywiseai/ynab/ui/home/HomeViewModel.kt`:

```kotlin
package com.pennywiseai.ynab.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.ui.backfill.BackfillObserver
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Narrow seam over CaptureScheduler.retryMessage so the VM stays unit-testable. */
fun interface MessageRetrier {
    fun retry(timestamp: Long)
}

/** Home's top-of-screen health: per-status counts + the newest processed timestamp. */
data class HomeStats(
    val posted: Int,
    val failed: Int,
    val unrouted: Int,
    val lastActivityMillis: Long?,
)

/** Transient state of the Home ⟳ re-scan action. [Result] holds how many messages posted. */
sealed interface RescanState {
    data object Idle : RescanState
    data object Running : RescanState
    data class Result(val imported: Int) : RescanState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dao: ProcessedMessageDao,
    private val retrier: MessageRetrier,
    private val enqueuer: BackfillEnqueuer,
    private val observer: BackfillObserver,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _filter = MutableStateFlow<MessageStatus?>(null)
    val filter: StateFlow<MessageStatus?> = _filter

    val items: StateFlow<List<ProcessedMessageEntity>> =
        _filter.flatMapLatest { status ->
            if (status == null) dao.observeAll() else dao.observeByStatus(status)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Counts are derived from the full (unfiltered) stream so the tiles are stable while filtering. */
    val stats: StateFlow<HomeStats> =
        dao.observeAll().map { all ->
            HomeStats(
                posted = all.count { it.status == MessageStatus.POSTED },
                failed = all.count { it.status == MessageStatus.FAILED },
                unrouted = all.count { it.status == MessageStatus.SKIPPED_UNROUTED },
                lastActivityMillis = all.maxOfOrNull { it.timestamp },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats(0, 0, 0, null))

    private val _rescanState = MutableStateFlow<RescanState>(RescanState.Idle)
    val rescanState: StateFlow<RescanState> = _rescanState

    fun setFilter(status: MessageStatus?) { _filter.value = status }

    /** Manual retry, available on any FAILED row: re-drive its inbox window (idempotent). */
    fun retry(item: ProcessedMessageEntity) = retrier.retry(item.timestamp)

    /**
     * Re-scan the last 24 hours to catch anything the real-time receiver missed. Idempotent
     * (import_id dedup), so it is safe to tap repeatedly. Waits for THIS run to actually go
     * Running before accepting a Done, so a stale terminal WorkInfo replay can't short-circuit
     * the result to a previous import's tally.
     */
    fun rescan() {
        if (_rescanState.value == RescanState.Running) return
        _rescanState.value = RescanState.Running
        enqueuer.enqueue(now() - DAY_MILLIS, now())
        viewModelScope.launch {
            val done = observer.status()
                .dropWhile { it !is BackfillRun.Running }
                .first { it is BackfillRun.Done } as BackfillRun.Done
            _rescanState.value = RescanState.Result(imported = done.posted)
        }
    }

    /** Dismiss the transient re-scan result (tapping ⟳ again also resets it). */
    fun clearRescanResult() { _rescanState.value = RescanState.Idle }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
```

Delete `app/src/main/kotlin/com/pennywiseai/ynab/ui/history/HistoryViewModel.kt`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.home.HomeViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Update the MessageRetrier provider package in UiModule**

In `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/UiModule.kt`, change the two references `com.pennywiseai.ynab.ui.history.MessageRetrier` to `com.pennywiseai.ynab.ui.home.MessageRetrier`:

```kotlin
    @Provides
    @Singleton
    fun provideMessageRetrier(scheduler: CaptureScheduler): com.pennywiseai.ynab.ui.home.MessageRetrier =
        com.pennywiseai.ynab.ui.home.MessageRetrier { ts -> scheduler.retryMessage(ts) }
```

- [ ] **Step 6: Rename the nav destination History→Home**

In `app/src/main/kotlin/com/pennywiseai/ynab/ui/nav/Screen.kt`, rename the `History` data object to `Home`:

```kotlin
    sealed interface Tab : Screen
    data object Home : Tab
    data object Backfill : Tab
    data object Settings : Tab
```

- [ ] **Step 7: Create the redesigned HomeScreen and delete the old HistoryScreen**

Create `app/src/main/kotlin/com/pennywiseai/ynab/ui/home/HomeScreen.kt`:

```kotlin
package com.pennywiseai.ynab.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.ui.common.StatusPill
import com.pennywiseai.ynab.ui.common.absoluteTime
import com.pennywiseai.ynab.ui.common.formatAmount
import com.pennywiseai.ynab.ui.common.relativeTime
import com.pennywiseai.ynab.ui.common.statusLabel
import com.pennywiseai.ynab.ui.theme.LocalStatusColors

@Composable
fun HomeScreen(
    onMapRoute: (String, String?) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val rescan by viewModel.rescanState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        HomeHeader(
            lastActivityMillis = stats.lastActivityMillis,
            rescan = rescan,
            onRescan = viewModel::rescan,
        )
        StatStrip(
            stats = stats,
            filter = filter,
            onSelect = viewModel::setFilter,
        )
        HorizontalDivider()
        ListHeader(filter = filter, count = items.size, onClear = { viewModel.setFilter(null) })

        if (items.isEmpty()) {
            EmptyState(filtered = filter != null)
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(items, key = { it.importId }) { item ->
                    TransactionRow(
                        item = item,
                        onRetry = { viewModel.retry(item) },
                        onMapRoute = { onMapRoute(item.bankName, item.last4) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    lastActivityMillis: Long?,
    rescan: RescanState,
    onRescan: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("₹", style = MaterialTheme.typography.titleLarge) }

        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val primary = when {
                rescan is RescanState.Result && rescan.imported > 0 -> "Imported ${rescan.imported}"
                rescan is RescanState.Result -> "Checked · nothing new"
                lastActivityMillis == null -> "No activity yet"
                else -> "Last transaction · ${relativeTime(System.currentTimeMillis(), lastActivityMillis)}"
            }
            Text(primary, style = MaterialTheme.typography.titleMedium)
            if (lastActivityMillis != null) {
                Text(
                    "Today at ${absoluteTime(lastActivityMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (rescan is RescanState.Running) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRescan) {
                Icon(Icons.Filled.Refresh, contentDescription = "Re-scan last 24 hours")
            }
        }
    }
}

@Composable
private fun StatStrip(
    stats: HomeStats,
    filter: MessageStatus?,
    onSelect: (MessageStatus?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile("Posted", stats.posted, MessageStatus.POSTED, filter, onSelect, Modifier.weight(1f))
        StatTile("Failed", stats.failed, MessageStatus.FAILED, filter, onSelect, Modifier.weight(1f))
        StatTile("Unrouted", stats.unrouted, MessageStatus.SKIPPED_UNROUTED, filter, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(
    label: String,
    count: Int,
    status: MessageStatus,
    filter: MessageStatus?,
    onSelect: (MessageStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = filter == status
    val container: Color = when (status) {
        MessageStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        MessageStatus.SKIPPED_UNROUTED -> LocalStatusColors.current.warningContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(
                if (active) Modifier.border(
                    2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp),
                ) else Modifier,
            )
            .clickable { onSelect(if (active) null else status) }
            .padding(vertical = 12.dp, horizontal = 12.dp),
    ) {
        Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ListHeader(filter: MessageStatus?, count: Int, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (filter == null) "Recent" else "${statusLabel(filter)} · $count",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (filter != null) TextButton(onClick = onClear) { Text("Show all ✕") }
    }
}

@Composable
private fun TransactionRow(
    item: ProcessedMessageEntity,
    onRetry: () -> Unit,
    onMapRoute: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${item.bankName} ·${item.last4 ?: "----"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    formatAmount(item.amount, item.currency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(item.status)
        }
        item.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            if (item.status == MessageStatus.FAILED) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            if (item.status == MessageStatus.SKIPPED_UNROUTED) {
                TextButton(onClick = onMapRoute) { Text("Map this card →") }
            }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (filtered) "Nothing here" else "No transactions yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.padding(4.dp))
        Text(
            if (filtered) "Try a different filter." else "Bank texts will appear here as they arrive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

Delete `app/src/main/kotlin/com/pennywiseai/ynab/ui/history/HistoryScreen.kt`.

- [ ] **Step 8: Wire the Home tab in PennyWiseApp**

In `app/src/main/kotlin/com/pennywiseai/ynab/ui/PennyWiseApp.kt`: change the import `com.pennywiseai.ynab.ui.history.HistoryScreen` to `com.pennywiseai.ynab.ui.home.HomeScreen`; add `import androidx.compose.material.icons.filled.Home`; update the `MainShell` default tab, first `NavigationBarItem`, and the `when (tab)` branch:

- Default tab: `var tab by remember { mutableStateOf<Screen.Tab>(Screen.Home) }`
- First nav item:

```kotlin
                    NavigationBarItem(
                        selected = tab == Screen.Home,
                        onClick = { tab = Screen.Home },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Home") },
                    )
```

- Branch:

```kotlin
                    Screen.Home -> HomeScreen(
                        onMapRoute = { bank, last4 ->
                            pushed = Screen.RuleEditor(prefillBank = bank, prefillLast4 = last4)
                        },
                    )
```

- [ ] **Step 9: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (no references to `ui.history` remain; grep to confirm: `git grep -n "ui.history"` returns nothing).

- [ ] **Step 10: Commit**

```bash
git add -A app/src/main/kotlin/com/pennywiseai/ynab/ui/home \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/history \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/nav/Screen.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/PennyWiseApp.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/UiModule.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/home \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/history
git commit -m "feat: Home screen — rename History→Home, stats strip, re-scan last-24h with in-app result, empty state"
```

---

### Task 5: Import screen (quick-range chips + in-app progress)

Replaces the bare `DateRangePicker`. Adds quick-range chips (7/30/90/Custom), a determinate/indeterminate in-app progress bar with live count sourced from Task 3's telemetry, cancel, and a completion summary. `BackfillViewModel` is refactored onto the seams so it is unit-testable.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillViewModel.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillScreen.kt`
- Test: Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillViewModelTest.kt`; Modify `app/src/test/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillWindowTest.kt`

**Interfaces:**
- Consumes: `BackfillEnqueuer` (start), `BackfillCanceller` + `BackfillObserver` (Task 3), `BackfillRun`.
- Produces: `fun quickRangeMillis(nowMillis: Long, days: Int): Pair<Long, Long>` (in `BackfillViewModel.kt`, next to `inclusiveEndMillis`); `BackfillViewModel(enqueuer, canceller, observer, now)` with `run: StateFlow<BackfillRun>`, `startQuickRange(days: Int)`, `startCustom(fromMillis, toDateMillis)`, `cancel()`.

- [ ] **Step 1: Add the failing quick-range test**

In `app/src/test/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillWindowTest.kt`, add (keep the existing `inclusiveEndMillis` test):

```kotlin
    @Test
    fun `quickRangeMillis spans the last N days ending now`() {
        val now = 1_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertEquals(now - 7 * day to now, quickRangeMillis(now, 7))
        assertEquals(now - 30 * day to now, quickRangeMillis(now, 30))
        assertEquals(now - 90 * day to now, quickRangeMillis(now, 90))
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.backfill.BackfillWindowTest"`
Expected: FAIL — `quickRangeMillis` unresolved.

- [ ] **Step 3: Refactor BackfillViewModel onto the seams + add quickRangeMillis**

Replace `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillViewModel.kt`:

```kotlin
package com.pennywiseai.ynab.ui.backfill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The DateRangePicker returns the end date at midnight; the inbox read is DATE < to, so add
 *  one day to include the whole selected end day. */
fun inclusiveEndMillis(endDateMillis: Long): Long = endDateMillis + 24L * 60 * 60 * 1000

/** The [now - days, now] window a quick-range chip imports. Pure — unit-tested without a clock. */
fun quickRangeMillis(nowMillis: Long, days: Int): Pair<Long, Long> =
    (nowMillis - days.toLong() * 24 * 60 * 60 * 1000) to nowMillis

@HiltViewModel
class BackfillViewModel @Inject constructor(
    private val enqueuer: BackfillEnqueuer,
    private val canceller: BackfillCanceller,
    private val observer: BackfillObserver,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val run: StateFlow<BackfillRun> =
        observer.status().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackfillRun.Idle)

    fun startQuickRange(days: Int) {
        val (from, to) = quickRangeMillis(now(), days)
        enqueuer.enqueue(from, to)
    }

    fun startCustom(fromMillis: Long, toDateMillis: Long) =
        enqueuer.enqueue(fromMillis, inclusiveEndMillis(toDateMillis))

    fun cancel() = canceller.cancel()
}
```

- [ ] **Step 4: Run the window test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.backfill.BackfillWindowTest"`
Expected: PASS.

- [ ] **Step 5: Write the BackfillViewModel behavior test**

Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillViewModelTest.kt`:

```kotlin
package com.pennywiseai.ynab.ui.backfill

import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackfillViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var capturedFrom: Long? = null
    private var capturedTo: Long? = null
    private var cancelled = false
    private val runFlow = MutableStateFlow<BackfillRun>(BackfillRun.Idle)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(now: Long = 1_000_000_000L) = BackfillViewModel(
        enqueuer = BackfillEnqueuer { f, t -> capturedFrom = f; capturedTo = t },
        canceller = BackfillCanceller { cancelled = true },
        observer = BackfillObserver { runFlow },
        now = { now },
    )

    @Test
    fun `startQuickRange enqueues the last N days`() {
        val now = 5_000_000_000L
        vm(now = now).startQuickRange(30)
        assertEquals(now - 30L * 24 * 60 * 60 * 1000, capturedFrom)
        assertEquals(now, capturedTo)
    }

    @Test
    fun `startCustom adds a day to the end date`() {
        vm().startCustom(fromMillis = 100L, toDateMillis = 200L)
        assertEquals(100L, capturedFrom)
        assertEquals(200L + 24L * 60 * 60 * 1000, capturedTo)
    }

    @Test
    fun `cancel forwards to the canceller`() {
        vm().cancel()
        assertTrue(cancelled)
    }

    @Test
    fun `run reflects observer emissions`() = runTest(dispatcher) {
        val vm = vm()
        runFlow.value = BackfillRun.Running(done = 5, total = 10)
        assertEquals(BackfillRun.Running(5, 10), vm.run.first { it is BackfillRun.Running })
    }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.backfill.BackfillViewModelTest"`
Expected: PASS.

- [ ] **Step 7: Redesign BackfillScreen**

Replace `app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillScreen.kt`:

```kotlin
package com.pennywiseai.ynab.ui.backfill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.capture.BackfillRun

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackfillScreen(viewModel: BackfillViewModel = hiltViewModel()) {
    val run by viewModel.run.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Import past transactions", style = MaterialTheme.typography.titleLarge)
        Text(
            "Only messages matching a route are imported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        when (val r = run) {
            is BackfillRun.Running -> RunningState(r, onCancel = viewModel::cancel)
            is BackfillRun.Done -> {
                DoneState(r)
                Spacer(Modifier.height(12.dp))
                RangePicker(viewModel)
            }
            BackfillRun.Idle -> RangePicker(viewModel)
        }
    }
}

@Composable
private fun RunningState(run: BackfillRun.Running, onCancel: () -> Unit) {
    Text("Importing…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (run.total > 0) {
        LinearProgressIndicator(
            progress = { run.done.toFloat() / run.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${run.done} of ~${run.total}", style = MaterialTheme.typography.bodySmall)
    } else {
        // Fallback (design spec): no cheap total yet -> indeterminate bar + running tally.
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Scanning…", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "It keeps running if you leave this screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCancel) { Text("Cancel import") }
}

@Composable
private fun DoneState(run: BackfillRun.Done) {
    Text(
        "Done · ${run.posted} posted · ${run.skipped} skipped · ${run.failed} failed",
        style = MaterialTheme.typography.titleMedium,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePicker(viewModel: BackfillViewModel) {
    var showCustom by remember { mutableStateOf(false) }

    Text("HOW FAR BACK", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 30, 90).forEach { days ->
            FilterChip(
                selected = false,
                onClick = { viewModel.startQuickRange(days) },
                label = { Text("$days days") },
            )
        }
        FilterChip(
            selected = showCustom,
            onClick = { showCustom = true },
            label = { Text("Custom…") },
        )
    }
    Text(
        "Already-imported transactions are skipped, so running this twice is safe.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (showCustom) {
        val state = rememberDateRangePickerState()
        AlertDialog(
            onDismissRequest = { showCustom = false },
            confirmButton = {
                val from = state.selectedStartDateMillis
                val to = state.selectedEndDateMillis
                TextButton(
                    enabled = from != null && to != null,
                    onClick = {
                        if (from != null && to != null) viewModel.startCustom(from, to)
                        showCustom = false
                    },
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Cancel") } },
            text = { DateRangePicker(state = state) },
        )
    }
}
```

- [ ] **Step 8: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillViewModel.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillScreen.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillViewModelTest.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/backfill/BackfillWindowTest.kt
git commit -m "feat: Import screen — quick-range chips + determinate/indeterminate in-app progress + completion summary"
```

---

### Task 6: Settings screen (connection row + overflow; flat routes with dividers)

Replaces the single-scroll junk drawer. Adds a persistent "Connected · N budgets · M accounts" row (token field hidden behind "Replace token"), titled sections, and a flat route list with neutral avatars, thin dividers, and a broken-route hint line.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RulesScreen.kt` (`RulesList`)
- Test: Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/settings/SettingsViewModelConnectionTest.kt`; Modify `app/src/test/kotlin/com/pennywiseai/ynab/data/local/SnapshotDaoTest.kt`

**Interfaces:**
- Consumes: `SnapshotDao` (existing budgets/accounts tables), `BudgetEntity`/`AccountEntity`.
- Produces: `SnapshotDao.countBudgets(): Int`, `SnapshotDao.countAccounts(): Int`; `data class ConnectionInfo(val budgetCount: Int, val accountCount: Int)`; `SettingsViewModel.loadConnection()`, `SettingsViewModel.connection: StateFlow<ConnectionInfo?>`.

- [ ] **Step 1: Add the failing SnapshotDao count test**

First inspect `app/src/test/kotlin/com/pennywiseai/ynab/data/local/SnapshotDaoTest.kt` for the existing seeding helpers (it already builds `BudgetEntity`/`AccountEntity` rows and calls `replaceSnapshot`). Add:

```kotlin
    @Test
    fun `countBudgets and countAccounts reflect the snapshot`() = runTest {
        // Reuse this test's existing snapshot-seeding helper (replaceSnapshot with N budgets / M accounts).
        // Seed 2 budgets and 3 accounts, then:
        assertEquals(2, dao.countBudgets())
        assertEquals(3, dao.countAccounts())
    }
```

> Implementer note: match the seeding to whatever `SnapshotDaoTest` already provides (call its existing `budget(...)` / `account(...)` builders and `dao.replaceSnapshot(...)`). If no such helper exists, seed inline with two `BudgetEntity` and three `AccountEntity` rows via `replaceSnapshot`. Add `import org.junit.Assert.assertEquals` if absent.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.local.SnapshotDaoTest"`
Expected: FAIL — `countBudgets` / `countAccounts` unresolved.

- [ ] **Step 3: Add the count queries**

In `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt`, add:

```kotlin
    /** Total budgets in the current snapshot — the Settings "Connected · N budgets" row. */
    @Query("SELECT COUNT(*) FROM budgets")
    suspend fun countBudgets(): Int

    /** Total accounts across all budgets in the snapshot — the "· M accounts" part. */
    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun countAccounts(): Int
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.local.SnapshotDaoTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing SettingsViewModel connection test**

Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/settings/SettingsViewModelConnectionTest.kt`:

```kotlin
package com.pennywiseai.ynab.ui.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.data.remote.FakeYnabApi
import com.pennywiseai.ynab.data.repository.YnabRepository
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelConnectionTest {

    private lateinit var db: PennyWiseDatabase
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    private fun vm() = SettingsViewModel(
        repository = YnabRepository(
            FakeYnabApi(), db.snapshotDao(), db.mappingRuleDao(), FakeTokenStore(), FakePostingStateStore(),
        ),
        tokenStore = FakeTokenStore(),
        postingState = FakePostingStateStore(),
        mappingRuleDao = db.mappingRuleDao(),
        processedMessageDao = db.processedMessageDao(),
        enqueuer = BackfillEnqueuer { _, _ -> },
    )

    @Test
    fun `loadConnection reports snapshot budget and account counts`() = runTest(dispatcher) {
        db.snapshotDao().replaceSnapshot(
            budgets = listOf(
                BudgetEntity(id = "b1", name = "Personal", currencyCode = "INR"),
                BudgetEntity(id = "b2", name = "Business", currencyCode = "USD"),
            ),
            accounts = listOf(
                AccountEntity(id = "a1", budgetId = "b1", name = "Everyday", closed = false, deleted = false),
                AccountEntity(id = "a2", budgetId = "b1", name = "Savings", closed = false, deleted = false),
                AccountEntity(id = "a3", budgetId = "b2", name = "Checking", closed = false, deleted = false),
            ),
        )
        val vm = vm()
        vm.loadConnection()

        val info = vm.connection.first { it != null }!!
        assertEquals(2, info.budgetCount)
        assertEquals(3, info.accountCount)
    }
}
```

> Implementer note: confirm the exact constructor parameters of `BudgetEntity`/`AccountEntity` (read `data/local/entity/BudgetEntity.kt` and `AccountEntity.kt`) and match field names/types; the shape above mirrors the columns referenced by `SnapshotDao.getOpenAccounts` (`closed`, `deleted`, `budgetId`).

- [ ] **Step 6: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.settings.SettingsViewModelConnectionTest"`
Expected: FAIL — `loadConnection` / `connection` unresolved.

- [ ] **Step 7: Add connection state to SettingsViewModel**

In `app/src/main/kotlin/com/pennywiseai/ynab/ui/settings/SettingsViewModel.kt`, inject the `SnapshotDao`, add the model + state + loader. Add constructor param `private val snapshotDao: com.pennywiseai.ynab.data.local.dao.SnapshotDao` and:

```kotlin
/** The persistent "Connected · N budgets · M accounts" summary on Settings. */
data class ConnectionInfo(val budgetCount: Int, val accountCount: Int)
```

Inside the class:

```kotlin
    private val _connection = MutableStateFlow<ConnectionInfo?>(null)
    val connection: StateFlow<ConnectionInfo?> = _connection

    /** Read snapshot counts for the connected-state row. Called when Settings opens and after refresh. */
    fun loadConnection() {
        viewModelScope.launch {
            val (budgets, accounts) = withContext(Dispatchers.IO) {
                snapshotDao.countBudgets() to snapshotDao.countAccounts()
            }
            _connection.value = if (budgets > 0) ConnectionInfo(budgets, accounts) else null
        }
    }
```

(Imports `MutableStateFlow` and `StateFlow` are already present.) Also add `snapshotDao` to the Hilt-injected constructor list.

- [ ] **Step 8: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.settings.SettingsViewModelConnectionTest"`
Expected: PASS.

- [ ] **Step 9: Redesign SettingsScreen**

Replace `app/src/main/kotlin/com/pennywiseai/ynab/ui/settings/SettingsScreen.kt`. Keep the shared `TokenEntry` composable (unchanged) at the bottom; restructure the screen with a connected row + overflow menu that reveals `TokenEntry` only on "Replace token":

```kotlin
package com.pennywiseai.ynab.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.ui.rules.RulesList

@Composable
fun SettingsScreen(
    onAddRule: () -> Unit,
    onMapSuggestion: (bank: String, last4: String?) -> Unit,
    onTokenCleared: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var replacing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadConnection() }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item { SectionTitle("YNAB") }
        item {
            val info = connection
            if (info != null && !replacing) {
                ConnectedRow(
                    summary = "Connected · ${info.budgetCount} budgets · ${info.accountCount} accounts",
                    onRefresh = { viewModel.refresh() },
                    onReplace = { replacing = true },
                    onDisconnect = { viewModel.clearToken(); onTokenCleared() },
                )
            } else {
                // Not connected, or replacing a token: show the field directly.
                TokenEntry(viewModel)
                if (replacing) TextButton(onClick = { replacing = false }) { Text("Cancel") }
            }
        }

        item { Spacer(Modifier.height(24.dp)); HorizontalDivider() }

        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Routes", Modifier.weight(1f))
                TextButton(onClick = onAddRule) { Text("+ Add") }
            }
        }
        // Rules + "Needs routing" subheader live in RulesList (restyled in this task).
        item { RulesList(onMapSuggestion = onMapSuggestion) }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ConnectedRow(
    summary: String,
    onRefresh: () -> Unit,
    onReplace: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = com.pennywiseai.ynab.ui.theme.LocalStatusColors.current.success,
        )
        Text(summary, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "YNAB options")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; onRefresh() })
            DropdownMenuItem(text = { Text("Replace token") }, onClick = { menu = false; onReplace() })
            DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; onDisconnect() })
        }
    }
}
```

Keep the existing `TokenEntry(viewModel: SettingsViewModel)` composable (copy it unchanged from the current file into this file — it is still referenced by onboarding and the replace-token path).

- [ ] **Step 10: Restyle RulesList (flat rows, dividers, neutral avatars, broken hint)**

Replace the `RulesList` composable body in `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RulesScreen.kt` (keep the same signature — `SettingsScreen` calls it):

```kotlin
package com.pennywiseai.ynab.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Rules + unrouted suggestions, embedded in the Settings hub. One flat list: neutral grey
 * avatars, thin dividers, a broken-route hint line (not a loud badge). Signature is
 * load-bearing: SettingsScreen calls it.
 */
@Composable
fun RulesList(
    onMapSuggestion: (bank: String, last4: String?) -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        rules.forEach { rule ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(rule.bankName)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        "${rule.bankName} ·${rule.last4 ?: "any"} → ${rule.currencyCode}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (rule.broken) {
                        Text(
                            "Target account was deleted · tap to fix",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { viewModel.deleteRule(rule) }) { Text("Delete") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (suggestions.isNotEmpty()) {
            Text(
                "NEEDS ROUTING",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            suggestions.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(s.bankName)
                    Text(
                        "${s.bankName} ·${s.last4 ?: "any"}",
                        Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = { onMapSuggestion(s.bankName, s.last4) }) { Text("Map →") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Neutral grey initial avatar — no per-bank color (color means "problem", not decoration). */
@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
```

> Note: the "broken route · tap to fix" row is currently not tappable-to-edit (v1 `RulesList` only deletes; editing an existing rule needs an edit route that the app does not yet wire — see CONTEXT/Plan 6 which removed `onEditRule`). Keep the hint text as guidance; the resolve path in v1 is Delete + re-Add. Do not add an edit-nav dependency here (out of scope).

- [ ] **Step 11: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/settings/SettingsViewModel.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/settings/SettingsScreen.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RulesScreen.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/settings/SettingsViewModelConnectionTest.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/local/SnapshotDaoTest.kt
git commit -m "feat: Settings redesign — connected row + overflow, hidden token field, flat route list with dividers"
```

---

### Task 7: Route editor (dropdowns, sections, live preview)

Replaces text-fields + chip-grids with Budget/Account dropdowns, CARD / SEND TO sections, and a live preview line. Retro-import dialog behavior is unchanged.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RuleEditorScreen.kt`
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreview.kt`
- Test: Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreviewTest.kt`

**Interfaces:**
- Consumes: `RulesViewModel` (`budgets`/`accounts`/`loadBudgets`/`loadAccounts`/`saveRule`/`retroImport`, `RuleDraft`, `SaveRuleResult`), `Screen.RuleEditor`.
- Produces: `fun routePreview(bank: String, last4: String?, budgetName: String?, accountName: String?, currency: String?): String?`.

- [ ] **Step 1: Write the failing preview test**

Create `app/src/test/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreviewTest.kt`:

```kotlin
package com.pennywiseai.ynab.ui.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutePreviewTest {

    @Test
    fun `full selection renders the route line`() {
        assertEquals(
            "SBI ·7756 → Personal / Everyday (₹)",
            routePreview("SBI", "7756", "Personal", "Everyday", "₹"),
        )
    }

    @Test
    fun `blank last4 shows any card`() {
        assertEquals(
            "SBI ·any → Personal / Everyday (INR)",
            routePreview("SBI", null, "Personal", "Everyday", "INR"),
        )
    }

    @Test
    fun `incomplete selection has no preview`() {
        assertNull(routePreview("", null, "Personal", "Everyday", "INR")) // no bank
        assertNull(routePreview("SBI", "7756", null, "Everyday", "INR")) // no budget
        assertNull(routePreview("SBI", "7756", "Personal", null, "INR")) // no account
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.rules.RoutePreviewTest"`
Expected: FAIL — `routePreview` unresolved.

- [ ] **Step 3: Implement routePreview**

Create `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreview.kt`:

```kotlin
package com.pennywiseai.ynab.ui.rules

/**
 * The live-preview line for the route editor: "SBI ·7756 → Personal / Everyday (₹)".
 * Returns null until bank, budget, and account are all chosen (nothing to preview yet).
 * Pure — unit-tested in RoutePreviewTest. A blank last4 renders as "any" (bank wildcard).
 */
fun routePreview(
    bank: String,
    last4: String?,
    budgetName: String?,
    accountName: String?,
    currency: String?,
): String? {
    if (bank.isBlank() || budgetName == null || accountName == null) return null
    val card = last4?.ifBlank { null } ?: "any"
    val cur = currency?.let { " ($it)" } ?: ""
    return "$bank ·$card → $budgetName / $accountName$cur"
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.rules.RoutePreviewTest"`
Expected: PASS.

- [ ] **Step 5: Redesign RuleEditorScreen with dropdowns + sections + preview**

Replace `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RuleEditorScreen.kt`:

```kotlin
package com.pennywiseai.ynab.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.ui.nav.Screen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    args: Screen.RuleEditor,
    onDone: () -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var bank by remember { mutableStateOf(args.prefillBank ?: "") }
    var last4 by remember { mutableStateOf(args.prefillLast4 ?: "") }
    var budgetId by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var offerImportFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadBudgets() }

    val selectedBudget = budgets.firstOrNull { it.id == budgetId }
    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    val currency = selectedBudget?.currencyCode ?: ""
    val valid = bank.isNotBlank() && budgetId.isNotBlank() && accountId.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (args.editRuleId == null) "New route" else "Edit route") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            scope.launch {
                                when (val result = viewModel.saveRule(
                                    RuleDraft(bank, last4, budgetId, accountId, currency, args.editRuleId),
                                )) {
                                    SaveRuleResult.Saved ->
                                        if (args.prefillBank != null) offerImportFor = bank else onDone()
                                    SaveRuleResult.DuplicateRoute ->
                                        error = "A route for this bank + last4 already exists"
                                    is SaveRuleResult.Invalid -> error = result.message
                                }
                            }
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("CARD", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(bank, { bank = it }, label = { Text("Bank name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(last4, { last4 = it }, label = { Text("Last 4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Blank = match any card from this bank.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("SEND TO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
            BudgetDropdown(
                budgets = budgets,
                selected = selectedBudget,
                onSelect = { b -> budgetId = b.id; accountId = ""; viewModel.loadAccounts(b.id) },
            )
            AccountDropdown(
                accounts = accounts,
                selected = selectedAccount,
                enabled = budgetId.isNotBlank(),
                onSelect = { a -> accountId = a.id },
            )

            routePreview(bank, last4, selectedBudget?.name, selectedAccount?.name, currency.ifBlank { null })?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    offerImportFor?.let { b ->
        AlertDialog(
            onDismissRequest = { offerImportFor = null; onDone() },
            title = { Text("Import past transactions?") },
            text = { Text("Re-scan the inbox for $b so already-received messages post now.") },
            confirmButton = { TextButton(onClick = { viewModel.retroImport(b); offerImportFor = null; onDone() }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { offerImportFor = null; onDone() }) { Text("Not now") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetDropdown(
    budgets: List<BudgetEntity>,
    selected: BudgetEntity?,
    onSelect: (BudgetEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { "${it.name} (${it.currencyCode})" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Budget") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        androidx.compose.material3.ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            budgets.forEach { b ->
                DropdownMenuItem(
                    text = { Text("${b.name} (${b.currencyCode})") },
                    onClick = { onSelect(b); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDropdown(
    accounts: List<AccountEntity>,
    selected: AccountEntity?,
    enabled: Boolean,
    onSelect: (AccountEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        androidx.compose.material3.ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { a ->
                DropdownMenuItem(text = { Text(a.name) }, onClick = { onSelect(a); expanded = false })
            }
        }
    }
}
```

> Note: when embedded in onboarding (Task 8), this screen renders its own `TopAppBar`. That is acceptable — onboarding hosts the editor as its "map first card" step and the top bar's back arrow simply calls `onDone`. Confirm visually during Task 8; if the nested app bar looks wrong there, onboarding can pass a flag — but do not add that unless the visual check fails.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RuleEditorScreen.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreview.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreviewTest.kt
git commit -m "feat: Route editor — Budget/Account dropdowns, CARD/SEND TO sections, live preview line"
```

---

### Task 8: Onboarding (single dense checklist screen)

Replaces the three stacked text steps with one dense screen: a three-item checklist (permissions, connect YNAB, map first card — optional), plus a pinned "Start capturing" CTA enabled once YNAB is connected.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/PennyWiseApp.kt` (pass `permissionsGranted`)
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/MainActivity.kt` (compute + pass `permissionsGranted`)

No new unit test: onboarding adds no new testable logic — its one gate ("Start capturing" enabled iff `tokenState is TokenUiState.Saved`) reuses `SettingsViewModel.tokenState`, already covered by `SettingsViewModelRetryTest`/`SettingsViewModelConnectionTest`, and the codebase does not unit-test composables (no Compose UI-test harness). Verification is the build + a manual smoke run.

**Interfaces:**
- Consumes: `SettingsViewModel.tokenState` (`TokenUiState`), `TokenEntry`, `RuleEditorScreen` (Task 7), `Screen.RuleEditor`.
- Produces: `OnboardingScreen(onRequestPermissions, onComplete, permissionsGranted, settings)` — new `permissionsGranted: Boolean` param.

- [ ] **Step 1: Rewrite OnboardingScreen as a single checklist + pinned CTA**

Replace `app/src/main/kotlin/com/pennywiseai/ynab/ui/onboarding/OnboardingScreen.kt`:

```kotlin
package com.pennywiseai.ynab.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.ui.nav.Screen
import com.pennywiseai.ynab.ui.rules.RuleEditorScreen
import com.pennywiseai.ynab.ui.settings.SettingsViewModel
import com.pennywiseai.ynab.ui.settings.TokenEntry
import com.pennywiseai.ynab.ui.settings.TokenUiState

/**
 * First-run: one dense screen, no wizard. A three-item checklist (permissions, connect YNAB,
 * map first card — optional) fills in checkmarks as steps complete; the pinned "Start
 * capturing" CTA enables once a valid token exists (the hard gate). Step 3 is skippable —
 * unrouted suggestions catch new cards later.
 */
@Composable
fun OnboardingScreen(
    onRequestPermissions: () -> Unit,
    onComplete: () -> Unit,
    permissionsGranted: Boolean,
    settings: SettingsViewModel = hiltViewModel(),
) {
    val tokenState by settings.tokenState.collectAsStateWithLifecycle()
    val connected = tokenState is TokenUiState.Saved
    var mappedCard by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Set up pennywise-ynab", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Three quick steps and your bank texts start flowing into YNAB.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier.weight(1f).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        ) {
            ChecklistItem(done = permissionsGranted, title = "Allow reading bank texts") {
                Text(
                    "SMS + notifications. Messages are read on-device — nothing else leaves your phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onRequestPermissions) { Text("Allow") }
            }

            ChecklistItem(done = connected, title = "Connect YNAB") {
                TokenEntry(settings)
            }

            ChecklistItem(done = mappedCard, title = "Map your first card  ·  OPTIONAL") {
                if (showEditor) {
                    RuleEditorScreen(
                        args = Screen.RuleEditor(),
                        onDone = { mappedCard = true; showEditor = false },
                    )
                } else {
                    OutlinedButton(onClick = { showEditor = true }) { Text("Add a route") }
                }
            }
        }

        Button(
            onClick = onComplete,
            enabled = connected,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text("Start capturing") }
    }
}

@Composable
private fun ChecklistItem(done: Boolean, title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
```

- [ ] **Step 2: Pass `permissionsGranted` through PennyWiseApp**

In `app/src/main/kotlin/com/pennywiseai/ynab/ui/PennyWiseApp.kt`, thread a `permissionsGranted: Boolean` param through `PennyWiseApp` into `OnboardingScreen`:

- Change the signature: `fun PennyWiseApp(onRequestPermissions: () -> Unit, permissionsGranted: Boolean, gate: AppGateViewModel = hiltViewModel())`
- In the `false ->` branch:

```kotlin
        false -> OnboardingScreen(
            onRequestPermissions = onRequestPermissions,
            onComplete = { gate.recheck() },
            permissionsGranted = permissionsGranted,
        )
```

- [ ] **Step 3: Compute `permissionsGranted` in MainActivity and pass it**

In `app/src/main/kotlin/com/pennywiseai/ynab/MainActivity.kt`, compute whether `RECEIVE_SMS` is granted and pass it. Add imports `androidx.core.content.ContextCompat`, `android.content.pm.PackageManager`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.setValue`, `androidx.lifecycle.compose.LocalLifecycleOwner` (or use a simple recomputation). Simplest robust form — recompute on each composition after the launcher returns:

```kotlin
                    var granted by remember { mutableStateOf(hasSmsPermission()) }
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { granted = hasSmsPermission() }
                    val permissions = remember { /* unchanged buildList { ... } */ }
                    PennyWiseApp(
                        onRequestPermissions = { launcher.launch(permissions) },
                        permissionsGranted = granted,
                    )
```

Add a private helper on the activity:

```kotlin
    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
```

- [ ] **Step 4: Build to verify it compiles + run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (compilation covers the changed activity/app signatures; no new unit test).

- [ ] **Step 5: Manual smoke check**

Build & install a debug build (`./gradlew :app:installDebug` on a device/emulator). Verify: fresh launch shows the single onboarding screen; granting permissions ticks item 1; saving a valid token ticks item 2 and enables "Start capturing"; "Start capturing" advances to Home. (This is a manual verification step — there is no Compose UI test harness.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/ui/onboarding/OnboardingScreen.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/PennyWiseApp.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/MainActivity.kt
git commit -m "feat: Onboarding — single dense checklist screen with pinned Start-capturing CTA"
```

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| Status color semantics (StatusColors theme extension, derived light/dark) | Task 1 |
| Navigation: History→Home rename, three tabs, rule editor pushed | Task 4 (+ retained stack in PennyWiseApp) |
| Empty & loading states (Home empty, token validating, import progress) | Task 4 (Home empty), existing `TokenEntry` "Validating…" (retained), Task 5 (import progress) |
| Home: header w/ last-transaction time, ⟳ re-scan (last 24h + result), stat strip (Posted/Failed/Unrouted, tap-to-filter), recent list w/ pills + inline fix | Tasks 2, 3, 4 |
| Settings: connected row + overflow (Refresh/Replace/Disconnect), hidden token field, flat routes + dividers + neutral avatars, broken hint, Needs-routing subheader | Task 6 |
| Route editor: top bar + Save-when-valid, CARD section, SEND TO dropdowns, currency inferred, live preview, retro-import dialog | Task 7 |
| Onboarding: single dense screen, 3-item checklist, pinned CTA, optional step 3 | Task 8 |
| Import: explanation, quick-range chips + Custom, safety note, self-labeling CTA (via chips), in-app progress (determinate + indeterminate fallback), Cancel, keeps-running note, completion summary | Task 5 |
| Currency-mismatch: amber in list, not its own tile | Task 1 mapping + Global Constraints (confirmed) |

The "self-labeling CTA (Import 30 days)" is realized as tappable quick-range chips that start immediately on tap (each chip *is* the action), which is cleaner than a separate summary-card + CTA; the summary-card/"Change" affordance from the mockup is intentionally simplified to direct chips. Flagged here as the one deliberate deviation.

**2. Placeholder scan:** No "TBD"/"add error handling"/"similar to Task N" — every step carries real code. Two explicit "implementer note" callouts (SnapshotDaoTest seeding helper; entity constructor field names) point at existing code to match, not gaps to invent.

**3. Type consistency:** `BackfillRun`/`BackfillObserver`/`BackfillCanceller` names are consistent across Tasks 3/4/5. `MessageRetrier` moves package `ui.history`→`ui.home` and `UiModule` is updated in the same task (Task 4). `Screen.History`→`Screen.Home` updated in nav + PennyWiseApp together (Task 4). `HomeStats`/`RescanState`/`ConnectionInfo` each defined once and consumed by their screen. `quickRangeMillis`/`inclusiveEndMillis` co-located and used by the same VM. `routePreview` signature matches its test and its single call site.

**Known cross-task compile ordering:** Task 3 changes `CaptureScheduler.backfillStatus()`→`backfillRun()`, which `BackfillViewModel` (old) references. Task 3 Step 11 includes the one-line stopgap so the module compiles before Task 5 fully replaces `BackfillViewModel`. If executing strictly task-by-task with a green build gate between tasks, apply that stopgap.
