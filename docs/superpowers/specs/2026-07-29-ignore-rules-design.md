# Ignore rules ("route to null")

Date: 2026-07-29

## Problem

Messages parsed from a **known** bank that has no matching route become
`SKIPPED_UNROUTED` and reappear forever in the Home "NEEDS ROUTING" suggestions
list. When a bank/card is genuinely junk (a bank the user doesn't track in YNAB,
promotional-but-transactional-looking messages, a card that should never be
imported), there is no way to silence it. The user must be able to **route such
a `(bank, last4)` to null** — i.e. drop it and stop being nagged.

## Goal

Add an **Ignore** rule: a routing-table entry keyed exactly like a normal route
but with **no destination**. A message matching an ignore rule is dropped
silently (no log row) and its `(bank, last4)` never appears as a suggestion.

Non-goals: sender-level blocking, whole-bank-only granularity, a new
`MessageStatus`, any audit trail of ignored messages.

## Decisions (from brainstorming)

- **Granularity:** same as routes — `(bankName, last4)`, wildcard `last4 = ""`.
  Existing precedence (exact `last4` beats bank wildcard) applies unchanged.
- **Trace:** ignored messages are **dropped silently** — reuse the existing
  `Classification.Dropped` outcome. No new `MessageStatus`, no migration of the
  status enum, no Home log row.
- **Entry points:** (1) one-tap **Ignore** button on each suggestion, (2)
  **"Ignore / don't import"** option in the Route Editor, (3) ignore rules are
  visible and deletable in the Settings routes list.
- **One-tap UX:** writes the ignore rule immediately; undo = delete it in the
  routes list. No confirm dialog.
- **Wording:** "Ignore" in the editor; renders as `→ Ignored` in the routes list.

## Design

### 1. Data model

An ignore rule is a `MappingRule` with no destination. Represent the
route-vs-ignore distinction with an explicit discriminator (`ignored: Boolean`,
or a small sealed destination) rather than overloading nulls.

- `core/model/MappingRule.kt`: an ignore rule carries no `budgetId`/`accountId`.
- `data/local/entity/MappingRuleEntity.kt`: blank (`""`) destination columns +
  `ignored` flag. The DB uses `fallbackToDestructiveMigration(dropAllTables = true)`
  (pre-release, `exportSchema = false`), so the schema change is a **`@Database`
  version bump only** — no hand-written `Migration`.
- `data/mapper/MappingRuleMapping.kt`: `toDomain`/`toEntity` handle both shapes.
- Keying unchanged: `(bankName, last4)`, wildcard stored as `""`. The existing
  UNIQUE `(bankName, last4)` index means a given key is **either** routed **or**
  ignored, never both.

**Emergent property (no extra code):** an ignore **wildcard** on a bank plus an
exact-`last4` **route** on one card = "ignore everything from this bank except
this one card," because `MappingResolver` already prefers the more specific
match. The inverse (route the whole bank, ignore one card) also works.

### 2. Resolution & pipeline

- `core/MappingResolver.kt`: **unchanged** — already returns the most-specific
  matching rule; now that rule may be an ignore rule.
- `pipeline/TransactionPipeline.kt` `classify()` step 3: add one branch — if the
  resolved rule is an ignore rule, return `Classification.Dropped`. This branch
  comes **before** the currency guard and dedup so an ignored message is never
  logged for any reason. `rule == null || rule.broken` still → `SKIPPED_UNROUTED`.
- Both real-time (`SmsReceiver` → pipeline) and bulk (`BackfillProcessor`) paths
  call `classify()`, so ignore applies to both automatically.

### 3. Suggestions & routes list

- `data/local/dao/ProcessedMessageDao.observeUnroutedSuggestions()` already
  excludes any `(bank, last4)` covered by a row in `mapping_rules`. An ignore
  rule *is* such a row, so ignored combos drop off "NEEDS ROUTING" immediately —
  **including** combos already logged `SKIPPED_UNROUTED` before being ignored.
  Verify the query's wildcard matching treats an ignore wildcard the same as a
  route wildcard.
- `ui/rules/RulesScreen.kt` (`RulesList`): ignore rules render as
  `SBI ·7756 → Ignored` (no currency/account) with the standard Delete button.

### 4. UI entry points

- **Suggestion one-tap** (`RulesList`): each "NEEDS ROUTING" row gets an
  **Ignore** button beside "Map →". Tap → write ignore rule for that exact
  `(bank, last4)` → row disappears. No confirm; undo via routes-list delete.
- **Route Editor** (`ui/rules/RuleEditorScreen.kt`): "SEND TO" section gains an
  **"Ignore / don't import"** choice; selecting it hides Budget/Account
  dropdowns and saves an ignore rule.
- `ui/rules/RulesViewModel.kt`: `RuleDraft` + `saveRule` no longer require
  `budgetId`/`accountId` when ignore is selected; routed-case validation intact;
  duplicate `(bank, last4)` still returns `DuplicateRoute`. Add a
  `saveIgnore(bank, last4)` (or equivalent) for the one-tap path.

### 5. Testing

- `MappingResolver`/`classify`: exact-ignore beats bank-wildcard route and
  vice-versa; ignore wildcard + exact route = "ignore bank except one card";
  ignored message → `Dropped` and never logged.
- Suggestions: ignored combo excluded, including a pre-existing
  `SKIPPED_UNROUTED` combo.
- `saveRule`: ignore draft skips budget/account validation; routed draft still
  requires them; duplicate `(bank, last4)` still conflicts.
- ViewModel: one-tap ignore writes the rule and removes the suggestion.
- (No migration test — destructive migration; version bump only.)

## Affected files

- `core/model/MappingRule.kt`
- `core/MappingResolver.kt` (tests only; logic unchanged)
- `data/local/entity/MappingRuleEntity.kt` (+ migration in `PennyWiseDatabase.kt`)
- `data/mapper/MappingRuleMapping.kt`
- `data/local/dao/MappingRuleDao.kt` (if an ignore-insert helper is useful)
- `data/local/dao/ProcessedMessageDao.kt` (verify suggestions query only)
- `pipeline/TransactionPipeline.kt`
- `pipeline/Classification.kt` (reuse `Dropped`; no change expected)
- `ui/rules/RulesScreen.kt`, `RuleEditorScreen.kt`, `RulesViewModel.kt`,
  `RoutePreview.kt`
