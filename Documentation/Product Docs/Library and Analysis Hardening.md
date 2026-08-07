# Library and Analysis Hardening

**Status:** design, not yet started
**Sprints:** 6
**Depends on:** People profiles (shipped), concurrent analysis (shipped), release pipeline (shipped)

---

## 1. Context

The library is a flat, undifferentiated list of recordings. That was fine when a recording was one
person on one watch, but it no longer matches how the app is used: several friends wearing several
watches across a weekend produces dozens of recordings whose only relationship — *we were all at
the same set* — the app cannot express.

Concurrent analysis can compare recordings that overlap, but the set has to be hand-picked every
time, and the result is either thrown away or frozen into a saved analysis. There is no durable
object that says "this was Subtronics."

**Goal:** introduce **events** and **event groups** as the organising layer, make the library
viewable at all three levels, and make the analysis screens read well enough to be worth showing
someone.

### Vocabulary

| Term | Means | Example |
|---|---|---|
| **Recording** | One watch, one continuous capture | Kyle's watch, 21:04–21:38 |
| **Event** | One thing that happened, and the recordings of it | "Subtronics" |
| **Event group** | A collection of events | "Coachella 2026" |

---

## 2. Design decisions

Two of these resolve overlaps with things that already exist. Implement them as written — the
alternatives were considered and rejected for the reasons given.

### 2.1 Events are live; saved analyses stay frozen

A saved **concurrent** analysis is already "a named set of recordings over a time window." An event
is the same thing that stays live. Keeping both as first-class concepts would give two ways to
express one idea.

**Decision:** events and groups are the *organisational* layer and always reflect the current
library. Saved analyses remain the *snapshot* layer — a frozen record of what the numbers said on a
given day. Snapshotting an event produces a saved analysis; it does not replace it.

Existing saved concurrent analyses are migrated into events (ticket 3.5) and the "save a concurrent
analysis" entry point is retired in favour of "create an event."

### 2.2 A recording belongs to at most one event

Many-to-many would allow a recording to appear under two sets, which does not correspond to
anything real — a recording happened at one place at one time. One-to-many keeps the assignment UI
a single choice and makes "unfiled recordings" a meaningful category.

Same for events within a group.

### 2.3 The event chart has one lane per *person*, not per recording

This is the visual centrepiece and the thing most likely to be implemented wrongly.

If someone's watch stopped and restarted mid-set, that is **two recordings but one person**. They
must render as **one curve with a gap**, not two curves in two colours. The existing
`ConcurrentAnalysis` builds one `ConcurrentSeries` per record, which would draw them as two
unrelated wearers.

```
        21:00        21:15        21:30        21:45
Kyle    ●━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━●     one recording
Ben     ●━━━━━━━━━━━━━━●    ●━━━━━━━━━━━━━━━━━━━━━●     split — one lane, one gap
Danny            ●━━━━━━━━━━━━━━━━━━━━━━━━━━━━●         joined late, left early
        └──────────────────────────────────────────┘
        earliest start                    latest end
```

The window runs from the earliest start of any recording in the event to the latest end. Lanes
begin and end where that person's data does. Gaps are drawn as breaks, never interpolated across.

### 2.4 The three analyses are one system, not three screens

Individual, event and group analysis answer three questions about the same data — *what did I do*,
*what did we do together*, *what did the weekend look like* — and each is only useful if you can get
to the others from it. Today they are separate destinations that happen to exist.

**They must link both ways.** From a group, reach its events; from an event, reach the recordings
in it and the group it belongs to; from a recording, reach the event it was part of. No screen is a
dead end, and no answer requires going back to the library to find the next one.

**Each must earn its screen.** A chart and a min/avg/max is not analysis. Every level owes the
reader something they could not have worked out by looking: which moment the group reacted to,
whose curve moved most, how this set compared to the last one, how this recording compares to that
person's usual.

**Reading a crowded chart is a feature.** Six curves on one graph is unreadable without a way to
isolate one. **Tapping a person dims the others and brings theirs forward** — the same gesture on
the legend, the lane, or the curve itself. Tapping again restores all of them. This is the single
most valuable interaction on the event screen and should be built with the chart, not bolted on
afterwards.

