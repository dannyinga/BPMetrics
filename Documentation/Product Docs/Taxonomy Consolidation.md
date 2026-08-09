# Taxonomy Consolidation

**Status:** design, not yet started
**Sprints:** 7
**Depends on:** UI and UX Cleanup (the screens this reshapes have just been reworked)

---

## 1. What this is for

The point of this app is not storage and it is not export. It is **being able to ask a question of a
group of recordings and get an answer** — was I more wound up at Subtronics or at Excision, does
co-op raise my rate more than solo, is my resting rate lower than it was in spring.

Everything in the library exists to make those questions askable. So the taxonomy is not an
organisational nicety to be tidied; it is the query language, and it should be designed from the
questions backwards.

Read that way, the current model has one part that is genuinely undersold and one that is genuinely
duplicated.

### 1.1 Categories are comparison axes, and that is not optional

`Character: Spiderman` and `Character: Hulk` are not two labels. They are two **values on one axis**,
and knowing that is what makes "compare my rate across characters" a question the app can answer at
all. A flat tag list cannot express it: nothing distinguishes two alternatives on an axis from two
unrelated labels that happen to co-occur.

This is the most analytically valuable structure in the app and it is currently presented as
bookkeeping — a management screen you must visit before you are allowed to label anything.

**Categories stay, and get a job.** The friction goes; the concept is promoted.

### 1.2 There are two relations, and the model has them the wrong way round

Two things need expressing, and they are not the same shape.

**Where a recording sits in time.** Griztronics at the Gorge → Day 1 → Subtronics → the recording.
Strictly nested, exclusive, chronological. This is where a recording *lives*, and it is a tree.

**What something is filed under.** "Festivals" holding Griztronics at the Gorge and Bass Canyon,
whose dates are months apart. No window, no chronology, and Griztronics is in it *while still living
on the timeline*. Many-to-many. This is a set.

The current model splits by **tier** — events at the bottom, collections above — when it should
split by **relation**. That single mistake produces every symptom in this document:

- The tree can only be two deep, so a festival, its days and its sets do not fit.
- A recording reaches a collection only through an event, so "Saturday, act unknown" is
  unrepresentable, and containment becomes a two-hop walk with different rules at each hop.
- Arbitrary grouping has nowhere to live, because the only grouping available is also a tier.

Four separate bugs here have been a count of a collection's contents disagreeing with its actual
contents — most recently the export source picker reporting "0 events" for a collection of
collections while the resolution two hundred lines away walks the subtree correctly. Both answer
"what is inside this?"; one is wrong. Three hops to know, and every feature is a chance to know two.

Split by relation instead and both words survive with sharper meanings: an **event** is the
time-bounded thing, which now nests; a **collection** is an arbitrary set, which now genuinely is
one.

---

## 2. The model

Four things, each answering a different question.

| | Question | Shape |
|---|---|---|
| **Event** | When did this happen, and what was it part of? | Nesting tree, optional time window |
| **Collection** | What do I want to look at together? | Arbitrary set, many-to-many |
| **Category → Tag** | What was true about it? | Named axis, with values |
| **Person** | Whose heart is this? | Already correct, unchanged |

### 2.1 Events: the timeline, nesting

`EventEntity` gains `parentId` and an **optional time window**; `EventGroupEntity` goes. An event
holding recordings is what an event always was; an event holding events is what a collection was
being used for. One entity, any depth.

```
Griztronics at the Gorge  2026        ← festival
└── Day 1                 14 Aug      ← day
    ├── Subtronics        21:00–22:30 ← set
    │   └── recordings
    └── recording                     ← Day 1, act unknown
```

This is the **canonical** structure: an event is where a recording *lives*, it lives in exactly one,
and the tree is browsable in time order at every level. Scrolling the library, opening Griztronics,
seeing Day 1 and Day 2, opening Day 1 and seeing each artist, opening an artist and seeing the
recordings — that is this tree, and every node of it can be analysed.

**A window is not a hint, it is the membership rule.** A recording inside an event's window belongs
to it. Filing is only for recordings that fall outside every window, and the event-suggestion
feature retires because the model now states what it was approximating.

**Membership is derived by one function and stored by one writer.**

A recording belongs to the **deepest** event whose window contains it. That answer is produced by a
pure function over the tree and the timestamps — and then written to a column, because reads are
constant and the things that change the answer are rare. Events do not move once they are set.
Recomputing on every read to protect against a change that happens twice a week is the wrong way
round.

