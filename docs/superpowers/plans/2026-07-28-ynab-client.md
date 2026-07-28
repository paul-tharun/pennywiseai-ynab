# pennywise-ynab — Plan 3: YNAB Client + Token + Snapshot Fetch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the app's YNAB network layer — the Retrofit/OkHttp client with a bearer-token auth interceptor, the kotlinx-serialization DTOs mirroring YNAB's JSON, encrypted PAT storage, and the token-validation + snapshot-refresh flow that populates the Room snapshot (Plan 2 tables) and re-validates existing routes against it.

**Architecture:** A `data/remote` package holds the Retrofit `YnabApi`, the YNAB DTOs, DTO→entity mappers, an `AuthInterceptor`, and a Hilt `NetworkModule` (OkHttp + Retrofit + a lenient `Json`). A `data/token` package holds a `TokenStore` interface with an `EncryptedSharedPreferences` implementation. A `data/repository` `YnabRepository` orchestrates the flow: validate the PAT via `GET /budgets` → fetch accounts per budget → map to Room entities → `SnapshotDao.replaceSnapshot` → re-validate rules (currency sync + broken detection). Network I/O is unit-tested against OkHttp `MockWebServer`; DTOs against pure kotlinx-serialization; the repository against a fake `YnabApi` + in-memory Room. **Encryption itself is not unit-tested** (it's framework wiring, verified by `assembleDebug` and the on-device smoke check per the spec's Definition of Done); consumers depend on the `TokenStore` interface and tests use a fake.

**Tech Stack:** Adds Retrofit **2.11.0**, OkHttp **4.12.0**, `converter-kotlinx-serialization` 2.11.0, androidx.security:security-crypto **1.1.0-alpha06** (EncryptedSharedPreferences), and `mockwebserver` 4.12.0 (test). Builds on Plan 2's Room persistence (`:app` + pinned `:parser-core` submodule) and Plan 1's `core` domain (`SaveTransaction`, `TransactionMapper`, `MappingRule`, `MappingResolver`).

## Global Constraints

Copied verbatim from the design spec / ADRs / Plans 1–2. Every task's requirements implicitly include this section.