Concrete requirements, applied at whichever level each makes sense:

| | Individual | Event | Group |
|---|---|---|---|
| Isolate one person | — | tap to highlight | tap to highlight |
| Cross-links | to its event and group | to its recordings and group | to its events |
| Comparison | vs this person's other recordings | vs the other people present | vs the other events |
| Moments | peaks within the recording | peaks the group shared | which event peaked highest |
| Coverage | gaps stated, not hidden | who was recording when | which events have whom |

### 2.5 Tags inherit down; they are never copied

A tag applied to a group or an event applies to everything underneath it. Tag the "Coachella 2026"
group with `Festivals › Coachella 2026`, and every recording of every set inside it carries that
tag for filtering and grouping.

This is what makes nested groups unnecessary. Structure — *this recording was at this set, which
was at this festival* — is the event/group hierarchy. Cross-cutting dimensions — genre, venue,
who you went with, weather — are tags, and a tag can be applied at whichever level it is true at.

**Inheritance is resolved at read time. Nothing is written onto the child records.** Copying the
tag down into `record_tag_cross_ref` would be simpler to implement and wrong in three ways:

- **It drifts.** Move a recording to a different event and its copied tags stay behind, now lying.
- **It has two sources of truth.** Whether a recording was at Coachella would be answerable from
  both the group membership and the tag, and they would disagree eventually.
- **It destroys intent.** A tag added by hand and a tag copied down become indistinguishable, so
  removing the group's tag cannot know which children to spare.

So: a record's **effective tags** are its own, plus its event's, plus its event's group's. One
resolver, used everywhere tags are read for filtering, grouping or display.

Inherited tags are shown differently from directly applied ones — outlined rather than filled — and
can only be removed where they were applied. Otherwise the first thing someone tries is removing an
inherited tag from one recording, which cannot mean anything.

---

## 3. Data model

### 3.1 New entities

`mobile/src/main/java/inga/bpmetrics/library/EventEntity.kt`

```kotlin
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "NULL") val groupId: Long? = null,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    val createdAt: Long = 0L
)

@Entity(tableName = "event_groups")
data class EventGroupEntity(
    @PrimaryKey(autoGenerate = true) val groupId: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    val createdAt: Long = 0L
)
```

**Do not store start/end times on the event.** They are derived from its recordings and would go
stale the moment one is added, removed or re-attributed. Compute them.

### 3.2 Changed entity

`BpmRecordEntity` gains:

```kotlin
@ColumnInfo(defaultValue = "NULL") val eventId: Long? = null
```

### 3.3 Migration 11 → 12

Follow the shape of `MIGRATION_10_11` in `LibraryDatabase.kt`.

1. `CREATE TABLE event_groups`, `CREATE TABLE events`
2. `ALTER TABLE bpm_records ADD COLUMN eventId INTEGER DEFAULT NULL`
3. No backfill — existing recordings start unfiled, which is correct.

> ⚠️ **This project has shipped two migrations whose SQL disagreed with the entities, and a third
> attempt at this very ticket made the same mistake.** Before committing, cross-check the migration
> against `TableInfo` in the generated
> `mobile/build/generated/ksp/debug/kotlin/inga/bpmetrics/library/LibraryDatabase_Impl.kt`,
> and extend `LibraryDatabaseMigrationTest` to cover 11 → 12. A fresh install proves nothing —
> only an upgrade fails.
>
> **The specific trap, all three times:** a Kotlin default (`val createdAt: Long = 0L`) is a
> *constructor* default, not a SQL one. Room expects `createdAt INTEGER NOT NULL` with no
> `DEFAULT`. Writing `DEFAULT 0` in the migration produces a column Room rejects. Only add
> `DEFAULT` to the SQL where the entity carries a matching `@ColumnInfo(defaultValue = …)`.
>
> Compare the generated `createSql` against the migration character by character. It is the one
> check that would have caught every occurrence.

