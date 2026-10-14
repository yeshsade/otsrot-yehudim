---
name: lefi-yamim-6-days
description: "Build only the לפי ימים half of lefi-yamim-6, including page-2 הריני מקשר, weekday pages, rebuilt navigation, a filled לפי ימים badge, and persistent thin-border V checkboxes with strict navigation-preservation checks."
---

# Days edition engine

1. Analyze fresh interior and cover PDFs.
2. Produce a source-map report before any build. It must map each source page to one topic and, for daily material, one weekday. It must have no reversed or overlapping ranges, no missing/duplicate daily pages, and no weekday marker contradicting its assignment.
3. If the source map is ambiguous (including a physical spread containing two weekday markers), stop. Ask one exact question at a time, record the safe page/split decision, show the corrected map, and obtain user confirmation. Never resolve it by duplicating a full page.
4. Confirm the exact booklet identity. Keep the booklet number in Arabic digits.
5. Ask and record every remaining required manual answer one question per message: each unassigned source page; each still-unanswered previous-week topic; previous-week Gemara; one unusual Daf Yomi day at a time.
6. Treat every user-confirmed previous-week assignment as authoritative, even outside the supplements range. Recalculate and revalidate source-page ownership before building.
7. Build the base booklet with page 1 cover, page 2 `הריני מקשר`, and page 3 weekday TOC.
8. Generate the cover label with `../scripts/generate_edition_label.py --edition days`; require a filled bold `לפי ימים` badge and attach with crop mode `none`.
9. Run `scripts/add_day_notes.py`.
10. Read `references/v-checkbox-rules.md`, then run `scripts/add_vcheck_boxes.py --strict` on a new output path.
11. Permit 6 or 12 boxes on the final rotated Tehillim half-spread; require 12 on ordinary rotated spreads. Require 6 on straight two-column end pages.
12. Read `vcheck-summary.json`. Reject unless `no_checkbox_overlaps` is true, navigation is identical, all targets are valid, no widget covers a link, and all approved topics succeed.
13. Render the final cover, `הריני מקשר`, TOC, portrait page, rotated page, every weekday boundary, final Tehillim half-spread, and representative unchecked and checked states.
14. Copy the validated PDF to `D:\מלמדים\הרב גלבך\אוצרות יהודים\לפי ימים` as `<booklet_number>_<parsha>_לפי_ימים.pdf`. Normalize spaces in the parsha to underscores and never include `סופי` in the delivered filename.
15. Upload that exact clean filename to permanent Drive folder ID `1h0_hB7wcymjRDAvmFHJcktbKBqgN6_-y`, list the folder to verify, and report the observed URL.

Never overwrite inputs or older skills.
