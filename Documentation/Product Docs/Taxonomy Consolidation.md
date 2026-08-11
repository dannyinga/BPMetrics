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
| **Events of one type** | Which concert? Which raid? | The type, and the same tree walk |
| **Recording** | Which single recording was the hardest? | Nothing |

The middle two did not exist and are the most valuable. They are also the reason §2.5 reverses
course on categories.

Each is a **partition** — every recording in scope falls in exactly one lane. That is why §2.5
allows only one tag per category, and it is what lets a comparison show percentages that add up.

### 3.0 An event type is a category, and its types are the values

"Event type" and "events of one type" are the two halves of one idea, and the app shipped only the
first half. **Event type** answers *concerts or sports games?* — one lane each. The question anyone
asks immediately afterwards is *which concert?*, and there was no way to ask it: **child event** puts
every event in the scope side by side, so a year's concerts sat scattered among its sports games
with nothing separating them.

So an event type behaves exactly like a tag category. `Character` is an axis whose lanes are
Spiderman and Hulk; `Concert` is an axis whose lanes are Subtronics, Excision and the rest. One is
offered per type that holds more than one event, on the same rule as everything else: an axis with
one value is not a comparison.

The residue is labelled differently, and that difference is the point. A recording with no character
tag is **Unlabelled**. A sports game inside a Concert comparison is not unlabelled — it has an event
type, it simply is not this one — so its lane says **Other event types**. It is still there, because
the lanes have to sum to the whole, but calling it unlabelled would be a lie about the data.

### 3.0.1 The scope's own event is never a lane

A festival's loose recordings carry the festival's id. Compare them by child event and the festival
becomes a lane holding everything, drawn at full width, ranked first — a bar that says nothing and
wins every comparison it appears in. The same event also appeared in its own *exclusion* list, which
offered to empty the page you were looking at.

Both come from the same gap: the analysis knew its records but not what it was an analysis **of**.
The scope's root event id is now threaded through, and it is excluded from the lanes, from the axes'
qualifying counts, and from the refinement sheet. An axis that only qualified because of the root is
not offered at all.

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

### Sprint 4b — One list, and the sort decides its shape

Timeline and Recordings were two views over the same recordings, chosen with a segmented control.
The timeline *is* the library in chronological order — so the second view was the first one sorted
differently, and the switcher was asking the user to pick between "sorted by time" and "sorted by
time, but flat".

The duplication did what duplication does. The timeline read the whole library while the flat list
read the filtered one, so **the Sprint 4 filter bar did nothing at all in the view the app opens
on** — the fifth instance of two sites deriving the same thing and drifting.

One list. **The sort decides the shape.** Chronology is the only ordering the tree means anything
under, because a festival is a stretch of time containing stretches of time. Sorting by peak rate
cannot nest — "which set went hardest" is a question about recordings, and the answer is a list of
them. So sorting by time draws the events and everything else draws them flat, and the sort control
names the shape it produces rather than leaving it to be discovered.

A collection is a different *relation*, not a different sorting, so it is a door in the app bar
rather than a third of a switch.

| Ticket | Work |
|---|---|
| **TX-4b.1** | The timeline reads the filtered records, and prunes to branches that hold one. |
| **TX-4b.2** | Sort by time draws the tree; every other sort draws the flat list. |
| **TX-4b.3** | The Recordings/Timeline/Collections switcher goes; collections get their own screen. |
| **TX-4b.4** | Reversing flips the top level only — inside a festival, Day 1 still precedes Day 2. |
| **TX-4b.5** | The sort is persisted, since it now decides the shape the library reopens in. |

**Verify:** filter to one person and the timeline shows only the events they recorded at; sort by
peak and the same filter still holds over a flat list; expansion survives the round trip.

---

### Sprint 5 — One detail screen

> Read §8 first — §8.1–8.5 is the model, §8.8 is what already shipped as Sprint 4c. Then §9: the
> detail screen is where the library's load cost lands, and §9.3 is much cheaper to build into it
> than to retrofit through it. Collections, saved views and saved analyses are one thing with three settings for
> how membership is decided, and the detail screen is a *scope* — so §8.3 has to land before, or
> with, this sprint. Building four screens into one on top of two disagreeing membership rules just
> moves the disagreement somewhere harder to see.

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

---

## 8. Selections: collections, views and saved analyses are one thing

Written before Sprint 5, because "one detail screen" cannot be got right while the app disagrees
with itself about what a detail screen is *of*.

### 8.1 There are two kinds of thing here, not four

**A property** is what a recording *is*: its person, its watch, its place, its time, the event it is
filed under, and its tags. Properties attach to records and events and are inherited down the tree.

**A selection** is a named set of recordings you want to look at again.

Collections, saved views and saved analyses are all selections. They differ in exactly one respect —
**how membership is decided** — and that is one axis with three settings, not three kinds of object:

| Today's name | How membership is decided | Picks up a recording added tomorrow? |
|---|---|---|
| Collection | Enumerated references, resolved through [EventTree] at read time | Only if it lands under a named event |
| Saved view | A predicate, re-asked every time | Yes |
| Saved analysis | Frozen copies of the rows and their numbers | Never |

The code already half-knows this. `AnalysisScope` says it outright — "a filter and a group are two
ways of naming the same kind of thing" — and `liveFactory` and `groupFactory` build the *same*
ViewModel. The only reason there are two factories is that membership is resolved twice.

### 8.2 One entity, three optional parts

```
Collection
  name, notes, cover, pinned
  members     explicit event and record references     (may be empty)
  rule        a filter, serialised                     (may be null)
  exclusions  things struck out by hand                (may be empty)
  frozen      a snapshot of rows and numbers           (may be null)
```