### 3.4 DAOs

New `EventDao` and `EventGroupDao`, modelled on `PersonDao.kt`. Required queries:

- events for a group, ordered by their earliest recording
- recordings for an event
- counts: recordings per event, events per group
- derived window: `SELECT MIN(startTime), MAX(endTime) FROM bpm_records WHERE eventId = :id`
- unfiled: `SELECT * FROM bpm_records WHERE eventId IS NULL`
- assign/unassign in bulk — **chunk ids at 500**, see `LibraryRepository.assignPersonToRecords`
  for why

---

## 4. Screens

### 4.1 Library — three view modes

A segmented control below the top bar: **Recordings · Events · Groups**. Persist the choice in
`SettingsRepository` so the app reopens where the user left it.

| Mode | Shows | Row content |
|---|---|---|
| Recordings | today's flat list, unchanged | existing `BpmRecordTile` |
| Events | events, newest first | name, date, people (colour dots), duration, recording count |
| Groups | groups, newest first | name, date range, event count, people across all events |

Events mode gets an **Unfiled** section below the events when any recording has no event —
otherwise recordings quietly disappear from the view that is meant to organise them. It sits below
rather than above because the view is for the organised library, not the inbox; the suggestion
cards at the top are what keep unfiled recordings from being missed.

Existing filters and multi-select stay available in Recordings mode. In Events and Groups mode the
selection actions collapse to the ones that make sense (analyse, export, delete).

### 4.2 Event detail

The current `ConcurrentAnalysisScreen`, extended. Sections top to bottom:

1. **Header** — name, date, duration, editable
2. **Chart** — person lanes as specified in §2.3, existing pinch-zoom and scrub retained
3. **Readout legend** — per person: colour, name, watch, value at the scrubbed instant
4. **Moments** — the existing group-intensity peaks
5. **Per-person summary** — min / avg / max / time-in-range, one row each
6. **Recordings** — what makes up this event, with the ability to remove one

Actions: export video, snapshot to a saved analysis, edit, delete.

### 4.3 Group detail — the aggregate analysis, upgraded

**A group analysis is not a new screen.** It is the existing aggregate analysis with a group as its
scope instead of a filter. Both answer the same question — "across this set of recordings, what
happened and who did what" — and a user who learns one has learned the other.

So `AnalysisScreen` serves both, and gains everything either needs:

1. **Scope header** — what is being analysed, in words. "Coachella 2026 · 3 events · 12 recordings ·
   14–16 Mar" for a group; the filter stated plainly for a filter. Same shape either way.
2. **Headline stats** — lowest, time-weighted average, highest, and the totals that give them
   meaning: recordings, people, total active time.
3. **Rankings by category** — tabs for Wearer, Watch, **Event**, then each tag category. Every
   comparison the records support, offered the same way.
4. **Rankings by record** — each recording by the selected metric, in its wearer's colour.
5. **Per person** — totals across the whole scope, so "who went hardest all weekend" is answerable.
   Tapping a person dims the others everywhere on the screen, as on the event page.

The Event tab is what satisfies "grouped analysis should have a way to view all the events
associated with that set of records" — and because it is a ranking tab rather than a bespoke
section, a filtered analysis that happens to span events gets it for free.

`AnalysisViewModel` already works from a bare `Flow<List<AnalysisRecord>>` and does not know where
the records came from. A group is one more factory alongside `liveFactory` and `savedFactory`.

> A saved analysis carries no event or person id — `saved_analysis_records` stores names only. So a
> frozen analysis offers the Wearer, Watch and tag tabs but not Event, and falls back to palette
> colours. Persisting them would mean a migration, and Sprint 5 already has one; revisit there.

---

## 5. Sprints

Chronological. Each ticket lists the files it touches and what "done" means. Do not start a sprint
until the previous one's verification passes.

---

### Sprint 1 — Data model

Nothing user-visible. Ends with a schema that supports everything else.

