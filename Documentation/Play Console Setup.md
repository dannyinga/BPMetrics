# Play Console setup — step by step

Everything that happens in a browser rather than in code. Do these in order; several later steps are
gated on earlier ones.

Assumes a **personal** developer account, which is what decides the testing requirement in step 9.

---

## ⚠️ Read this before you plan a date

Personal developer accounts registered after 13 November 2023 must run a **closed test with at least
12 testers, opted in continuously for 14 days**, before Google will even accept an application for
production access. Internal testing does not count. Neither does 12 people who join on day 12.

Practically: **the 14-day clock is the longest thing in this whole document**, and it cannot start
until steps 1–8 are done. Recruit the twelve first — you need twelve real Google accounts willing to
install and leave it installed. Friends with watches are the obvious candidates, and you already
have some.

---

## 1. Create the developer account

console.play.google.com → pay the **$25 one-off** fee.

Personal accounts require identity verification: a government ID and proof of address. This is
reviewed by a human and can take a few days. Start it now even if you are weeks from shipping.

The account name is shown publicly on your listing. Choose accordingly.

## 2. Create the app

**All apps → Create app.**

| Field | Value | Reversible? |
|---|---|---|
| App name | BPMetrics | Yes |
| Default language | English (US) | Yes |
| App or game | App | Yes |
| Free or paid | Free | **Paid → free only. Never free → paid.** |

You are not asked for the package name here — it is taken from the first upload, and
`inga.bpmetrics` is **permanent** once published. It cannot be changed, ever, for the life of the
listing.

## 3. Generate the upload key

Locally, once:

```bash
keytool -genkeypair -v -keystore bpmetrics-upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias bpmetrics
```

Keep `bpmetrics-upload.jks` somewhere backed up and **outside the repository** — `.gitignore`
already excludes `*.keystore` but not `*.jks`, so do not rely on it.

Enrolling in Play App Signing (step 5) means Google can reset this key if you lose it. That safety
net does not exist until you have uploaded once.

## 4. Wire signing into Gradle

In `mobile/build.gradle.kts` and `wear/build.gradle.kts`, read credentials from the environment so
local builds work without secrets and CI works with them. I can do this part; it is the one step
here that is code.

## 5. First upload — must be done by hand

**Testing → Internal testing → Create new release.**

This upload is what creates Play App Signing. Choose **"Let Google create and manage my app signing
key"**.

The Play Developer API cannot create a listing or make a first release, so this step is manual
exactly once. Everything after it can be automated.

### ⚠️ The phone and watch go to *separate tracks*

They cannot share a release. Since August 2023 Play requires Wear OS releases to use a **dedicated
Wear OS track**, so that watch releases and watch testers are managed independently of the phone
app. Putting both bundles in one release is rejected with:

> To submit this release, remove your Wear OS bundle and create a dedicated Wear OS track under
> Wear OS in the Form factors tab in Advanced settings.

Create it first: **Release → Setup → Advanced settings → Form factors → Wear OS → Add Wear OS
track.** Then:

| Bundle | Goes to |
|---|---|
| `mobile/build/outputs/bundle/release/mobile-release.aab` | the ordinary track (Internal testing) |
| `wear/build/outputs/bundle/release/wear-release.aab` | the **Wear OS** track |

Same `applicationId`, same signing key, **different `versionCode`s** — 10 and 11. Play routes by
device using the watch module's `<uses-feature android:name="android.hardware.type.watch" />`.

The release workflows publish to `alpha` / `production` for the phone and `wear:alpha` /
`wear:production` for the watch. If a publish is rejected as an unknown track, the Wear track has
not been created yet, or is named differently — list the real names with the Play Developer API:
`GET /androidpublisher/v3/applications/inga.bpmetrics/edits/{editId}/tracks`.

## 6. Complete the App content declarations

**Policy → App content.** All of these are mandatory before any public track.

