# V Checkbox Rules

These are the approved rules for `lefi-yamim-vcheck-test`.

## Main Principle

The digital checkbox is like a learner marking V with a pen in a physical booklet.
It must mark progress without damaging the text.

Use real PDF form checkboxes. Do not use drawn static squares. Do not flatten the fields.

## Script

Use:

```bash
python scripts/add_vcheck_boxes.py --pdf "<final-after-day-notes.pdf>" --analysis "<analysis.json>" --output "<name>_vcheck_test.pdf" --preview-dir "<vcheck-qa>" --gap-pt 14
```

Run it only after `scripts/add_day_notes.py`.

## Global Rules

1. Never cover text.
2. Never cover headings, side titles, return buttons, previous/next topic buttons, or existing links.
3. If the rule cannot work consistently for a whole topic, skip the entire topic or ask the user.
4. No page-by-page improvisation inside a topic.
5. Never allow checkbox rectangles to intersect or crowd each other. Search for a nearby safe position consistently; if none exists, fail under `--strict`.
6. Use three checkboxes per learned column.
7. Do not put the first checkbox at the top of the column.
8. Put the last checkbox at the end of the learned text.
9. Keep a safety gap from text. Default: `--gap-pt 14`.
10. Ignore the black weekday square when calculating text boundaries.
11. Preview diagnostic lines are not allowed in final output.

## Layouts

### Two-column layout

`box | column | column | box`

Use for:

- ליקוטי מוהר"ן
- ליקוטי הלכות

Rules:

- Three boxes per column.
- Use outside available lanes.
- In ליקוטי הלכות, outside margin text is a title. Avoid it and do not treat it as learned text.
- Last box follows actual learned text end.

### Four-column layout

`box | column | column | box | box | column | column | box`

Use for rotated / joined A5 pages.

Rules:

- Split the page into two A5 halves.
- Each half has two text columns.
- Use both outer and inner lanes.
- Detect text and lanes per A5 half, not across the whole A4 spread.
- Ignore the black weekday square.
- Keep a safety gap from letters.

## Four-column Full Learned Text

All text is learned. Use full learned text height.

Topics:

- תהילים
- ספרי רבינו
- שמות הצדיקים / ספר המידות
- ליקוטי תפילות

These topics do not need translation/commentary cutoff detection.

## זוהר

Only the text above `תרגום לשון הזוהר` is learned.

Rules:

- Split into two A5 halves.
- Find `תרגום לשון הזוהר` separately in each half.
- Use the approved templates in `assets/vcheck-templates`.
- Put checkboxes only above the title.
- Do not mark the translation.
- If the match is weak or ambiguous, skip the whole topic or fail with `--strict`.

Important:

- Do not search one full-width A4 gap.
- Right and left halves can have different heights.
- Never output the pink diagnostic line used during testing.

## משנה

Only the text above `רבינו עובדיה מברטנורה` is learned.

Rules:

- Split into two A5 halves.
- Find `רבינו עובדיה מברטנורה` separately in each half.
- Use the approved templates in `assets/vcheck-templates`.
- Put checkboxes only above it.
- Do not mark Bartenura commentary.
- If the match is weak or ambiguous, skip the whole topic or fail with `--strict`.

## שולחן ערוך

The learned text is above the approved boundary/title area.

Rules:

- Split into two A5 halves.
- Detect the approved title/boundary area per half.
- Use the approved templates in `assets/vcheck-templates`.
- Put checkboxes only above that boundary.
- At the end of a topic, grey/future-week text is not learned.
- The last checkbox follows the end of black learned text, not grey text.
- If the boundary or learned-text end is ambiguous, skip the whole topic or fail with `--strict`.

## Topics Without Checkboxes

Do not add checkboxes to:

- דף היומי / גמרא
- חומש / פרשת השבוע
- נביאים וכתובים
- משנה ברורה
- תיקון הכללי
- השלמות

## Field Names

Use unique stable field names:

`vcheck_<topic_slug>_p<page>_c<column>_<index>`

Examples:

- `vcheck_zohar_p056_c2_3`
- `vcheck_likutey_halakhot_p132_c1_2`

## Validation

Before accepting a test PDF:

1. Check no widget rectangle intersects text.
2. Check no widget rectangle intersects another widget rectangle.
3. Check no widget rectangle intersects an existing link rectangle.
4. Check link count before and after.
5. Check widget count.
6. Require `no_checkbox_overlaps: true` in `vcheck-summary.json`.
7. Render and inspect every preview generated for boundary topics, including the first משנה page.
8. Ask user approval.


## Approved widget appearance and coordinate rules

- Convert rendered detection pixels back to PDF points by dividing every rectangle coordinate by the render zoom.
- Apply the page derotation matrix for pages rotated 90 or 270 degrees.
- Draw the unchecked appearance as a white square with a thin 0.75-unit black border.
- Draw the checked state with the approved custom V, not the viewer default glyph.
- Use a pre-rotated V appearance on rotated pages so the visible V remains upright.
- Set the widget border width to zero; the custom appearance owns the visible border.
- Call `widget.update()` immediately after adding the widget.
- Permit at most 2% dark antialias overlap inside a candidate rectangle; never permit a link intersection.
- For straight end pages of תהילים and שולחן ערוך, use the two-column layout even though the rest of the topic uses four columns.

## Mandatory checkbox collision audit

For every output:

1. Reserve each accepted checkbox rectangle while planning later candidates on the same page.
2. Require a visible clearance between neighboring widgets; default minimum is 0.75 PDF points.
3. Check every final widget pair on every page after saving.
4. Fail the run on any positive-area widget/widget intersection.
5. Write `no_checkbox_overlaps` to `vcheck-summary.json` and require it to be true.

## Mandatory navigation audit

For every output:

1. Compare every link on every page before and after checkbox insertion, excluding only PDF xref identifiers.
2. Compare link kind, clickable rectangle, destination page, destination point, URI, file, and named destination.
3. Require every internal target to be within the PDF page range.
4. Require zero widget/link rectangle intersections.
5. Fail the run, rather than warn, when any navigation check differs.