- **members only** — today's collection.
- **rule only** — today's saved view; a *smart* collection.
- **members and rule** — the thing asked for during Sprint 2b: every Subtronics recording, plus
  these three that belong with them anyway.
- **frozen** — today's saved analysis.

`members ∪ rule − exclusions`, resolved now rather than when the collection was made.

**Exclusions belong on the selection, not on the screen.** `ScopeExclusions` is transient today, so
saving a refined analysis silently loses the refinement. Putting them here is what makes "everything
by Kyle except the one where the strap was loose" expressible without abandoning the rule.

**Freezing stays, as a checkbox rather than a concept.** A deleted recording's data points are gone
and its numbers cannot be recomputed, so freezing has to remain possible — but it should be the
exception someone chooses, not the only way to keep an analysis.

### 8.3 One resolver, which is the part that matters

```kotlin
sealed interface ScopeRef {
    data class Recording(val recordId: Long)
    data class Event(val eventId: Long)            // the subtree
    data class Collection(val collectionId: Long)  // members ∪ rule − exclusions
    data class Query(val filter: FilterState)      // ad hoc, not yet saved
    data class Frozen(val collectionId: Long)
}

object Scope { fun recordsIn(ref: ScopeRef, library: Library): List<BpmRecord> }
```

Everything resolves through it, **including the filter bar's Collection dimension**. That is not a
tidiness argument: filtering by a collection and opening that collection are two derivations of one
answer today, and §8.6 shows they already disagree.

A rule may name a collection, so resolution carries a visited set. A cycle resolves to empty and the
editor refuses to create one — the same discipline as `EventTree.wouldCycle`.

### 8.4 Tags stay separate, and here is the test

A tag is a **property of a thing**; a collection is a **set of things**. The distinction earns its
keep because a tag is a value on an **axis** — its category — and an axis is what a comparison split
runs on. "Artist › Subtronics" against "Artist › Excision" is a comparison. "Festivals" is not a
value of anything and cannot be compared against its siblings, because it has none.

> If it is true of the recording, it is a **tag**.
> If it is a bag you assembled by hand, it is a **collection**.
> If it is a question asked about properties, it is a **smart collection**.

A smart collection whose rule is a single tag is redundant with the tag, and the editor should say
so rather than let two names for one set drift apart. Smart collections earn their keep on compound
questions — "Kyle, at a festival, over 180" — which no single tag expresses.

### 8.5 What this makes Sprint 5

**A detail screen is a scope, its numbers, and a split.** §3 already established the second half;
§8.3 supplies the first. There is then exactly one screen:

| Subject | Scope |
|---|---|
| A recording | `Recording` — a scope of one; most splits unavailable |
| An event | `Event` |
| A collection, a view, a saved analysis | `Collection` |
| Whatever the filter bar currently says | `Query` |

with one action — **Save this as a collection** — turning a `Query` into a `Collection`. That action
*is* the saved-analysis feature, live by default rather than frozen.

It also settles where collections live. A pinned selection belongs in the filter bar beside the
saved views it is now the same thing as, not behind a button in the app bar. The library's actions
go back to sort, filter and select.

### 8.6 What the audit found on the way

Three defects, all the same failure: **Sprint 2b changed what the word "group" meant, and three
sites kept the old meaning.**

