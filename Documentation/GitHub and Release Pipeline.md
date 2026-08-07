# GitHub and release pipeline

How BPMetrics gets from a commit to a phone — either from a GitHub Release or from the Play Store.

---

## Part 1 — What I found in the repo today

Worth knowing before anything else, because two of these are blockers and one is a surprise.

| Finding | Impact |
|---|---|
| **Every CI run has failed.** Five for five, back to May. **Now fixed** — see below. | The pipeline was red for months and nothing said so. That is the real finding: a gate nobody watches is not a gate. |
| **No signing configuration anywhere.** `release {}` has no `signingConfig`. | `assembleRelease` produces an *unsigned* APK. It cannot be installed and cannot be uploaded to Play. **Blocker.** |
| **`versionCode = 1` in both modules**, hardcoded separately. | Play rejects any upload whose `versionCode` it has seen before, and every artifact in a release needs its own. Two modules both saying `1` cannot both be uploaded. **Blocker.** |
| **`minSdk = 34`** (Android 14) on both modules. | Excludes every device older than Android 14, which on the watch side means Wear OS 5 and newer only. See "The minSdk decision" below. |
| CI only triggers on `main`. | No gate on the branch you actually develop on. |
| CI never runs instrumented tests. | The Room migration tests — the defect class that has bitten this project twice — have never once run automatically. |
| No branch protection on `main`. | Nothing stops a direct push, including a red one. |
| `isMinifyEnabled = false` | Larger downloads, and R8's checks never run. Not a blocker. |

### The CI failure, and what it was hiding

Two separate causes, one behind the other:

1. `:mobile:compileDebugUnitTestKotlin` — test sources referencing classes that no longer existed.
   Fixed earlier in this project's history, which uncovered:
2. `:wear:lintDebug` — four `RestrictedApi` errors on `OWNED_EXERCISE_IN_PROGRESS`. The Health
   Services library annotates that constant as internal even though reading `exerciseTrackedStatus`
   is the documented way to ask the question, so every use is a lint error. Three were long-standing;
   **one I introduced myself** in the session-recovery work.

Both are now fixed — the four call sites go through a single `isOwnedExerciseInProgress()` helper
carrying one suppression with a reason. `./gradlew build`, the exact command CI runs, passes locally.

The lesson worth keeping: the first failure masked the second for months. Once CI is green, it needs
to be **required**, so it cannot go red unnoticed again.

---

## Part 2 — The concepts

### Debug vs release

A **debug** build is what Android Studio installs when you press Run. It is signed with a throwaway
key that every Android developer's machine generates automatically, it is debuggable, and it is not
distributable.

A **release** build is optimised, not debuggable, and signed with *your* key. Everything below is
about release builds.

### Signing, and why it is the thing to get right

An Android app is signed with a private key held in a **keystore** file. Android's rule: an update
can only install over an existing app if it is signed with the same key. Lose the key and you can
never update your app again — the only recourse is a new listing under a new package name, starting
from zero installs.

Google solved this with **Play App Signing**, and it is worth understanding because it changes what
you keep safe:

- You generate an **upload key**. You sign your builds with it. It lives in a GitHub secret.
- Google holds the **app signing key** — the one that actually signs what users install.
- Play strips your upload signature and re-signs with the app signing key.
- **If your upload key leaks or is lost, Google resets it for you.** That is the whole point.

One consequence to plan around: an APK you sign yourself and publish on GitHub carries *your*
signature. An install from Play carries *Google's*. They are different apps as far as Android is
concerned — **you cannot install one over the other**. Whichever you have, you must uninstall before
switching. Given you sideload today, this will bite the first time you install from Play, so decide
which is your primary channel.

### versionCode vs versionName

- **`versionCode`** — an integer, invisible to users. Play requires it to strictly increase, and
  never accepts one it has already seen, even from a deleted release. This is the one that causes
  upload failures.
- **`versionName`** — the string a user sees, "1.2.0". Play does not care whether it changes.

With two modules shipping to one listing, phone and watch each need their *own* `versionCode`. The
scheme below derives both from one number so they cannot collide.

### APK vs AAB

- **APK** — installable directly. What you sideload.
- **AAB** (Android App Bundle) — not installable. You upload it and Play generates a tailored APK
  per device. **Required** for new apps on Play.

So the pipeline produces both: an **AAB** for Play, an **APK** for the GitHub Release.

### Installing, concretely

You only install via Android Studio today. The other three ways, in order of how you will use them:

- **From a GitHub Release.** Open the release page on the phone's browser, tap the `.apk`, approve
  "install unknown apps" for the browser the first time. That is the whole process. The watch is
  harder — Wear OS has no browser, so a watch APK has to go over `adb` (`adb connect <watch-ip>`
  after enabling wireless debugging), which is fiddly enough that Play's internal track is genuinely
  the easier route for the watch.
- **From Play's internal testing track.** You get an opt-in link, tap it once, and thereafter the
  app updates itself like any other — on the phone *and* the watch, with no cables. This is the
  reason to bother with Play even before you publish publicly.
- **From Play production.** Ordinary Play Store install.

### Play tracks

Play has four rungs, and they map naturally onto branches:

| Track | Who sees it | Review |
|---|---|---|
| **Internal testing** | Up to 100 named testers | Minutes |
| **Closed testing** | Named tester lists / Google Group | Hours to a day |
| **Open testing** | Anyone who opts in | Full review |
| **Production** | Everyone | Full review, staged rollout by % |

### How the watch and the phone ship together

Both modules already declare `applicationId = "inga.bpmetrics"`, and the watch module declares
`<uses-feature android:name="android.hardware.type.watch" />`. That is the correct arrangement: one
Play listing, two artifacts, and Play routes each to the right device. Both must be signed with the
same key and carry distinct `versionCode`s.

### ⚠️ The rule that will decide your timeline

For **personal** Play developer accounts created after 13 November 2023, Google requires a **closed
test with at least 12 testers, running continuously for 14 days**, before you may even apply for
production access. Organisation accounts are exempt.

Check which account type you have before planning any launch date. If it applies, twelve real people
must opt in and stay opted in for a fortnight — that is a recruiting problem, not an engineering one,
and it is the longest pole by far.

### The minSdk decision

`minSdk = 34` means Android 14 or newer on the phone, and Wear OS 5 or newer on the watch. Both cut
off a meaningful share of devices, and the watch side especially — Wear OS 5 shipped in 2024, so
anything older cannot install this at all.

I do not know whether that was deliberate. Health Services and the Data Layer both support far older
releases, so if the number was a default nobody revisited, lowering it is the single cheapest way to
widen who can use this. Check the current distribution figures in Android Studio
(**Help → New Project → minimum SDK → Help me choose**) before deciding — it shows live percentages.

---

## Part 3 — The branch model

Three long-lived branches, each mapping to a Play track. For a solo developer three branches would
normally be overkill; here it earns its keep because Play's own tracks are staged the same way.

```
feature/*  ──PR──▶  dev  ──PR──▶  staging  ──PR──▶  main
                     │              │                │
                  build +        internal         production
                  all tests      testing          (staged %)
                                 track            + GitHub Release
```

| Branch | Purpose | Gate | Deploys to |
|---|---|---|---|
| `feature/*` | One change | Build + unit tests + lint on PR | nothing |
| `dev` | Integration | Everything above **plus instrumented tests on an emulator** | nothing (or internal track) |
| `staging` | Release candidate | Full pipeline; version bumped here | Play **internal testing** |
| `main` | Shipped | Full pipeline | Play **production**, staged rollout + **GitHub Release with APK** |

**Why instrumented tests gate `dev` specifically:** they need an emulator, which is slow (several
minutes). Too slow for every feature PR, too important to leave until `main` — the migration tests
are exactly the thing you want to hear about before a release candidate exists, not after.

---

## Part 4 — The workflows

Four files in `.github/workflows/`.

### `ci.yml` — every PR and push to any of the three branches
Build, unit tests, lint. Fast, so it never discourages opening a PR. Replaces the existing
`android-ci.yml`, which triggers only on `main` and is indented oddly at the top level.

### `instrumented.yml` — PRs into `dev`, `staging`, `main`
Runs `connectedDebugAndroidTest` on an emulator via
`reactivecircus/android-emulator-runner`, with AVD caching so it costs minutes rather than tens of
minutes. This is what finally runs `LibraryDatabaseMigrationTest` automatically.

### `release-staging.yml` — push to `staging`
Builds a signed AAB and uploads to Play's **internal testing** track. On your device within minutes.

### `release-production.yml` — push to `main`
Builds signed AAB **and** APK. Uploads the AAB to **production** at a staged rollout, attaches the
APK to a **GitHub Release** tagged from the version, and writes release notes from the commits.

### Secrets required

| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | The upload keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias inside the keystore |
| `KEY_PASSWORD` | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Cloud service account with Play Developer API access |

> This repo is **public**. Secrets are not exposed to forked-PR builds, but be deliberate: never
> `echo` a secret, and keep the signing steps out of workflows that run on `pull_request` from forks.

