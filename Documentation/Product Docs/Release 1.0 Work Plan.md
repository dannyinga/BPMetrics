# Release 1.0 Work Plan

*November 2026. Branch: `product/release-1.0-polish` (cut from `work/release-v1.0`).*

Everything left before 1.0, in order. It covers bug fixes, the consolidation refactor, the UX polish and the theme/branding work. Each sprint is **one commit**: it builds and passes its tests on its own, and leaves the app in a shippable state.

**Sources**
- **Audit #n / §n** refers to [UX Audit 2026-11](../UX%20Audit%202026-11.md). `#` is a bug number in §1; `§` is a section.
- **Plan §n** refers to [Model and Export Consolidation](Model%20and%20Export%20Consolidation.md), which holds the design behind the refactor sprints.
- **Play #n** refers to [Play Store Readiness Audit](../Play%20Store%20Readiness%20Audit.md).

**Rules for every sprint**
- `./gradlew :mobile:testDebugUnitTest :wear:testDebugUnitTest :mobile:assembleDebug :wear:assembleDebug` is green before committing.
- Danny installs builds on real devices. Builds may be installed on the emulators.
- Commit to `product/release-1.0-polish` only, and only when Danny says so. Never push to `staging` or `production`, because those publish builds.
- Before a sprint that changes a Room entity, diff the exported schema. If the diff is unexpected, stop.

---

## Decisions

