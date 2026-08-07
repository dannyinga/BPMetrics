# Export Utility and Settings Hardening

**Status:** design, not yet started
**Sprints:** 6
**Depends on:** Library and Analysis Hardening (events and groups must exist)

---

## 1. Context

Exporting works, but the way you reach it does not.

Today there are four separate entry points — a record's detail screen, the graph detail screen, the
library's multi-select menu, and the concurrent analysis screen — each opening its own dialog with
its own subset of the options. The video dialog is a single scrolling sheet containing resolution,
crop, time zone, overlay video, sync offset, graph rectangle, alignment mode and per-record colours,
with a live preview somewhere in the middle. It is the most complex screen in the app and it is
presented as a modal.

Settings are half-remembered. Image dimensions, opacity and the axis toggles persist through
`SettingsRepository`; the graph rectangle, alignment mode, overlay video and sync offset do not.
So the arrangement you spent ten minutes getting right for one video has to be rebuilt for the next
one, and there is no way to say "do that again."

The render queue is a separate drawer entry, which means the thing you configured and the thing
telling you how it is going live in different places.

**Goal:** one **Export Utility** — a place you go to make things, that folds in the queue, remembers
what you like as named presets, and can produce several exports from one set of choices.

### And the settings screen behind it

Settings has the same problem from the other direction. Of its four sections, three are **Image
Export Defaults**, **Video Export Defaults** and **Default Video Time Zone** — so once presets
exist, most of that screen has been superseded and what remains is one dropdown for auto-naming.

It also behaves unlike any other settings screen on the platform: changes are staged and you are
asked "Would you like to save your changes before leaving?" on the way out. Android settings apply
as you touch them.

So the second half of this document is not a tidy-up. Export defaults move out, and the things a
settings screen for *this* app should actually offer move in — most of which do not exist yet.

---

## 2. The shape of it

### 2.1 A staged flow, not a wall of options

Four steps, each answering one question, with the ability to jump back to any of them.

```
  ┌──────────┐   ┌────────────┐   ┌────────────┐   ┌────────┐
  │ 1 Source │──▶│ 2 Contents │──▶│ 3 Look     │──▶│ 4 Make │
  └──────────┘   └────────────┘   └────────────┘   └────────┘
   What am I      Which curves     How should it    Queue it
   exporting?     go on it?        look?
```

| Step | Answers | Notes |
|---|---|---|
| **1 Source** | recordings, an event, a group, or a saved analysis | Scopes which video clips are on offer |
| **2 Contents** | which clips to export, and whose curves go on each | One export per ticked clip — see §2.3 |
| **3 Look** | canvas, graph placement, background, and every option that exists today | Preview alongside, not buried |
| **4 Make** | confirm, then watch the queue | The queue lives here rather than in its own drawer entry |

The existing entry points survive — they open the utility with steps 1 and 2 already answered, so
exporting one recording stays two taps.

### 2.2 Presets

The point of the whole document. A preset is a named, saved set of everything in step 3.

- Ships with a few: **Landscape 1080p**, **Story 9:16**, **Square 1:1**.
- "Save as preset…" from step 3 captures the current settings.
- One preset can be marked default and is pre-selected for new exports.
- A preset stores *appearance*, never *content* — no record ids, no time ranges. Otherwise it stops
  being reusable the moment those recordings are gone.

### 2.3 Batch exporting — the video is the unit, not the event

An event is a concert; during it you might have filmed six clips. Each clip is its own export, with
the heart-rate curves of whoever was recording *while that clip was filming* overlaid on it.

So "one export per event" is wrong. The shape is:

```
Group: Coachella 2026
└── Event: Subtronics
    ├── clip 21:07  ── overlay: Kyle, Ben, Danny        ☑
    ├── clip 21:19  ── overlay: Kyle, Ben               ☑   Ben's watch had stopped
    ├── clip 21:26  ── overlay: Kyle, Ben, Danny        ☐   not wanted
    └── clip 21:44  ── overlay: Kyle, Danny             ☑
└── Event: Zeds Dead
    └── clip 23:12  ── overlay: Kyle                    ☑
```

Selecting a group or event lists every clip that overlaps it, each already showing which people
would appear on it. Tick any number; each ticked clip becomes one job. The recordings offered per
clip default to those overlapping **that clip's** timespan, not the whole event — a clip filmed
after someone's watch stopped should not offer their curve.

