# UX Audit — November 2026

A read of every screen on the phone and the watch ahead of 1.0, from the code. Five reviewers each
took one area (library and navigation, detail pages and analysis, export, settings and management,
the watch app); their findings are merged here. The highest-impact claims were checked by hand
against the code and are marked **✓ verified**. A visual pass on an emulator is still to come and
will add what only shows on screen (spacing, contrast, clipping).

Paths are relative to `mobile/src/main/java/inga/bpmetrics/` unless they start with `wear/`.

Severity: **High** = someone loses data, gets stuck, or is told something false. **Medium** =
friction or confusion. **Low** = polish.

---

## 1. Bugs to fix before 1.0

These are functional defects the audit turned up, not matters of taste.

| # | Where | What happens | |
|---|---|---|---|
| 1 | Detail headers — `ui/tags/TagComponents.kt:294-297` | Tapping a tag you set yourself **removes it**. No ✕, no confirm, no undo. Inherited tags look the same and show a toast instead. | ✓ verified |
| 2 | Export — `ui/export/ExportUtilityScreen.kt:309-335`, `ExportUtilityViewModel.kt:1330-1349` | Every visit to the Contents step rebuilds the clip list unticked. Back from Look, tapping the step bar, rotating, or leaving the tab **wipes ticks, per-clip framing, "Filmed at" corrections and hand-added videos**. | ✓ verified |
| 3 | Export — `AndroidManifest.xml:6` | `POST_NOTIFICATIONS` is declared but never requested (targetSdk 36). Render progress, Cancel, and "Export complete" notifications never appear on Android 13+. | ✓ verified |
| 4 | Library filter — `ui/library/FilterChips.kt:103-105` | A collection filter holds a **collection** id but is looked up among **events**. The chip names an unrelated event or is missing — and with no chip there is no Clear, so the list stays narrowed with nothing to remove. Same at `ui/analysis/AnalysisViewModel.kt:923`. | ✓ verified |
| 5 | Library date filter — `ui/library/FilterEditor.kt:874-883` | The picker's UTC-midnight dates are compared to local times. In the Americas "Mar 14" shows as "Mar 13 – Mar 14" and **drops evening shows on the 14th**. `DateTimePicker` already does this right. | ✓ verified |
| 6 | Event analysis — `ui/analysis/AnalysisViewModel.kt:134-153` | Events flagged "excluded from roll-ups" are only excluded once **something else** is unticked, so totals change when you untick an unrelated item. `ScopeRefinement.recordsIn` already does it right. | ✓ verified |
| 7 | Settings — heart rate, retry interval, export time zone | Three settings and the per-person resting/max fields **do nothing**: `HeartRateZones.forPerson` has no caller, and `syncRetryMinutes` / `defaultTimeZone` are read only by the settings screen. Zones come from fixed 100/130/160 bands. | ✓ verified |
| 8 | Settings — `ui/settings/SettingsScreen.kt:618-641`, `SettingsRepository.kt:257,264` | Number fields save and clamp **every keystroke**, and reset to the stored value. Editing resting 60 → 65 stores 30 and the field jumps to "30". A normal value can't be typed. | ✓ verified |
| 9 | Locations — `ui/locations/LocationsViewModel.kt:44-55` | Event counts load once, before the list exists. Every venue shows "0 events" and delete says "Nothing is using this location" for venues that are in use. | ✓ verified |
| 10 | Watch — `wear/.../MainActivity.kt:76-78` | `BPMetricsTheme` is never applied. The watch's colours, type scale and tabular digits are dead code; chips render in stock Wear blue and the BPM digits jitter. | ✓ verified |
| 11 | Watch — `wear/.../ui/Screens.kt:354-374` | Stop is **one tap**, in the same place as Start, using Android's `ic_delete` icon. A bump in a crowd ends the set. "Send now" sits 12dp above it. | ✓ verified |
| 12 | Watch — `wear/.../recording/RecordingRepository.kt:515,535`, `health/HealthService.kt:282-292` | A recording with no readings, a save that throws, or the 30s save timeout all **silently discard** the set. The screen goes "Saving…" → idle with no "Saved" or "Not saved". | reported |
| 13 | Watch — `wear/.../ui/Screens.kt:81-85`, `PermissionsViewModel.kt:36-41` | Denying a permission leaves the wearer stuck with no explanation or route to Settings. Notifications are required, so declining them locks out recording. | reported |
| 14 | Watch — `wear/.../ui/ExerciseCapabilitiesViewModel.kt:33-34` | The "device doesn't support heart rate" check never runs; its error branch is a blank black screen (`Screens.kt:176-178`). | reported |
| 15 | Watches — `ui/watches/WatchesScreen.kt:147, 282-286` | "Register a watch" makes an entry that a real watch can never match, and the merge it promises has no UI. "Apply to past" reassigns **every** recording from that watch, including ones attributed to other people, with no confirm. | reported |
| 16 | Collections — `ui/library/CollectionsList.kt:219-224` | "Stop using a rule" on a card empties the collection with no confirm. The editor offers the safe version ("Keep these N and stop updating"). | reported |
| 17 | Library selection — `LibraryScreen.kt:921`, `LibraryViewModel.kt:678,1406,1425,1439` | With events selected, Edit says "Edit 0 recordings" and tags/attribute/correct time/collection/photo/merge ignore the events, while file/export/analyse include them. "Correct the time…" with only events selected appears later, out of nowhere. | reported |
| 18 | Auto-naming — `library/LibraryRepository.kt:2387-2391, 2494-2498` | Tagging any recording with a tag from the naming category **replaces its title** with "Tag N", including hand-written titles. | reported |
| 19 | Export — `ClipRetiming.kt:110` | Correcting a clip's time to an empty stretch unticks it; correcting it back doesn't re-tick it. The preview still shows it; the render leaves it out. | reported |
| 20 | Export — zero clips ticked | Next stays enabled; step 4 then offers one export of the **whole span** on a plain background — hours, for a collection. | reported |
| 21 | Export presets — `PresetComponents.kt:86-88,124` | Tapping a preset replaces unsaved settings with no undo. After any tweak, "Update" disappears, so changes can only go to a duplicate with the same name. | reported |
| 22 | Analysis — `AnalysisViewModel.kt:574,809-826`, `AnalysisScreen.kt:335-341,1052-1082` | Event and recording pages are labelled "Collection". Every page flashes "Nothing matches this filter" before loading. An event with no recordings yet hides its header, cover and Events tab — the only way down to its days. | reported |
| 23 | About — `ui/about/AboutScreen.kt:101-105` | "Privacy policy: Not yet published." Play requires one. | reported |
| 24 | Restore — `export/BackupRestore.kt:141` | An old-format backup's groups are restored as **events**, but each group's cover is written with `CoverOwner.Collection(id)` — into the collections table, under an event's id. The cover lands on the wrong thing or nowhere. | ✓ |
| 25 | Render queue — `export/RenderJobStore.kt:102`, `ExportPreset.from` (`ExportPreset.kt:464`) | A queued video is persisted through `ExportPreset.from(config)`, which drops the pill settings, clock mode, wordmark and per-lane framing. If Android kills the app before the job runs, it renders with defaults instead of what was queued. | ✓ |
| 26 | Video frames — `export/ImageExporter.kt:770` | The "gap to the end" test compares a **pixel** distance with `GAP_THRESHOLD_MS` (10 000), so it is effectively never true and the dashed gap segment is never drawn. | ✓ |
| 27 | Video frames — `ImageExporter.kt:1044` | A single-recording video takes its title from the recording and ignores the caption, and uses a different BPM range from the multi-recording path the preview uses, so the render doesn't match the preview. | reported |
| 28 | Events and collections — `EventDetailViewModel.kt:162-167` | Events and collections have notes in the database and in backups, but no screen can edit them. | reported |
| 29 | Startup — `BPMetricsApp.openLibrary` (`BPMetricsApp.kt:73-81`) | The guard only constructs `LibraryRepository`; Room opens the file lazily on the first query. A library with no migration path (found on the emulator: schema 2 → 30) passes the guard and then **crashes the app on the main thread**, so the Recovery screen never appears. The guard must force the open (e.g. touch `openHelper.writableDatabase`) inside the `runCatching`. | ✓ reproduced |

