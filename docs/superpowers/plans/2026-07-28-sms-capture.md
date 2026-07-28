# PennyWise → YNAB — Plan 5: SMS Capture (real-time + backfill) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Execution: work directly on `main` — no git worktree** (user decision, 2026-07-28). Use **Opus** for every implementation/review sub-agent. Commit after each task as the steps specify.

**Goal:** Feed real bank SMS into the already-built `TransactionPipeline` from both capture modes the spec requires — a real-time `BroadcastReceiver` → expedited WorkManager job per message, and an on-demand date-range **backfill** foreground worker that reads the SMS inbox, parses+routes locally, and **bulk-POSTs grouped by budget** (ADR-0004) with a per-chunk `400`→individual fallback — plus exception-only notifications, all under the retry/idempotency rules Plan 4 established.

**Architecture:** A new `com.pennywiseai.ynab.capture` package owns the Android glue. Plan 4's `TransactionPipeline` is refactored (behavior-preserving) to expose a shared `classify(body, sender, timestamp)` seam — the "parse→route mapping function" ADR-0003 names — so both `process()` (single, real-time) and a new `BackfillProcessor` (bulk) run the *same* parse/skip/route/currency/dedup/pause logic and differ only in how they POST. WorkManager runs everything off the main thread: a manifest `SmsReceiver` (Hilt `@AndroidEntryPoint`) reassembles multipart PDUs and enqueues an **expedited** `SmsPostWorker` that calls `process()` and maps `PipelineResult` → `Result.retry()`/`failure()`/`success()` with a `runAttemptCount` ceiling; a `BackfillWorker` (foreground `dataSync` service, determinate progress) reads `content://sms/inbox` via an injectable `SmsInboxReader` seam and drives `BackfillProcessor`. A `Notifier` owns channels + the three exception-only notifications (terminal failure, posting-paused, backfill summary). WorkManager is wired to Hilt via `HiltWorkerFactory` + `Configuration.Provider`. Everything with logic is unit-tested pure-JVM/Robolectric with fakes; framework wiring (receiver, inbox `ContentResolver` query, foreground service) is verified by `assembleDebug` + the on-device smoke check per the spec's Definition of Done.

**Tech Stack:** Adds AndroidX **WorkManager 2.11.2** (`work-runtime-ktx` + `work-testing`) and **AndroidX Hilt 1.4.0** (`hilt-work` + `hilt-compiler` via KSP), on top of Dagger-Hilt 2.59.2. No other new deps (`NotificationCompat` is transitive via `androidx.core:core-ktx` 1.18.0). Builds on Plans 1–4 (`core`, `data.local`, `data.remote`, `data.token`, `data.state`, `pipeline`). JVM tests + Robolectric 4.15.1 + `kotlinx-coroutines-test` 1.10.2 + `TestListenableWorkerBuilder`.

## Global Constraints

Copied verbatim from the design spec / ADRs / Plans 1–4. Every task's requirements implicitly include this section.