`VideoExporter.getOverlappingVideos` already finds clips overlapping a set of recordings, so the
matching exists; this is about presenting it per clip rather than picking one.

Each queued job stays independently cancellable and retryable.

### 2.4 Images do not go through the queue

An image renders in well under a second. Putting it in a queue adds a step and a place to look for
something that has already finished. Images export inline, with the file offered immediately;
the queue is for video only.

### 2.5 What Settings should hold

Grouped, applied immediately, and with each group earning its place.

| Group | Contains | Why |
|---|---|---|
| **Appearance** | Theme (system / light / dark), Material You colour on/off, 12- or 24-hour clock, date format | `Theme.kt` calls `dynamicDarkColorScheme` unconditionally — there is no way to turn it off or force a theme |
| **Library** | Default view mode (recordings / events / groups), default sort, auto-naming category | The view mode comes from the Library and Analysis Hardening work |
| **Heart rate** | Resting and maximum defaults, and the zone boundaries used for time-in-zone | Needed by LAH-6.5. Per-person overrides belong on the **person**, not here — a shared default with an override where it differs |
| **Storage** | What is using space, broken down; clear staged exports; manage database backups | See below — this is the section that matters most |
| **Sync** | Retry interval, a manual "check for recordings now", what is still awaiting transfer | The watch shows a pending count; the phone shows nothing |
| **About** | Version, build, privacy policy, licences, share diagnostics | Play requires a reachable privacy policy anyway |

**Storage deserves its own justification.** This app has already filled a phone and then been unable
to open, because every exported video was retained in the cache invisibly and the database backup
routine consumed what was left. Both are fixed, but neither is *visible*: there is still no way to
see that `files/db_backups` holds five copies of the database, and no way to restore one of those
backups without a cable and `adb`. A backup nobody can reach is not a backup.

So: show the breakdown, let it be cleared, and let a backup be restored from inside the app.

**Settings that are not settings.** Two things that look like candidates and are not:

- **Resting heart rate** varies per person, so it belongs on `PersonEntity` with a global default
  here to fall back on.
- **The 10-second gap threshold** is a statement about what the sensor does, not a preference. It
  stays a constant; exposing it invites someone to make their data lie.

---

## 3. Data model

### 3.1 Preset storage

Room, not DataStore. Presets are a list that grows, gets edited and deleted; DataStore is for
single values and modelling a collection in it is how you end up hand-parsing JSON.

```kotlin
@Entity(tableName = "export_presets")
data class ExportPresetEntity(
    @PrimaryKey(autoGenerate = true) val presetId: Long = 0,
    val name: String,
    /** Serialized ImageExportConfig + VideoExportConfig, minus anything content-specific. */
    val configJson: String,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isBuiltIn: Boolean = false,
    val createdAt: Long = 0L
)
```

Serialize with `BpmGson.instance` from `core/` — the same instance used everywhere else, so date
handling stays consistent.

**Fields a preset must not carry:** `startTimeMs`, `endTimeMs`, `customRecordColors`,
`overlayVideoUri`, `records`, `graphTitle`. All of these belong to one particular export. Strip
them on save and ignore them on load.

> Migration follows the same discipline as everything else here: verify against the generated
> `TableInfo`, extend `LibraryDatabaseMigrationTest`, never `fallbackToDestructiveMigration`.

### 3.2 The queue becomes durable

`RenderQueueManager` is an in-memory `object`. A job dies with the process, and the app has already
demonstrated it can be killed mid-render on a full device.

Persist queued and in-progress jobs so a render survives a restart, and so "what happened to that
export I started" is answerable.

---

## 4. Suggestions worth taking

Things not in the original description that the current code argues for.

**Size and duration estimate before starting.** `VideoExporter` now refuses a render there is no
room for, but only once you have configured everything. Step 4 should say "about 240 MB, roughly
3 minutes" up front. The bitrate and duration are already known.

**One preview, honestly rendered.** The current preview is a still frame. It should render the same
overlay code at a scrubable instant, so what you see is what the encoder will do — the pill sizing
work is a good example of something that could only be judged by exporting.

**Aspect-ratio-aware graph placement.** Switching canvas from 16:9 to 9:16 currently leaves the
graph rectangle where it was, usually off-canvas. Placement should be stored proportionally and
re-fitted when the canvas changes.

