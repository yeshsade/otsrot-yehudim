---
name: lefi-yamim-6-days
description: "Build only the לפי ימים half of lefi-yamim-6, including page-2 הריני מקשר, weekday pages, rebuilt navigation, a filled לפי ימים badge, and persistent thin-border V checkboxes with strict navigation-preservation checks."
---

# Days edition engine

1. Analyze fresh interior and cover PDFs.
2. Confirm the exact booklet identity. Keep the booklet number in Arabic digits.
3. Ask and record every required manual answer one question per message, in this order: booklet identity; each unassigned source page; each still-unanswered previous-week topic; previous-week Gemara; one unusual Daf Yomi day at a time.
4. Treat every user-confirmed previous-week assignment as authoritative, even outside the supplements range. Recalculate source-page ownership before building. Use `../scripts/apply_manual_answers.py`; do not hand-edit derived ranges or `day_pages`.
5. Build the base booklet with page 1 cover, page 2 `הריני מקשר`, and page 3 weekday TOC.
6. Generate the cover label with `../scripts/generate_edition_label.py --edition days`; require a filled bold `לפי ימים` badge and attach with crop mode `none`.
7. Read `../references/daf-yomi-navigation.md`, then run `scripts/add_day_notes.py`. Change only the text on EXISTING continuation buttons in ALL study topics that have them: name their own topic and destination day or previous week. Topics without buttons remain without buttons. Preserve each button's count, location, and destination. Reject the build unless every supplied previous-week page has one forward link and a reciprocal return link with Sunday, as before. Never add Gemara-to-Mishnah buttons or previous-week buttons on later days.
8. Read `references/v-checkbox-rules.md`, then run `scripts/add_vcheck_boxes.py --strict` on a new output path.
9. Permit 6 or 12 boxes on the final rotated Tehillim half-spread; require 12 on ordinary rotated spreads. Require 6 on straight two-column end pages.
10. Read `vcheck-summary.json`. Reject unless `no_checkbox_overlaps` is true, navigation is identical, all targets are valid, no widget covers a link, and all approved topics succeed.
11. Render the final cover, `הריני מקשר`, TOC, portrait page, rotated page, final Tehillim half-spread, and representative unchecked and checked states.
12. Copy the validated PDF to `D:\מלמדים\הרב גלבך\אוצרות יהודים\לפי ימים` as `<booklet_number>_<parsha>_לפי_ימים.pdf`. Normalize spaces in the parsha to underscores and never include `סופי` in the delivered filename.
13. Upload that exact clean filename to permanent Drive folder ID `1Ov0eLEXjT4Q6uSaXF9dUKZ_Lwm1bf7B1`, list the folder to verify, and report the observed URL.

Never overwrite inputs or older skills.
