# Ignore Rules ("route to null") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user mark a `(bank, last4)` as "Ignore / don't import" so matching bank messages are dropped silently and stop appearing as "NEEDS ROUTING" suggestions.

**Architecture:** An ignore rule is an ordinary `MappingRule` in the existing `mapping_rules` table, distinguished by a new `ignored: Boolean` flag and carrying no destination. Resolution is unchanged (`MappingResolver` already returns the most-specific match); `TransactionPipeline.classify()` gains one branch that maps a resolved ignore rule to the existing `Classification.Dropped` outcome (no log row, no `MessageStatus` change). Because an ignore rule is a row in `mapping_rules`, the existing suggestions query excludes ignored combos with no change.

**Tech Stack:** Kotlin, Android, Room, Hilt, Jetpack Compose (Material3), JUnit4 + Robolectric, kotlinx-coroutines-test.

## Global Constraints

- **No new `MessageStatus` value.** The enum in `data/local/MessageStatus.kt` is a fixed 5-value set marked "do not extend". Ignored messages reuse `Classification.Dropped` (never logged).
- **No hand-written Room migration.** The DB uses `fallbackToDestructiveMigration(dropAllTables = true)` (`DatabaseModule.kt:24`), pre-release, `exportSchema = false`. A schema change requires **only** bumping `@Database(version = …)`; do not add a `Migration` class or migration test.
- **Wildcard storage sentinel:** the bank wildcard is stored as `last4 = ""` (`WILDCARD_LAST4`), never NULL. Domain `last4 == null` ⇔ entity `last4 == ""` via `toDomain()`/`toEntity()`.
- **Ignore-rule shape:** an ignore rule has `ignored = true`, `broken = false`, and empty `budgetId`/`accountId`/`currencyCode` (`""`). Its destination fields are never read.
- **UNIQUE `(bankName, last4)`** on `mapping_rules` stays — a given key is either routed or ignored, never both.
- **Test command:** `./gradlew :app:testDebugUnitTest` (filter with `--tests "fully.qualified.ClassName"`). Module is `:app`.
- **Wording:** editor option label "Ignore / don't import"; routes-list rendering "→ Ignored".

---

### Task 1: `ignored` flag through the data layer

Add the discriminator to the domain model, Room entity, and mapper, and bump the DB version so Room recreates the table with the new column.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/core/model/MappingRule.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/MappingRuleEntity.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMapping.kt`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt:26`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMappingTest.kt`

**Interfaces:**
- Produces: `MappingRule(bankName, last4, budgetId, accountId, currencyCode, broken = false, ignored = false)` and `MappingRuleEntity(id, bankName, last4, budgetId, accountId, currencyCode, broken = false, ignored = false)`; `MappingRuleEntity.toDomain()` / `MappingRule.toEntity(id)` preserve `ignored`.

- [ ] **Step 1: Write the failing test**

Append to `MappingRuleMappingTest.kt` (add imports if the file lacks them — `MappingRule`, `MappingRuleEntity`, `assertTrue`/`assertFalse`):

```kotlin
@Test
fun `toDomain preserves the ignored flag`() {
    val entity = MappingRuleEntity(
        id = 5, bankName = "SBI", last4 = "7756",
        budgetId = "", accountId = "", currencyCode = "", ignored = true,
    )
    assertTrue(entity.toDomain().ignored)
}

@Test
fun `toEntity preserves the ignored flag`() {
    val rule = MappingRule(
        bankName = "SBI", last4 = "7756",
        budgetId = "", accountId = "", currencyCode = "", ignored = true,
    )
    assertTrue(rule.toEntity().ignored)
}

