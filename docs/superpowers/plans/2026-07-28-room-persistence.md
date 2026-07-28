# pennywise-ynab — Plan 2: Room Persistence Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the app's complete local persistence layer in Room — the processed-message log, user mapping rules, and the YNAB budget/account/currency snapshot — with the "unrouted suggestions" query and a data-integrity guarantee that a `(bankName, last4)` route can never be stored twice, all under fast JVM (Robolectric) unit tests.

**Architecture:** One small Room database (`PennyWiseDatabase`) owns four tables: `processed_messages` (the log, keyed by `import_id`), `mapping_rules` (routes), and the two snapshot tables `budgets` + `accounts`. Plan 2 defines the entities, DAOs, type converters, the entity↔domain mapping for rules, the cross-table unrouted-suggestions query, and a Hilt module that provides the database and DAOs as singletons. **No networking and no UI** — this plan produces the persistence surface that Plan 3 (YNAB client + token/snapshot fetch) and later the pipeline/UI consume. Everything is exercised by Robolectric-backed in-memory-Room unit tests running under `testDebugUnitTest` (no emulator).

**Tech Stack:** Kotlin 2.3.21, AGP 9.2.1, Gradle 9.4.1, Room **2.8.4** (`room-runtime` + `room-ktx` + `room-compiler` via KSP 2.3.9), Hilt 2.59.2, kotlinx-coroutines-test 1.10.2, Robolectric 4.15.1, androidx.test:core 1.6.1, JUnit4. Builds directly on Plan 1's scaffold (`:app` + pinned `:parser-core` submodule).

## Global Constraints

Copied verbatim from the design spec / ADRs / Plan 1. Every task's requirements implicitly include this section.