| Ticket | Work |
|---|---|
| **LAH-1.1** | Add `EventEntity` and `EventGroupEntity` as specified in §3.1. New file `library/EventEntity.kt`. |
| **LAH-1.2** | Add `eventId` to `BpmRecordEntity`. |
| **LAH-1.3** | Add `EventDao` and `EventGroupDao` per §3.4. Register both on `LibraryDatabase`, bump `version` to 12, add both entities to `@Database`. |
| **LAH-1.4** | Write `MIGRATION_10_11`-style `MIGRATION_11_12`, register it in `addMigrations`. **Never add `fallbackToDestructiveMigration`.** |
| **LAH-1.5** | Extend `LibraryDatabaseMigrationTest`: add `MIGRATION_11_12` to `ALL_MIGRATIONS`, add a 5→12 chain test, and a case asserting existing recordings survive with `eventId IS NULL`. |
| **LAH-1.6** | Repository methods on `LibraryRepository`: `getAllEvents`, `getEventsForGroup`, `getRecordsForEvent`, `getUnfiledRecords`, `createEvent`, `renameEvent`, `deleteEvent`, `assignRecordsToEvent`, and the group equivalents. Deleting an event unfiles its recordings; it never deletes them. |

**Verify:** `./gradlew build` clean; `LibraryDatabaseMigrationTest` passes on an emulator; generated
`TableInfo` matches the migration SQL column-for-column.

---

### Sprint 2 — Library reorganisation

Ends with events and groups creatable and browsable.

| Ticket | Work |
|---|---|
| **LAH-2.1** | View-mode switcher in `ui/library/LibraryScreen.kt`. Persist the selection via `SettingsRepository`. |
| **LAH-2.2** | `EventCard` and `GroupCard` composables in a new `ui/library/EventComponents.kt`. Show people as colour dots using `PersonColors` — reuse `PersonSwatch` from `ui/components/PersonComponents.kt`. |
| **LAH-2.3** | Events list, including the **Unfiled** section below the events. |
| **LAH-2.4** | Groups list. |
| **LAH-2.5** | Create / rename / delete dialogs for both. Follow the dialog pattern in `ui/people/PeopleScreen.kt`. |
| **LAH-2.6** | "Add to event" in the Recordings multi-select menu, mirroring the bulk wearer flow in `LibraryScreen` + `BulkWearerDialog`. Offer existing events and "New event…". |
| **LAH-2.7** | Suggest events from unfiled recordings: cluster by overlapping or near-adjacent times (gap < 30 min) and offer "Create event from these N recordings". Suggestion only — never auto-file. |

**Verify:** create a group, create two events in it, file recordings into each, confirm all three
view modes render and the counts agree.

---

### Sprint 3 — Event detail

The centrepiece. Ends with an event page that is better than today's concurrent analysis.

| Ticket | Work |
|---|---|
| **LAH-3.1** | New `ui/analysis/EventAnalysis.kt`. Like `ConcurrentAnalysis` but keyed on **person**: group records by `personId`, merge each person's records into one time-ordered lane, preserve gaps. Records with no person each get their own lane, labelled by watch. Unit-test the merge: two adjacent records for one person → one lane with one gap; two people → two lanes. |
| **LAH-3.2** | Render lanes in `ConcurrentChart` (or a sibling). Window = earliest start to latest end across the event. Gaps break the path — reuse the `GAP_THRESHOLD_MS` treatment already in `drawSeries`. |
| **LAH-3.3** | Per-person summary rows: min / avg / max / active duration. |
| **LAH-3.4** | Event screen shell — header, chart, legend, moments, summary, recordings list with remove. Route `Routes.EVENT_DETAIL`. |
| **LAH-3.5** | Migrate existing saved **concurrent** analyses into events, one event per analysis, carrying name and recordings. Retire the "save concurrent analysis" entry point; keep saved analyses for group snapshots. |
| **LAH-3.6** | Video export from the event page, passing the event name as `graphTitle` — the plumbing already exists in `ImageExporter.ImageExportConfig`. |
| **LAH-3.7** | **Tap to isolate.** Tapping a person — on the legend, their lane, or their curve — dims every other curve and brings theirs forward; tapping again restores all. Selection is chart state, so the readout legend and summary rows follow it. Per §2.4, this is the interaction that makes a six-curve chart readable at all, so build it with the chart rather than after. |
| **LAH-3.8** | Cross-links out: to each recording in the event, and up to the group it belongs to. |