@Test
fun `a routed rule round-trips with ignored false`() {
    val rule = MappingRule("HDFC Bank", "1234", "b1", "a1", "INR")
    assertFalse(rule.toEntity().toDomain().ignored)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.mapper.MappingRuleMappingTest"`
Expected: FAIL to compile — `ignored` is not a parameter of `MappingRuleEntity`/`MappingRule`.

- [ ] **Step 3: Add the field and mapping**

In `MappingRule.kt`, add after `broken`:

```kotlin
    /** True for an "ignore / route to null" rule: no destination; its messages are
     *  dropped (never logged) instead of posted. broken is always false for these. */
    val ignored: Boolean = false,
```

In `MappingRuleEntity.kt`, add after `broken`:

```kotlin
    /** True for an ignore rule (route to null). Destination columns are empty and unused. */
    val ignored: Boolean = false,
```

In `MappingRuleMapping.kt`, add `ignored = ignored,` to both `toDomain()` and `toEntity()`:

```kotlin
fun MappingRuleEntity.toDomain(): MappingRule = MappingRule(
    bankName = bankName,
    last4 = last4.ifEmpty { null },
    budgetId = budgetId,
    accountId = accountId,
    currencyCode = currencyCode,
    broken = broken,
    ignored = ignored,
)

fun MappingRule.toEntity(id: Long = 0): MappingRuleEntity = MappingRuleEntity(
    id = id,
    bankName = bankName,
    last4 = last4 ?: WILDCARD_LAST4,
    budgetId = budgetId,
    accountId = accountId,
    currencyCode = currencyCode,
    broken = broken,
    ignored = ignored,
)
```

In `PennyWiseDatabase.kt`, bump the version:

```kotlin
    version = 3,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.mapper.MappingRuleMappingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/core/model/MappingRule.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/MappingRuleEntity.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMapping.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMappingTest.kt
git commit -m "feat: add ignored flag to MappingRule (data layer)"
```

---

### Task 2: Drop messages that resolve to an ignore rule

`MappingResolver` needs no change — it already returns the most-specific matching rule, which may now be an ignore rule. `classify()` adds one branch: a resolved ignore rule → `Classification.Dropped`, evaluated **before** the broken/unrouted, currency, and dedup checks so an ignored message is never logged for any reason.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/pipeline/TransactionPipeline.kt:69-74`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/pipeline/ClassificationSeamTest.kt`

**Interfaces:**
- Consumes: `MappingRule.ignored` (Task 1); `resolver.resolve(rules, bankName, last4)`; `Classification.Dropped`.
- Produces: `classify(...)` returns `Classification.Dropped` when the resolved rule has `ignored == true`.

- [ ] **Step 1: Write the failing tests**

Append to `ClassificationSeamTest.kt`. The class seeds `ruleDao` with a routed HDFC·1234 rule; these tests seed their own ignore rules via a fresh `FakeMappingRuleDao` passed to a locally-built pipeline, mirroring the existing "no token" test at lines 102-113.

```kotlin
@Test
fun `an exact ignore rule classifies Dropped`() = runTest {
    val ignoreDao = FakeMappingRuleDao(
        mutableListOf(
            MappingRuleEntity(id = 1, bankName = "SBI", last4 = "7756",
                budgetId = "", accountId = "", currencyCode = "", ignored = true),
        ),
    )
    val p = TransactionPipeline(
        smsParser = SmsParser { _, _, _ -> parsed(bank = "SBI", last4 = "7756") },
        mapper = mapper, resolver = resolver, poster = FakeTransactionPoster(),
        mappingRuleDao = ignoreDao, processedMessageDao = logDao,
        tokenStore = tokenStore, postingState = postingState,
    )
    assertEquals(Classification.Dropped, p.classify("b", "s", 1L))
}

@Test
fun `ignore wildcard drops other cards but an exact route still posts`() = runTest {
    // "Ignore all SBI except card 7756": wildcard ignore + exact route.
    val dao = FakeMappingRuleDao(
        mutableListOf(
            MappingRuleEntity(id = 1, bankName = "SBI", last4 = "",
                budgetId = "", accountId = "", currencyCode = "", ignored = true),
            MappingRuleEntity(id = 2, bankName = "SBI", last4 = "7756",
                budgetId = "b1", accountId = "a1", currencyCode = "INR"),
        ),
    )
    fun pipe(p: ParsedTransaction) = TransactionPipeline(
        smsParser = SmsParser { _, _, _ -> p },
        mapper = mapper, resolver = resolver, poster = FakeTransactionPoster(),
        mappingRuleDao = dao, processedMessageDao = logDao,
        tokenStore = tokenStore, postingState = postingState,
    )
    // The routed card posts...
    assertTrue(pipe(parsed(bank = "SBI", last4 = "7756")).classify("b", "s", 1L)
        is Classification.Postable)
    // ...every other SBI card is dropped by the wildcard ignore.
    assertEquals(Classification.Dropped,
        pipe(parsed(bank = "SBI", last4 = "9999")).classify("b", "s", 2L))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.pipeline.ClassificationSeamTest"`
Expected: FAIL — the ignored message currently classifies `Skipped(SKIPPED_UNROUTED)` (its empty currency also can't match), not `Dropped`.

- [ ] **Step 3: Add the ignore branch to classify()**

In `TransactionPipeline.kt`, replace the step-3 block (lines 69-74) with:

```kotlin
        // 3. Resolve the route (exact last4 beats bank wildcard). An ignore rule ("route
        //    to null") drops the message silently — never logged — before any other check.
        //    Missing OR broken -> fail fast as SKIPPED_UNROUTED; a broken route never hits
        //    the network.
        val rule = resolver.resolve(rules ?: currentRules(), parsed.bankName, parsed.accountLast4)
        if (rule != null && rule.ignored) {
            return Classification.Dropped
        }
        if (rule == null || rule.broken) {
            return Classification.Skipped(MessageStatus.SKIPPED_UNROUTED, parsed, importId)
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.pipeline.ClassificationSeamTest"`
Expected: PASS (all existing tests in the class still pass).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/pipeline/TransactionPipeline.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/pipeline/ClassificationSeamTest.kt
git commit -m "feat: drop messages resolving to an ignore rule"
```

---

### Task 3: ViewModel — save ignore rules and one-tap ignore

`RuleDraft` gains an `ignored` flag; `saveRule` skips the budget/account requirement for ignore drafts and stores an ignore rule; a new `ignoreSuggestion(bank, last4)` writes an ignore rule directly for the one-tap path.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RulesViewModel.kt:26-34,76-98`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/ui/rules/RulesViewModelTest.kt`

**Interfaces:**
- Consumes: `MappingRule.ignored`, `RuleDraft`, `SaveRuleResult`, `mappingRuleDao`, `processedMessageDao.observeUnroutedSuggestions`.
- Produces:
  - `RuleDraft(bankName, last4, budgetId, accountId, currencyCode, editRuleId, ignored = false)`
  - `saveRule(draft): SaveRuleResult` — for `ignored` drafts, requires only a non-blank bank and stores `ignored = true` with empty destination.
  - `ignoreSuggestion(bankName: String, last4: String?): Job` — inserts an ignore rule for that combo.

- [ ] **Step 1: Write the failing tests**

Append to `RulesViewModelTest.kt`:

```kotlin
@Test
fun `saveRule stores an ignore rule without a destination`() = runTest(dispatcher) {
    val vm = vm()
    val result = vm.saveRule(
        RuleDraft(bankName = "SBI", last4 = "7756", budgetId = "", accountId = "",
            currencyCode = "", editRuleId = null, ignored = true),
    )
    assertEquals(SaveRuleResult.Saved, result)
    val rule = vm.rules.first { it.isNotEmpty() }.single()
    assertTrue(rule.ignored)
    assertEquals("7756", rule.last4)
}

@Test
fun `saveRule still requires a destination for a routed draft`() = runTest(dispatcher) {
    val vm = vm()
    val result = vm.saveRule(
        RuleDraft(bankName = "SBI", last4 = "7756", budgetId = "", accountId = "",
            currencyCode = "", editRuleId = null, ignored = false),
    )
    assertTrue(result is SaveRuleResult.Invalid)
}

@Test
fun `ignoreSuggestion writes an ignore rule and clears the suggestion`() = runTest(dispatcher) {
    val vm = vm()
    // A logged unrouted combo shows up as a suggestion...
    db.processedMessageDao().upsert(unrouted("s1", "SBI", ts = 500L))
    assertEquals(listOf("SBI"), vm.suggestions.first { it.isNotEmpty() }.map { it.bankName })

    vm.ignoreSuggestion("SBI", "1234").join()
    advanceUntilIdle()

    // ...and disappears once an ignore rule covers it (NOT EXISTS against mapping_rules).
    assertTrue(vm.suggestions.first { it.isEmpty() }.isEmpty())
    assertTrue(db.mappingRuleDao().getAll().single().ignored)
}
```

Note: `unrouted(...)` (defined at test line 79) seeds `last4 = "1234"`, so ignoring `("SBI", "1234")` covers it exactly.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.rules.RulesViewModelTest"`
Expected: FAIL to compile — `ignored` is not a `RuleDraft` parameter and `ignoreSuggestion` does not exist.

- [ ] **Step 3: Implement**

In `RulesViewModel.kt`, add `ignored` to `RuleDraft`:

```kotlin
/** A pending create/edit from the rule editor. `last4` null (or blank) = bank wildcard.
 *  `ignored` = an "ignore / route to null" rule (no destination). */
data class RuleDraft(
    val bankName: String,
    val last4: String?,
    val budgetId: String,
    val accountId: String,
    val currencyCode: String,
    val editRuleId: Long?,
    val ignored: Boolean = false,
)
```

Replace `saveRule` (lines 76-98) with:

```kotlin
    suspend fun saveRule(draft: RuleDraft): SaveRuleResult {
        if (draft.bankName.isBlank()) return SaveRuleResult.Invalid("Bank name required")
        if (!draft.ignored && (draft.budgetId.isBlank() || draft.accountId.isBlank())) {
            return SaveRuleResult.Invalid("Pick a budget and account")
        }
        val rule = MappingRule(
            bankName = draft.bankName.trim(),
            last4 = draft.last4?.ifBlank { null },
            budgetId = draft.budgetId,
            accountId = draft.accountId,
            currencyCode = draft.currencyCode,
            ignored = draft.ignored,
        )
        return try {
            if (draft.editRuleId != null) {
                mappingRuleDao.update(rule.toEntity(id = draft.editRuleId))
            } else {
                mappingRuleDao.insert(rule.toEntity())
            }
            SaveRuleResult.Saved
        } catch (_: SQLiteConstraintException) {
            SaveRuleResult.DuplicateRoute
        }
    }

    /**
     * One-tap ignore from a "NEEDS ROUTING" suggestion: store an ignore rule for this exact
     * (bank, last4) so its messages drop and the combo leaves the suggestions list. A
     * suggestion has no covering rule, so the insert cannot conflict; the result is ignored.
     */
    fun ignoreSuggestion(bankName: String, last4: String?) = viewModelScope.launch {
        saveRule(
            RuleDraft(
                bankName = bankName, last4 = last4, budgetId = "", accountId = "",
                currencyCode = "", editRuleId = null, ignored = true,
            ),
        )
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.rules.RulesViewModelTest"`
Expected: PASS (existing VM tests still pass).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RulesViewModel.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/rules/RulesViewModelTest.kt
git commit -m "feat: ViewModel saves ignore rules and one-tap ignore"
```

---

### Task 4: Route-preview line for ignore rules

Extend the pure `routePreview` helper so the editor can show an ignore preview (`SBI ·7756 → Ignored`) without needing a budget/account.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreview.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreviewTest.kt`

**Interfaces:**
- Produces: `routePreview(bank, last4, budgetName, accountName, currency, ignored = false): String?` — when `ignored` and `bank` is non-blank, returns `"$bank ·$card → Ignored"` (budget/account/currency ignored); otherwise unchanged.

- [ ] **Step 1: Write the failing tests**

Append to `RoutePreviewTest.kt`:

```kotlin
@Test
fun `ignored preview shows the card routed to Ignored`() {
    assertEquals("SBI ·7756 → Ignored",
        routePreview("SBI", "7756", null, null, null, ignored = true))
}

@Test
fun `ignored preview renders a blank last4 as any`() {
    assertEquals("SBI ·any → Ignored",
        routePreview("SBI", "", null, null, null, ignored = true))
}

@Test
fun `ignored preview is null when the bank is blank`() {
    assertNull(routePreview("", "7756", null, null, null, ignored = true))
}
```

Add `import org.junit.Assert.assertNull` if the file lacks it.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.rules.RoutePreviewTest"`
Expected: FAIL to compile — `routePreview` has no `ignored` parameter.

- [ ] **Step 3: Implement**

Replace the body of `routePreview` in `RoutePreview.kt`:

```kotlin
fun routePreview(
    bank: String,
    last4: String?,
    budgetName: String?,
    accountName: String?,
    currency: String?,
    ignored: Boolean = false,
): String? {
    if (bank.isBlank()) return null
    val card = last4?.ifBlank { null } ?: "any"
    if (ignored) return "$bank ·$card → Ignored"
    if (budgetName == null || accountName == null) return null
    val cur = currency?.let { " ($it)" } ?: ""
    return "$bank ·$card → $budgetName / $accountName$cur"
}
```

Also update the KDoc first line to note the ignore variant:

```kotlin
/**
 * The live-preview line for the route editor: "SBI ·7756 → Personal / Everyday (₹)", or
 * "SBI ·7756 → Ignored" when [ignored]. Returns null until bank (and, for a route, budget
 * and account) are chosen. Pure — unit-tested in RoutePreviewTest. Blank last4 => "any".
 */
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ui.rules.RoutePreviewTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreview.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/ui/rules/RoutePreviewTest.kt
git commit -m "feat: route-preview line for ignore rules"
```

---

### Task 5: Compose UI — ignore button, "Ignored" rendering, editor toggle

Wire the three entry points into Compose. These are Compose composables with no unit tests in this codebase (mirroring `RulesScreen`/`RuleEditorScreen`, which have none); the behavior underneath is already covered by Tasks 3–4. Verify by compiling and by the manual checklist.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RulesScreen.kt:43-55,67-76`
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RuleEditorScreen.kt`

**Interfaces:**
- Consumes: `viewModel.ignoreSuggestion(bank, last4)` (Task 3); `MappingRule.ignored`; `routePreview(..., ignored)` (Task 4); `RuleDraft(..., ignored)`.

- [ ] **Step 1: RulesList — render ignore rules and add the Ignore button**

In `RulesScreen.kt`, change the rule label (line 43-46) to branch on `ignored`:

```kotlin
                    Text(
                        if (rule.ignored) {
                            "${rule.bankName} ·${rule.last4 ?: "any"} → Ignored"
                        } else {
                            "${rule.bankName} ·${rule.last4 ?: "any"} → ${rule.currencyCode}"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
```

In the suggestion row (lines 68-75), add an **Ignore** button before "Map →":

```kotlin
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(s.bankName)
                    Text(
                        "${s.bankName} ·${s.last4 ?: "any"}",
                        Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = { viewModel.ignoreSuggestion(s.bankName, s.last4) }) { Text("Ignore") }
                    TextButton(onClick = { onMapSuggestion(s.bankName, s.last4) }) { Text("Map →") }
                }
```

- [ ] **Step 2: RuleEditorScreen — ignore toggle**

In `RuleEditorScreen.kt`, add an `ignore` state next to the others (after line 54):

```kotlin
    var ignore by remember { mutableStateOf(false) }
```

Change `valid` (line 62) so an ignore rule needs only a bank:

```kotlin
    val valid = bank.isNotBlank() && (ignore || (budgetId.isNotBlank() && accountId.isNotBlank()))
```

In the save `onClick` (lines 77-88), pass `ignored = ignore` in the draft and, for an ignore rule, send empty destination fields:

```kotlin
                            scope.launch {
                                val draft = if (ignore) {
                                    RuleDraft(bank, last4, "", "", "", args.editRuleId, ignored = true)
                                } else {
                                    RuleDraft(bank, last4, budgetId, accountId, currency, args.editRuleId)
                                }
                                when (val result = viewModel.saveRule(draft)) {
                                    SaveRuleResult.Saved ->
                                        if (args.prefillBank != null && !ignore) offerImportFor = bank else onDone()
                                    SaveRuleResult.DuplicateRoute ->
                                        error = "A route for this bank + last4 already exists"
                                    is SaveRuleResult.Invalid -> error = result.message
                                }
                            }
```

Replace the "SEND TO" section header + dropdowns (lines 102-113) so the dropdowns hide when ignoring, and add the toggle. Insert a checkbox row and gate the dropdowns:

```kotlin
            Text("SEND TO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ignore, onCheckedChange = { ignore = it })
                Text("Ignore / don't import", style = MaterialTheme.typography.bodyLarge)
            }
            if (!ignore) {
                BudgetDropdown(
                    budgets = budgets,
                    selected = selectedBudget,
                    onSelect = { b -> budgetId = b.id; accountId = ""; viewModel.loadAccounts(b.id) },
                )
                AccountDropdown(
                    accounts = accounts,
                    selected = selectedAccount,
                    enabled = budgetId.isNotBlank(),
                    onSelect = { a -> accountId = a.id },
                )
            }
```

Update the preview call (lines 115-121) to pass `ignored`:

```kotlin
            routePreview(bank, last4, selectedBudget?.name, selectedAccount?.name, currency.ifBlank { null }, ignored = ignore)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
```

Add the missing imports at the top of `RuleEditorScreen.kt`:

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
```

- [ ] **Step 3: Compile and run the full unit-test suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 4: Manual verification checklist**

Build/install a debug build and confirm:
- A "NEEDS ROUTING" suggestion shows both **Ignore** and **Map →**; tapping **Ignore** makes the row vanish and it reappears in the list above as `<bank> ·<last4> → Ignored` with a **Delete** button.
- Deleting that ignore rule brings the suggestion back on the next matching message (undo path).
- In the route editor, ticking **Ignore / don't import** hides the Budget/Account dropdowns, enables **Save** with only a bank filled, and the preview reads `<bank> ·<card> → Ignored`.
- After an ignore rule exists, a fresh matching bank SMS produces no Home-log row (dropped silently).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RulesScreen.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/ui/rules/RuleEditorScreen.kt
git commit -m "feat: ignore-rule UI (suggestion button, editor toggle, list rendering)"
```

---

## Self-Review

**Spec coverage:**
- Data model (`ignored` flag, entity, mapper, wildcard/UNIQUE unchanged) → Task 1.
- Resolution unchanged + classify drops ignored → Task 2 (incl. "ignore bank except one card" emergent behavior).
- Suggestions exclude ignored combos → no query change needed (ignore rule is a `mapping_rules` row satisfying the existing `NOT EXISTS`); proven by Task 3's `ignoreSuggestion … clears the suggestion` test.
- Routes-list rendering `→ Ignored` + delete (undo) → Task 5 Step 1 (delete button already exists).
- Three entry points: suggestion one-tap → Tasks 3+5; editor toggle → Tasks 3+5; manage/undo in routes list → Task 5.
- One-tap writes immediately, no confirm → Task 3 `ignoreSuggestion` + Task 5 Step 1.
- Drop silently, no new `MessageStatus` → Task 2 (reuses `Classification.Dropped`); Global Constraints.

**Deviation from spec (intentional):** the spec mentioned a "Room migration" and "migration test". The DB actually uses `fallbackToDestructiveMigration(dropAllTables = true)` with `exportSchema = false`, so the correct implementation is a version bump only (Task 1, Step 3). No migration class or test is written. This is noted in Global Constraints.

**Placeholder scan:** none — every code step contains the full edit.

**Type consistency:** `ignored` is the single field name across `MappingRule`, `MappingRuleEntity`, `RuleDraft`, and the `routePreview`/`ignoreSuggestion` signatures. `saveRule` returns `SaveRuleResult`; `ignoreSuggestion` returns `Job` (from `viewModelScope.launch`). Ignore-rule destination fields are `""` everywhere.
