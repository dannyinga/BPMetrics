# Navigation & Watches Redesign

**Date:** 2026-08-04
**Branch:** `dev/multi-watch-v1.1`
**Status:** design agreed, implementation in progress

---

## Goals

1. Replace the overloaded Library top bar with a navigation drawer.
2. Add a **Watches** registry so each watch can be given a human name from the phone.
3. Make analyses saveable, named, and permanent.

---

## Decisions

These were settled before implementation and drive everything below.

| Decision | Choice | Why |
|---|---|---|
| Watch name on a record | **Snapshot at ingest** | A record must keep the name it was recorded under, even after the watch is renamed |
| Saved analyses | **Frozen** | "Coachella 2026 Analysis" means *those* recordings, permanently |
| On-watch wearer cycler | **Retired** | Two sources of truth for the same field |
| Analysis location | **Drawer destination** | It is a peer activity, not a Library sub-view |

---

## Part 1 — Navigation drawer

### Destinations

Library (start), Analysis, Tags, Render Queue, Watches, Settings, About.

**About** is new. A privacy policy link is required for Play submission and the drawer is its natural home. See the Play Store Readiness Audit.

### What moves

The Library top bar currently carries Import, Clear Filters, Tags, Render Queue (+badge), and Settings. Tags, Render Queue, and Settings move to the drawer. Import and Clear Filters stay — they act on the Library's own contents.

Tags, Render Queue, and Settings are currently *pushed* onto the back stack with back arrows. As drawer peers they become siblings, so their `navigationIcon` becomes the hamburger.

### Back-stack semantics

This is the substantive part of Phase 1, not the drawer widget.

- Drawer open + back → close drawer
- Library + back → exit app
- Any other top-level + back → return to Library
- Detail / graph screens keep back arrows and push normally

Drawer navigation uses `popUpTo(LIBRARY) { saveState = true }`, `launchSingleTop = true`, `restoreState = true`. Without this, repeated drawer taps stack duplicate destinations.

### Three known gotchas

1. **Settings has an unsaved-changes dialog** wired only to back. Leaving via the drawer must trigger it too, or edits vanish silently.
2. **Library selection mode** replaces the nav icon with Close. The hamburger must not appear then, and drawer swipe-to-open must be disabled, or the gesture fights multi-select.
3. **The Render Queue badge** lives on the top bar icon today. It moves to the drawer item, where it is invisible while the drawer is closed. Keep a small indicator in the Library top bar while renders are active.

### Analysis scope

`AnalysisViewModel` is constructed from `libraryViewModel.filteredRecords`. That ViewModel is already hoisted to `BPMetricsNavHost` scope and shared, so Analysis continues to read the Library's current filter with no extra work — moving it into the drawer does not break it.

A standalone scope selector becomes necessary only in Phase 4, when a saved analysis needs to define its membership independently of whatever the Library happens to be filtered to right now.

---

## Part 2 — Watches

### Identity vs. attribution

Two separate concepts, deliberately stored separately:

| Field | Meaning | Lifetime |
|---|---|---|
| `watchId` | *Which physical watch* | Stable forever |
| `wearerName` | *Who was wearing it* | Snapshotted at ingest, frozen |

`wearerName` already exists as a per-record column, so **the display path does not change**. `ImageExporter`, `CsvExporter`, `JsonExporter`, and the multi-watch pills keep reading `record.metadata.wearerName` exactly as they do today. Only ingest changes.

`watchId` exists for grouping and provenance: "all records from Watch A, whatever it was called at the time." It is what enables filter-by-watch and duplicate merging.

### The worked example

> Saturday: give Watch A to Kyle. Registry says Watch A = "Kyle". Records stamp "Kyle".
> Sunday: give the same watch to Ben. Rename Watch A to "Ben". Records stamp "Ben".
> Saturday's records still read "Kyle".

### Why append-style, not the paired-device list

`NodeClient.getConnectedNodes()` returns only **currently connected** nodes. A friend's watch that is off, out of range, or with them rather than you will not appear — which is most of the time. It also reports `displayName`, which is the model, not the person.

So the registry is populated by records arriving from a watch it has not seen before. The node scan is a secondary aid for pre-registration only.

### The gap this creates, and the required mitigations

Snapshot-at-ingest means **the name must exist before the recording arrives**. The first use of a brand-new watch is exactly when it will not: records sync stamped with the model name, and naming the watch afterwards is too late.

Two features are therefore part of the core, not polish:

1. **Pre-registration** — name a watch before the event. The `NodeClient` scan surfaces paired-but-never-recorded watches; a manual "add watch" covers one that is not nearby.
2. **Bulk re-attribution** — *"apply name X to records from this watch between date A and B."* Cheap, since the name is a per-row column. Without it, fixing a mis-stamped event means editing records one at a time.

