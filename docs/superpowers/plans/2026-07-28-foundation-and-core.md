# PennyWise → YNAB — Plan 1: Foundation & Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Android project, vendor `parser-core` as a submodule proven callable from the app, and build the framework-free core of the parse→post pipeline — transaction-type classification, the `ParsedTransaction → YNAB SaveTransaction` mapper, and the `(bank, last4)` mapping resolver — all under unit test.

**Architecture:** A single Android app module (`:app`) depends on the KMP `:parser-core` module included from a git submodule of `pennywiseai-tracker`. AGP resolves parser-core's `jvm()` variant (parser-core has no Android target; this is the exact mechanism the tracker's own app uses). Plan 1 adds only pure JVM logic in `:app` — no networking, no Room, no UI beyond a placeholder Activity. Everything here is exercised by fast JVM unit tests (`testDebugUnitTest`); `assembleDebug` additionally proves parser-core dexes into the APK.

**Tech Stack:** Kotlin 2.3.21, AGP 9.2.1, Gradle 9.4.1 (wrapper), Hilt 2.59.2 (bootstrapped, no modules yet), Jetpack Compose (placeholder screen only), kotlinx.serialization 1.11.0 (for the YNAB model), JUnit4 for unit tests. `parser-core` pinned to `pennywiseai-tracker@1347ce50a07641f25237f7b37894025440520f4a`.

## Global Constraints

Copied verbatim from the design spec / ADRs. Every task's requirements implicitly include this section.

- **SDK/toolchain:** `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`, Java **11** bytecode (`sourceCompatibility`/`targetCompatibility` = `VERSION_11`, Kotlin `jvmTarget = JVM_11`), JDK 21 Gradle toolchain (parser-core uses `jvmToolchain(21)`; resolved via the foojay plugin).
- **App identity:** `namespace` = `applicationId` = `com.pennywiseai.ynab`.
- **parser-core is never modified.** It is a pinned git submodule. Updating parsers later = `git submodule update --remote` + review + commit the pointer bump. No local edits to any file under the submodule.
- **`import_id` is content-only (ADR-0001):** `import_id = "PW:" + parsed.generateTransactionId()`. `generateTransactionId()` is an **instance method on `ParsedTransaction`** (not a free function); it hashes `sender | amount(2dp HALF_UP) | first16(md5(smsBody))` → a 32-char MD5 hex, so the id is 35 chars (≤ 36). It deliberately excludes the timestamp so real-time and backfill capture produce the same id.
- **Postable types & sign (ADR-0002):** only `INCOME` (inflow `+`), `EXPENSE` / `CREDIT` / `INVESTMENT` (outflow `−`) post. `TRANSFER` and `BALANCE_UPDATE` are non-postable and must never reach the mapper.
- **Milliunits:** `amount.movePointRight(3).setScale(0, HALF_UP)` as a `Long`, independent of the currency's decimals (YNAB is always ×1000). Sign applied by transaction type.
- **Truncation:** `payee_name` ≤ 50 chars (null/blank merchant → omit); `memo` ≤ 200 chars.
- **Single network destination (future):** the only host the app will ever contact is `api.ynab.com`. No analytics/crash SDKs, ever. (No networking is added in Plan 1, but do not introduce any third-party tracker/telemetry dependency.)

### parser-core ground truth (for reference while implementing)

Package `com.pennywiseai.parser.core` (+ `.bank`). Verified against the pinned commit:

