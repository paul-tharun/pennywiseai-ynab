# Add the SMS send time to `import_id`

Supersedes the "every SMS carries a reference id" assumption in ADR-0001.

Scapia (Federal Bank) card SMS carry no date, reference, or card number, so two
real payments of the same amount to the same merchant produce byte-identical SMS
and therefore the same content-only `import_id`. YNAB drops the second as a
duplicate and the app logs it `POSTED`, so the payment silently never appears.

From the cutoff (`2026-11-17T00:00+05:30`), `import_id` is
`"PW:" + first18(generateTransactionId()) + ":" + yyyyMMddHHmmss` of the SMS send
time in UTC — exactly 36 chars, YNAB's limit. Messages before the cutoff keep the
legacy `"PW:" + generateTransactionId()` so a backfill over already-posted history
still dedups against YNAB.

Both capture paths must see the same send time: the live receiver uses the PDU's
service-center timestamp, and backfill reads the inbox `DATE_SENT` column, which
the platform fills from that same timestamp. `DATE` (device receive time) is only a
fallback when `DATE_SENT` is unset; a message hitting that fallback can be posted
twice if it is captured by both paths. UTC keeps the id stable across timezone
changes. Seconds precision is truncated because the service-center timestamp has
none.