- **SDK/toolchain (unchanged):** `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`, Java **11** bytecode, JDK 21 Gradle toolchain; unit tests execute on a JDK-21 launcher (already configured in `app/build.gradle.kts`). `kotlin.jvm.target.validation.mode=warning`.
- **App identity:** `namespace` = `applicationId` = `com.pennywiseai.ynab`.
- **parser-core is never modified.** Pinned submodule at `third_party/pennywiseai-tracker`. Plan 3 does not touch it.
- **Single network destination (design spec, Security & privacy).** The only host the app ever contacts is `api.ynab.com` over HTTPS. **No** analytics, **no** crash SDK, **no** other host — ever. Do **not** add an OkHttp logging interceptor at any level: it would risk logging the `Authorization` header (the PAT). No third-party tracker/telemetry dependency.
- **Token handling (design spec).** The PAT lives **only** in `EncryptedSharedPreferences` and is sent **only** in the YNAB `Authorization: Bearer <token>` header. It is never logged, never persisted in Room, never placed in a URL.
- **`import_id` is the dedup authority (ADR-0005).** YNAB rejects a duplicate `import_id` within a budget — including per element of a bulk POST — and reports it in `duplicate_import_ids`. The client models this field; the pipeline (Plan 4) acts on it.
- **Fixed 5-status set (CONTEXT.md):** `POSTED`, `SKIPPED_UNROUTED`, `SKIPPED_NON_TRANSACTION`, `SKIPPED_CURRENCY_MISMATCH`, `FAILED`. Do not add a status. Plan 3 adds none.
- **YNAB is always ×1000 milliunits; no FX, no categories (YAGNI).** Plan 3 sends amounts as-is (Plan 1's mapper already computed them).
- **Snapshot DAO REPLACE footgun (Plan 2 carry-forward — MUST close here).** `SnapshotDao.insertBudgets`/`insertAccounts` use `OnConflictStrategy.REPLACE`; a direct call with an existing budget id does DELETE-then-INSERT and **cascades that budget's accounts away**. They are safe only as `replaceSnapshot` internals. Plan 3 is the first code that populates the snapshot: it MUST call **only** `replaceSnapshot`, never the raw inserts, and this plan documents them as internal (Task 4, Step 1).

### Domain / persistence types already defined (consumed, not redefined here)

```kotlin
// com.pennywiseai.ynab.core.model.SaveTransaction  (Plan 1; @Serializable, snake_case @SerialName)
@Serializable
data class SaveTransaction(
    @SerialName("account_id") val accountId: String,
    val date: String,                                   // yyyy-MM-dd
    val amount: Long,                                   // signed milliunits
    @SerialName("payee_name") val payeeName: String? = null,
    val memo: String? = null,
    @SerialName("import_id") val importId: String,
    val approved: Boolean = true,
    val cleared: String = "cleared",
)

// com.pennywiseai.ynab.core.model.MappingRule  (Plan 1; last4 == null is the wildcard)
data class MappingRule(
    val bankName: String, val last4: String?, val budgetId: String,
    val accountId: String, val currencyCode: String,
)

// com.pennywiseai.ynab.data.local.entity.BudgetEntity  (Plan 2)
data class BudgetEntity(val id: String, val name: String, val currencyCode: String)

// com.pennywiseai.ynab.data.local.entity.AccountEntity  (Plan 2)
data class AccountEntity(val id: String, val budgetId: String, val name: String, val closed: Boolean, val deleted: Boolean)

// com.pennywiseai.ynab.data.local.dao.SnapshotDao  (Plan 2) — call ONLY replaceSnapshot to write
suspend fun replaceSnapshot(budgets: List<BudgetEntity>, accounts: List<AccountEntity>)
suspend fun getBudgetCurrency(budgetId: String): String?
suspend fun accountExists(budgetId: String, accountId: String): Boolean

// com.pennywiseai.ynab.data.local.dao.MappingRuleDao  (Plan 2)
suspend fun getAll(): List<MappingRuleEntity>
suspend fun update(rule: MappingRuleEntity)

// com.pennywiseai.ynab.data.mapper  (Plan 2)
fun MappingRuleEntity.toDomain(): MappingRule
```

---

## File Structure

All new code lives under `com.pennywiseai.ynab.data.remote`, `.data.token`, and `.data.repository`, keeping the network layer separate from Plan 2's `data.local` and Plan 1's `core`.

**Modify:**
- `gradle/libs.versions.toml` — add Retrofit, OkHttp, converter, security-crypto, mockwebserver versions + libraries.
- `app/build.gradle.kts` — add the network + test deps.
- `app/src/main/AndroidManifest.xml` — add `<uses-permission android:name="android.permission.INTERNET" />`.
- `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt` — KDoc the raw inserts as `replaceSnapshot`-internal (carry-forward closure; no behavior change).

**Create (main):**
- `data/remote/dto/BudgetDtos.kt` — `BudgetsResponse`/`BudgetsData`/`BudgetDto`/`CurrencyFormatDto`.
- `data/remote/dto/AccountDtos.kt` — `AccountsResponse`/`AccountsData`/`AccountDto`.
- `data/remote/dto/TransactionDtos.kt` — `SaveTransactionsRequest`/`SaveTransactionsResponse`/`SaveTransactionsData`.
- `data/remote/SnapshotMapping.kt` — `BudgetDto.toEntity()`, `AccountDto.toEntity(budgetId)`.
- `data/remote/YnabApi.kt` — the Retrofit interface.
- `data/remote/AuthInterceptor.kt` — attaches the bearer header from `TokenStore`.
- `data/remote/NetworkModule.kt` — Hilt module: `Json`, `OkHttpClient`, `Retrofit`, `YnabApi`.
- `data/token/TokenStore.kt` — the interface.
- `data/token/EncryptedTokenStore.kt` — `EncryptedSharedPreferences`-backed impl.
- `data/token/TokenModule.kt` — Hilt `@Binds` for `TokenStore`.
- `data/repository/SnapshotResult.kt` — the sealed result type.
- `data/repository/YnabRepository.kt` — the orchestration.

**Create (test, under `app/src/test/kotlin/com/pennywiseai/ynab/data/...`):**
- `data/remote/DtoSerializationTest.kt` — pure JVM (kotlinx-serialization).
- `data/remote/AuthInterceptorTest.kt` — JVM + MockWebServer.
- `data/remote/YnabApiTest.kt` — JVM + MockWebServer + Retrofit.
- `data/token/FakeTokenStore.kt` — in-memory test double (test source set, reused by later tests).
- `data/remote/FakeYnabApi.kt` — configurable test double (test source set).
- `data/repository/YnabRepositoryTest.kt` — Robolectric (in-memory Room) + fakes.

### Test strategy

- **DTOs:** pure JVM — decode literal YNAB JSON (with extra unknown keys) via a `Json { ignoreUnknownKeys = true }` and assert fields. No Android, no Robolectric.
- **Interceptor + API:** OkHttp `MockWebServer` on the JVM. Build a real `OkHttpClient`/`Retrofit` pointed at `server.url("/")`, enqueue canned responses, drive `suspend` calls with `runTest`, and inspect `server.takeRequest()` for headers/body. No Robolectric.
- **Repository:** the only Robolectric tests here — they need a real SQLite engine for `SnapshotDao`/`MappingRuleDao`. Use in-memory Room (same harness as Plan 2: `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`, `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`), a `FakeYnabApi` (canned `Response<T>`, or a thrown `IOException` for the offline branch), and a `FakeTokenStore`. This keeps every network branch deterministic without a socket.
- **Encryption:** not unit-tested (Android Keystore is unavailable off-device and the value is a framework guarantee). Covered by `assembleDebug` compiling the wiring (Task 2) and the spec's on-device smoke check. Tests depend on the `TokenStore` interface via the fake.

### Schema/versioning note

Plan 3 adds **no** Room entities or columns — the snapshot tables already exist (Plan 2). `PennyWiseDatabase` stays at `version = 1`, `exportSchema = false`. The only `data.local` change is a KDoc edit on `SnapshotDao`.

---

### Task 1: Network dependencies + YNAB DTOs + JSON deserialization tests

Adds the Retrofit/OkHttp/security-crypto/mockwebserver dependencies and the INTERNET permission, then delivers the YNAB DTOs proven by pure-JVM deserialization tests against realistic YNAB JSON (including unknown fields, to lock in lenient parsing).

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/dto/BudgetDtos.kt`, `.../dto/AccountDtos.kt`, `.../dto/TransactionDtos.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/remote/DtoSerializationTest.kt`

**Interfaces:**
- Consumes: `com.pennywiseai.ynab.core.model.SaveTransaction` (Plan 1) as the POST-body element.
- Produces:
  - `@Serializable BudgetsResponse(data: BudgetsData)`, `BudgetsData(budgets: List<BudgetDto>)`, `BudgetDto(id: String, name: String, currency_format: CurrencyFormatDto?)`, `CurrencyFormatDto(iso_code: String)`
  - `@Serializable AccountsResponse(data: AccountsData)`, `AccountsData(accounts: List<AccountDto>)`, `AccountDto(id: String, name: String, closed: Boolean, deleted: Boolean)`
  - `@Serializable SaveTransactionsRequest(transactions: List<SaveTransaction>)`, `SaveTransactionsResponse(data: SaveTransactionsData)`, `SaveTransactionsData(transaction_ids: List<String>, duplicate_import_ids: List<String>)`

- [ ] **Step 1: Add dependencies to the version catalog**

Edit `gradle/libs.versions.toml`. Under `[versions]` add:

```toml
retrofit = "2.11.0"
okhttp = "4.12.0"
securityCrypto = "1.1.0-alpha06"
```

Under `[libraries]` add:

```toml
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { module = "com.squareup.retrofit2:converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "securityCrypto" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
```

(No `[plugins]` changes — `kotlin-serialization` is already declared and applied.)

- [ ] **Step 2: Wire the deps into `app/build.gradle.kts`**

In the `dependencies { ... }` block, add the network deps alongside the existing ones and extend the test deps:

```kotlin
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.okhttp.mockwebserver)
```

`kotlinx-serialization-json` is already a dependency (Plan 1). Retrofit pulls OkHttp transitively, but we declare `okhttp` explicitly because `AuthInterceptor` and `NetworkModule` reference OkHttp types directly.

- [ ] **Step 3: Add the INTERNET permission**

Edit `app/src/main/AndroidManifest.xml`, adding the permission above the `<application>` tag:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

This is the single capability that lets the app reach `api.ynab.com`. No `usesCleartextTraffic` — YNAB is HTTPS-only.

- [ ] **Step 4: Create the budget DTOs** `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/dto/BudgetDtos.kt`

```kotlin
package com.pennywiseai.ynab.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for `GET /budgets`. YNAB wraps every payload in a top-level `data` object.
 * Only the fields the app needs are modeled; the client's Json is configured with
 * ignoreUnknownKeys = true so YNAB's many other fields are dropped silently.
 * `currency_format` is nullable defensively — a budget without one cannot be
 * routed (no currency for the mismatch guard) and is filtered out upstream.
 */
@Serializable
data class BudgetsResponse(val data: BudgetsData)

@Serializable
data class BudgetsData(val budgets: List<BudgetDto>)

@Serializable
data class BudgetDto(
    val id: String,
    val name: String,
    @SerialName("currency_format") val currencyFormat: CurrencyFormatDto? = null,
)

@Serializable
data class CurrencyFormatDto(
    @SerialName("iso_code") val isoCode: String,
)
```

- [ ] **Step 5: Create the account DTOs** `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/dto/AccountDtos.kt`

```kotlin
package com.pennywiseai.ynab.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTOs for `GET /budgets/{id}/accounts`. `closed`/`deleted` mirror YNAB and are
 * carried into the snapshot so the picker can filter them out (Plan 2's
 * getOpenAccounts). Balance, type, etc. are unmodeled and ignored.
 */
@Serializable
data class AccountsResponse(val data: AccountsData)

@Serializable
data class AccountsData(val accounts: List<AccountDto>)

@Serializable
data class AccountDto(
    val id: String,
    val name: String,
    val closed: Boolean,
    val deleted: Boolean,
)
```

- [ ] **Step 6: Create the transaction DTOs** `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/dto/TransactionDtos.kt`

```kotlin
package com.pennywiseai.ynab.data.remote.dto

import com.pennywiseai.ynab.core.model.SaveTransaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for `POST /budgets/{id}/transactions`. The request body is always the
 * `transactions` array (bulk) — real-time sends one element, backfill sends a
 * chunk (ADR-0004). The response reports created `transaction_ids` and, crucially,
 * `duplicate_import_ids`: elements YNAB rejected as an already-present import_id.
 * Both default to empty so a partial/absent field never NPEs. The pipeline (Plan 4)
 * maps these to POSTED. Defaults make dedup the sole authority (ADR-0005).
 */
@Serializable
data class SaveTransactionsRequest(val transactions: List<SaveTransaction>)

@Serializable
data class SaveTransactionsResponse(val data: SaveTransactionsData)

@Serializable
data class SaveTransactionsData(
    @SerialName("transaction_ids") val transactionIds: List<String> = emptyList(),
    @SerialName("duplicate_import_ids") val duplicateImportIds: List<String> = emptyList(),
)
```

- [ ] **Step 7: Write the DTO deserialization test** `app/src/test/kotlin/com/pennywiseai/ynab/data/remote/DtoSerializationTest.kt`

```kotlin
package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.core.model.SaveTransaction
import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `budgets response parses id name and iso currency, ignoring unknown fields`() {
        val body = """
            {"data":{"budgets":[
              {"id":"b1","name":"Personal","last_modified_on":"2026-01-01",
               "currency_format":{"iso_code":"USD","decimal_digits":2,"symbol_first":true}},
              {"id":"b2","name":"Family","currency_format":{"iso_code":"INR","decimal_digits":2}}
            ],"default_budget":null}}
        """.trimIndent()
        val parsed = json.decodeFromString<BudgetsResponse>(body)
        assertEquals(listOf("b1", "b2"), parsed.data.budgets.map { it.id })
        assertEquals("USD", parsed.data.budgets[0].currencyFormat?.isoCode)
        assertEquals("INR", parsed.data.budgets[1].currencyFormat?.isoCode)
    }

    @Test
    fun `budget with no currency_format decodes to null`() {
        val body = """{"data":{"budgets":[{"id":"b1","name":"NoCurrency"}]}}"""
        val parsed = json.decodeFromString<BudgetsResponse>(body)
        assertNull(parsed.data.budgets.single().currencyFormat)
    }

    @Test
    fun `accounts response parses closed and deleted flags`() {
        val body = """
            {"data":{"accounts":[
              {"id":"a1","name":"Checking","type":"checking","on_budget":true,"closed":false,"deleted":false,"balance":123000},
              {"id":"a2","name":"Old Card","type":"creditCard","closed":true,"deleted":false}
            ]}}
        """.trimIndent()
        val parsed = json.decodeFromString<AccountsResponse>(body)
        assertEquals(listOf("a1", "a2"), parsed.data.accounts.map { it.id })
        assertEquals(false, parsed.data.accounts[0].closed)
        assertEquals(true, parsed.data.accounts[1].closed)
    }

    @Test
    fun `save transactions request serializes to the transactions array with snake_case`() {
        val request = SaveTransactionsRequest(
            transactions = listOf(
                SaveTransaction(
                    accountId = "a1", date = "2026-07-28", amount = -100_000L,
                    payeeName = "Coffee", memo = "ref123", importId = "PW:abc",
                ),
            ),
        )
        val encoded = Json.encodeToString(request)
        // snake_case field names and the wrapping array must be present for YNAB.
        assertEquals(true, encoded.contains("\"transactions\""))
        assertEquals(true, encoded.contains("\"account_id\":\"a1\""))
        assertEquals(true, encoded.contains("\"import_id\":\"PW:abc\""))
        assertEquals(true, encoded.contains("\"amount\":-100000"))
    }

    @Test
    fun `post response parses transaction_ids and duplicate_import_ids`() {
        val body = """
            {"data":{"transaction_ids":["t1","t2"],"duplicate_import_ids":["PW:dup"],
                     "transactions":[],"server_knowledge":42}}
        """.trimIndent()
        val parsed = json.decodeFromString<SaveTransactionsResponse>(body)
        assertEquals(listOf("t1", "t2"), parsed.data.transactionIds)
        assertEquals(listOf("PW:dup"), parsed.data.duplicateImportIds)
    }

    @Test
    fun `post response with missing dedup fields defaults to empty lists`() {
        val parsed = json.decodeFromString<SaveTransactionsResponse>("""{"data":{}}""")
        assertEquals(emptyList<String>(), parsed.data.transactionIds)
        assertEquals(emptyList<String>(), parsed.data.duplicateImportIds)
    }
}
```

- [ ] **Step 8: Run the DTO test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.remote.DtoSerializationTest"`
Expected: PASS (6 tests). If it fails to *compile*, re-check Steps 1–2 (catalog + build wiring) and Step 4–6 imports. `Json.encodeToString(request)` uses the reified overload — no explicit serializer needed.