```kotlin
// com.pennywiseai.parser.core.bank.BankParserFactory  (Kotlin object)
object BankParserFactory {
    fun parse(smsBody: String, sender: String, timestamp: Long): ParsedTransaction?  // content-aware: tries all sender-matching parsers, first non-null
    fun getParser(sender: String): BankParser?
    fun isKnownBankSender(sender: String): Boolean
    // ...
}

// com.pennywiseai.parser.core.ParsedTransaction  (data class; uses java.math.BigDecimal)
data class ParsedTransaction(
    val amount: java.math.BigDecimal,
    val type: TransactionType,
    val merchant: String?,
    val reference: String?,
    val accountLast4: String?,
    val balance: java.math.BigDecimal?,
    val creditLimit: java.math.BigDecimal? = null,
    val smsBody: String,
    val sender: String,
    val timestamp: Long,
    val bankName: String,
    val transactionHash: String? = null,
    val isFromCard: Boolean = false,
    val currency: String = "INR",
    val fromAccount: String? = null,
    val toAccount: String? = null,
    val isMobileWallet: Boolean = false,
) {
    fun generateTransactionId(): String  // 32-char lowercase MD5 hex, timestamp-independent
}

// com.pennywiseai.parser.core.TransactionType  (enum)
enum class TransactionType { INCOME, EXPENSE, CREDIT, TRANSFER, INVESTMENT, BALANCE_UPDATE }
```

Note: the six enum values are exactly partitioned by ADR-0002 — 4 postable, 2 non-postable. There is **no** `DEBIT` value. Do not rely on `transactionHash` (null for ~all banks); always use `generateTransactionId()`.

---

## File Structure

Created in this plan:

- `third_party/pennywiseai-tracker/` — git submodule (whole tracker repo, pinned). `:parser-core` points at its `parser-core/` subdir.
- `settings.gradle.kts` — includes `:app` and `:parser-core` (projectDir overridden into the submodule); pluginManagement + foojay + repositories.
- `build.gradle.kts` (root) — plugin declarations (`apply false`).
- `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper (copied from submodule).
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/themes.xml`.
- `app/src/main/kotlin/com/pennywiseai/ynab/PennyWiseYnabApp.kt` — `@HiltAndroidApp` Application.
- `app/src/main/kotlin/com/pennywiseai/ynab/MainActivity.kt` — placeholder Compose screen.
- `app/src/main/kotlin/com/pennywiseai/ynab/core/TransactionTypeExt.kt` — `isPostable()` / `ynabSign()`.
- `app/src/main/kotlin/com/pennywiseai/ynab/core/model/MappingRule.kt` — domain route model.
- `app/src/main/kotlin/com/pennywiseai/ynab/core/model/SaveTransaction.kt` — YNAB request model.
- `app/src/main/kotlin/com/pennywiseai/ynab/core/MappingResolver.kt` — `(bank, last4)` resolution.
- `app/src/main/kotlin/com/pennywiseai/ynab/core/TransactionMapper.kt` — `ParsedTransaction → SaveTransaction`.
- Tests under `app/src/test/kotlin/com/pennywiseai/ynab/...` mirroring the above.