- **SDK/toolchain (unchanged):** `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`, Java **11** bytecode, JDK 21 Gradle toolchain; unit tests run on a JDK-21 launcher (already configured). `kotlin.jvm.target.validation.mode=warning` (a `jvmTarget` validation warning is expected on `assembleDebug` and is non-fatal).
- **App identity:** `namespace` = `applicationId` = `com.pennywiseai.ynab`.
- **parser-core is never modified.** Consumed only through the existing `SmsParser` seam / `BankParserFactory` binding. Plan 5 does not touch the submodule.
- **Single network destination (design spec, Security & privacy).** The only host the app ever contacts is `api.ynab.com` over HTTPS, via the existing `YnabApi`/`TransactionPoster`. **No** analytics, **no** crash SDK, **no** other host, **no** OkHttp logging interceptor. Plan 5 adds **no** new endpoint and **no** network call of its own — it only drives Plan 4's pipeline/poster. SMS content is parsed on-device; only a routed, postable transaction's fields leave the device, and only to YNAB.
- **Token handling (design spec).** The PAT lives only in `EncryptedSharedPreferences` behind `TokenStore`, read only by `AuthInterceptor` (and, for the pause short-circuit, by the pipeline). Plan 5 never reads, logs, or persists the token; it never puts SMS bodies or the token into a notification, an `error` string, or WorkManager `inputData` beyond the one message being processed.
- **`import_id` is the dedup authority (ADR-0005).** Every retry and every re-scan is idempotent because YNAB rejects a duplicate `import_id` within a budget (reported per element in `duplicate_import_ids`). A duplicate is a **successful** post → `POSTED`. Real-time overlap with backfill is therefore safe. The local `ProcessedMessageEntity` (PK = `importId`) is best-effort only.
- **Fixed 5-status set (CONTEXT.md):** `POSTED`, `SKIPPED_UNROUTED`, `SKIPPED_NON_TRANSACTION`, `SKIPPED_CURRENCY_MISMATCH`, `FAILED`. Plan 5 adds **no** status. Un-parseable SMS (parser returns null) are **dropped, never logged**. "retrying…" vs "failed — tap to retry" is **derived** later (Plan 6) from error class + whether a retry job is live — not a new status.
- **Two capture modes, one pipeline (ADR-0003).** Neither mode posts inline. `onReceive` runs on the main thread under a ~10s ANR ceiling → it only reassembles + enqueues; one WorkManager worker runs parse→post. "Immediate" = an **expedited** job, not a synchronous send. The shared unit is the parse→route mapping (`classify`), invoked two ways.
- **Backfill posts in bulk, grouped by budget (ADR-0004).** Parse+route the whole range locally (no network), group postable transactions by `budgetId`, send one bulk POST per chunk (**≤ 100** transactions per chunk — well under YNAB's 200 req/hr), map the response (created + `duplicate_import_ids` → `POSTED`). **All-or-nothing chunk:** a bulk POST is atomic, so on a chunk `400` (a genuine bad element) **fall back to individual POSTs for just that chunk** — good rows land `POSTED`, only the bad row(s) get `FAILED` with the per-transaction error. Duplicates are **not** errors (they return on a normal `2xx`). Both modes treat `429`/`5xx`/offline as retryable, but the two modes act on that differently: **real-time** reschedules the single-message worker with WorkManager backoff (retry until the `MAX_ATTEMPTS` ceiling); **backfill records the affected rows `FAILED` in-place and does not auto-retry the worker** (implemented as of Plan 5 — see the note below). Because every post is idempotent via `import_id`, re-running the same date range is safe and cheap (dedup skips what already landed), so a transient `429`/blip during backfill is recovered by re-running the range — Plan 6 adds a manual re-run/retry action. (Ruling 2026-07-28: this backfill-non-retry behavior was reviewed and accepted as intentional; the `EXPONENTIAL` backoff configured on the backfill request is inert and retained only for parity/future use.)
- **Rate limit (design spec).** No proactive governor. Chunking keeps a normal backfill to a handful of requests; a `429` in **real-time** is handled **reactively** (retryable → WorkManager backoff). In **backfill** a `429`/`5xx`/offline marks the affected rows `FAILED` for this run and relies on an idempotent re-run of the range (Plan 6 manual action) rather than a worker retry. Real-time is a few messages/day and doesn't meaningfully eat the 200/hr headroom.
- **Error classification (design spec, Error handling) — already implemented in Plan 4's pipeline/poster; Plan 5 honors it end-to-end.** Retryable (offline / timeout / `429` / `5xx`) → `FAILED` + a WorkManager retry with exponential backoff under a `NetworkType.CONNECTED` constraint; a **generous ceiling (`runAttemptCount` cap ≈ 24h of backoff)** then marks the row terminal so a broken network can't leave a zombie job. Terminal-immediately: `400` (our bug) / `404` (route target vanished → the pipeline already flips the rule **broken** and records the row terminal). `401` **or no token** → the pipeline sets the persistent `postingPaused` flag and records `FAILED` **without** hitting YNAB (no 401 storm); a validated token save clears it (Plan 4).
- **Notifications (exception-only) (design spec).** `POSTED` → **no** notification (history is the happy-path record). **Terminal `FAILED`** → notify (but **not** while a retryable failure is still inside its auto-retry window — don't cry wolf). `postingPaused` / `401` → notify prominently. **Backfill completion** → one **summary** notification (`posted N · skipped M · failed K`). On API 33+ `POST_NOTIFICATIONS` is requested in first-run (Plan 6); if denied, everything still works — the user relies on the in-app banner/history. `NotificationManagerCompat.notify` simply no-ops without the grant.
- **Permissions.** Plan 5 adds `RECEIVE_SMS` (real-time), `READ_SMS` (backfill inbox), `POST_NOTIFICATIONS` (API 33+ status), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` (long backfill). Manifest kept clean for later distribution choices. **The runtime-permission request UI is Plan 6** (first-run flow) — Plan 5 declares the permissions and the components that need them; on a dev device the grant is given manually for the smoke check.
- **No schema change.** Plan 5 touches no Room entity/DAO except *reading/writing existing* tables via the pipeline. DB stays at `version = 2` (Plan 4).
- **YAGNI (deferred to Plan 6 — UI):** the backfill date-range picker screen, the cancel button, the history screen's manual **retry** action, the "map this route" retroactive import, the runtime-permission request flow, the posting-paused banner, and the bulk-retry-of-`FAILED`-on-token-save trigger. Plan 5 delivers the *engine* those UIs drive: `CaptureScheduler.enqueueBackfill(from, to)`, cancellation via `WorkManager.cancelUniqueWork`, and `isStopped`-based cooperative cancellation inside the worker. It does **not** build any Composable.

### Types already defined (consumed here; ✎ = modified by this plan)

```kotlin
// ✎ com.pennywiseai.ynab.pipeline.TransactionPipeline  (Task 2 extracts classify(); process() behavior unchanged)
@Singleton class TransactionPipeline @Inject constructor(
    smsParser: SmsParser, mapper: TransactionMapper, resolver: MappingResolver, poster: TransactionPoster,
    mappingRuleDao: MappingRuleDao, processedMessageDao: ProcessedMessageDao,
    tokenStore: TokenStore, postingState: PostingStateStore,
) {
    suspend fun process(body: String, sender: String, timestamp: Long): PipelineResult
    suspend fun classify(body: String, sender: String, timestamp: Long): Classification   // NEW (Task 2)
    companion object { const val ERROR_NO_TOKEN; const val ERROR_TOKEN_INVALID; const val ERROR_ROUTE_BROKEN }
}

// com.pennywiseai.ynab.pipeline.PipelineResult  (Plan 4)
sealed interface PipelineResult { data object Dropped; data class Skipped(status: MessageStatus); data object Posted; data class Failed(retryable: Boolean) }

// com.pennywiseai.ynab.pipeline.PostOutcome  (Plan 4)
sealed interface PostOutcome { data object Posted; data object Unauthorized; data object RouteBroken; data class Failed(retryable: Boolean, error: String) }
// com.pennywiseai.ynab.pipeline.TransactionPoster  — suspend fun post(budgetId: String, transactions: List<SaveTransaction>): PostOutcome
// com.pennywiseai.ynab.pipeline.SmsParser  — fun interface parse(body, sender, timestamp): ParsedTransaction?

// com.pennywiseai.ynab.core.model.SaveTransaction  (Plan 1)  — data class(accountId, date, amount, payeeName?, memo?, importId, approved, cleared)
// com.pennywiseai.ynab.core.model.MappingRule    (Plan 1/4) — data class(bankName, last4?, budgetId, accountId, currencyCode, broken)
// com.pennywiseai.ynab.core.TransactionMapper    — fun map(parsed, rule): SaveTransaction; fun importIdFor(parsed): String
// com.pennywiseai.ynab.core.MappingResolver      — fun resolve(rules, bankName, last4?): MappingRule?
// com.pennywiseai.ynab.core.isPostable()         — TransactionType.isPostable()
// com.pennywiseai.ynab.data.mapper.WILDCARD_LAST4 (== ""); MappingRuleEntity.toDomain()
// com.pennywiseai.ynab.data.local.MessageStatus  — enum(POSTED, SKIPPED_UNROUTED, SKIPPED_NON_TRANSACTION, SKIPPED_CURRENCY_MISMATCH, FAILED)
// com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity(importId, sender, bankName, last4?, amount: BigDecimal, currency, status, error?, timestamp)
// com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao — suspend fun upsert(e); getByImportId(id); getAll(); getByStatus(s)
// com.pennywiseai.ynab.data.local.dao.MappingRuleDao      — suspend fun getAll(); setBroken(bankName, last4, broken); insert/update/delete
// com.pennywiseai.ynab.data.token.TokenStore              — fun getToken(): String?
// com.pennywiseai.ynab.data.state.PostingStateStore       — fun isPaused(): Boolean; fun setPaused(paused: Boolean)
// com.pennywiseai.parser.core.ParsedTransaction(amount: BigDecimal, type, merchant?, reference?, accountLast4?, ..., smsBody, sender, timestamp, bankName, currency, ...) { fun generateTransactionId(): String }

// Test doubles already present (com.pennywiseai.ynab.pipeline test source) — reused/extended here:
//   FakeTransactionPoster(outcome), FakeMappingRuleDao(rules), FakeProcessedMessageDao,
//   FakeTokenStore(initial), FakePostingStateStore(initial)
```

---

## File Structure

New Android glue lives in one package, `com.pennywiseai.ynab.capture`, split by responsibility. The pipeline's shared `classify` seam extends the existing `pipeline` package. Notifications live in `capture.notify`.

**Create (main):**
- `pipeline/Classification.kt` — the shared parse→map decision the two modes branch on.
- `capture/CaptureScheduler.kt` — enqueues the real-time + backfill WorkManager jobs (the API Plan 6's UI calls); cancels backfill.
- `capture/SmsPostWorker.kt` — expedited real-time worker: `process()` → `Result`.
- `capture/RawSms.kt` — `RawSms(sender, body, timestamp)` + the pure `reassembleSms(...)` used by the receiver.
- `capture/SmsReceiver.kt` — `@AndroidEntryPoint` `BroadcastReceiver` for `SMS_RECEIVED`.
- `capture/SmsInboxReader.kt` — interface + `@Binds` module; the backfill's SMS source seam.
- `capture/ContentResolverSmsInboxReader.kt` — `content://sms/inbox` query impl.
- `capture/BackfillProcessor.kt` — the bulk group/chunk/post/400-fallback/response-map engine + `BackfillSummary`.
- `capture/BackfillWorker.kt` — foreground `dataSync` worker: read inbox → drive `BackfillProcessor` → summary.
- `capture/notify/Notifier.kt` — channels, foreground infos, the 3 exception-only notifications.

**Modify (main):**
- `gradle/libs.versions.toml` — add WorkManager + AndroidX-Hilt versions/libraries.
- `app/build.gradle.kts` — add the 3 impl deps + `work-testing` test dep + the AndroidX-Hilt KSP compiler.
- `app/src/main/AndroidManifest.xml` — permissions, `tools` namespace, remove the default `WorkManagerInitializer`, declare `SmsReceiver`, merge `foregroundServiceType` onto `SystemForegroundService`.
- `PennyWiseYnabApp.kt` — implement `Configuration.Provider` with an injected `HiltWorkerFactory`.
- `pipeline/TransactionPipeline.kt` — extract `classify()`; rebuild `process()` on it (no behavior change).

**Create (test):**
- `pipeline/ClassificationSeamTest.kt` — the new `classify()` branch matrix (pure JVM).
- `capture/SmsReassembleTest.kt` — multipart concatenation (pure JVM).
- `capture/SmsPostWorkerTest.kt` — `PipelineResult` → `Result` incl. retry ceiling (Robolectric + `TestListenableWorkerBuilder`).
- `capture/FakeSmsInboxReader.kt` — in-memory `SmsInboxReader` double.
- `capture/BackfillProcessorTest.kt` — grouping / chunking / 400-fallback / duplicate / cancellation (pure JVM).
- `capture/notify/NotifierTest.kt` — channels + posted notifications (Robolectric `ShadowNotificationManager`).

**Modify (test):**
- `pipeline/FakeTransactionPoster.kt` — add a per-call `responder` (backfill needs different outcomes per chunk).

### Test strategy

- **`classify` seam:** pure JVM. The existing `TransactionPipelineTest` (Plan 4) stays green unchanged — `process()` is refactored, not re-specified. `ClassificationSeamTest` adds direct assertions on each `Classification` variant (incl. the no-token→`setPaused(true)` side effect) so the seam both `process()` and `BackfillProcessor` depend on is nailed independently.
- **`BackfillProcessor`:** pure JVM — the real `TransactionPipeline` wired with the existing fakes (`SmsParser` lambda, `FakeMappingRuleDao`, `FakeProcessedMessageDao`, fixed-`ZoneId` `TransactionMapper`, real `MappingResolver`, `FakeTokenStore`, `FakePostingStateStore`) + `FakeTransactionPoster` with a `responder`. Covers: two budgets → one POST per budget; a chunk boundary at `CHUNK_SIZE`; a chunk `400` → individual fallback (good rows `POSTED`, bad row `FAILED`); a `2xx` duplicate → `POSTED`; `Unauthorized` → pause + stop; `RouteBroken` → `setBroken` + `FAILED`; `isCancelled` after the in-flight chunk. No socket, no Android.
- **`SmsPostWorker`:** Robolectric + `TestListenableWorkerBuilder` — construct the worker directly with a fake pipeline + fake notifier; assert `Result` per `PipelineResult` and that `runAttemptCount ≥ MAX_ATTEMPTS` turns a retryable failure terminal. `WorkManagerTestInitHelper` is **not** needed (we test `doWork()` logic, not scheduling).
- **`reassembleSms`:** pure JVM — driven with `List<String>` part bodies (not framework `SmsMessage`, a final class), so multipart concatenation + empty/blank-sender guards are testable. The receiver's `SmsMessage`→bodies extraction is thin and covered by `assembleDebug` + the on-device smoke check.
- **`Notifier`:** Robolectric — assert channels exist and each `notify*` posts a notification (via `ShadowNotificationManager`); the foreground-info notification is asserted by building it directly (not through a real foreground transition).
- **Framework wiring** (receiver registration, `Configuration.Provider`, `ContentResolverSmsInboxReader`, the foreground service type, the `HiltWorkerFactory` graph): verified by `./gradlew :app:assembleDebug` + the spec's on-device smoke check. Encryption-style "not unit-tested because it's framework glue" precedent is Plan 3's `EncryptedSharedPreferences`.

---

### Task 1: WorkManager + Hilt-Work infrastructure

Wire WorkManager to Hilt so later tasks can inject dependencies into `@HiltWorker`s: add the deps, make the `Application` a `Configuration.Provider` backed by `HiltWorkerFactory`, and remove WorkManager's default startup initializer (required for on-demand init). No worker exists yet — this task is verified by `assembleDebug` (the DI graph compiles and dexes).

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/pennywiseai/ynab/PennyWiseYnabApp.kt`

**Interfaces:**
- Consumes: nothing new (Dagger-Hilt 2.59.2 already set up).
- Produces: a WorkManager runtime initialized from Hilt (`HiltWorkerFactory`), available to Tasks 3–8.

- [ ] **Step 1: Add versions + libraries to the catalog** `gradle/libs.versions.toml`

Under `[versions]` add:

```toml
work = "2.11.2"
androidxHilt = "1.4.0"
```

Under `[libraries]` add:

```toml
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
androidx-hilt-work = { module = "androidx.hilt:hilt-work", version.ref = "androidxHilt" }
androidx-hilt-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "androidxHilt" }
```

- [ ] **Step 2: Add the dependencies** `app/build.gradle.kts`

In the `dependencies { }` block, alongside the existing Hilt lines, add the three impl deps + the AndroidX-Hilt KSP compiler (this is **in addition to** the existing `ksp(libs.hilt.android.compiler)` — both compilers run):

```kotlin
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
```

And in the test deps block add:

```kotlin
    testImplementation(libs.androidx.work.testing)
```

- [ ] **Step 3: Make the Application a `Configuration.Provider`** `app/src/main/kotlin/com/pennywiseai/ynab/PennyWiseYnabApp.kt`

Replace the file with:

```kotlin
package com.pennywiseai.ynab

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * On-demand WorkManager initialization (design spec / ADR-0003): WorkManager is
 * configured from Hilt so @HiltWorkers can inject app dependencies. Implementing
 * Configuration.Provider (a `val` since WorkManager 2.6) requires removing the
 * default WorkManagerInitializer from the manifest — see AndroidManifest.xml.
 */
@HiltAndroidApp
class PennyWiseYnabApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

- [ ] **Step 4: Remove the default initializer + add the `tools` namespace** `app/src/main/AndroidManifest.xml`

Add `xmlns:tools="http://schemas.android.com/tools"` to the `<manifest>` root element (next to the existing `xmlns:android`). Then, **inside** `<application>…</application>`, add the provider that strips only the `WorkManagerInitializer` meta-data (leaving `androidx.startup` itself intact for other libraries):

```xml
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authority="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
```

- [ ] **Step 5: Verify the graph compiles and dexes**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Proves `HiltWorkerFactory` is provided (AndroidX-Hilt compiler + Dagger-Hilt agree) and the manifest merges. A `kotlin.jvm.target.validation` warning is expected and non-fatal.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/AndroidManifest.xml \
        app/src/main/kotlin/com/pennywiseai/ynab/PennyWiseYnabApp.kt
git commit -m "feat: WorkManager + Hilt-Work infrastructure (Configuration.Provider, on-demand init)"
```

---

### Task 2: Extract the shared `classify()` seam in `TransactionPipeline`

Refactor Plan 4's pipeline so the parse→skip→route→currency→dedup→pause decision is a single reusable `classify()` returning a `Classification`, and rebuild `process()` on top of it with **identical** observable behavior (the Plan 4 `TransactionPipelineTest` must still pass unchanged). This is the DRY foundation ADR-0003 names — `BackfillProcessor` (Task 7) reuses `classify()` verbatim, so the two capture modes can never diverge on which SMS is postable.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/pipeline/Classification.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/pipeline/TransactionPipeline.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/pipeline/ClassificationSeamTest.kt`

**Interfaces:**
- Consumes: everything `TransactionPipeline` already injects.
- Produces:
  - `sealed interface Classification { Dropped; Skipped(status, parsed, importId); AlreadyPosted(parsed, importId); Paused(parsed, importId, error); Postable(parsed, importId, rule, transaction) }`
  - `TransactionPipeline.classify(body, sender, timestamp): Classification` — pure decision (its only writes are the existing no-token→`setPaused(true)` side effect and the local-dedup **read**; it does **not** write the log).
  - `process(...)` unchanged externally.

- [ ] **Step 1: Create the `Classification` type** `app/src/main/kotlin/com/pennywiseai/ynab/pipeline/Classification.kt`

```kotlin
package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.core.model.SaveTransaction
import com.pennywiseai.ynab.data.local.MessageStatus

/**
 * The outcome of the shared parse→route decision (ADR-0003's "parse→route mapping
 * function"), before any POST. TransactionPipeline.process() (real-time, single POST)
 * and BackfillProcessor (bulk POST) both branch on this so they can never disagree on
 * which SMS is postable. `classify` carries the parsed message + its import_id on every
 * non-Dropped variant so the caller can record the log row itself (skips/pause) or POST
 * (Postable). It writes no log row — recording is the caller's job.
 */
sealed interface Classification {
    /** Parser returned null — no import_id exists; drop silently, never log. */
    data object Dropped : Classification

    /** A SKIPPED_* outcome (non-transaction / unrouted-or-broken / currency mismatch). */
    data class Skipped(
        val status: MessageStatus,
        val parsed: ParsedTransaction,
        val importId: String,
    ) : Classification

    /** Local log already has this import_id POSTED (best-effort dedup; ADR-0005). */
    data class AlreadyPosted(
        val parsed: ParsedTransaction,
        val importId: String,
    ) : Classification

    /** Posting is paused / no token — record FAILED without touching the network. */
    data class Paused(
        val parsed: ParsedTransaction,
        val importId: String,
        val error: String,
    ) : Classification

    /** Ready to POST: the mapped transaction + the resolved (non-broken) rule. */
    data class Postable(
        val parsed: ParsedTransaction,
        val importId: String,
        val rule: MappingRule,
        val transaction: SaveTransaction,
    ) : Classification
}
```

- [ ] **Step 2: Rewrite `TransactionPipeline` around `classify()`** `app/src/main/kotlin/com/pennywiseai/ynab/pipeline/TransactionPipeline.kt`

Replace the whole file with the version below. `process()` produces the same log writes / return values / side effects as before — only the internals are re-expressed through `classify()` + `postSingle()`.

```kotlin
package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.core.isPostable
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.mapper.WILDCARD_LAST4
import com.pennywiseai.ynab.data.mapper.toDomain
import com.pennywiseai.ynab.data.state.PostingStateStore
import com.pennywiseai.ynab.data.token.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single shared parse -> post path for both capture modes (design spec, ADR-0003).
 * classify() is the reusable decision (parse, skip, route, currency-guard, local dedup,
 * pause) that both process() (real-time single POST) and BackfillProcessor (bulk POST)
 * run. process() handles ONE message end-to-end: classify, record the terminal outcome,
 * and for a Postable, POST a one-element array and record. An un-parseable message is
 * dropped and never logged.
 */
@Singleton
class TransactionPipeline @Inject constructor(
    private val smsParser: SmsParser,
    private val mapper: TransactionMapper,
    private val resolver: MappingResolver,
    private val poster: TransactionPoster,
    private val mappingRuleDao: MappingRuleDao,
    private val processedMessageDao: ProcessedMessageDao,
    private val tokenStore: TokenStore,
    private val postingState: PostingStateStore,
) {

    /**
     * The shared decision, up to (but not including) the POST. Records NOTHING — the
     * caller records skip/pause rows and POSTs Postables. The only state it mutates is
     * the existing no-token pause latch (so a bad/absent token can't trigger a 401 storm).
     */
    suspend fun classify(body: String, sender: String, timestamp: Long): Classification {
        // 1. Parse. No parser match -> no import_id exists; drop silently.
        val parsed = smsParser.parse(body, sender, timestamp) ?: return Classification.Dropped
        val importId = mapper.importIdFor(parsed)

        // 2. Non-postable type (TRANSFER / BALANCE_UPDATE) -> skip before the mapper (ADR-0002).
        if (!parsed.type.isPostable()) {
            return Classification.Skipped(MessageStatus.SKIPPED_NON_TRANSACTION, parsed, importId)
        }

        // 3. Resolve the route (exact last4 beats bank wildcard). Missing OR broken -> fail
        //    fast as SKIPPED_UNROUTED; a broken route never hits the network.
        val rules = mappingRuleDao.getAll().map { it.toDomain() }
        val rule = resolver.resolve(rules, parsed.bankName, parsed.accountLast4)
        if (rule == null || rule.broken) {
            return Classification.Skipped(MessageStatus.SKIPPED_UNROUTED, parsed, importId)
        }

        // 4. Currency guard -> never POST a wrong-currency amount (no FX).
        if (!parsed.currency.equals(rule.currencyCode, ignoreCase = true)) {
            return Classification.Skipped(MessageStatus.SKIPPED_CURRENCY_MISMATCH, parsed, importId)
        }

        // 5. Local dedup (best-effort optimization only; YNAB import_id is the authority).
        if (processedMessageDao.getByImportId(importId)?.status == MessageStatus.POSTED) {
            return Classification.AlreadyPosted(parsed, importId)
        }

        // 6. Build the YNAB transaction.
        val transaction = mapper.map(parsed, rule)

        // 7. Pause / no-token short-circuit BEFORE the network (no 401 storm).
        val token = tokenStore.getToken()
        if (postingState.isPaused() || token.isNullOrBlank()) {
            if (token.isNullOrBlank()) postingState.setPaused(true)
            val error = if (token.isNullOrBlank()) ERROR_NO_TOKEN else ERROR_TOKEN_INVALID
            return Classification.Paused(parsed, importId, error)
        }

        return Classification.Postable(parsed, importId, rule, transaction)
    }

    /** Process ONE message end-to-end (real-time path): classify, record, POST if Postable. */
    suspend fun process(body: String, sender: String, timestamp: Long): PipelineResult =
        when (val c = classify(body, sender, timestamp)) {
            is Classification.Dropped -> PipelineResult.Dropped
            is Classification.Skipped -> {
                record(c.parsed, c.importId, c.status)
                PipelineResult.Skipped(c.status)
            }
            is Classification.AlreadyPosted -> PipelineResult.Posted
            is Classification.Paused -> {
                record(c.parsed, c.importId, MessageStatus.FAILED, c.error)
                PipelineResult.Failed(retryable = false)
            }
            is Classification.Postable -> postSingle(c)
        }

    private suspend fun postSingle(c: Classification.Postable): PipelineResult =
        when (val outcome = poster.post(c.rule.budgetId, listOf(c.transaction))) {
            is PostOutcome.Posted -> {
                record(c.parsed, c.importId, MessageStatus.POSTED)
                PipelineResult.Posted
            }
            is PostOutcome.Unauthorized -> {
                postingState.setPaused(true)
                record(c.parsed, c.importId, MessageStatus.FAILED, ERROR_TOKEN_INVALID)
                PipelineResult.Failed(retryable = false)
            }
            is PostOutcome.RouteBroken -> {
                mappingRuleDao.setBroken(c.rule.bankName, c.rule.last4 ?: WILDCARD_LAST4, true)
                record(c.parsed, c.importId, MessageStatus.FAILED, ERROR_ROUTE_BROKEN)
                PipelineResult.Failed(retryable = false)
            }
            is PostOutcome.Failed -> {
                record(c.parsed, c.importId, MessageStatus.FAILED, outcome.error)
                PipelineResult.Failed(outcome.retryable)
            }
        }

    private suspend fun record(
        parsed: ParsedTransaction,
        importId: String,
        status: MessageStatus,
        error: String? = null,
    ) {
        processedMessageDao.upsert(
            ProcessedMessageEntity(
                importId = importId,
                sender = parsed.sender,
                bankName = parsed.bankName,
                last4 = parsed.accountLast4,
                amount = parsed.amount,
                currency = parsed.currency,
                status = status,
                error = error,
                timestamp = parsed.timestamp,
            ),
        )
    }

    companion object {
        const val ERROR_NO_TOKEN = "no token - awaiting token"
        const val ERROR_TOKEN_INVALID = "token invalid - awaiting new token"
        const val ERROR_ROUTE_BROKEN = "route target missing - rule marked broken (remap needed)"
    }
}
```

- [ ] **Step 3: Run the Plan 4 pipeline suite to prove no behavior change**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.pipeline.TransactionPipelineTest"`
Expected: PASS — every existing case still green (the refactor is behavior-preserving).

- [ ] **Step 4: Write the `classify` seam test** `app/src/test/kotlin/com/pennywiseai/ynab/pipeline/ClassificationSeamTest.kt`

```kotlin
package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class ClassificationSeamTest {

    private val mapper = TransactionMapper(ZoneId.of("UTC"))
    private val resolver = MappingResolver()
    private val ruleDao = FakeMappingRuleDao(
        mutableListOf(
            MappingRuleEntity(id = 1, bankName = "HDFC Bank", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "INR"),
        ),
    )
    private val logDao = FakeProcessedMessageDao()
    private val tokenStore = FakeTokenStore("valid-pat")
    private val postingState = FakePostingStateStore()

    private var nextParsed: ParsedTransaction? = null

    private fun pipeline() = TransactionPipeline(
        smsParser = SmsParser { _, _, _ -> nextParsed },
        mapper = mapper, resolver = resolver, poster = FakeTransactionPoster(),
        mappingRuleDao = ruleDao, processedMessageDao = logDao,
        tokenStore = tokenStore, postingState = postingState,
    )

    private fun parsed(
        type: TransactionType = TransactionType.EXPENSE,
        bank: String = "HDFC Bank",
        last4: String? = "1234",
        currency: String = "INR",
    ) = ParsedTransaction(
        amount = BigDecimal("100.00"), type = type, merchant = "Coffee", reference = "ref1",
        accountLast4 = last4, balance = null, smsBody = "spent Rs 100 at Coffee ref1",
        sender = "VM-HDFCBK", timestamp = 1_753_000_000_000L, bankName = bank, currency = currency,
    )

    @Test
    fun `null parse is Dropped`() = runTest {
        nextParsed = null
        assertEquals(Classification.Dropped, pipeline().classify("junk", "S", 1L))
    }

    @Test
    fun `non-postable type classifies Skipped SKIPPED_NON_TRANSACTION`() = runTest {
        nextParsed = parsed(type = TransactionType.TRANSFER)
        val c = pipeline().classify("b", "s", 1L) as Classification.Skipped
        assertEquals(MessageStatus.SKIPPED_NON_TRANSACTION, c.status)
    }

    @Test
    fun `unrouted classifies Skipped SKIPPED_UNROUTED`() = runTest {
        nextParsed = parsed(bank = "ICICI Bank", last4 = "9999")
        val c = pipeline().classify("b", "s", 1L) as Classification.Skipped
        assertEquals(MessageStatus.SKIPPED_UNROUTED, c.status)
    }

    @Test
    fun `broken route classifies Skipped SKIPPED_UNROUTED without network`() = runTest {
        ruleDao.setBroken("HDFC Bank", "1234", true)
        nextParsed = parsed()
        val c = pipeline().classify("b", "s", 1L) as Classification.Skipped
        assertEquals(MessageStatus.SKIPPED_UNROUTED, c.status)
    }

    @Test
    fun `currency mismatch classifies Skipped SKIPPED_CURRENCY_MISMATCH`() = runTest {
        nextParsed = parsed(currency = "USD")
        val c = pipeline().classify("b", "s", 1L) as Classification.Skipped
        assertEquals(MessageStatus.SKIPPED_CURRENCY_MISMATCH, c.status)
    }

    @Test
    fun `already-POSTED import id classifies AlreadyPosted`() = runTest {
        nextParsed = parsed()
        val importId = mapper.importIdFor(nextParsed!!)
        logDao.upsert(
            ProcessedMessageEntity(
                importId = importId, sender = "s", bankName = "HDFC Bank", last4 = "1234",
                amount = BigDecimal("100.00"), currency = "INR", status = MessageStatus.POSTED, timestamp = 1L,
            ),
        )
        assertTrue(pipeline().classify("b", "s", 1L) is Classification.AlreadyPosted)
    }

    @Test
    fun `no token classifies Paused and latches postingPaused`() = runTest {
        val noToken = FakeTokenStore(null)
        val ps = FakePostingStateStore()
        val p = TransactionPipeline(
            smsParser = SmsParser { _, _, _ -> parsed() },
            mapper = mapper, resolver = resolver, poster = FakeTransactionPoster(),
            mappingRuleDao = ruleDao, processedMessageDao = logDao, tokenStore = noToken, postingState = ps,
        )
        val c = p.classify("b", "s", 1L) as Classification.Paused
        assertEquals(TransactionPipeline.ERROR_NO_TOKEN, c.error)
        assertTrue(ps.isPaused()) // latched so no 401 storm
    }

    @Test
    fun `valid token and route classifies Postable with mapped transaction`() = runTest {
        nextParsed = parsed()
        val c = pipeline().classify("b", "s", 1L) as Classification.Postable
        assertEquals("b1", c.rule.budgetId)
        assertEquals("a1", c.transaction.accountId)
        assertEquals(mapper.importIdFor(nextParsed!!), c.importId)
    }
}
```

- [ ] **Step 5: Run the seam test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.pipeline.ClassificationSeamTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/pipeline/Classification.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/pipeline/TransactionPipeline.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/pipeline/ClassificationSeamTest.kt
git commit -m "refactor: extract shared classify() seam from pipeline (DRY for backfill; ADR-0003)"
```

---

### Task 3: Notifier — channels + the three exception-only notifications

Deliver the notification surface both worker paths use before the workers exist, so Tasks 4/8 depend on a tested `Notifier`: two channels, the determinate-progress `ForegroundInfo` for the backfill service, and the three exception-only notifications (terminal failure, posting-paused, backfill summary). Uses only framework drawables (no new res asset). `NotificationCompat` is transitive via `androidx.core:core-ktx`.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/capture/notify/Notifier.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/capture/notify/NotifierTest.kt`

**Interfaces:**
- Consumes: `BackfillSummary` — **defined in this task's file as a top-level type** (Task 7's `BackfillProcessor` imports it from here).
- Produces:
  - `data class BackfillSummary(val posted: Int, val skipped: Int, val failed: Int)`
  - `@Singleton class Notifier @Inject constructor(@ApplicationContext context)`:
    - `fun backfillForegroundInfo(done: Int, total: Int): ForegroundInfo`
    - `fun notifyBackfillSummary(summary: BackfillSummary)`
    - `fun notifyTerminalFailure(sender: String)`
    - `fun notifyPaused()`
    - `companion object { const val BACKFILL_NOTIFICATION_ID; const val CHANNEL_PROGRESS; const val CHANNEL_ALERTS }`

