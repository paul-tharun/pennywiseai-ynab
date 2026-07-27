# Capture enqueues; one WorkManager worker owns parse→post

Neither capture mode posts inline. The real-time `BroadcastReceiver` and the
on-demand backfill scan both do the minimum — extract `(body, sender,
timestamp)` and enqueue a single WorkManager job — and one worker runs the whole
pipeline: `factory.parse` → route → map → POST → record status.

Why, despite "fully automatic / immediate": a `BroadcastReceiver.onReceive`
runs on the main thread under a ~10s ANR ceiling and cannot safely make a
token-validated HTTPS POST that may hang on a flaky network. "Immediate"
therefore means an **expedited** WorkManager job, not a synchronous send.

The shared unit is the **parse→route mapping** function, invoked two ways: the
receiver enqueues one expedited job per message; backfill runs one foreground
worker that loops the date range. Only *posting* differs between them (single vs
bulk — see ADR-0004).

Consequences: the first attempt and every retry are the *same* code path
(backoff/idempotency come for free via `import_id`). The cost is that posting is
near-real-time, not literally instant, and is subject to WorkManager/Doze
scheduling.
