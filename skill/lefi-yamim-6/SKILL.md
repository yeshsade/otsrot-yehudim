---
name: lefi-yamim-6
description: "Build one or both weekly Otzarot Yehudim editions: days edition with Hareni Mekasher, weekday navigation and persistent V checkboxes, and topics edition with original order, a cover-color-matched TOC, return buttons, edition label, and approved checkboxes. Use for only לפי ימים, only לפי נושאים, or both; treat הכל or בשלמותו as both."
---

# Lefi Yamim 6

## Select the requested mode

Honor the request exactly:

- `days`: build only `לפי ימים`.
- `topics`: build only `לפי נושאים`.
- `both`: build `לפי ימים` first, then `לפי נושאים`.

Treat `הכל`, `בשלמותו`, `הסקיל המלא`, or an equivalent request as `both`. Never build an unrequested edition. Build topics from the original interior and cover PDFs, never from the days output.

## User dialogue and manual answers

- Ask exactly one required manual question per message. Never batch questions.
- If the user asks for clarification, answer it and then repeat only the current unanswered question.
- Preserve the booklet number as Arabic digits, for example `835`; never convert it to Hebrew letters or gematria.
- Use the exact labels `גמרא — שבוע קודם`, `משנה ברורה — שבוע קודם`, `ליקוטי מוהר״ן — שבוע קודם`, and `ליקוטי הלכות — שבוע קודם`.
- A user-confirmed previous-week page overrides automatic topic detection and may be anywhere in the source PDF, not only in the supplements range.
- Never describe a previous-week page merely as an "עמוד המשך".
- Read `references/manual-answers.md`. Store answers separately and run
  `scripts/apply_manual_answers.py`; never hand-edit derived `topic_ranges`,
  `day_pages`, Daf Yomi runs, or `topic_starts_original`.
- Previous-week pages are separate from current-week topic ranges. They never
  shift the next current-week topic. In the topics edition, a previous-week
  page is the TOC target when it is the first page readers must see.

## Fixed page identity

- Days: page 1 cover, page 2 fixed `הריני מקשר`, page 3 weekday TOC.
- Topics: page 1 cover, page 2 topics TOC; do not insert `הריני מקשר` unless explicitly requested.

## Sources and safety

- Read fresh source PDFs from `D:\מלמדים\הרב גלבך\אוצרות יהודים\בעבודה`.
- Never overwrite source PDFs.
- Rebuild each edition from pristine sources into a fresh output. Never repair a delivered PDF by stacking full-page overlays.
- Compare final size with the source PDFs and reject unexplained inflation above 1.35 times the combined interior-and-cover size.
- Never use Canva.
- Preserve every navigation link through checkbox and label stages.
- Reject invalid targets, every checkbox/link intersection, and every checkbox/checkbox intersection.

## Cover identity

Derive and visually verify the parsha, numeric booklet number, date range, and weekly color from the cover. Ask the user to confirm the exact identity before final building. Use the same color for the edition label and topics TOC.

The edition marker is mandatory:

- days: `לפי ימים` in the approved filled bold badge;
- topics: `לפי נושאים` in bold text with no badge.

Render the final cover and reject missing, clipped, or stale-colored edition text.

## Mode engines

- Days: read `days/SKILL.md`; deliver locally to `D:\מלמדים\הרב גלבך\אוצרות יהודים\לפי ימים`.
- Topics: read `topics/SKILL.md`; deliver locally to `D:\מלמדים\הרב גלבך\אוצרות יהודים\מוכן`.

## Final filenames

- Never put `סופי` in a delivered PDF filename.
- Days: `<booklet_number>_<parsha>_לפי_ימים.pdf`.
- Topics: `<booklet_number>_<parsha>_לפי_נושאים.pdf`.
- Keep the booklet number as Arabic digits and normalize spaces in the parsha to underscores.
- Suffixes such as `_base`, `_notes`, `_vcheck`, and `_qa` are permitted only for temporary or QA files. Never upload or deliver them.
- If the clean destination filename already exists, do not invent a `סופי` variant. Keep work on a temporary path and ask one question before replacing the delivered file.

## Permanent Drive delivery for 5787

- Archive root: `https://drive.google.com/drive/folders/1SkAKg5tYbnOnoWoaFMQ692_4ojd8GyGB?usp=sharing`
- Year `תשפ"ז`: `https://drive.google.com/drive/folders/1r-08C5sVzq-FJy3qIG00QQ0GNHbji_9U`
- Topics folder ID: `1OzLVd0UwqpkST2td_SGMh-6ghhgFh5a1`
- Days folder ID: `1Ov0eLEXjT4Q6uSaXF9dUKZ_Lwm1bf7B1`

After local validation, upload automatically to the matching folder. In `both` mode upload each edition to its own folder. Upload only final PDFs. Verify by listing the destination folder and report the observed Drive URL.

The user granted standing permission to upload final PDFs to these exact two folder IDs. If an external connector still requires a fresh confirmation, ask one confirmation question only.

## Final validation

Report for every requested edition: clean final filename, local path, page count, checkbox count, zero checkbox overlaps, link count, navigation equality, visible label style, local delivery, and Drive URL. Reject any delivered filename containing `סופי`.

Keep QA files until user acceptance, then remove only temporary artifacts.

Before accepting workflow changes, run the Sukkot and Bereshit cases in
`references/regression-cases.md`. Do not change Tehillim repetition, Zohar, or
V-checkbox behavior unless a specific failing case proves that change is needed.