- [ ] **Step 9: Prove the deps dex into the APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Confirms Retrofit/OkHttp/security-crypto resolve and dex on the Android path. A `kotlin.jvm.target.validation` warning is expected and non-fatal.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml \
        app/src/main/kotlin/com/pennywiseai/ynab/data/remote/dto/BudgetDtos.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/remote/dto/AccountDtos.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/remote/dto/TransactionDtos.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/remote/DtoSerializationTest.kt
git commit -m "feat: add Retrofit/OkHttp deps + YNAB DTOs with lenient JSON parsing"
```

---

### Task 2: Encrypted token storage + bearer auth interceptor

Delivers the PAT storage seam and the interceptor that attaches it. `TokenStore` is an interface so the interceptor and repository depend on an abstraction; `EncryptedTokenStore` is the real `EncryptedSharedPreferences` implementation (compiled + dexed, not unit-tested); `FakeTokenStore` (test source) drives the interceptor test and later the repository test. The interceptor's behavior — attach `Authorization: Bearer <token>` when a token exists, send nothing when it doesn't — is proven against MockWebServer.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/data/token/TokenStore.kt`, `.../token/EncryptedTokenStore.kt`, `.../token/TokenModule.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/AuthInterceptor.kt`
- Create (test): `app/src/test/kotlin/com/pennywiseai/ynab/data/token/FakeTokenStore.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/remote/AuthInterceptorTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `interface TokenStore { fun getToken(): String?; fun setToken(token: String); fun clear() }`
  - `class EncryptedTokenStore(context) : TokenStore` — `@Inject constructor`, Hilt-bound as `TokenStore`.
  - `class AuthInterceptor(tokenStore: TokenStore) : Interceptor` — adds the bearer header when `getToken()` is non-blank.
  - `class FakeTokenStore(initial: String? = null) : TokenStore` (test) — in-memory, reused by Task 4/5.

- [ ] **Step 1: Create the token-store interface** `app/src/main/kotlin/com/pennywiseai/ynab/data/token/TokenStore.kt`

```kotlin
package com.pennywiseai.ynab.data.token