---

## 2. Patterns across the app

These show up on many screens; fixing each once is cheaper than fixing every instance.

**Feedback.** Toasts everywhere, sometimes SHORT and sometimes LONG; one screen (Locations) uses a
Snackbar; Tags and most bulk edits give no feedback at all. Toasts can't carry *Undo* or *Open*,
which deletes, tag removal, splits, presets and saves all need. `launchGuarded` swallows every
failure, so a failed rename or delete looks like success (`util/BackgroundScope.kt:67-73`).
→ One Snackbar host per screen, with Undo for destructive actions and Open for things that create
something; route `launchGuarded` failures to it.

**Loading vs empty.** People, Watches, Locations and every analysis page start with `isEmpty = true`
and flash their empty state before data arrives. → A loading state distinct from empty.

**Work on the main thread.** CSV import and export, `looksLikeJson`, image save, Save CSV on the
recording page, diagnostics (`logcat`), and the Recovery screen's database copy. → Move to IO with
a progress indicator; Recovery matters most because it runs when the user is already worried.

**Vocabulary.** The same things go by different names:
- Low/Avg/High vs Min/Avg/Max vs Low/Average/Peak vs Low/Avg/Max — and "Peak" is also a zone name.
- Zones / Time in range / Where the time went / bands.
- Location / Venue / Where it was.
- Smart / Living / Static / Frozen / Saved analysis / Pinned / Save view.
- Add to an event / File into an event; Remove / Delete; Set cover / Set photo; No event / Unfiled.
- What's included / Refine scope / Scope — refined.

