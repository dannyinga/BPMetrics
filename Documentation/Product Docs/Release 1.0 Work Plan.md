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

### S5 — Settings that do what they say

| Ticket | Work | Audit |
|---|---|---|
| **S5.1** | Remove the personal resting/max heart-rate fields (from Settings and People), the sync retry interval, the export time zone, and `HeartRateZones.forPerson` (D1). Stored values are left alone and simply no longer read. | #7 |
| **S5.2** | Number fields edit as text and commit on done or when focus leaves, clamping with a visible message instead of on every keystroke. | #8 |
| **S5.3** | Restoring a backup file confirms first and says plainly what it does. It doesn't overwrite settings it shouldn't, such as id-valued keys from another database. While it runs, progress shows on Restore itself (with a count), and both buttons are disabled. Back up suggests a dated name with no doubled `.json`. | §3 Settings, #33, #34 |
| **S5.4** | Open the most-used settings group by default and move Sync status higher. | §3 Settings |
| **S5.5** | The People edit dialog gets Cancel, a scrolling body, and a warning that removing a person unlinks their watches. | §3 Settings |

**Commit:** `fix(settings): fields you can type in, and settings that exist`

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

### S11 — One inheritance walk

| Ticket | Work | Plan |
|---|---|---|
| **S11.1** | Add `Source` and `Inherited<T>`, plus `EventTree.nearest` and `gather`. Write tests that compare them with the current three resolvers on a realistic tree. | §3 |
| **S11.2** | `CoverResolver`, `LocationResolver` and `EffectiveTagsResolver` become thin calls. Delete `CoverSource`, `PlaceSource`, `TagSource` and the three `Effective*` types. | §3 |
| **S11.3** | Remove the bypasses: the inline walk at `EventDetailScreen.kt:106`, and the private `eventCovers` maps in `LibraryViewModel` and `BpmRecordViewModel`. | §3 |

**Commit:** `refactor(library): one nearest-wins walk for covers, places and tags`

### S12 — One verb per operation

| Ticket | Work | Plan |
|---|---|---|
| **S12.1** | Add `rename(ref)`, `setNotes(ref)` and `delete(ref)` in the repository, with per-type cleanup inside `delete`. Move callers over. | §4 |
| **S12.2** | One `setCover`/`setCoverCrop`/`clearCover(ref)`. Delete the three sets of view-model wrappers, and move the recording cover queries out of `EventDao`. | §4 |
| **S12.3** | `setLocation(ref)`, `addTag(ref)`, `removeTag(ref)` and `tagsOf(ref)`. | §4 |
| **S12.4** | Delete the "group" API: `setEventGroup*`, `createEventGroup`, `getEventGroup`, `*TagToGroup`, `getGroupTagsForBackup`, `Routes.GROUP_DETAIL` (renamed to a collection route) and `ExportSource.Group`. | §4 |

**Commit:** `refactor(library): one repository verb per operation`

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

### S14 — Renderers that agree with each other

| Ticket | Work | Plan / Audit |
|---|---|---|
| **S14.1** | Rename `ImageExporter` → `VideoFrameRenderer` and `TimelineImageExporter` → `ImageRenderer`. | Plan §6 |
| **S14.2** | Add `ChartKit`, one place for the BPM range, grid lines, person colour, wearer label, `ellipsize`, paint setup, scale factor, gap constant and zone resolution. Both renderers use it. | Plan §6 |
| **S14.3** | Single-recording video uses the caption and the shared range, so the render matches the preview. Fix the pixel-vs-ms gap check. | #26, #27 |
| **S14.4** | One `MediaStoreSaver` for images and video, which cleans up after a failed save. One filename sanitiser. | Plan §6 |
| **S14.5** | Add a reference-bitmap check per renderer: the same input gives the same pixels within tolerance. | Plan §6 |

**Commit:** `refactor(export): shared chart kit, honest names, one way to save`

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
| **S18.3** | Remove "Register a watch" and the merge it promised (D2). Watches appear when they first sync. | #15 |
| **S18.4** | "Use current" location explains a denied permission. | §3 Settings |

**Commit:** `refactor(ui): one way to manage people, watches, places and tags`

---

## Phase G — App-wide polish

### S19 — Loading and the main thread

| Ticket | Work | Audit |
|---|---|---|
| **S19.1** | Distinct loading and empty states on People, Watches, Locations and analysis. The event timeline currently says "Nothing measured in this scope" for about 15 seconds while it loads. | §2, §6 |
| **S19.2** | Move work to IO with progress: CSV import and export, `looksLikeJson`, image save, Save CSV, diagnostics, and the Recovery database copy. | §2 |

**Commit:** `fix(ui): load before saying empty; keep files off the main thread`

### S20 — Rows, state and Back

| Ticket | Work | Audit |
|---|---|---|
| **S20.1** | Make whole rows `toggleable`/`selectable` (clip cards, settings, tags, refinement, pickers), and remove `clickable` where nothing happens (moment rows). | §2 |
| **S20.2** | Use `rememberSaveable` for the analysis tab, chart zoom and scrub, export sections and the event-page tab. | §2 |
| **S20.3** | Back arrows on live analysis and the render queue, with the queue going back to Export. Breadcrumbs don't push duplicate pages. Export entered from a detail page goes back to that page. Tapping the Export tab returns to the wizard, not to a queue opened from it. | §2, §6 |

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