| # | Question | Decision | Needed by |
|---|---|---|---|
| D1 | Personal resting/max heart rate, the sync retry interval and the export time zone do nothing (Audit #7). Should they be wired up or removed? | **Decided: remove all three for 1.0.** Personal zones would make the stored `zonesEncoded` column stale, so they need a recompute pass; that's post-1.0 work. | S5 |
| D2 | "Register a watch" makes an entry no real watch can match (Audit #15). Should it be fixed or removed? | **Decided: remove it.** Watches register themselves when they first sync. | S18 |
| D3 | A brand typeface, or the system font? | *Open.* Decide during the theme phase. Taxonomy Sprint 6 called it a deliberate non-goal. | S25 |
| D4 | The launcher glyph and brand colour. | *Open.* Danny's call. S26 needs the artwork or a direction. | S26 |
| D5 | Hosting for the privacy policy (GitHub Pages, or a Play-hosted page). | **Decided: GitHub Pages from the repo.** | S28 |
| D6 | How is a B2B set tagged? | **Decided: one tag for each artist, plus one for the B2B itself,** all in the Artist category. | S16A |

---

## Phase A — Clean slate

### S0 — Visual pass *(doc-only commit; needs the emulators)*

This can run any time before Phase F. It needs the Pixel_7_Pro, Pixel_9_Pro_XL_API_36 and My_Watch emulators, and a backup file from Danny's phone.

**Status, 2026-11-04: done.** Findings are in audit §6 (bugs #30–#38) and filed below.
- **Pixel_7_Pro (Android 13), Danny's real library:** every phone screen, including the render queue and Export step 4, at 1.0× and 1.3× font, and at 360dp wide (`wm density 640`) at both font sizes.
- **Pixel_9_Pro_XL_API_36 (Android 16):** created for this pass from the installed API 36 image, with a copy of the same library. Covered edge-to-edge, the keyboard over dialogs, partial media access, and a real render.
- **Watch:** permissions, idle, recording, and after Stop.

| Ticket | Work |
|---|---|
| **S0.1** | Install the debug builds on the emulators and restore the backup. Screenshot every phone screen at the default size, at 1.3× font, and at a small-phone display size (`wm size`/`wm density`). |
| **S0.2** | Screenshot every watch screen: idle, recording, saving, sync states, and the permission screens. |
| **S0.3** | Add the findings to the audit as §6, and file each one into the matching sprint below as a new ticket. |

**Commit:** `docs(audit): visual pass on phone and watch emulators`

### S1 — Dead code

This sprint changes no behaviour, and it shrinks everything that follows.

| Ticket | Work |
|---|---|
| **S1.1** | **Phone UI dead code:** `EventSubjectHeader`, `Breadcrumb`, `AnalysisTrioItem`, `AnalysisBar`, `EventDao.getUngroupedEventsFlow`, and `getEventsForGroup`. Merge `countLabel`/`countLabelPublic` into one function. `BpmGradient` turned out to be live (used by `ConcurrentChart`), so it stays. |
| **S1.2** | **`EventGroupEntity`.** Delete the class (its table was dropped in 27→28) and its reference in `CoverResolverTest`. Keep the backup DTO for groups, because old files still contain them. |
| **S1.3** | **Export dead code:** `renderGraphToBitmap`, `shareBitmap`, `renderMultiRecordsOnCanvas`, `VideoExporter.getVideoCaptureTime` (along with `ExportUtils.parseDateToMillis`, which only it used), both `getOverlappingVideos`, `BpmRanges.snippet*`, and `ExportUtils.adjustAlpha` with its unused import. |
| **S1.4** | **Export service and docs:** delete the unread service flows (`finishedFile`, `isExporting`, `exportProgress`). Move the misplaced KDoc and `@Suppress` from `baseFrame` back to `sanitised()` (`ExportPreset.kt:259-275`). The redundant `getRecordWithId` fetch moved to S13.4, because removing it changes which record the render is named after. |
| **S1.5** | **Resources:** delete the unused webp launcher mipmaps and the template strings in the watch's `res/values` ("From the Square world"). |

**Verify:** the build and tests are green, and a grep for every deleted name returns nothing.
**Commit:** `chore: remove dead code ahead of 1.0`

### S2 — One way to say what happened

This is the feedback layer that later sprints rely on, especially for Undo (Audit §2 *Feedback*).

| Ticket | Work |
|---|---|
| **S2.1** | A `SnackbarHost` in the app scaffold, plus a small `Feedback` API offering `say(text)`, `undoable(text, undo)` and `created(text, open)`. Make the toast length consistent wherever a toast remains. |
| **S2.2** | `launchGuarded` (`util/BackgroundScope.kt:67-73`) reports failures through `Feedback`, so they no longer look like success. |
| **S2.3** | Move the existing toasts on the library, detail pages and Locations over to `Feedback`. Locations' own snackbar becomes the shared one. |

**As built:** `ui/feedback/Feedback.kt` + `FeedbackHost.kt`, with `failed(text)` added alongside the three verbs. S2.3 went further than planned: People, Watches, Settings and Export moved too, because one shared host next to leftover toasts would have been the inconsistency this sprint exists to remove. The only remaining Toast is "Restored. Reopen BPMetrics.", shown as the app closes. `launchGuarded` takes a `failure` text (`null` for background refreshes). Deleting a location offers Undo, which restores the row, its photo, and the events and recordings that pointed at it. Split creates a recording with Open.

**Known:** the shared snackbar sits over the add button on management screens while it shows. That's fixed in S18.1's scaffold.

**Verify:** a failed rename (force one in a test) shows an error, and a delete shows Undo. *Done: `FeedbackTest` covers both; Undo was also checked on the emulator.*
**Commit:** `feat(ui): one feedback channel, with undo, and failures that say so`

---

## Phase B — Phone bugs

### S3 — Nothing destroys work silently

| Ticket | Work | Audit |
|---|---|---|
| **S3.1** | Tapping a tag explains where it came from. Removing a tag uses an explicit ✕ (at least 48dp) and offers Undo. | #1 |
| **S3.2** | Auto-naming only renames titles that are still auto-generated. Hand-written titles are never replaced. | #18 |
| **S3.3** | "Stop using a rule" on a collection card offers the editor's safe version ("Keep these N and stop updating") behind a confirm. | #16 |
| **S3.4** | A watch's "Apply to past" only reassigns recordings that are unattributed or attributed to that watch's previous wearer, and shows a confirm with the count. | #15 (part) |
| **S3.5** | When restoring a legacy group as an event, write its cover to the event, not to the collections table. | #24 |
| **S3.6** | Settings restore: give the two "Restore" buttons distinct names and descriptions, confirm deleting a database backup, and label the pre-restore copy. | §3 Settings (High) |
| **S3.7** | The startup guard forces the database open, so a library that can't be migrated reaches the Recovery screen instead of crashing. Add a test using a schema-2 file. | #29 |
| **S3.8** | Restore remaps the ids inside smart-collection rules (events by their old id, people/tags/venues by name, watches by watch id) instead of copying the rule verbatim. Drop references it can't resolve and report them in the restore summary. Add a test that restores into a library that already has items. | #30 |
| **S3.9** | The backup carries each watch's wearer (by person name), colour, and first/last-seen dates, and restore applies them. Restore never sets "last seen" to the restore time. Bump the backup format version, and keep old files readable. | #36 |

**Verify:** add unit tests for S3.2 and S3.5, and do a manual round trip of a legacy backup on the emulator.

**As built:**
- **S3.1:** the ✕ is a 48dp button beside the chip, not inside it. Tapping the chip explains the tag. Removal offers Undo, which restores the tag exactly, without auto-naming or displacing a sibling.
- **S3.2:** `AutoTitle` counts a title as generated only when it is blank or "<prefix> N", where the prefix is "Untitled" or a naming-category tag. "Ends in a number" would have caught hand-written titles in the real library ("Basket Case 1", "KC to PHX 2").
- **S3.3:** the card's "Stop using a rule" is now "Stop updating…", which confirms with the count and then keeps the members.
- **S3.4:** "Apply to past" also saves the wearer picked in the dialog; before, it changed the past and silently left the future.
- **S3.6:** "Roll back" and "Restore from file". Fixing it turned up a data-loss bug: a roll back copied only the `.db` and dropped the backup's `-wal`, so it lost whatever the library last did. The pre-roll-back copy of the live database had the same problem. Both now copy the companions.
- **S3.7:** `LibraryRepository.ensureOpen()`. The instrumented test `UnmigratableLibraryTest` builds a version-2 file and asserts that opening it fails; it passes on API 36.
- **S3.8 + S3.9:** backup format **7**. Collections carry `sourceId` and `ruleNames`; watches carry their wearer, colour and dates; and people named by a watch or a rule travel too. Restore remaps through `RuleRemap`. An id it can't translate matches nothing rather than everything, and `RestoreResult.rulesNeedingAttention` names the affected collections. **Every rule from a format 6 file (Danny's current backups) is reported, because those files don't say what their people, tag and venue ids meant.** Events still resolve.
- **Tests:** `AutoTitleTest`, `RuleRemapTest`, `BackupRestoreTest` (legacy cover, rule remap into a library that already has the person, old-file reporting, and watch details), plus `BackupCoverageTest`, which now also asserts every `WatchEntity` column.
**Commit:** `fix: stop losing tags, titles, rules and covers without asking`

### S4 — Numbers that tell the truth

| Ticket | Work | Audit |
|---|---|---|
| **S4.1** | The collection filter chip looks up collections, in `FilterChips.kt:103` and `AnalysisViewModel.kt:923`. | #4 |
| **S4.2** | The date filter converts picker dates from UTC midnight to local days, as `DateTimePicker` already does. | #5 |
| **S4.3** | Roll-ups always honour `excludedFromParentAnalysis`. Use `ScopeRefinement.recordsIn` as the single path. | #6 |
| **S4.4** | Locations: event counts follow the list's flow instead of loading once in `init` (which reads `uiState.value` before `stateIn` emits, so it never loads). People and Watches have the same `init`, hidden by a `LaunchedEffect(size)` on their screens. Fix all three the same way, and remove the workarounds. | #9 |
| **S4.5** | Analysis: label pages for what they are (Event, Recording, Collection). The cause is `describeScope` (`AnalysisViewModel.kt:797-826`), which maps recordings, events and selections all to `AnalysisScope.Group`. Show a loading state before "Nothing matches"; an empty event still shows its header, cover and Events tab. | #22 |
| **S4.6** | Selections that include events: Edit counts them, and every bulk action either includes them or explains why it can't. "Correct the time…" is offered only when it applies. | #17 |

**Verify:** add unit tests for S4.2 (across a time-zone boundary) and S4.3 (exclusion with nothing else unticked).
**Commit:** `fix: filters, roll-ups and counts that agree with the library`

**As built:**
- **S4.1:** the chip reads `FilterOptions.collectionEntities`, and the analysis header reads `getAllCollections()`. `FilterChipsTest` had a case that used an event's id as a collection id and passed only because of the bug; it now uses a real collection.
- **S4.2:** `ui/library/PickerDays.kt` converts both ways: picker → local range on Apply, and stored range → picker days on reopen. Ranges saved before this still hold their UTC-midnight instants, and they show as they did until they're picked again.
- **S4.3:** `ScopeRefinement.droppedEventIds` is the one place the rule lives. `recordsIn` and the analysis screen both call it, and the screen now also passes its root, so an event analysed directly is never dropped by its own flag.
- **S4.4:** grouped counts observed as Flows (`eventCountsByLocation`, `recordCountsByPerson`, `recordCountsByWatch`). These are queries only, with no schema change. Every `refreshCounts()` and both `LaunchedEffect(size)` workarounds are gone.
- **S4.5:** `AnalysisScope.Group.kind` (Collection, Event, Recording, Selection) drives the top bar's kind line and the empty-state text. `AnalysisUiState.isLoading` shows a spinner under the subject header. An empty scope keeps its header and section bar, so the Events tab still works.
- **S4.6:** Edit counts both kinds ("Edit 2 events and 3 recordings", plus how many recordings that covers). Tags and photos go on picked events themselves. Attribute covers the recordings inside them. Add to a collection adds events as events; the separate "Add events to a collection…" item is gone. "Correct the time…" appears only when recordings are picked one by one. Merge with events selected says why it can't. The shift dialog's stuck flag, which made it appear "out of nowhere", is cleared.
- **Filing moves events (Danny's call):** "File into an event…" / "Add to an event…" now nests picked events under the target, with everything inside them, and files only the recordings picked one by one. Before, it filed the recordings inside a picked event and left the event and its sets empty. A move that would put an event inside itself is refused and reported. "New event…" from either picker takes the picked events too. Before, "Move events into… → New event…" made the event and moved nothing.

### S5 — Settings that do what they say

| Ticket | Work | Audit |
|---|---|---|
| **S5.1** | Remove the personal resting/max heart-rate fields (from Settings and People), the sync retry interval, the export time zone, and `HeartRateZones.forPerson` (D1). Stored values are left alone and simply no longer read. | #7 |
| **S5.2** | Number fields edit as text and commit on done or when focus leaves, clamping with a visible message instead of on every keystroke. | #8 |
| **S5.3** | Restoring a backup file confirms first and says plainly what it does. It doesn't overwrite settings it shouldn't, such as id-valued keys from another database. While it runs, progress shows on Restore itself (with a count), and both buttons are disabled. Back up suggests a dated name with no doubled `.json`. | §3 Settings, #33, #34 |
| **S5.4** | Open the most-used settings group by default and move Sync status higher. | §3 Settings |
| **S5.5** | The People edit dialog gets Cancel, a scrolling body, and a warning that removing a person unlinks their watches. | §3 Settings |
| **S5.6** | **One entry per watch, whatever build it runs.** (a) `resolveWatch` also matches on the Data Layer node that sent the record. A watch id it hasn't seen, from a node an existing entry already has as `lastKnownNodeId`, files the recording under that entry, which keeps its name, wearer and colour. The entry remembers the new id as an alias so later syncs match directly; that's a schema change, so diff the schema first. This also covers pre-id builds keyed by model name. (b) `WatchDto` carries `lastKnownNodeId` (still format 7, which hasn't shipped), and restore matches on it the same way. Take it out of `BackupCoverageTest`'s excuses. (c) A real "Merge into…" action on a watch, using the existing `WatchesViewModel.merge`, with a confirm that gives the count. (d) The Watches screen suggests "This looks like the same watch as X" for entries with the same model whose first-to-last-seen ranges don't overlap. It's a suggestion that opens the merge confirm, never an automatic merge. No change on the watch, so watches already in use benefit. A factory reset gives a watch a new node, and that case is left to (c). **Verify:** a unit test where a reinstalled watch (new id, same node) lands on its old entry; one where two watches of the same model on different nodes stay apart; and a backup round trip that keeps the node. | #39 |

**Commit:** `fix(settings): fields you can type in, settings that exist, and one entry per watch`

**As built:**
- **S5.1:** removed the Heart rate group, the retry interval, the export time zone, the People resting/max fields, `HeartRateZones` and its test, and `PersonEntity.hasOwnZones`. The columns and preference keys stay. Backups still carry people's figures, so they survive for a later recompute pass.
- **S5.2:** with S5.1 done, Settings has no number fields left. The ticket's bug lived on in the export editor's Width, Height and Visible window fields. They now edit as text and commit on Done or when focus leaves, through `ui/components/NumberEntry`, which clamps to 16–4096 px and 1–600 s and says so under the field.
- **S5.3, plus Danny's preview request:** both ways in (Settings → Restore from file, and Library → Import recordings…) go through `ui/restore/ImportSession`. It reads the file, shows `ImportPreviewDialog` and writes nothing until confirmed.
  - **The preview** comes from `RestorePlanner`, which applies the restore's own matching rules without writing. It's a full-screen list of every item in the file, grouped by kind. Each kind has an "All…" shortcut (add new and skip what's here, add new and replace what's here, or skip all) and opens to a per-item Add / Replace / Skip control. Danny's calls:
    - **Grouping:** per kind, expandable.
    - **Default:** what's here starts on Skip, which keeps yours.
    - **Event matching:** name, parent and creation time.
  - **What each choice does:** `RestoreChoices` carries them into `restoreBackup`.
    - **Skip on something here** keeps it and links the file's references to it.
    - **Replace** writes the file's version over it, though never a recording's readings.
    - **Add a copy** is offered only for events, collections and presets.
    - **Skip on something new** leaves it out. A skipped event's contents go to the nearest ancestor that came in.
    - **Shared rules:** `RestoreKeys` and `EventMatcher` are used by both the preview and the restore, so the two agree.
  - **Event matching:** events are matched by `EventMatcher`, and a restore now keeps an event's `createdAt`; before, it stamped the time of the restore. Restoring a library's own backup therefore does nothing by default. A library that was itself restored before this build (the emulator copy) still has restore-time stamps, so its events show as new.
  - **Collections** match by name and `createdAt`. Replacing one keeps its id, so other rules that name it still work.
  - **Settings** are per item: Keep mine or Use the file's. Only `SettingsRepository.RESTORABLE_KEYS` are offered (wallpaper colours, 24-hour clock, date format, library order), never id-valued or bookkeeping keys.
  - **Progress:** shown as a count in the dialog and on the Restore button, with both buttons disabled.
  - **CSVs** get the same screen: Add or Skip for new recordings, and Skip or Replace (title, notes, tags) for ones already here. Before, every import of the same CSV added it again.
  - **Duplicates within a file:** a recording that appears twice in one file is now saved once.
  - **Backup names:** "BPMetrics backup 2026-11-05.json", so nothing doubles the `.json`.
- **S5.4:** Sync is first and Backup opens by default.
- **S5.5:** the People dialog scrolls, has Cancel, keeps Remove in the body apart from Save, and the remove confirm names the watches that will lose their wearer.
- **S5.6:**
  - **(a) Matching on sync:** schema **31** adds `watch_aliases`, checked against the exported schema before committing. An unknown id is looked up as an alias first, then by node, and is remembered on a match. The alias turned out to be required: Danny's restored library has no node ids, so without it a merge would come undone on the next sync.
  - **(b) Backups:** they carry `lastKnownNodeId` and `aliases`. A restore recognises a watch by id, alias or node, and translates rules that name the file's id.
  - **(c) Merging:** "Merge into another watch…" moves the recordings, re-points aliases, and carries the node and the seen dates over.
  - **(d) The hint:** `WatchDuplicates` compares the recordings' own time spans, since a pre-S3 restore stamped every watch's first/last-seen dates. "Not the same" stores the pair in `distinct_watch_pairs`.
  - **Verified** on the emulator: "The OG" was suggested as "OG", merged, and the alias was written.
- **Tests:** `RestorePlannerTest`, `NumberEntryTest`, `WatchDuplicatesTest`, three more `BackupRestoreTest` cases, the watch node and aliases in `BackupCoverageTest`, and instrumented `WatchIdentityTest` (5 cases) plus `migrate30To31`.
  - **Old fixtures fixed:** four migration tests had failed since August, because their fixtures inserted recordings without the `description` column. They're fixed, and all 70 pass on API 33.
- **Found and fixed in this sprint:** restoring a backup into the library it came from used to add every event and collection again ("110 events, 12 collections" against 287 recordings already here). Fixed by the event and collection matching above. Verified on the emulator: the 12 collections are recognised. Adding a single event from the list brought in exactly that event, with nothing else changed.

### S6 — Export wizard keeps your work

| Ticket | Work | Audit |
|---|---|---|
| **S6.1** | Clip selection lives in the view model, keyed by source. It's rebuilt only when the source changes, so ticks, framing, "Filmed at" corrections and added videos survive Back, step taps, rotation and tab switches. | #2 |
| **S6.2** | Re-timing a clip back into range ticks it again, and the preview always matches what will render. | #19 |
| **S6.3** | With no clips ticked, Next is disabled and the reason is shown. | #20 |
| **S6.4** | A batch can't be started twice: once queued, step 4 shows "Queued, open the render queue". | §3 Export (High) |
| **S6.5** | Step 4 gets a review list (what, format, size, where it will be saved) and a Back button. The step bar can't skip ahead of the furthest step reached. Step 4's size estimate uses the same sum as the render's pre-flight space check (1.9 GB promised vs 4,670 MB required today), and a job that can't fit is stopped at step 4 with the reason, not queued to fail. | §3 Export, #38 |
| **S6.6** | Hide the app's bottom bar inside export, so the Look step has room on a 640dp phone. | §3 Export |
| **S6.7** | Source rows share one text inset whether or not they have a cover, and Next no longer floats over the list. Readout pills that would be identical (two recordings by one person) say which recording each is. | §6 Export |
| **S6.8** | Restore `.setFrameRate(config.frameRate)` on the image-backdrop `EditedMediaItem` (dropped in `135a468`), so a video with no footage renders. Pull the item construction into a function, and cover it with a test that asserts the frame rate and duration are set. | #35 |
| **S6.9** | Partial media access (Android 14+): declare `READ_MEDIA_VISUAL_USER_SELECTED`, and tell full from partial access. With partial access, Contents says "You've shared only some videos", offering "Choose more" and "Allow all", and never says nothing was filmed. | #37 |

**Commit:** `fix(export): the wizard remembers what you did`

**As built:**
- **S6.1:** the clips were already held in the view model. The bug was the Contents step re-scanning on every visit and replacing the list. Now `hasClipsFor(recordIds)` skips a scan that's already been done, and a scan that does run merges by uri, keeping ticks, framing, "Filmed at" corrections and hand-added clips. The export view model lives at the shell level, so all of this also survives tab switches and rotation.
- **S6.2:** `ClipSelection.untickedByTime` remembers a clip that a correction unticked, so moving it back into range ticks it again. Unticking by hand clears the flag. While the clip being previewed won't be exported, the Look step says so in red under the preview.
- **S6.3:** `nextBlockedReason` ("Tick at least one clip to export.") shows beside a disabled Next, and the step bar can't jump forward past it either. With no footage at all, nothing is blocked: the plain background is the export.
- **S6.4:** what was queued is held in the view model as a key (source, clips with their casts, preset, title). Step 4 shows "Queued" for that exact batch, however you come back to it.
- **S6.5:**
  - **Review list:** each video's title, length and size; the format; "saved to Movies/BPMetrics"; and the free space needed against what the phone has.
  - **One size sum:** `VideoExporter.sizing()` is now used by both the render and step 4. The old estimate used the preset's two flat bitrates, which the render never read, and left out the render's 2.5× working space.
  - **Space check:** a batch that can't fit is stopped at step 4 with the reason.
  - **Back:** step 4 now has a Back button; it had none.
- **S6.6:** the shell hides its tab bar on Export once past step 1, where the steps have their own Back.
- **S6.7:**
  - **Insets:** a row with a cover keeps the icon slot as empty space, so every row's text starts at the same inset.
  - **Bottom bar:** step navigation sits on its own tonal surface instead of floating over the list.
  - **Readouts:** `ImageExporter.distinctLabelsOf` tells repeated wearers apart by watch, then by title, then by number.
- **S6.8:** `VideoExporter.editedItem()` sets the frame rate for an image input again, covered by `VideoExporterItemTest`. On the emulator a 3-minute video with no footage rendered: 32 MB, against 62 MB estimated.
- **S6.9:** declares `READ_MEDIA_VISUAL_USER_SELECTED` and requests it alongside `READ_MEDIA_VIDEO`. `VideoExporter.mediaAccess()` tells full, partial and none apart. With partial access, Contents says only some videos were shared and offers "Choose more" and "Allow all"; when nothing is found it says so about the shared videos only. Gaining access re-scans and merges. **Not checked on a device:** partial access only exists on Android 14+, and the API 36 emulator was closed for this run.
- **Verified on the emulator (API 33):**
  - A hand-added clip, unticked, blocks Next with the reason, and the step bar won't jump past it.
  - Ticked, it survives Look → Back.
  - Step 4 shows the review, and stays "Queued" after Back → Next.
  - Both renders (with footage, and the plain background) finished.
  - The test videos were deleted afterwards.

### S7 — Presets and the render queue

| Ticket | Work | Audit |
|---|---|---|
| **S7.1** | Request `POST_NOTIFICATIONS` before the first render, and explain what is lost if it's declined. | #3 |
| **S7.2** | Choosing a preset with unsaved changes asks first. "Update" stays available after edits. | #21 |
| **S7.3** | Last-used settings win over the default preset. | §3 Export |
| **S7.4** | Width and height fields can be cleared while typing. Either make "Lock aspect ratio" work or remove it. Image crop fields follow the 12/24h setting and show errors. | §3 Export |
| **S7.5** | Render queue: confirm before Cancel, add "Clear finished", show the file location on completed jobs, and make notifications open the queue. A failed job is counted as "Failed", not "Inactive", and its reason shows inline in body text (not behind a tap, and not in monospace). Retry re-runs the pre-flight check before queueing. The failure notification uses an alerting channel and gives the reason. | §3 Export, §6 |
| **S7.6** | Change the foreground service type to `mediaProcessing`. | §3 Export, Play #14 |
| **S7.7** | Add `android:label` to the phone's `<application>`, so permission prompts, notifications and App info say "BPMetrics" and not "inga.bpmetrics.BPMetricsApp". | #31 |

**Verify:** on the Pixel_7_Pro emulator (Android 13), where the permission is currently denied, the prompt appears and progress notifications show. Force a failure (an export larger than the free space) and check that the queue and the notification both give the reason.
**Commit:** `fix(export): presets that don't eat edits, and a queue that reports back`

**As built:**
- **S7.1:** before the first render, step 4 asks once in its own words what notifications give: progress, plus word when a render is done or why it failed. Then it shows the system prompt. "Not now" or a refusal still queues the render. From then on the Queued screen says what's missing and offers "Turn on notifications", which opens the app's notification settings.
- **S7.2:**
  - **The preset stays selected through edits.** `presetEdited` compares the look with the stored preset, so changing a setting and changing it back isn't an edit.
  - **What the bar shows:** a note saying what the look was changed from, with "Update to current settings" always in that preset's menu.
  - **Asking first:** while there are unsaved changes, choosing a preset asks before replacing them, with "Use X" or "Keep my changes".
  - **Queue labels:** queued jobs are labelled "Name (edited)".
- **S7.3:** last-used now records which preset it started from, and `startingLook()` puts it ahead of the default. The default is what a first export starts from.
- **S7.4:**
  - **Width and height:** these fields already committed on Done (since S5).
  - **Lock aspect ratio:** typing one side now scales the other, to an even number (`CanvasSize.follow`).
  - **Crop fields:** they show and read times in the 12/24-hour setting, through `ClockEntry`. They accept either clock, pick AM or PM from the recording's span, and give a reason under the field: can't read it, outside the recording, needs the date, needs AM/PM. "From has to be before To" shows under the pair.
  - **Next waits:** on an image's Contents step, Next waits for any of these problems to be fixed.
- **S7.5:**
  - **Cancel and Clear finished:** Cancel asks first. "Clear finished" removes saved and cancelled jobs and keeps failed ones, with their reason and Retry.
  - **Locations:** a finished job shows where it was saved, read from the media store, e.g. "Saved to Movies/BPMetrics/Mr_Amsterdam_….mp4".
  - **Stats:** count Failed and Cancelled separately.
  - **Failure reasons:** shown inline in body text, written by `RenderFailure.describe`.
  - **Retry:** runs `VideoExporter.preflightProblem()` first (clip readable, free space), which the render itself now runs too, and refuses with the up-to-date reason.
  - **Notifications:**
    - Every render notification's tap opens the render queue (`MainActivity.EXTRA_OPEN`).
    - Saved and failed renders go on a new alerting channel, "Finished and failed renders".
    - The saved notification has a Play action.
    - The failed notification gives the reason.
- **S7.6:** the service declares `mediaProcessing|dataSync` and starts as `mediaProcessing` on Android 15 and up. **The audit was wrong about the version:** the type is new in Android 15, not 14, so Android 14 still runs renders as `dataSync`, and the Play Console declaration for `dataSync` is still needed while minSdk is below 35.
- **S7.7:** `android:label="@string/app_name"` on `<application>`; the system prompt now reads "Allow BPMetrics to send you notifications?".
- **Verified on the emulator (API 33):**
  - **Notifications:** the permission was denied and never asked. Start showed the explanation, then the system prompt, and the progress notification appeared on the quiet channel. Tapping it opened the render queue.
  - **Presets:** with a default set, the look was last-used, not the default. Applying a preset, then typing a width of 960, gave 960×540 and showed "Changed from 4 person best". Update was still in its menu. Choosing another preset asked first.
  - **Cancel:** asked first; "Keep rendering" let it finish.
  - **Saved:** the notification on the alerting channel gave the full path, with a Play action.
  - **Forced failure:** I filled the storage after step 4's check, then tapped Start. The queue and the notification both said "Not enough space: it needs about 68 MB free… the phone has 0 MB."
  - **Retry with space back:** rendered.
  - **Retry with too little space:** an older job needing 4.6 GB was refused, and its reason updated.
  - **Clear finished:** left only the failed job.
  - **Clean-up:** the filler file and the test videos were deleted.
  - **Over real footage:** a 36-second Samsung clip from Boogie T rendered over its five recordings, with the saved notification giving its path.
    - **The clip's start time:** "Filmed at" read 12:23:35, matching the filename. That's the stamp read as the last frame, corroborated by the file's modification time.
    - **Copy caveat:** a copy made with `adb push` loses that time, so set it back with `touch -d @<seconds>` and rescan.
- **Also fixed: exports two hours early.**
  - **What happened:** with no venue, an export's clock fell back to the preset's zone, which is the phone's zone frozen into the preset when it was made. All eight presets said America/Los_Angeles, so the Boogie T clip, shown as 12:23 AM in Contents, rendered as 10:23 PM.
  - **The fix:** `exportZoneOf()` falls back to the phone's zone, the clock every screen uses. It's used by the video and image renders and by the crop fields.
  - **The footage's own clock comes second.** `ClipZone` reads the MP4's `mdta` tags directly: Samsung's `com.samsung.android.utc_offset`, or the offset at the end of an iPhone's `com.apple.quicktime.creationdate`. Android's metadata reader exposes neither. It seeks to the `moov` box and never reads the video data.
    - **Order:** `exportZoneOf()` uses the venue's zone, then the clip's offset, then the phone's zone.
    - **Where it's read:** when a clip is found or picked (`VideoClip.zoneId`), and again by the render for a job restored from the queue.
    - **Where it's used:** the Look preview, the render, and the queued video's name.
    - **Not changed:** screens, such as the Contents card, still show the recordings' clock.
    - **Checked on the emulator:** with the emulator switched to Pacific time, Contents showed 10:23 PM, and the preview, the rendered frame and the file name all read 12:23 AM.
  - **Presets no longer have a time zone.** A zone is where something happened, which is a location's business. `ExportPreset.timeZoneId` is gone, and so is the old `default_timezone` setting's migration into a preset. Older preset JSON still carries the key, and it's ignored.
- **Also fixed: the queue reappearing in Export.** Step 4's "Open the render queue" pushed the queue on top of Export, so the queue was saved as part of Export's state. Coming back to Export, even from an event's Export button, showed the queue again. It now switches sections, like the drawer does.

---

## Phase C — Watch

### S8 — The watch never loses a set

| Ticket | Work | Audit |
|---|---|---|
| **S8.1** | Apply `BPMetricsTheme` in `MainActivity`. | #10 |
| **S8.2** | Stop needs a long press (or a confirm), uses a stop icon, and is kept well apart from "Send now". | #11 |
| **S8.3** | Saving ends in "Saved" or "Not saved, here's why". Empty recordings, exceptions and timeouts are never silent. | #12 |
| **S8.4** | A denied permission explains itself and offers the system Settings. | #13 |
| **S8.5** | The heart-rate capability check actually runs, and an unsupported device sees a message instead of a black screen. | #14 |
| **S8.6** | A reading over 250 BPM no longer crashes the watch or destroys the recording. Check first whether this is already fixed. | Play #12 |

**Commit:** `fix(wear): themed, harder to stop by accident, and honest about saving`

**As built:**
- **S8.1:** `MainActivity` wraps everything in `BPMetricsTheme`, so the watch is teal like the phone.
- **S8.2:** tapping Stop opens a "Stop recording?" confirm, with an X to keep recording and a stop square to stop and save. The button has a stop icon (it was the system "delete" icon). "Send now" is hidden while recording, so nothing sits right above Stop, and sending carries on by itself anyway.
- **S8.3:** a stop ends in a line on the watch until it's tapped away or the next recording starts:
  - "Saved · 33s, 34 readings" in teal.
  - Or, in red, "Not saved:" plus the reason (worded in `SaveOutcome`, tested): no heart rate measured; every reading out of range; stopped before anything was recorded; or the write failed, with the error.
  - **Lost recordings fixed:** a stop that Health Services never confirmed hit a 30-second watchdog that called `forceReset()`, deleting the readings without a word. The watchdog now saves what was recorded (`saveWithoutConfirmation`).
  - **No more 30-second wait:** a stop with no exercise of ours running (`endExercise()` now says whether it asked for an end) saves straight away.
- **S8.4:**
  - **A refusal says so:** "Heart rate access is off, so BPMetrics can't record", with "Open Settings" and "Ask again". After two refusals the system stops showing its prompt, and Grant now leads here instead of doing nothing.
  - **Picks up the change:** permissions are re-checked on return, so granting in Settings carries on.
  - **Notifications are optional:** refusing them no longer blocks recording.
- **S8.5:** the capability check was skipped whenever permissions were granted, which is always, since that screen comes first. It now always runs.
  - An unsupported watch gets a message; a check that fails gets the reason and "Try again" (that state was a black screen).
  - The health service starts only once the check has passed.
  - If a recording is already running, a failed check lets the wearer through to Stop.
- **S8.6:**
  - **Watch:** already fixed. Out-of-range readings are dropped as they arrive and again when the recording is saved, so a reading over 250 can't reach the data point's check that throws.
  - **Phone:** CSV and JSON import, and recording split and merge, built data points without that filter, so one bad reading failed the whole import. They skip it now (tested for JSON).
- **Tests:** the watch has unit tests now (JUnit in `wear/build.gradle.kts`), starting with `SaveOutcomeTest`.
- **Verified on the watch emulator (API 36):**
  - **Refusing access:** heart rate access refused twice gave the explanation. On a round screen two full buttons didn't fit, so the actions are compact chips.
  - **Open Settings:** opened the watch's Settings. Granting there and coming back went through the device check to "Ready to record".
  - **Stop:** asked first; "Keep recording" carried on; confirming showed "Saved · 33s, 34 readings"; tapping the line dismissed it.
  - **Clean-up:** the test recording was removed from the watch's outbox, so it won't reach the phone emulator.
  - **Not checked on a device:** the unsupported-watch message (the emulator has the sensor), and the "Not saved" and watchdog paths, which can't be triggered on the emulator.

### S9 — Watch layout and states

| Ticket | Work | Audit |
|---|---|---|
| **S9.1** | Use Wear `Scaffold` + `TimeText` + `ScalingLazyColumn` with rotary input on every screen. | §3 Watch |
| **S9.2** | Recording and idle look clearly different at a glance. Show BPM in a different style when not recording. | §3 Watch (High) |
| **S9.3** | The Start/Stop button keeps its position and width. Warnings ("no signal", "off wrist") use larger, coloured type. | §3 Watch |
| **S9.4** | Sync chips wrap instead of cutting words off. "Could not reach the phone" stays until it's dismissed and offers a retry. | §3 Watch |
| **S9.5** | Timer format ("1h 0m 5s"), a heart icon that survives tinting, and an ongoing activity that shows the timer and uses a monochrome icon. | §3 Watch |
| **S9.6** | Start uses a record symbol and not a heart. The permission screen's button fits inside the round screen. The layout doesn't jump when the "not on phone yet" line appears. | §6 Watch |

Ambient mode and a tile are post-1.0.

**Commit:** `feat(wear): proper Wear scaffolding and a recording state you can see`

**As built:**
- **S9.1:** every watch screen sits in one frame, `WearScreen`: `Scaffold` with `TimeText`, a vignette, a `PositionIndicator`, and a `ScalingLazyColumn` that scrolls with the crown or bezel (`rotaryScrollable`). That covers permissions, the device check, loading, recording and recovery.
- **S9.2:** recording shows a red "● REC 9s" line and the reading in bold white with a red heart. Idle shows the status in that line, and the reading in a smaller, dimmed grey.
- **S9.3:**
  - **The control:** one size and one place in both states. The timer moved out of the row beside it, so Start and Stop both sit at the same spot.
  - **Warnings:** "Watch off wrist", "No signal" and "Finding pulse" have their own line, in amber and larger type.
- **S9.4:**
  - **Sync chips:** full width, and their labels wrap to two lines.
  - **Send failures:** "Could not reach the phone" stays, with Retry and Dismiss; it used to vanish after four seconds.
  - **Placement:** the pending count and the send chip sit below the control, a turn of the crown away.
- **S9.5:**
  - **Timer:** reads "1h 0m 5s" (`Elapsed`, tested).
  - **Heart icon:** `ic_heart` is a single shape that survives tinting; the two-tone `ic_heart_plus` is gone.
  - **Notification:** the ongoing notification and ongoing activity use the heart as a monochrome icon. While recording they show a running timer ("Recording · #duration#" with a `StopwatchPart`, plus the notification's chronometer).
- **S9.6:**
  - **Start icon:** Start is a red record dot on a dark button, and Stop is a white square on red.
  - **Permission screen:** its chip fits the round screen.
  - **No jumping:** the warning line keeps its height when empty, and the "not on phone yet" line sits below the control, so nothing jumps when it appears.
- **Verified on My_Watch (API 36), paired with Pixel_7_Pro:**
  - **Screens:** idle, recording, saved and the permission screen, all fitting the round screen. The control sat at the same spot in each.
  - **Watch face:** showed the white heart while recording.
  - **Sync:** a 34-second recording reached the phone straight away.
  - **Not exercised:** the warnings and the send-failure state; the emulator's synthetic sensor doesn't produce them.

---

## Phase D — Model refactor *(no visible change)*

### S10 — One shape for library items

| Ticket | Work | Plan |
|---|---|---|
| **S10.1** | `Cover` becomes `@Embedded` with explicit column names on `BpmRecordEntity`, `EventEntity` and `CollectionEntity`, replacing 7 fields × 3. The schema diff must be empty. | §1 |
| **S10.2** | Add the sealed interfaces `LibraryItem` (displayName, notes, ownCover, ref) and `TreeItem` (parentEventId, ownLocationId). The three entities implement them. | §2 |
| **S10.3** | `ItemRef` replaces `LibraryRepository.CoverOwner` and maps 1:1 onto `ScopeRef`. | §2 |
| **S10.4** | Stop reading `EventEntity.groupId`. `createEvent` loses the parameter. The column drop is left for the next real migration. | §1 |

**Verify:** schema JSON unchanged; `CoverResolverTest`, `MembershipReconcileTest` and `JsonExporterTest` unchanged and green.
**Commit:** `refactor(library): recordings, events and collections share one shape`

**As built:**
- **S10.1:** the three entities embed `CoverColumns`, which holds the seven columns under their existing names; `ownCover` is `cover.toCover()`.
  - **Why not embed `Cover` itself (Plan §1):** `Cover` has non-null crop fields with defaults, which would have made those columns `NOT NULL` and needed a migration.
  - **Schema is not byte-identical.** A fresh export (after setting the old file aside: Room had kept it) differs in two ways:
    - **Field paths:** they read `cover.path` and so on.
    - **Column order:** Room lists embedded columns last, so the seven cover columns move to the end of each `CREATE TABLE`.
  - **Still no migration:** the `identityHash` is unchanged, and every column's name, affinity, nullability and default is identical. Room checks by name, not position.
  - **Verified on the emulator:** the existing library opened on the new build with no integrity error, and all 70 migration tests pass against the new `31.json`.
- **S10.2:** `LibraryItem` (ref, displayName, notes, ownCover) and `TreeItem` (parentEventId, ownLocationId) live in `LibraryItem.kt`. Records and events are `TreeItem`s; collections are `LibraryItem`s. The members are getters, so Room adds no columns.
  - **Recordings:** a recording's notes are its description, and its displayName is `RecordNameFormatter.displayName` without the live wearer name.
- **S10.3:** `ItemRef` replaces `LibraryRepository.CoverOwner` everywhere, and `ItemRef.scope` maps to `ScopeRef`.
- **S10.4:** `createEvent(name, createdAt)` lost `groupId`, and the column is documented as unread.
  - **Old backups:** restoring a pre-nesting backup used to write the event's `groupName` into that dead column, so the event lost its place in the tree. It now parents the event under its group, as the 23→24 migration does for a live library.
- **Tests:** `BackupCoverageTest`'s excuses now name `cover` once instead of seven columns three times. All unit tests pass.

### S11 — One inheritance walk

| Ticket | Work | Plan |
|---|---|---|
| **S11.1** | Add `Source` and `Inherited<T>`, plus `EventTree.nearest` and `gather`. Write tests that compare them with the current three resolvers on a realistic tree. | §3 |
| **S11.2** | `CoverResolver`, `LocationResolver` and `EffectiveTagsResolver` become thin calls. Delete `CoverSource`, `PlaceSource`, `TagSource` and the three `Effective*` types. | §3 |
| **S11.3** | Remove the bypasses: the inline walk at `EventDetailScreen.kt:106`, and the private `eventCovers` maps in `LibraryViewModel` and `BpmRecordViewModel`. | §3 |

**Commit:** `refactor(library): one nearest-wins walk for covers, places and tags`

**As built:**
- **S11.1:** `Inherited.kt` holds the new pieces:
  - `Source` (OWN, EVENT, ANCESTOR) and `Inherited<T>` (value, source, isInherited).
  - Two walks: `nearest` (first value from own, then up the chain) and `gather` (all values, deduplicated, each credited to the nearest place it appears).
  - Tree conveniences: `EventTree.nearest`, `EventTree.gather` and `ancestryAbove` for any `TreeItem`.
  - Accessors `.tag`, `.cover` and `.location`, so call sites still read as what they hold.
- **Comparison test:** `InheritanceWalkTest` builds a festival → day → set tree, with recordings in the set, on the day and unfiled, plus a tag repeated up the chain. It checked the walk against all three resolvers before they moved onto it, and passes after.
- **S11.2:** `CoverResolver`, `LocationResolver` and `EffectiveTagsResolver` are thin calls to the walk.
  - **Deleted:** `CoverSource`, `PlaceSource`, `TagSource`, `EffectiveCover`, `EffectivePlace` and `EffectiveTag`.
  - **New:** `CoverResolver.of(item, events)` is the one-call cover lookup.
  - **Kept as one-liners:** `resolve` and `forRecording`, which tests and callers still use.
  - **`LocationResolver.forEvent`** still reports an event's own venue as `EVENT`, as it always did.
- **S11.3:** the bypasses call `CoverResolver.of`: `EventDetailScreen`'s inline walk, and the private `eventCovers` maps in `LibraryViewModel.coversByRecord` and `BpmRecordViewModel.cover`.
- **Verified on the emulator:** the library renders covers as before, including a festival's days that have none of their own showing the festival's.

### S12 — One verb per operation

| Ticket | Work | Plan |
|---|---|---|
| **S12.1** | Add `rename(ref)`, `setNotes(ref)` and `delete(ref)` in the repository, with per-type cleanup inside `delete`. Move callers over. | §4 |
| **S12.2** | One `setCover`/`setCoverCrop`/`clearCover(ref)`. Delete the three sets of view-model wrappers, and move the recording cover queries out of `EventDao`. | §4 |
| **S12.3** | `setLocation(ref)`, `addTag(ref)`, `removeTag(ref)` and `tagsOf(ref)`. | §4 |
| **S12.4** | Delete the "group" API: `setEventGroup*`, `createEventGroup`, `getEventGroup`, `*TagToGroup`, `getGroupTagsForBackup`, `Routes.GROUP_DETAIL` (renamed to a collection route) and `ExportSource.Group`. | §4 |

**Commit:** `refactor(library): one repository verb per operation`

**As built:**
- **S12.1:** `rename(ref)`, `setNotes(ref)` and `delete(ref)` take an `ItemRef`, and the per-type `when` lives once, in the repository.
  - **Notes:** a recording's notes are its description.
  - **Delete:** keeps each type's cleanup. A recording takes its readings; an event releases its recordings; a collection drops its links, and frozen rows cascade.
  - **Removed:** the 14 functions they replace, including the unused `renameSavedAnalysis` and `deleteSavedAnalysis`.
- **S12.2:** the repository's `setCover` names the stored file from the item itself, so callers no longer look up a hint.
  - **Shared actions:** `ui/components/CoverActions.kt` holds `setCoverOn`, `cropCoverOn` and `clearCoverOn`, which work from any page's view model.
  - **`LibraryViewModel`:** has one `setCover`/`setCoverCrop`/`clearCover(ref)`, used by the library, collections, collection page and event page.
  - **The recording page:** calls the shared actions with its own reload.
  - **Removed:** the event page's three wrappers, the recording page's three and six in `LibraryViewModel`.
  - **DAO:** the recording cover queries moved from `EventDao` to `BpmRecordDao`, as `updateCover` and `coverPathOf`.
- **S12.3:** `setLocation(ref)`, `addTag(ref)`, `removeTag(ref)` and `tagsOf(ref)` take a `TreeItemRef`, a new sub-interface of `ItemRef` covering recordings and events.
  - **Collections can't hold them:** passing one doesn't compile.
  - **`TreeItem.ref`:** narrowed to `TreeItemRef`.
  - **`addTag`:** returns the tag a recording displaced (one per category), and null for an event.
- **S12.4:** the "group" API is gone: `setEventGroup`, `setEventGroupParent`, `setEventGroupNotes`, `getEventGroup`, `createEventGroup`, the `*TagToGroup`/`getTagsForGroup` functions, `allGroupTags` and `getGroupTagsForBackup`.
  - **Old backups:** restoring one makes its groups as plain events with `createEvent`.
  - **Renames:** `ExportSource.Group(groupId)` is `ExportSource.Collection(collectionId)`, and `Routes.GROUP_DETAIL` is `Routes.COLLECTION_DETAIL` (`collection_detail/{collectionId}`). `LibraryViewModel.setEventGroup` is `setEventParent`.
- **Verified on the emulator:** a collection opens on the new route; Export from it starts with that collection, and an image export still offers "One image per event".

---

## Phase E — Export refactor

### S13 — The preset is the look

| Ticket | Work | Plan / Audit |
|---|---|---|
| **S13.1** | Add `ExportContent` (records, window, title, colours, photos, time zone) and the sealed `ExportRequest` with `Image` and `Video`. | Plan §6 |
| **S13.2** | Renderers take `(ExportContent, ExportPreset)`. Delete the look fields of `ImageExportConfig` and `VideoExportConfig`, `TimelineImageExporter.Spec`, `applyTo`, `ExportPreset.from`, and the 3 separate config builders. | Plan §6 |
| **S13.3** | A render job stores `preset.toJson()` plus the content, so nothing is lost across process death. Add a round-trip test. | #25 |
| **S13.4** | Delete `headerXPercent`, the unreachable elapsed-time alignment, and the bit-rate fields if the encoder keeps choosing its own rate. Old preset JSON still reads, because Gson ignores unknown fields. Add a test for that. The render service takes its primary record from the request instead of re-fetching it with `getRecordWithId`. | Plan §6 |

**Commit:** `refactor(export): one look, one content, two kinds of request`

**As built:**
- **S13.1:** `export/ExportRequest.kt` holds `ExportContent` (records, window, title, colours, photos, the clip's zone) and `ExportRequest.Video` (content, preset, clip, clip start).
  - **Departure: no `ExportRequest.Image`.** The timeline image's content is already-laid-out series, built by the view model per image, so its `Spec` keeps its content fields and takes the preset as `look` (below).
- **S13.2:** `ImageExportConfig` and `VideoExportConfig` are gone, and so are `ExportPreset.applyTo` and `ExportPreset.from`.
  - **Video-frame renderer:** takes `(content, look)`. Inside, a small `Frame(content, look, zoneId)` carries them and the resolved zone; nothing is copied.
  - **Video render:** takes an `ExportRequest.Video`.
  - **Timeline image `Spec`:** the fifteen copied look fields became `look: ExportPreset`.
  - **Config builders:** the view model's `buildConfig` (now `buildRequest`) and the Look preview's own copy build content and read the preset. The third builder was the job store's, below.
  - **Framing:** the request's preset is fully resolved for that video. The clip's framing is baked in and the per-cast pins cleared, so `frameFor` answers the frame it uses and a stored job frames exactly as queued.
- **S13.3:** a job stores `request.preset.toJson()` and content columns, with no schema change.
  - **Round trip:** `RenderJobStoreTest` saves and restores a job through a fake DAO. Its readout pills, clock mode, wordmark, framing, sync offset and frame rate come back identical (Audit #25).
  - **Not stored:** photos, as before.
- **S13.4:** `headerXPercent`, `overlayBitRate` and `regularBitRate` are gone from the preset; the renderers read none of them, and the encoder sizes each render itself. The unreachable elapsed-time alignment is gone too.
  - **Old presets:** JSON with those keys still opens and doesn't carry them on (tested).
  - **Render service:** takes the primary recording from the request instead of fetching it again by id.
- **Also fixed: the plain background.** A video with no footage was drawn on a 128×128 backdrop, fitted into a 16:9 video with black bars either side. It's now the video's own shape.
- **Verified on the emulator:**
  - **Preview and render agree:** a no-footage video of Mr Amsterdam matched the preview (framing, both readout pills, clock, wordmark), and its background fills the frame.
  - **Image:** an image export renders from the preset.
  - **Clean-up:** the test files were deleted.

### S14 — Renderers that agree with each other

| Ticket | Work | Plan / Audit |
|---|---|---|
| **S14.1** | Rename `ImageExporter` → `VideoFrameRenderer` and `TimelineImageExporter` → `ImageRenderer`. | Plan §6 |
| **S14.2** | Add `ChartKit`, one place for the BPM range, grid lines, person colour, wearer label, `ellipsize`, paint setup, scale factor, gap constant and zone resolution. Both renderers use it. | Plan §6 |
| **S14.3** | Single-recording video uses the caption and the shared range, so the render matches the preview. Fix the pixel-vs-ms gap check. | #26, #27 |
| **S14.4** | One `MediaStoreSaver` for images and video, which cleans up after a failed save. One filename sanitiser. | Plan §6 |
| **S14.5** | Add a reference-bitmap check per renderer: the same input gives the same pixels within tolerance. | Plan §6 |

**Commit:** `refactor(export): shared chart kit, honest names, one way to save`

**As built:**
- **S14.1:** `VideoFrameRenderer` and `ImageRenderer`, renamed with history kept, and their tests with them (`VideoFrameAlignmentTest`, `VideoFrameRankingTest`, `ImageRendererSpecTest`).
- **S14.2:** `export/ChartKit.kt` holds the BPM range, gridlines, colour, wearer label and distinct labels, `ellipsize`, paint, scale, zone and gap constant. Each renderer's private copy is deleted.
  - **One range:** padded, at least 20 bpm tall, padding kept within 30–220, then widened to whole gridline steps. The picture's gridlines are now round numbers, as the video's already were.
  - **Colours:** the image builder uses `ChartKit.colourFor`, so a recording nobody is assigned to gets a palette colour by position in a picture too.
  - **Scale:** off the short edge, 1 at 1080. A square video used to measure itself against a 1920 width and drew at 0.28× in a half-width frame, against 0.5× for portrait. It now matches portrait (see the note below).
- **S14.3:** the single-recording video path (`renderOnCanvas` and its own range) is deleted. Every video goes through the aligned path, so one recording is drawn exactly as the preview draws it (Audit #27).
  - **Gap check (#26):** the line to the end of the plot compared pixels with milliseconds. It now converts the pixels to time first.
- **S14.4:** `export/MediaStoreSaver.kt`, `saveVideo` and `saveImage` over one `save`. Both now delete their gallery entry if writing fails; the video saver used to leave a hidden pending one behind.
  - **One file name rule:** `FileNames.of(title)` keeps letters in any language ("Café Tacvba", which the old ASCII rule turned into "Caf__Tacvba"), digits, `-` and `_`, and caps the length at 80. It replaced five copies in two variants, plus the suggested names for CSV and preset saves.
- **S14.5:** `RendererReferenceTest` (androidTest) draws a fixed two-person input with each renderer and compares it with `src/androidTest/assets/reference/`. A pixel counts as different past 8 steps in any channel, and the test fails past 0.5% of pixels. The references were recorded on Pixel_7_Pro, checked by eye, and the test re-records a missing one.
  - **Also:** `MediaStoreSaverTest` checks that a picture saves and that a failed video copy leaves nothing behind, and `ChartKitTest` covers the range, colours, labels and file names.
- **Note for later: readout names on narrow frames.** At the corrected square scale, a half-width frame gives the pill's name about 29 px, so "Danny" shows as "D…" and "Bethany" not at all. A portrait canvas with the same frame was already doing this. The pill's width budget (22% of the frame) doesn't grow with its height cap.
- **Verified:** all unit tests and both apps build, and 5 device tests pass on the emulator.

### S14A — An overlay that fits what it's given *(Video Overlay and Exporter UX Audit)*

| Ticket | Work | Audit |
|---|---|---|
| **S14A.1** | The pills always fit: more columns or smaller pills before they overflow, never dropping a person. Pill width from what it says, so names show on 9:16. | O1, O4 |
| **S14A.2** | The vertical scale follows what's on screen (eased), with the whole recording as a choice. | O2 |
| **S14A.3** | Labels and gridlines thin, then the time axis drops, instead of overlapping. Titles shrink before they're cut. A readable floor for all text. | O3, O4, O6 |
| **S14A.4** | Contrast for curves and text over footage. | O5 |
| **S14A.5** | "Match the clip" canvas, fill or fit for fixed shapes, a bigger default frame, one pill style. | O6, O7, O8 |

**Commit:** `feat(export): an overlay that fits what it's given`

**As built:**
- **Before the sprint,** two fixes from checking the audit against real footage landed on their own:
  - `a7c21cb`: clips start from the camera's file name. Samsung stamps when saving ends, about 0.8 s late.
  - `e307412`: the preview skips footage from before the press, as the render does, shows the exact frame once the playhead rests, and its length matches the render's.
- **S14A.1:** `export/ReadoutLayout.kt`, a pure plan.
  - **The rule:** the fewest columns whose rows stay readable; past that, the tallest rows shrunk until they fit. Places fill column by column, so the fastest heart is top left of the block, and a pill changing places slides between them.
  - **Sized against the plot.** The pills used to be sized against the whole panel and stacked from the top of the plot, which is how they ran off the bottom.
  - **Names:** the plan measures the width whole names need, flat or stacked, and shrinks rows to fit them; names are only cut when that would go below readable. Name text is held to the readable floor.
  - **`ReadoutLayoutTest`:** 1–8 people fit, a short lower third fits five, the switch to two columns, shrinking instead of dropping, anchoring, and sliding between places.
- **S14A.2:** `ChartKit.windowRange`, the snapped range of the readings in the window, averaged over the second around the playhead.
  - **Smooth and repeatable:** the range glides, and the same frame always gets the same scale, which the render needs since frames are drawn out of order.
  - **The setting:** `ExportPreset.bpmScale`, "What's on screen" (default) or "Whole recording". Stills keep the whole range.
- **S14A.3:** the panel's bands are sized from the text in them. They were fixed allowances: 120 for a 48-unit title, 140 for three-digit labels.
  - **The readable floor:** all text is at least 2% of the canvas's short edge (`ChartKit.readableText`).
  - **What gives way first:** labels thin to every second or third line when they'd touch, and gridlines thin the same way. The time axis goes first when keeping it would leave a plot under 3.5 label heights. A title shrinks to 70% before it's cut.
- **S14A.4:** `ExportPreset.contrast`: Auto, Off, Soft shadow or Outline. Auto means a shadow when the panel is under 30%.
  - **What gets it:** a dark halo under the curves (faded with them), a ring behind the playhead dot, and a shadow or edge on the title, clock and axis labels.
  - **Existing presets:** they pick up Auto and "What's on screen" (version 5; missing values are filled in on read).
- **S14A.5:**
  - **"Match the clip":** `ExportPreset.matchClip` and `forClip`. A video takes its clip's shape, rotation applied, with its short edge at the preset's or the clip's, whichever is smaller. Resolved once each in the request, the preview and the estimate, so the render and the queue only see real sizes.
    - It's the first built-in, so it's the default on a fresh install. An upgrade offers it but doesn't change the default (`BUILT_IN_REVISION` 3).
    - Clips carry their size (`VideoExporter.shapeOf`).
  - **Fill or fit:** `fillCanvas` crops footage of another shape instead of adding bars.
    - The preview now fits or fills the way the render does. It used to stretch the frame over the whole canvas, so the bars only showed up in the export.
  - **Default frame:** 0.06–0.94 across, 0.58–0.93 down; the old default is added to `SUPERSEDED_FRAMINGS`. "Landscape · lower third" starts at 0.64, not 0.68, for new installs.
  - **One pill style:** every pill has a rim, in the wearer's colour with several people and quietly in the label colour with one.
  - **Controls:** "Match the clip" with the Canvas chips, a Fill switch, and Scale and Contrast choices in Graph (video only).
- **References:** `RendererReferenceTest` now draws every built-in at 1, 3 and 5 people (half size, 18 images), and the video-frame reference is re-recorded. All recorded on Pixel_7_Pro and checked by eye.
- **Checked against real footage:** the Granada clip at five people on every built-in, the Gorge, laser and arena clips on the story looks, and a portrait clip rendered matched, which came out 540×960 and upright.
- **Verified:** all unit tests pass. The export device tests pass on the emulator. Lint shows nothing new (the three `RenderFailure.kt` errors predate this).
- **Left for S14B:** a text size control, colour controls for the grid, labels and ramp, and the editor itself. Title wrapping was skipped: a title shrinks, then is cut.

---

## Phase F — One component per job

### S15 — Detail pages

| Ticket | Work | Plan / Audit |
|---|---|---|
| **S15.1** | One `ItemDetailViewModel(ref)` for recording, event and collection pages. Collections stop borrowing `LibraryViewModel`. | Plan §5 |
| **S15.2** | One `CoverEditing(ref)` (picker, crop, clear), and one subtitle and count builder. | Plan §5 |
| **S15.3** | A visible route from an event to its recordings, and tappable event lanes. | §3 Detail (High) |
| **S15.4** | Event subtitles show the full date span and name the venue's time zone. Counts respect the person filter and exclusions. Duration reads as the event's length, and the header and timeline agree on it (72h 33m vs 75h 16m today). The title isn't repeated in both the app bar and the header. | §3 Detail, §6 |
| **S15.5** | A refined scope ("What's included") shows on the page and survives leaving it. A recording with no readings shows "No readings", not 0/0/0. The recording Summary stops repeating the zones and the peak. Highlights are hidden when they can only restate the header (a single recording), and the two peak cards are merged into one. | §3 Detail, §6 |

**Commit:** `refactor(detail): one detail view model, one cover flow`

### S16 — Editors

| Ticket | Work | Plan / Audit |
|---|---|---|
| **S16.1** | One `ItemEditorDialog` built from shared fields (`NameField`, `NotesField`, `TagsButton`, `LocationField`, `CoverSlot`), with a type-specific section for each kind. | Plan §5 |
| **S16.2** | Events and collections get notes. | #28 |
| **S16.3** | One location widget instead of two. | Plan §5 |
| **S16.4** | Cancel means cancel. Collection pin, freeze and rule changes, and tags edited inside "Edit recording", apply only on Save. | §3 Library, §3 Detail |
| **S16.5** | The event editor shows validation errors next to the field and at the top, and its time picker follows the app's 12/24h setting. | §3 Library |
| **S16.6** | The editor dialog's body scrolls, and its actions stay above the keyboard (`imePadding`) and below the status bar. On Android 16 today, Save and Cancel sit under the keyboard. | §6 |

**Commit:** `refactor(ui): one editor with shared fields`

### S16A — Artists from event names *(Danny, 2026-11-05)*

Most Concert events are already named after their artist, but only some carry an Artist tag. In Danny's library, of 76 Concerts, the Bass Canyon sets are tagged and none of the Dancefestopia sets are. Tagging means typing the name a second time. This sprint reads the tag from the name instead. It comes after S16 because the suggestions live in the single editor that sprint builds.

**Rule for B2B sets (Danny's call):** a B2B set gets a tag for each artist, *and* one for the B2B itself, all in the Artist category. "Excision B2B SLANDER" is tagged Excision, SLANDER and "Excision B2B SLANDER". Each artist's numbers include the set, and the pairing can still be compared on its own.

| Ticket | Work | Source |
|---|---|---|
| **S16A.1** | **An event type names the tag category it expects.** Concert and Show expect Artist, and any type can be given one (Soccer Game → Team). The mapping is set from the event editor's type field ("Concerts are tagged with: Artist ▾"). It needs storage, either a small table or a stored setting. If it's a table, diff the schema first and give it a place in the backup (`BackupCoverageTest`). Nothing is hard-coded: a type with no category gets none of what follows. | Danny |
| **S16A.2** | **Read the artist from the event name.** A pure `ArtistNames.parse(name)`, unit-tested on Danny's real names: "Excision B2B SLANDER", "Wooli B2B Crankdat", "Excision (Detox)", "SVDDEN DEATH: VOYD", "Boogie T @ The Granada", "Air Quotes B2B It'sNotImportant", "Inzo". Rules:<br>• Split on B2B, b2b, " x ", " & " and " vs ".<br>• Drop a trailing "(…)" and anything after ": ". Drop anything after " @ ", which can also offer a venue match.<br>• Match existing tags in the type's category, ignoring case, so "Inzo" finds INZO.<br>• A B2B produces each artist plus the B2B tag (rule above).<br><br>When an event of a typed category is named or renamed, an exact match to existing tags is applied straight away, with Undo through `Feedback.undoable`. Anything new is offered as one-tap chips in the editor ("Add SLANDER as an artist"). Tags are put on the event, so its recordings inherit them. | Danny |
| **S16A.3** | **"Missing artists": fixing what's already there.** Reached from the Tags screen, and from a count on it ("31 concerts have no artist"). It lists every event whose type expects a category but carries no tag from it, each with the S16A.2 suggestion as chips. Tap to apply one, or "Apply all suggestions", with one Undo for the batch. The same screen offers cleanup:<br>• Tags that differ only in capitals (INZO / Inzo) → merge into one.<br>• Existing single-tag B2Bs ("Excision B2B SLANDER") → add the separate artist tags, keeping the B2B tag.<br><br>Every change shows a count and asks before it runs. | Danny |
| **S16A.4** | **Name a set by picking its artists.** In the editor, for a type that expects a category, choosing artists first fills in the name: "Wooli B2B Crankdat" for two, the artist's name for one. The name stays editable, and once it's been typed over, picking artists no longer changes it. This is S16A.2 run the other way, so typing and tagging become one step. | Danny |

**Verify:**
- **Unit tests:** `ArtistNames.parse` on the names above, including case-only matches and a name with no artist in it.
- **The fix-up pass:** applying "Apply all" to a copy of Danny's library tags every Dancefestopia set with nothing typed.
- **Undo:** after an auto-applied tag, Undo leaves the event as it was.

**Commit:** `feat(tags): concerts tag their artists from their names`

*Not in this sprint:* pasting a festival's lineup to create its sets with their artists and times (idea 4), and choosing the set on the watch (idea 6). Both are candidates for after 1.0.

### S17 — Library cards and library flow

| Ticket | Work | Audit |
|---|---|---|
| **S17.1** | One `LibraryCard` skeleton. The record, event and collection cards fill its slots. Collection text gets the over-cover colour. Stats are labelled the same way on every card (no colour-only hearts). Dates use a compact form that drops the current year and doesn't truncate. Covers blur enough that flyer lettering doesn't compete with the title. The "Smart — whatever matches…" line becomes a small badge. | Plan §5, §3 Library, §6 |
| **S17.2** | Deleting an event says what really happens to its recordings and child events. "Add to an event" with an event selected nests it. | §3 Library |
| **S17.3** | "New event…" and "New collection…" inside pickers finish the move or add the user started. | §3 Library |
| **S17.4** | First-run copy and the copy for expanded empty events. The awaiting-selection card can be dismissed and points at the right control. Select All respects collapsed events. | §3 Library |
| **S17.5** | Incoming transfers are visible from the Library, not only behind an 8dp dot. | §3 Library |

**Commit:** `refactor(library): one card, and library actions that finish what they start`

### S18 — Management screens

| Ticket | Work | Audit |
|---|---|---|
| **S18.1** | A shared management-list scaffold for People, Watches, Locations and Tags. It provides FAB add, tap to edit, one destructive verb, `Feedback`, and `BpmEmptyState`. The FAB moves up while a snackbar is showing (since S2 the shared snackbar covers it). | §3 Settings table |
| **S18.2** | Move the four screens onto it. Tags gets an empty state and drops the hidden edit mode. The scaffold leaves room so the FAB never covers the last row. Tags is titled "Tags" and its categories show counts. Watches: replace the red "will arrive unattributed" line repeated on every card with one banner ("N watches have no wearer"). The watch dialog gets Cancel, keeps Remove apart from Save, and hides "Model:" when the model is unknown. | §3 Settings, §6 |
| **S18.3** | Remove "Register a watch" and the dialog text promising a merge (D2). Watches appear when they first sync. Keep S5.6's "Merge into…" and its suggestion, and move them onto the scaffold. | #15 |
| **S18.4** | "Use current" location explains a denied permission. | §3 Settings |

**Commit:** `refactor(ui): one way to manage people, watches, places and tags`

---

## Phase G — App-wide polish

### S19 — Loading and the main thread

| Ticket | Work | Audit |
|---|---|---|
| **S19.1** | Distinct loading and empty states on People, Watches, Locations and analysis. The event timeline currently says "Nothing measured in this scope" for about 15 seconds while it loads, and the Library shows "Nothing here yet" for a moment on a cold start (seen in S7). | §2, §6 |
| **S19.2** | Move work to IO with progress: CSV import and export, `looksLikeJson`, image save, Save CSV, diagnostics, and the Recovery database copy. | §2 |

**Commit:** `fix(ui): load before saying empty; keep files off the main thread`

### S20 — Rows, state and Back

| Ticket | Work | Audit |
|---|---|---|
| **S20.1** | Make whole rows `toggleable`/`selectable` (clip cards, settings, tags, refinement, pickers), and remove `clickable` where nothing happens (moment rows). | §2 |
| **S20.2** | Use `rememberSaveable` for the analysis tab, chart zoom and scrub, export sections and the event-page tab. | §2 |
| **S20.3** | Back arrows on live analysis and the render queue, with the queue going back to Export. Breadcrumbs don't push duplicate pages. Export entered from a detail page goes back to that page. (Tapping the Export tab returning to the wizard rather than a queue opened from it was fixed in S7.) | §2, §6 |

**Commit:** `fix(ui): rows that respond, state that stays, Back that goes back`

### S21 — Charts and accessibility

| Ticket | Work | Audit |
|---|---|---|
| **S21.1** | Size the timeline in dp/sp and respect font scale. Show a scrub readout with time and BPM. Give the shaded band a legend. Make zoomed time labels distinct. Multi-day axes show dates, and ticks fall on round times. Moments name the day when the scope spans more than one. | §2 Charts, §6 |
| **S21.2** | Let vertical drags scroll the page over a chart. Collapse the analysis header on small phones. | §2 Charts |
| **S21.3** | Pair every colour-only state with a second cue. Name sliders and switches, and add semantics to canvas controls. Make touch targets at least 48dp. The drawer button has a single content description (there are three today). | §2 Accessibility, §6 |

**Commit:** `feat(a11y): readable charts and controls that announce themselves`

### S22 — Words

| Ticket | Work | Audit |
|---|---|---|
| **S22.1** | A glossary, as a short section in this doc: Low/Avg/Peak, Zones, Venue, Living/Static, Remove vs Delete, Unfiled, and so on. | §2 Vocabulary |
| **S22.2** | One pass applying it, removing implementation words (cast, readouts, lanes, …) and Title Case. | §2 Vocabulary |
| **S22.3** | A single plural helper replacing about 30 hand-built plurals. Readable times in the split dialog. Backup filenames use dates. Fix the copy typos, including "1 render queued. They carry on…" and the "Start 1" button. | §2, §3, §6 |

**Commit:** `chore(copy): one name for each thing`

---

## Phase H — Theme and branding

### S23 — A complete colour scheme

| Ticket | Work | Audit |
|---|---|---|
| **S23.1** | Generate the full M3 dark scheme from the brand seed, with every role set, including `surfaceContainer*`, `surfaceBright` and `surfaceDim`. | §4 step 1 |
| **S23.2** | Replace the 11 `surfaceVariant.copy(alpha)` sites with container roles. | §4 step 1 |
| **S23.3** | Status bar and navigation bar icons are light on the dark theme. | #32 |

**Commit:** `feat(theme): every colour role set, and no more purple-grey`

### S24 — Extended colours

| Ticket | Work | Audit |
|---|---|---|
| **S24.1** | An `ExtendedColors` `CompositionLocal` holding the heart-rate ramp and zones (shared with exports), chart grid, cover scrim, `onCover` text, and status tokens (success, queued, running, failed, attention). | §4 step 2 |
| **S24.2** | UI code stops importing `BpmHigh`, `BpmLow` and `BpmAccent`. That's 61 uses, 26 of them in the render queue. | §4 |
| **S24.3** | Separate the person palette from the warning red and the brand teal, and make neighbouring person colours clearly distinct. Teal vs cyan and red vs orange are indistinguishable on the timeline today. | §4, §6 |

**Commit:** `feat(theme): extended colours, and brand colours only where they mean something`

### S25 — Type and spacing

| Ticket | Work | Audit |
|---|---|---|
| **S25.1** | Fill in `displaySmall` and `headlineSmall`, and replace the 10 `.sp` literals with type tokens. Apply D3. | §4 step 3 |
| **S25.2** | Adopt `BpmSpacing` on the settings and management screens. Expanded settings groups get inner padding, and chip rows get normal row spacing. Check at 360dp wide with 1.3× font: step-bar labels ellipsize or drop instead of clipping ("Conten"), dates don't break inside a time, and detail app bars move secondary icons into the overflow menu so the title has room. | §4 step 4, §6 |

**Commit:** `feat(theme): type scale and spacing everywhere`

### S26 — Brand assets

| Ticket | Work | Audit |
|---|---|---|
| **S26.1** | A launcher glyph from D4, with an adaptive icon and a monochrome layer for themed icons, plus a beta badge variant. | §4 step 5 |
| **S26.2** | A splash screen to match, and a notification small icon replacing the system icons. | §4 step 5 |
| **S26.3** | The same icon and the ongoing-activity icon on the watch. | §4 step 5 |
| **S26.4** | A brand mark in the drawer header, and the drawer highlights the current screen. | §6 |

**Commit:** `feat(brand): app icon, splash and notification icon`

### S27 — Watch theme

| Ticket | Work | Audit |
|---|---|---|
| **S27.1** | The watch theme draws on the shared palette. Chips and accents use it, and digits are tabular. | §4 step 6 |

**Commit:** `feat(wear): one palette for both wrists`

---

## Phase I — Release

### S28 — Ready for the store

| Ticket | Work | Source |
|---|---|---|
| **S28.1** | Write and publish a privacy policy (D5), and link it from About and the Play listing. | #23, Play #4 |
| **S28.2** | Re-check every P0/P1 item in the Play Store Readiness Audit, fix any still open, and mark the rest done in that doc. | Play |
| **S28.3** | A final visual pass on the emulators, comparing against the S0 screenshots. Include Android 16 (Pixel_9_Pro_XL_API_36), 1.3× font, and 360dp wide. | — |
| **S28.4** | Set the version to 1.0.0 in `version.properties` (About shows 1.1.0 today) and update the release notes. Update the copyright year on About. | Play #3, §6 |

**Commit:** `chore(release): 1.0`

After S28, merging to `staging` (beta) and then `production` is Danny's call.