Implementation words reach the UI: cast, readouts, roll-ups, lanes, claimed, trailing opacity,
"63% of the width". Title Case survives in Tags, the render queue and a few menus.
→ A short glossary, then one pass.

**Tappable-looking things that don't respond, and the reverse.** Moment rows on the recording page
ripple and do nothing. Clip cards, setting rows, tag rows and refinement rows only respond on the
checkbox or switch, and TalkBack reads the control without its label. Picker rows show a tick but
can't be unticked.
→ `toggleable`/`selectable` on whole rows; remove `clickable` where nothing happens.

**State that resets.** Analysis tab, chart zoom/scrub, export settings sections and the event-page
tab use `remember` rather than `rememberSaveable`, so they reset on back navigation or rotation.

**Back navigation.** Live analysis and the render queue show a hamburger, not a back arrow, and the
render queue's back goes to the Library rather than Export. Breadcrumb taps push duplicate pages.
Export entered from a detail page goes back to a step-1 picker the user never saw.

**Charts.** The timeline is sized in raw pixels (labels ~7–8sp on high-density phones, ignoring
font scale); scrubbing shows a line but no time or BPM; colour is the only link between a curve and
a person; the shaded band has no legend; zoomed-in time labels all read the same minute. The chart
also grabs vertical drags, so the page can't be scrolled over it, and the analysis header is pinned
above the tabs — on a small phone the tabs get under 250dp.

**Accessibility.** Most icons are described; the gaps are colour-only state (isolation by alpha,
selected sources by tint, step bar progress, sort direction), unnamed sliders and switches,
canvas-only controls (scrub bar, framing), and small targets (breadcrumb links ~20dp, colour swatches
28dp, a 32dp picker back button, an 18dp tag delete).

---

## 3. By area

### Library, collections, navigation
- **Medium** — Deleting an event says its N recordings "move back to Unfiled", but only recordings
  filed directly on it do; child events move to the top level (`LibraryScreen.kt:1130-1139, 874-877`).
- **Medium** — "Add to an event…" with an event selected moves the whole subtree's recordings into
  the target instead of nesting the event (`LibraryViewModel.kt:941-951`).