/**
 * The single seam for the YNAB Personal Access Token. The token is a secret: it
 * lives only behind this interface (encrypted at rest) and is read only by the
 * AuthInterceptor to build the Authorization header. Kept an interface so
 * consumers depend on the abstraction and tests use an in-memory fake — the
 * encrypted implementation needs the Android Keystore and is not unit-tested.
 */
interface TokenStore {
    /** The stored PAT, or null if none is set. */
    fun getToken(): String?

    /** Store (or overwrite) the PAT. */
    fun setToken(token: String)

    /** Remove the stored PAT (used when the user clears/replaces it). */
    fun clear()
}
```

- [ ] **Step 2: Create the encrypted implementation** `app/src/main/kotlin/com/pennywiseai/ynab/data/token/EncryptedTokenStore.kt`

```kotlin
package com.pennywiseai.ynab.data.token

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TokenStore backed by EncryptedSharedPreferences (AES-256, key in the Android
 * Keystore). security-crypto is deprecated but adequate for a single secret in v1
 * (design spec, Token storage). Not unit-tested — the Keystore is unavailable
 * off-device; correctness is a framework guarantee, verified by assembleDebug and
 * the on-device smoke check.
 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : TokenStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    override fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private companion object {
        const val PREFS_FILE = "ynab_secure_prefs"
        const val KEY_TOKEN = "ynab_pat"
    }
}
```

- [ ] **Step 3: Bind the implementation in Hilt** `app/src/main/kotlin/com/pennywiseai/ynab/data/token/TokenModule.kt`

```kotlin
package com.pennywiseai.ynab.data.token

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TokenModule {

    @Binds
    @Singleton
    abstract fun bindTokenStore(impl: EncryptedTokenStore): TokenStore
}
```

- [ ] **Step 4: Create the auth interceptor** `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/AuthInterceptor.kt`

```kotlin
package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.token.TokenStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the YNAB PAT as `Authorization: Bearer <token>` on every outgoing
 * request. When no token is stored the header is omitted (the request will 401,
 * which the repository/pipeline handle) — the interceptor never invents a header.
 * Reads the token fresh per request so a token saved mid-session takes effect
 * immediately. The token appears only here; there is no logging interceptor that
 * could leak it (design spec, Single network destination).
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.getToken()
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
```

- [ ] **Step 5: Create the fake token store (test source)** `app/src/test/kotlin/com/pennywiseai/ynab/data/token/FakeTokenStore.kt`

```kotlin
package com.pennywiseai.ynab.data.token

/** In-memory TokenStore for tests (no Keystore). Reused across remote/repository tests. */
class FakeTokenStore(initial: String? = null) : TokenStore {
    private var token: String? = initial
    override fun getToken(): String? = token
    override fun setToken(token: String) { this.token = token }
    override fun clear() { token = null }
}
```

- [ ] **Step 6: Write the interceptor test** `app/src/test/kotlin/com/pennywiseai/ynab/data/remote/AuthInterceptorTest.kt`

```kotlin
package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.token.FakeTokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun clientWith(token: String?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(FakeTokenStore(token)))
            .build()

    private fun call(client: OkHttpClient) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(Request.Builder().url(server.url("/v1/budgets")).build())
            .execute().close()
    }

    @Test
    fun `attaches bearer header when a token is present`() {
        call(clientWith("secret-pat"))
        val recorded = server.takeRequest()
        assertEquals("Bearer secret-pat", recorded.getHeader("Authorization"))
    }

    @Test
    fun `omits the header when no token is set`() {
        call(clientWith(null))
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `omits the header when the token is blank`() {
        call(clientWith("   "))
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }
}
```

- [ ] **Step 7: Run the interceptor test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.remote.AuthInterceptorTest"`
Expected: PASS (3 tests). MockWebServer binds a localhost port; no Robolectric needed.

- [ ] **Step 8: Prove the encrypted store + Hilt binding dex**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. This is the only verification of `EncryptedTokenStore`/`TokenModule` (encryption is not unit-tested); it confirms the security-crypto API and the Hilt `@Binds` graph compile.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/data/token/TokenStore.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/token/EncryptedTokenStore.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/token/TokenModule.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/remote/AuthInterceptor.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/token/FakeTokenStore.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/remote/AuthInterceptorTest.kt
git commit -m "feat: encrypted PAT storage (TokenStore) + bearer auth interceptor"
```

---

### Task 3: Retrofit YnabApi + NetworkModule

Wires the client together: the Retrofit `YnabApi` (the three endpoints), and the Hilt `NetworkModule` that provides the lenient `Json`, the `OkHttpClient` (with `AuthInterceptor`), the `Retrofit` (base `https://api.ynab.com/`), and the `YnabApi`. Endpoint round-trips — request path/body and response parsing, including `duplicate_import_ids` — are proven against MockWebServer with real Retrofit + the real `Json`.

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/YnabApi.kt`, `.../remote/NetworkModule.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/remote/YnabApiTest.kt`

**Interfaces:**
- Consumes: `AuthInterceptor` (Task 2); all DTOs + `SaveTransaction` (Task 1).
- Produces:
  - `interface YnabApi { suspend fun getBudgets(): Response<BudgetsResponse>; suspend fun getAccounts(budgetId): Response<AccountsResponse>; suspend fun postTransactions(budgetId, body): Response<SaveTransactionsResponse> }`
  - Hilt-provided `Json`, `OkHttpClient`, `Retrofit`, `YnabApi` (all `@Singleton`). `YnabApi` is injected by `YnabRepository` (Task 4).

- [ ] **Step 1: Create the Retrofit interface** `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/YnabApi.kt`

```kotlin
package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The YNAB REST surface the app uses. Every call returns Response<T> so callers
 * can branch on the HTTP status (401 unauthorized, 404 missing budget/account,
 * 429 rate limit, 5xx) — status classification lives in the repository (snapshot)
 * and the pipeline (Plan 4, posting). Base URL is https://api.ynab.com/ so paths
 * carry the `v1/` prefix.
 */