What makes this safe is not the column, it is who is allowed to write it:

- **One pure function** computes membership for the whole library from events and recordings. No
  I/O, fully testable, and the single definition of what "inside" means.
- **One reconcile step** runs it and writes the result. Nothing else ever writes the column.
- **Callers only read.** This is the whole difference from the current model, where every feature
  that changed something was also responsible for correcting what depended on it — and four bugs
  came from one of them not knowing it had to.

The list of things that invalidate membership is short and closed:

| Invalidates | Does not |
|---|---|
| A recording arriving, being deleted, split or merged | A title or note |
| A window created, moved or cleared | A tag, cover or type |
| An event created, deleted or reparented | A collection's contents |
| The people qualifying a window | Anything on a person or watch |

Being able to write that table is the point. A short closed list can be audited; "wherever it
matters" cannot.

Recompute the whole library rather than the affected part. A few thousand recordings against a few
hundred events is milliseconds, and a partial recompute is a second, subtler definition of what
changed — which is the same class of mistake in a new place. Optimise only against a measurement.

And because the derivation is pure, the stored column can be **checked** against it: a test that
reconciles a realistic library and asserts every row matches, so drift is a failing test rather
than a wrong number on someone's screen.

A recording that no window contains is **unfiled**, which is a real state and not an error. It may
still be filed by hand, so `eventId` carries either the derived answer or an explicit one — the
window wins where there is one, the manual value applies where there is not.

**One relation, walked one way.** `event.parentId`, and membership from windows. Counts, tag
inheritance, cover inheritance, export scope and analysis scope become one recursive walk with one
rule, written once. The recurring bug stops being possible rather than being fixed again.

### 2.2 Overlapping windows, which must be allowed

Two artists on two stages at the same time is not an edge case at a festival, it is the normal
shape of one. Kyle at Subtronics and Ben at Excision from nine to half past is two events that
genuinely overlap, and a model that refuses it is wrong about the world.

So the earlier constraint is dropped. What replaces it keeps the rule unambiguous:

**An event's window may be qualified by people.** "Subtronics, 21:00–22:30, Kyle and Sam" and
"Excision, 21:00–22:30, Ben" overlap in time and not in people, so a recording still has exactly one
answer — the key is (time × person) rather than time alone.

- Sibling windows overlapping in time **and** in people is still refused, because that genuinely has
  no answer. The message names the event it collides with.
- Windows with no people named claim everyone, so the simple case stays simple and two unqualified
  siblings still cannot overlap.
- Nesting is not overlap. The innermost window containing a recording holds it.

Where a recording falls in an overlap the app cannot resolve — two unqualified windows that were
created before this rule, say — it stays unfiled and says which events it could belong to. Unfiled
and explained beats filed and wrong.

### 2.3 Collections: arbitrary sets

A collection holds events and recordings **by reference**. It has no window, no chronology, and no
claim on where anything lives. "Festivals" holds Griztronics at the Gorge and Bass Canyon, months
apart, while both remain exactly where they are on the timeline.

Membership is many-to-many: an event can be in Festivals and in 2026 and in With Kyle at once. That
is the property the old tier-based collection could not have, and it is the whole reason arbitrary
grouping had nowhere to live.

A collection is a **second view over the same objects**, not a second home for them. Deleting one
removes a grouping and nothing else. It is analysable exactly like an event — "compare every
festival" is the same machinery pointed at a different set.

Collections do not nest, at least to begin with. Nesting a set is a real thing to want eventually,
but the tree already exists for hierarchy and adding a second one is how this document's problem
started.

### 2.4 What kind of thing it is

One word at every tier stops being a compromise if the tier can say what it *is*. An event carries
a **type** — "Concert", "Festival", "Gaming session", "Run", "Raid" — chosen freely and
suggested from types already in use.

This is better than inventing a fixed vocabulary because the vocabulary is the part that would make
the app concert-shaped. The event is generic; the word on it is the user's.

It also earns its place analytically rather than being decoration: **type is a fourth comparison
axis**. "Do gaming sessions or concerts wind me up more" is the same question as Spiderman vs Hulk,
one level up, and falls out of the same machinery.

A type is a plain label on the event, not a tag. Tags describe what was true during a
recording; a type describes what the event is, there is exactly one, and it needs no axis
because it is its own.

