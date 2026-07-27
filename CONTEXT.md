# PennyWise → YNAB

A standalone Android app that reads bank SMS on-device, parses each into a
transaction with PennyWise's `parser-core`, and posts it to the YNAB API fully
automatically. This glossary fixes the vocabulary the app's own code uses;
`parser-core` and YNAB terms are referenced but owned elsewhere.

## Language

**import_id**:
The per-message deduplication key sent to YNAB. Derived content-only as
`"PW:" + parser-core's generateTransactionId()` — a hash of
`sender | amount | md5(smsBody)` that deliberately excludes the timestamp so the
real-time and backfill capture paths produce the same key for one message.
_Avoid_: dedup hash, transaction id.

**Processed message**:
One row in the local log representing a single SMS the pipeline has handled,
keyed by `import_id`, carrying its terminal status. Not "the transaction" — a
message may be skipped or fail and still be a processed message.
_Avoid_: transaction record, log entry.

**Status**:
The terminal outcome recorded for a processed message. Fixed set of **5**:
`POSTED`, `SKIPPED_UNROUTED`, `SKIPPED_NON_TRANSACTION`,
`SKIPPED_CURRENCY_MISMATCH`, `FAILED`. Only parsed messages are persisted — a
message keyed by `import_id`, which exists only after a successful parse. An
**un-parseable** SMS (`factory.parse` → `null`: OTPs, promos, personal texts, or a
bank SMS whose format regressed) has no `import_id` and is **dropped silently, not
logged** — this keeps the app from accumulating a metadata record of the whole
inbox. `SKIPPED_NON_TRANSACTION` covers a message that parsed fine but whose type
is not posted in v1 — `TRANSFER` (own-account move) or `BALANCE_UPDATE`
(zero-amount balance notice). `SKIPPED_CURRENCY_MISMATCH` covers a message whose
`currency` differs from the routed budget's currency (no FX; never post a
wrong-currency amount).

**Mapping rule** (a.k.a. **Route**):
A user-defined entry `(bankName, last4?) → (budgetId, accountId)` that decides
which YNAB budget+account a parsed message posts to. `last4` present = exact
rule; `last4` absent = **wildcard** (bank-wide). Resolution: exact rule with a
non-null matching `last4` wins; else the bank wildcard; else the message is
`SKIPPED_UNROUTED`. `last4` matches by exact string equality (may be 3 or 4
digits); a null `last4` can only match a wildcard. A rule also stores the target
budget's ISO currency, so the currency-mismatch guard needs no live lookup.
_Avoid_: account link, binding.

**Backfill**:
An on-demand pass over a user-picked date range of the SMS inbox, run by one
foreground worker that parses + routes every message and **bulk**-posts the
results grouped by budget. Distinct from the real-time path, which posts one
message at a time. Overlap between the two is safe via `import_id`.
_Avoid_: import, sync, catch-up.

**Unrouted suggestion**:
A distinct `(bankName, last4)` combo pulled from `SKIPPED_UNROUTED` log rows and
offered in settings as a one-tap mapping to create.

**Postable transaction**:
A `ParsedTransaction` whose type the app posts to YNAB: `INCOME` (inflow),
`EXPENSE` / `CREDIT` / `INVESTMENT` (outflow). `TRANSFER` and `BALANCE_UPDATE`
are not postable in v1.
_Avoid_: valid transaction (every parse is valid; only some are postable).