Module note (deviation from the spec's simplified tree): the spec draws `parser-core/` as the submodule directory. In reality the submodule is the **whole** `pennywiseai-tracker` repo (it has no standalone parser-core repo), so it is vendored at `third_party/pennywiseai-tracker/` and `settings.gradle.kts` points the `:parser-core` Gradle project at its `parser-core/` subdirectory. Behaviour is identical to the spec's intent; only the path differs.

---

### Task 1: Project scaffold + `parser-core` submodule (buildable, parser-core dexed & callable)

**Files:**
- Create: `third_party/pennywiseai-tracker/` (submodule), `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper files, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/kotlin/com/pennywiseai/ynab/PennyWiseYnabApp.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/MainActivity.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/ParserCoreIntegrationTest.kt`

**Interfaces:**
- Consumes: `com.pennywiseai.parser.core.bank.BankParserFactory.parse(body, sender, timestamp)`, `com.pennywiseai.parser.core.ParsedTransaction`, `com.pennywiseai.parser.core.TransactionType`.
- Produces: a compiling, installable `:app` with Hilt bootstrapped and `:parser-core` on both the unit-test classpath and the dex output. Later tasks and plans add code under `com.pennywiseai.ynab.*`.

- [ ] **Step 1: Add the parser-core submodule, pinned**

```bash
cd /Users/paul/factly/playground/pennywiseai-ynab
git submodule add https://github.com/sarim2000/pennywiseai-tracker.git third_party/pennywiseai-tracker
git -C third_party/pennywiseai-tracker checkout 1347ce50a07641f25237f7b37894025440520f4a
git submodule update --init --recursive
```

Expected: `.gitmodules` created; `third_party/pennywiseai-tracker/parser-core/build.gradle.kts` exists.

- [ ] **Step 2: Copy the Gradle wrapper from the submodule (guarantees Gradle 9.4.1)**

```bash
cp third_party/pennywiseai-tracker/gradlew third_party/pennywiseai-tracker/gradlew.bat ./
cp -R third_party/pennywiseai-tracker/gradle/wrapper ./gradle/wrapper
chmod +x gradlew
```

Expected: `./gradle/wrapper/gradle-wrapper.properties` points at `gradle-9.4.1-bin.zip`. (`gradle.bat` may not exist in the source; ignore a "No such file" for it.)

- [ ] **Step 3: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.2.1"
kotlin = "2.3.21"
ksp = "2.3.9"
hilt = "2.59.2"
coreKtx = "1.18.0"
lifecycle = "2.10.0"
activityCompose = "1.13.0"
composeBom = "2026.05.01"
material3 = "1.5.0-alpha12"
kotlinxSerializationJson = "1.11.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3", version.ref = "material3" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-android-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 4: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pennywiseai-ynab"
include(":app")
include(":parser-core")
project(":parser-core").projectDir = file("third_party/pennywiseai-tracker/parser-core")
```

- [ ] **Step 5: Create the root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
}
```

The `kotlin.multiplatform` alias (declared `apply false`) makes the Kotlin 2.3.21 KMP plugin available to the submodule's `parser-core/build.gradle.kts`, which applies bare `kotlin("multiplatform")` with no version of its own.

- [ ] **Step 6: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
# The app compiles to JVM 11 bytecode but consumes parser-core (jvmToolchain 21).
# Downgrade the cross-module JVM-target check from error to warning so the
# higher-target project dependency does not fail the app's compilation.
kotlin.jvm.target.validation.mode=warning
```

- [ ] **Step 7: Create `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.pennywiseai.ynab"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pennywiseai.ynab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":parser-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
}
```

`implementation(project(":parser-core"))` with no variant qualifier is exactly how the tracker's own Android app consumes this KMP module — AGP falls back to the `jvm()` variant.

- [ ] **Step 8: Create `app/src/main/res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.PennyWiseYnab" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 9: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".PennyWiseYnabApp"
        android:allowBackup="true"
        android:label="PennyWise → YNAB"
        android:supportsRtl="true"
        android:theme="@style/Theme.PennyWiseYnab">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.PennyWiseYnab">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 10: Create the Application class** `app/src/main/kotlin/com/pennywiseai/ynab/PennyWiseYnabApp.kt`

```kotlin
package com.pennywiseai.ynab

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PennyWiseYnabApp : Application()
```

- [ ] **Step 11: Create the placeholder Activity** `app/src/main/kotlin/com/pennywiseai/ynab/MainActivity.kt`

```kotlin
package com.pennywiseai.ynab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("PennyWise → YNAB — setup pending")
                }
            }
        }
    }
}
```

- [ ] **Step 12: Write the failing integration test** `app/src/test/kotlin/com/pennywiseai/ynab/ParserCoreIntegrationTest.kt`

```kotlin
package com.pennywiseai.ynab

import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.bank.BankParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the pinned parser-core submodule is on the app's classpath and callable
 * via the content-aware factory entry point. The SMS below is a verified case
 * from parser-core's own HDFC test suite (UPI debit).
 */
class ParserCoreIntegrationTest {

    private val hdfcUpiDebit =
        "Rs.500.00 debited from A/c XX1234 on 20-Oct-25 to merchant@upi (UPI Ref No 123456789012)"

    @Test
    fun `factory parses a real HDFC expense SMS`() {
        val parsed = BankParserFactory.parse(hdfcUpiDebit, "CP-HDFCBK-S", 1_690_000_000_000L)

        assertNotNull("HDFC UPI debit should parse", parsed)
        assertEquals(TransactionType.EXPENSE, parsed!!.type)
        assertEquals("1234", parsed.accountLast4)
        assertEquals("123456789012", parsed.reference)
    }

    @Test
    fun `factory returns null for a non-bank message`() {
        val parsed = BankParserFactory.parse("Hey, are we still on for dinner?", "AD-FRIEND", 0L)
        assertNull(parsed)
    }

    @Test
    fun `generateTransactionId is a 32-char hex, stable across timestamps`() {
        val a = BankParserFactory.parse(hdfcUpiDebit, "CP-HDFCBK-S", 111L)!!.generateTransactionId()
        val b = BankParserFactory.parse(hdfcUpiDebit, "CP-HDFCBK-S", 999L)!!.generateTransactionId()

        assertEquals(32, a.length)
        assertEquals(a, b) // ADR-0001: id excludes the timestamp
        assertTrue(a.all { it in "0123456789abcdef" })
    }
}
```