### 2.5 Categories, with the friction removed

The concept is kept whole. What goes is the requirement to visit a management screen before
labelling anything: a tag is created inline where it is applied, and its axis is chosen or created
in the same gesture.

`parentCategoryId` stays **mandatory**, which is the opposite of the earlier draft of this document.
An axis-less tag cannot be compared against anything, and a model that permits half its tags to be
uncomparable has given up the capability §1.1 exists to protect. Where a tag genuinely has no
alternatives, the axis is simply a category of one — which is honest, and costs nothing.

**One tag per category per recording.** A recording is Spiderman or Hulk, not both. This is a real
constraint with a real cost — a co-op session with two characters cannot be labelled as such — and
it is worth paying, because it makes a category a **partition**: every recording in scope falls in
exactly one lane, lanes sum to the whole, and no total counts anything twice. A comparison whose
lanes overlap is one whose percentages do not mean anything, and explaining that at every total is
worse than the constraint.

Where two values genuinely apply, the honest move is a value that says so — "Co-op" as a character,
or a second axis for the second player.

### 2.6 Naming, settled

Both existing words are kept and both mean something sharper than before:

- An **event** is a thing that happened, at a time. It nests, so a festival, a day and a set are all
  events and the word holds at every depth.
- A **collection** is a set of things you want to look at together. No time, no hierarchy, no claim
  on where anything lives.

"Event" sounds concert-shaped, but the **type** in §2.4 is what carries the domain, and it is the
user's word rather than the app's. A nesting, time-bounded event typed "Raid" or "Training block"
or "Road trip" is not concert software. Removing the type and generalising the noun instead would
have been the wrong trade: it makes every label vaguer to avoid one label being specific.

---

### 2.7 Where it happened, and what time it was there

A recording carries a wall clock and nothing that says which wall. Every timestamp in the app is
rendered in the phone's *current* zone, so a set watched at 21:00 at the Gorge reads as 21:00 until
you fly home, and then reads as 00:00 the next day — for ever. The recording did not move. The
reader did.

This is not a display nicety. An event window is the membership rule, and a window is entered as a
wall-clock time; if the clock a recording is stored against and the clock a window is entered
against disagree, membership is wrong. Two festivals in different zones cannot both be correct
under one interpretation.

**A location is a registry entry, like a person or a watch.** A name, a time zone, optionally a
picture and coordinates. You make "The Gorge" once and point events at it, exactly as you point a
recording at a person — and for the same reasons: renaming it fixes every event at once, and
comparing across locations works on identity rather than on whether two strings match.

This is what removes the hardest part of the problem. The only reason to derive a time zone from
coordinates is not knowing it, and someone naming a venue knows what time it is there. Picking the
zone once, when the location is made, is one extra field on a form that gets filled in rarely —
against a bundled boundary dataset of several megabytes, a lookup that can be subtly wrong near a
border, and a third-party dependency deciding what time your recordings say. The registry is
smaller, more accurate and more honest.

**Coordinates are optional and informational.** Useful for a map one day, and for "where was this",
but nothing depends on them. They can be filled from the device if location permission is granted —
a GPS fix needs no network — and the feature works completely without it. Permission is an
optional convenience, never a requirement, and a location typed by hand is worth exactly as much as
one captured automatically.

**Events carry the location; recordings inherit it.** A venue is a property of the occasion — you
were at the Gorge all night, with everyone else — and putting it on each recording would mean
setting it once per watch per night. Inheritance is the same nearest-wins walk up `EventTree` that
tags and covers already use, so there is one implementation, and a recording can still override
where it genuinely differs.

**The resolved zone is frozen onto the recording.** Inheritance decides it; a column stores it. The
same reasoning as membership: it changes rarely, is read constantly, and a stored value can be
checked against the pure function that produced it. It also means a recording exported or backed up
carries its own clock rather than depending on a tree that may since have been rearranged.

**Location is a fourth comparison axis**, alongside person, event and tag category — "the Gorge
against Showbox" is the same machinery as Spiderman against Hulk, and it works whether or not any
coordinates were ever captured.

What this does *not* change: the stored instant. Recordings are epoch milliseconds and stay that
way. A zone is how an instant is written down, never what it is — and the day this is confused is
the day a daylight-saving boundary starts moving people's recordings around.