---

## Part 5 — Versioning

One source of truth at the repo root, `version.properties`:

```properties
versionName=1.1.0
versionBase=2
```

Both modules read it, and derive non-colliding codes:

- mobile `versionCode = versionBase * 10` → 20
- wear `versionCode = versionBase * 10 + 1` → 21

Bump `versionBase` in the PR that opens a release. It is one number, it is reviewable, and it makes
"which build is this?" answerable from the repo rather than from Play.

---

## Part 6 — Order of work

Roughly dependency-ordered. Steps 1–3 are worth doing regardless of whether you ever ship to Play.

1. **Get CI green.** It may already be — the failure was test sources I have since fixed. Confirm
   before building anything on top of it.
2. **Create the upload keystore** and wire signing into `release {}`, reading credentials from
   environment variables so local builds work without secrets and CI works with them.
3. **Centralise versioning** into `version.properties`.
4. **Replace the CI workflow**; add the instrumented-test workflow.
5. **Create `dev` and `staging`**; protect all three with required status checks.
6. **Register the Play developer account** ($25 one-off). Check the personal-vs-organisation
   distinction against the 12-tester rule.
7. **Create the app listing and upload one AAB by hand.** The API cannot create a listing or make
   the first release — this step is manual, once.
8. **Create the service account**, grant it Play Developer API access, add the secrets.
9. **Add the release workflows**; test `staging` → internal track end to end.
10. **Complete the Play requirements**: privacy policy URL, data safety form, content rating,
    screenshots, target API level.
11. **Run the closed test** if the 12-tester rule applies.
12. **Ship `main` → production**, staged.

---

## Part 7 — Decisions taken

1. **Play is the primary channel.** GitHub Releases become an archive, not an install route — a
   sideloaded APK cannot upgrade a Play install, so offering both invites confusion. The release
   workflow still attaches an APK, labelled as such.
2. **`minSdk`** — audited below. Both apps build clean at 31 with no code changes.
3. **Personal Play account** → the 12-tester closed-testing rule **applies**. This changes the
   branch mapping: `staging` publishes to **closed testing**, not internal, because only closed
   testing counts toward the 14-day requirement.
4. **Only `staging` publishes.** `dev` stays local — Android Studio onto the phone and watch.
5. **`main` is renamed `production`**, matching the track it deploys to.

Revised mapping:

```
feature/*  ──PR──▶  dev  ──PR──▶  staging  ──PR──▶  production
                     │              │                  │
                  build +        Play closed        Play production
                  all tests      testing            (staged %)
                  (local only)   (the 14-day        + GitHub Release
                                  clock runs here)    (archive)
```

---

## Part 8 — The minSdk audit

Tested empirically by lowering the value and building, rather than by reading documentation.

| Module | Current | Builds clean at | First thing that breaks below that |
|---|---|---|---|
| `mobile` | 34 | **31** | `dynamicDarkColorScheme` (API 31) in `Theme.kt`; below 29, `ContentResolver.loadThumbnail` |
| `wear` | 34 | **31** | `VibratorManager` (API 31) in `HealthService.kt` |
| `core` | 34 | **31** | nothing of its own — it is a data module and only ever constrained by its consumers |

**`minSdk = 31` on all three builds and lints clean with no code changes at all.** That is Android
12 on the phone and Wear OS 3 on the watch, against Android 14 / Wear OS 5 today.

Going lower is cheap too: 29 needs one guard around `dynamicColorScheme`, 26 needs a second around
`loadThumbnail`. Neither is more than a few lines.

The code already anticipates older releases — there are `SDK_INT` guards for API 29 and 33, and the
watch already falls back when `HealthPermissions.READ_HEART_RATE` is unavailable. Whoever wrote
those expected to run below 34.

### ⚠️ But lint passing does not prove the watch works at 31

`HealthService` calls `startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)` and the
manifest declares `android:foregroundServiceType="health"`. **Both are API 34.** Lint does not flag
them because the type is an `int` constant, inlined at compile time where `NewApi` cannot see it.

So the watch module compiles clean at 31 and may still fail at runtime on a Wear OS 3 or 4 watch,
either by refusing the unknown service type or by silently not applying it — which on a watch means
the recording lifecycle problems we just spent a session fixing.

**Recommendation:** lower `mobile` and `core` to 31 now — that is free and safe, and the phone app
has no 34-only API. Hold `wear` at 34 until the health foreground service type has been given a
fallback and tested on an actual older watch. They are separate artifacts in the same Play listing,
so they are allowed to differ.