### The watch and its wearer are separate fields

A watch is not a person. `deviceName` identifies the hardware — "Watch A", the one with the blue
strap — and stays put. `currentWearerName` is who has it at the moment, and is what gets stamped
onto arriving records.

A single field cannot serve both. Naming a watch after its wearer means renaming the hardware every
time it changes hands, and makes "what did this particular watch record" unanswerable.

This distinction drives the filters:

| Filter | Matches | Answers |
|---|---|---|
| Wearer | `wearerName` frozen on each record | "Show me Kyle's recordings" — still correct after that watch was handed to Ben |
| Watch | `watchId` | "Show me everything this watch recorded", whoever wore it |

Watches are listed and picked by `deviceName`, never by wearer, so choosing one means choosing
hardware.

### Name resolution at ingest

Priority order:

1. The registry's current wearer for this watch
2. The name the watch sent (legacy records only — see below)
3. Blank — the recording arrives unattributed and can be corrected by bulk re-attribution

### Retiring the on-watch cycler

The watch's wearer-name preset cycler is removed. The phone is the single control point for naming. `RecordingRepository.setWearerName` / `getWearerName` and the chip in `RecordingScreen` go away; the watch continues to send its `deviceId` and stable ID.

### Stable watch identity

Neither existing candidate works as a key:

- **`deviceId`** is `Build.MODEL` unless set manually. Two friends with the same model collide into one entry.
- **Data Layer node ID** is unique but is not guaranteed stable across re-pairing or factory reset.

The watch therefore generates a **UUID on first run**, persists it in `bpm_prefs`, and sends it with every record. Unique per physical watch, survives re-pairing and app updates, and requires no configuration.

Records synced before that watch is updated are backfilled by grouping on `deviceId`. That cannot separate two identical models retroactively, so **watches should be updated before being handed out broadly**.

### Schema

Room migration **5 → 6**:

- New `watches` table: stable ID, device name, current wearer, last-seen model, last-seen node ID, first seen, last seen, colour.
- New `watchId` column on `bpm_records`.
- Backfill: one watch row per distinct existing `deviceId`.

Must follow the hardened pattern from `326b675` — idempotent, no destructive fallback — and needs a migration test. The project has none yet; see the Play Store Readiness Audit.

### Per-watch colour

`ImageExporter.MULTI_WATCH_PALETTES` currently assigns export colours **by list index**, so the same person changes colour between exports. Storing a colour on the watch row makes it consistent.

---

## Part 3 — Saved analyses

### Frozen, not criteria-based

A saved analysis stores a self-contained snapshot. If recordings are later imported, re-tagged, or deleted, a saved analysis does not change.

### What is stored

- Name and created timestamp
- Member record IDs
- A human-readable description of the filter that produced it
- **The per-record values the analysis needs**: title, date, min, avg, max, active duration, tags

That last item is what makes the snapshot self-contained. The screen lets you switch metric (LOW / AVG / HIGH), reverse either sort, and change category tab — all of which recompute from the snapshot without touching the library.

It also means a saved analysis survives deletion of its records. The numbers stay correct, drill-down to a deleted record is unavailable, and the UI reports "N recordings no longer in your library" rather than quietly changing the answer.

Storage cost is trivial: a few fields per record, tens of records per analysis.

### Navigation shape

The drawer's Analysis destination lands on a **list of saved analyses plus "New analysis"** — the same shape as Library, but for analyses. Library keeps a contextual "Analyze current filter" action that deep-links in pre-seeded.

---

## Phasing

| Phase | Scope | Status |
|---|---|---|
| 1 | Drawer, back-stack semantics, Analysis as a destination, About | Done |
| 2 | Watches registry: schema 5→6, backfill, screen, naming, ingest stamping, pre-registration, bulk re-attribution | Done |
| 3 | Watch-side UUID, retire cycler | Done — merge UI still outstanding |
| 4 | Saved analyses: table, snapshot capture, list UI | Done — standalone scope selector still outstanding |
| 5 | Filter by wearer and by watch; device/wearer split (schema 7→8) | Done |
| 6 | Per-watch colour, merge UI for duplicate registrations | Outstanding |

Phase 3 is what makes attribution correct when two friends have the same watch model, so watches
should be updated before being handed out broadly.

Nothing here has been exercised on a device. The migration tests compile and are packaged, but
`:mobile:connectedDebugAndroidTest` on real hardware is what actually proves the 5→8 chain against
a database with recordings in it.

---

## Open items

- Migration test infrastructure does not exist yet and is a prerequisite for Phase 2 landing safely.
- `mobile:assembleDebugAndroidTest` is broken independently of this work (`BpmRecordContentTest` references a composable that no longer exists), so instrumentation coverage for any of this cannot run until that is repaired.