**Nothing here touches the network.** Zone ids come from the platform's own tzdata, which Android
keeps current; coordinates, when wanted, come from the device's own receiver. The app continues to
work with the radio off, which is the condition it is most often used in.

---
## 3. Analysis, which is the point

Every comparison the app can offer is "split these recordings by X and lay the groups over each
other". There are exactly three useful values of X, and the model above is chosen to make all three
first-class:

| Split by | Answers | Needs |
|---|---|---|
| **Person** | Who was most wound up? | People — already works |
| **Child event** | Day 1 vs Day 2, set vs set, week vs week | One tree walk |
| **Tag within a category** | Spiderman vs Hulk, co-op vs solo, indoor vs outdoor | Categories as axes |
| **Event type** | Concerts vs gaming sessions vs runs | A type on the event |

The last two do not exist yet and are the most valuable. They are also the reason §2.5 reverses
course on categories.

Each of the four is a **partition** — every recording in scope falls in exactly one lane. That is
why §2.5 allows only one tag per category, and it is what lets a comparison show percentages that
add up.

**Scope and split are separate choices.** Scope says which recordings ("Coachella"), split says how
to divide them ("by character"). The current analysis screen conflates them — a scope is picked and
the split is implied — which is why comparing across a tag axis is unreachable today.

**Every analysis is scoped to an event or a collection, and split by one axis.** That single sentence should
describe the whole feature.

### 3.1 A scope is not always everything under it

"Day 1" contains the sets, and it also contains the walk to the campsite, the merch queue and two
hours of sitting down. Analysing Day 1 as a whole averages the day with the queue in it, and the
number that comes out is not wrong so much as not the question.

So **a scope may exclude parts of itself.** The analysis screen lists what is in scope — the child
events and the loose recordings — and any of them can be switched off. Excluding an event excludes
its subtree. Everything is included by default, so the simple case needs no interaction.

Two things make this more than a checkbox list:

**It is remembered where it belongs.** An exclusion made while poking around is transient; one saved
with a saved analysis persists with it. Neither writes anything to the event, because "not part of
this analysis" is a fact about the analysis and not about the event.

**An event can carry a default.** A "Camp break" typed as such is going to be excluded from every
roll-up it ever appears in, and re-excluding it each time is the app making the user repeat itself.
So an event may be marked **excluded from parent analysis** — set once on the break, honoured
wherever it is rolled up, and still overridable in any individual analysis. It never affects the
library, the timeline or an export; only what a parent's numbers are computed over.

That flag is the piece worth getting right, because it is what turns "Day 1" from a figure that
happens to include the merch queue into a figure that means the day.

### 3.2 Chronological browsing

A library of two hundred recordings across several people needs to be walkable in the order things
happened, independent of who was wearing what.

An event's span is its window if it has one, otherwise the span of its contents. Sorting events by
span start gives a chronological walk for free — and because every tier is the same entity, the same
ordering works at festival, day and set level.

**Recordings sort alongside events, not after them.** A recording that no deeper window claims sits
in the list at the time it happened, between the events either side of it. Day 1 ends at the last
set, the watch keeps running back at camp, and that stretch appears between Day 1 and Day 2 —
because that is when it was, and a list sorted by time that puts some things in time order and the
rest in a bucket at the bottom is not sorted by time.

This is also why "unfiled" needs no special handling. Inside a festival whose own window covers the
weekend, the camp recording is not unfiled at all — it is a direct child of the festival, because
the festival is the deepest window containing it. It only becomes unfiled when nothing contains it,
and then it sorts into the top level of the library by the same rule.

---

## 4. Filtering

The filter is a dialog with a section per dimension, which makes the user think in the app's schema
rather than in their own question. "Kyle at Coachella in the rain" is spread across three sections.

Replace it with **one bar: a search field and a row of chips**, where a person, a tag, a collection,
a date range and a rate band are all just chips. The active filter reads as a sentence.

Saved views follow, and are what makes it worth doing: a filter that has to be rebuilt each time is
a form, and a filter that can be pinned is a view.

---

## 5. Colour

The analysis tabs each introduce a palette, so one person is one colour in the library, another in a
comparison and a third in a zone breakdown. There is already one correct answer.

- **Identity** — a person — takes that person's colour.
- **Quantity** — a rate — takes the low-to-high ramp.
- **Zones** are the one deliberate third scheme, and should be defined alongside the others rather
  than arrived at by accident.