- [ ] **Step 1: Create the Notifier** `app/src/main/kotlin/com/pennywiseai/ynab/capture/notify/Notifier.kt`

```kotlin
package com.pennywiseai.ynab.capture.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** posted / skipped / failed tallies from one backfill run (design spec, Notifications). */
data class BackfillSummary(val posted: Int, val skipped: Int, val failed: Int)

/**
 * Owns notification channels + the three exception-only notifications (design spec):
 * a determinate progress notification for the backfill foreground service, a one-shot
 * backfill summary, a terminal-failure alert, and a posting-paused alert. POSTED never
 * notifies. Uses framework drawables so no res asset is added. Posting no-ops without
 * POST_NOTIFICATIONS (API 33+) — the app still works via the in-app banner/history.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // minSdk 26, so NotificationChannel is always available (no version guard).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Import progress", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** The ongoing determinate notification WorkManager shows while the backfill runs. */
    fun backfillForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle("Importing transactions")
            .setContentText(if (total > 0) "$done / $total" else "Scanning…")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0) // indeterminate until total is known
            .build()
        return ForegroundInfo(
            BACKFILL_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /** One-shot end-of-backfill summary (design spec: "posted N · skipped M · failed K"). */
    fun notifyBackfillSummary(summary: BackfillSummary) {
        val text = "posted ${summary.posted} · skipped ${summary.skipped} · failed ${summary.failed}"
        post(
            SUMMARY_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle("Backfill complete")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Terminal FAILED alert — only fired once a failure is out of its auto-retry window. */
    fun notifyTerminalFailure(sender: String) {
        post(
            FAILURE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle("A transaction couldn't be posted")
                .setContentText("From $sender — open the app to retry.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Posting-paused alert (401 / no token) — surfaced prominently (design spec). */
    fun notifyPaused() {
        post(
            PAUSED_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle("Posting paused")
                .setContentText("Your YNAB token is missing or invalid. Update it to resume.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun post(id: Int, notification: android.app.Notification) {
        // No-ops silently if POST_NOTIFICATIONS is not granted (API 33+).
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        const val CHANNEL_PROGRESS = "import_progress"
        const val CHANNEL_ALERTS = "alerts"
        const val BACKFILL_NOTIFICATION_ID = 1001
        const val SUMMARY_NOTIFICATION_ID = 1002
        const val FAILURE_NOTIFICATION_ID = 1003
        const val PAUSED_NOTIFICATION_ID = 1004
    }
}
```

