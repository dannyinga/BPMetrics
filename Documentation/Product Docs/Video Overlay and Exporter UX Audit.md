# Video Overlay and Exporter UX Audit

*Audit, November 2026, after S14. Covers the graph as it is drawn over footage, and the export utility's Look step and preset menus. Doesn't reopen the export model (Model and Export Consolidation §6).*

## How this was done

- **Renders:** every built-in preset was drawn at 1, 2, 3 and 5 people with the real renderer. The backgrounds were a dark concert stage and a bright, busy daylight scene, with seven variations on top. The cast is the Boogie T @ The Granada cast (Danny, Colin, Stina, Bethany, Tyler) and a long real-world title. The images below are from that run.
- **The editor:** the Look step was walked on the Pixel_7_Pro emulator, and every control was inventoried from the code (sections, labels, defaults, the preset field each one writes, and how deep it's nested).

## Checked against real footage

Danny supplied four clips: an arena show (March), a daytime crowd at the Gorge, a laser set at night (both May, Bass Canyon), and Boogie T at The Granada (October). Every preset was re-rendered over frames from them. The images in this doc stay as the stand-ins, because the repo is public and the real frames show people in the crowd.

The real footage confirmed everything below, and made O1, O3 and O5 look worse:
- **O1:** at 5 people over the Granada footage, Colin's pill is cut off.
- **O3:** "Landscape · lower third" over the Gorge has a plot that's one line across the crowd.
- **O5:** with no panel over the daytime crowd, the curves disappear completely, and only the pills are readable.

The curves in those renders are still the test harness's made-up heart rates. The in-app preview of the Granada clip shows the real data, and it backs up O2: the scale runs 50–200, with all five curves inside a band about 20 bpm tall.

**Fixed during the audit: clip times shown in the wrong zone.** The Gorge clip (`20260524_200337.mp4`) shows Danny's watch at 0:03, reading "101 · Recording… 1s". The library agrees:
- filming started at 03:03:38.5 UTC (the file's end stamp minus its 37.5 s length);
- the recording started at 03:03:39.3;
- the first reading is 101 bpm.

So the data lines up to within a second, and the overlay at 0:02 read 100 against the watch's 101.

**The problem** was in the labels. The clip card and "Filmed at" said **10:03 PM** (the phone's Central time), while the render printed **8:03 PM** (the file's −07:00). None of the Gorge events has a place, so these two screens fell back to the phone's zone, skipping the file's offset that the render uses. Anyone who "corrects" 10:03 to the 8 PM they remember moves the clip two hours away from its recordings.

**The fix:** `ClipRetiming.clockFor` gives the card, the clip selector strip and "Filmed at" the render's own rule (venue, then the file's offset, then the phone). Unit-tested; on the emulator the card now reads 8:03 PM.

**Fixed after the audit: clips ran about a second late** (`a7c21cb`). The watch's own timer showed the press 1.6–2.1 s into the Gorge clip; the app put it 0.8 s in.
- **The cause:** Samsung stamps a clip when it finishes *saving*, not at its last frame. Across 88 clips on Danny's S23 Ultra, the stamp less the clip's length landed 0.8 s after the time in the file's name, whatever the length, size or resolution.
- **The fix:** the camera's file name (`20260524_200337.mp4`) is the start, plus half a second for rounding, whenever it agrees with the stamp to within 5 s. Otherwise the stamp less the length less 0.3 s (the 0.8 s save, less the 0.5 s lost to whole-second rounding).
- **A side effect:** clips copied back onto a phone, whose modified time is the copy, used to be read a whole clip-length out. The name now places them.
- **Checked:** the Gorge clip now starts at 8:03:37.5 PM, putting the press 1.75 s in. In a render, every frame showing the watch at "101 · 1s" has the overlay at 101.

**Fixed after the audit: the preview ran the curve ahead of the footage** (`e307412`). A clip that starts before the recording does has the footage before the press skipped in the render; the preview read from the top of the file instead, so it was out by that much (1.75 s at the Gorge, 0.8 s before the timing fix). The preview now skips the same footage, shows the exact frame once the playhead rests (it used the nearest keyframe, up to half a second away), and its length matches the render's.

**One extra finding:** on a 9:16 story with the graph in the lower third, the panel sits over the stage, which is the thing being filmed. That's an argument for the default story framing sitting lower, below the subject and above the platform's caption area.

## The overlay in one paragraph

Given room, the overlay looks good (image 5). The problem is that the defaults rarely give it room, and the layout doesn't adapt when it's short of space:
- **The curves are flat.** The vertical scale covers the whole recording but only 30 seconds are visible, so every curve is a flat line.
- **Readout pills overflow.** At 3 people they run past the bottom of the panel, and at 5 the last person isn't drawn at all.
- **Names disappear on stories.** On 9:16, the format most clips get posted in, names never fit.
- **One built-in is broken.** "Landscape · lower third" is too short for its own axis labels.
- **No panel means invisible curves.** Over bright footage, curves without a panel vanish.

## Part 1. The graph over footage

Severity is how much of a real export it spoils: **High** breaks or hides content, **Medium** makes it hard to read, **Low** is polish.

### O1. Readout pills overflow the panel from 3 people up *(High)*

![Landscape, five people](Overlay%20Audit/01-landscape-five-people.png)

- **Every preset has this.** At 3 people the third pill hangs below the panel. At 5, the fifth (Colin) is cut off by the panel edge, and on landscape most of a fourth goes too.
- **The cause** is that each pill has a minimum height (`48 × scale`), and that floor wins over fitting the column. The code comment says so: "the floor keeps a crowded session legible even if the column then runs slightly past the graph". At 5 it's well past "slightly".
- **It matters now:** the Boogie T footage is a 5-person case.
- **Fix:**
  - The column must always fit.
  - When pills would go below a readable height, switch layouts instead of overflowing: two columns, or a row of compact pills along the panel's bottom edge.
  - Never drop a person.

### O2. The curves are flat because the scale is the whole recording's *(High)*

- **The cause:** the BPM range comes from every reading in the recording (25–150 here), and the window shows 30 seconds. Heart rate rarely moves more than a few bpm in 30 s, so every curve is a near-horizontal line. That's true in all the renders, and for a 2-minute window too.
- **The result:** the graph, which is the reason the overlay exists, shows almost nothing. The pills carry all the information.
- **Fix:** a **"Scale: follow what's on screen / whole recording"** choice, defaulting to follow.
  - The range follows the visible window plus a margin, eased over about a second so it doesn't jump.
  - Gridlines stay on round numbers, via ChartKit.
  - "Whole recording" stays for anyone comparing absolute levels across a set.

### O3. "Landscape · lower third" is broken as shipped *(High)*

![Lower third, two people](Overlay%20Audit/02-lower-third-two-people.png)

- **The plot is a few pixels tall.** The panel is 25% of the height, and the title, axis and labels take most of it.
- **The BPM labels pile on top of each other:** 150/125/100/75/50/25 stacked into a smudge.
- **Pills don't fit:** one pill sticks out of the panel, and a second is cut off.
- **Fix:**
  - The layout should drop what doesn't fit, in a fixed order: BPM labels, then gridlines, then the time axis.
  - Pills go horizontal on a short panel (O1).
  - Add a reference test for this preset so it can't regress silently.

### O4. Names never show on Story or Square *(High for 9:16)*

![Story, names cut](Overlay%20Audit/03-story-names-cut.png)

- **What happens:**
  - At 1–3 people, pills read "D…", "S…", "T…", and Colin's name doesn't appear at all.
  - At 5 people the names do show, because the pills shrink.
  - The title is cut too: "Excision B2B SLA…".
- **The cause:** the pill's width is capped at 22% of the panel and doesn't grow with its height (noted in S14's "As built").
- **Fix:**
  - Size the pill from what it has to say: the name's width, up to a cap tied to the panel *and* the row height.
  - If it still doesn't fit, put the name under the number at full width.
  - Let the title wrap to two lines, or shrink to a floor, before cutting it.

### O5. Over bright footage without a panel, the curves disappear *(Medium)*

![No panel, bright footage](Overlay%20Audit/04-no-panel-bright-footage.png)

- **What happens:** the pills survive because they have their own backgrounds. The curves are thin colored lines with nothing behind them, so they vanish over sky and bokeh.
- **Who it affects:** "Story · minimal" ships exactly this setup, with no panel.
- **Fix:** a **contrast outline** setting (off / soft shadow / outline) on curves, playhead dots, the clock and the title, defaulting to soft shadow when panel opacity is under about 30%.

### O6. Everything is small at the defaults *(Medium)*

![Given room](Overlay%20Audit/05-given-room.png)

- **The defaults:**
  - The panel is centered, half the width, in the lower third.
  - At 1080p the axis labels are about 15 px tall and the time ticks ("−10s / now / +10s") smaller still.
  - On a phone, where these clips are watched, both are unreadable.
- **With room:** the same renderer at nearly full frame (above) is clear and good-looking.
- **Fix:**
  - **A larger default frame.**
  - **One "Text size" control** for title, clock and axes. Today only the pills have size controls.
  - **A readable floor:** a minimum on-screen text height, relative to the canvas's short edge, that every label respects.

### O7. Footage of a different shape gets black bars *(Medium)*

- **What happens:** the canvas size comes from the preset, and footage is shrunk to fit it ([VideoExporter.kt:298](../../mobile/src/main/java/inga/bpmetrics/export/VideoExporter.kt#L298), `LAYOUT_SCALE_TO_FIT`). A portrait phone clip exported with "Landscape 1080p", the default, comes out as a narrow strip between two black bars.
- **Fix:**
  - Make **"Match the clip"** the default canvas.
  - Keep fixed shapes as a choice, with a fill/fit option, for anyone posting to a specific format.

### O8. Smaller things *(Low)*

- **The single-person pill has a different style:** a black background with no colored border, while multi-person pills have one.
- **The wordmark is tiny,** at the panel's bottom-right corner (variation renders).
- **Fixed colors:** gridlines, axis labels and the low/high ramp colors are stored on the preset, but nothing in the UI edits them.
- **The clock** is always in the header. The Readouts hint says it "takes the other side" from the pills (it doesn't; see E6).

## Part 2. The export utility and preset menus

### E1. The editor has no room *(High)*

![Look step](Overlay%20Audit/06-look-step.png)

- **The layout:**
  - On a Pixel 7 Pro, the pinned preview, step bar and bottom bar leave about a quarter of the screen for controls.
  - Those controls are six collapsed accordions holding about 35 settings.
  - The sections all re-collapse after rotation or a trip to a picker.
- **The preview is small:** about 390 × 215 dp, with the graph inside at about a third of that.
- **The result:** you can't judge legibility in the place you're editing it.
- **Full screen doesn't help with landscape clips.** The full-screen preview stays portrait, so a 16:9 clip uses about a quarter of the screen with black above and below.
- **The handles cover what they frame.** The drag handles sit on the panel's edge, on top of its title and the wordmark (in the Granada preview, the top-middle handle covers the "@" in "Boogie T @ The Granada").

### E2. The controls don't say whether they edit the preset or this one clip *(High)*

- **Framing writes to the clip:** while a clip is previewed, which is almost always, the drag, the placement chips, Center and Width write *that clip's* override. They still sit inside the preset's Graph section, and nothing on screen says so.
- **The override leaks into presets:** "Save as…" and "Update" then bake the clip's frame into the preset.
- **Per-person-count frames are invisible:** frames saved with "Use this framing for N-person clips" have no list and no way to clear them. The Settings editor only edits the base frame, so those edits may never reach some exports.

### E3. Two preset editors that have drifted apart *(Medium)*

The Look step and Settings › Export › Edit preset both use `LookSections`, but around it they disagree:

| | Look step | Settings |
|---|---|---|
| **Built-ins** | Can't be updated | Can be overwritten and renamed |
| **Import** | Applies the preset at once, discarding unsaved edits without asking | Only saves it |
| **Export** | "Export to file", `.bpmpreset` | "Share" (actually a save dialog), `.bpmpreset.json` with an unsanitised name |
| **Leaving with unsaved changes** | — | Back discards them without asking |
| **"Choose a video"** | Works | Present, but does nothing |

Settings also says "The look itself is edited in the export utility" right next to its own Edit button.

### E4. "Default preset" doesn't do what it says *(Medium)*

- **By design** (Audit §3), a new export opens with the *last-used* look, and the default applies only when there isn't one ([ExportUtilityViewModel.kt:103](../../mobile/src/main/java/inga/bpmetrics/ui/export/ExportUtilityViewModel.kt#L103)).
- **So after the first export,** "Make default" and the Settings radio button change nothing, and the hint "What a new export opens with" is wrong.
- **Fix:** label it honestly ("Start from: last used / ⟨preset⟩"), or let the default win.

### E5. Presets mix up shape and look *(Medium)*

- **Shapes:** three of the five built-ins are only canvas sizes, which repeat the Canvas chips.
- **Looks:** the other two are real looks.
- **Similar names that differ:** "Lower third" as a placement and "Landscape · lower third" as a preset are different rectangles.
- **Fix:** with "Match the clip" (O7), a preset becomes purely a *look*, and the shape is chosen per export.

### E6. Switches and hints that mislead *(Medium)*

- **"Live stats" hides a whole section's effect:** the switch, in Graph, turns off the pills, while the Readouts section stays fully editable.
- **The clock hint is wrong:** the Readouts "Side" hint says the clock takes the other side. The renderer always puts the clock in the header.
- **"Trailing opacity" is the wrong word:** it fades what's *ahead* of the playhead.
- **Ambiguous names:** "Labels" means axis numbers, "Width of the frame" means the panel, and "cast" is jargon.
- **Unhelpful offset hint:** "Sync offset" says to watch the preview, which in Settings has no footage. (The preview snapping to the nearest keyframe was fixed in `e307412`.)

### E7. Sync is split in two *(Medium)*

- **"Filmed at"** is per clip and sits above the sections.
- **"Sync offset"** is per preset and sits last in the last section.
- **Fix:** both answer "when was this filmed", so they belong together, per clip, with the offset as a nudge.

### E8. Smaller things *(Low)*

- **Hidden selection:** Canvas chips only light up on an exact pixel match, so 3840×2160 shows nothing selected. The same goes for frame rates outside the five chips.
- **Small targets:** the preset overflow button is 32 dp with a 16 dp icon, and Delete is four taps deep.
- **Two names for one field:** "Heading" (Look) and "Title" (Contents, for images) edit the same field. For a video batch it replaces every clip's caption, and the hint doesn't say so.
- **Silent Next:** Next is greyed out on Source with no reason shown.
- **Dead UI:** a "Video: … / Remove" block can never appear.

## Part 3. The Boogie T cast: why a clip offers 4 people, not 5

Each clip only offers people whose recording **overlaps that clip's own time**. At Boogie T @ The Granada:
- Danny, Colin, Stina and Bethany started at about 04:11 UTC.
- Tyler started at 04:49.

So any clip filmed before 04:49 offers 4. That's deliberate: a person with no readings during the clip would draw nothing. It doesn't fit "the footage should have the option of up to 5", though.

**Decided (2026-11-06): (a).** Built in S14B.6, limited to people with a recording within two hours of the clip, so a festival source doesn't offer another day's recordings. The two directions were:
- **(a)** Show everyone in the event on every clip. People who weren't recording at that point appear greyed, with "No readings during this clip", and can still be ticked. Their curve starts when their watch does.
- **(b)** Keep it as is, but say why someone is missing.

**Checked with the real clip:** `20261017_002335.mp4` was filmed at 00:23:35 CDT, which is 05:23:35 UTC. That's after Tyler started, so all five overlap it.
- On the emulator, with the same library, the exporter finds the clip at 12:23 AM ("Filmed at … America/Chicago", correctly read from the file) and offers all five: Tyler, Bethany, Stina, Colin and Danny.
- Samsung stamps the file with the time filming *ended* (05:24:12). The exporter already accounts for that.

So if the phone offers fewer than five for this clip, the cause is on the phone, not in this rule. Likely causes:
- the copy being used has a different date (copies made in a gallery app can be re-stamped);
- or the phone is on an older build.

A screenshot of the clip card on the phone would settle which.

## Proposed overhaul

One sprint for the renderer and one for the editor, before S15. Each would get a reference test per built-in at 1, 3 and 5 people, so this audit's failures can't come back silently.

### S14A: An overlay that fits what it's given

**Built** (see "S14A" in the Release 1.0 Work Plan for what was done and how it was checked).

| Ticket | Work | Findings |
|---|---|---|
| **S14A.1** | The pill column always fits: it switches to two columns or a horizontal row before it overflows, and never drops a person. Pills size from their content, so names show on 9:16. | O1, O4 |
| **S14A.2** | Vertical scale follows the visible window (eased), with whole recording as a choice. | O2 |
| **S14A.3** | Layout degrades in order (BPM labels → gridlines → time axis) instead of overlapping. The title wraps or shrinks before it's cut. A readable floor applies to all text. | O3, O4, O6 |
| **S14A.4** | Contrast outline for curves and text over footage. | O5 |
| **S14A.5** | "Match the clip" canvas as the default, with fill/fit for fixed shapes. A bigger default frame. One style for the pill at every cast size. | O6, O7, O8 |

### S14B: One look editor with room to work

**Built** (see "S14B" in the Release 1.0 Work Plan). Presets as looks only (E5) is the part not done.

| Ticket | Work | Findings |
|---|---|---|
| **S14B.1** | One editor, used by both the export flow and Settings. A large preview on top, and a bottom panel of short tabs (*Layout, Graph, Readouts, Text & color, Time, Signature*) in place of six accordions. | E1, E3 |
| **S14B.2** | Tap something in the preview (the pills, the title, the clock, the wordmark) to jump to its settings. | E1 |
| **S14B.3** | A visible scope switch: "This look" vs "Only this clip". Per-person-count frames are listed and can be cleared. | E2 |
| **S14B.4** | Color controls for the fields that already exist (grid, labels, low/high ramp), plus text size and contrast outline. | O8, "fully customizable" |
| **S14B.5** | Sync in one place, per clip. Honest labels (E6). The default preset reworded or made to win. Presets become looks only. | E4–E8 |
| **S14B.6** | The Boogie T cast fix, in whichever direction Danny picks (Part 3). | Part 3 |

## What would help from Danny

1. ~~Real clips.~~ Done: four clips supplied 2026-11-05 (see "Checked against real footage"). They're kept out of the repo, which is public.
2. **Where they get posted** (Stories, Reels, TikTok, YouTube, kept private). That decides the default canvas and frame, and the safe areas to keep clear of platform buttons.
3. **One or two overlays you like** from other apps (screenshots are enough), for direction on density and style. They'd only be a reference; the result stays BPMetrics' own design.
4. **Your look.** If there's a look you keep coming back to, export it as a `.bpmpreset` and it can become a built-in.
5. **Decisions:** the Boogie T direction (Part 3), whether the default preset should beat last-used (E4), and whether "Match the clip" should be the default canvas (O7). **Decided 2026-11-06:** everyone at the show (a), the last look wins, and "Match the clip" is the default canvas on a fresh install.
