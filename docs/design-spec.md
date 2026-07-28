# pennywise-ynab — Design Spec

**Date:** 2026-07-28
**Status:** Approved design, pre-implementation — refined during a grilling session
**Working title:** pennywise-ynab (repo: `pennywiseai-ynab`)

> **Refined during grilling (2026-07-28).** Several sections below were sharpened
> against `parser-core` ground truth and the YNAB API. The authoritative record
> of those decisions is this repo's `CONTEXT.md` (glossary) and
> `docs/adr/0001–0004`. Where this doc and an ADR disagree, the ADR
> wins. Key changes: content-only `import_id` drops the dead `.take(33)`
> (ADR-0001); only `INCOME`/`EXPENSE`/`CREDIT`/`INVESTMENT` post, `TRANSFER` and
> `BALANCE_UPDATE` are skipped (ADR-0002); capture only enqueues, one worker
> posts (ADR-0003); backfill bulk-POSTs grouped by budget under YNAB's 200 req/hr
> limit (ADR-0004); a currency-mismatch guard skips rather than posting a wrong
> amount.

## Goal

A standalone Android app that reads bank SMS on-device, parses each message into
a transaction using PennyWise's existing `parser-core` library, and posts the
result to the **YNAB** (You Need A Budget) API as a new transaction — fully
automatically. It is primarily a personal tool but is intended to be published
as signed releases for others to install.

## Decisions (locked)

| Question | Decision |
|---|---|
| Platform | Standalone Android app (reads SMS on-device, no server) |
| Post flow | **Fully automatic** — every parsed + routed **postable** SMS is POSTed immediately (no in-app review); `TRANSFER`/`BALANCE_UPDATE`/currency-mismatch are skipped and logged. Dedup via `import_id`. |
| Account routing | **User-defined mapping in settings**: `(bank, last4)` → a specific YNAB budget + account. Unmapped messages are skipped and logged. |
| SMS capture | **Real-time** `BroadcastReceiver` **+** on-demand **date-range backfill** from the SMS inbox. |
| YNAB auth | **Personal Access Token (PAT)**, stored encrypted on-device. |
| `parser-core` consumption | **Separate public repo + git submodule** pinned to a commit of `pennywiseai-tracker`. |
| Distribution | Decided later; keep manifest + signing clean so GitHub / F-Droid / Play all stay open. (Note: SMS permissions are restricted on Google Play; GitHub + F-Droid is the likely path.) |
| YNAB HTTP client | **Retrofit + OkHttp** |

## Non-goals (YAGNI for v1)

Categories / category-guessing, OAuth login, multi-device sync, cloud backup,
YNAB transfers, currency conversion, in-app editing of transactions.

## Security & privacy posture

The app reads *every* SMS on the device, so its data-flow invariant is a
first-class design property, not an afterthought:

- **Single network destination.** The only endpoint the app ever contacts is
  YNAB (`api.ynab.com`) over HTTPS. **No** analytics, **no** crash reporting
  (no Firebase/Crashlytics), no other host — in v1.
- **On-device parsing.** SMS content is parsed locally. Only the fields of a
  *routed, postable* transaction (`payee`=merchant, `memo`=reference, `amount`,
  `date`, `import_id`) ever leave the device, and only to YNAB.
- **Token handling.** The PAT lives only in `EncryptedSharedPreferences` and is
  sent only in the YNAB `Authorization` header.
- **Consequences.** This keeps the threat model to one trusted destination, keeps
  the app F-Droid-eligible (its inclusion criteria forbid proprietary trackers),
  and is the honest posture for an app with this permission surface. The accepted
  cost is no field crash telemetry — any future observability must be **opt-in and
  privacy-preserving**, never a default tracker. A contributor adding a crash SDK
  would silently break this invariant, so it is stated here explicitly.

## Project structure

Separate public repo; `parser-core` vendored as a git submodule so parser
updates arrive via deliberate, reviewable pointer bumps.

```
pennywiseai-ynab/
├── app/                      # Android app (Compose + M3, Hilt)
├── parser-core/              # git submodule → pinned commit of pennywiseai-tracker
├── CONTEXT.md                # domain glossary
├── docs/
│   ├── design-spec.md        # this document
│   └── adr/                  # 0001–0004
├── settings.gradle.kts       # include(":parser-core") + include(":app")
└── README.md                 # setup incl. `git clone --recurse-submodules`
```

Stack mirrors PennyWise so `parser-core` drops in unchanged: Kotlin, JDK 21
toolchain, Jetpack Compose + Material 3, Hilt DI, Room, WorkManager,
kotlinx.serialization, Retrofit + OkHttp.