- [ ] **Step 13: Run the unit test — expect it to fail because nothing builds yet, then confirm it passes once the scaffold compiles**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.ParserCoreIntegrationTest"`
Expected: PASS (3 tests). The first run also downloads the Gradle distribution, JDK 21 toolchain (foojay), and dependencies. If it fails to resolve `:parser-core`, re-check Step 4's `projectDir` line and that the submodule is checked out.

- [ ] **Step 14: Prove parser-core dexes into the APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` exists. This step (not the unit test) is what verifies the KMP-JVM → Android dexing path works end to end. Any `kotlin.jvm.target.validation` warning is expected and non-fatal.

- [ ] **Step 15: Add a `.gitignore` and commit**

Create `/.gitignore`:

```gitignore
.gradle/
build/
/local.properties
*.apk
.idea/
.DS_Store
```

```bash
git add .gitignore .gitmodules gradlew gradlew.bat gradle/ settings.gradle.kts build.gradle.kts gradle.properties app/ third_party/pennywiseai-tracker
git commit -m "feat: scaffold Android app + pinned parser-core submodule, verified callable"
```

---

### Task 2: TransactionType classifier (postable + YNAB sign)

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/core/TransactionTypeExt.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/core/TransactionTypeExtTest.kt`

**Interfaces:**
- Consumes: `com.pennywiseai.parser.core.TransactionType`.
- Produces: `fun TransactionType.isPostable(): Boolean`, `fun TransactionType.ynabSign(): Int` (returns `+1` for `INCOME`, `-1` for `EXPENSE`/`CREDIT`/`INVESTMENT`, throws `IllegalArgumentException` for the two non-postable types). Consumed by `TransactionMapper` (Task 4) and, later, the pipeline's skip logic (Plan 3).

- [ ] **Step 1: Write the failing test** `app/src/test/kotlin/com/pennywiseai/ynab/core/TransactionTypeExtTest.kt`

```kotlin
package com.pennywiseai.ynab.core

import com.pennywiseai.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTypeExtTest {

    @Test
    fun `the four postable types are postable`() {
        listOf(
            TransactionType.INCOME,
            TransactionType.EXPENSE,
            TransactionType.CREDIT,
            TransactionType.INVESTMENT,
        ).forEach { assertTrue("$it should be postable", it.isPostable()) }
    }

    @Test
    fun `transfer and balance update are not postable`() {
        assertFalse(TransactionType.TRANSFER.isPostable())
        assertFalse(TransactionType.BALANCE_UPDATE.isPostable())
    }

    @Test
    fun `income is an inflow`() {
        assertEquals(1, TransactionType.INCOME.ynabSign())
    }

    @Test
    fun `expense, credit, investment are outflows`() {
        assertEquals(-1, TransactionType.EXPENSE.ynabSign())
        assertEquals(-1, TransactionType.CREDIT.ynabSign())
        assertEquals(-1, TransactionType.INVESTMENT.ynabSign())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `transfer has no sign`() {
        TransactionType.TRANSFER.ynabSign()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `balance update has no sign`() {
        TransactionType.BALANCE_UPDATE.ynabSign()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.core.TransactionTypeExtTest"`
Expected: FAIL — `isPostable`/`ynabSign` unresolved reference.

- [ ] **Step 3: Write the implementation** `app/src/main/kotlin/com/pennywiseai/ynab/core/TransactionTypeExt.kt`