Nothing else introduces a palette. A comparison split by tag colours its groups from the ramp or
from a neutral series, never from a fourth invented set.

---

## 6. Sprints

### Sprint 1 — Events become the tree

| Ticket | Work |
|---|---|
| **TX-1.1** | `EventEntity` gains `parentId`, optional `windowStart`/`windowEnd`, an optional set of people qualifying the window, and a `type`. Migration turns every existing collection into an event and reparents its events under it, discarding nothing. |
| **TX-1.2** | One recursive walk — `descendants`, `ancestry`, `contents`, `span` — cycle-guarded, pure, tested, replacing every hand-rolled traversal. |
| **TX-1.2b** | `resolveMembership(events, recordings)` — pure, the single definition of "inside", covering deepest-window-wins and the people qualification. |
| **TX-1.2c** | One `reconcileMembership()` that runs TX-1.2b and writes the column, called from the closed list of mutations in §2.1 and nowhere else. Callers read only. A test reconciles a realistic library and asserts every row matches the pure result, so drift fails a build rather than reaching a screen. |
| **TX-1.3** | One DAO; `EventGroupDao` goes. Every count, span and roll-up derived through TX-1.2. |
| **TX-1.4** | Tag and cover inheritance re-pointed at the single walk; nearest-wins unchanged. |

**Verify:** migrate a copy of a real library three levels deep; every count on every screen agrees
with what an export of the same scope actually contains.

---

### Sprint 2 — Windows, and the chronological library

| Ticket | Work |
|---|---|
| **TX-2.1** | Windows define membership: a recording inside one belongs to that event. Retires the suggestion mechanism. |
| **TX-2.2** | Overlap allowed where people differ, refused where they do not, naming the event it collides with. A recording in an unresolvable overlap stays unfiled and says which events it could belong to. |
| **TX-2.3** | Library in chronological order: events and loose recordings interleaved by span at every level, openable to any depth. This is the primary view. |
| **TX-2.4** | Recordings filed into any event, including one that already holds events. |
| **TX-2.5** | Event type: free text, suggested from types already used, shown on the card. |
| **TX-2.6** | Export source picker and analysis scope read the shared walk. Closes the "0 events" defect at its root. |

**Verify:** scroll to a festival, open a day, open an artist, see its recordings — then export that
artist and confirm what the picker said matches what came out.

---

### Sprint 2b — Collections become sets

| Ticket | Work |
|---|---|
| **TX-2b.1** | A collection references events and recordings, many-to-many. No window, no parent, no ownership. |
| **TX-2b.2** | A second library view listing collections, alongside the chronological one. |
| **TX-2b.3** | Add to a collection from an event, a recording, or a multi-selection. |
| **TX-2b.4** | A collection is an analysis and export scope, exactly like an event. |

**Verify:** a "Festivals" collection holding two festivals months apart, analysable as one scope,
with both still in their right place on the timeline.

---

### Sprint 3 — Categories as axes

| Ticket | Work |
|---|---|
| **TX-3.1** | Tags created inline where applied; the axis chosen or created in the same gesture. The management screen goes; the concept stays. |
| **TX-3.2** | One tag per category per recording, enforced on write: applying a second replaces the first rather than being refused, because the user's intent is plainly to change it. |
| **TX-3.3** | `AnalysisSplit` — person, child event, tag-within-category, or event type — as an explicit choice, separate from scope. |
| **TX-3.4** | Split comparison: one lane per value, with a per-lane summary. This is the feature the whole document is for. |
| **TX-3.5** | An axis is offered for splitting only where the scope contains two or more of its values — an axis with one value is not a comparison. |
| **TX-3.6** | Scope refinement: the analysis lists what is in scope and any child event or loose recording can be excluded. Excluding an event excludes its subtree. Everything included by default. |
| **TX-3.7** | `excludedFromParentAnalysis` on an event — set once on a break, honoured in every roll-up, overridable per analysis. Never affects the library, the timeline or an export. |
| **TX-3.8** | Exclusions persist with a saved analysis and are transient otherwise, because "not part of this analysis" is a fact about the analysis rather than about the event. |

**Verify:** "compare my rate across characters" answerable in two taps from a scoped analysis, and a
Day 1 average that excludes the merch queue.

---

### Sprint 3b — Place and time zone

