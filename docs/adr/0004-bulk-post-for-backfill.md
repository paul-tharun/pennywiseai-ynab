# Backfill posts in bulk, grouped by budget

YNAB rate-limits to **200 requests/hour per access token** (rolling; returns
`429`). A date-range backfill can match hundreds of messages, so one POST per
message would exhaust the quota and fail partway — independent of latency.

`POST /budgets/{budget_id}/transactions` accepts a `transactions` **array**, and
the response reports created `transaction_ids` plus `duplicate_import_ids`. So
backfill:

1. parses + routes the whole range locally (no network),
2. groups postable transactions by `budget_id` (the endpoint is per-budget),
3. sends one bulk POST per chunk (~100–200 transactions),
4. maps the response — created → `POSTED`, `duplicate_import_ids` → `POSTED`
   (already present).

A 600-message backfill becomes a handful of requests, well under 200/hour, and
`import_id` dedup is preserved per array element. Real-time keeps a single-
transaction POST per message (a few per day — no batching needed). Both paths
treat `429` as retryable with WorkManager backoff.

**All-or-nothing chunks.** A bulk POST is atomic: if *any* element is invalid the
whole request `400`s and nothing is created (duplicates are not errors — they
return on a normal `201` in `duplicate_import_ids`). So a genuine bad element
would otherwise sink its whole chunk. On a chunk `400` we **fall back to
individual POSTs for just that chunk** (still idempotent via `import_id`): good
rows land `POSTED`, only the actually-bad row(s) get `FAILED` with the real
per-transaction error, and the fallback respects the rate limiter. Chunk-400s are
expected-rare — every transaction is mapper-built and routed to a
snapshot-validated account — so the linear fallback cost only materializes on a
genuinely odd message. (Bisect-retry was considered and rejected as over-built
for a case this rare.)
