---
name: lefi-yamim-7
description: Build reviewed Hebrew learning booklets by days or topics from pristine interior and cover PDFs, with measured progress checkboxes and an embedded app progress map.
---

# לפי ימים 7

Use this skill when asked to prepare an אוצרות יהודים booklet. Version 7 is separate from version 6 and HFNAYA. The public build entry point is `scripts/build_booklet.py`; copied legacy scripts supply assets and layout utilities, not an alternative completed version 7 workflow.

Read `references/source-review.md` before reviewing a new source, and `references/validation.md` before delivery. Build only the editions requested by the user. Always start from pristine interior and cover PDFs; never patch a previously generated booklet or change source PDFs.

## Original typography

Use the owner-approved 2026-11-08 `assets/font-profile.json` for every added component: David regular/bold for contents and controls, Guttman Hatzvi Bold for headings, and David Bold for day-card titles. The owner approved David Bold instead of unavailable Guttman David Bold, and the uploaded TZVIB.TTF Hatzvi version. The original October profile is archived as `assets/font-profile-2026-10-20.json`.

All three approved font binaries serving four roles are committed under `assets/fonts/`. New chats need only cover/interior uploads. Read `START-HERE.md` and run `scripts/check_font_files.py` before installing or running tests. `--fonts-dir` and `OTZAROT_FONTS_DIR` are optional overrides. The uploaded font library is an archive, not automatic fallback. Builds verify hashes, family/style and character coverage and write `qa/font-audit.json`; unapproved substitutions still stop the build. Original study typography stays in the source PDF.

## Prepare and review

Install `requirements.txt` in a virtual environment. Use the existing cloud checkout; do not create another worktree unless requested.

```sh
python scripts/inspect_source.py --interior INTERIOR.pdf --cover COVER.pdf --output-dir review
```

Inspect the discovered topics, day markers, rotation, raster pages and gray future material. Complete a copy of `review/answers-UNCONFIRMED.json`. Source and cover SHA-256 bind every answer to these exact inputs. Read issue, parsha and Hebrew date label from the cover, and record the actual Sunday as ISO `booklet.week_start`; the computer's current date is not the booklet date.

Ask the owner which SOURCE pages belong to the preceding week's Mishnah Berurah, Likutey Moharan, Likutey Halakhot and Gemara. Ask one unresolved question at a time and show a page image when requested. A printed page number differs from a source PDF index. Never infer previous-week ownership from a page's position. Explicit null means the owner confirmed that no such page exists; an unanswered value is not null. Reuse answers already given for these exact files. `examples/845-reviewed-answers.json` preserves the four explicit answers and reviewed boundaries for the supplied Toldot files; its hashes prevent reuse with different inputs.

Owner preference, 2026-10-28: attach an actual rendered page preview to every source-page question, including each completion page and duplicate-amud question. Do not require the owner to have the booklet open on their computer. Show the image inline with the question and include a clickable image link if needed; page numbers alone are insufficient.

Review the seven real Tehillim day boundaries and the end of Psalm 150. Set `tehillim.boundaries_reviewed` only after inspection; record post-Psalm exclusions as described in the source review. Extra prayers and helpful verses are preserved but receive no study boxes. Do not invent sample day sequences. Keep provenance for manual answers and semantic review.

Before layout, inspect the actual Gemara amud identities as well as daily page counts. Restore the Skill 6 manual gate: any duplicated amud or unusual daily run requires one owner question at a time confirming whether A, B or both are duplicated and the affected daily source range. A duplicate can straddle two apparently normal four-page runs, so counts alone are insufficient. Never call a duplicate an auxiliary page or remove its progress box without the owner's source classification. Preserve both copies and all their commentary; copies of the same amud on the confirmed day share one field and one progress unit. Record the complete reviewed daily partition in `gemara_review` in the source-hash-bound answers, never by editing derived `day_pages` or PDF pages. Unconfirmed, incomplete or contradictory review stops before layout. See `references/source-review.md` for the input contract.

## Agreed placement rules

All checkbox rules below apply to current-week study pages only. Previous-week completion pages keep their content and navigation without progress boxes.