- [ ] **Step 2: Write the Notifier test** `app/src/test/kotlin/com/pennywiseai/ynab/capture/notify/NotifierTest.kt`

```kotlin
package com.pennywiseai.ynab.capture.notify

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifierTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val notifier = Notifier(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `both channels are created`() {
        val ids = manager.notificationChannels.map { it.id }.toSet()
        assertTrue(ids.contains(Notifier.CHANNEL_PROGRESS))
        assertTrue(ids.contains(Notifier.CHANNEL_ALERTS))
    }

    @Test
    fun `backfill summary posts a notification`() {
        notifier.notifyBackfillSummary(BackfillSummary(posted = 3, skipped = 1, failed = 0))
        assertNotNull(shadowOf(manager).getNotification(Notifier.SUMMARY_NOTIFICATION_ID))
    }

    @Test
    fun `terminal failure posts a notification`() {
        notifier.notifyTerminalFailure("VM-HDFCBK")
        assertNotNull(shadowOf(manager).getNotification(Notifier.FAILURE_NOTIFICATION_ID))
    }

    @Test
    fun `paused posts a notification`() {
        notifier.notifyPaused()
        assertNotNull(shadowOf(manager).getNotification(Notifier.PAUSED_NOTIFICATION_ID))
    }

    @Test
    fun `foreground info carries the backfill notification id`() {
        val info = notifier.backfillForegroundInfo(done = 2, total = 10)
        assertTrue(info.notificationId == Notifier.BACKFILL_NOTIFICATION_ID)
        assertNotNull(info.notification)
    }
}
```

- [ ] **Step 3: Run the Notifier test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.capture.notify.NotifierTest"`
Expected: PASS (5 tests). Robolectric supplies a real `NotificationManager`; `ShadowNotificationManager` records posts + channels.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/capture/notify/Notifier.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/capture/notify/NotifierTest.kt
git commit -m "feat: Notifier - channels + exception-only notifications (progress, summary, failure, paused)"
```

---

### Task 4: Real-time worker + scheduler

Deliver the real-time post path: an expedited `@HiltWorker` that runs `pipeline.process()` for one SMS and maps `PipelineResult` → WorkManager `Result` with a `runAttemptCount` retry ceiling and exception-only failure notification, plus `CaptureScheduler.enqueueRealtime(...)` that Task 5's receiver calls. The worker is the same code path for the first attempt and every retry (idempotent via `import_id`).

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsPostWorker.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/capture/SmsPostWorkerTest.kt`

**Interfaces:**
- Consumes: `TransactionPipeline.process` (Task 2), `Notifier` (Task 3), `PostingStateStore` (Plan 4).
- Produces:
  - `SmsPostWorker` — `@HiltWorker`; keys `KEY_BODY`/`KEY_SENDER`/`KEY_TIMESTAMP`; `MAX_ATTEMPTS = 8`.
  - `CaptureScheduler.enqueueRealtime(body: String, sender: String, timestamp: Long)` — expedited, network-constrained, exponential backoff, unique-per-message KEEP. (Task 8 adds `enqueueBackfill`/`cancelBackfill` to this same class.)

- [ ] **Step 1: Create the real-time worker** `app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsPostWorker.kt`