```kotlin
package com.pennywiseai.ynab.core

import com.pennywiseai.parser.core.TransactionType

/** The four TransactionTypes this app posts to YNAB (ADR-0002). */
fun TransactionType.isPostable(): Boolean = when (this) {
    TransactionType.INCOME,
    TransactionType.EXPENSE,
    TransactionType.CREDIT,
    TransactionType.INVESTMENT -> true
    TransactionType.TRANSFER,
    TransactionType.BALANCE_UPDATE -> false
}

/**
 * YNAB amount sign for a postable type (ADR-0002): INCOME is an inflow (+1);
 * EXPENSE / CREDIT / INVESTMENT are outflows (-1). The two non-postable types
 * are skipped upstream and must never reach this function.
 */
fun TransactionType.ynabSign(): Int = when (this) {
    TransactionType.INCOME -> 1
    TransactionType.EXPENSE,
    TransactionType.CREDIT,
    TransactionType.INVESTMENT -> -1
    TransactionType.TRANSFER,
    TransactionType.BALANCE_UPDATE ->
        throw IllegalArgumentException("Non-postable type $this has no YNAB sign")
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.core.TransactionTypeExtTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/core/TransactionTypeExt.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/core/TransactionTypeExtTest.kt
git commit -m "feat: classify TransactionType postability and YNAB sign (ADR-0002)"
```

---

### Task 3: Mapping resolver — `(bank, last4)` → rule

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/core/model/MappingRule.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/core/MappingResolver.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/core/MappingResolverTest.kt`

**Interfaces:**
- Consumes: nothing from parser-core (operates on the domain model).
- Produces:
  - `data class MappingRule(val bankName: String, val last4: String?, val budgetId: String, val accountId: String, val currencyCode: String)`
  - `class MappingResolver { fun resolve(rules: List<MappingRule>, bankName: String, last4: String?): MappingRule? }`
  - Both consumed by `TransactionMapper` (Task 4) and the pipeline (Plan 3). `MappingRule` becomes the Room entity's domain counterpart in Plan 2.

- [ ] **Step 1: Write the failing test** `app/src/test/kotlin/com/pennywiseai/ynab/core/MappingResolverTest.kt`

```kotlin
package com.pennywiseai.ynab.core