- **Medium** — Select All selects every filtered recording including ones inside collapsed events.
- **Medium** — First run in the default sort says "Give an event a time window…" instead of "No
  recordings yet — start one on a watch" (`TimelineList.kt:87-104`).
- **Medium** — Expanded event cards in the timeline draw an empty band, and their empty copy points
  at a "Recordings view" that no longer exists (`EventComponents.kt:398-415`).
- **Medium** — "New event…"/"New collection…" inside pickers create something empty and drop the
  move or add the user was doing (`LibraryScreen.kt:1123-1126, 1255-1262`).
- **Medium** — The awaiting-selection card says "Tap Select in the bar above", but Select is in the
  overflow; the card can't be dismissed and lingers.
- **Medium** — Collection editor: pin, unfreeze and rule changes apply immediately; the name applies
  on Save; Cancel only reverts the name.
- **Medium** — Collection card text uses no over-cover colour, unlike event cards.
- **Medium** — Event editor is one long scrolling dialog with its validation error at the bottom, so
  a disabled Save looks broken. Its time picker follows the system 12/24h setting, not the app's.
- **Medium** — Incoming transfers appear only in Settings → Sync; the Library shows an 8dp dot.
- **Low** — Selection title can clip beside four icons; Export CSV and Save as file share an icon;
  backup file names use epoch milliseconds; "Successfully exported  CSVs!" has a double space.

### Recording, event and collection pages; analysis
- **High** — No visible route from an event to its recordings: only Compare → Recording chip → lane
  rows (no chevron). Event lanes aren't tappable at all.
- **Medium** — The recording Summary shows zones three times and the peak three times.
- **Medium** — A recording with no readings shows Low 0 / Avg 0 / High 0.
- **Medium** — Tags edited from inside "Edit recording" stick even after Cancel.
- **Medium** — Event subtitles show only the start date, so a 3-day festival reads "Aug 1 · 2:00 PM–11:30 PM";
  event times never name the venue's time zone; header counts ignore the person filter and
  exclusions while the figures respect them; the duration is summed across people and reads like
  the event's length.
