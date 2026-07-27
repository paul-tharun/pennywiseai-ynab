# Which `TransactionType`s post, and their YNAB sign

`parser-core` emits six types; the app posts only four and fixes their sign from
the main PennyWise app's own consistent interpretation:

| type | YNAB amount |
|---|---|
| `INCOME` | inflow, `+amount` |
| `EXPENSE` | outflow, `−amount` |
| `CREDIT` (credit-card **spend**, not "money credited") | outflow, `−amount` |
| `INVESTMENT` | outflow, `−amount` |

`TRANSFER` and `BALANCE_UPDATE` are **not posted in v1**; each message is logged
`SKIPPED_NON_TRANSACTION`.

- `BALANCE_UPDATE` carries `amount = ZERO` and represents no money movement —
  posting it would create junk.
- `TRANSFER` is an own-account move. YNAB models this as a linked two-account
  transfer, but that is an explicit v1 non-goal. Posting one leg as a plain
  outflow would invent a phantom expense that never reduced net worth and
  pollute spending analytics — worse than skipping. Revisit if real transfer
  support is wanted.

Note the naming trap: the SMS keyword *"credited"* maps to `INCOME`, not
`CREDIT`. The `CREDIT` enum value means "credit card", never "money in".