```kotlin
package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pennywiseai.ynab.capture.notify.Notifier
import com.pennywiseai.ynab.data.state.PostingStateStore
import com.pennywiseai.ynab.pipeline.PipelineResult
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Real-time post path (ADR-0003): parse->post ONE SMS off the main thread. First attempt
 * and every retry run the same code (idempotent via import_id). A retryable failure
 * (offline / 429 / 5xx) reschedules with backoff until MAX_ATTEMPTS (~24h ceiling), then
 * turns terminal. Terminal failures notify — but a paused pipeline notifies "paused",
 * not a per-message failure (design spec, Notifications).
 */
@HiltWorker
class SmsPostWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: TransactionPipeline,
    private val postingState: PostingStateStore,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    // Only used when expedited work runs as a foreground service on Android <= 11.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifier.backfillForegroundInfo(done = 0, total = 0)

    override suspend fun doWork(): Result {
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, 0L)

        return when (val result = pipeline.process(body, sender, timestamp)) {
            is PipelineResult.Failed -> {
                if (result.retryable && runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    // Terminal: distinguish a paused pipeline (surface pause) from a real failure.
                    if (postingState.isPaused()) notifier.notifyPaused() else notifier.notifyTerminalFailure(sender)
                    Result.failure()
                }
            }
            // Dropped / Skipped / Posted are all "handled" — nothing to reschedule.
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_BODY = "body"
        const val KEY_SENDER = "sender"
        const val KEY_TIMESTAMP = "timestamp"

        /** ~24h of exponential backoff from a 10s floor (design spec, Error handling). */
        const val MAX_ATTEMPTS = 8
    }
}
```

- [ ] **Step 2: Create the scheduler** `app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt`

```kotlin
package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues the capture WorkManager jobs (the API Plan 6's UI and Task 5's receiver call).
 * Real-time is expedited + single-message; backfill (Task 8) is a foreground worker.
 */
@Singleton
class CaptureScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Enqueue an expedited post for one received SMS. Unique-per-message so a duplicated
     *  broadcast doesn't double-process (post is idempotent regardless via import_id). */
    fun enqueueRealtime(body: String, sender: String, timestamp: Long) {
        val request = OneTimeWorkRequestBuilder<SmsPostWorker>()
            .setInputData(
                workDataOf(
                    SmsPostWorker.KEY_BODY to body,
                    SmsPostWorker.KEY_SENDER to sender,
                    SmsPostWorker.KEY_TIMESTAMP to timestamp,
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        val uniqueName = "realtime-${sender}-${timestamp}-${body.hashCode()}"
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }
}
```

- [ ] **Step 3: Write the worker test** `app/src/test/kotlin/com/pennywiseai/ynab/capture/SmsPostWorkerTest.kt`

```kotlin
package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.pennywiseai.ynab.capture.notify.Notifier
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.pipeline.PipelineResult
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsPostWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifier = Notifier(context)
    private val postingState = FakePostingStateStore()

    /** Build an SmsPostWorker whose pipeline.process(...) returns a fixed PipelineResult. */
    private fun worker(result: PipelineResult, attempt: Int = 1): SmsPostWorker {
        val pipeline = object : TransactionPipeline(
            smsParser = { _, _, _ -> null }, // unused: process() is overridden below
            mapper = com.pennywiseai.ynab.core.TransactionMapper(java.time.ZoneId.of("UTC")),
            resolver = com.pennywiseai.ynab.core.MappingResolver(),
            poster = com.pennywiseai.ynab.pipeline.FakeTransactionPoster(),
            mappingRuleDao = com.pennywiseai.ynab.pipeline.FakeMappingRuleDao(),
            processedMessageDao = com.pennywiseai.ynab.pipeline.FakeProcessedMessageDao(),
            tokenStore = com.pennywiseai.ynab.data.token.FakeTokenStore("t"),
            postingState = postingState,
        ) {
            override suspend fun process(body: String, sender: String, timestamp: Long) = result
        }
        return TestListenableWorkerBuilder<SmsPostWorker>(context)
            .setInputData(workDataOf(SmsPostWorker.KEY_BODY to "b", SmsPostWorker.KEY_SENDER to "S", SmsPostWorker.KEY_TIMESTAMP to 1L))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SmsPostWorker(appContext, workerParameters, pipeline, postingState, notifier)
            })
            .build()
    }

    @Test
    fun `posted is success`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(PipelineResult.Posted).doWork())
    }

    @Test
    fun `skipped is success`() = runTest {
        assertEquals(
            ListenableWorker.Result.success(),
            worker(PipelineResult.Skipped(com.pennywiseai.ynab.data.local.MessageStatus.SKIPPED_UNROUTED)).doWork(),
        )
    }

    @Test
    fun `retryable failure under the ceiling retries`() = runTest {
        assertEquals(ListenableWorker.Result.retry(), worker(PipelineResult.Failed(retryable = true), attempt = 1).doWork())
    }

    @Test
    fun `retryable failure at the ceiling is terminal failure`() = runTest {
        assertEquals(
            ListenableWorker.Result.failure(),
            worker(PipelineResult.Failed(retryable = true), attempt = SmsPostWorker.MAX_ATTEMPTS).doWork(),
        )
    }

    @Test
    fun `non-retryable failure is terminal failure`() = runTest {
        assertEquals(ListenableWorker.Result.failure(), worker(PipelineResult.Failed(retryable = false)).doWork())
    }
}
```

> **Note for the implementer:** the test subclasses `TransactionPipeline` and overrides `process()`. That requires `TransactionPipeline` and its `process` method to be **`open`**. In Task 2's file, change `class TransactionPipeline` → `open class TransactionPipeline` and `suspend fun process(` → `open suspend fun process(`. Make **only** these two `open` (Hilt can still inject an open class). If you prefer not to open the class, instead introduce a tiny `fun interface RealtimePipeline { suspend fun process(...): PipelineResult }` that `TransactionPipeline` implements and the worker depends on — but opening the two members is the smaller change. Apply the `open` edit as part of this step and re-run the Task 2 suites to confirm they still pass.

- [ ] **Step 4: Apply the `open` edit and run both suites**

Edit `TransactionPipeline` per the note (class + `process` become `open`).

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.pipeline.*" --tests "com.pennywiseai.ynab.capture.SmsPostWorkerTest"`
Expected: PASS — `TransactionPipelineTest`, `ClassificationSeamTest`, and the 5 worker cases.

- [ ] **Step 5: Assemble to prove the @HiltWorker dexes**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` — `SmsPostWorker`'s `@AssistedInject` graph compiles under the AndroidX-Hilt compiler.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsPostWorker.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/pipeline/TransactionPipeline.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/capture/SmsPostWorkerTest.kt
git commit -m "feat: expedited real-time SmsPostWorker + CaptureScheduler (retry ceiling, terminal notify)"
```

---

### Task 5: SMS receiver + multipart reassembly

Deliver the real-time entry point: a manifest `BroadcastReceiver` that reassembles a multipart SMS and hands `(body, sender, timestamp)` to `CaptureScheduler.enqueueRealtime`. `onReceive` does the minimum (ADR-0003) — no parsing, no network. The concatenation logic is a pure function so it's unit-tested without the framework `SmsMessage` (a final class).

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/capture/RawSms.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/capture/SmsReassembleTest.kt`

**Interfaces:**
- Consumes: `CaptureScheduler.enqueueRealtime` (Task 4).
- Produces:
  - `data class RawSms(val sender: String, val body: String, val timestamp: Long)`
  - `fun reassembleSms(bodies: List<String>, sender: String?, timestamp: Long): RawSms?` — null on empty parts or blank sender.
  - `SmsReceiver` — `@AndroidEntryPoint` receiver for `SMS_RECEIVED_ACTION`. **Also consumed by Task 6's `SmsInboxReader` (same `RawSms` type).**

- [ ] **Step 1: Create the RawSms model + reassembly** `app/src/main/kotlin/com/pennywiseai/ynab/capture/RawSms.kt`

```kotlin
package com.pennywiseai.ynab.capture

/** A raw SMS ready for the pipeline: full (reassembled) text + its sender + receipt time. */
data class RawSms(val sender: String, val body: String, val timestamp: Long)

/**
 * Reassemble a multipart SMS by concatenating each PDU part's body in order (design spec,
 * SMS capture). Returns null when there are no parts or the sender is blank (nothing the
 * pipeline could route). Kept a pure function so it's testable without the final
 * framework SmsMessage class — the receiver maps SmsMessage[] -> bodies/sender/timestamp.
 */
fun reassembleSms(bodies: List<String>, sender: String?, timestamp: Long): RawSms? {
    if (bodies.isEmpty()) return null
    if (sender.isNullOrBlank()) return null
    return RawSms(sender = sender, body = bodies.joinToString(separator = ""), timestamp = timestamp)
}
```

- [ ] **Step 2: Create the receiver** `app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsReceiver.kt`

```kotlin
package com.pennywiseai.ynab.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Real-time capture (ADR-0003): onReceive runs on the main thread under a ~10s ANR
 * ceiling, so it only reassembles the multipart PDUs and enqueues an expedited worker —
 * it never parses or posts inline. Guarded by BROADCAST_SMS in the manifest so only the
 * system can deliver the broadcast.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: CaptureScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val raw = reassembleSms(
            bodies = parts.map { it.messageBody.orEmpty() },
            sender = parts.first().originatingAddress,
            timestamp = parts.first().timestampMillis,
        ) ?: return

        scheduler.enqueueRealtime(raw.body, raw.sender, raw.timestamp)
    }
}
```

- [ ] **Step 3: Declare the permission + receiver** `app/src/main/AndroidManifest.xml`

Add the permission near the existing `INTERNET` line (outside `<application>`):

```xml
    <uses-permission android:name="android.permission.RECEIVE_SMS" />
```

And inside `<application>…</application>`, declare the receiver (system-only delivery via the `BROADCAST_SMS` permission):

```xml
        <receiver
            android:name=".capture.SmsReceiver"
            android:exported="true"
            android:permission="android.permission.BROADCAST_SMS">
            <intent-filter>
                <action android:name="android.provider.Telephony.SMS_RECEIVED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Write the reassembly test** `app/src/test/kotlin/com/pennywiseai/ynab/capture/SmsReassembleTest.kt`

```kotlin
package com.pennywiseai.ynab.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsReassembleTest {

    @Test
    fun `single part is returned as-is`() {
        val raw = reassembleSms(listOf("Spent Rs 100 at Coffee"), "VM-HDFCBK", 1_753_000_000_000L)
        assertEquals(RawSms("VM-HDFCBK", "Spent Rs 100 at Coffee", 1_753_000_000_000L), raw)
    }

    @Test
    fun `multiple parts concatenate in order with no separator`() {
        val raw = reassembleSms(listOf("Spent Rs 100 ", "at Coffee ", "ref ABC123"), "VM-HDFCBK", 42L)
        assertEquals("Spent Rs 100 at Coffee ref ABC123", raw?.body)
    }

    @Test
    fun `empty parts returns null`() {
        assertNull(reassembleSms(emptyList(), "VM-HDFCBK", 1L))
    }

    @Test
    fun `blank sender returns null`() {
        assertNull(reassembleSms(listOf("body"), "   ", 1L))
        assertNull(reassembleSms(listOf("body"), null, 1L))
    }
}
```

