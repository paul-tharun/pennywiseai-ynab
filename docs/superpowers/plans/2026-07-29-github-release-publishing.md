# GitHub Release Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tag-driven publishing of signed release APKs to GitHub Releases, consumable by Obtainium for auto-updates.

**Architecture:** A conditional Gradle `signingConfig` activates only when `SIGNING_*` env vars are present (local unsigned builds untouched). A single GitHub Actions workflow triggers on `v*` tags, guards tag-vs-versionName, runs unit tests as the release gate, builds the signed APK, and attaches it to a GitHub Release. No push/PR CI workflow exists or is added.

**Tech Stack:** Gradle Kotlin DSL (AGP signingConfigs), GitHub Actions (`actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`, `softprops/action-gh-release`).

**Spec:** `docs/superpowers/specs/2026-07-29-github-release-publishing-design.md`

## Global Constraints

- Env var names (exact): `SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
- GitHub secret names (exact): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Workflow trigger: `on: push: tags: ['v*']` — release workflow only, no push/PR CI workflow.
- Attached APK name: `pennywiseai-ynab-<version>.apk` (version without the `v` prefix).
- Tag guard: pushed tag must equal `v` + `versionName` from `app/build.gradle.kts`, else fail with an explicit message.
- JDK 21 (Temurin) in CI — the unit-test task requires a JDK 21 launcher for parser-core's bytecode.
- Checkout with `submodules: true` — `third_party/pennywiseai-tracker` is a public HTTPS submodule, no auth needed.
- Minify stays off. R8/keep-rules are explicitly out of scope.
- Test/build command in CI: `./gradlew test :app:assembleRelease` (tests gate the release).
- When signing vars are absent, `assembleRelease` must keep producing `app-release-unsigned.apk` with zero setup.
- No new app unit tests — the Gradle signing block is configuration, exercised by build invocations and the workflow run itself.

---

### Task 1: Conditional release signing in Gradle

**Files:**
- Modify: `app/build.gradle.kts:21-25` (the `buildTypes` block; add a `signingConfigs` block just before it)
- Modify: `.gitignore` (add `*.jks` so a real keystore can never be committed)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: a release build that reads the four `SIGNING_*` env vars at configuration time. With all vars set → signed `app/build/outputs/apk/release/app-release.apk`. Without them → unsigned `app/build/outputs/apk/release/app-release-unsigned.apk`. Task 2's workflow exports exactly these vars.

There is no unit test for this task (Global Constraints: signing is configuration). The test cycle is three build invocations: unsigned build still works, signed build produces a verifiable signature, and the keystore can't be committed.

- [ ] **Step 1: Add the conditional signing config**

In `app/build.gradle.kts`, replace the current `buildTypes` block:

```kotlin
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
```

with:

```kotlin
    // Release signing is driven entirely by environment variables so CI can sign
    // without any checked-in secrets. When SIGNING_KEYSTORE_PATH is unset (every
    // local build by default), assembleRelease produces an unsigned APK.
    val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

    signingConfigs {
        if (signingKeystorePath != null) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
```

Ordering matters: the `signingConfigs` block must appear textually **before** `buildTypes` in the file — Kotlin DSL blocks execute top-to-bottom, and `getByName("release")` fails if the config hasn't been created yet. Note `file(...)` resolves relative paths against `app/`, so callers must pass an absolute path (Task 2's workflow uses `${{ runner.temp }}`, which is absolute).

- [ ] **Step 2: Verify the unsigned path (no env vars)**

Run from the repo root:

```bash
./gradlew :app:assembleRelease
ls app/build/outputs/apk/release/
```

Expected: BUILD SUCCESSFUL; the listing contains `app-release-unsigned.apk`. This proves local builds need no setup.

- [ ] **Step 3: Verify the signed path with a throwaway keystore**

Generate a disposable keystore, build with the four env vars set, and verify the signature with `apksigner` (part of the Android SDK build-tools; not on PATH by default):

```bash
KS_DIR=$(mktemp -d)
keytool -genkeypair -v -keystore "$KS_DIR/test.jks" -alias release \
  -keyalg RSA -keysize 2048 -validity 30 \
  -storepass testpass -keypass testpass -dname "CN=throwaway-test"

SIGNING_KEYSTORE_PATH="$KS_DIR/test.jks" \
SIGNING_KEYSTORE_PASSWORD=testpass \
SIGNING_KEY_ALIAS=release \
SIGNING_KEY_PASSWORD=testpass \
./gradlew :app:assembleRelease

APKSIGNER=$(ls -d "$HOME/Library/Android/sdk/build-tools/"*/apksigner | tail -1)
"$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk

rm -rf "$KS_DIR" app/build/outputs/apk/release/app-release.apk
```

Expected: BUILD SUCCESSFUL, then `apksigner` prints `Signer #1 certificate DN: CN=throwaway-test` and exits 0. The `rm` at the end deletes the throwaway keystore and the throwaway-signed APK so no stale artifact lingers.

- [ ] **Step 4: Ignore keystore files**

Append to `.gitignore` (the file already ignores `*.apk`):

```
*.jks
```

Verify: `git check-ignore -v pennywiseai-ynab-release.jks` prints the `.gitignore` rule (exit 0). The real release keystore lives outside the repo, but this guards against a careless copy into the tree.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts .gitignore
git commit -m "feat: env-var-driven release signing config (unsigned builds unaffected)"
```

---

### Task 2: Release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: the `SIGNING_KEYSTORE_PATH` / `SIGNING_KEYSTORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` env-var contract from Task 1, which makes `./gradlew :app:assembleRelease` emit a signed `app/build/outputs/apk/release/app-release.apk`.
- Produces: on push of a `v*` tag, a GitHub Release for that tag with `pennywiseai-ynab-<version>.apk` attached. Reads repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (created by the user in Task 3).

There is no unit test for a workflow file; the cycle is: write it, statically validate the YAML and the version-extraction command locally, then Task 3 validates it end-to-end with a real tag push.

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags: ['v*']

permissions:
  contents: write  # create the Release and upload the APK asset

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
        with:
          submodules: true

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      - name: Decode release keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
        run: echo "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.jks"

      - name: Guard tag matches versionName
        run: |
          VERSION=$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
          if [ -z "$VERSION" ]; then
            echo "Could not extract versionName from app/build.gradle.kts" >&2
            exit 1
          fi
          if [ "$GITHUB_REF_NAME" != "v$VERSION" ]; then
            echo "Tag $GITHUB_REF_NAME does not match versionName v$VERSION in app/build.gradle.kts." >&2
            echo "Delete the tag, bump versionCode/versionName, commit, and re-tag." >&2
            exit 1
          fi

      - name: Test and build signed APK
        env:
          SIGNING_KEYSTORE_PATH: ${{ runner.temp }}/release.jks
          SIGNING_KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          SIGNING_KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew test :app:assembleRelease

      - name: Rename APK
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          cp app/build/outputs/apk/release/app-release.apk "pennywiseai-ynab-$VERSION.apk"

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: pennywiseai-ynab-*.apk
```

Notes on intent (matches spec step order — decode keystore before the guard):
- Secrets are passed via `env:` rather than interpolated into `run:` scripts, so they never appear in shell command lines.
- `gradle/actions/setup-gradle@v4` provides build caching and validates the Gradle wrapper checksum by default.
- The guard's `sed` prints only lines matching `versionName = "..."`, stripping everything but the quoted value.
- If tests fail, `./gradlew test :app:assembleRelease` exits non-zero before/without producing a release; the Release step never runs. Missing/wrong secrets fail at keystore decode (empty file) or Gradle signing. No Release is created in either case.

- [ ] **Step 2: Validate the YAML parses**

macOS ships Ruby with a YAML parser; no extra tooling needed:

```bash
ruby -ryaml -e 'YAML.load_file(".github/workflows/release.yml"); puts "YAML OK"'
```

Expected: prints `YAML OK`. (If `actionlint` happens to be installed, `actionlint .github/workflows/release.yml` is a stronger check — optional.)

- [ ] **Step 3: Validate the guard's version extraction against the real build file**

Run the exact `sed` command from the workflow locally:

```bash
sed -n 's/^[[:space:]]*versionName = "\(.*\)"$/\1/p' app/build.gradle.kts
```

Expected output: exactly `0.1.0` (one line, nothing else). If the output is empty or has extra lines, fix the pattern or the build file — this command is the guard's single point of failure.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "feat: tag-driven release workflow (test-gated, signed APK to GitHub Releases)"
```

---

### Task 3: One-time setup and end-to-end release validation (manual, user)

**Files:** none in-repo — this task is operator work plus one live workflow run.

**Interfaces:**
- Consumes: Task 1's signing contract, Task 2's workflow, and the four repository secrets it reads.
- Produces: a green `v0.1.0` release run proving the whole chain; the durable keystore all future releases must be signed with.

These steps are performed by the user (they involve the real keystore and the GitHub repo). The executor's job for this task is to present the checklist and verify the outcome afterward, not to run the `keytool`/secret steps themselves.

- [ ] **Step 1: Create the GitHub repository and push `main`**

The `parser-core` submodule (`third_party/pennywiseai-tracker`) is public HTTPS pinned at v2.17.1 — CI checks it out with `submodules: true`, no auth setup needed.

- [ ] **Step 2: Generate the release keystore locally and back it up**

```bash
keytool -genkeypair -v -keystore pennywiseai-ynab-release.jks -alias release \
  -keyalg RSA -keysize 4096 -validity 10000
```

Back the file up durably **outside the repo**. It can never be rotated without breaking Obtainium updates — Obtainium only installs updates over a same-signature APK.

- [ ] **Step 3: Add the four repository secrets**

In GitHub → repo → Settings → Secrets and variables → Actions:

- `KEYSTORE_BASE64` — output of `base64 < pennywiseai-ynab-release.jks`
- `KEYSTORE_PASSWORD` — the keystore password
- `KEY_ALIAS` — `release` (the alias used in Step 2)
- `KEY_PASSWORD` — the key password

- [ ] **Step 4: Tag and push the first release**

`versionCode = 1` / `versionName = "0.1.0"` are already correct in `app/build.gradle.kts` for the first release — no bump needed:

```bash
git tag v0.1.0
git push origin main --tags
```

- [ ] **Step 5: Confirm the release chain end-to-end**

Per the spec's Testing section, confirm all four:

1. The workflow run is green.
2. A `v0.1.0` Release exists with `pennywiseai-ynab-0.1.0.apk` attached.
3. The APK downloads and installs on-device.
4. Obtainium (configured on the phone with the repo URL) detects the release.

If the run is red: a tag/versionName mismatch means delete the tag, fix the version, re-tag; a keystore/secret failure means fix the secret values — no Release is created on any failure, so re-pushing the tag after a fix is safe.

---

## Release ritual (recorded for future releases)

1. Bump `versionCode` (monotonic int) and `versionName` in `app/build.gradle.kts`; commit.
2. `git tag v<versionName> && git push origin main --tags`.
3. The workflow builds, tests, signs, and publishes; Obtainium picks up the new release.
