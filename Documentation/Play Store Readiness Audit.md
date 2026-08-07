# BPMetrics — Play Store Readiness Audit

**Date:** 2026-07-31
**Branch audited:** `dev/multi-watch-v1.1` @ `0d4c761`
**Modules:** `:mobile`, `:wear`, `:core`

## How this was verified

Findings are grounded in build output, not just source reading:

- `./gradlew :mobile:lintRelease :wear:lintRelease :core:lintRelease` — wear fails with 3 errors; mobile reports 51 warnings / 7 hints; core clean.
- `./gradlew :mobile:assembleRelease :wear:assembleRelease` — both succeed, **unsigned**.
- `./gradlew :mobile:dependencyInsight --configuration releaseRuntimeClasspath --dependency room-compiler` — confirms the annotation processor resolves onto the release runtime classpath.
- APK contents inspected via `unzip -l`.
- Full-tree greps for network calls, `stringResource`, permission launchers, coroutine scopes, and `TODO`/`FIXME` markers.

**Current state summary:** the app compiles and produces working release artifacts, but cannot be uploaded (unsigned, colliding version codes) and carries several policy and correctness problems that would surface in review or in the field.

---

# P0 — Blocks upload or gets rejected

### 1. No release signing configuration

No `signingConfigs` block exists in any module and no keystore is present (or gitignored) in the tree. Build output is `mobile-release-unsigned.apk` / `wear-release-unsigned.apk`.

**Fix:** generate an upload keystore, add a `signingConfigs` block reading from a gitignored `keystore.properties`, enroll in Play App Signing.

---

### 2. Mobile and Wear share `applicationId` *and* `versionCode`

| | `applicationId` | `versionCode` | `versionName` |
|---|---|---|---|
| `mobile/build.gradle.kts:16-19` | `inga.bpmetrics` | `1` | `1.0` |
| `wear/build.gradle.kts:15-18` | `inga.bpmetrics` | `1` | `1.0` |

Sharing the package name is correct for a paired phone + watch listing. But both APKs ship in the same Play release, and Play requires each APK in a multi-APK release to carry a distinct version code. **This upload is rejected outright.**

**Fix:** define the version in the root build and give Wear an offset (common convention: watch = phone code + 1000, or phone code + 1).

---

### 3. Version metadata is stale

`versionCode 1` / `versionName "1.0"` while the branch is `dev/multi-watch-v1.1` with three feature commits past that point. No git tags exist in the repo.

**Fix:** establish a version bump + tagging convention before the first upload, not after.

---

### 4. No privacy policy

Nothing in the repo, no in-app link, no About screen. Play requires a privacy policy URL in the App content section for **every** app; heart-rate data additionally puts BPMetrics under the stricter Health apps policy.

---

### 5. Data Safety and Health declarations not yet addressable

Heart rate is sensitive health data. The Play Console requires:

- Data Safety form (collection, sharing, retention, deletion)
- Health Apps declaration
- Foreground service type declaration (see #14)
- Photo & video permission declaration, if #6 is kept

Finding #8 materially changes the honest answers to the Data Safety form, so resolve that first.

---

### 6. `READ_MEDIA_VIDEO` is a broad-media permission requiring justification

`VideoExporter.getOverlappingVideos` (`mobile/.../export/VideoExporter.kt:395-419`) queries the entire `MediaStore.Video` collection to find clips overlapping a recording's time window.

Two problems:

- Play's Photo & Video Permissions policy requires a declaration form for broad media access and routinely rejects apps where a picker would suffice.
- Lint flags `SelectedPhotoAccess`: Android 14 partial ("select photos") grants are not handled, so a user who picks partial access silently gets an empty suggestion list.

Note the *other* video entry point already uses `ActivityResultContracts.GetContent()` (SAF), which requires no permission at all.

**Options:** migrate the overlap-suggestion feature to the photo picker (removes the policy problem entirely), or keep it, handle partial grants, and prepare the declaration.

---

### 7. `INTERNET` permission declared but never used

Declared in both `mobile/src/main/AndroidManifest.xml` and `wear/src/main/AndroidManifest.xml`. A full-tree grep for `HttpURLConnection`, `OkHttp`, `Retrofit`, `URL(`, and `http(s)://` found **zero** network calls (the single match is a doc comment URL).

It contradicts the README's "No cloud storage, no privacy compromises" and is exactly the mismatch reviewers look for.

**Fix:** delete from both manifests.

---

### 8. Auto Backup is on with untouched template rules — health data goes to Google Drive

`mobile/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` are the Android Studio scaffolding files with every rule commented out, so the default "back up everything" behaviour applies.

That means the following are uploaded to cloud backup:

- the Room database `bpmetrics_db` containing all heart-rate records
- the persistent copies written to `filesDir/db_backups/` by `LibraryDatabase.performPreMigrationBackup` (`LibraryDatabase.kt:209`)

This directly contradicts the local-only promise in the README and changes the Data Safety answers.

**Fix:** either set `android:allowBackup="false"`, or write explicit `<exclude>` rules covering the database and `db_backups/`. Whichever is chosen must match what the README and privacy policy claim.

---

# P1 — Ships badly

### 9. Mobile release APK is 71 MB; Wear APK is 31 MB

Root cause is proven, not inferred. `mobile/build.gradle.kts:69` declares:

```kotlin
implementation(libs.androidx.room.compiler) { … }   // ← wrong
ksp(libs.androidx.room.compiler)                    // ← correct, already present
```

`dependencyInsight --configuration releaseRuntimeClasspath` confirms `androidx.room:room-compiler:2.8.1` resolves onto the **runtime** classpath. The shipping APK therefore contains:

```
1,175,740  org/sqlite/native/Mac/x86_64/libsqlitejdbc.jnilib
1,023,488  org/sqlite/native/Windows/aarch64/sqlitejdbc.dll
  923,136  org/sqlite/native/Windows/x86_64/sqlitejdbc.dll
  861,184  org/sqlite/native/Windows/x86/sqlitejdbc.dll
  752,128  org/sqlite/native/Windows/armv7/sqlitejdbc.dll
   47,846  androidx/room/jarjarred/org/antlr/v4/tool/templates/codegen/Go/Go.stg
   42,870  androidx/room/jarjarred/org/antlr/v4/tool/templates/codegen/Cpp/Cpp.stg
```

The Room annotation processor, ANTLR, and SQLite JDBC native binaries **for macOS and Windows** are being shipped to Android phones. Combined with `isMinifyEnabled = false` and an unshrunk `material-icons-extended`, dex totals 65 MB across 5 dex files.

A 31 MB Wear APK is also very poor for install-over-Bluetooth.

**Fix:** delete the `implementation` line, enable R8 (#10). Expect an order-of-magnitude reduction.

---

### 10. R8 is disabled — and enabling it naively will silently break watch↔phone sync

`isMinifyEnabled = false` in all three modules. All three `proguard-rules.pro` files are 100% commented-out template text.

The hazard: `BpmWatchRecord` and `BpmDataPoint` (in `:core`) cross the Wearable Data Layer as Gson-reflected JSON —
`PhoneSyncManager.sendRecordToPhone` (`wear/.../sync/PhoneSyncManager.kt:73`) serialises, `DataClientProcessor.convertDataItemToRecord` (`mobile/.../datasync/DataClientProcessor.kt:84`) deserialises.

Without keep rules, R8 renames the fields, the JSON keys stop matching, and **sync fails without a crash** — the app builds, installs, launches, and quietly never receives data from the watch again.

**Fix, in this order:**
1. Enable `isMinifyEnabled` + `isShrinkResources`.
2. Add keep rules for `inga.bpmetrics.core.**` (and any other Gson-reflected type).
3. Test a full watch → phone transfer on **release** builds before proceeding with anything else.

---

### 11. `./gradlew lint` fails on `:wear`

Three `RestrictedApi` errors on `ExerciseTrackedStatus.Companion.OWNED_EXERCISE_IN_PROGRESS`:

- `wear/.../health/ExerciseClientManager.kt:77`
- `wear/.../health/ExerciseClientManager.kt:90`
- `wear/.../health/HealthService.kt:126`

CI (`.github/workflows/android-ci.yml`) runs `./gradlew check` on `main`, so either main is red or these landed on the branch.

**Fix:** compare `info.exerciseTrackedStatus` against the public API surface rather than the restricted companion constant.

---

### 12. A BPM reading over 250 crashes the watch and destroys the recording

`BpmDataPoint.init` throws `IllegalArgumentException` when `bpm > 250` (`core/.../BpmDataPoint.kt`).

The failure path:

1. Ingest at `RecordingRepository.kt:167` filters only `bpm > 0` — an artifact reading of e.g. 300 is persisted to the local DAO without complaint.
2. `finalizeAndCleanup` reconstructs it at `RecordingRepository.kt:262`:
   ```kotlin
   val points = dao.getAllPoints().map { BpmDataPoint(it.timestamp, it.bpm) }
   ```
3. This runs inside `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (`RecordingRepository.kt:39`) with **no `CoroutineExceptionHandler`** → uncaught → process crash, and the entire session is lost before it reaches the sync outbox.

The same shape of failure occurs if the wall clock steps backwards mid-recording: `BpmWatchRecord.init` throws on `endTime <= startTime`.

Sensor artifacts on wrist-worn PPG are not rare.

**Fix:** clamp or drop out-of-range values at ingest; add a `CoroutineExceptionHandler` to the repository scope; guard `endTime <= startTime` at finalize.

---

### 13. No crash reporting

Nothing beyond Play Console vitals. Post-launch you are blind to field crashes and ANRs.

**Fix:** Firebase Crashlytics or Sentry. Both work fine for a local-only app, but must be disclosed in Data Safety.

---

### 14. Wrong foreground service type for video export

`mobile/src/main/AndroidManifest.xml:24` declares `android:foregroundServiceType="dataSync"` for `BpmExportService`, which does video transcoding.

Android 14 added `mediaProcessing` specifically for this. `dataSync` carries tighter runtime quotas on Android 15+ and needs its own Play declaration. The app already targets SDK 36.

---

### 15. Zero localization; template resources still present

`stringResource` appears **0 times** across both apps. There are 131 hardcoded `Text("…")` literals in `:mobile` alone.

Leftovers flagged by lint as unused:

- `wear/src/main/res/values/strings.xml` — `hello_world` = `"From the Square world,\nHello, %1$s!"` (plus the `values-round` variant)
- `mobile/src/main/res/values/colors.xml` — the entire untouched purple/teal template palette
- `mobile` + `wear` — `ic_launcher_background.xml`, `splash_icon.xml`

---

### 16. `minSdk 34` cuts off most of the market

Android 14+ phones only; Wear OS 5+ watches only. Lint reports the `SDK_INT` guards in `ExportUtils.kt:46,62` and `BpmGraphDetailScreen.kt:91` as already-dead code, so nothing in the source appears to actually require 34.

This is a business decision, not a defect — but launching at 34 means a very small addressable audience for no evident technical reason. Dropping to 30–31 would multiply reach at the cost of testing time.

---

# P2 — Polish

| # | Finding | Location |
|---|---|---|
| 17 | `Theme.BPMetrics` inherits `android:Theme.Material.Light.NoActionBar`; no `values-night` → white window flash behind a dark Compose UI | `mobile/res/values/themes.xml` |
| 18 | `dynamicColor = true` hardcoded → the `bpm_accent` brand colour never appears on Android 12+ | `ui/theme/Theme.kt` |
| 19 | No `android:label` on the mobile `<application>` element | `mobile/AndroidManifest.xml` |
| 20 | Adaptive icons missing the `monochrome` layer (themed icons, Android 13+) — both modules | `mipmap-anydpi-v26/` |
| 21 | `import android.R` plus framework notification icons | `export/BpmExportService.kt:3` |
| 22 | `exportSchema = true` with no `room.schemaLocation` KSP arg → no schemas committed, migrations unverifiable and untestable | `library/LibraryDatabase.kt:157` |
| 23 | 38 tests for ~13,000 lines; `MIGRATION_4_5` is untested despite being the stated data-loss guard | — |
| 24 | Dependencies ~1 year stale: Compose BOM 2025.09 → 2026.06, media3 1.5.1 → 1.10.1, AGP 8.13 → 9.3.1; `navigation-compose` declared twice at two versions | `gradle/libs.versions.toml` |
| 25 | Phone sync only runs while `MainActivity` is alive (listener bound to activity lifecycle) — records don't land until the app is opened | `MainActivity.kt:46` |
| 26 | CSV import failures return `null` silently | `export/CsvExporter.kt:113` |
| 27 | No About screen, no in-app privacy policy link, no onboarding | — |
| 28 | No release CI: no signed-AAB job, no version automation, no signing secrets | `.github/workflows/android-ci.yml` |

---

# Remediation plan

## Phase 1 — Make it publishable (~1 week)

1. Generate an upload keystore; add `signingConfigs` reading from a gitignored `keystore.properties`; enroll in Play App Signing. *(#1)*
2. Split version codes — shared version in the root build with a Wear offset. Set `1.1.0` and an appropriate code. *(#2, #3)*
3. Delete `implementation(libs.androidx.room.compiler)` from `mobile/build.gradle.kts:69`. Rebuild; confirm the `sqlitejdbc` and `jarjarred` entries are gone from the APK. *(#9)*
4. Enable `isMinifyEnabled` + `isShrinkResources`. Add Gson keep rules for `inga.bpmetrics.core.**`. **Then test a full watch → phone sync on release builds before doing anything else.** *(#10)*
5. Remove `INTERNET` from both manifests. Decide on `READ_MEDIA_VIDEO`. *(#6, #7)*
6. Write real backup / data-extraction rules, or disable backup. Must match README and privacy policy. *(#8)*
7. Fix the three wear lint errors so `./gradlew check` is green. *(#11)*

## Phase 2 — Don't ship known crashes (~1 week)

8. Clamp/drop out-of-range BPM at ingest; add a `CoroutineExceptionHandler` to `RecordingRepository.scope`; guard `endTime <= startTime` at finalize. *(#12)*
9. Switch `BpmExportService` to the `mediaProcessing` FGS type. *(#14)*
10. Add crash reporting; disclose it in Data Safety. *(#13)*
11. Add a `values-night` theme and a dark-capable window background; remove the `dynamicColor` hardcode. *(#17, #18)*
12. Set `room.schemaLocation`, commit schemas, write a `MIGRATION_4_5` test. *(#22, #23)*

## Phase 3 — Store assets & compliance (~3–5 days, parallelizable)

13. Write and host the privacy policy. Be specific about heart-rate data, local-only storage, and whatever was decided in step 6. *(#4)*
14. Add an About screen linking to it. *(#27)*
15. Complete Data Safety, Health Apps declaration, FGS declaration, and (if kept) the photo/video permission declaration. *(#5)*
16. Produce store assets: 512×512 icon, 1024×500 feature graphic, phone screenshots, and **Wear OS screenshots** — a separate required set; the Wear listing will not publish without them.
17. Decide on `minSdk`. *(#16)*

## Phase 4 — Release engineering (~2–3 days)

18. Extend CI to build a signed AAB on tag, with secrets in GitHub Actions. *(#28)*
19. Internal testing track release → install on a real phone + watch pair → verify sync, export, notifications, and a fresh install on a device that has never had the app.
20. Closed testing with real users before production. Google requires a testing period for new personal developer accounts.

## Phase 5 — Post-launch backlog

Localization (extract the 131 strings), background sync independent of `MainActivity`, dependency upgrades, monochrome icons, test coverage, template resource cleanup.

---

# Do these first

1. **Fix the version code collision** — a one-line blocker that would otherwise be discovered at upload time.
2. **Delete the `room-compiler` implementation line** — one line, roughly 30 MB.

The finding that will bite hardest if skipped is **#10**: enabling R8 without keep rules produces an app that builds, installs, launches, and silently never receives data from the watch again.
