# Source review

`inspect_source.py` writes discovery plus an unanswered JSON template. Bind review to both source and cover SHA-256. Read booklet metadata from the actual cover. Confirm four previous-week subjects with the owner, referring to one-based SOURCE page indices. Never copy an old issue's answers into a new issue.

For Toldot 845 only, the owner explicitly confirmed source pages 67 (Mishnah Berurah), 99 (Likutey Moharan), 152 (Likutey Halakhot), and 151 (Gemara). The Hebrew cover dates are כ״ח חשון – ד׳ כסלו תשפ״ז; Sunday is 2026-11-08. These are validation-case answers, not defaults for future booklets.

## Tehillim

Detect the seven black weekly day tabs, in source reading order and right-to-left column order. Small monthly “ליום…” references are not weekly boundaries. A boundary can occur inside a column and the last Psalm can end at the top of a new page. Inspect actual chapters and the final verse of Psalm 150; its font alone cannot distinguish subsequent verses/prayers. Do not stop at an earlier page just because almost all of the day fits there.

`tehillim.exclude_after_psalms` contains reviewed end cutoffs in upright normalized page coordinates. For the supplied 845 source, the approved measurement is `{"page":92,"column":1,"end_y_pt":57.61}`. It preserves the final line of Psalm 150 and excludes later prayers. Do not reuse this number for another source. Record inspection in `review_provenance` and set `boundaries_reviewed: true` only after the semantic boundary review.

Daily 845 source spans: Sunday 89–90, Monday 90, Tuesday 91–92, Wednesday 93–94, Thursday 94–95, Friday 95–97, Saturday 97–98. Shared source pages appear in two days when needed; boxes only mark each day's portion. Source 99 belongs to previous-week Likutey Moharan.

Raster pages are not blank because text extraction returns little text. Preserve the introduction in the topic edition; the historical day edition omits it. Keep all actual source text on included pages. Gray future text can remain visible but must not become current study progress.

A newly structured or unreadable source requires review/OCR and a tested recognition change. Do not assert universal recognition based on a single validated booklet.

## Gemara duplicates and day ownership

`discovery.json` includes `gemara_review_required`: actual repeated amud identities, unusual daily page counts and the provisional daily runs. This restores the manual-confirmation gate from Skill 6 and also catches repeated amudim split across normal-size runs. One amud printed with two commentaries is retained in full; it is not automatically reference-only material.

When review is required, ask which amud (A, B or both) is duplicated and confirm the affected day's original source run. The answers file uses `gemara_review: {"confirmed_by_owner": true, "day_runs": [{"day_id": "sunday", "source_pages": [2,3,4,5], "duplicate_amud": null}, ...]}`. Include all seven runs in chronological order, with `duplicate_amud` equal to null, `a`, `b` or `both` as verified against the source. Record how the owner's answer resolved the boundary. This is review input; the source model recomputes ownership from it. Every Gemara/commentary source page must remain exactly once in the daily partition. All copies of a duplicated amud must belong to its confirmed day; one shared PDF field covers all physical copies. Never invent confirmation or reuse a different source hash's review.

For Vayetze 846, the owner confirmed Thursday's doubled A (Arakhin2a, sources17/19), with all sources16–21 on Thursday. The preceding Wednesday therefore ends at source15. These are an exact-input regression case, not defaults for another issue.