- [ ] **Step 5: Run the reassembly test + assemble**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.capture.SmsReassembleTest"`
Expected: PASS (4 tests).

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` — the `@AndroidEntryPoint` receiver + manifest merge compile.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/capture/RawSms.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsReceiver.kt \
        app/src/main/AndroidManifest.xml \
        app/src/test/kotlin/com/pennywiseai/ynab/capture/SmsReassembleTest.kt
git commit -m "feat: SmsReceiver + multipart reassembly -> enqueue expedited real-time post"
```

---

### Task 6: SMS inbox reader seam

Deliver the backfill's SMS source behind an interface so the bulk processor (Task 7) is testable without the content provider: `SmsInboxReader.read(fromMillis, toMillis)` returns `List<RawSms>` from `content://sms/inbox` (rows are already reassembled — no multipart handling). A Hilt `@Binds` provides the real `ContentResolver` impl; a `FakeSmsInboxReader` serves the tests.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsInboxReader.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/capture/ContentResolverSmsInboxReader.kt`
- Create (test): `app/src/test/kotlin/com/pennywiseai/ynab/capture/FakeSmsInboxReader.kt`

**Interfaces:**
- Consumes: `RawSms` (Task 5).
- Produces:
  - `interface SmsInboxReader { suspend fun read(fromMillis: Long, toMillis: Long): List<RawSms> }`
  - `ContentResolverSmsInboxReader` — `@Inject`, `@Binds` as `SmsInboxReader`.
  - `FakeSmsInboxReader(messages)` (test) — returns seeded rows in `[from, to)`.

- [ ] **Step 1: Create the interface + Hilt binding** `app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsInboxReader.kt`

```kotlin
package com.pennywiseai.ynab.capture

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The backfill's SMS source (design spec: on-demand date-range backfill reads
 * content://sms/inbox). A seam so BackfillProcessor is tested with a fake, and the real
 * ContentResolver query (framework glue) is covered by assembleDebug + the smoke check.
 */
interface SmsInboxReader {
    /** Inbox messages with DATE in [fromMillis, toMillis), newest first. Rows are already
     *  fully reassembled by the telephony provider (no multipart concatenation needed). */
    suspend fun read(fromMillis: Long, toMillis: Long): List<RawSms>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SmsInboxReaderModule {

    @Binds
    @Singleton
    abstract fun bindSmsInboxReader(impl: ContentResolverSmsInboxReader): SmsInboxReader
}
```

- [ ] **Step 2: Create the ContentResolver impl** `app/src/main/kotlin/com/pennywiseai/ynab/capture/ContentResolverSmsInboxReader.kt`

```kotlin
package com.pennywiseai.ynab.capture

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads content://sms/inbox over a date range (READ_SMS). Each row's BODY is the full
 * message (the provider concatenates multipart PDUs on store), so no reassembly is done
 * here — unlike the live receiver path. DATE is epoch millis.
 */
@Singleton
class ContentResolverSmsInboxReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsInboxReader {

    override suspend fun read(fromMillis: Long, toMillis: Long): List<RawSms> {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"
        val args = arrayOf(fromMillis.toString(), toMillis.toString())

        val result = mutableListOf<RawSms>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            args,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val sender = cursor.getString(addressIdx) ?: continue
                val body = cursor.getString(bodyIdx) ?: continue
                result += RawSms(sender = sender, body = body, timestamp = cursor.getLong(dateIdx))
            }
        }
        return result
    }
}
```

- [ ] **Step 3: Create the fake** `app/src/test/kotlin/com/pennywiseai/ynab/capture/FakeSmsInboxReader.kt`

```kotlin
package com.pennywiseai.ynab.capture

/** In-memory SmsInboxReader: returns seeded messages whose timestamp is in [from, to). */
class FakeSmsInboxReader(private val messages: List<RawSms> = emptyList()) : SmsInboxReader {
    override suspend fun read(fromMillis: Long, toMillis: Long): List<RawSms> =
        messages.filter { it.timestamp in fromMillis until toMillis }
            .sortedByDescending { it.timestamp }
}
```

- [ ] **Step 4: Assemble to prove the binding + query compile**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` — the `@Binds` graph and the `Telephony.Sms.Inbox` query compile. (The real query is exercised by the on-device smoke check; `BackfillProcessorTest` uses `FakeSmsInboxReader`.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/capture/SmsInboxReader.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/capture/ContentResolverSmsInboxReader.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/capture/FakeSmsInboxReader.kt
git commit -m "feat: SmsInboxReader seam + content://sms/inbox date-range query"
```

---

### Task 7: BackfillProcessor — bulk group / chunk / 400-fallback

Deliver the backfill engine (ADR-0004): classify a whole batch via the shared `classify()` seam, record the terminal outcomes, group the postables by budget, bulk-POST in chunks of ≤ 100, and on a chunk `400` fall back to individual POSTs so one bad element can't sink its chunk. Returns a `BackfillSummary`. Pure JVM — heavily tested with the existing fakes + a per-call poster responder.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillProcessor.kt`
- Modify (test): `app/src/test/kotlin/com/pennywiseai/ynab/pipeline/FakeTransactionPoster.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillProcessorTest.kt`

**Interfaces:**
- Consumes: `TransactionPipeline.classify` (Task 2), `TransactionPoster` (Plan 4), `ProcessedMessageDao`/`MappingRuleDao` (Plan 2/4), `PostingStateStore` (Plan 4), `BackfillSummary` (Task 3), `RawSms` (Task 5).
- Produces:
  - `@Singleton class BackfillProcessor @Inject constructor(pipeline, poster, processedMessageDao, mappingRuleDao, postingState)`
  - `suspend fun run(messages: List<RawSms>, onProgress: suspend (Int, Int) -> Unit = {_,_->}, isCancelled: () -> Boolean = { false }): BackfillSummary`
  - `companion object { const val CHUNK_SIZE = 100 }`
- `FakeTransactionPoster.responder` (test) — per-call outcome override.

- [ ] **Step 1: Add a per-call responder to the poster fake** `app/src/test/kotlin/com/pennywiseai/ynab/pipeline/FakeTransactionPoster.kt`

Replace the file with (adds `responder` + records every call; keeps the existing `outcome`/`calls`/`lastBudgetId`/`lastTransactions` API so Plan 3/4 tests still compile):

```kotlin
package com.pennywiseai.ynab.pipeline

import com.pennywiseai.ynab.core.model.SaveTransaction

/** Configurable TransactionPoster double. Set `outcome` for a fixed reply, or `responder`
 *  to vary the reply per call (budgetId, transactions) — the backfill needs both. */
class FakeTransactionPoster(var outcome: PostOutcome = PostOutcome.Posted) : TransactionPoster {
    var calls = 0
    var lastBudgetId: String? = null
    var lastTransactions: List<SaveTransaction> = emptyList()
    val allCalls = mutableListOf<Pair<String, List<SaveTransaction>>>()
    var responder: ((String, List<SaveTransaction>) -> PostOutcome)? = null

    override suspend fun post(budgetId: String, transactions: List<SaveTransaction>): PostOutcome {
        calls++
        lastBudgetId = budgetId
        lastTransactions = transactions
        allCalls += budgetId to transactions
        return responder?.invoke(budgetId, transactions) ?: outcome
    }
}
```

- [ ] **Step 2: Create the processor** `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillProcessor.kt`

```kotlin
package com.pennywiseai.ynab.capture

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.capture.notify.BackfillSummary
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.mapper.WILDCARD_LAST4
import com.pennywiseai.ynab.data.state.PostingStateStore
import com.pennywiseai.ynab.pipeline.Classification
import com.pennywiseai.ynab.pipeline.PostOutcome
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import com.pennywiseai.ynab.pipeline.TransactionPoster
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backfill engine (ADR-0004). Classifies a whole batch with the SHARED classify()
 * seam (so it agrees with real-time on what's postable), records the terminal outcomes,
 * then groups postables by budget and bulk-POSTs in chunks. A bulk POST is atomic, so a
 * chunk 400 (a genuine bad element) falls back to individual POSTs — good rows POSTED,
 * only the bad row(s) FAILED. Duplicates are POSTED (not errors). Idempotent via import_id.
 */
@Singleton
class BackfillProcessor @Inject constructor(
    private val pipeline: TransactionPipeline,
    private val poster: TransactionPoster,
    private val processedMessageDao: ProcessedMessageDao,
    private val mappingRuleDao: MappingRuleDao,
    private val postingState: PostingStateStore,
) {

    private class Tally {
        var posted = 0
        var skipped = 0
        var failed = 0
        fun toSummary() = BackfillSummary(posted, skipped, failed)
    }

    suspend fun run(
        messages: List<RawSms>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): BackfillSummary {
        val tally = Tally()
        val total = messages.size
        val postables = mutableListOf<Classification.Postable>()

        // Phase 1: classify + record terminal outcomes locally (no network).
        for ((index, message) in messages.withIndex()) {
            if (isCancelled()) return tally.toSummary()
            when (val c = pipeline.classify(message.body, message.sender, message.timestamp)) {
                is Classification.Dropped -> {} // never logged
                is Classification.Skipped -> { record(c.parsed, c.importId, c.status); tally.skipped++ }
                is Classification.AlreadyPosted -> tally.posted++ // already POSTED; row stands
                is Classification.Paused -> {
                    record(c.parsed, c.importId, MessageStatus.FAILED, c.error); tally.failed++
                }
                is Classification.Postable -> postables += c
            }
            onProgress(index + 1, total)
        }

        // Phase 2: group by budget, bulk-POST in chunks.
        for ((budgetId, group) in postables.groupBy { it.rule.budgetId }) {
            for (chunk in group.chunked(CHUNK_SIZE)) {
                if (isCancelled()) return tally.toSummary()
                postChunk(budgetId, chunk, tally)
            }
        }
        return tally.toSummary()
    }

    private suspend fun postChunk(budgetId: String, chunk: List<Classification.Postable>, tally: Tally) {
        when (val outcome = poster.post(budgetId, chunk.map { it.transaction })) {
            is PostOutcome.Posted -> {
                chunk.forEach { record(it.parsed, it.importId, MessageStatus.POSTED) }
                tally.posted += chunk.size
            }
            is PostOutcome.Unauthorized -> {
                postingState.setPaused(true)
                chunk.forEach { record(it.parsed, it.importId, MessageStatus.FAILED, TransactionPipeline.ERROR_TOKEN_INVALID) }
                tally.failed += chunk.size
            }
            is PostOutcome.RouteBroken -> {
                chunk.map { it.rule }.distinctBy { it.bankName to it.last4 }
                    .forEach { mappingRuleDao.setBroken(it.bankName, it.last4 ?: WILDCARD_LAST4, true) }
                chunk.forEach { record(it.parsed, it.importId, MessageStatus.FAILED, TransactionPipeline.ERROR_ROUTE_BROKEN) }
                tally.failed += chunk.size
            }
            is PostOutcome.Failed -> {
                if (!outcome.retryable) {
                    // 400 on an atomic chunk: isolate the bad element(s) with individual POSTs.
                    chunk.forEach { postChunk(budgetId, listOf(it), tally).takeIf { chunk.size > 1 } }
                    if (chunk.size == 1) recordFailed(chunk.single(), outcome.error, tally)
                } else {
                    chunk.forEach { record(it.parsed, it.importId, MessageStatus.FAILED, outcome.error) }
                    tally.failed += chunk.size
                }
            }
        }
    }

    private suspend fun recordFailed(item: Classification.Postable, error: String, tally: Tally) {
        record(item.parsed, item.importId, MessageStatus.FAILED, error)
        tally.failed++
    }

    private suspend fun record(
        parsed: ParsedTransaction,
        importId: String,
        status: MessageStatus,
        error: String? = null,
    ) {
        processedMessageDao.upsert(
            ProcessedMessageEntity(
                importId = importId,
                sender = parsed.sender,
                bankName = parsed.bankName,
                last4 = parsed.accountLast4,
                amount = parsed.amount,
                currency = parsed.currency,
                status = status,
                error = error,
                timestamp = parsed.timestamp,
            ),
        )
    }

    companion object {
        /** ≤ 100 transactions per bulk POST (ADR-0004) — a 600-msg backfill = a handful of requests. */
        const val CHUNK_SIZE = 100
    }
}
```

