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

Nothing outstanding. The two defects §8.6 found are fixed, and the one this stretch introduced —
tapping a collection navigating nowhere — was caught before it shipped.

### 10.2 Incomplete

| | Why it matters |
|---|---|
| **No rule editor for a smart collection** (§8.9). Built from the filter bar, edited by re-applying and re-saving. | Enough to use, not enough to call finished. |
| **Exclusions have no UI** (§8.9). Column, resolver and tests exist. | "Not that one" is one context-menu item away. |
| **Freezing is only reachable from Save.** | It is a property of a selection now, so it belongs on the collection too — and so does thawing. |

### 10.3 Unlovely

| | Why it matters |
|---|---|
| **Empty states across the new pages are untested.** A recording with no readings, an event with nothing in it, a collection whose rule matches nothing. | The three states most likely to be someone's first experience of a page. |
| **Compare and Recordings each render their own metric selector.** Correct — it sorts both — but they have not been seen side by side. | Two selectors that disagree about which is selected would be worse than the pinned one it replaced. |
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
| **"Remove from event" was gone** | Back, as an optional per-row action on the shared Recordings section. Offered only where the scope is something a recording can be *taken out of* — an event. A collection's membership is edited from the collection; a filter's is not membership at all. |
| **"Save CSV" was gone** | Back, in the recording's overflow. |
| **The library's duplicate event editor** | Deleted. It now opens `EventEditorLauncher`, the same one the event's page uses. Tags and the photo are deliberately *not* offered from the timeline row: both need the thing they describe on screen to be worth editing, and opening the event is one tap from the same row. |
| **The collection route was registered at `"/{groupId}"`** | Fixed. A Kotlin string template eaten by a shell substitution — the fourth of that kind this stretch, and the reason string templates now go through the editor rather than through `perl`. |
