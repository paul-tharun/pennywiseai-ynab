# Content-hash `import_id`, excluding the message timestamp

The app dedups against YNAB entirely through `import_id`, set to
`"PW:" + generateTransactionId()` from `parser-core`. That id hashes
`sender | amount | md5(smsBody)` and **deliberately omits the SMS timestamp**,
because the real-time `BroadcastReceiver` and the inbox backfill observe
different timestamps for the same message; a content-only key lets both paths
produce the identical `import_id`, so overlapping captures can't double-post.

The accepted cost: two genuinely distinct transactions with a byte-identical
SMS body (same sender, same amount, same text) would collide and YNAB would drop
the second. We accept this because every bank SMS in scope carries a reference
id in its body, so distinct transactions always differ in `md5(smsBody)`. The
scheme is effectively permanent — once transactions exist in a budget under
these ids, changing the derivation would break dedup for all history.