| Ticket | Work |
|---|---|
| **TX-3b.1** | `LocationEntity` — name, time zone, optional picture and coordinates — with a registry screen beside People and Watches. `EventEntity` gains `locationId`; `BpmRecordEntity` gains an override and the resolved `timeZoneId`. |
| **TX-3b.2** | `LocationResolver` — nearest-wins up `EventTree`, the same shape as `CoverResolver` and `EffectiveTagsResolver`. Pure, tested, and the only definition of "where was this". |
| **TX-3b.3** | Optional coordinate capture from the device, behind a permission the app never requires. No network, no Play Services: the framework location provider, or nothing. |
| **TX-3b.4** | `reconcileTimeZones()` — resolves and writes `bpm_records.timeZoneId`, on the same closed trigger list as membership and by the same single-writer rule. |
| **TX-3b.5** | One formatter that takes an instant and a zone. Every timestamp in the app goes through it: library, timeline, record screen, analysis, export. A recording renders in *its* clock everywhere, with the zone shown where it differs from the reader's. |
| **TX-3b.6** | Location on the event editor: pick from the registry, or make one in the same gesture — the same inline pattern as tags. |
| **TX-3b.7** | Location as a comparison axis — `SplitAxis.Place`, offered on the same two-or-more rule as the others, and working whether or not coordinates were ever captured. |
| **TX-3b.8** | Windows are entered and displayed in the event's own zone. This is the correctness half: a window typed as 21:00 at the Gorge must mean 21:00 there. |

**Verify:** a west-coast festival and an east-coast one in the same library, each reading in its own
clock on every screen; a window typed at one of them claiming the right recordings; "the Gorge
against Showbox" answerable as a split; and all of it working with the radio off and location
permission refused.

**Risks worth naming before starting:**

- **TX-3b.5 is the large one.** Every timestamp render is a call site, and the ones that get missed
  are the ones nobody looks at twice. Worth finding them by making the zone-less formatter
  impossible to call rather than by grepping.
- **A window is a wall-clock time in a zone, not an instant.** Storing the instant is right, but the
  editor must convert through the event's zone both ways, and the conversion has to survive the
  event's location being changed afterwards — at which point the honest behaviour is to keep the
  wall-clock reading and move the instant, since "the set started at nine" is what was meant.
- **Daylight saving.** A window spanning a transition is an hour longer or shorter than it looks.
  Correct, and worth a test rather than a discovery.
- **Deleting a location** must orphan rather than cascade, like every other reference in this model.
  An event whose venue was deleted keeps its recordings and its times; it simply stops saying where.

---

### Sprint 4 — Filtering

| Ticket | Work |
|---|---|
| **TX-4.1** | One search-and-chips bar; every dimension is a chip. |
| **TX-4.2** | The filter dialog goes. |
| **TX-4.3** | Saved views, pinned to the library. |

**Verify:** "Kyle at Coachella" in one gesture from an empty filter.

---

### Sprint 5 — One detail screen

There are four screens showing "a thing and its numbers" — a recording, an event, a collection, and
a scoped analysis — and they overlap almost entirely. Each was added when its subject was added,
which is the same way the taxonomy accumulated.

They are one screen. **A detail page is a subject and its analysis**, and §3 already established
that an analysis is a scope plus a split. A recording is simply the narrowest scope there is.

So tapping anything opens the same page:

| Tapped | Subject shows | Scope of the analysis |
|---|---|---|
| Recording | Title, when, who, watch, tags, cover | That recording |
| Event | Name, type, window, what is inside, tags, cover | That event and its subtree |
| Collection | Name, what is in it | Everything it references |

The subject section differs because the things genuinely differ. Everything below it — the chart,
the split control, the readings, the zones, the export button — is one component pointed at a
different scope.

**Tapping opens; the chevron expands.** Those are two different questions — "what happened here"
wants the page, "is this recording already filed" wants a peek without losing your place — and the
library already makes that distinction. This sprint makes it lead somewhere consistent.

| Ticket | Work |
|---|---|
| **TX-5.1** | One `DetailScreen(scope)` with a subject header per subject type and one shared analysis body. |
| **TX-5.2** | `BpmRecordScreen`, `EventAnalysisScreen` and the group analysis screen fold into it. Four screens become one. |
| **TX-5.3** | Tapping a recording, an event or a collection anywhere in the app opens it. Chevrons keep expanding in place. |
| **TX-5.4** | Scope refinement (TX-3.6) and the split control (TX-3.3) available on every one of them, because they are the same body. |
| **TX-5.5** | Breadcrumb up the tree from any subject, so no page is a dead end — a set walks up to its day, its festival, and any collection referencing it. |