1. **Filtering by a collection is broken, invisibly.** `FilterState.selectedGroupIds` means three
   different things. `addFilterTerm(COLLECTION, …)` writes **collection ids**; `applyFilter` matches
   them against **the event's parent event id** (`groupIdByEvent = events.associate { it.eventId to
   it.parentId }`); `FilterChips.of` renders the chip by looking for an **event** with that id.
   Adding a collection filter therefore narrows the library by an unrelated rule *and renders no
   chip*, so there is nothing to tap to undo it. The library shrinks with no visible cause.

2. **Tagging a collection writes to the wrong table.** `groupFactory` passes a collection id to
   `ScopeTagging`, which calls `addTagToGroup` → `addTagToEvent`, inserting the collection id into
   `event_tag_cross_ref.eventId`. That column carries a foreign key to `events`, so the write either
   throws or tags whichever unrelated event happens to share the number.

3. **Dead weight the fold left behind.** `event_groups` and `event_group_tag_cross_ref` are still
   registered entities with no readers, and the old `LibraryFilterDialog` is still reachable from
   the saved-analyses screen despite TX-4.2 — a second filter UI writing `selectedGroupIds` with
   event ids, which is where the third meaning of the field came from.

### 8.7 Cost

A migration, 27 → 28:

- `collections` gains `filterJson`, `isPinned`, `frozenAt` and an exclusions column.
- `saved_views` rows fold in as rule-only collections; the table is dropped.
- `saved_analyses` rows fold in as frozen collections; `saved_analysis_records` re-keys to
  `collectionId`.
- `event_groups` and `event_group_tag_cross_ref` are dropped.

Then one resolver replaces `CollectionScope.recordsIn`, the `groupMatch` branch of `applyFilter`,
and the membership half of all three `AnalysisViewModel` factories.

### 8.8 What shipped — Sprint 4c

| Ticket | Work |
|---|---|
| **TX-4c.1** | `FilterState` and the predicate move to the library layer as `LibraryFilter`, because a collection's rule now needs evaluating below the UI. |
| **TX-4c.2** | `Scope` — one resolver for `Recording`, `Event`, `Collection` and `Query`, with a cycle guard. |
| **TX-4c.3** | `CollectionEntity` gains a rule, exclusions, pinning and `frozenAt`. Migration 27 → 28 folds `saved_views` and `saved_analyses` in, drops both plus the two dead `event_groups` tables. |
| **TX-4c.4** | Every membership question routes through `Scope`: the library filter, the collection analysis, the export scope, the collection cards, the repository. |
| **TX-4c.5** | Collection tagging removed; the pre-Sprint-4 filter dialog deleted. |
| **TX-4c.6** | Backup format 5 carries collections; the coverage test points at the entity in use. |

**Verify:** filter to a collection and the library shows exactly what opening that collection shows;
a smart collection picks up a recording imported afterwards; a saved view from before the migration
comes back as a pinned smart collection with the same question.

Four things worth knowing about the fold:

1. **Same-time analyses became live sets, not frozen ones.** A concurrent analysis never stored its
   curves — it stored *which* recordings and re-read them on open — so it folds in as a collection
   naming those recordings, with no `frozenAt`. `convertConcurrentAnalysesToEvents` is gone: its own
   reasoning was that rewriting `bpm_records.eventId` on a correlation the schema does not express
   was too dangerous for a migration, and it was right, so the migration does not do it. A set that
   would rather be an event can be promoted by hand.
2. **The re-key runs row by row in Kotlin.** `collections` and `saved_analyses` both autoincrement
   from 1, so their ids collide and no join can say which new collection a snapshot row belongs to.
   Reading `last_insert_rowid()` per row is slower and correct; the arithmetic alternative fails
   silently by attaching someone's numbers to the wrong set.
3. **An unreadable rule means *no* rule, not an empty filter.** An empty `FilterState` selects the
   whole library, so the usual lenient fallback would turn "every Subtronics recording" into
   "everything" — and a set that has quietly grown is far harder to notice than one that has quietly
   shrunk. `FilterCodec.parseOrNull` exists for exactly this asymmetry.
4. **Pinning defaults to off for hand-made sets.** Folding views and collections together would
   otherwise put every set anyone ever made into the filter bar. A saved view carried its own
   pinning across.

### 8.9 Still open

1. **There is no rule editor yet.** A smart collection is created from the filter bar — build the
   question, then "Save view" — and edited by applying it, changing the filter and re-saving. That
   is enough to use the feature and it is not enough to call it finished; a collection's own screen
   should show its rule as chips and let them be edited in place.
2. **Exclusions have no UI.** The column, the resolver and the tests are all there, so "not that
   one" is one context-menu item away. It wants to live on the detail screen Sprint 5 builds rather
   than being bolted onto the collection card first.
3. **Freezing is only reachable from the analysis screen's Save.** Now that it is a property of a
   selection, "freeze these numbers" belongs on the collection too — and so does the reverse.

---

### 8.10 Collections replaces the Analysis tab

The navigation consequence of §8.1, finished rather than left half-done. §8.5 said a collection is a
pinned selection and does not belong behind a button in the library's app bar; it went into an
overflow menu instead, which was worse — a door to another section sitting beside the controls for
the list in front of you.

The real point is sharper than placement. **The Analysis tab and the Collections screen were two
lists over one table.** A saved analysis is a collection with `frozenAt` set, so Analysis was showing
a filtered subset of what Collections shows all of, with its own screen, its own empty state and its
own "new" flow.

And **analysis is not a place.** §8.5 has a detail screen as a scope, its numbers and a split — so
you analyse by *opening* something. A tab named after the verb implied a fourth kind of thing to go
and find, when the answer was always "open the recording, the event, or the collection".

So the bar is **Collections · Library · Export**, and `SavedAnalysesScreen` is gone.

The four ways to name a scope each have exactly one door:

| Scope | Door |
|---|---|
| A recording | Open it from the library |
| An event | Open it from the timeline |
| A collection | Open it from Collections |
| A question | **Analyse**, on the filter bar |

That last one was missing. Retiring the pre-Sprint-4 filter dialog in §8.6 took the only way to
analyse the library as currently filtered, and nothing replaced it — an analysis could be run over
everything or over a saved collection, but not over the question in the bar. It is a chip beside
*Save view* now, which is the right neighbour: the two things worth doing with a question you have
just built are keeping it and answering it.

Two scopes that are not collections and never will be sit in the Collections overflow: **Analyse
everything**, the one scope that always exists, and **Compare at the same time**, which is picked by
hand in the library because "these three, which overlapped" is not a filter.

The library's app bar is back to four: sort, filter, select, import.

## 9. Why the library is slow, and what to store

Written before Sprint 5, because the detail screen is where the cost lands and retrofitting a fix
through a finished screen is the more expensive order.

The instinct is to cache the analysis: an event hardly changes once it is set, so why recompute its
numbers. The instinct is right about the waste and wrong about where it is. **The analysis is not the
expensive part, and an event is the least safe thing to key a cache on.**

### 9.1 Three layers, and only one of them is heavy

**Load.** `LibraryRepository.records` is a `@Transaction SELECT * FROM bpm_records` with an
`@Relation` onto `bpm_data_points` — so it pulls **every reading in the library** — held permanently
in a hot `StateFlow`. Room's invalidation tracker re-runs the whole query on any write to
`bpm_records`, `bpm_data_points` or `record_tag_cross_ref`, which means renaming one recording
rebuilds every data point in the library.

At the ~1 Hz Health Services samples at, a four-hour set is about 14,400 rows. Two hundred hour-long
recordings is roughly 720,000 objects, resident, permanently.

**Reduce.** `AnalysisRecord.from` makes two O(points) passes per recording — `calculateActiveDurationMs`
and `BpmZones.split` — and allocates one `Pair` per point on the way through.

**Present.** Cheap, and not worth discussing.

The library list, the timeline, the filter, every count and the summary analysis read **none** of the
data points. They are loaded, held and rebuilt for the benefit of the four screens that draw curves.

### 9.2 Store what the reduction derives

`minId`, `avg` and `maxId` are **already columns**. The only per-point numbers a summary needs are
the active duration and the zone split — and both are functions of data that never changes after
ingest. A recording's points are written once and never edited; merge and split create new records
rather than rewriting old ones.

`SavedAnalysisRecordEntity` already persists exactly those two fields. The frozen path proved they
are storable; the live path recomputes them forever.

Put `activeDurationMs` and the zone breakdown on `bpm_records`, computed at ingest, and **a summary
analysis needs no data points at all.**

The zone split is a function of [BpmZones.DEFAULT], which is four absolute, hard-coded bands —
deliberately not a percentage of anyone's maximum, since these are recordings of people at gigs
rather than athletes on a plan. So the stored split never goes stale. Making the bands configurable
later would mean recomputing every row, and that repass has to be built in the same change or the
stored figures quietly describe the old bands.

### 9.3 Split the stream

`records` becomes points-free: `BpmRecordEntity` and its tags. Points load per scope, on demand, for
the four things that genuinely draw curves — the recording chart, the event overlay, the same-time
overlay and export.

This fits Sprint 5 exactly. The detail screen already takes a [ScopeRef], so it asks for that
scope's points and nothing else asks at all.

### 9.4 Then do not cache

After §9.2 and §9.3 a scope's summary is a fold over N small rows. For any library that will
plausibly exist, that is microseconds — and caching it would buy nothing measurable while
introducing an invalidation problem.

**And the invalidation problem is the wrong way round from the intuition.** A *recording's* numbers
never change, which is why storing them is safe. *Event membership* changes constantly: a window
moves, `reconcileMembership` reruns, a rule-backed collection re-answers on every write. So anything
keyed on an event or a collection is the least safe thing to cache, and that is precisely what a
cache-the-analysis design would key on.

The one place caching earns its keep is **curve data for a chart, per scope, in memory for the
session**. Not persisted: a stored downsampled curve is a second definition of the curve, which is
the failure this whole document exists to unwind.

### 9.5 Measured

`startRecordFlowFromDB` logs what the stream actually holds. On the real library, 2026-08-09:

```
~8874 KB resident — about 162,000 data points
```

Which settles it, in three parts:

**Memory is not the problem.** 8.7 MB against a 192–512 MB heap is noise, and anyone reaching for a
cache to save memory is solving the wrong thing.

**The reload is.** Room maps 162,000 rows through a cursor and allocates 162,000 objects every time
anything writes to `bpm_records`, `bpm_data_points` or `record_tag_cross_ref` — so on every rename,
every tag change, and every `reconcileMembership`. Analysis then walks the same points twice more
and allocates a `Pair` per point.

**The trajectory decides it.** This is linear in recording-hours, and recording-hours is what the app
exists to accumulate. One festival weekend — four people, ten hours a day, two days — is roughly
288,000 points, nearly triple the whole library as it stands today. A design that is merely
uncomfortable now is unusable after two more festivals.

So §9.2 and §9.3 are worth building, and worth building **before** the Sprint 5 detail screen rather
than after: the screen is the thing that decides what gets loaded, and retrofitting a points-free
stream through a finished screen is the more expensive order. They are a pair — §9.3 cannot happen
until §9.2 has moved the two derived numbers out of the points.

The log line goes once that work lands. It is there to answer one question and it has answered it.

### 9.6 What shipped — Sprint 4d

| Ticket | Work |
|---|---|
| **TX-4d.1** | `bpm_records` gains `activeDurationMs` and `zonesEncoded`; migration 28 → 29 adds the columns and nothing else. |
| **TX-4d.2** | `DerivedFigures` — one definition of both, called at ingest and by the backfill. |
| **TX-4d.3** | `backfillDerivedFigures`, gated on `activeDurationMs IS NULL` rather than a preference. |
| **TX-4d.4** | `BpmRecord` loses its readings; `BpmRecordWithPoints` is the type that has them. |
| **TX-4d.5** | `recordsWithPoints(ids)` / `recordsWithPointsIn(scope)`; the four curve-drawing screens load their own scope. |
| **TX-4d.6** | `BpmRecordEntity` covered by the backup coverage test, which had never asserted it. |

**Verify:** the library, the timeline and a summary analysis touch `bpm_data_points` not at all; a
recording chart, an event overlay, a same-time overlay and an export each load only their own scope.

Four things worth knowing:

1. **The absence is a type, not an empty list.** `BpmRecord` has no `dataPoints` field at all. A
   sometimes-empty list would draw a flat line and report zero, which looks like an answer; a
   missing type does not compile. Seven of the seventeen consumers of the library stream needed
   readings, which is far too much surface to leave to a convention.
2. **The backfill's gate is a query.** `WHERE activeDurationMs IS NULL` rather than a "done" flag,
   so a pass that dies halfway finishes next launch and a row arriving without the figures is
   repaired rather than wrong forever. This is why the column is nullable: zero is a real answer
   for a recording with no readings, and a default of zero would make the two indistinguishable.
3. **The bands and the active duration do not sum to the same number, and never have.**
   `BpmZones.split` walks consecutive *pairs*, so the last reading has no successor and contributes
   no time; `activeDurationOf` closes that final interval with the recording's own length. They
   differ by exactly one interval. Found while writing `DerivedFiguresTest`, whose first version
   asserted they agreed. Neither is wrong — a band cannot know how long the last reading held, and
   a duration can — so storing both preserves the difference exactly rather than quietly moving
   every zone breakdown in the app. It is now pinned by a test that says so.
4. **§9.2 was wrong about the zone bands.** It said the split depends on the user's resting and
   maximum figures and would need a repass when they change. It does not: `BpmZones.DEFAULT` is
   four absolute, hard-coded bands, deliberately not a percentage of anyone's maximum. So the
   stored split never goes stale. **If the bands are ever made configurable, the repass has to be
   built in the same change** — otherwise every stored figure quietly describes the old bands.

### 9.7 Still open

1. **`RecordMerge.gapMs` now works from metadata**, so a recording with no readings at all counts
   toward the span in the preview while contributing nothing to the merge itself. A degenerate case
   in a preview dialog, noted rather than fixed.
2. **The backfill has not been run against a real library.** It is bounded, batched and idempotent,
   but 162,000 readings is the first honest test of it, and that happens on device.
3. **Nothing caches, and nothing should yet.** §9.4 still holds: measure again once this has
   landed. The remaining per-scope loads are bounded by the scope, and a scope is a few recordings.

---

## 10. Polish

A running list, kept here rather than discovered one message at a time.

Sprints 4b through 5 moved several thousand lines of UI in a short stretch. The structure that came
out of it is right — one library, one selection, one resolver, one detail screen — but the *surface*
has not been looked at with fresh eyes, and a fold that is correct can still leave a page that reads
badly. This section is where that goes.

**The rule for this list:** an item earns a place by being a thing someone would notice, and it
carries the reason it is wrong. "Tidy the header" is not an item. "The collection page shows a
counts card while every other subject shows its cover" is.

Ordered by whether the app is *wrong*, *incomplete*, or *unlovely* — in that order, because a page
that lies is worse than one that is missing something, which is worse than one that merely looks
unfinished.

### 10.1 Wrong

**A database that will not open must not kill the app.** It did. `LibraryDatabase.getInstance` is a
default constructor argument to `LibraryRepository`, so the first touch of `libraryRepository` opens
the file — and that first touch was in `Application.onCreate`, on the main thread. Anything wrong
with the database therefore ended the process before a pixel was drawn, identically on every
subsequent launch, with the automatic pre-migration backups sitting one directory away and
completely unreachable.

The trigger was mundane and will happen again: an **older build installed over a newer library**.
A build from `1ca70d5` onwards upgrades the file to version 30; a build from before it expects 29,
and databases only ever go forwards. Room says *"a migration from 30 to 29 was required but not
found"*, which is accurate and tells the person holding the phone nothing — least of all that their
recordings are completely fine.

So the open is guarded and its failure is kept rather than thrown, and `RecoveryScreen` says what
happened. It reads the version straight off the file, so it can tell the two cases apart: a library
newer than the app is **not damaged and must not be restored over** — restoring would work and would
throw away everything recorded since the backup — while a library that genuinely will not open gets
the backup list and a way back. Both beat a crash loop; telling them apart is the point.

Nothing outstanding. The two defects §8.6 found are fixed, and the two this stretch introduced —
tapping a collection navigating nowhere, and the export button doing nothing at all — are in §10.5.

**The export button is worth its own note**, because of how it failed. The fold wrote the button,
wrote the dialog, wrote the four navigation handoffs, and never wrote the six lines that render the
dialog. Everything compiled. Nothing warned. The flag was set and nothing read it, so a button on
four pages did nothing for two commits.

That shape — *state written, never observed* — is invisible to the compiler and to any test that
does not drive the UI. It is now checked for directly by `tools/dead-ui-flags.pl`, which walks every
`var … by remember { mutableStateOf(…) }` in the source and reports any whose only appearances are
writes. It found this one and one other, a leftover expansion set in the library that the ViewModel
had taken over.

```
perl tools/dead-ui-flags.pl $(find mobile/src/main/java -name '*.kt')
```

### 10.2 Incomplete

| | Why it matters |
|---|---|
| **Exclusions have no UI** (§8.9). Column, resolver and tests exist. | "Not that one" is one context-menu item away. |
| **Freezing still cannot be *started* from a collection.** Unfreezing can. | It is a property of a selection now, so setting it belongs there too, not only on Save. |

### 10.2.1 Living and static collections, finished

The model has carried both since §8: a rule re-asked every time the set is read, hand-picked
members that stay as you left them, and any mix. Only half was reachable. A rule could be created
exactly one way — narrow the library, press "Save view" — and changed exactly one way, by doing that
again. From the collection itself there was no way to see what it asked, and no way to give a
hand-made set a rule at all.

The editor now names both states and offers both directions:

- **Living.** A rule, edited in place through the same component the library's filter bar uses —
  they are the same object, and were being edited by two different pieces of UI.
- **Static.** A list. What is in it is what somebody put in it.

**Living → static keeps what the rule found.** This is the part that was missing rather than merely
unbuilt. "Stop using a rule" dropped the rule, and with it everything the rule had found that nobody
had also named by hand — right when the rule was a mistake, wrong when the rule was the *point* and
you now want the answer held still, which is the ordinary case. `materialiseCollection` writes the
current answer down as membership first. Still references, not copies: a recording renamed or
refiled tomorrow stays in the set and stays correct.

**Frozen is deliberately not on that axis.** It is a copy of the *numbers*, for recordings that may
not survive. Thawing keeps whichever still exist and hands the set back as a static one; the
snapshot rows are left in place, so thawing by mistake is not destructive.

**Creating one asks what a collection is.** It was a name field, which is enough for a static set
and nothing else — a living collection could not be *created* at all, only made afterwards by
editing one, so the useful half of the feature was reachable only by someone who already knew it
existed. The new dialog asks the two things that cannot sensibly be deferred: what it collects
(Static or Living, with the rule editor when Living) and whether it is pinned. A cover has no choice
but to wait, because a cover is stored against an id that does not exist until the dialog returns,
and the dialog says so rather than leaving someone hunting for a control that cannot be there.

### 10.2.2 Event type joins the filter

Compare has been able to split by event type since §3.0. The filter could not narrow by it — so
"every concert" was a comparison you could draw and not a library you could look at, and a living
collection of every concert was impossible to express. `FilterState.selectedEventTypes`, matched
through `FilterContext.eventTypeByEvent` because a type belongs to the *event* and a recording knows
only which event it is filed under. By the string rather than an id: an event type has no registry,
only a vocabulary that forms from use.

**The filter's dialogs are drawn now.** Choosing a dimension is a centred grid of tiles carrying the
glyph, the name and how many values it can offer, so a dead end is visible before it is tapped — it
was nine rows of plain text with a heading saying "Narrow by" above nine labelled buttons. Every
picker has a **back arrow** to that grid: one wrong tap used to mean closing the dialog and starting
again. The value picker stays open until Done and shows what is already on, rather than closing on
the first tap so that narrowing to three people meant opening the same list three times.

**Each dimension is drawn as the thing it is.** A person is a face and a colour, an event is a cover
and a place in a tree, a collection is a cover and a name — and the filter was the one screen in the
app where all three were a line of grey text. People get their avatar, events nest exactly as the
timeline does with their own covers, collections show theirs and whether they are living or frozen.
Venues, watches and event types stay as chips: a short string with nothing to draw.

**Tags are ticked, nested under their categories.** The flat "Character › Hulk" list is the right
shape for a chip and the wrong shape for choosing — the level people think at is the category, and a
flat list turns "any character" into eight taps with no way to see you got them all. The category
carries a tri-state box: ticking it takes everything under it, and it shows half-ticked when only
some are in.

**A tag term could match everything.** Found by ticking a tag and watching the library not narrow.
The tag term groups the chosen tags by category — two characters mean "either", a character and a
venue mean "both" — and it built that category lookup by *walking the records*. So a tag nothing
carried had no category, `mapNotNull` dropped it from the term, and a filter left with no terms
matches the whole library. Asking for an unused tag returned everything instead of nothing.

Two changes, and the distinction between them matters. The **fix** is that a tag's category comes
from the registry now (`FilterContext.categoryByTag`) — it is a fact about the tag, not about which
records happen to carry it. The **safety net** is that an id with no known category groups on its
own rather than vanishing, so a caller with no registry behind it — a frozen snapshot filtered on
its own terms — still enforces the term and still answers "nothing". Six tests, one of which was
written to fail first.

**Date and rate finally do something.** Both have been in `FilterDimension` since the filter was
built, both were listed and tappable, and both opened a value picker over a list that is empty by
definition — a range is not chosen from a list. That is also why `FilterEditor` now takes the whole
`FilterState` rather than emitting `(dimension, id)` pairs: a band is two numbers and cannot be
expressed as a term with an id, so the contract that covered seven dimensions could never have
covered these two. Date uses Material's own range picker with the end pushed to the end of its day;
rate is a slider over the **average**, said on the dialog because "over 180" means something very
different applied to peaks.

### 10.3 Unlovely

| | Why it matters |
|---|---|
| **Empty states across the new pages are untested.** A recording with no readings, an event with nothing in it, a collection whose rule matches nothing. | The three states most likely to be someone's first experience of a page. |
| **The subject header's height varies a lot by subject.** An event with a trail, a place and six tags is much taller than a bare recording. | The chart's position on screen moves between pages, which makes flicking between them feel unsteady. |
| **`RecordingSubject.kt` is 591 lines.** Lifted wholesale so the fold's diff stayed honest about what changed. | Now that it has landed, the split dialog and the insights section each want their own file. |
| **Sprint 6 — colour discipline** is polish of exactly this kind and is already written. | Worth doing as part of this rather than after it. |

### 10.4 Not on this list

Things deliberately left alone, so they do not get "fixed" by accident:

- **The zone bands and the active duration do not sum to the same number** (§9.6). Long-standing,
  understood, pinned by a test. Changing it moves every zone breakdown in the app.
- **`gapMs` counts a reading-less recording toward the span** in the merge preview (§9.7). A
  degenerate case in a dialog that describes an action rather than performing it.
- **The frozen path keeps its own snapshot table.** It looks like duplication and is not: a frozen
  selection's readings may be gone, which is the whole reason it was frozen.

### 10.5 Cleared

| | What was done |
|---|---|
| **A collection had no subject header** | `CollectionDetailScreen`, with the cover as its header like the other two. Its trail points *downward* into the events it names — a set does not nest, so it has nothing above it, and the way out of the page is inward. |
| **No breadcrumb from a collection** | The same trail. §2.4 satisfied for the third subject. |
| **"Remove from event" was gone** | Restored as a per-row X, then removed again — see the last row of this table. Refiling belongs in the recording's editor and in bulk edit, not on every row of a list. |
| **"Save CSV" was gone** | Back, in the recording's overflow. |
| **The library's duplicate event editor** | Deleted. It now opens `EventEditorLauncher`, the same one the event's page uses. Tags and the photo are deliberately *not* offered from the timeline row: both need the thing they describe on screen to be worth editing, and opening the event is one tap from the same row. |
| **The collection route was registered at `"/{groupId}"`** | Fixed. A Kotlin string template eaten by a shell substitution — the fourth of that kind this stretch, and the reason string templates now go through the editor rather than through `perl`. |
| **The Recordings tab was a comparison in disguise** | Folded into Compare as `SplitAxis.Recording`. A list of recordings ranked by low, average or peak *is* a comparison — it had its own metric selector, its own row layout, and answered the same question a second way. Compare took its look: flat rows with a dot, a name and a figure at a fixed right margin, instead of a stack of bordered cards that broke the column the eye reads down. A lane holding one recording opens it, whatever axis produced it. |
| **Compare could open on an empty screen** | An axis is selected by default, and tapping the selected chip no longer clears it. Comparing used to be a question you asked on purpose from a page of totals; now it is a tab, and arriving there *is* the question — so opening on chips above blank space asked for a tap before the screen would say anything. The tab is offered only when the scope has an axis, so there is always an answer to show. |
| **Three rows of identical chips** | Measure, metric and axis all rendered as `FilterChip`s, so nothing in the shape said which row was the question. Measure is now a segmented control; the metric is three coloured dials in the app's own low/average/peak colours, with the chosen one named beside them; only the axes stay as chips. The comparison bar takes the metric's colour too — it was always red, so a column of blue lows sat above red bars. |
| **The event being analysed appeared inside its own analysis** | §3.0.1. It was a lane in its own event comparison and a row in its own exclusion list. |
| **The refinement sheet put recordings after the subtree** | A day's loose recordings were emitted after all of its nested sets, so they read as belonging to the last set rather than to the day. Own recordings now come directly beneath their event, before its children. |
| **The X on a recording row** | Removed. Filing is edited from the recording or from a bulk edit; a destructive control on every row of a list you are reading is one mis-tap from a recording quietly leaving its event. |
| **The export button did nothing** | `ExportKindDialog` was written and never rendered — the button set a flag nothing observed. Rendered, on all four subjects. See §10.1 for the check that now catches this shape. |
| **The cover was edited from a dialog three taps from the cover** | The button is on the picture now — bottom right of the header, the one corner reliably empty because the writing is bottom left. The header is where the whole of what you are editing is on screen: the crop, what the writing does to it, how bright it is. It stays in the *library's* row editor, which has no header to put a button on. |
| **The edit modal was three headings and a paragraph** | Photo, Tags and What's included each had a heading, a button and — for one — an explanation, spent on three things that are all "this opens somewhere else". One row of buttons with icons and counts. |
| **A three-dot menu holding one item, and that item Delete** | Gone from the event page and the collection page. Delete sits at the bottom of the editor, below a rule, furthest from Save. |
| **Two selections that could not see each other** | Recordings were selected in the app bar; events in a strip above the list. Two counts, two close buttons, two back handlers, and no way to say "these two sets and that one recording" — an entirely ordinary thing to want. One selection now, in the app bar, saying *"2 events · 3 recordings"* when it is both. Every action goes through `selectedRecordIdsEffective`, where a chosen event contributes its whole subtree: analysing, filing and exporting are actions on *recordings*, and which kind of row was tapped to name them is not something they should care about. The event-only actions — move into, add to a collection — are in the same overflow as everything else. |
| **Filing a selection was three levels down** | Bulk edit → "File into an event…" → the picker. Filing is the most ordinary thing anyone does with a fresh selection, so **Add to an event…** is the first item in the selection's own overflow — it needs a selection, so that is where it belongs. Creating an empty event is a *library* action and sits in the library overflow. Both land in the same picker. |
| **Four action icons on the library bar** | Sort and filter are controls over the list you are looking at and stay. Select, New event and Import are things you *do*, and they are now one overflow. |
| **A selected event with a cover showed no sign of it** | The card tinted its container and the cover is drawn *over* the container, so on an event with a picture — which is most of them — selection was invisible. A 2dp outline now, the same one a recording tile uses. The tint stays for the ones with no cover; the border is what actually says so. |
| **Only titles were legible over a cover** | Fixed on the *picture*, after a wrong turn. A stroked outline on the text was tried first — it works, and it makes every letter look like a sticker; it also treats the symptom on the wrong object, because what is unreadable is unreadable on account of the photograph. Reverted. A cover now carries a **Darken** slider beside Soften: per-cover, because only some covers are too bright and dimming all of them is how every photograph becomes the same grey rectangle. `coverDim` on all three owners, migration 29→30. |
| **The comparison bar lied about close numbers** | Bars were scaled between the lowest and the highest lane, which uses the width well and is a lie in the case that matters: with two lanes, one is pinned empty and the other full by construction, so 151 against 152 drew as nothing against everything. Now zero to the highest figure in the comparison, so a bar is a proportion and two close numbers look close. |
| **Tags could push a header off the screen** | A recording inheriting its set's, its day's and its festival's tags easily carries a dozen, which wraps to four rows of chips over the cover they are covering up. Four, then "+N more", expanding in place. The header takes the tags as data now rather than a finished row of chips — it has to be able to count them — which also removed the second copy of that `FlowRow`. |
| **Video-or-image was asked twice, in the wrong places** | It was chosen on **Source**, where the answer changes nothing — the source is the same set of recordings either way — which meant step 1 asked two unrelated questions *and* every entry point outside the utility needed a modal asking it before it could navigate. It is now a segmented toggle at the top of **Contents**, which is the step it actually decides: a video picks clips to draw on, an image asks which recordings share a timeline. The pre-flow dialog is gone and `openExportOf` no longer carries a kind. |
| **The cover editor had no door** | `CoverCropDialog` — crop, pan, pinch, soften — was reachable from a person's profile photo and *nowhere else*. Every other cover imported straight to whatever the centre-fill happened to catch, permanently. `setCoverCrop` existed on three ViewModels with no caller on any of them. Wired for events, collections and recordings. |
| **…and then three doors** | The first fix put "Change photo", "Adjust" and "Remove" in the editor in front of the sheet, which is clunky to read and wrong in principle: two of those three act on a picture without ever showing it, and all three are things the sheet can do with the picture on screen. One button now. Editing a cover is: open it, choose or do not choose a photograph, frame it, save. `CoverCropDialog` takes a nullable cover and owns choosing and removing, so the same sheet is the empty state and the editor. |
| **The library could not set a photo** | The timeline row's editor deliberately omitted it — the reasoning was that a photo button with nothing to check it against was worse than no button. That dissolves once picking one opens the framing sheet, which *is* the picture at the size the library will draw it. Restored, with its own `setEventCover` on the library ViewModel. |
| **An individual recording's Highlights looped for ever** | A recording is the narrowest scope there is, so its own highest peak came from itself — and the new link pointed at the page it was already on, pushing a fresh copy onto the back stack every tap. `AnalysisViewModel` now knows which recording it *is*, and both Highlights and the Compare lanes ask for a way to open a record rather than being handed one: null means "that is here", and no link is drawn. |
| **The recording editor's tag button read " tags"** | The sixth eaten string template. Same as the event editor's, found the same way. |
| **Highlights were a dead end** | Every row is a statement about one moment — "Kyle hit 186" — and there was no way to go and look at it. "Highest peak" and "Peak came from" now open the recording behind them. "Most time recorded" deliberately does not: a total across eleven recordings is not any one of them, and opening the longest would answer a question nobody asked. |
| **The Summary opened on a chart of coloured bars** | Reordered to how the questions are asked: what stood out, who was there, then how the time was spent. "Where the time went" is the most detailed thing on the page and the least likely to be what someone came for, so it is last. |
| **The duration was a section heading** | Moved into the subject header beside the low, average and high. It is a total for the same subject as those three, not a label for a chart — and the heading it sat in was the only reason that section had to be at the top. |
| **Compare sat behind Timeline** | Second now. It answers the question people arrive with — which of these was the most — where the Timeline answers "what did it look like", which is a thing you go and look at rather than a thing you ask. |
| **The chart could be pinched and nothing said so** | Zoom and pan worked and were invisible: nothing on a chart announces a gesture, and there was no gesture at all for "put it back", so a chart somebody had pinched into stayed that way until they left the page. There are handles on those operations now, the visible span is stated — *"6m 20s · 9:14 PM – 9:20 PM"* — and a scrollbar doubles as the one thing a zoomed chart otherwise cannot say: where in the whole you are. |
| **Isolating a curve meant hitting the curve** | On a plot with six lanes that is a two-pixel target among five others, and it asks you to know whose line is whose *before* you can ask which one is whose. A strip of faces above the chart, each ringed in that lane's colour, does it standing still. |
| **Splitting had the fields but not the picture** | Two text fields behind a menu item, with no view of what was being cut; the chart could show the exact stretch and had no way to act on it. The **view window is the selection** now: pinch and pan until the chart is showing the set — that is the visual fine-tuning, done against the curve — then *Split this stretch* opens holding those two instants, to the second, for when you know it began at 21:00 exactly. Typed in the recording's own clock, so a set at the Gorge is entered in the times it happened at. Both doors call one `splitBetween`. |
| **The library ignored the Appearance setting** | Four places held their own patterns: the event card's span (`"d MMM"` and a 24-hour clock), the name the app invents for an untitled recording (`"d MMM, HH:mm"`), the analysis scope's date range, and the graph's scrub readout — which had a 24-hour formatter it never used, so a library set to 24 hours still read "10:30:00 AM" there. All through `StringFormatHelpers` now, so a card in the library reads the way the same event reads on its own page. Two exceptions kept on purpose: the event editor's window edges keep their **seconds**, because a window is a membership rule and "21:00" and "21:00:47" claim different recordings; and the export flow's typed time field keeps a fixed pattern, because it has to parse back what it prints. The year is no longer dropped for the current year — a nicety a format can afford while it owns its own pattern, and cannot survive somebody else's. |
| **`03/14/2026` and `9:14:07 PM`** | The default date format is `MMMM d, yyyy` — "August 5, 2026" — and it is the first option in Settings. A date on a detail page is read once and remembered, not scanned down a column, so making the reader parse a number before they know the month was work for nothing. Times drop their seconds: nothing this formats is meaningful to the second. The one exception is the clock burned into an exported video, which is *running* and would look frozen without them. |
| **The refinement sheet was not drawn as a tree** | `entriesFor` has returned a nested walk with a depth on every row since it was rewritten, and the dialog rendered them all flush left — so a festival's days, its sets, and the recordings inside those sets arrived as one flat column of forty checkboxes. It indents by depth now. The indent is the point of the sheet: it is where you see that unticking one row takes six others with it. |
| **Unticking an event left its children ticked** | The rule was always in the numbers — excluding an event excludes its subtree — but the sheet drew a scope that did not exist: Day 1 out, its six sets apparently still in. Rows under an excluded row now show unticked, dimmed, saying *"Left out with what it's in"*, and cannot be ticked back on their own. The way back is the parent's box, which is the only tick that would actually change anything. |
| **"Refine scope" sat in the middle of the Summary** | Moved into the event's edit modal, under **Analysis**, beside Tags and Photo. Deciding what an analysis covers is an edit to the question being asked; it was a text button next to the "Where the time went" heading, which is a place for a figure. `AnalysisScreen` still owns the sheet — the editor is rendered through a new `subjectEditor` slot that is handed the way in — so there is still exactly one implementation. A subject with no editor keeps the Summary's button rather than losing the door. |
| **The event editor's tag button read " tags"** | `"$tagCount tag"` with the template eaten by a shell substitution — the fifth of that kind. Found while adding the row next to it. |
| **Four copies of the export handoff** | One `openExportOf(source, kind)`. The four detail pages differed only in the `ExportSource` they named, and four copies of a handoff is four chances for one to land on a different step. Two further dead wires went with it: `openExport`, and the library's `onExportSelection` — the selection menu exports CSV and `.bpmjson` itself now, so the video/image path out of it had no caller. |