interface YnabApi {

    @GET("v1/budgets")
    suspend fun getBudgets(): Response<BudgetsResponse>

    @GET("v1/budgets/{budgetId}/accounts")
    suspend fun getAccounts(@Path("budgetId") budgetId: String): Response<AccountsResponse>

    @POST("v1/budgets/{budgetId}/transactions")
    suspend fun postTransactions(
        @Path("budgetId") budgetId: String,
        @Body body: SaveTransactionsRequest,
    ): Response<SaveTransactionsResponse>
}
```

- [ ] **Step 2: Create the network module** `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/NetworkModule.kt`

```kotlin
package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.token.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

/**
 * Provides the single-destination YNAB client. There is deliberately NO logging
 * interceptor — it could record the Authorization header (the PAT). The Json is
 * lenient (ignoreUnknownKeys) because YNAB returns far more per object than the
 * app models.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://api.ynab.com/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // send approved/cleared even at their defaults
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenStore: TokenStore): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .build()

    @OptIn(ExperimentalSerializationApi::class)
    @Provides
    @Singleton
    fun provideRetrofit(json: Json, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideYnabApi(retrofit: Retrofit): YnabApi = retrofit.create(YnabApi::class.java)
}
```

- [ ] **Step 3: Write the API round-trip test** `app/src/test/kotlin/com/pennywiseai/ynab/data/remote/YnabApiTest.kt`

```kotlin
package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.core.model.SaveTransaction
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import com.pennywiseai.ynab.data.token.FakeTokenStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class YnabApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: YnabApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(FakeTokenStore("pat")))
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YnabApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `getBudgets hits v1 budgets and parses the body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"budgets":[{"id":"b1","name":"Personal","currency_format":{"iso_code":"USD"}}]}}""",
            ),
        )
        val response = api.getBudgets()
        assertTrue(response.isSuccessful)
        assertEquals("USD", response.body()!!.data.budgets.single().currencyFormat?.isoCode)
        assertEquals("/v1/budgets", server.takeRequest().path)
    }

    @Test
    fun `getAccounts interpolates the budget id into the path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"accounts":[{"id":"a1","name":"Checking","closed":false,"deleted":false}]}}""",
            ),
        )
        val response = api.getAccounts("b1")
        assertEquals("a1", response.body()!!.data.accounts.single().id)
        assertEquals("/v1/budgets/b1/accounts", server.takeRequest().path)
    }

    @Test
    fun `postTransactions sends the transactions array and parses dedup fields`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"data":{"transaction_ids":["t1"],"duplicate_import_ids":["PW:dup"]}}""",
            ),
        )
        val request = SaveTransactionsRequest(
            transactions = listOf(
                SaveTransaction(accountId = "a1", date = "2026-07-28", amount = -100_000L, importId = "PW:abc"),
            ),
        )
        val response = api.postTransactions("b1", request)

        assertEquals(listOf("t1"), response.body()!!.data.transactionIds)
        assertEquals(listOf("PW:dup"), response.body()!!.data.duplicateImportIds)

        val recorded = server.takeRequest()
        assertEquals("/v1/budgets/b1/transactions", recorded.path)
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"transactions\""))
        assertTrue(sentBody.contains("\"import_id\":\"PW:abc\""))
        assertTrue(sentBody.contains("\"account_id\":\"a1\""))
    }

    @Test
    fun `a 401 is surfaced as an unsuccessful response, not an exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"id":"401"}}"""))
        val response = api.getBudgets()
        assertEquals(false, response.isSuccessful)
        assertEquals(401, response.code())
    }
}
```

- [ ] **Step 4: Run the API test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.remote.YnabApiTest"`
Expected: PASS (4 tests). This exercises the real Retrofit + kotlinx converter path end-to-end (the same wiring `NetworkModule` provides) without an emulator.

- [ ] **Step 5: Prove the Hilt graph compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Confirms `NetworkModule`'s providers form a valid graph with `TokenModule` (`AuthInterceptor` gets a `TokenStore`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/data/remote/YnabApi.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/remote/NetworkModule.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/remote/YnabApiTest.kt
git commit -m "feat: Retrofit YnabApi + Hilt NetworkModule (single-destination client)"
```

---

### Task 4: Snapshot fetch — DTO→entity mappers + YnabRepository

Delivers the core Plan 3 flow: `YnabRepository.saveTokenAndRefresh(token)` stores the PAT, validates it via `GET /budgets`, fetches accounts per budget, maps DTOs to Room entities, and persists the whole tree via `SnapshotDao.replaceSnapshot` — returning a `SnapshotResult` that distinguishes success, an invalid token (`401`), and a network error. Rule re-validation is stubbed here (empty `brokenRules`) and filled in Task 5. **This task closes the Plan 2 carry-forward** by documenting `SnapshotDao`'s raw inserts as internal and writing only through `replaceSnapshot`.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt` (KDoc the raw inserts; **no behavior change**)
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/SnapshotMapping.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/data/repository/SnapshotResult.kt`, `.../repository/YnabRepository.kt`
- Create (test): `app/src/test/kotlin/com/pennywiseai/ynab/data/remote/FakeYnabApi.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/repository/YnabRepositoryTest.kt`

**Interfaces:**
- Consumes: `YnabApi` (Task 3), `SnapshotDao` (Plan 2), `TokenStore` (Task 2); DTOs (Task 1); `BudgetEntity`/`AccountEntity` (Plan 2).
- Produces:
  - `fun BudgetDto.toEntity(): BudgetEntity?` (null when `currencyFormat` is absent); `fun AccountDto.toEntity(budgetId: String): AccountEntity`
  - `sealed interface SnapshotResult { data class Success(budgetCount, accountCount, brokenRules: List<MappingRule>); data object Unauthorized; data class Error(message) }`
  - `class YnabRepository(api, snapshotDao, tokenStore) { suspend fun saveTokenAndRefresh(token): SnapshotResult; suspend fun refreshSnapshot(): SnapshotResult }` — **`mappingRuleDao` is added to the constructor in Task 5.**
  - `class FakeYnabApi : YnabApi` (test) — configurable budgets/accounts responses; can throw `IOException`.

- [ ] **Step 1: Document SnapshotDao's raw inserts as internal (Plan 2 carry-forward closure)**

Edit `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt`. Replace the two bare `@Insert` methods' declarations with KDoc'd versions (behavior unchanged — Room still generates the same impl; the `replaceSnapshot` body already calls them):

```kotlin
    /**
     * Internal to replaceSnapshot — NOT a general-purpose upsert. REPLACE does
     * DELETE-then-INSERT, and DELETE cascades a budget's accounts away (FK
     * ON DELETE CASCADE). Safe only because replaceSnapshot clears first, so these
     * hit empty tables. Callers (YnabRepository) MUST write via replaceSnapshot.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgets(budgets: List<BudgetEntity>)

    /** Internal to replaceSnapshot — see insertBudgets. Do not call directly. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<AccountEntity>)
```

- [ ] **Step 2: Create the DTO→entity mappers** `app/src/main/kotlin/com/pennywiseai/ynab/data/remote/SnapshotMapping.kt`

```kotlin
package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.data.remote.dto.AccountDto
import com.pennywiseai.ynab.data.remote.dto.BudgetDto

/**
 * A budget maps to a snapshot row only if it carries a currency: the currency is
 * required for the offline mismatch guard and to store on rules, so a
 * currency-less budget is unroutable and dropped (returns null).
 */