**Verify:** the same question — "what happened here, and how does it split" — asked of a recording,
a set, a festival and a collection, on one screen, with one control.

---

### Sprint 6 — Colour discipline

| Ticket | Work |
|---|---|
| **TX-6.1** | Audit every palette in the analysis tabs against §5. |
| **TX-6.2** | Zones defined in `BpmPalette` as the deliberate third scheme. |
| **TX-6.3** | Split-by-tag lanes take a defined neutral series, not an invented one. |

**Verify:** one person across the library, a comparison, a zone breakdown and an export — one colour
throughout.

---

## 7. Resolved

1. **Both words kept, split by relation.** An event is the timeline — nesting, time-bounded, where a
   recording lives. A collection is an arbitrary set — many-to-many, no time, a second view rather
   than a second home. Each event carries a free-text type, and that is what keeps the app out of
   any one domain. See §2.1, §2.3, §2.4.
2. **Windows swallow.** A recording inside a window belongs to that event — the window is the
   membership rule, not a suggestion. Filing is only for recordings outside every window.
3. **Windows may overlap where the people differ.** Two stages at one festival is the normal shape
   of a festival, so the key is (time × person) rather than time alone. Overlap in both is still
   refused, because it genuinely has no answer. Nesting is not overlap. See §2.2.
4. **One tag per category per recording.** A category is a partition, so lanes sum to the whole and
   no total double-counts. Where two values genuinely apply, the answer is a value that says so.
5. **Membership is stored, computed by one function, written by one step.** The deepest window
   containing a recording holds it. The column is recomputed on a short closed list of mutations
   rather than on every read, because events rarely move and reads are constant. What keeps it
   honest is that callers never write it and a test checks it against the pure result. See §2.1.
6. **Loose recordings sort by time, not into a bucket.** A stretch back at camp appears between
   Day 1 and Day 2, because that is when it was. See §3.2.
7. **A scope may exclude parts of itself.** Day 1 without the merch queue is a different and better
   number than Day 1 with it. See §3.1.
8. **A location is a registry entry, like a person or a watch.** Name, time zone, optionally a
   picture and coordinates. Made once and pointed at, so renaming fixes every event and comparing
   works on identity rather than on two strings matching. See §2.7.
9. **The time zone is chosen, not derived.** Someone naming a venue knows what time it is there, so
   asking once beats a bundled boundary dataset of several megabytes that can be subtly wrong near
   a border. This is what removed the only dependency this feature would have needed.
10. **Location lives on the event, and recordings inherit it.** A venue is a property of the
    occasion, so it is set once a night rather than once per watch. The same nearest-wins walk as
    tags and covers, with a recording able to override.
11. **The resolved zone is frozen onto the recording.** Same reasoning as membership: rare to
    change, constant to read, and checkable against the pure function that produced it. A backed-up
    recording carries its own clock rather than depending on a tree.
12. **Coordinates are optional and informational.** Capturable from the device where permission is
    granted — a GPS fix needs no network — and nothing depends on them. A location typed by hand is
    worth exactly as much as one captured automatically.
13. **Nothing added here touches the network.** Zone ids come from the platform tzdata; coordinates
    come from the device receiver. The app keeps working with the radio off, which is the condition
    it is most often used in.

### Still open

Nothing blocking. Three things to decide while building:

1. **Is a full recompute still cheap at ten thousand recordings?** The design says recompute
   everything rather than the affected part, because a partial recompute is a second definition of
   what changed. If a full pass becomes noticeable on a real library, scope it by the window that
   moved — but measure first, and keep the pure function as the only definition.
2. **Should `excludedFromParentAnalysis` be implied by type?** A "Break" is almost always excluded,
   and typing one could set the flag. Convenient, but a type quietly changing a number is the kind
   of helpfulness that is hard to find when it is wrong.
3. **What happens to a window when its event's zone changes?** The reading — "the set started at
   nine" — is almost certainly what was meant, so the wall clock should hold and the instant move.
   But that silently reassigns membership, so it wants a word on screen rather than being done
   quietly. Decide when TX-3b.8 is built, not before.
