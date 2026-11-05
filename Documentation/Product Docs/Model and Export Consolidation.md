# Model and Export Consolidation

*Proposal, November 2026. Pre-1.0. Builds on Taxonomy Consolidation §8 (properties vs selections) and does not reopen it.*

## The problem in one paragraph

Recordings, events and collections share most of their attributes: a name, notes, a cover, and (for recordings and events) a place and tags inherited down the event tree. Today each of those is written once per type at every layer. The table has 7 cover columns, copied 3 times. The repository has 3 rename functions, 4 notes functions, 2 location functions, 9 tag functions and 3 cover paths. There are 3 nearest-wins resolvers with 3 identical `Source` enums, 3 detail screens that each build their own subtitle and cover flow, 3 editor dialogs with 3 name fields and 2 different location widgets, and 3 library cards built on the same skeleton. Export has the same problem in a different shape: one *look* is described by 5 overlapping config types, which are hand-copied into each other in 4 places.

## The rule

**Inherit behaviour through interfaces; share data through embedded value types.** Don't use table inheritance.

- **Interfaces** (a sealed hierarchy) say *what a thing is*, so one function can handle every type.
- **`@Embedded` value classes** say *what a thing has*, so the 7 cover columns become one `cover` field.
- **No shared table.** Room maps one class to one table, and Kotlin data classes can't extend each other. Putting recordings, events and collections into one table would be a large migration, would make most columns nullable, and would undo the properties/selections split that the taxonomy work set up on purpose.

## 1. Storage: no schema change

| Change | How | Migration |
|---|---|---|
| One cover field | `@Embedded val cover: Cover?` on all three entities, with `@ColumnInfo(name = "coverPath")` etc. inside `Cover`, so the column names stay exactly as they are | **None.** The generated schema is identical. Check this by diffing the exported `schemas/…/30.json` before and after. |
| Delete `EventGroupEntity` | The table was dropped in 27→28. Only one test still references the class. | None |
| `EventEntity.groupId` | Stop reading it now. Drop the column whenever the next real migration happens. | Optional, later |
| `title`/`description` vs `name`/`notes` | Unify at the interface (below). Leave the columns as they are. | None |

The backup DTOs in `JsonExporter` **stay separate and flat**. They are a file format, and they should change only when that format does.

## 2. Domain: one sealed hierarchy

```kotlin
/** Anything with a page of its own: a recording, an event, a collection. */
sealed interface LibraryItem {
    val ref: ItemRef               // replaces CoverOwner; maps 1:1 onto ScopeRef
    val displayName: String
    val notes: String
    val ownCover: Cover?
}

/** Things that live in the event tree and inherit properties from it (Taxonomy §8.1). */
sealed interface TreeItem : LibraryItem {
    val parentEventId: Long?       // a recording's eventId, an event's parentId
    val ownLocationId: Long?
}

// BpmRecordEntity : TreeItem     EventEntity : TreeItem     CollectionEntity : LibraryItem
```

Collections are a `LibraryItem` but not a `TreeItem`. That one line in the type system encodes the decision that collections don't hold tags or a place.

## 3. Inheritance: one resolver

Replace `CoverSource`, `PlaceSource` and `TagSource`, along with `EffectiveCover`, `EffectivePlace` and `EffectiveTag`, with:

```kotlin
enum class Source { OWN, EVENT, ANCESTOR }
data class Inherited<T>(val value: T, val source: Source) { val isInherited get() = source != Source.OWN }

fun <T> EventTree.nearest(item: TreeItem, pick: (TreeItem) -> T?): Inherited<T>?   // cover, place
fun <T> EventTree.gather(item: TreeItem, pick: (TreeItem) -> List<T>): List<Inherited<T>> // tags (deduped)
```

`CoverResolver`, `LocationResolver` and `EffectiveTagsResolver` each shrink to a single call. The places that bypass them should call `nearest` too: the inline walk at `EventDetailScreen.kt:106`, and the private `eventCovers` maps in `LibraryViewModel` and `BpmRecordViewModel`.

## 4. Repository: one verb per operation

| Today | After |
|---|---|
| `updateRecordTitle`, `renameEvent`, `renameCollection`, `renameSavedAnalysis` | `rename(ref, name)` |
| `updateRecordDescription`, `setEventNotes`, `setEventGroupNotes`, `setCollectionNotes` | `setNotes(ref, notes)` |
| `setCover`/`setCoverCrop`/`clearCover` + 3 sets of view-model wrappers | `setCover(ref, …)` etc. once, with no per-screen wrappers |
| `setRecordLocation`, `setEventLocation` | `setLocation(ref: TreeItemRef, id)` |
| `add/remove/getTagsFor{Record,Event,Group}` + `getGroupTagsForBackup` | `addTag(ref, tag)`, `removeTag(ref, tag)`, `tagsOf(ref)` |
| `deleteRecordWithId`, `deleteEvent`, `deleteCollection`, `deleteSavedAnalysis` | `delete(ref)`. The per-type cleanup stays inside, as one `when`. |
| `setEventParent`, `setEventGroup`, `setEventGroupParent`, `createEventGroup`, `getEventGroup`, `*TagToGroup`, `Routes.GROUP_DETAIL`, `ExportSource.Group` | Delete the "group" aliases. Name things *event* or *collection*. |

