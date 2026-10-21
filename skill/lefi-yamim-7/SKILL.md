---
name: lefi-yamim-7
description: Build reviewed Hebrew learning booklets by days or topics from pristine interior and cover PDFs, with measured progress checkboxes and an embedded app progress map.
---

# לפי ימים 7

Use this skill when asked to prepare an אוצרות יהודים booklet. Version 7 is separate from version 6 and HFNAYA. The public build entry point is `scripts/build_booklet.py`; copied legacy scripts supply assets and layout utilities, not an alternative completed version 7 workflow.

Read `references/source-review.md` before reviewing a new source, and `references/validation.md` before delivery. Build only the editions requested by the user. Always start from pristine interior and cover PDFs; never patch a previously generated booklet or change source PDFs.

## Original typography

The owner supplied the four original fonts on 2026-10-20. Use `assets/font-profile.json` for every added component: David regular/bold for contents and controls, Guttman Hatzvi Bold for cover headings/date/brand and the topics header, and Guttman David Bold for day-card titles. `DAVIDB.TTF` is Guttman David, distinct from David Bold in `davidbd.ttf`.

Font files are supplied separately. Put them in `assets/fonts/` locally, or pass `--fonts-dir /path/to/fonts`. Set `OTZAROT_FONTS_DIR` to the same directory when running helpers or tests. Builds verify SHA-256, actual family/style and character coverage, and write `qa/font-audit.json`. Missing or different fonts stop before the output directory is created. Do not choose an installed substitute or replace a complete date font to render a missing character. Inspect rendered examples against the accepted design before rebuilding. Original study-page typography remains part of the source PDF.

## Prepare and review

Install `requirements.txt` in a virtual environment. Use the existing cloud checkout; do not create another worktree unless requested.

```sh
python scripts/inspect_source.py --interior INTERIOR.pdf --cover COVER.pdf --output-dir review
```

Inspect the discovered topics, day markers, rotation, raster pages and gray future material. Complete a copy of `review/answers-UNCONFIRMED.json`. Source and cover SHA-256 bind every answer to these exact inputs. Read issue, parsha and Hebrew date label from the cover, and record the actual Sunday as ISO `booklet.week_start`; the computer's current date is not the booklet date.

Ask the owner which SOURCE pages belong to the preceding week's Mishnah Berurah, Likutey Moharan, Likutey Halakhot and Gemara. Ask one unresolved question at a time and show a page image when requested. A printed page number differs from a source PDF index. Never infer previous-week ownership from a page's position. Explicit null means the owner confirmed that no such page exists; an unanswered value is not null. Reuse answers already given for these exact files. `examples/845-reviewed-answers.json` preserves the four explicit answers and reviewed boundaries for the supplied Toldot files; its hashes prevent reuse with different inputs.

Review the seven real Tehillim day boundaries and the end of Psalm 150. Set `tehillim.boundaries_reviewed` only after inspection; record post-Psalm exclusions as described in the source review. Extra prayers and helpful verses are preserved but receive no study boxes. Do not invent sample day sequences. Keep provenance for manual answers and semantic review.

## Agreed placement rules

* On landscape pages, measure the full column INCLUDING commentary as reference height H and only the study text as L. Use ceil(3L/H), between one and three boxes. An exact third gets one; slightly above one third gets two; slightly above two thirds gets three. Distribute along the study portion, ending with it. Commentary determines H but receives no boxes. Full neighboring columns establish the reference extent when a last column is short; never normalize a short final column to its own height. Straight pages retain three per study column.
* Apply measured regions to Mishnah (before Bartenura), Zohar (before translation), current black Shulchan Aruch, Likutey Moharan, Likutey Halakhot, Sifrei Rabeinu, Sefer Hamidot, Shemot Tzadikim and Likutey Tefilot. Hamidot and Tzadikim are separate subjects on the right and left halves.
* Shulchan Aruch uses the inherited visual `באר הגולה` boundary templates at render zoom 2.2, with minimum match score 0.55, independently in each landscape half (one page boundary in portrait). Include all black study styles above that boundary, including Rema and chapter headings; exclude gray future material. Measure each study column separately and keep the agreed thirds against the full column including commentary. Never choose one font subset or hardcode source font sizes for this subject. A missing/weak boundary stops the build for review. Check coverage against original glyphs, including every active column, and recheck the saved PDF's counts against the measured heights. A fully gray column correctly receives no boxes. Save `qa/shulchan-coverage.json` and the independent round-trip coverage audit in `validation.json`.
* Zohar uses each half's `תרגום לשון הזוהר` heading as its semantic study/translation boundary, including legacy Hebrew PDF encoding. Measure every black study style above it, including Tosefta; exclude gray prior/future text. Font subsets can alternate within one column, so selecting a single font is forbidden here. An absent or ambiguous heading stops for source review. H still includes translation; gray-only columns receive no boxes.
* Nach gets one box after the verses and before commentary in each half: four per day. Mishnah Berurah gets one at the lower right of each page.
* Gemara gets one at the lower left of each actual Gemara page, with amud A/B separate. Commentary-only pages get none. Lower-corner boxes first use the established row and horizontal search; if a low source line blocks it, search up to 12 pt below that row within the same corner, still requiring empty space and unchanged page dimensions. Two booklet pages belonging to the same amud share one PDF parent field with synchronized children, not two independent progress units. Keep each physical occurrence's `pages` list separate from the logical field's combined list. Joining shared PDF fields must preserve every existing navigation link, including its destination point across unequal-height pages. Recreate links from resolved snapshot coordinates after copying widgets; verify this through the full build and saved-file validator, not only through the joining helper. All internal links, including TOC/day cards, must land at `(0,0)` on their intended target.
* Tehillim uses the same measured thirds on actual daily Psalm portions. A new day may start inside a column. Exclude text after Psalm 150, future gray reading and adjacent non-Tehillim pages.
* Parsha and Tikkun Haklali receive no study boxes.
* Continuation labels are exactly `העמוד הקודם` and `העמוד הבא`. Preserve the correct subject/day destination. In the visible reading orientation, previous is at the upper right, next at the lower left, and return-to-contents at the upper center. Keep these anchors at every page size, including landscape pages. Never relocate a button, swap edges, or rotate it into a side margin. Only an actual checkbox collision permits proportional reduction, no smaller than 75%, within the original footprint, keeping the right edge fixed for continuation buttons as in the approved 25% example (and the top center for return); shrink its clickable rectangle with it. Overlap of the original translucent button with source text or a heading is intentional and allowed; it is not a layout failure. Preserve the original background alpha, size and font. Source text or another control alone does not authorize shrinking. A checkbox or another clickable control must never be covered, even when the button is transparent. If no allowed size fits safely, record the conflict and withhold the delivery PDF for review. Preserve the original page dimensions; adding margins or enlarging a source page to fit controls was explicitly rejected by the owner. Continuation text uses the original 29-pixel font on the 4× raster (7.25 pt at full button size), never an enlarged font fitted to the shorter generic wording. Normalize PDF rotation physically before placement so text, links and widgets share coordinates.

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