> **Implementer note — the 400-fallback recursion.** The `is PostOutcome.Failed` non-retryable branch must re-POST each element **individually** and record each element's own result. The compact `chunk.forEach { postChunk(budgetId, listOf(it), tally) ... }` above is subtle; implement it as the explicit loop below instead (clearer, same behavior) and delete `recordFailed`:
>
> ```kotlin
> is PostOutcome.Failed -> {
>     if (outcome.retryable) {
>         chunk.forEach { record(it.parsed, it.importId, MessageStatus.FAILED, outcome.error) }
>         tally.failed += chunk.size
>     } else if (chunk.size == 1) {
>         // A single element that 400s is genuinely bad — record its error, no further split.
>         record(chunk.single().parsed, chunk.single().importId, MessageStatus.FAILED, outcome.error)
>         tally.failed++
>     } else {
>         // Atomic chunk 400: isolate by re-posting each element on its own.
>         chunk.forEach { postChunk(budgetId, listOf(it), tally) }
>     }
> }
> ```
>
> Use this explicit form in the source; the tests below assume it.

- [ ] **Step 3: Write the processor test** `app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillProcessorTest.kt`

```kotlin
package com.pennywiseai.ynab.capture

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import com.pennywiseai.ynab.pipeline.FakeMappingRuleDao
import com.pennywiseai.ynab.pipeline.FakeProcessedMessageDao
import com.pennywiseai.ynab.pipeline.FakeTransactionPoster
import com.pennywiseai.ynab.pipeline.PostOutcome
import com.pennywiseai.ynab.pipeline.SmsParser
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class BackfillProcessorTest {

    private val logDao = FakeProcessedMessageDao()
    private val ruleDao = FakeMappingRuleDao(
        mutableListOf(
            MappingRuleEntity(id = 1, bankName = "HDFC Bank", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "INR"),
            MappingRuleEntity(id = 2, bankName = "ICICI Bank", last4 = "5678", budgetId = "b2", accountId = "a2", currencyCode = "INR"),
        ),
    )
    private val postingState = FakePostingStateStore()
    private val poster = FakeTransactionPoster()

    // Parser keyed off the SMS body so each message maps to a distinct bank/reference.
    private val parser = SmsParser { body, sender, timestamp ->
        when {
            body.startsWith("HDFC") -> parsed("HDFC Bank", "1234", body, sender, timestamp)
            body.startsWith("ICICI") -> parsed("ICICI Bank", "5678", body, sender, timestamp)
            else -> null
        }
    }

    private fun parsed(bank: String, last4: String, body: String, sender: String, ts: Long) = ParsedTransaction(
        amount = BigDecimal("100.00"), type = TransactionType.EXPENSE, merchant = "M", reference = body,
        accountLast4 = last4, balance = null, smsBody = body, sender = sender, timestamp = ts,
        bankName = bank, currency = "INR",
    )

    private fun pipeline() = TransactionPipeline(
        smsParser = parser,
        mapper = TransactionMapper(ZoneId.of("UTC")),
        resolver = MappingResolver(),
        poster = poster, // classify() never posts; process() would, but backfill calls classify only
        mappingRuleDao = ruleDao, processedMessageDao = logDao,
        tokenStore = FakeTokenStore("valid-pat"), postingState = postingState,
    )

    private fun processor() = BackfillProcessor(pipeline(), poster, logDao, ruleDao, postingState)

    private fun sms(body: String, ts: Long) = RawSms("VM-BANK", body, ts)

    @Test
    fun `postables are grouped into one bulk POST per budget`() = runTest {
        val summary = processor().run(
            listOf(sms("HDFC a", 1), sms("HDFC b", 2), sms("ICICI c", 3)),
        )
        assertEquals(2, poster.calls) // one per budget, not per message
        assertEquals(3, summary.posted)
        assertEquals(setOf("b1", "b2"), poster.allCalls.map { it.first }.toSet())
    }

    @Test
    fun `unrouted and non-transaction are skipped and not posted`() = runTest {
        val summary = processor().run(listOf(sms("UNKNOWN x", 1), sms("HDFC ok", 2)))
        assertEquals(1, summary.posted)
        // "UNKNOWN" parses to null -> Dropped (not logged), so skipped stays 0 here.
        assertEquals(0, summary.skipped)
        assertEquals(MessageStatus.POSTED, logDao.getAll().single().status)
    }

    @Test
    fun `a duplicate import id in a 2xx chunk is POSTED`() = runTest {
        poster.outcome = PostOutcome.Posted // YNAB reports the dup inside a 2xx -> Posted
        val summary = processor().run(listOf(sms("HDFC dup", 1)))
        assertEquals(1, summary.posted)
    }

    @Test
    fun `chunk 400 falls back to individual posts - good rows POSTED, bad row FAILED`() = runTest {
        // First (bulk) call 400s; then per-element: the element whose ref contains BAD 400s, others 2xx.
        poster.responder = { _, txns ->
            if (txns.size > 1) PostOutcome.Failed(retryable = false, error = "HTTP 400")
            else if (txns.single().memo?.contains("BAD") == true || txns.single().importId.isNotEmpty() && false)
                PostOutcome.Failed(retryable = false, error = "HTTP 400")
            else PostOutcome.Posted
        }
        // Simpler: drive the bad-row decision off the transaction's payee/memo via the body.
        poster.responder = { _, txns ->
            when {
                txns.size > 1 -> PostOutcome.Failed(retryable = false, error = "HTTP 400")
                txns.single().memo?.contains("BAD") == true -> PostOutcome.Failed(retryable = false, error = "HTTP 400")
                else -> PostOutcome.Posted
            }
        }
        val summary = processor().run(
            listOf(sms("HDFC good1", 1), sms("HDFC BAD", 2), sms("HDFC good2", 3)),
        )
        assertEquals(2, summary.posted)
        assertEquals(1, summary.failed)
        // 1 bulk call + 3 individual retries = 4 poster calls.
        assertEquals(4, poster.calls)
    }

    @Test
    fun `retryable chunk failure marks the whole chunk FAILED`() = runTest {
        poster.outcome = PostOutcome.Failed(retryable = true, error = "HTTP 429")
        val summary = processor().run(listOf(sms("HDFC a", 1), sms("HDFC b", 2)))
        assertEquals(0, summary.posted)
        assertEquals(2, summary.failed)
        assertEquals(1, poster.calls) // no per-element fallback for a retryable failure
    }

    @Test
    fun `401 pauses posting and fails the chunk`() = runTest {
        poster.outcome = PostOutcome.Unauthorized
        val summary = processor().run(listOf(sms("HDFC a", 1)))
        assertEquals(1, summary.failed)
        assertEquals(true, postingState.isPaused())
    }

    @Test
    fun `404 marks the route broken and fails the chunk`() = runTest {
        poster.outcome = PostOutcome.RouteBroken
        processor().run(listOf(sms("HDFC a", 1)))
        assertEquals(true, ruleDao.getAll().single { it.bankName == "HDFC Bank" }.broken)
    }

    @Test
    fun `cancellation before posting stops the run`() = runTest {
        val summary = processor().run(
            messages = listOf(sms("HDFC a", 1), sms("HDFC b", 2)),
            isCancelled = { true }, // cancelled from the first check
        )
        assertEquals(0, poster.calls)
        assertEquals(BackfillSummary(0, 0, 0), summary)
    }
}
```

> **Implementer note:** in the 400-fallback test, the "bad row" is selected by `memo?.contains("BAD")`. `memo` is derived from the parsed `reference`, which the fake parser sets to the SMS body — so the `"HDFC BAD"` message maps to a transaction whose memo contains `BAD`. If your `TransactionMapper` prefixes/suffixes the memo, adjust the substring accordingly (keep the marker distinctive). Delete the first, dead `poster.responder =` assignment shown mid-test — it's there only to illustrate; the second assignment is the one that runs.

- [ ] **Step 4: Run the processor test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.capture.BackfillProcessorTest"`
Expected: PASS (8 tests). Also re-run the poster/pipeline suites to confirm the `FakeTransactionPoster` change didn't break them:
Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.pipeline.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillProcessor.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/pipeline/FakeTransactionPoster.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillProcessorTest.kt
git commit -m "feat: BackfillProcessor - bulk POST grouped by budget with 400->individual fallback (ADR-0004)"
```

---

### Task 8: BackfillWorker + scheduler + foreground manifest

Deliver the backfill runtime: a foreground `dataSync` `@HiltWorker` that reads the inbox range, drives `BackfillProcessor` with a determinate progress notification and cooperative `isStopped` cancellation, and fires the summary; plus `CaptureScheduler.enqueueBackfill(from, to)` / `cancelBackfill()` (the API Plan 6's date-range screen calls) and the manifest FGS declarations. This closes the SMS-capture subsystem.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillWorker.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt`, `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillWorkerTest.kt`

**Interfaces:**
- Consumes: `SmsInboxReader` (Task 6), `BackfillProcessor` (Task 7), `Notifier` (Task 3).
- Produces:
  - `BackfillWorker` — `@HiltWorker`; keys `KEY_FROM`/`KEY_TO`; `WORK_NAME = "sms-backfill"`.
  - `CaptureScheduler.enqueueBackfill(fromMillis: Long, toMillis: Long)` — foreground, network-constrained, unique KEEP.
  - `CaptureScheduler.cancelBackfill()` — `cancelUniqueWork(BackfillWorker.WORK_NAME)`.

- [ ] **Step 1: Create the backfill worker** `app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillWorker.kt`