fun BudgetDto.toEntity(): BudgetEntity? = currencyFormat?.let {
    BudgetEntity(id = id, name = name, currencyCode = it.isoCode)
}

fun AccountDto.toEntity(budgetId: String): AccountEntity = AccountEntity(
    id = id,
    budgetId = budgetId,
    name = name,
    closed = closed,
    deleted = deleted,
)
```

- [ ] **Step 3: Create the result type** `app/src/main/kotlin/com/pennywiseai/ynab/data/repository/SnapshotResult.kt`

```kotlin
package com.pennywiseai.ynab.data.repository

import com.pennywiseai.ynab.core.model.MappingRule

/**
 * Outcome of a token save / snapshot refresh. Unauthorized is kept distinct from
 * Error because a 401 means "no valid token" (the pipeline pauses posting; the UI
 * prompts for a new PAT), whereas Error is a transient/offline failure the user
 * can retry. `brokenRules` (populated in Task 5) are existing routes whose target
 * budget/account no longer exists in the fresh snapshot — surfaced to the user,
 * not treated as a posting failure.
 */
sealed interface SnapshotResult {
    data class Success(
        val budgetCount: Int,
        val accountCount: Int,
        val brokenRules: List<MappingRule>,
    ) : SnapshotResult

    data object Unauthorized : SnapshotResult

    data class Error(val message: String) : SnapshotResult
}
```

- [ ] **Step 4: Create the repository** `app/src/main/kotlin/com/pennywiseai/ynab/data/repository/YnabRepository.kt`

```kotlin
package com.pennywiseai.ynab.data.repository

import com.pennywiseai.ynab.data.local.dao.SnapshotDao
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.remote.YnabApi
import com.pennywiseai.ynab.data.remote.toEntity
import com.pennywiseai.ynab.data.token.TokenStore
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates token validation + snapshot refresh (design spec, Settings). Writes
 * the snapshot ONLY through SnapshotDao.replaceSnapshot (its raw inserts cascade —
 * Plan 2 carry-forward). Rule re-validation is added in Task 5.
 */
@Singleton
class YnabRepository @Inject constructor(
    private val api: YnabApi,
    private val snapshotDao: SnapshotDao,
    private val tokenStore: TokenStore,
) {

    /** Store the PAT, then validate it + refresh the snapshot in one flow. */
    suspend fun saveTokenAndRefresh(token: String): SnapshotResult {
        tokenStore.setToken(token)
        return refreshSnapshot()
    }

    /** Re-pull budgets → accounts and atomically replace the local snapshot. */
    suspend fun refreshSnapshot(): SnapshotResult {
        val budgetsResponse = try {
            api.getBudgets()
        } catch (e: IOException) {
            return SnapshotResult.Error(e.message ?: "network error")
        }
        classify(budgetsResponse)?.let { return it }

        val budgets = budgetsResponse.body()?.data?.budgets.orEmpty()
            .mapNotNull { it.toEntity() } // drop currency-less budgets

        val accounts = mutableListOf<AccountEntity>()
        for (budget in budgets) {
            val accountsResponse = try {
                api.getAccounts(budget.id)
            } catch (e: IOException) {
                return SnapshotResult.Error(e.message ?: "network error")
            }
            classify(accountsResponse)?.let { return it }
            accountsResponse.body()?.data?.accounts.orEmpty()
                .forEach { accounts += it.toEntity(budget.id) }
        }

        snapshotDao.replaceSnapshot(budgets, accounts)

        // brokenRules is filled in Task 5; snapshot persistence stands alone here.
        return SnapshotResult.Success(budgets.size, accounts.size, brokenRules = emptyList())
    }

    /** Map a non-success HTTP status to a SnapshotResult, or null if successful. */
    private fun classify(response: Response<*>): SnapshotResult? = when {
        response.isSuccessful -> null
        response.code() == 401 -> SnapshotResult.Unauthorized
        else -> SnapshotResult.Error("HTTP ${response.code()}")
    }
}
```

- [ ] **Step 5: Create the fake API (test source)** `app/src/test/kotlin/com/pennywiseai/ynab/data/remote/FakeYnabApi.kt`

```kotlin
package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.remote.dto.AccountsData
import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetsData
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Configurable YnabApi double for repository tests. Lambdas let a test return a
 * canned body, an error code, or throw IOException (offline). accountsByBudget maps
 * a budget id to its accounts response.
 */
class FakeYnabApi : YnabApi {

    var budgets: () -> Response<BudgetsResponse> =
        { Response.success(BudgetsResponse(BudgetsData(emptyList()))) }

    var accountsByBudget: (String) -> Response<AccountsResponse> =
        { Response.success(AccountsResponse(AccountsData(emptyList()))) }

    override suspend fun getBudgets(): Response<BudgetsResponse> = budgets()

    override suspend fun getAccounts(budgetId: String): Response<AccountsResponse> =
        accountsByBudget(budgetId)

    override suspend fun postTransactions(
        budgetId: String,
        body: SaveTransactionsRequest,
    ): Response<SaveTransactionsResponse> = throw UnsupportedOperationException("not used in Plan 3")

