# YNAB `import_id` is the dedup authority; local checks are best-effort

Two workers can process the same message concurrently — a duplicate SMS
broadcast, or a backfill overlapping the real-time receiver. `import_id` is not
known at enqueue time (it needs the parsed `amount`), so the WorkManager job
itself cannot be deduped up front; the collision has to be resolved downstream.

We resolve it by making **YNAB the sole correctness authority**:

- YNAB rejects a duplicate `import_id` within a budget — including per element of
  a bulk POST — and reports it in the response's `duplicate_import_ids`. A racing
  second POST therefore never creates a second transaction; we record it
  `POSTED` (already present). This holds even across reinstall/device change,
  where the local log is gone.
- The pipeline's local "already `POSTED`? → stop" check is kept purely as an
  **optimization** to skip a redundant call in the common *sequential* case
  (a backfill re-touching a message real-time already posted). It is a
  read-then-act with an inherent race and is explicitly **not** a correctness
  guarantee.
- The processed-message log insert is an **upsert** (Room `OnConflictStrategy`),
  so two writers on the same `importId` (PK) converge on one row instead of
  crashing.

Rejected alternatives:

- **A `POSTING` / in-flight status as a local lock.** Would add a transient
  state to the otherwise fixed status set and a crash-recovery problem (rows
  stuck `POSTING` after a worker dies mid-post). Unnecessary once YNAB
  guarantees single-post.
- **Serializing all posting through one unique WorkManager chain.** Eliminates
  the one redundant request but throttles throughput and still can't dedupe at
  enqueue. Not worth it for a race this rare (real-time is a few messages a day)
  with YNAB as backstop.

The accepted cost is at most one wasted API request on a genuine concurrent
race — negligible against the 200 req/hr budget. Builds on ADR-0001 (the
content-only `import_id` that makes both capture paths agree).