`parser-core` is a pure-Kotlin KMP module with no Android deps. Its entry point:

```kotlin
BankParserFactory.parse(body, sender, timestamp)  // → ParsedTransaction?  (content-aware: tries all matching parsers)
```

No parser code is modified. Updating parsers = `git submodule update --remote`
+ review + commit the pointer bump.

## SMS capture

- **Real-time:** `BroadcastReceiver` on `android.provider.Telephony.SMS_RECEIVED`
  → **reassemble the multipart PDUs** (`getMessagesFromIntent`, concatenate all
  parts' `messageBody`) into the full text → enqueue an **expedited WorkManager
  job** that runs the pipeline. `onReceive` never posts inline (main-thread / ANR
  limit); see ADR-0003.
- **On-demand backfill:** a screen where the user picks a date range → **one
  foreground worker** queries the SMS inbox (`content://sms/inbox`, READ_SMS;
  rows are already fully reassembled) for that window → parses + routes each →
  **bulk-POSTs grouped by budget** (ADR-0004). Overlap with real-time is safe
  because of `import_id` dedup.
  - **Progress:** ongoing **determinate** foreground notification (`posted /
    total`), ending in the exception-only backfill **summary** (Q4 /
    Notifications).
  - **Cancellation:** user can cancel mid-run; stop after the in-flight chunk.
    Already-`POSTED` rows persist (idempotent). "Resume" is not a mode — re-running
    the same range is cheap and safe because `import_id` dedup skips what's done.
  - **Rate limit:** no proactive governor. Chunking keeps a normal backfill to a
    handful of requests (far under 200/hr), so a `429` is handled **reactively**
    (honor backoff, continue). The limit is per-token and shared with real-time,
    but real-time is a few messages/day and doesn't meaningfully eat the headroom.
- **Permissions:** `RECEIVE_SMS`, `READ_SMS`, `POST_NOTIFICATIONS` (status),
  `FOREGROUND_SERVICE` if a long scan needs it. Runtime permission flow on first
  launch. Manifest kept clean for later distribution choices.

## Parse → post pipeline (single shared path for both capture modes)

```
(body, sender, timestamp)
  → BankParserFactory.parse(body, sender, timestamp)     // content-aware: tries ALL matching parsers
  → null?                → drop silently (no import_id exists; not persisted), stop
  → type TRANSFER/BALANCE_UPDATE?  → log "SKIPPED_NON_TRANSACTION", stop   // ADR-0002
  → resolve mapping by (bankName, accountLast4)          // from settings
  → no mapping?          → log "SKIPPED_UNROUTED", stop
  → currency != budget's currency? → log "SKIPPED_CURRENCY_MISMATCH", stop // no FX
  → build YNAB SaveTransaction                           // mapper (unit-tested)
  → local import_id already POSTED?  → stop (best-effort optimization only; ADR-0005)
  → POST transaction(s)          // real-time: single; backfill: bulk array grouped by budget (ADR-0004)
                                 // YNAB import_id is the dedup authority; log insert upserts (ADR-0005)
  → record result (POSTED / FAILED) in local log         // 429/5xx/offline → FAILED + WorkManager backoff
```

## ParsedTransaction → YNAB `SaveTransaction` mapping (core adapter, well-tested)

YNAB `SaveTransaction` fields and how they're derived:

- `account_id` — from the resolved mapping rule.
- `date` — `yyyy-MM-dd` from `timestamp` (device time zone).
- `amount` — **milliunits**: `amount.movePointRight(3).setScale(0, HALF_UP)` as a
  long (YNAB is always ×1000, independent of the currency's decimals). Sign from
  `TransactionType` (ADR-0002): `INCOME` → `+`; `EXPENSE` / `CREDIT` /
  `INVESTMENT` → `−`. `TRANSFER` and `BALANCE_UPDATE` never reach the mapper
  (skipped upstream as `SKIPPED_NON_TRANSACTION`).
- `payee_name` — `merchant` (YNAB auto-matches to existing payees). Truncated to
  YNAB's 50-char limit; null/blank merchant → omit (YNAB allows no payee).
- `memo` — `reference` (optionally plus bank name), truncated to 200 chars.
- `import_id` — `"PW:" + generateTransactionId()`, stable per message.
  `generateTransactionId()` is a 32-char MD5 hex, so the id is always 35 chars
  (≤ 36) — the spec's original `.take(33)` was dead code on a wrong length
  assumption and is dropped (ADR-0001). **This is the entire dedup story:** the id
  hashes `sender | amount | md5(smsBody)` (no timestamp, so both capture paths
  agree), and YNAB rejects a duplicate `import_id` within a budget — including
  per element of a bulk POST — so re-scans and receiver/scan overlap can't
  double-post. Relies on every in-scope SMS carrying a reference id so distinct
  transactions differ in body.
- `approved` — `true` (fully automatic).
- `cleared` — `"cleared"`.

**Currency:** YNAB performs no FX. Each mapping rule targets an account in a
budget of a fixed currency; the milliunit amount is sent as-is. The mapping UI
shows the target budget's currency so the user routes (e.g.) USD-card SMS to a
USD budget, and each rule stores that currency. Currency mismatches are not
auto-converted (out of scope) **and are not posted**: at post time, if
`parsed.currency` ≠ the routed budget's currency the message is skipped as
`SKIPPED_CURRENCY_MISMATCH` — never sent with a wrong-currency amount. This
catches a domestic card used abroad, not just misconfiguration.

## Onboarding (first-run flow)

Sequence: **grant SMS permissions → enter + validate token (→ snapshot) → create
first rule(s) → optional initial backfill.** Rule creation depends on the token
(the picker reads the snapshot), so the token step precedes routing. SMS that
arrive mid-setup are not lost: with no rule yet they log `SKIPPED_UNROUTED`,
pre-populate the unrouted suggestions, and are recoverable via the retroactive
scoped-backfill import once a route exists.

## Settings (all on-device; sensitive values encrypted)

- **YNAB token:** PAT in `EncryptedSharedPreferences`. On save, call `GET /budgets`
  to validate the token, then eagerly fetch `GET /budgets/{id}/accounts` for each
  budget and persist the whole **budget → accounts → currency** tree as a local
  **snapshot** (a YNAB user typically has 1–3 budgets, so this is a handful of
  calls, once). Rule creation picks from the cached snapshot — zero network, works
  offline, stays off the 200/hr budget. A manual **refresh** re-pulls the tree.
  Closed/deleted accounts are filtered out of the picker.
- **Snapshot validation on token save:** every token save refreshes the snapshot
  and re-checks existing rules against it. A rule whose `budgetId` or `accountId`
  is absent from the new snapshot (e.g. a different token pointing at a different
  YNAB account) is flagged **broken** and surfaced prominently; its incoming
  messages log as `SKIPPED_UNROUTED` (not `FAILED`) until remapped — no new status.
  A rule whose IDs still resolve but whose budget currency changed has its stored
  currency updated automatically from the refreshed snapshot.
- **Account mapping rules:** list of `(bankName, last4?) → (budgetId,
  accountId)` plus the target budget's ISO currency (cached from the snapshot for
  the offline mismatch guard). `last4` blank = wildcard (whole-bank route); a
  specific `last4` takes precedence over the wildcard for the same bank. Add /
  edit / delete. Newly seen unmapped `(bank, last4)` combos surface from the log as
  "unrouted" suggestions to map in one tap.
- **Retroactive import of a newly-mapped route:** tapping an unrouted suggestion
  creates the rule, then offers *"import past transactions for this route."* That
  runs a **scoped backfill** (ADR-0004) over the range from the earliest matching
  `SKIPPED_UNROUTED` row's `timestamp` to now — re-reading those SMS from the
  inbox and re-parsing them, so the now-routed ones post. Because `import_id` is
  content-deterministic, each stale `SKIPPED_UNROUTED` row **upserts to `POSTED`**
  (same PK) and self-heals; already-`POSTED` messages are dedup-skipped. The log
  therefore stores only display fields + `importId` + `status` — never the full
  parsed transaction — because retroactive posting re-derives from the inbox
  rather than replaying stored data. (If the user deleted the original SMS, that
  message simply stays skipped.)

## Local persistence (Room)

One small DB. Primary table is a **processed-message log**:

| Column | Purpose |
|---|---|
| `importId` (PK) | local dedup before calling YNAB |
| `sender`, `bankName`, `last4` | routing + display |
| `amount`, `currency` | display |
| `status` | `POSTED` / `SKIPPED_UNROUTED` / `SKIPPED_NON_TRANSACTION` / `SKIPPED_CURRENCY_MISMATCH` / `FAILED` (un-parseable SMS are dropped, never logged — they have no `import_id`) |
| `error` | failure detail |
| `timestamp` | ordering |

Powers a **history screen**: all persisted rows, reverse-chronological, filterable
by status; a detail view shows `amount` / `payee` / `date` / `status` / `error`.
`FAILED` rows carry the manual **retry** action; `SKIPPED_UNROUTED` rows carry the
**"map this route"** action (→ rule creation + retroactive import). Mapping rules
stored here (Room), so the "unrouted suggestions" query (distinct `(bank, last4)`
with no covering rule) is plain SQL.

**Retention:** none in v1 (YAGNI). Because un-parseable SMS are never logged
(Q8), the log grows only with actual bank transactions — a few thousand rows/year
for a heavy user, which Room handles trivially. No auto-pruning, and no manual
"clear history" either, to keep the v1 surface small. (If a clear is ever added it
is safe against double-posting: dropping a `POSTED` row loses only the local
optimization; YNAB's `import_id` still rejects a re-post — ADR-0005.)

## Error handling

**Retry is classified by error, not blanket.** A transient failure must not be
dropped; a non-transient one must not be retried forever.

- **Retryable** (offline / timeout / `429` rate limit / `5xx`) → mark `FAILED`,
  enqueue a **WorkManager** retry with exponential backoff under a network
  constraint. These self-resolve. A generous ceiling (~24h of backoff) then marks
  the row **terminal** so a permanently-broken network can't leave a zombie job
  rescheduling forever. `import_id` makes every retry idempotent.
- **Terminal immediately** (no auto-retry — needs human action):
  - `400` (malformed body) — our bug; retrying can't help.
  - `404` (unknown budget/account) — the rule's target vanished; flip the rule to
    **broken** (see Settings) and the row to terminal. This is the broken-rule
    signal, not an endless-retry case.
  - `401` **or no token configured** → set a persistent **`postingPaused`** flag
    and surface it prominently (banner + notification). "No valid token" is one
    state whether the token is invalid (`401`) or was never set / was deleted.
    While paused the worker short-circuits **before the network** — it still
    parses + routes, then records `FAILED` (`error = "token invalid — awaiting new
    token"` / `"no token — awaiting token"`) without hitting YNAB, so a bad or
    absent token can't trigger a 401 storm. Saving a token **validates** it via
    `GET /budgets`; on success it clears `postingPaused` and **bulk-retries every
    `FAILED` message** (idempotent via `import_id`).
- **Manual retry** stays available on **any** `FAILED` row regardless of class, so
  a fixed rule or restored network lets the user re-drive it. A failed real-time
  post is never lost: it lives in the log with a manual retry button plus (for the
  retryable class) the background retry job.
- Parser returns null / no route / non-postable type / currency mismatch → not
  errors; logged as `SKIPPED_*`, visible in history.

### Notifications (exception-only)

The app posts silently; notifications interrupt only when a human is needed.

- `POSTED` → **no notification** (history is the happy-path record).
- **Terminal `FAILED`** → notify. *Not* fired while a retryable failure is still
  inside its auto-retry window (don't cry wolf for something likely to self-heal).
- `postingPaused` / 401 → notify prominently (banner + notification).
- **Backfill completion** → one **summary** notification
  (`posted N · skipped M · failed K`); backfill is user-initiated and awaited.
- History-row wording ("retrying…" vs "failed — tap to retry") is **derived** from
  error class + whether a retry job is live — **not** a new status; the fixed
  5-status set stands.
- `POST_NOTIFICATIONS`: on API 33+ request it in the first-run flow; on API < 33
  notifications post without a runtime grant. If denied, everything still works —
  the user relies on the in-app banner/history instead.

## Testing — Definition of Done

- **Unit — mapper:** `ParsedTransaction → SaveTransaction` — milliunit math,
  sign by transaction type, date formatting, `import_id` length (≤36) and
  stability, payee/memo truncation.
- **Unit — mapping resolver:** `(bank, last4)` resolution, including specific-
  last4-over-wildcard precedence and no-match behavior.
- **parser-core:** already has its own JUnit 5 suite; the submodule keeps it
  runnable but this app does not modify it.
- **On-device smoke check:** real-time receiver fires, permission flow works, a
  test SMS reaches YNAB (or is correctly skipped).

## Open items — resolved during grilling

- **YNAB endpoints/models (Retrofit):** `GET /budgets` (token validation +
  budget picker, with `currency_format.iso_code`), `GET /budgets/{id}/accounts`
  (account picker), `POST /budgets/{id}/transactions` — the request body carries
  a **`transactions` array** (bulk), and the response's `duplicate_import_ids`
  drives dedup mapping. Real-time sends a one-element array; backfill sends chunks
  (~100–200) grouped by budget.
- **Mapping rules storage: Room** — a table alongside the processed-message log,
  so the "unrouted suggestions" query (distinct `(bank, last4)` from the log with
  no covering rule) is plain SQL.
- **SDK / toolchain:** match PennyWise — `minSdk 26`, `targetSdk 36`,
  `compileSdk 37`, Java 11 bytecode (JDK 21 Gradle toolchain).
- **Token storage:** `EncryptedSharedPreferences` for v1 (deprecated but adequate
  for a single secret; revisit only on a device-compat issue).