    companion object {
        /** Build a Retrofit-style error Response for a given HTTP code. */
        fun <T> error(code: Int): Response<T> =
            Response.error(code, "{}".toResponseBody("application/json".toMediaType()))
    }
}
```

- [ ] **Step 6: Write the repository test** `app/src/test/kotlin/com/pennywiseai/ynab/data/repository/YnabRepositoryTest.kt`

```kotlin
package com.pennywiseai.ynab.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.PennyWiseDatabase
import com.pennywiseai.ynab.data.remote.FakeYnabApi
import com.pennywiseai.ynab.data.remote.dto.AccountDto
import com.pennywiseai.ynab.data.remote.dto.AccountsData
import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetDto
import com.pennywiseai.ynab.data.remote.dto.BudgetsData
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.CurrencyFormatDto
import com.pennywiseai.ynab.data.token.FakeTokenStore
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YnabRepositoryTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var api: FakeYnabApi
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var repository: YnabRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = FakeYnabApi()
        tokenStore = FakeTokenStore()
        repository = YnabRepository(api, db.snapshotDao(), tokenStore)
    }

    @After
    fun tearDown() = db.close()

    private fun budget(id: String, iso: String?) =
        BudgetDto(id = id, name = "Budget $id", currencyFormat = iso?.let { CurrencyFormatDto(it) })

    private fun account(id: String) =
        AccountDto(id = id, name = "Acct $id", closed = false, deleted = false)

    private fun budgetsOk(vararg b: BudgetDto) =
        Response.success(BudgetsResponse(BudgetsData(b.toList())))

    private fun accountsOk(vararg a: AccountDto) =
        Response.success(AccountsResponse(AccountsData(a.toList())))

    @Test
    fun `saveTokenAndRefresh stores the token and persists the snapshot`() = runTest {
        api.budgets = { budgetsOk(budget("b1", "USD")) }
        api.accountsByBudget = { accountsOk(account("a1"), account("a2")) }

        val result = repository.saveTokenAndRefresh("my-pat")

        assertTrue(result is SnapshotResult.Success)
        result as SnapshotResult.Success
        assertEquals(1, result.budgetCount)
        assertEquals(2, result.accountCount)
        assertEquals("my-pat", tokenStore.getToken())
        assertEquals("USD", db.snapshotDao().getBudgetCurrency("b1"))
        assertEquals(listOf("a1", "a2"), db.snapshotDao().getOpenAccounts("b1").map { it.id })
    }

    @Test
    fun `accounts are fetched per budget and associated with the right budget`() = runTest {
        api.budgets = { budgetsOk(budget("b1", "USD"), budget("b2", "INR")) }
        api.accountsByBudget = { budgetId ->
            if (budgetId == "b1") accountsOk(account("a1")) else accountsOk(account("a2"))
        }

        repository.refreshSnapshot()

        assertEquals(listOf("a1"), db.snapshotDao().getOpenAccounts("b1").map { it.id })
        assertEquals(listOf("a2"), db.snapshotDao().getOpenAccounts("b2").map { it.id })
    }

    @Test
    fun `a currency-less budget is dropped from the snapshot`() = runTest {
        api.budgets = { budgetsOk(budget("b1", "USD"), budget("b2", null)) }
        api.accountsByBudget = { accountsOk(account("a1")) }

        val result = repository.refreshSnapshot() as SnapshotResult.Success

        assertEquals(1, result.budgetCount)
        assertEquals(listOf("b1"), db.snapshotDao().getBudgets().map { it.id })
    }

    @Test
    fun `a 401 on budgets returns Unauthorized and does not touch the snapshot`() = runTest {
        api.budgets = { Response.error(401, "{}".toResponseBody("application/json".toMediaType())) }

        val result = repository.saveTokenAndRefresh("bad-pat")

        assertEquals(SnapshotResult.Unauthorized, result)
        assertEquals(emptyList<String>(), db.snapshotDao().getBudgets().map { it.id })
    }

    @Test
    fun `an IOException on budgets returns Error`() = runTest {
        api.budgets = { throw IOException("offline") }

        val result = repository.refreshSnapshot()

        assertTrue(result is SnapshotResult.Error)
        assertEquals("offline", (result as SnapshotResult.Error).message)
    }

    @Test
    fun `refresh replaces the previous snapshot`() = runTest {
        api.budgets = { budgetsOk(budget("b1", "USD")) }
        api.accountsByBudget = { accountsOk(account("a1")) }
        repository.refreshSnapshot()

        api.budgets = { budgetsOk(budget("b2", "INR")) }
        api.accountsByBudget = { accountsOk(account("a2")) }
        repository.refreshSnapshot()

        assertEquals(listOf("b2"), db.snapshotDao().getBudgets().map { it.id })
        assertEquals(emptyList<String>(), db.snapshotDao().getOpenAccounts("b1").map { it.id })
    }
}
```

- [ ] **Step 7: Run the repository test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.repository.YnabRepositoryTest"`
Expected: PASS (6 tests). The replace test confirms writes go through `replaceSnapshot` (old tree cascades out), closing the carry-forward in practice.

- [ ] **Step 8: Prove the whole app still builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. `YnabRepository` is `@Inject`-constructed from Hilt-provided `YnabApi`/`SnapshotDao`/`TokenStore`, so this also validates the full graph.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/remote/SnapshotMapping.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/repository/SnapshotResult.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/repository/YnabRepository.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/remote/FakeYnabApi.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/repository/YnabRepositoryTest.kt
git commit -m "feat: YnabRepository snapshot fetch (validate token, persist tree); close SnapshotDao cascade footgun"
```

---

### Task 5: Rule re-validation on refresh — broken detection + currency sync

Completes the "snapshot validation on token save" behavior (design spec, Settings): after persisting the fresh snapshot, re-check every existing rule against it. A rule whose `(budgetId, accountId)` no longer resolves is returned as **broken** (surfaced to the user; its messages will log `SKIPPED_UNROUTED`, handled by the pipeline — no new status). A rule that still resolves but whose budget currency changed has its stored `currencyCode` updated automatically.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/repository/YnabRepository.kt` (inject `MappingRuleDao`; add `revalidateRules`; wire into `refreshSnapshot`)
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/repository/YnabRepositoryTest.kt` (add cases)

**Interfaces:**
- Consumes: `MappingRuleDao.getAll()`/`update()` (Plan 2), `MappingRuleEntity.toDomain()` (Plan 2), `SnapshotDao.accountExists`/`getBudgetCurrency` (Plan 2).
- Produces: `YnabRepository(api, snapshotDao, mappingRuleDao, tokenStore)` — constructor now takes `mappingRuleDao`; `SnapshotResult.Success.brokenRules` is now populated.

- [ ] **Step 1: Add the failing test cases** — append to `YnabRepositoryTest.kt`

Add these imports at the top of the existing file:

```kotlin
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
```

Update `setUp()` to pass the new DAO into the constructor (the signature changes this task):

```kotlin
        repository = YnabRepository(api, db.snapshotDao(), db.mappingRuleDao(), tokenStore)