**Verify:** an event with a deliberately split recording renders as one lane with one gap, in one
colour. Export a video and confirm the title and colours match the on-screen chart.

---

### Sprint 4 — Group analysis

Per §4.3 this sprint *upgrades* the aggregate analysis rather than building a second screen beside
it. A group is a scope; a filter is a scope; the screen is the same.

| Ticket | Work |
|---|---|
| **LAH-4.1** | `AnalysisScope` replaces `FilterDescription`: what is being analysed, its title, counts and date range, for a filter or a group alike. `AnalysisRecord` gains `personId`, `personColorArgb`, `eventId` and `eventName` — live only, not persisted. |
| **LAH-4.2** | `AnalysisViewModel.groupFactory`, and route `Routes.GROUP_DETAIL` rendering `AnalysisScreen`. Group cards in the Library open it. |
| **LAH-4.3** | **Event** as a ranking category alongside Wearer and Watch, so events rank against each other by the selected metric. Available to any scope spanning more than one event, not just a group. |
| **LAH-4.4** | Navigation: group → event → recording, with back behaving sensibly at each level. |
| **LAH-4.5** | Snapshot to a saved analysis from the group scope, preserving the existing frozen semantics. |
| **LAH-4.6** | Cross-links: a ranking bar opens what it ranks — an event bar opens the event page, a record row opens the recording. Per §2.4 no analysis screen is a dead end. |
| **LAH-4.7** | Per-person totals across the scope, with tap-to-isolate carried over from LAH-3.7 dimming the other rows and bars. |
| **LAH-4.8** | Tidy the screen itself: a real scope header instead of three "All" lines, headline totals, consistent card treatment, and empty states that say what would fill them. |

**Verify:** a group with three events ranks them, the totals equal the sum of the parts, and the
same analysis reached by filtering looks the same as the one reached from the group.

---

### Sprint 5 — Tag inheritance

Makes everything already built on tags work at the event and group level. See §2.4 for why these
are resolved rather than copied.

| Ticket | Work |
|---|---|
| **LAH-5.1** | Tag join tables for the new levels: `event_tag_cross_ref` and `event_group_tag_cross_ref`, mirroring `RecordTagCrossRef`. Migration 12 → 13, with the same verification discipline as LAH-1.4. |
| **LAH-5.2** | `EffectiveTags` resolver in `library/`: given a record, return its own tags plus its event's plus its group's, each marked with where it came from. Single implementation — every read site uses it. Unit-test the three levels and the de-duplication when the same tag appears twice. |
| **LAH-5.3** | Apply tags to an event and to a group. Reuse `TagSelectionDialog` from `ui/tags/`. |
| **LAH-5.4** | Filtering uses effective tags: `LibraryViewModel.applyFilter` matches inherited tags as well as direct ones, so filtering by `Festivals › Coachella 2026` returns every recording underneath it. |
| **LAH-5.5** | Analysis grouping uses effective tags — `AnalysisViewModel`'s tag categories become far more useful, since a festival becomes a category everything falls under. |
| **LAH-5.6** | Display: inherited tags outlined, direct tags filled, with the source named on long-press. Removal only offered where the tag was applied. |

**Verify:** tag a group, confirm every recording under it is returned by that tag filter, then move
one recording to an event outside the group and confirm it stops matching — without anything having
been written to that recording.

---

### Sprint 6 — Naming and presentation

The polish that makes the rest legible.

| Ticket | Work |
|---|---|
| **LAH-6.1** | Recording display names built from metadata rather than "Record 12": `{wearer} · {start time}`, falling back through watch then title. Central helper — do not scatter the logic. |
| **LAH-6.2** | Apply it in the library tile, record detail, analysis rows, export legend, and `AnalysisRecord`. |
| **LAH-6.3** | Filter by event and by group in `LibraryFilterDialog`, alongside the existing person and watch filters. |
| **LAH-6.4** | Empty states for all three library modes, and for an event with no recordings. |
| **LAH-6.5** | Time-in-zone per person — share of active time in configurable BPM bands. Adds the "so what" to the summary rows. |

