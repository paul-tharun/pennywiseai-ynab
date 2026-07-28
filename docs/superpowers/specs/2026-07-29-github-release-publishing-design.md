# GitHub Release Publishing — Design

Date: 2026-07-29
Status: Approved

## Goal

Tag-driven publishing of signed release APKs to GitHub Releases, consumable by
Obtainium on the user's phone for auto-updates. No Play Store (SMS permissions
make a listing impractical), no separate CI workflow — the release workflow is
the only automation and it runs the unit tests as its gate.

## Decisions (settled with user)

- **Publish target**: GitHub Releases, Obtainium-style — signed APK attached to
  a Release per version tag.
- **Signing**: brand-new release keystore, generated locally by the user, kept
  backed up outside the repo. Fed to CI as base64 in GitHub Actions secrets.
  Same key forever — Obtainium updates only install over a same-signature APK.
- **Trigger**: pushing a git tag matching `v*` (e.g. `v0.1.0`).
- **Scope**: release workflow only; no push/PR CI workflow.
- **Signing mechanism**: Gradle `signingConfig` read from environment
  variables, active only when the vars are present (approach chosen over
  post-build `apksigner` and over fastlane). Local unsigned builds unaffected;
  a signed build is reproducible locally by exporting the same vars.

## One-time setup (manual, user)

1. Create the GitHub repository; push `main`. The `parser-core` submodule
   (`third_party/pennywiseai-tracker`, public HTTPS, pinned v2.17.1) needs no
   auth — CI checks it out with `submodules: true`.
2. Generate the keystore locally, e.g.:
   `keytool -genkeypair -v -keystore pennywiseai-ynab-release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000`
   Back the file up durably; it can never be rotated without breaking updates.
3. Add four repository secrets:
   - `KEYSTORE_BASE64` — `base64 < pennywiseai-ynab-release.jks`
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`

## Gradle change (`app/build.gradle.kts`)

- `signingConfigs { create("release") }` populated from env vars
  `SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
  `SIGNING_KEY_PASSWORD`.
- The `release` build type uses that signing config **only when the env vars
  are set**; otherwise `assembleRelease` keeps producing an unsigned APK, so
  local builds need no setup.
- Minify stays off. Enabling R8 requires keep-rules for Hilt/Room/Retrofit/
  kotlinx-serialization and is explicitly out of scope.

## Workflow (`.github/workflows/release.yml`)

Trigger: `on: push: tags: ['v*']`.

Steps, in order:

1. `actions/checkout` with `submodules: true`.
2. Set up JDK 21 (Temurin) — the unit-test task already requires a JDK 21
   launcher for parser-core's bytecode.
3. `gradle/actions/setup-gradle` for build caching.
4. Decode `KEYSTORE_BASE64` to a file; export the four `SIGNING_*` env vars.
5. **Tag/version guard**: fail if the pushed tag (`v<X>`) does not equal
   `v` + `versionName` in `app/build.gradle.kts`. Catches a forgotten version
   bump before Obtainium sees a mismatched release.
6. `./gradlew test :app:assembleRelease` — tests gate the release.
7. Create the GitHub Release for the tag with
   `softprops/action-gh-release`, attaching the APK renamed to
   `pennywiseai-ynab-<version>.apk`.

## Release ritual

1. Bump `versionCode` (monotonic int) and `versionName` in
   `app/build.gradle.kts`; commit.
2. `git tag v<versionName> && git push origin main --tags`.
3. Workflow builds, signs, publishes. Obtainium (configured once on the phone
   with the repo URL) picks up the new release.

## Error handling

- Missing/wrong secrets → keystore decode or Gradle signing fails, workflow
  red, no release created.
- Test failure → workflow stops before assembling; no release created.
- Tag/versionName mismatch → guard step fails with an explicit message; delete
  the tag, fix the version, re-tag.

## Testing

- The workflow itself is validated by pushing a real tag (`v0.1.0`) and
  confirming: green run, Release exists, APK installs on-device, and Obtainium
  detects it.
- Unit tests run inside the workflow; no new app tests are needed for this
  change (the Gradle signing block is configuration, exercised by the workflow
  run itself).