```

Add these test methods to the class:

```kotlin
    @Test
    fun `a rule whose account vanished from the new snapshot is reported broken`() = runTest {
        db.mappingRuleDao().insert(
            MappingRuleEntity(bankName = "HDFC", last4 = "1234", budgetId = "b1", accountId = "gone", currencyCode = "USD"),
        )
        api.budgets = { budgetsOk(budget("b1", "USD")) }
        api.accountsByBudget = { accountsOk(account("a1")) } // "gone" is not here

        val result = repository.refreshSnapshot() as SnapshotResult.Success

        assertEquals(1, result.brokenRules.size)
        assertEquals("gone", result.brokenRules.single().accountId)
    }

    @Test
    fun `a rule that still resolves is not broken`() = runTest {
        db.mappingRuleDao().insert(
            MappingRuleEntity(bankName = "HDFC", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "USD"),
        )
        api.budgets = { budgetsOk(budget("b1", "USD")) }
        api.accountsByBudget = { accountsOk(account("a1")) }

        val result = repository.refreshSnapshot() as SnapshotResult.Success

        assertEquals(emptyList<Any>(), result.brokenRules)
    }

    @Test
    fun `a resolving rule whose budget currency changed has its stored currency updated`() = runTest {
        db.mappingRuleDao().insert(
            MappingRuleEntity(bankName = "HDFC", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "USD"),
        )
        api.budgets = { budgetsOk(budget("b1", "INR")) } // budget currency now INR
        api.accountsByBudget = { accountsOk(account("a1")) }

        val result = repository.refreshSnapshot() as SnapshotResult.Success

        assertEquals(emptyList<Any>(), result.brokenRules)
        assertEquals("INR", db.mappingRuleDao().getAll().single().currencyCode)
    }

    @Test
    fun `a broken rule's currency is left untouched`() = runTest {
        db.mappingRuleDao().insert(
            MappingRuleEntity(bankName = "HDFC", last4 = "1234", budgetId = "b1", accountId = "gone", currencyCode = "USD"),
        )
        api.budgets = { budgetsOk(budget("b1", "INR")) }
        api.accountsByBudget = { accountsOk(account("a1")) }

        repository.refreshSnapshot()

        // Not updated: a broken rule needs remapping, not a silent currency change.
        assertEquals("USD", db.mappingRuleDao().getAll().single().currencyCode)
    }
```

- [ ] **Step 2: Run the new cases to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.repository.YnabRepositoryTest"`
Expected: FAIL — the constructor now needs 4 args (compile error) and `brokenRules` is still `emptyList()`.

- [ ] **Step 3: Inject `MappingRuleDao` and add re-validation** — edit `YnabRepository.kt`

Add the imports:

```kotlin
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.mapper.toDomain
```

Add `mappingRuleDao` to the constructor:

```kotlin
class YnabRepository @Inject constructor(
    private val api: YnabApi,
    private val snapshotDao: SnapshotDao,
    private val mappingRuleDao: MappingRuleDao,
    private val tokenStore: TokenStore,
) {
```

Replace the final `return` of `refreshSnapshot()` with re-validation:

```kotlin
        snapshotDao.replaceSnapshot(budgets, accounts)

        val brokenRules = revalidateRules()
        return SnapshotResult.Success(budgets.size, accounts.size, brokenRules)
    }

    /**
     * Re-check every rule against the just-persisted snapshot. A rule whose target
     * account no longer exists is broken (returned, not mutated — it needs
     * remapping). A rule that still resolves but whose budget currency changed has
     * its stored currency synced automatically (design spec, Settings).
     */
    private suspend fun revalidateRules(): List<MappingRule> {
        val broken = mutableListOf<MappingRule>()
        for (rule in mappingRuleDao.getAll()) {
            if (!snapshotDao.accountExists(rule.budgetId, rule.accountId)) {
                broken += rule.toDomain()
            } else {
                val currency = snapshotDao.getBudgetCurrency(rule.budgetId)
                if (currency != null && currency != rule.currencyCode) {
                    mappingRuleDao.update(rule.copy(currencyCode = currency))
                }
            }
        }
        return broken
    }
```

- [ ] **Step 4: Run the full repository test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.repository.YnabRepositoryTest"`
Expected: PASS (10 tests — the 6 from Task 4 plus the 4 here).

- [ ] **Step 5: Run the full unit-test suite (no regressions across Plans 1–3)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Confirms the network layer didn't disturb the core/data suites.

- [ ] **Step 6: Final assemble**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/data/repository/YnabRepository.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/repository/YnabRepositoryTest.kt
git commit -m "feat: re-validate rules on snapshot refresh (broken detection + currency sync)"
```

---

## Scope boundary (deferred to later plans)

Recorded so the reviewer doesn't flag these as gaps — they are intentionally **not** in Plan 3:

- **Posting the transactions.** `YnabApi.postTransactions` and its DTOs exist and are tested here, but grouping-by-budget, chunking, the `duplicate_import_ids`→`POSTED` mapping, chunk-`400` linear fallback (ADR-0004), and error classification (429/5xx retry, 400/404 terminal) are the **pipeline (Plan 4)**.
- **`postingPaused` flag + bulk-retry of `FAILED` on token save.** The spec ties these to token save, but the bulk-retry needs the pipeline. `SnapshotResult.Unauthorized` is the signal Plan 4/6 will act on; the flag storage + retry live in **Plan 4**.
- **UI.** The token-entry screen, budget/account pickers reading the snapshot, the broken-rule banner, and manual "refresh" are **Plan 6**. Plan 3 exposes `YnabRepository.saveTokenAndRefresh`/`refreshSnapshot` returning `SnapshotResult` for that UI to call.
- **Real-time receiver / backfill worker** — **Plan 5**.

## Self-review notes

- **Spec coverage (Settings / YNAB endpoints):** `GET /budgets` (validation + currency) → Task 3/4; `GET /budgets/{id}/accounts` (per budget, closed/deleted carried) → Task 1/3/4; `POST /budgets/{id}/transactions` with `transactions` array + `duplicate_import_ids` → Task 1/3; PAT in `EncryptedSharedPreferences`, sent only in `Authorization` → Task 2; snapshot persisted via `replaceSnapshot`, pickable offline → Task 4; broken-rule detection + auto currency update on token save → Task 5; closed/deleted filtered from picker → already in Plan 2's `getOpenAccounts` (unchanged). Single destination / no logging interceptor / no tracker → Task 3 (`NetworkModule`) + Global Constraints.
- **Type consistency:** `YnabApi` signatures identical in Task 3 (def), `FakeYnabApi` (Task 4), `YnabApiTest`. `SnapshotResult` fields (`budgetCount`, `accountCount`, `brokenRules`) consistent Task 4↔5. `YnabRepository` constructor arg order documented as changing in Task 5 (adds `mappingRuleDao`) and the test's `setUp()` is updated in the same task. `BudgetDto.toEntity(): BudgetEntity?` (nullable) consistent between def (Task 4) and its `mapNotNull` call site.
- **Carry-forward closure:** Plan 2's `SnapshotDao` REPLACE footgun is closed in Task 4 Step 1 (KDoc) + enforced by the repository writing only through `replaceSnapshot` (proven by the replace test). Plan 1's remaining deferral (Gradle wrapper `distributionSha256Sum` + CI wrapper-validation) is **still** out of scope — it needs CI, which does not exist yet.