Room still needs one DAO query per table. The `when (ref)` goes in one private place in the repository, not in every caller. Also move the recording cover queries out of `EventDao`.

## 5. UI: one skeleton per kind of screen, type-specific slots

- **Detail pages.** Use one `ItemDetailViewModel(ref)` for the three screens, so collections stop borrowing `LibraryViewModel`. Use one `CoverEditing(ref)` composable to replace the three copies of picker + crop + clear. The subtitle and count strings get a single builder. `SubjectHeader` stays; keep the per-type subject sections, since Taxonomy Sprint 5 says those genuinely differ.
- **Editors.** Use one `ItemEditorDialog` with shared fields: `NameField`, `NotesField` (which gives events and collections a notes editor for the first time), `TagsButton`, `LocationField` (one widget, not two) and `CoverSlot`. Each type adds its own section: the event's type, parent and window; the collection's rule, pin and freeze; the recording's wearer and start time.
- **Library cards.** Use one `LibraryCard` with a cover background, title, span, faces, counts, an overflow menu and the selection treatment. `BpmRecordTile`, `EventCard` and `CollectionCard` become thin wrappers that fill in the slots. Delete `countLabelPublic`.
- **Delete these dead composables:** `EventSubjectHeader`, `Breadcrumb`, and `EventDetailViewModel.setNotes`/`rename`. Delete the last two only if `NotesField` doesn't end up using them.

## 6. Export: the preset *is* the look

One look is currently spread across `ExportPreset`, `ImageExportConfig` (30 fields, mixing look and content), `VideoExportConfig`, `TimelineImageExporter.Spec`, and `RenderJobEntity` columns. `applyTo` hand-copies 34 fields, and the view model copies 13 more. `VideoExportConfig` is built in 3 places, and the render-queue round trip loses fields. All of that is one problem with one fix:

```kotlin
data class ExportContent(records, window, title, colours, photos, timeZone)   // what
// ExportPreset (unchanged, still the versioned JSON)                         // how it looks
sealed interface ExportRequest { val content: ExportContent; val preset: ExportPreset
    data class Image(…, val grouping, val crop) : ExportRequest
    data class Video(…, val clip: ClipSelection?) : ExportRequest
}
```

- Renderers take `(ExportContent, ExportPreset)` directly. This deletes `ImageExportConfig`'s look fields, `VideoExportConfig`'s look fields, `Spec`, `applyTo`, and `ExportPreset.from`. A render job stores `preset.toJson()` and `ExportContent`, so nothing can go missing between queueing and rendering. (That loss is audit bug 25.)
- **Rename for what they do.** `ImageExporter` only renders video frames, so it becomes `VideoFrameRenderer`. `TimelineImageExporter` becomes `ImageRenderer`.
- **A shared `ChartKit`.** These are the parts both renderers should agree on: the BPM range (today there are 3 algorithms), grid lines (`GraphAxes`), colour per person, wearer label, `ellipsize`, paint setup, scale factor, the gap constant, and zone resolution. The curve style, axes and header stay per renderer, because those differ on purpose.
- **One `MediaStoreSaver`** replaces the near-copy video and image savers, so both clean up after a failed save. **One filename sanitiser** replaces the 5 copies in 2 variants.
- **Delete dead code:**
  - `renderGraphToBitmap`, `shareBitmap`, `renderMultiRecordsOnCanvas`, `getVideoCaptureTime`, and both `getOverlappingVideos`.
  - The `snippet*` ranges, `adjustAlpha`, `headerXPercent`, and the unreachable elapsed-time alignment.
  - `BpmExportService.finishedFile`/`isExporting`/`exportProgress`.
  - The bit-rate fields, if the encoder keeps choosing its own rate.

## Order

Each phase compiles, passes the tests and is committed on its own. Phases 1–3 change no behaviour that a user would see.

0. **Delete dead code.** This is the lowest risk, and it shrinks everything that follows.
1. **Storage and domain:** sections 1–3. Check the schema JSON diff.
2. **Repository verbs:** section 4. Mostly mechanical. Callers move over one screen at a time.
3. **Export:** section 6. Fixes audit bugs 25–27 as a side effect.
4. **UI skeletons:** section 5. Do this **before the theme pass**, so there is one card, one editor and one header to theme instead of three of each.
5. **Theme and branding.**

The UX-audit bugs that live outside these areas can be fixed alongside any phase, because they are independent. Examples are the tag tap, the UTC date filter, the notification permission, number fields and the watch Stop button.

## Tests to lean on / add

- The existing `CoverResolverTest`, `MembershipReconcileTest`, `JsonExporterTest` and `ShiftTimeTest` should pass unchanged through phases 1–2.
- Add tests that `nearest`/`gather` give the same answers as the three resolvers they replace. Run both side by side before deleting the old ones.
- Add a render-job round-trip test: a job queued, persisted and restored gives back an identical preset.
- Add one golden-ish bitmap check per renderer, so `ChartKit` can't silently change a curve.