**Remember the last export per source type.** Distinct from presets: the last settings used for a
video and for an image, restored automatically, so the common case needs no choosing at all.

**Export an image sequence or a still from any instant.** The renderer already draws a frame at an
arbitrary playhead. Exposing "export this moment as an image" is nearly free.

**A "what changed" line on each queue entry** — which preset, how many recordings, which event — so
a queue of six exports is distinguishable.

---

## 5. Sprints

### Sprint 1 — Export Utility shell

| Ticket | Work |
|---|---|
| **EXP-1.1** | `AppDestination.EXPORT` + `Routes.EXPORT`, placed next to Render Queue. New `ui/export/ExportUtilityScreen.kt`. |
| **EXP-1.2** | Four-step scaffold with a step indicator and free navigation between visited steps. State in an `ExportUtilityViewModel` that survives rotation. |
| **EXP-1.3** | Fold the render queue in as step 4 and remove its separate drawer entry. Keep the badge behaviour from `AppDrawer`. |
| **EXP-1.4** | Move the existing video and image dialog *content* into step 3 unchanged. No behaviour change yet — this ticket is purely relocation, so the diff stays reviewable. |

**Verify:** every existing export path still produces the same file it did before.

---

### Sprint 2 — Source and contents

| Ticket | Work |
|---|---|
| **EXP-2.1** | Step 1: pick recordings, an event, a group, or a saved analysis. Reuse the library's list components rather than building new ones. |
| **EXP-2.2** | Step 2, clip list: for the chosen source, list every video clip overlapping it, grouped by event, each showing its time and which people would appear. Multi-select, all ticked by default. Built on `VideoExporter.getOverlappingVideos`. |
| **EXP-2.3** | Per-clip recording picker, defaulting to the recordings overlapping **that clip's** timespan rather than the event's. Show each person with their colour. A clip filmed after someone's watch stopped must not offer their curve. |
| **EXP-2.4** | Each ticked clip becomes one queued job carrying its own clip and recording set. |
| **EXP-2.5** | Rewire the existing entry points to open the utility pre-filled at step 3. |
| **EXP-2.6** | Handle the no-clips case: a source with no overlapping video still exports against a solid background, which is what `VideoExporter` already does when `overlayVideoUri` is null. |

**Verify:** a group whose two events have four and one clip respectively offers five, and unticking four of them queues exactly one job with the right recordings.

---

### Sprint 3 — Presets

| Ticket | Work |
|---|---|
| **EXP-3.1** | `ExportPresetEntity`, DAO, migration, and the built-in presets seeded on first run. |
| **EXP-3.2** | Serialization that strips content-specific fields per §3.1. Unit-test the round trip, and specifically that a stripped field does not survive it. |
| **EXP-3.3** | Preset bar in step 3: apply, save as, update, delete, set default. |
| **EXP-3.4** | "Last used" settings per export type, restored automatically when no preset is chosen. |
| **EXP-3.5** | Export a preset to a `.bpmpreset` file and import one, via the same share/open plumbing as `JsonExporter`. Validate on import — an unknown or malformed field is rejected with a message, never applied silently. Version the payload so a preset from a future build says so rather than half-working. |

**Verify:** save a preset, change everything, re-apply it, and confirm the settings return without dragging any record ids or time ranges with them.

---

### Sprint 4 — Settings and preview

| Ticket | Work |
|---|---|
| **EXP-4.1** | Reorganise step 3 into **Canvas**, **Graph**, **Background**, **Overlay** sections. Same options, findable. |
| **EXP-4.2** | Proportional graph placement that re-fits when the canvas aspect changes. |
| **EXP-4.3** | **Scrub any frame of the pending export.** A timeline under the preview; moving it pulls the background frame with `MediaMetadataRetriever.getFrameAtTime` and draws the overlay at the same playhead through `ImageExporter.renderAlignedRecordsOnCanvas` — the identical call the encoder makes per frame. So what is previewed is what will be rendered, and settings can be judged against the busiest moment rather than the first one. |
| **EXP-4.4** | Size and duration estimate in step 4, using the bitrate maths already in `VideoExporter`, alongside available space. |
| **EXP-4.5** | Export a still from the previewed instant — nearly free once EXP-4.3 exists, and inline rather than queued per §2.4. |