* On landscape pages, measure the full column INCLUDING commentary as reference height H and only the study text as L. Use ceil(3L/H), between one and three boxes. An exact third gets one; slightly above one third gets two; slightly above two thirds gets three. Distribute along the study portion, ending with it. Commentary determines H but receives no boxes. Full neighboring columns establish the reference extent when a last column is short; never normalize a short final column to its own height. Straight pages retain three per study column.
* Apply measured regions to Mishnah (before Bartenura), Zohar (before translation), current black Shulchan Aruch, Likutey Moharan, Likutey Halakhot, Sifrei Rabeinu, Sefer Hamidot, Shemot Tzadikim and Likutey Tefilot. Hamidot and Tzadikim are separate subjects on the right and left halves.
* Shulchan Aruch uses the inherited visual `באר הגולה` boundary templates at render zoom 2.2, with minimum match score 0.55, independently in each landscape half (one page boundary in portrait). Include all black study styles above that boundary, including Rema and chapter headings; exclude gray future material. Measure each study column separately and keep the agreed thirds against the full column including commentary. Never choose one font subset or hardcode source font sizes for this subject. A missing/weak boundary stops the build for review. Check coverage against original glyphs, including every active column, and recheck the saved PDF's counts against the measured heights. A fully gray column correctly receives no boxes. Save `qa/shulchan-coverage.json` and the independent round-trip coverage audit in `validation.json`.
* Zohar uses each half's `תרגום לשון הזוהר` heading as its semantic study/translation boundary, including legacy Hebrew PDF encoding. Measure every black study style above it, including Tosefta; exclude gray prior/future text. Font subsets can alternate within one column, so selecting a single font is forbidden here. An absent or ambiguous heading stops for source review. H still includes translation; gray-only columns receive no boxes.
* Nach gets one box after the verses and before commentary in each half: four per day. Mishnah Berurah gets one at the lower right of each page.
* Gemara gets one at the lower left of each actual Gemara page, with amud A/B separate. Commentary-only pages get none. Lower-corner boxes first use the established row and horizontal search; if a low source line blocks it, search up to 12 pt below that row within the same corner, still requiring empty space and unchanged page dimensions. Two booklet pages belonging to the same amud share one PDF parent field with synchronized children, not two independent progress units. Keep each physical occurrence's `pages` list separate from the logical field's combined list. Joining shared PDF fields must preserve every existing navigation link, including its destination point across unequal-height pages. Recreate links from resolved snapshot coordinates after copying widgets; verify this through the full build and saved-file validator, not only through the joining helper. All internal links, including TOC/day cards, must land at `(0,0)` on their intended target.
* Tehillim uses the same measured thirds on actual daily Psalm portions. A new day may start inside a column. Exclude text after Psalm 150, future gray reading and adjacent non-Tehillim pages.
* Parsha and Tikkun Haklali receive no study boxes.
* Previous-week completion pages receive **no checkboxes**, regardless of topic. Preserve their source content, `week_offset: -1` page records, and reciprocal links to the current week's reading. They are reference/completion material, not progress units of this booklet. No previous-week checks may appear in the embedded map or placement manifest. This owner correction supersedes older outputs that included separate previous-week progress fields.
* Continuation labels are exactly `העמוד הקודם` and `העמוד הבא`. Preserve the correct subject/day destination. In the visible reading orientation, previous is at the upper right, next at the lower left, and return-to-contents at the upper center. Keep these anchors at every page size, including landscape pages. Never relocate a button, swap edges, or rotate it into a side margin. Only an actual checkbox collision permits proportional reduction, no smaller than 75%, within the original footprint, keeping the right edge fixed for continuation buttons as in the approved 25% example (and the top center for return); shrink its clickable rectangle with it. Overlap of the original translucent button with source text or a heading is intentional and allowed; it is not a layout failure. Preserve size and font. Owner update 2026-11-08: halve every return-to-contents background/decor alpha (main fill 238→119, highlight 128→64); draw text/icons afterward at their original opacity. Continuation controls keep their original alpha. This does not authorize any change of anchor, hit area or page dimensions. Source text or another control alone does not authorize shrinking. A checkbox or another clickable control must never be covered, even when the button is transparent. If no allowed size fits safely, record the conflict and withhold the delivery PDF for review. Preserve the original page dimensions; adding margins or enlarging a source page to fit controls was explicitly rejected by the owner. Continuation text uses the original 29-pixel font on the 4× raster (7.25 pt at full button size), never an enlarged font fitted to the shorter generic wording. Normalize PDF rotation physically before placement so text, links and widgets share coordinates.

## Build

Use a reviewed independent `check_booklet.py` from Claude's `claude/android-reader` branch, after reading its `SKILL7-FOR-GPT.md`. Keep its provenance/hash in the validation report. The output directory must be fresh.

```sh
python scripts/build_booklet.py --interior INTERIOR.pdf --cover COVER.pdf --answers reviewed-answers.json --fonts-dir ORIGINAL_FONTS_DIR --output-dir build-days --checker CHECKER.py --edition days
```

For the requested topic edition use `--edition topics` and a different output directory. The builder saves source ownership, page layout, measurements, source-only PDF and a canonical map in `qa/`. It embeds `otzarot-progress-map.v1.json` into the final PDF. Generate map rows from actual planned widgets; never guess a fixed number of boxes. Previous-week material has `week_offset: -1`. Keep mixed-day reading regions, topic/day IDs, RTL columns and shared-field identity consistent with actual widgets.

A candidate remains in `qa/` if validation fails. A delivery filename is created only after internal source/map/widget/link checks and the independent checker pass. Fix the general rule and rebuild into a fresh directory. Do not silently weaken a checker to pass a broken file.

```sh
python -m unittest discover -s tests -v
```

Inspect representative rendered pages and complete daily Tehillim sequences before claiming visual approval. Report exact edition, input identity, validation results and outstanding limitations. Share the output and code through the destination already authorized by the user; do not assume permission to send email or upload to an unrelated service.

Likutey Halakhot placement, 2026-11-08: determine the body size by character-weighted size frequency per column, including every body font/style. Smaller inline text remains study; small notes outside the body horizontal footprint do not extend it. Keep those notes as collision obstacles. Use bounded side lanes 2–12 pt from study and at least18 pt from the paper edge. Try the existing outer side, then the clear gutter; intermediate checkpoints may shift at most12 pt vertically, final checkpoints only0–6 pt upward. Preserve the agreed count (portrait three per column), original page size and controls. If no clear nearby lane fits, stop instead of drifting to the paper edge. Independently remeasure and validate the saved widget lanes.

## Owner question format for Gemara duplication — 2026-11-08

The owner explicitly restored this workflow: state one day and its full one-based SOURCE-page range in the uploaded interior, then ask which amud is duplicated. Use: **״יום רביעי — עמודי מקור16–21 בקובץ הפנים. איזה עמוד כפול: א׳, ב׳ או שניהם?״** Wait for the answer, record it against the source hashes, then ask the next unresolved day. Do not lead with the detected pair, ask the owner to confirm two identical photos, or present a table of predicted answers. The owner's short answer ״ב״ confirms B for that stated day/run. Show a photo if requested for duplication questions; this explicit preference supersedes the earlier mandatory-photo rule for those questions only. Completion-page questions still include a page preview. Keep all copies and commentaries; the internal identity review must independently agree with the owner's answer before building.