- **SDK/toolchain (unchanged from Plan 1):** `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`, Java **11** bytecode, JDK 21 Gradle toolchain, `kotlin.jvm.target.validation.mode=warning`.
- **App identity:** `namespace` = `applicationId` = `com.pennywiseai.ynab`.
- **parser-core is never modified.** Pinned submodule at `third_party/pennywiseai-tracker` (tag v2.17.1). Plan 2 adds no dependency on it and does not touch it.
- **Single network destination:** the only host the app will ever contact is `api.ynab.com`. **No** analytics/crash SDK, ever. Plan 2 adds no networking at all. Do not introduce any third-party tracker/telemetry dependency.
- **Fixed 5-status set (CONTEXT.md / spec):** `POSTED`, `SKIPPED_UNROUTED`, `SKIPPED_NON_TRANSACTION`, `SKIPPED_CURRENCY_MISMATCH`, `FAILED`. Do not add a sixth status. Un-parseable SMS are never logged (they have no `import_id`).
- **`import_id` is the dedup authority (ADR-0005), Room is a best-effort local optimization.** The log is keyed by `import_id` and a re-processed message **upserts** its row (`SKIPPED_UNROUTED` → `POSTED` on the same PK). Losing a local row never risks a double-post because YNAB rejects a duplicate `import_id`.
- **Route wildcard:** a `MappingRule` with `last4 == null` is a bank-wide wildcard; a specific `last4` takes precedence (resolution logic already lives in Plan 1's `MappingResolver`, unchanged here). **A `(bankName, last4)` pair — including two bank wildcards — must be unrepresentable twice in storage** (Plan 1 carry-forward).
- **Backup hardening (Plan 1 carry-forward):** `android:allowBackup` must be `false` before any real user data lands on disk — the message log is financial metadata. Flip it in this plan.
- **No FX, no categories, no retention/pruning in v1 (YAGNI).**

### Domain types already defined in Plan 1 (consumed, not redefined here)

```kotlin
// com.pennywiseai.ynab.core.model.MappingRule  (domain model; last4 == null is the wildcard)
data class MappingRule(
    val bankName: String,
    val last4: String?,
    val budgetId: String,
    val accountId: String,
    val currencyCode: String,
)

// com.pennywiseai.ynab.core.MappingResolver  (resolves domain MappingRule; exact-over-wildcard)
class MappingResolver {
    fun resolve(rules: List<MappingRule>, bankName: String, last4: String?): MappingRule?
}
```

---

## File Structure

All new code lives under `com.pennywiseai.ynab.data` (the persistence layer), keeping it cleanly separate from Plan 1's `com.pennywiseai.ynab.core` (framework-free domain logic).

**Modify:**
- `gradle/libs.versions.toml` — add Room, coroutines-test, Robolectric, androidx.test:core versions + libraries.
- `app/build.gradle.kts` — add Room deps (+ KSP compiler), test deps, and `testOptions.unitTests.isIncludeAndroidResources = true` (required by Robolectric).
- `app/src/main/AndroidManifest.xml` — `android:allowBackup="false"` (carry-forward).

**Create (main):**
- `data/local/MessageStatus.kt` — the 5-value status enum.
- `data/local/Converters.kt` — Room `@TypeConverter`s (`BigDecimal`↔`String`, `MessageStatus`↔`String`).
- `data/local/entity/ProcessedMessageEntity.kt` — the log row.
- `data/local/entity/MappingRuleEntity.kt` — the route row (unique index on `(bankName, last4)`).
- `data/local/entity/BudgetEntity.kt`, `data/local/entity/AccountEntity.kt` — snapshot rows.
- `data/local/dao/ProcessedMessageDao.kt`, `data/local/dao/MappingRuleDao.kt`, `data/local/dao/SnapshotDao.kt`.
- `data/local/UnroutedSuggestion.kt` — projection for the unrouted-suggestions query.
- `data/local/PennyWiseDatabase.kt` — the `@Database` (built up entity-by-entity across tasks).
- `data/local/DatabaseModule.kt` — Hilt module providing the DB + DAOs.
- `data/mapper/MappingRuleMapping.kt` — `MappingRuleEntity`↔`MappingRule` extensions (`null`↔`""` wildcard).

**Create (test, all under `app/src/test/kotlin/com/pennywiseai/ynab/data/...`):**
- `data/local/ProcessedMessageDaoTest.kt`, `data/local/MappingRuleDaoTest.kt`, `data/local/SnapshotDaoTest.kt`, `data/local/UnroutedSuggestionsTest.kt`.
- `data/mapper/MappingRuleMappingTest.kt` (pure JVM, no Robolectric).

### Room test strategy (applies to every DAO test in this plan)

Room needs a **real** SQLite engine, so the tracker's `isReturnDefaultValues` JVM-stub trick cannot exercise DAOs. Plan 2 runs DAO tests on the JVM via **Robolectric**, which supplies a real SQLite implementation without an emulator:

- Annotate each DAO test class with `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [34])`. Pinning the Robolectric shadow SDK to 34 avoids any bleeding-edge shadow gap at `compileSdk = 37`; Room's runtime does not care which shadow level is used.
- Build an in-memory DB per test: `Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PennyWiseDatabase::class.java).allowMainThreadQueries().build()`, and `db.close()` in `@After`.
- Drive `suspend` DAO functions with `kotlinx.coroutines.test.runTest { ... }`.
- Robolectric downloads its `android-all` runtime jar on first run (network), same as Plan 1's first Gradle/SDK fetch.

> If Robolectric fails to initialize under this exact toolchain (JDK 21 / AGP 9.2.1), the fix is to bump `robolectric` to the latest 4.x in the version catalog — **not** to switch DAO tests to instrumented `androidTest` (that would break the no-emulator, fast-`testDebugUnitTest` workflow Plan 1 established). This surfaces immediately at Task 1 Step 11.

### Schema/versioning note

`PennyWiseDatabase` stays at `version = 1` with `exportSchema = false` for the whole plan. This is pre-release with no shipped database, so entities are **added** to the `@Database` list task-by-task with no migrations (fresh install each dev build). Migrations and schema export are deliberately out of scope until v1 ships.

---

### Task 1: Persistence scaffold + processed-message log

Stands up Room in the build, flips `allowBackup`, creates the shared infrastructure (status enum, converters, database, Hilt module), and delivers the first real table — the processed-message log — proven by a Robolectric DAO test that also validates the whole test harness.

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/MessageStatus.kt`, `.../data/local/Converters.kt`, `.../data/local/entity/ProcessedMessageEntity.kt`, `.../data/local/dao/ProcessedMessageDao.kt`, `.../data/local/PennyWiseDatabase.kt`, `.../data/local/DatabaseModule.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/local/ProcessedMessageDaoTest.kt`

**Interfaces:**
- Consumes: nothing from Plan 1 (persistence layer is independent of the core domain logic).
- Produces:
  - `enum class MessageStatus { POSTED, SKIPPED_UNROUTED, SKIPPED_NON_TRANSACTION, SKIPPED_CURRENCY_MISMATCH, FAILED }`
  - `@Entity ProcessedMessageEntity(importId: String [PK], sender: String, bankName: String, last4: String?, amount: BigDecimal, currency: String, status: MessageStatus, error: String?, timestamp: Long)`
  - `interface ProcessedMessageDao { suspend fun upsert(...); suspend fun getAll(): List<...>; suspend fun getByStatus(status): List<...>; suspend fun getByImportId(importId): ...? }`
  - `abstract class PennyWiseDatabase : RoomDatabase { fun processedMessageDao(): ProcessedMessageDao }`
  - Hilt-provided `PennyWiseDatabase` (`@Singleton`) and `ProcessedMessageDao`. All consumed by the pipeline in a later plan.

- [ ] **Step 1: Add dependencies to the version catalog**

Edit `gradle/libs.versions.toml`. Under `[versions]` add:

```toml
room = "2.8.4"
coroutinesTest = "1.10.2"
robolectric = "4.15.1"
androidxTestCore = "1.6.1"
```

Under `[libraries]` add:

```toml
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutinesTest" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
```

(No `[plugins]` changes — Room uses the KSP plugin already declared in Plan 1.)

- [ ] **Step 2: Wire Room + test deps + Robolectric resources into `app/build.gradle.kts`**

Inside the existing `android { ... }` block, add a `testOptions` block (Robolectric needs merged resources/manifest):

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

In the `dependencies { ... }` block, add the Room deps alongside the existing ones and extend the test deps:

```kotlin
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
```

`room-ktx` brings `kotlinx-coroutines` transitively, so `suspend` DAO functions compile without an explicit coroutines dependency. Room's `@Database` codegen runs via the existing `ksp(...)` pipeline.

- [ ] **Step 3: Flip `allowBackup` off (Plan 1 carry-forward)**

Edit `app/src/main/AndroidManifest.xml`, changing the one attribute:

```xml
        android:allowBackup="false"
```

This lands before any user data hits disk, so cloud/adb backup can never exfiltrate the message log (or, in Plan 3, the YNAB token).

- [ ] **Step 4: Create the status enum** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/MessageStatus.kt`

```kotlin
package com.pennywiseai.ynab.data.local

/**
 * The terminal outcome recorded for a processed message. Fixed 5-value set
 * (CONTEXT.md / design spec) — do not extend. Un-parseable SMS are dropped, not
 * logged, so there is no "unparsed" status.
 */
enum class MessageStatus {
    POSTED,
    SKIPPED_UNROUTED,
    SKIPPED_NON_TRANSACTION,
    SKIPPED_CURRENCY_MISMATCH,
    FAILED,
}
```

- [ ] **Step 5: Create the Room type converters** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/Converters.kt`

```kotlin
package com.pennywiseai.ynab.data.local

import androidx.room.TypeConverter
import java.math.BigDecimal

/**
 * Room converters. Amounts persist as their exact plain-string form (never a
 * lossy double); status persists as its enum name. Applied DB-wide via
 * @TypeConverters on PennyWiseDatabase, and to query parameters too.
 */
class Converters {

    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)

    @TypeConverter
    fun statusToString(value: MessageStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}
```

- [ ] **Step 6: Create the log entity** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/ProcessedMessageEntity.kt`

```kotlin
package com.pennywiseai.ynab.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pennywiseai.ynab.data.local.MessageStatus
import java.math.BigDecimal

/**
 * One processed message — a single SMS the pipeline handled, keyed by import_id
 * and carrying its terminal status. Stores only display + routing fields
 * (never the full parsed transaction): retroactive posting re-derives from the
 * inbox (design spec, Local persistence). `last4` is nullable because a parsed
 * message may carry no account tail.
 */
@Entity(tableName = "processed_messages")
data class ProcessedMessageEntity(
    @PrimaryKey val importId: String,
    val sender: String,
    val bankName: String,
    val last4: String?,
    val amount: BigDecimal,
    val currency: String,
    val status: MessageStatus,
    val error: String? = null,
    val timestamp: Long,
)
```

- [ ] **Step 7: Create the log DAO** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/ProcessedMessageDao.kt`

```kotlin
package com.pennywiseai.ynab.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity

@Dao
interface ProcessedMessageDao {

    /**
     * Upsert by import_id. A re-processed message (e.g. SKIPPED_UNROUTED -> POSTED
     * after a route is added) overwrites its existing row on the same PK (ADR-0005).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ProcessedMessageEntity)

    @Query("SELECT * FROM processed_messages ORDER BY timestamp DESC")
    suspend fun getAll(): List<ProcessedMessageEntity>

    @Query("SELECT * FROM processed_messages WHERE status = :status ORDER BY timestamp DESC")
    suspend fun getByStatus(status: MessageStatus): List<ProcessedMessageEntity>

    @Query("SELECT * FROM processed_messages WHERE importId = :importId")
    suspend fun getByImportId(importId: String): ProcessedMessageEntity?
}
```

- [ ] **Step 8: Create the database** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt`

```kotlin
package com.pennywiseai.ynab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity

/**
 * The app's single local database. Entities are added task-by-task in Plan 2;
 * version stays at 1 (pre-release, no migrations) and schema export is off until
 * v1 ships.
 */
@Database(
    entities = [
        ProcessedMessageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class PennyWiseDatabase : RoomDatabase() {
    abstract fun processedMessageDao(): ProcessedMessageDao
}
```

- [ ] **Step 9: Create the Hilt module** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/DatabaseModule.kt`

```kotlin
package com.pennywiseai.ynab.data.local

import android.content.Context
import androidx.room.Room
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PennyWiseDatabase =
        Room.databaseBuilder(context, PennyWiseDatabase::class.java, "pennywise.db").build()

    @Provides
    fun provideProcessedMessageDao(db: PennyWiseDatabase): ProcessedMessageDao =
        db.processedMessageDao()
}
```

- [ ] **Step 10: Write the log DAO test** `app/src/test/kotlin/com/pennywiseai/ynab/data/local/ProcessedMessageDaoTest.kt`

```kotlin
package com.pennywiseai.ynab.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessedMessageDaoTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var dao: ProcessedMessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.processedMessageDao()
    }

    @After
    fun tearDown() = db.close()

    private fun msg(
        importId: String,
        status: MessageStatus,
        timestamp: Long,
        last4: String? = "1234",
    ) = ProcessedMessageEntity(
        importId = importId,
        sender = "VM-HDFCBK",
        bankName = "HDFC Bank",
        last4 = last4,
        amount = BigDecimal("100.00"),
        currency = "INR",
        status = status,
        error = null,
        timestamp = timestamp,
    )

    @Test
    fun `upsert then read back by import id preserves fields`() = runTest {
        dao.upsert(msg("PW:a", MessageStatus.POSTED, 1L))
        val row = dao.getByImportId("PW:a")
        assertNotNull(row)
        assertEquals(MessageStatus.POSTED, row!!.status)
        assertEquals(BigDecimal("100.00"), row.amount) // BigDecimal survives the converter
    }

    @Test
    fun `upsert replaces the row with the same import id`() = runTest {
        dao.upsert(msg("PW:a", MessageStatus.SKIPPED_UNROUTED, 1L))
        dao.upsert(msg("PW:a", MessageStatus.POSTED, 2L))
        assertEquals(1, dao.getAll().size)
        assertEquals(MessageStatus.POSTED, dao.getByImportId("PW:a")!!.status)
    }

    @Test
    fun `getAll is reverse chronological`() = runTest {
        dao.upsert(msg("PW:old", MessageStatus.POSTED, 1L))
        dao.upsert(msg("PW:new", MessageStatus.POSTED, 5L))
        assertEquals(listOf("PW:new", "PW:old"), dao.getAll().map { it.importId })
    }

    @Test
    fun `getByStatus filters to one status`() = runTest {
        dao.upsert(msg("PW:a", MessageStatus.POSTED, 1L))
        dao.upsert(msg("PW:b", MessageStatus.FAILED, 2L))
        assertEquals(listOf("PW:b"), dao.getByStatus(MessageStatus.FAILED).map { it.importId })
    }

    @Test
    fun `null last4 round-trips`() = runTest {
        dao.upsert(msg("PW:n", MessageStatus.SKIPPED_UNROUTED, 1L, last4 = null))
        assertEquals(null, dao.getByImportId("PW:n")!!.last4)
    }
}
```

- [ ] **Step 11: Run the log DAO test — this also proves the Robolectric harness**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.local.ProcessedMessageDaoTest"`
Expected: PASS (5 tests). The first run downloads Robolectric's `android-all` jar. If it fails to *compile*, re-check Steps 1–2 (catalog + build wiring). If it fails to *initialize Robolectric* (not a test assertion), bump `robolectric` in the catalog to the latest 4.x per the strategy note above — do not switch to `androidTest`.

- [ ] **Step 12: Prove Room codegen dexes into the APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. This confirms Room's KSP-generated `_Impl` classes compile and dex on the Android release path (the unit test only proves the JVM path). A `kotlin.jvm.target.validation` warning is expected and non-fatal.

- [ ] **Step 13: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/MessageStatus.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/Converters.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/ProcessedMessageEntity.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/ProcessedMessageDao.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/DatabaseModule.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/local/ProcessedMessageDaoTest.kt
git commit -m "feat: Room persistence scaffold + processed-message log; disable allowBackup"
```

---

### Task 2: Mapping-rule table with unique-route integrity + entity↔domain mapping

Persists user routes in `mapping_rules` and makes a duplicate `(bankName, last4)` — including duplicate bank wildcards — **impossible to store**, closing the Plan 1 carry-forward. Bridges the storage form (wildcard = `""`) to Plan 1's domain `MappingRule` (wildcard = `null`) so the existing `MappingResolver` keeps working unchanged.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt` (add entity + DAO accessor), `.../data/local/DatabaseModule.kt` (provide the new DAO)
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/MappingRuleEntity.kt`, `.../data/local/dao/MappingRuleDao.kt`, `.../data/mapper/MappingRuleMapping.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMappingTest.kt`, `app/src/test/kotlin/com/pennywiseai/ynab/data/local/MappingRuleDaoTest.kt`

**Interfaces:**
- Consumes: Plan 1's `com.pennywiseai.ynab.core.model.MappingRule` and `com.pennywiseai.ynab.core.MappingResolver`.
- Produces:
  - `@Entity(indices = [Index(["bankName","last4"], unique = true)]) MappingRuleEntity(id: Long [PK, autoGenerate], bankName: String, last4: String /* "" == wildcard */, budgetId: String, accountId: String, currencyCode: String)`
  - `const val WILDCARD_LAST4 = ""`; `fun MappingRuleEntity.toDomain(): MappingRule`; `fun MappingRule.toEntity(id: Long = 0): MappingRuleEntity`
  - `interface MappingRuleDao { suspend fun insert(rule): Long; suspend fun update(rule); suspend fun delete(rule); suspend fun getAll(): List<MappingRuleEntity> }`
  - `PennyWiseDatabase.mappingRuleDao()` + Hilt-provided `MappingRuleDao`.

- [ ] **Step 1: Write the entity↔domain mapping test first** `app/src/test/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMappingTest.kt`

```kotlin
package com.pennywiseai.ynab.data.mapper

import com.pennywiseai.ynab.core.model.MappingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingRuleMappingTest {

    @Test
    fun `wildcard maps null last4 to empty string and back`() {
        val domain = MappingRule("HDFC Bank", null, "b1", "a1", "INR")
        val entity = domain.toEntity()
        assertEquals("", entity.last4)          // stored non-null so the unique index binds
        assertEquals(domain, entity.toDomain()) // "" -> null on the way out
    }

    @Test
    fun `exact last4 survives the round trip`() {
        val domain = MappingRule("HDFC Bank", "1234", "b1", "a1", "INR")
        assertEquals(domain, domain.toEntity().toDomain())
    }

    @Test
    fun `toDomain converts empty string last4 to null`() {
        val entity = MappingRuleEntity(
            id = 7, bankName = "ICICI Bank", last4 = "", budgetId = "b2", accountId = "a2", currencyCode = "INR",
        )
        assertNull(entity.toDomain().last4)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.mapper.MappingRuleMappingTest"`
Expected: FAIL — `MappingRuleEntity`, `toDomain`, `toEntity` unresolved.

- [ ] **Step 3: Create the entity** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/MappingRuleEntity.kt`

```kotlin
package com.pennywiseai.ynab.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted route. `last4` is NON-null here: the empty string "" encodes the
 * bank wildcard. This matters for the UNIQUE(bankName, last4) index — SQLite
 * treats NULLs as DISTINCT, so a nullable unique index would still admit two
 * wildcard rules for one bank. Encoding the wildcard as "" makes any duplicate
 * (bankName, last4) pair — exact OR wildcard — unrepresentable (Plan 1
 * carry-forward). Convert to/from the domain MappingRule (wildcard == null) via
 * toDomain()/toEntity() in data/mapper.
 */
@Entity(
    tableName = "mapping_rules",
    indices = [Index(value = ["bankName", "last4"], unique = true)],
)
data class MappingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankName: String,
    val last4: String,
    val budgetId: String,
    val accountId: String,
    val currencyCode: String,
)
```

- [ ] **Step 4: Create the entity↔domain mapping** `app/src/main/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMapping.kt`

```kotlin
package com.pennywiseai.ynab.data.mapper

import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity

/** Storage sentinel for the bank wildcard (domain uses last4 == null). */
const val WILDCARD_LAST4 = ""

fun MappingRuleEntity.toDomain(): MappingRule = MappingRule(
    bankName = bankName,
    last4 = last4.ifEmpty { null },
    budgetId = budgetId,
    accountId = accountId,
    currencyCode = currencyCode,
)

fun MappingRule.toEntity(id: Long = 0): MappingRuleEntity = MappingRuleEntity(
    id = id,
    bankName = bankName,
    last4 = last4 ?: WILDCARD_LAST4,
    budgetId = budgetId,
    accountId = accountId,
    currencyCode = currencyCode,
)
```

- [ ] **Step 5: Run the mapping test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.mapper.MappingRuleMappingTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Create the DAO** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/MappingRuleDao.kt`

```kotlin
package com.pennywiseai.ynab.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity

@Dao
interface MappingRuleDao {

    /**
     * Insert a route. ABORT (the default) so a duplicate (bankName, last4) raises
     * SQLiteConstraintException rather than silently overwriting — the UI validates
     * before calling, and the crash is a last-line integrity guarantee. Returns the
     * new row id.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: MappingRuleEntity): Long

    @Update
    suspend fun update(rule: MappingRuleEntity)

    @Delete
    suspend fun delete(rule: MappingRuleEntity)

    @Query("SELECT * FROM mapping_rules ORDER BY bankName, last4")
    suspend fun getAll(): List<MappingRuleEntity>
}
```

- [ ] **Step 7: Register the entity + DAO on the database**

Edit `app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt`: add the import, add `MappingRuleEntity::class` to the `entities` list, and add the accessor.

```kotlin
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
```

```kotlin
@Database(
    entities = [
        ProcessedMessageEntity::class,
        MappingRuleEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
```

```kotlin
    abstract fun mappingRuleDao(): MappingRuleDao
```

Then add the Hilt provider in `DatabaseModule.kt`:

```kotlin
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
```

```kotlin
    @Provides
    fun provideMappingRuleDao(db: PennyWiseDatabase): MappingRuleDao =
        db.mappingRuleDao()
```

- [ ] **Step 8: Write the DAO test** `app/src/test/kotlin/com/pennywiseai/ynab/data/local/MappingRuleDaoTest.kt`

```kotlin
package com.pennywiseai.ynab.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.mapper.toDomain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MappingRuleDaoTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var dao: MappingRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.mappingRuleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun exact(last4: String = "1234", accountId: String = "a-exact") =
        MappingRuleEntity(bankName = "HDFC Bank", last4 = last4, budgetId = "b1", accountId = accountId, currencyCode = "INR")

    private fun wildcard(accountId: String = "a-wild") =
        MappingRuleEntity(bankName = "HDFC Bank", last4 = "", budgetId = "b1", accountId = accountId, currencyCode = "INR")

    @Test
    fun `insert then getAll returns the rule`() = runTest {
        dao.insert(exact())
        assertEquals(listOf("a-exact"), dao.getAll().map { it.accountId })
    }

    @Test
    fun `update changes the target account`() = runTest {
        val id = dao.insert(exact())
        dao.update(exact().copy(id = id, accountId = "a-new"))
        assertEquals("a-new", dao.getAll().single().accountId)
    }

    @Test
    fun `delete removes the rule`() = runTest {
        val id = dao.insert(exact())
        dao.delete(exact().copy(id = id))
        assertEquals(0, dao.getAll().size)
    }

    @Test
    fun `duplicate exact route is rejected by the unique index`() = runTest {
        dao.insert(exact())
        try {
            dao.insert(exact(accountId = "a-dup"))
            fail("expected SQLiteConstraintException for duplicate (bankName, last4)")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun `duplicate bank wildcard is rejected by the unique index`() = runTest {
        dao.insert(wildcard())
        try {
            dao.insert(wildcard(accountId = "a-dup"))
            fail("expected SQLiteConstraintException for a second bank wildcard")
        } catch (_: SQLiteConstraintException) {
            // expected — the "" sentinel makes duplicate wildcards representable-proof
        }
    }

    @Test
    fun `an exact rule and a wildcard for the same bank coexist`() = runTest {
        dao.insert(exact())
        dao.insert(wildcard())
        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun `persisted rules feed the Plan 1 resolver with exact-over-wildcard precedence`() = runTest {
        dao.insert(wildcard())
        dao.insert(exact())
        val rules = dao.getAll().map { it.toDomain() } // "" -> null so the resolver's wildcard logic works
        val resolved = MappingResolver().resolve(rules, "HDFC Bank", "1234")
        assertEquals("a-exact", resolved!!.accountId)
    }
}
```

- [ ] **Step 9: Run the DAO test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.local.MappingRuleDaoTest"`
Expected: PASS (7 tests). The two rejection tests are the carry-forward guarantee; the last test proves storage feeds Plan 1's resolver correctly.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/MappingRuleEntity.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/MappingRuleDao.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMapping.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/DatabaseModule.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/mapper/MappingRuleMappingTest.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/local/MappingRuleDaoTest.kt
git commit -m "feat: persist mapping rules with unique (bank, last4) route index (Plan 1 carry-forward)"
```

---

### Task 3: Budget/account snapshot tables

Persists the YNAB budget → account → currency snapshot in Room so rule creation, the currency-mismatch guard, and rule-validation all read locally with zero network (design spec, Settings). Plan 3's YNAB client will populate these tables via `replaceSnapshot`; here we build and test the storage + query surface.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt` (add entities + DAO accessor), `.../data/local/DatabaseModule.kt` (provide the new DAO)
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/BudgetEntity.kt`, `.../data/local/entity/AccountEntity.kt`, `.../data/local/dao/SnapshotDao.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/local/SnapshotDaoTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `@Entity BudgetEntity(id: String [PK], name: String, currencyCode: String)`
  - `@Entity(foreignKeys = [budgetId -> budgets.id, CASCADE], indices = [Index("budgetId")]) AccountEntity(id: String [PK], budgetId: String, name: String, closed: Boolean, deleted: Boolean)`
  - `interface SnapshotDao { suspend fun replaceSnapshot(budgets, accounts); suspend fun getBudgets(): List<BudgetEntity>; suspend fun getOpenAccounts(budgetId): List<AccountEntity>; suspend fun getBudgetCurrency(budgetId): String?; suspend fun accountExists(budgetId, accountId): Boolean }`
  - `PennyWiseDatabase.snapshotDao()` + Hilt-provided `SnapshotDao`.

- [ ] **Step 1: Create the budget entity** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/BudgetEntity.kt`

```kotlin
package com.pennywiseai.ynab.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A YNAB budget in the local snapshot. `id` is the YNAB budget id; currencyCode is its currency_format.iso_code. */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currencyCode: String,
)
```

- [ ] **Step 2: Create the account entity** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/AccountEntity.kt`

```kotlin
package com.pennywiseai.ynab.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A YNAB account in the local snapshot, owned by a budget. The foreign key
 * cascades on delete so clearing budgets clears their accounts in one step
 * (Room enables PRAGMA foreign_keys by default). `closed`/`deleted` mirror YNAB;
 * closed/deleted accounts are filtered out of the picker.
 */
@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = BudgetEntity::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("budgetId")],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val budgetId: String,
    val name: String,
    val closed: Boolean,
    val deleted: Boolean,
)
```

- [ ] **Step 3: Create the snapshot DAO** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt`

```kotlin
package com.pennywiseai.ynab.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity

@Dao
interface SnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgets(budgets: List<BudgetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<AccountEntity>)

    /** Deleting budgets cascades to their accounts (FK ON DELETE CASCADE). */
    @Query("DELETE FROM budgets")
    suspend fun clearBudgets()

    /** Atomically replace the whole snapshot tree — a token save / manual refresh re-pulls it. */
    @Transaction
    suspend fun replaceSnapshot(budgets: List<BudgetEntity>, accounts: List<AccountEntity>) {
        clearBudgets()
        insertBudgets(budgets)
        insertAccounts(accounts)
    }

    @Query("SELECT * FROM budgets ORDER BY name")
    suspend fun getBudgets(): List<BudgetEntity>

    @Query("SELECT * FROM accounts WHERE budgetId = :budgetId AND closed = 0 AND deleted = 0 ORDER BY name")
    suspend fun getOpenAccounts(budgetId: String): List<AccountEntity>

    @Query("SELECT currencyCode FROM budgets WHERE id = :budgetId")
    suspend fun getBudgetCurrency(budgetId: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM accounts WHERE id = :accountId AND budgetId = :budgetId)")
    suspend fun accountExists(budgetId: String, accountId: String): Boolean
}
```

- [ ] **Step 4: Register the entities + DAO on the database**

Edit `PennyWiseDatabase.kt`: add imports, both entities, and the accessor.

```kotlin
import com.pennywiseai.ynab.data.local.dao.SnapshotDao
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
```

```kotlin
@Database(
    entities = [
        ProcessedMessageEntity::class,
        MappingRuleEntity::class,
        BudgetEntity::class,
        AccountEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
```

```kotlin
    abstract fun snapshotDao(): SnapshotDao
```

Then add the Hilt provider in `DatabaseModule.kt`:

```kotlin
import com.pennywiseai.ynab.data.local.dao.SnapshotDao
```

```kotlin
    @Provides
    fun provideSnapshotDao(db: PennyWiseDatabase): SnapshotDao =
        db.snapshotDao()
```

- [ ] **Step 5: Write the snapshot DAO test** `app/src/test/kotlin/com/pennywiseai/ynab/data/local/SnapshotDaoTest.kt`

```kotlin
package com.pennywiseai.ynab.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.dao.SnapshotDao
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotDaoTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var dao: SnapshotDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.snapshotDao()
    }

    @After
    fun tearDown() = db.close()

    private val usd = BudgetEntity("bud-usd", "Personal (USD)", "USD")
    private val inr = BudgetEntity("bud-inr", "Family (INR)", "INR")
    private fun acct(id: String, budgetId: String, closed: Boolean = false, deleted: Boolean = false) =
        AccountEntity(id = id, budgetId = budgetId, name = "Acct $id", closed = closed, deleted = deleted)

    @Test
    fun `replaceSnapshot populates budgets and accounts`() = runTest {
        dao.replaceSnapshot(
            budgets = listOf(usd, inr),
            accounts = listOf(acct("a1", "bud-usd"), acct("a2", "bud-inr")),
        )
        assertEquals(listOf("Family (INR)", "Personal (USD)"), dao.getBudgets().map { it.name }) // sorted by name
        assertEquals(listOf("a1"), dao.getOpenAccounts("bud-usd").map { it.id })
    }

    @Test
    fun `getOpenAccounts excludes closed and deleted`() = runTest {
        dao.replaceSnapshot(
            budgets = listOf(usd),
            accounts = listOf(
                acct("open", "bud-usd"),
                acct("closed", "bud-usd", closed = true),
                acct("deleted", "bud-usd", deleted = true),
            ),
        )
        assertEquals(listOf("open"), dao.getOpenAccounts("bud-usd").map { it.id })
    }

    @Test
    fun `getBudgetCurrency returns the iso code, or null when unknown`() = runTest {
        dao.replaceSnapshot(listOf(usd), listOf(acct("a1", "bud-usd")))
        assertEquals("USD", dao.getBudgetCurrency("bud-usd"))
        assertEquals(null, dao.getBudgetCurrency("bud-missing"))
    }

    @Test
    fun `accountExists is true only for a matching budget and account`() = runTest {
        dao.replaceSnapshot(listOf(usd, inr), listOf(acct("a1", "bud-usd")))
        assertTrue(dao.accountExists("bud-usd", "a1"))
        assertFalse(dao.accountExists("bud-inr", "a1"))   // right account id, wrong budget
        assertFalse(dao.accountExists("bud-usd", "gone")) // unknown account
    }

    @Test
    fun `replaceSnapshot cascade-clears the previous tree`() = runTest {
        dao.replaceSnapshot(listOf(usd), listOf(acct("old", "bud-usd")))
        dao.replaceSnapshot(listOf(inr), listOf(acct("new", "bud-inr")))
        assertEquals(listOf("bud-inr"), dao.getBudgets().map { it.id })
        assertEquals(emptyList<String>(), dao.getOpenAccounts("bud-usd").map { it.id }) // old accounts cascaded out
        assertEquals(listOf("new"), dao.getOpenAccounts("bud-inr").map { it.id })
    }
}
```

- [ ] **Step 6: Run the snapshot DAO test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.local.SnapshotDaoTest"`
Expected: PASS (5 tests). The cascade test confirms Room's FK enforcement is on.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/BudgetEntity.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/entity/AccountEntity.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/SnapshotDao.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/PennyWiseDatabase.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/DatabaseModule.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/local/SnapshotDaoTest.kt
git commit -m "feat: persist YNAB budget/account snapshot with picker + validation queries"
```

---

### Task 4: Unrouted-suggestions query

The last piece of the persistence surface: the cross-table query that powers settings' one-tap "map this route" suggestions — distinct `(bankName, last4)` combos from `SKIPPED_UNROUTED` log rows that **no** existing rule would route. This needs both the `processed_messages` and `mapping_rules` tables, so it comes after Tasks 1–2.

**Files:**
- Modify: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/ProcessedMessageDao.kt` (add the query)
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/data/local/UnroutedSuggestion.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/data/local/UnroutedSuggestionsTest.kt`

**Interfaces:**
- Consumes: `ProcessedMessageEntity` (Task 1), `MappingRuleEntity` (Task 2).
- Produces:
  - `data class UnroutedSuggestion(val bankName: String, val last4: String?)`
  - `ProcessedMessageDao.getUnroutedSuggestions(status: MessageStatus): List<UnroutedSuggestion>`

- [ ] **Step 1: Write the failing test** `app/src/test/kotlin/com/pennywiseai/ynab/data/local/UnroutedSuggestionsTest.kt`

```kotlin
package com.pennywiseai.ynab.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnroutedSuggestionsTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var messages: ProcessedMessageDao
    private lateinit var rules: MappingRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PennyWiseDatabase::class.java,
        ).allowMainThreadQueries().build()
        messages = db.processedMessageDao()
        rules = db.mappingRuleDao()
    }

    @After
    fun tearDown() = db.close()

    private var seq = 0L
    private suspend fun logged(bank: String, last4: String?, status: MessageStatus) {
        messages.upsert(
            ProcessedMessageEntity(
                importId = "PW:${seq++}",
                sender = "S", bankName = bank, last4 = last4,
                amount = BigDecimal("1.00"), currency = "INR",
                status = status, error = null, timestamp = seq,
            ),
        )
    }

    private suspend fun unrouted() =
        messages.getUnroutedSuggestions(MessageStatus.SKIPPED_UNROUTED)

    @Test
    fun `an unrouted combo with no covering rule is suggested`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        assertEquals(listOf(UnroutedSuggestion("HDFC Bank", "1234")), unrouted())
    }

    @Test
    fun `a combo covered by an exact rule is not suggested`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        rules.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "1234", budgetId = "b", accountId = "a", currencyCode = "INR"))
        assertEquals(emptyList<UnroutedSuggestion>(), unrouted())
    }

    @Test
    fun `a combo covered by a bank wildcard is not suggested`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        rules.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "", budgetId = "b", accountId = "a", currencyCode = "INR"))
        assertEquals(emptyList<UnroutedSuggestion>(), unrouted())
    }

    @Test
    fun `a null-last4 message is only covered by a wildcard`() = runTest {
        logged("HDFC Bank", null, MessageStatus.SKIPPED_UNROUTED)
        rules.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "1234", budgetId = "b", accountId = "a", currencyCode = "INR"))
        // exact rule does not cover a null-last4 message -> still suggested
        assertEquals(listOf(UnroutedSuggestion("HDFC Bank", null)), unrouted())
        rules.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "", budgetId = "b2", accountId = "a2", currencyCode = "INR"))
        // adding the wildcard now covers it
        assertEquals(emptyList<UnroutedSuggestion>(), unrouted())
    }

    @Test
    fun `non-unrouted statuses are ignored`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.POSTED)
        logged("HDFC Bank", "5678", MessageStatus.FAILED)
        assertEquals(emptyList<UnroutedSuggestion>(), unrouted())
    }

    @Test
    fun `duplicate unrouted rows collapse to one suggestion`() = runTest {
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        logged("HDFC Bank", "1234", MessageStatus.SKIPPED_UNROUTED)
        assertEquals(1, unrouted().size)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.local.UnroutedSuggestionsTest"`
Expected: FAIL — `UnroutedSuggestion` and `getUnroutedSuggestions` unresolved.

- [ ] **Step 3: Create the projection** `app/src/main/kotlin/com/pennywiseai/ynab/data/local/UnroutedSuggestion.kt`

```kotlin
package com.pennywiseai.ynab.data.local

/**
 * A distinct (bankName, last4) combo seen in SKIPPED_UNROUTED log rows with no
 * covering rule — offered in settings as a one-tap route to create. last4 is
 * nullable (a message may carry no account tail).
 */
data class UnroutedSuggestion(
    val bankName: String,
    val last4: String?,
)
```

- [ ] **Step 4: Add the query to `ProcessedMessageDao`**

Edit `app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/ProcessedMessageDao.kt`: add the import and the method.

```kotlin
import com.pennywiseai.ynab.data.local.UnroutedSuggestion
```

```kotlin
    /**
     * Distinct (bankName, last4) combos from unrouted log rows that no rule would
     * route. A rule covers a row when it is for the same bank AND its last4 either
     * equals the row's last4 or is the wildcard "". A null message last4 can only
     * be covered by the wildcard (the `r.last4 = p.last4` comparison is NULL/unknown
     * for it, so only `r.last4 = ''` matches). Callers pass MessageStatus.SKIPPED_UNROUTED.
     */
    @Query(
        """
        SELECT DISTINCT p.bankName AS bankName, p.last4 AS last4
        FROM processed_messages p
        WHERE p.status = :status
          AND NOT EXISTS (
            SELECT 1 FROM mapping_rules r
            WHERE r.bankName = p.bankName
              AND (r.last4 = p.last4 OR r.last4 = '')
          )
        ORDER BY p.bankName, p.last4
        """,
    )
    suspend fun getUnroutedSuggestions(status: MessageStatus): List<UnroutedSuggestion>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.data.local.UnroutedSuggestionsTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Run the full unit-test suite as a regression check**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — Plan 1's core tests (integration + classifier + resolver + mapper) plus all of Plan 2's persistence tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/data/local/UnroutedSuggestion.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/data/local/dao/ProcessedMessageDao.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/data/local/UnroutedSuggestionsTest.kt
git commit -m "feat: query distinct unrouted (bank, last4) suggestions with no covering rule"
```

---

## What Plan 2 deliberately leaves out (picked up later)

- **Retrofit + OkHttp YNAB client**, YNAB request/response models, token storage in `EncryptedSharedPreferences` + validation, and the budget/account **snapshot fetch** that calls `SnapshotDao.replaceSnapshot(...)` → **Plan 3** (the other half of Plan 1's original "Plan 2"; renumbers the roadmap by one).
- **Pipeline worker** (parse→route→map→post) that writes `ProcessedMessageEntity` rows and reads rules via `MappingRuleDao` + `MappingResolver`, the currency-mismatch guard reading `SnapshotDao.getBudgetCurrency`, rule-validation (broken-rule detection) via `SnapshotDao.accountExists`, error classification, `postingPaused` → **Plan 4**.
- **Real-time `BroadcastReceiver`** and **date-range backfill** → **Plan 5**.
- **Onboarding, settings, history UI** (which consume `getUnroutedSuggestions`, `getOpenAccounts`, `getAll`/`getByStatus`) and **notifications** → **Plan 6**. Reactive `Flow` variants of the read queries are deferred to here (YAGNI until a UI observes them).
- **Room schema export + migrations**: kept off (`exportSchema = false`, `version = 1`) until v1 ships; the first post-release schema change adds export + a migration + a migration test.
- **Gradle wrapper `distributionSha256Sum` + wrapper-validation CI** (Plan 1 carry-forward, "optional hardening"): still deferred — it needs CI, which does not exist yet.

---

## Self-Review

**Spec coverage (Plan 2 scope = Room persistence, per the split decision):**
- Processed-message log table with the fixed 5-status set, `import_id` PK, upsert semantics (ADR-0005), reverse-chron + by-status reads → Task 1. ✓
- Mapping-rule persistence in Room → Task 2. ✓
- Plan 1 carry-forward — **unique index on `(bankName, last4)`** made effective against duplicate wildcards via the `""` sentinel (SQLite NULL-distinct pitfall handled) → Task 2 (two rejection tests). ✓
- Plan 1 carry-forward — **`allowBackup="false"`** before user data lands → Task 1 Step 3. ✓
- Budget/account/currency snapshot in Room, picker filtering closed/deleted, currency lookup, and existence checks for rule-validation → Task 3. ✓
- "Unrouted suggestions" as plain SQL (distinct `(bank, last4)` from `SKIPPED_UNROUTED` with no covering rule) → Task 4. ✓
- Snapshot storage location (open design question) resolved to **Room tables** → Tasks 3–4. ✓
- Deferred items (YNAB client, token/`EncryptedSharedPreferences`, pipeline, UI, receiver/backfill, migrations, wrapper-checksum CI) explicitly listed above — they need subsystems this plan does not build. ✓

**Placeholder scan:** No TBD/TODO. Every code and test step carries full content. No "add error handling"/"write tests"-style stubs.

**Type consistency:**
- `MessageStatus` (5 values) defined once (Task 1) and used by `ProcessedMessageEntity`, `ProcessedMessageDao.getByStatus`/`getUnroutedSuggestions`, and every test.
- `ProcessedMessageEntity` field set (`importId, sender, bankName, last4, amount, currency, status, error, timestamp`) is identical across the entity (Task 1 Step 6), the DAO test factory (Task 1 Step 10), and the Task 4 test helper.
- `MappingRuleEntity(id, bankName, last4, budgetId, accountId, currencyCode)` defined once (Task 2) and referenced identically in `MappingRuleDao`, both Task 2 tests, and the Task 4 test.
- `MappingRule` (domain, Plan 1) ↔ `MappingRuleEntity` bridge: `toEntity()` maps `null → ""`, `toDomain()` maps `"" → null` (Task 2 Step 4), verified by `MappingRuleMappingTest` and exercised end-to-end against Plan 1's `MappingResolver` (Task 2 Step 8, last test).
- `BudgetEntity(id, name, currencyCode)` / `AccountEntity(id, budgetId, name, closed, deleted)` defined once (Task 3) and used identically in `SnapshotDao` + `SnapshotDaoTest`.
- `PennyWiseDatabase` accessors (`processedMessageDao`, `mappingRuleDao`, `snapshotDao`) match the Hilt providers in `DatabaseModule` and every test's `db.xDao()` call. The `@Database` `entities` list grows across Tasks 1→2→3 to the final four; `version` stays 1 with `exportSchema = false` throughout.
- `getUnroutedSuggestions(status: MessageStatus): List<UnroutedSuggestion>` signature (Task 4) matches its test call `getUnroutedSuggestions(MessageStatus.SKIPPED_UNROUTED)`, and `UnroutedSuggestion(bankName, last4)` column aliases (`AS bankName`, `AS last4`) match the projection's property names.

**Test-harness risk (called out, not hidden):** DAO tests depend on Robolectric initializing under JDK 21 / AGP 9.2.1. This is validated end-to-end at Task 1 Step 11, with an explicit version-bump remedy in the Room test-strategy note. No other task can go green until that harness works, so the risk surfaces at the earliest possible point.