**Verify:** switch a 16:9 preset to 9:16 and confirm the graph stays on canvas. Scrub to a moment
where the pills are crowded, export, and confirm the frame matches what the preview showed.

---

### Sprint 5 — Queue durability

| Ticket | Work |
|---|---|
| **EXP-5.1** | Persist the queue so jobs survive process death; reconcile on startup — anything left RENDERING was interrupted. |
| **EXP-5.2** | Retry a failed job without reconfiguring it. |
| **EXP-5.3** | Describe each job — preset, recording count, event — so a queue of six is readable. |
| **EXP-5.4** | Clean up staged renders for jobs that will never complete, alongside the existing startup sweep in `BPMetricsApp`. |

**Verify:** start a batch, force-stop the app, reopen, and confirm the queue reports honestly what happened.

---

### Sprint 6 — App settings

Last, because the export sections cannot be removed until presets replace them.

| Ticket | Work |
|---|---|
| **EXP-6.1** | Apply changes immediately. Delete the staged-edit state and the "Unsaved Changes" dialog from `ui/settings/SettingsScreen.kt` — no other Android settings screen behaves this way, and it is the reason that screen carries a back-handler at all. |
| **EXP-6.2** | Restructure into the groups in §2.5, each an expandable section. Reuse `ExpandableSection` from `ui/components/SharedComponents.kt`. |
| **EXP-6.3** | Remove **Image Export Defaults** and **Video Export Defaults**, superseded by presets. Migrate any existing values into a "Previous defaults" preset on first run so nobody's configuration is silently dropped. |
| **EXP-6.4** | **Appearance**: theme (system / light / dark) and a Material You toggle, wired through `ui/theme/Theme.kt`, which currently calls `dynamicDarkColorScheme` unconditionally. Plus 12/24-hour and date format, applied through `StringFormatHelpers` so one change reaches every screen. |
| **EXP-6.5** | **Library**: default view mode and sort order, read by `LibraryViewModel` on first composition. |
| **EXP-6.6** | **Heart rate**: default resting and maximum, and zone boundaries. Add a per-person override on `PersonEntity` (migration), falling back to these. Consumed by LAH-6.5's time-in-zone. |
| **EXP-6.7** | **Storage**: a breakdown — recordings, staged exports, database, backups — with sizes. "Clear staged exports" calls the existing `ExportUtils.clearStagedExports`. |
| **EXP-6.8** | **Storage**: list the database backups in `files/db_backups` with dates and sizes, and allow restoring one. Restoring must confirm loudly, close the database, swap the file, and relaunch. Currently these are unreachable without `adb`. |
| **EXP-6.9** | **Sync**: what the phone is still missing, a manual "check now" triggering `DataClientProcessor.sweepExistingRecords`, and the retry interval. |
| **EXP-6.10** | **About**: version and build from `BuildConfig`, privacy policy link, licences, and "share diagnostics" bundling recent logs — which would have shortened the disk-full investigation considerably. |

**Verify:** every setting takes effect without a save button; changing the theme is immediate;
restore a backup and confirm the library returns to that state.

---

## 6. Conventions

Same as the Library and Analysis Hardening document — §6 there applies here verbatim. In particular:

1. **Migrations** verified against the generated `TableInfo`, with a migration test.
2. **Colour** always through `PersonColors`.
3. **Staged exports are deleted** once copied to their destination, and on cancellation. This has
   already filled one phone.
4. **Check free space before rendering**, not partway through.

---

## 7. Resolved questions

1. **Presets are exportable and importable** — EXP-3.5. Versioned and validated on import.
2. **A batch lists every clip, per event, individually selectable** — §2.3. The video is the unit
   of export, not the event; a concert with six clips offers six exports.
3. **Images do not go through the queue** — §2.4. They render inline and are offered immediately.
4. **Any frame of a pending export can be previewed** — EXP-4.3. Feasible, and cheaper than it
   sounds: both halves already exist. `MediaMetadataRetriever.getFrameAtTime` supplies the
   background frame and `ImageExporter.renderAlignedRecordsOnCanvas` draws the overlay at a given
   playhead — which is exactly what `VideoExporter` does thirty times a second. The preview is the
   same code path, driven by a slider instead of an encoder.
