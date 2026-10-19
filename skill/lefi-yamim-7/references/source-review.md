# Source review

`inspect_source.py` writes discovery plus an unanswered JSON template. Bind review to both source and cover SHA-256. Read booklet metadata from the actual cover. Confirm four previous-week subjects with the owner, referring to one-based SOURCE page indices. Never copy an old issue's answers into a new issue.

For Toldot 845 only, the owner explicitly confirmed source pages 67 (Mishnah Berurah), 99 (Likutey Moharan), 152 (Likutey Halakhot), and 151 (Gemara). The Hebrew cover dates are כ״ח חשון – ד׳ כסלו תשפ״ז; Sunday is 2026-11-08. These are validation-case answers, not defaults for future booklets.

## Tehillim

Detect the seven black weekly day tabs, in source reading order and right-to-left column order. Small monthly “ליום…” references are not weekly boundaries. A boundary can occur inside a column and the last Psalm can end at the top of a new page. Inspect actual chapters and the final verse of Psalm 150; its font alone cannot distinguish subsequent verses/prayers. Do not stop at an earlier page just because almost all of the day fits there.

`tehillim.exclude_after_psalms` contains reviewed end cutoffs in upright normalized page coordinates. For the supplied 845 source, the approved measurement is `{"page":92,"column":1,"end_y_pt":57.61}`. It preserves the final line of Psalm 150 and excludes later prayers. Do not reuse this number for another source. Record inspection in `review_provenance` and set `boundaries_reviewed: true` only after the semantic boundary review.

Daily 845 source spans: Sunday 89–90, Monday 90, Tuesday 91–92, Wednesday 93–94, Thursday 94–95, Friday 95–97, Saturday 97–98. Shared source pages appear in two days when needed; boxes only mark each day's portion. Source 99 belongs to previous-week Likutey Moharan.

Raster pages are not blank because text extraction returns little text. Preserve the introduction in the topic edition; the historical day edition omits it. Keep all actual source text on included pages. Gray future text can remain visible but must not become current study progress.

A newly structured or unreadable source requires review/OCR and a tested recognition change. Do not assert universal recognition based on a single validated booklet.
