---
name: lefi-yamim-6-topics
description: "Build only the לפי נושאים edition from original weekly PDFs with original page order, a cover-color-matched page-2 TOC, 15 topic links, WhatsApp/email/archive links, return buttons, a visible edition label, and approved persistent V checkboxes."
---

# Topics edition engine

Use the original interior and cover PDFs, never the days PDF. Keep the booklet number in Arabic digits. Page 2 is the topics TOC; do not insert `הריני מקשר` unless the user explicitly requests it.

1. Derive and visually verify parsha, numeric booklet number, date range, and cover circle color.
2. Generate the TOC from `assets/topics-toc-template.png` with `scripts/generate_topics_toc.py`.
3. Render the TOC; require zero blue-theme pixels outside the preserved WhatsApp/Gmail icon area; reject stale parsha/date, missing `לפי נושאים`, or a color mismatch.
4. Run `scripts/build_topics_booklet.py analyze`; verify exactly 15 topic starts, blank pages, and rotated pages. Apply confirmed starts with `../scripts/apply_manual_answers.py`; never hand-edit `topic_starts_original`. A previous-week page is the start when it is the first page readers must see.
5. Build once from pristine sources with the generated TOC and approved WhatsApp, email, and archive URLs. Preserve original nonblank page order; never stack corrective TOC or cover overlays on an existing output.
6. Require 15 internal TOC links with targets exactly matching all 15 confirmed source starts, 3 external TOC links, and a return-to-page-2 link on every page from page 3 onward.
7. Read `../days/references/v-checkbox-rules.md`, then run `scripts/add_topic_vcheck_boxes.py` using the original interior PDF and analysis JSON.
8. A final rotated half-spread in Tehillim may contain 6 or 12 boxes; ordinary rotated spreads require 12. A straight two-column end page requires 6.
9. Reject unless navigation is identical, all targets are valid, and no checkbox covers any link.
10. Generate the cover label with `../scripts/generate_edition_label.py --edition topics`; attach with crop mode `none`.
11. Render the final cover and the generated label preview; both must show visible bold `לפי נושאים` text and matching color.
12. Render the final TOC, portrait page, rotated page, and any half-spread end page. Compare final size with the combined source size and reject unexplained inflation above 1.35x.
13. Copy the validated PDF to `D:\מלמדים\הרב גלבך\אוצרות יהודים\מוכן` as `<booklet_number>_<parsha>_לפי_נושאים.pdf`. Normalize spaces in the parsha to underscores and never include `סופי` in the delivered filename.
14. Upload that exact clean filename to permanent Drive folder ID `1OzLVd0UwqpkST2td_SGMh-6ghhgFh5a1`, list the folder to verify, and report the observed URL.

Never use Canva and never move source content.