**Verify:** no screen shows a bare "Record N" where a wearer is known.

---

### Sprint 7 — The single recording as an analysis

A recording's detail page is an *individual* analysis and should carry its weight alongside the
event and group pages. Today it shows a title, min/avg/max, a metadata card, a description, tags
and a small static preview — which says almost nothing about what happened during the recording.

Placed last because it benefits from everything above: the person's colour, its event and group,
inherited tags, and the naming from Sprint 6.

| Ticket | Work |
|---|---|
| **LAH-7.1** | Header rework in `ui/record/BpmRecordScreen.kt`: wearer with their colour, watch, duration, and a breadcrumb to the event and group it belongs to — tapping either navigates there. |
| **LAH-7.2** | Replace the static `BpmGraphPreview` with an interactive chart: scrub to read a value, pinch to zoom, axis labels. **Reuse the chart built for events** rather than writing a third one — see LAH-7.6. |
| **LAH-7.3** | Expand the stat block beyond min/avg/max: active duration, time above/below resting, longest sustained climb, and the recording's own peak moments using the same `findPeaks` treatment as `ConcurrentAnalysis`. |
| **LAH-7.4** | Show gaps honestly. A recording with a sensor dropout should say so — "3 gaps, 4m 12s not measured" — rather than presenting an interpolated line as continuous data. |
| **LAH-7.5** | Context: how this recording compares to that person's others — "their 3rd highest peak", "12% above their average". This is what makes a single recording interesting rather than just a number. |
| **LAH-7.6** | **Unify the chart implementations.** There are two — `ui/graph/` for single records and `ui/analysis/ConcurrentChart.kt` for several — and only the second has zoom, scrub and axis labels. Extract one chart that draws N lanes, and let a single recording be the N=1 case. Prevents a third from appearing next time. |
| **LAH-7.7** | Tidy `ui/graph/BpmGraphDetailScreen.kt`: it currently mixes chart rendering, range selection and export launching in 285 lines. Chart comes from LAH-7.6; export moves out per the Export Utility document. |

**Verify:** open a recording with a known dropout and confirm the gap is both drawn and stated;
confirm the event breadcrumb navigates; confirm the same chart code renders one lane here and
several on the event page.

---

## 6. Conventions for whoever implements this

Non-negotiable, because each of these has already cost this project a bug:

1. **Migrations** — verify against the generated `TableInfo`, extend the migration test, never add
   `fallbackToDestructiveMigration`. Test by installing *over* an existing build.
2. **Bulk id queries** — chunk at 500. Room expands `IN (:ids)` to one bind variable each and
   SQLite caps them at 999; select-all reaches it.
3. **Colour** — always `PersonColors.colorFor`. Never introduce a second palette.
4. **Instrumented tests must run**, not merely compile. They are gated on PRs into `dev`, `staging`
   and `production`.
5. **Comments explain why, not what.** This codebase documents the reasoning behind non-obvious
   decisions; match that.
6. **Warning-clean builds.** `./gradlew build` must produce no new warnings.

### Commands

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio3\jbr"
.\gradlew.bat build                        # compile, unit test, lint
.\gradlew.bat :mobile:connectedDebugAndroidTest   # migrations, needs a device
```

### Branching

Work on `dev`. Promote `dev` → `staging` → `production` by pull request; all three are protected and
require the checks to pass.

---

## 7. Resolved questions

All confirmed:

1. **An event may span midnight or several days.** Nothing enforces a duration.
2. **A group's dates are derived from its events**, never stored — same reasoning as event times.
3. **An event with no recordings survives** until deleted explicitly.
4. **No nested groups.** One level of structure, with tag inheritance (§2.4) providing the
   cross-cutting dimension that nesting would otherwise have been used for.