| Declaration | For BPMetrics |
|---|---|
| **Privacy policy** | A public URL. GitHub Pages on this repo is free and sufficient. Must state what you collect and how it is handled. |
| **App access** | No login — all functionality available without credentials. |
| **Ads** | No. |
| **Content rating** | Questionnaire. A health tracker rates Everyone. |
| **Target audience** | 18+. Do not check any under-13 box; it triggers Families policy obligations you do not want. |
| **Data safety** | ⚠️ The one that needs care. See below. |
| **Health apps** | ⚠️ Also needs care. See below. |
| News / COVID / Financial / Government | No to all. |

### Data safety — the section that matters

Heart rate is **sensitive health data** under Play policy. Answer honestly:

- **Collected?** Yes — health and fitness → heart rate.
- **Shared with third parties?** **No.** Recordings go watch → phone and stay there.
- **Encrypted in transit?** Yes — the Wearable Data Layer is encrypted by Play Services.
- **Can users request deletion?** Yes — recordings are deleted in-app.
- **Required or optional?** Required; it is the entire function of the app.

Two things worth being precise about, because you have already had to correct the wording once:
the app itself never sends data to a server, but Play Services may relay watch→phone transfers via
Google's infrastructure when the devices are not directly connected. Your privacy policy should say
that plainly rather than claim data never leaves the devices.

### Health apps declaration

Play has a separate declaration for apps handling health data. You will be asked what health data
you access and why. Answer: heart rate, via Wear OS Health Services, for the user's own recording
and review. There is no medical claim — do not make one anywhere in your listing, or you enter a
much stricter policy category.

## 7. Store listing

**Grow → Store presence → Main store listing.**

| Asset | Requirement |
|---|---|
| App name | ≤ 30 characters |
| Short description | ≤ 80 characters |
| Full description | ≤ 4000 characters |
| App icon | 512 × 512 PNG, 32-bit |
| Feature graphic | 1024 × 500 |
| Phone screenshots | 2–8, min 320px on the short side |
| **Wear OS screenshots** | **Required** to appear on the Wear store. 384 × 384 round. |

Your `/Private_Media` and demo folders likely already have usable material.

## 8. Service account, for automated releases

This is what lets `staging` publish itself.

1. **Google Cloud Console** → create a project (or reuse one).
2. **APIs & Services → Enable APIs** → enable **Google Play Android Developer API**.
3. **IAM & Admin → Service Accounts** → create one → **Keys → Add key → JSON**. Download it.
4. **Play Console → Users and permissions → Invite new user** → paste the service account's email.
5. Grant **Release to testing tracks** and **Release to production** for BPMetrics only.
6. Put the JSON into the GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`.

Access can take up to 24 hours to propagate. If the first automated upload fails with a permissions
error, wait rather than debug.

## 9. Closed testing — the 14-day clock

**Testing → Closed testing → Create track.**

- Add 12+ testers by email, or better, create a **Google Group** and add the group — then you can
  add and remove people without touching the Play Console.
- Share the opt-in link. Each tester must **accept it and install**.
- All twelve must remain opted in for **14 continuous days**. Someone uninstalling resets your
  standing with them.

This is the track `staging` publishes to, so your ordinary QA and the countdown are the same
activity rather than two chores.

Track progress under **Testing → Closed testing → your track**.

## 10. Apply for production access

Once the 14 days are up, Play surfaces an application form. You describe how you tested and what you
learned. Review takes days and can ask for more.

## 11. First production release

**Production → Create new release.** Start at a **staged rollout** — 10% is a sensible first step —
so a problem reaches a tenth of your users rather than all of them.

After this, pushing to `production` does it automatically.

---

## Ordering, at a glance

```
1 account ──▶ 2 app ──▶ 3 key ──▶ 4 gradle ──▶ 5 first upload (manual)
                                                     │
                        ┌────────────────────────────┤
                        ▼                            ▼
                 6 declarations              8 service account
                 7 store listing                     │
                        │                            │
                        └──────────┬─────────────────┘
                                   ▼
                        9 closed testing ── 14 days ──▶ 10 apply ──▶ 11 production
```

Steps 6, 7 and 8 are independent of each other — do them while the identity verification in step 1
is being reviewed.