- **Medium** — Refining an event (What's included) leaves no sign on the page and is lost on leaving.
- **Medium** — The collection trail joins unrelated events with "›" so they look like a hierarchy, and
  doesn't wrap. "Save analysis" keeps Save disabled until you retype the suggested name, then jumps
  to Collections without saying so.
- **Medium** — Three different ways to focus on a person (Summary card tap, timeline face tap, People
  filter), each reaching different parts of the page, none with a hint.
- **Low** — Split dialog shows "02:13.000" and zero-padded clock times unlike the rest of the app;
  the gap note is in error red; dead code (`AnalysisTrioItem`, `AnalysisBar`, `EventSubjectHeader`,
  `Breadcrumb`, `BpmGradient`) is worth removing before theming.

### Export
- **High** — Starting the same batch twice is easy: leave and return and step 4 offers "Start N" again.
- **Medium** — Step 4 has no review list and no Back button; the step bar lets a new export jump
  straight to steps 3 and 4.
- **Medium** — Look step on a ~640dp phone leaves about 90dp for settings once the preview, scrub
  bar, Back/Next bar and the app's own bottom bar are drawn. → Hide the app nav bar inside export.
- **Medium** — Sync offset (in the preset, all clips) and Filmed at (per clip) fix the same problem
  in two places; nudging Filmed at by a second takes two dialogs. → ± second buttons.
- **Medium** — The preview seeks to the nearest keyframe, so sync adjustments under a second
  mostly don't show (`OPTION_CLOSEST_SYNC`).
- **Medium** — Render queue: cancel without confirm, raw exception text on failure, no "Clear
  finished" (the function exists, unused), no file location on completed jobs, notifications open
  the Library rather than the queue.
- **Medium** — Width/height fields can't be cleared (backspace leaves "1" → a 1px canvas); "Lock
  aspect ratio" does nothing; image crop fields ignore the 12/24h setting and fail silently.
- **Medium** — The default preset always wins over last-used, silently dropping last session's
  tweaks.
- **Low** — Background renders use the `dataSync` foreground service type, which Android 15 caps at
  ~6h/day; `mediaProcessing` fits better.

### Settings, People, Watches, Locations, Tags
- **High** — Two "Restore" buttons do opposite things (database backup replaces, backup file merges).
  Deleting a database backup has no confirm. The pre-restore copy isn't labelled.
- **Medium** — Restoring a backup file says it "adds to this library" but overwrites all settings,
  including an id-valued key from another database. It starts the moment a file is picked.
- **Medium** — All seven settings groups start collapsed; Sync status (diagnostics) is last.
- **Medium** — People edit dialog: Save and a red Remove, no Cancel; body doesn't scroll; removing
  someone unlinks their watches without saying so.
- **Medium** — Tags: blank screen with no empty state; actions give no feedback; tags are inert
  until a hidden edit mode, then delete is an 18dp icon.
- **Medium** — "Use current" location does nothing silently when permission is denied.
- **Low** — The four management screens add, edit, delete and confirm four different ways:

| | People | Watches | Locations | Tags |
|---|---|---|---|---|
| Add | FAB | FAB | FAB | App-bar + |
| Edit | Tap → dialog | Tap → dialog | Tap or overflow | Hidden edit mode |
| Destructive verb | Remove | Remove | Delete | Delete |
| Feedback | Toast | Toast | Snackbar | None |
| Empty state | Custom | Custom (different) | In-list | None |

→ One shared management-list scaffold; `BpmEmptyState` already exists and none of them use it.

### Watch app
- **High** — Recording vs not recording can't be told apart at a glance: BPM shows in the same style
  while idle, and the only differences are a 12sp caption and the button colour.
- **Medium** — No `Scaffold`, `TimeText`, `ScalingLazyColumn` or rotary support anywhere; the
  recording screen can't scroll, so a larger font or a two-line sync message pushes Stop into the
  round crop.
- **Medium** — The Start/Stop button shifts sideways as the timer appears and changes width.
- **Medium** — The most important warnings ("Recording · no signal", "watch off wrist") are in the
  smallest type, in white.
- **Medium** — No ambient mode or tile; the ongoing activity shows only "BPMetrics" and uses a
  full-colour icon that renders as a blob.
- **Medium** — Chip text like "Already sent · waiting on phone" is cut off mid-word; "Could not reach
  the phone" disappears after 4s with no way forward.
- **Low** — Timer reads "1h 5s"; the heart-plus icon loses its "+" when tinted; template strings
  ("From the Square world") are still in `res/values`.

---

## 4. Theme & branding readiness

**Where it stands.** One hand-written dark scheme from a teal seed; dynamic colour is opt-in; no
light theme. `Color(0x…)` appears only in the theme files, inline shapes are almost gone, and the
tabular metric styles are consistent — a good base.

**The biggest visible inconsistency.** The scheme doesn't set `surfaceContainer*`, `surfaceBright`
or `surfaceDim`, so default Cards, dialogs, menus, the drawer and sheets fall back to Material's
purple-grey neutrals on a teal-black app. Meanwhile 11 places fake a container with
`surfaceVariant.copy(alpha=…)` at six different alphas.

**What bypasses the theme.**
- Brand values imported directly (`BpmHigh`, `BpmLow`, `BpmAccent`), ignoring dynamic colour: 61
  uses across 10 files, 26 of them in the render queue. Legitimate for the heart-rate ramp in
  charts; misused as status colours (failed, queued) and attention dots.
- `.sp` literals outside `Type.kt`: 10 sites in 4 files, including a 9sp label.
- `Color.White` for titles over photos in three places — one `OnCoverText` token.
- The person palette's coral, pink and cyan sit next to the "high" red and the teal accent, so a
  wearer's colour can read as a warning or as the brand.
- On the watch, the whole theme is unused (bug #10).

**Suggested order for the theme work.**
1. Generate a full Material 3 dark scheme from the brand seed, every role set explicitly; replace the
   11 `surfaceVariant.copy(alpha)` sites with `surfaceContainerLow/High`.
2. Add an extended-colours object through a `CompositionLocal`: the heart-rate ramp and zones (fixed,
   shared with exports), chart grid, cover scrim and on-cover text, and status tokens (success,
   queued, running, failed, attention) derived from the scheme. Stop importing `BpmHigh`/`BpmAccent`
   in UI code.
3. Fill in `displaySmall` and `headlineSmall`; replace the `.sp` literals with type tokens; choose a
   brand typeface here if wanted.
4. Finish adopting `BpmSpacing` on settings and management screens.
5. Assets: a real launcher glyph (today it's Material's MonitorHeart) with a monochrome layer for
   themed icons, a matching splash, a notification small icon (notifications use system icons
   today), a beta badge variant; delete the unused webp mipmaps.
6. Apply `BPMetricsTheme` on the watch and point its chips and accents at the shared palette.

Steps 1–2 are also what would make a light theme a one-file change later.

**Localisation.** Nearly every string is hard-coded (~300 `Text("…")` literals) and plurals are
built by hand in ~30 places. Fine for an English-only 1.0; centralising plurals during the polish
pass is cheap.

---

## 5. Worth keeping

- Delete confirmations say what is at stake: recordings are permanent; events and collections are
  grouping only.
- Selection mode: explicit Select as well as long-press; taps toggle while selecting; Back and ✕
  exit the same way.
- Empty states that explain themselves (filter-aware, hidden-unfiled).
- The merge dialog previews the result and refuses invalid merges inline.
- Time-zone honesty: recordings and event windows read in their own clock and name it when it differs.
- The person filter: badge, banner on every tab, "Show everyone".
- Export preview uses the real renderer, with full-screen mode and a scrub bar; framing tools with
  pins per number of people; up-front size/time estimate and out-of-space refusal; the render queue
  survives process death.
- Data shown honestly: lines break at sensor gaps, bars start at zero, "Unlabelled" is kept last.
- Restore keeps the replaced database and checks free space first; the last restore result stays
  on screen; Recovery tells a downgrade from corruption.
- On the watch: recording can start before a signal lock; interrupted sessions are salvaged after a
  restart; "Send now" reports what actually happened.

---

## 6. Visual pass — emulators, 2026-11-04

Pixel 7 Pro emulator (Android 13, 1440×3120) with Danny's real library restored from a backup
(287 recordings, 110 events, 12 collections, 11 people), and a Wear OS 6 emulator (384px round).
The second sitting added 1.3× font, a 360dp-wide display, and a Pixel 9 Pro XL emulator on
Android 16 (API 36) carrying a copy of the same library.
Screenshots were taken with adb; findings that confirm §1–§4 are marked *(confirms …)*.

### New bugs

| # | Where | What happens | |
|---|---|---|---|
| 30 | Restore — `LibraryRepository.kt:1682`, `BackupRestore.kt` | A smart collection's rule is restored **verbatim**, but its rule names events, people, tags, venues and watches by **id**, and every one of those gets a new id on import. After a restore the rules point at whatever now has those numbers: "Danny BC" (event 3 + person 2) matched **nothing**, "Colin BC" matched one unrelated recording. A merge into an existing library points them at the wrong things. Affects every friend import. | ✓ reproduced |
| 31 | Phone manifest — `<application>` | No `android:label` on the application (only on the activity), so system dialogs name the app **"inga.bpmetrics.BPMetricsApp"** — seen on the photos-and-videos permission prompt; App info and the notification prompt will read the same. | ✓ reproduced |
| 32 | Phone, every screen | The status bar uses **dark icons on the near-black background**; the clock and system icons are barely visible. | ✓ seen |
| 33 | Restore — Settings → Backup | A 51 MB restore took **about 4 minutes**. The progress shows on the **Back up** button ("Working…"), Restore stays enabled, and there is no count or progress. | ✓ seen |
| 34 | Back up | The picker saved the file as `BPMetrics_Backup_<epoch ms>.bpmjson.json` (the system appended `.json`) in whatever folder it last used — on Danny's phone, *DCIM/Screen recordings*. | ✓ seen |
| 35 | Video export, no clip — `VideoExporter.kt:266-269` | **Every video export without footage fails.** The "plain background" path feeds Media3 an image, and `ImageAssetLoader` requires a frame rate on the `EditedMediaItem` (`checkState(frameRate != RATE_UNSET_INT)`). The `.setFrameRate(config.frameRate)` call was dropped in `135a468` (render queue). The user sees "Export Failed — There was an error encoding '<name>'". Not specific to any Android version. | ✓ reproduced (Android 16) |
| 36 | Backup — `JsonExporter.kt:319-323, 524` | `WatchDto` carries only id, name and model. A restore loses every watch's **wearer**, colour, and first/last-seen dates, and stamps "last seen" with the restore time. After restoring, all 11 watches read "No wearer set", and their next syncs arrive unattributed until each one is reassigned by hand. | ✓ reproduced |
| 37 | Export → Contents, Android 14+ | Choosing **"Allow limited access"** at the photos-and-videos prompt gives a one-time compatibility grant of `READ_MEDIA_VIDEO`. The app reads it as full access, finds nothing among the few items picked, and says **"Nothing on this phone was filmed while these recordings were running"**. That statement is false whenever the footage exists but wasn't picked. | ✓ reproduced (Android 16) |
| 38 | Export step 4 → render queue | Step 4 estimated "about 1.9 GB"; the render's pre-flight check then needed **4,670 MB** and failed within seconds. The job was queued without a warning, and Retry would fail the same way. | ✓ reproduced |

### Phone

- **Surfaces** *(confirms §4)* — default cards, the drawer and the bottom bar are Material's
  purple-grey; the Timeline and Settings cards are a greenish `surfaceVariant` tint; cover cards
  are a third look. Two of these sit one tab apart on the event page.
- **Settings** — expanded groups have no inner padding: text and the Back up / Restore buttons run
  to the card's edges. Chip groups (sort, auto-naming) leave a large gap between rows. Group headers
  are bare labels with no description *(confirms collapsed groups)*.
- **Library cards** — dates spell out the year even for this year and truncate
  ("September 20, 2026, 9:26 PM – 9…"). On busy flyers the blur lets the flyer's own lettering
  compete with the title. Expanded event cards draw an empty band *(confirms)*.
- **Four ways to show the numbers** — event cards "Peak 151 Avg 102"; recording tiles three
  coloured hearts with **no labels** (low/avg/high by colour alone); page headers Low/Avg/High;
  person cards Min/Avg/Max *(confirms vocabulary)*.
- **Event page** — subtitle reads "Collection" *(confirms #22)*; the title is truncated in the app
  bar beside four icons and repeated in the header; a five-day festival reads "August 13, 2026 ·
  9:06 PM–12:23 AM" *(confirms)*; the header says 72h 33m and the timeline 75h 16m for the same
  event; the two Highlights cards ("Highest peak · Danny 184", "Peak came from · Inzo 184") read as
  the same fact.
- **Timeline** — after "Draw the chart" the tab says **"Nothing measured in this scope" for about
  15 seconds** before the chart appears *(confirms loading vs empty, and it is long)*. Axis labels
  are ~7sp *(confirms)*. A multi-day axis has no dates and ticks at odd minutes (9:06 PM, 12:09 PM,
  3:13 AM). Four curves overplot into one mass; Danny's teal and Stina's cyan are near-identical,
  as are Tyler's red and Colin's orange — and Danny's colour *is* the brand teal *(confirms §4
  palette)*. Moments list times with no day.
- **Recording page** — subtitle "Collection · 1 recording"; the peak appears four times (header,
  two Highlights cards, Moments); Highlights make no sense for one recording; zones are "Time in
  range" here and "Where the time went" on the event page.
- **Collections** — every card repeats "Smart — whatever matches, whenever you look".
- **Export** — source rows with a cover start their text at a different inset from rows with an
  icon; the disabled Next floats over the list. On Look, the graph frame is a small box with eight
  handles; readout pills truncate to "Dan…", and two recordings by the same person get identical
  pills; "Readouts" and "Signature" are section names. Settings get under half the screen
  *(confirms)*.
- **People** — the FAB covers the last row; each person's colour is shown twice (bar and ring).
- **Tags** — titled "Manage Tags" (the drawer says "Tags"); category rows are a different
  expandable style from Settings groups and show no counts.
- **Drawer** — no current-screen highlight, no brand mark.

### Watch

- The permission screen's **Grant Access** button runs off the bottom of the round screen.
- Idle shows a large live BPM and a **heart** as the Start button — nothing says "record".
- Recording: Stop is a **red ✕** (reads as discard), the button **slides left** as the timer
  appears, and the timer is a small "6s" off to the side *(confirms #11)*.
- After Stop there is **no "Saved"**; the screen jumps straight back to idle, the layout shifts up
  so the BPM touches the top edge, and "Send now" is stock Wear blue *(confirms #10, #12)*.
- No clock on any screen *(confirms)*.

### Second sitting — the remaining screens, sizes and Android 16

- **Confirmed:** #3 (`POST_NOTIFICATIONS` is declared, never requested, and denied on Android 13,
  so a 2½-hour render runs with no notification); #9 (every location reads "0 events": `init`
  reads `uiState.value` before `stateIn` has emitted. People and Watches hide the same bug with a
  `LaunchedEffect(size)` on the screen); #22 (`describeScope` maps recordings, events and
  hand-picked selections all to `AnalysisScope.Group`, which `scopeSubtitle` labels "Collection");
  #31 (the failure notification is also headed "inga.bpmetrics.BPMetricsApp").
- **Watches** — every card carries "No wearer set" and "Recordings from this watch will arrive
  unattributed" in red, eleven times over. The edit dialog has no Cancel, puts a destructive
  Remove beside Save, and shows "Model: Watch" when the model is unknown.
- **Locations** — the drawer button's description is "Menu" here, "Open navigation menu"
  elsewhere, and "Open navigation drawer" on the render queue.
- **About** — "Version 1.1.0" (`version.properties`) on the branch heading for 1.0; "Copyright ©
  2025"; the privacy policy says "Not yet published".
- **Export step 4** — one line of text and a button in an otherwise empty screen. It doesn't
  say what will be made (format, size, destination), has no Back, and the button reads "Start 1".
  After starting: "1 render queued. They carry on if you leave this screen."
- **Render queue** — opened from Export, it shows the drawer icon, not Back, and tapping the
  Export tab later returns to the queue, not the wizard. A failed job is counted under
  **"Inactive"**; its plain-English reason is hidden behind "Tap to view error details" and set in
  monospace. The failure notification (when allowed) is on a silent channel with a generic message.
- **1.3× font** — the main screens hold. Dates and durations truncate ("October 16, 2026 –
  Octobe…", "1h 10…"), the smart-collection tagline is cut mid-phrase, and a cover card loses
  "recordings" after "223" with no ellipsis.
- **360dp wide (and 360dp at 1.3×)** — the event app bar's four icons leave "Mr Amst…" /
  "Collection · 2 rec…"; the step bar clips "Contents" to "Conten" with no ellipsis; event dates wrap
  mid-time ("9:26 / PM–9:30 PM"); the Settings Restore button runs to the card's edge.
- **Android 16 (Pixel 9 Pro XL, API 36)** — edge-to-edge holds: nothing is drawn under the status
  or gesture bars, and scrolled content clears the gesture bar. Dark status-bar icons persist
  (#32). The **edit-event dialog doesn't move for the keyboard**: Save and Cancel sit under it,
  and the dialog's top meets the status bar. #35 and #37 were found here.