```kotlin
package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pennywiseai.ynab.capture.notify.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * On-demand backfill (design spec + ADR-0004): a foreground dataSync worker that reads the
 * SMS inbox for a date range, runs BackfillProcessor (bulk POST grouped by budget), shows a
 * determinate progress notification, supports cooperative cancellation (stop after the
 * in-flight chunk via isStopped), and ends with the exception-only summary notification.
 * Overlap with real-time is safe (import_id dedup); "resume" isn't a mode — re-running the
 * same range is cheap because dedup skips what's done.
 */
@HiltWorker
class BackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reader: SmsInboxReader,
    private val processor: BackfillProcessor,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.backfillForegroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val from = inputData.getLong(KEY_FROM, 0L)
        val to = inputData.getLong(KEY_TO, Long.MAX_VALUE)

        setForeground(notifier.backfillForegroundInfo(done = 0, total = 0))

        val messages = reader.read(from, to)
        val summary = processor.run(
            messages = messages,
            onProgress = { done, total -> setForeground(notifier.backfillForegroundInfo(done, total)) },
            isCancelled = { isStopped },
        )

        notifier.notifyBackfillSummary(summary)
        return Result.success()
    }

    companion object {
        const val KEY_FROM = "from_millis"
        const val KEY_TO = "to_millis"
        const val WORK_NAME = "sms-backfill"
    }
}
```

- [ ] **Step 2: Add backfill enqueue/cancel to the scheduler** `app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt`

Add these two methods inside `CaptureScheduler` (the imports `OneTimeWorkRequestBuilder`, `Constraints`, `NetworkType`, `ExistingWorkPolicy`, `workDataOf`, `BackoffPolicy`, `WorkRequest`, `TimeUnit` are already present from Task 4):

```kotlin
    /** Enqueue a foreground backfill over [fromMillis, toMillis). Unique KEEP so a second
     *  request while one runs is ignored (re-running a range is safe but pointless). */
    fun enqueueBackfill(fromMillis: Long, toMillis: Long) {
        val request = OneTimeWorkRequestBuilder<BackfillWorker>()
            .setInputData(
                workDataOf(
                    BackfillWorker.KEY_FROM to fromMillis,
                    BackfillWorker.KEY_TO to toMillis,
                ),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniqueWork(BackfillWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Cancel an in-flight backfill; the worker stops after the current chunk (isStopped). */
    fun cancelBackfill() {
        workManager.cancelUniqueWork(BackfillWorker.WORK_NAME)
    }
```

- [ ] **Step 3: Declare the foreground-service permissions + service type** `app/src/main/AndroidManifest.xml`

Add the permissions near the existing `RECEIVE_SMS` line (outside `<application>`):

```xml
    <uses-permission android:name="android.permission.READ_SMS" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

And inside `<application>…</application>`, merge the `dataSync` type onto WorkManager's foreground service (WorkManager declares the `<service>`; we contribute only the type, required on Android 14+):

```xml
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />
```

- [ ] **Step 4: Write the backfill worker test** `app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillWorkerTest.kt`

```kotlin
package com.pennywiseai.ynab.capture

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.pennywiseai.ynab.capture.notify.Notifier
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import com.pennywiseai.ynab.pipeline.FakeMappingRuleDao
import com.pennywiseai.ynab.pipeline.FakeProcessedMessageDao
import com.pennywiseai.ynab.pipeline.FakeTransactionPoster
import com.pennywiseai.ynab.pipeline.SmsParser
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifier = Notifier(context)
    private val logDao = FakeProcessedMessageDao()
    private val ruleDao = FakeMappingRuleDao(
        mutableListOf(
            MappingRuleEntity(id = 1, bankName = "HDFC Bank", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "INR"),
        ),
    )
    private val poster = FakeTransactionPoster()

    private val parser = SmsParser { body, sender, ts ->
        if (body.startsWith("HDFC")) ParsedTransaction(
            amount = BigDecimal("100.00"), type = TransactionType.EXPENSE, merchant = "M", reference = body,
            accountLast4 = "1234", balance = null, smsBody = body, sender = sender, timestamp = ts,
            bankName = "HDFC Bank", currency = "INR",
        ) else null
    }

    private fun pipeline() = TransactionPipeline(
        smsParser = parser, mapper = TransactionMapper(ZoneId.of("UTC")), resolver = MappingResolver(),
        poster = poster, mappingRuleDao = ruleDao, processedMessageDao = logDao,
        tokenStore = FakeTokenStore("t"), postingState = FakePostingStateStore(),
    )

    private fun worker(reader: SmsInboxReader): BackfillWorker {
        val processor = BackfillProcessor(pipeline(), poster, logDao, ruleDao, FakePostingStateStore())
        return TestListenableWorkerBuilder<BackfillWorker>(context)
            .setInputData(workDataOf(BackfillWorker.KEY_FROM to 0L, BackfillWorker.KEY_TO to 1000L))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, name: String, params: WorkerParameters) =
                    BackfillWorker(appContext, params, reader, processor, notifier)
            })
            .build()
    }

    @Test
    fun `reads the inbox, posts, and fires the summary`() = runTest {
        val reader = FakeSmsInboxReader(listOf(RawSms("VM-HDFCBK", "HDFC a", 10L), RawSms("VM-HDFCBK", "HDFC b", 20L)))
        val result = worker(reader).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, poster.calls) // one bulk POST for budget b1
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(shadowOf(manager).getNotification(Notifier.SUMMARY_NOTIFICATION_ID))
    }

    @Test
    fun `empty range still succeeds and summarizes zero`() = runTest {
        val result = worker(FakeSmsInboxReader(emptyList())).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, poster.calls)
    }
}
```

- [ ] **Step 5: Run the backfill worker test + assemble**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.capture.BackfillWorkerTest"`
Expected: PASS (2 tests). `setForeground` under `TestListenableWorkerBuilder` is a no-op transition; the run still completes and posts the summary.

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` — `BackfillWorker`'s `@AssistedInject` graph + the FGS manifest merge compile.

- [ ] **Step 6: Full suite green + commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — the entire app unit-test suite (Plans 1–5).

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/capture/BackfillWorker.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/capture/CaptureScheduler.kt \
        app/src/main/AndroidManifest.xml \
        app/src/test/kotlin/com/pennywiseai/ynab/capture/BackfillWorkerTest.kt
git commit -m "feat: foreground BackfillWorker + enqueue/cancel + dataSync FGS manifest (closes SMS capture)"
```

---

## On-device smoke check (spec Definition of Done — manual, not a unit test)

After Task 8, verify the framework wiring the JVM tests can't: install a debug build on a device/emulator with a SIM or SMS-injection, grant `RECEIVE_SMS`/`READ_SMS`/`POST_NOTIFICATIONS`, configure a valid token + one rule (via a temporary debug entry point or `adb`), then:
1. Inject a matching bank SMS (`adb emu sms send …` or a real message) → confirm within a few seconds a YNAB transaction appears (or the message is correctly `SKIPPED_*` in the log).
2. Trigger a backfill over a range containing known messages (temporary debug button calling `CaptureScheduler.enqueueBackfill`) → confirm the determinate progress notification, the summary notification, and that re-running the same range double-posts nothing (dedup).

This is the spec's "on-device smoke check" and is expected to be exercised once the Plan 6 UI provides the permission flow + backfill screen.

---

## Self-Review

**1. Spec coverage (SMS-capture slice of design-spec.md):**
- Real-time `BroadcastReceiver` on `SMS_RECEIVED` + multipart reassembly + expedited WorkManager job → Tasks 4–5. ✓ (`onReceive` never posts inline — ADR-0003.)
- On-demand date-range backfill: one foreground worker, inbox query, parse+route each, bulk-POST grouped by budget → Tasks 6–8. ✓ (ADR-0004 grouping/chunking/400-fallback in Task 7.)
- Backfill progress = determinate foreground notification (`posted/total`) → Task 8 `onProgress` + Task 3 `backfillForegroundInfo`. ✓ (progress bar is over messages examined; the summary carries `posted N`.)
- Cancellation mid-run, stop after in-flight chunk, resume-is-not-a-mode → Task 7 `isCancelled`/`isStopped` + Task 8 `cancelBackfill`. ✓
- Rate limit handled reactively (429 retryable) → poster classification (Plan 4) honored. Real-time retries via WorkManager backoff (Task 4). **Backfill records retryable failures `FAILED` in-place and does not auto-retry the worker (Task 7)** — recovery is an idempotent re-run of the range (Plan 6 manual action). Reviewed and accepted 2026-07-28. ✓
- Permissions `RECEIVE_SMS`/`READ_SMS`/`POST_NOTIFICATIONS`/`FOREGROUND_SERVICE` → Tasks 5, 8. ✓ (runtime request flow explicitly deferred to Plan 6 — noted in Global Constraints.)
- Error handling: retryable→WorkManager backoff w/ ~24h ceiling, terminal 400/404, 401/no-token pause → Task 4 (`MAX_ATTEMPTS`, terminal notify) + pipeline (Plan 4). ✓
- Notifications exception-only (no notif on POSTED; terminal FAILED; paused; backfill summary) → Task 3 `Notifier`, wired in Tasks 4 & 8. ✓
- Single shared pipeline for both modes → Task 2 `classify()` seam (ADR-0003). ✓
- Single network destination / on-device parsing / token never leaves except to YNAB → no new endpoint added; workers drive Plan 4's poster only. ✓

**2. Placeholder scan:** No "TBD"/"add error handling"/"write tests for the above". Every code + test step carries real content. The two implementer notes (worker `open`, 400-fallback recursion) give the exact edit, not a vague direction. ✓

**3. Type consistency:**
- `Classification` variants defined in Task 2 are consumed with matching shapes in Tasks 4 (`process` via seam) and 7 (`Postable.rule`/`.transaction`/`.parsed`/`.importId`). ✓
- `BackfillSummary(posted, skipped, failed)` defined in Task 3, imported by Task 7's processor + returned to Task 8's worker. ✓
- `CaptureScheduler` created in Task 4 (`enqueueRealtime`) and extended in Task 8 (`enqueueBackfill`/`cancelBackfill`) — same class, `workManager` field reused. ✓
- Worker input-data keys (`SmsPostWorker.KEY_*`, `BackfillWorker.KEY_*`) are referenced from the scheduler by their companion constants — no string drift. ✓
- `RawSms` defined in Task 5, consumed by Task 6 (`SmsInboxReader`) and Task 7 (`run(messages: List<RawSms>)`). ✓
- `FakeTransactionPoster` gains `responder`/`allCalls` in Task 7 without removing the `outcome`/`calls`/`lastBudgetId`/`lastTransactions` fields Plan 3/4 tests use. ✓
- `Notifier.backfillForegroundInfo(done, total)` signature matches both `getForegroundInfo` (0,0) and `onProgress` callers. ✓

One gap surfaced and resolved: Task 4's `SmsPostWorkerTest` subclasses `TransactionPipeline`, which requires the class + `process` to be `open` — folded into Task 4 Step 3/4 as an explicit edit with a re-run of the Task 2 suites, rather than left implicit.

---

**Plan complete and saved to `docs/superpowers/plans/2026-07-28-sms-capture.md`.** Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh **Opus** subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