import com.pennywiseai.ynab.core.model.MappingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingResolverTest {

    private val resolver = MappingResolver()
    private val exact = MappingRule("HDFC Bank", "1234", "b1", "a-exact", "INR")
    private val wildcard = MappingRule("HDFC Bank", null, "b1", "a-wild", "INR")
    private val otherBank = MappingRule("ICICI Bank", null, "b2", "a-icici", "INR")

    @Test
    fun `exact last4 wins over the bank wildcard`() {
        val r = resolver.resolve(listOf(wildcard, exact, otherBank), "HDFC Bank", "1234")
        assertEquals("a-exact", r!!.accountId)
    }

    @Test
    fun `falls back to the wildcard when no exact last4 matches`() {
        val r = resolver.resolve(listOf(wildcard, exact), "HDFC Bank", "9999")
        assertEquals("a-wild", r!!.accountId)
    }

    @Test
    fun `a null message last4 matches only the wildcard`() {
        val r = resolver.resolve(listOf(exact, wildcard), "HDFC Bank", null)
        assertEquals("a-wild", r!!.accountId)
    }

    @Test
    fun `no rule for the bank returns null`() {
        assertNull(resolver.resolve(listOf(exact, wildcard), "SBI", "1234"))
    }

    @Test
    fun `null last4 with only exact rules returns null`() {
        assertNull(resolver.resolve(listOf(exact), "HDFC Bank", null))
    }

    @Test
    fun `last4 matches by exact string equality`() {
        // "234" must not match a rule whose last4 is "1234"
        assertNull(resolver.resolve(listOf(exact), "HDFC Bank", "234"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.core.MappingResolverTest"`
Expected: FAIL — `MappingRule` / `MappingResolver` unresolved.

- [ ] **Step 3: Create the domain model** `app/src/main/kotlin/com/pennywiseai/ynab/core/model/MappingRule.kt`

```kotlin
package com.pennywiseai.ynab.core.model

/**
 * A user-defined route: (bankName, last4?) -> (budgetId, accountId).
 * last4 == null is a bank-wide wildcard. currencyCode is the target budget's
 * ISO currency, cached for the offline currency-mismatch guard (used in Plan 3).
 */
data class MappingRule(
    val bankName: String,
    val last4: String?,
    val budgetId: String,
    val accountId: String,
    val currencyCode: String,
)
```

- [ ] **Step 4: Write the resolver** `app/src/main/kotlin/com/pennywiseai/ynab/core/MappingResolver.kt`

```kotlin
package com.pennywiseai.ynab.core

import com.pennywiseai.ynab.core.model.MappingRule

/**
 * Resolves a parsed message's (bankName, last4) to a MappingRule.
 *
 * Precedence: an exact rule (same bank, non-null last4 equal by string to the
 * message's last4) wins over the bank wildcard (last4 == null). No match -> null
 * (the caller logs SKIPPED_UNROUTED). A null message last4 can only match a
 * wildcard.
 */
class MappingResolver {

    fun resolve(rules: List<MappingRule>, bankName: String, last4: String?): MappingRule? {
        val forBank = rules.filter { it.bankName == bankName }
        if (last4 != null) {
            forBank.firstOrNull { it.last4 == last4 }?.let { return it }
        }
        return forBank.firstOrNull { it.last4 == null }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.core.MappingResolverTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/core/model/MappingRule.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/core/MappingResolver.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/core/MappingResolverTest.kt
git commit -m "feat: resolve (bank, last4) routes with exact-over-wildcard precedence"
```

---

### Task 4: Transaction mapper — `ParsedTransaction` → YNAB `SaveTransaction`

**Files:**
- Create: `app/src/main/kotlin/com/pennywiseai/ynab/core/model/SaveTransaction.kt`, `app/src/main/kotlin/com/pennywiseai/ynab/core/TransactionMapper.kt`
- Test: `app/src/test/kotlin/com/pennywiseai/ynab/core/TransactionMapperTest.kt`

**Interfaces:**
- Consumes: `ParsedTransaction`, `MappingRule`, `TransactionType.isPostable()` / `.ynabSign()`.
- Produces:
  - `@Serializable data class SaveTransaction(accountId, date, amount: Long, payeeName: String?, memo: String?, importId, approved: Boolean = true, cleared: String = "cleared")` with `@SerialName` snake_case field names — the YNAB request-body element (Plan 2 wraps it in the `transactions` array for Retrofit).
  - `class TransactionMapper(zoneId: ZoneId = ZoneId.systemDefault()) { fun map(parsed: ParsedTransaction, rule: MappingRule): SaveTransaction }`. Consumed by the pipeline worker (Plan 3).

- [ ] **Step 1: Write the failing test** `app/src/test/kotlin/com/pennywiseai/ynab/core/TransactionMapperTest.kt`

```kotlin
package com.pennywiseai.ynab.core

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.ynab.core.model.MappingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class TransactionMapperTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val mapper = TransactionMapper(zone)
    private val rule = MappingRule("HDFC Bank", "1234", "budget-1", "account-1", "INR")

    private fun parsed(
        amount: BigDecimal,
        type: TransactionType,
        merchant: String? = "Amazon",
        reference: String? = "REF123",
        smsBody: String = "spent Rs 100 at Amazon ref REF123",
        sender: String = "VM-HDFCBK",
        timestamp: Long = 1_690_000_000_000L,
    ) = ParsedTransaction(
        amount = amount,
        type = type,
        merchant = merchant,
        reference = reference,
        accountLast4 = "1234",
        balance = null,
        smsBody = smsBody,
        sender = sender,
        timestamp = timestamp,
        bankName = "HDFC Bank",
    )

    @Test
    fun `expense maps to negative milliunits`() {
        val tx = mapper.map(parsed(BigDecimal("100.00"), TransactionType.EXPENSE), rule)
        assertEquals(-100_000L, tx.amount)
    }

    @Test
    fun `income maps to positive milliunits`() {
        val tx = mapper.map(parsed(BigDecimal("2500.50"), TransactionType.INCOME), rule)
        assertEquals(2_500_500L, tx.amount)
    }

    @Test
    fun `credit and investment are outflows`() {
        assertTrue(mapper.map(parsed(BigDecimal("10"), TransactionType.CREDIT), rule).amount < 0)
        assertTrue(mapper.map(parsed(BigDecimal("10"), TransactionType.INVESTMENT), rule).amount < 0)
    }

    @Test
    fun `milliunit rounding is half-up`() {
        // 100.4565 -> x1000 = 100456.5 -> HALF_UP -> 100457
        val tx = mapper.map(parsed(BigDecimal("100.4565"), TransactionType.EXPENSE), rule)
        assertEquals(-100_457L, tx.amount)
    }

    @Test
    fun `account_id comes from the rule`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE), rule)
        assertEquals("account-1", tx.accountId)
    }

    @Test
    fun `date is yyyy-MM-dd in the given zone`() {
        // 1_690_000_000_000 ms = 2023-07-22T04:26:40Z; in Asia/Kolkata still 2023-07-22
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE), rule)
        assertEquals("2023-07-22", tx.date)
    }

    @Test
    fun `import_id is PW-prefixed and within 36 chars`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE), rule)
        assertTrue(tx.importId.startsWith("PW:"))
        assertEquals(35, tx.importId.length)
        assertTrue(tx.importId.length <= 36)
    }

    @Test
    fun `import_id ignores the timestamp so both capture paths agree`() {
        val a = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, timestamp = 111L), rule)
        val b = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, timestamp = 999L), rule)
        assertEquals(a.importId, b.importId)
    }

    @Test
    fun `payee is truncated to 50 chars`() {
        val long = "X".repeat(80)
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, merchant = long), rule)
        assertEquals(50, tx.payeeName!!.length)
    }

    @Test
    fun `blank merchant omits payee`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, merchant = "   "), rule)
        assertNull(tx.payeeName)
    }

    @Test
    fun `null merchant omits payee`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, merchant = null), rule)
        assertNull(tx.payeeName)
    }

    @Test
    fun `memo is truncated to 200 chars`() {
        val long = "Y".repeat(250)
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, reference = long), rule)
        assertEquals(200, tx.memo!!.length)
    }

    @Test
    fun `approved is true and cleared is cleared`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE), rule)
        assertTrue(tx.approved)
        assertEquals("cleared", tx.cleared)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-postable type is rejected`() {
        mapper.map(parsed(BigDecimal.ZERO, TransactionType.BALANCE_UPDATE), rule)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.core.TransactionMapperTest"`
Expected: FAIL — `SaveTransaction` / `TransactionMapper` unresolved.

- [ ] **Step 3: Create the YNAB request model** `app/src/main/kotlin/com/pennywiseai/ynab/core/model/SaveTransaction.kt`

```kotlin
package com.pennywiseai.ynab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One YNAB SaveTransaction (an element of the POST body's `transactions` array).
 * JSON field names match the YNAB API (snake_case) via @SerialName.
 * `amount` is in milliunits (YNAB is always x1000), signed.
 */
@Serializable
data class SaveTransaction(
    @SerialName("account_id") val accountId: String,
    val date: String, // yyyy-MM-dd
    val amount: Long,
    @SerialName("payee_name") val payeeName: String? = null,
    val memo: String? = null,
    @SerialName("import_id") val importId: String,
    val approved: Boolean = true,
    val cleared: String = "cleared",
)
```

- [ ] **Step 4: Write the mapper** `app/src/main/kotlin/com/pennywiseai/ynab/core/TransactionMapper.kt`

```kotlin
package com.pennywiseai.ynab.core

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.core.model.SaveTransaction
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Maps a routed, postable ParsedTransaction to a YNAB SaveTransaction.
 * `zoneId` controls date derivation — the device zone in production, a fixed
 * zone in tests.
 */
class TransactionMapper(private val zoneId: ZoneId = ZoneId.systemDefault()) {

    fun map(parsed: ParsedTransaction, rule: MappingRule): SaveTransaction {
        require(parsed.type.isPostable()) {
            "Non-postable type ${parsed.type} must be skipped upstream, not mapped"
        }

        val magnitude = parsed.amount
            .movePointRight(3)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
        val amount = abs(magnitude) * parsed.type.ynabSign()

        val date = Instant.ofEpochMilli(parsed.timestamp)
            .atZone(zoneId)
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE) // yyyy-MM-dd

        return SaveTransaction(
            accountId = rule.accountId,
            date = date,
            amount = amount,
            payeeName = parsed.merchant?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PAYEE),
            memo = parsed.reference?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_MEMO),
            importId = IMPORT_ID_PREFIX + parsed.generateTransactionId(),
        )
    }

    companion object {
        const val IMPORT_ID_PREFIX = "PW:"
        const val MAX_PAYEE = 50
        const val MAX_MEMO = 200
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.pennywiseai.ynab.core.TransactionMapperTest"`
Expected: PASS (14 tests).

- [ ] **Step 6: Run the full unit-test suite as a regression check**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all of Tasks 1–4 (integration + classifier + resolver + mapper).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/pennywiseai/ynab/core/model/SaveTransaction.kt \
        app/src/main/kotlin/com/pennywiseai/ynab/core/TransactionMapper.kt \
        app/src/test/kotlin/com/pennywiseai/ynab/core/TransactionMapperTest.kt
git commit -m "feat: map ParsedTransaction to YNAB SaveTransaction (milliunits, sign, import_id, truncation)"
```

---

## What Plan 1 deliberately leaves out (picked up later)

- **Retrofit + OkHttp YNAB client**, budget/account snapshot, the `transactions` array wrapper and `duplicate_import_ids` response, token validation & `EncryptedSharedPreferences` → **Plan 2**.
- **Room** processed-message log + mapping-rule persistence, unrouted-suggestions query → **Plan 2**.
- **Pipeline worker** (parse→route→map→post), currency-mismatch guard, local dedup optimization/upsert, error classification, `postingPaused` → **Plan 3**.
- **Real-time `BroadcastReceiver`** (multipart reassembly + expedited job) and **date-range backfill** (bulk POST, chunk-400 fallback, progress/summary notifications) → **Plan 4**.
- **Onboarding, settings, history UI, notifications** → **Plan 5**.

---

## Self-Review

**Spec coverage (Plan 1 scope only):**
- Project scaffold + Hilt + parser-core submodule, verified callable → Task 1 (unit-test + `assembleDebug` gates). ✓
- `import_id` derivation, length ≤ 36, timestamp-independence (ADR-0001) → Task 4 tests. ✓
- Postable types & YNAB sign (ADR-0002) → Task 2 + Task 4. ✓
- Milliunit HALF_UP math, date formatting, payee/memo truncation → Task 4. ✓
- `(bank, last4)` resolution incl. exact-over-wildcard precedence and no-match (spec "Unit — mapping resolver") → Task 3. ✓
- Out-of-scope-for-Plan-1 items (currency-mismatch guard, POST, Room, UI) are explicitly deferred above — they need subsystems this plan does not build.

**Placeholder scan:** No TBD/TODO; every code and test step carries full content. The one intentional runtime placeholder is `MainActivity`'s "setup pending" screen (real UI is Plan 5) — it exists only so the app assembles/installs.

**Type consistency:** `MappingRule(bankName, last4, budgetId, accountId, currencyCode)` is defined once (Task 3) and consumed unchanged by `TransactionMapper` (Task 4, reads `accountId`). `SaveTransaction` field names (`accountId`, `amount`, `payeeName`, `memo`, `importId`, `approved`, `cleared`) match between the model (Task 4 Step 3), the mapper (Step 4), and the tests (Step 1). `isPostable()`/`ynabSign()` signatures match between Task 2 and their use in Task 4. `BankParserFactory.parse`, `ParsedTransaction` constructor args, and `generateTransactionId()` match the verified parser-core ground truth.
