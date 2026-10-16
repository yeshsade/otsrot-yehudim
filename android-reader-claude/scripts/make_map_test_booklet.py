#!/usr/bin/env python3
"""Builds the emulator test booklet with a hidden progress map (stage 2).

Takes the synthetic check-box booklet and attaches otzarot-progress-map.v1.json (schema otzarot.progress-map/1)
listing its six boxes: topic "mishnah", one day per page, plus one previous-week entry that the app must leave out.
Usage: python3 make_map_test_booklet.py <synthetic-checkbox-source.pdf> <out.pdf>   (needs pypdf)
"""
import json
import sys

import pypdf

src, out = sys.argv[1], sys.argv[2]
days = [("sunday", "יום ראשון"), ("monday", "יום שני"), ("tuesday", "יום שלישי")]
checks = []
for page in (1, 2, 3):
    for column in (1, 2):
        checks.append({"id": f"mishnah.{days[page - 1][0]}.{column:03d}", "field": f"vcheck_mishnah_p{page:03d}_c{column}_1",
                       "topic_id": "mishnah", "day_id": days[page - 1][0], "week_offset": 0, "order": column, "final_page": page})
checks.append({"id": "x.prev", "field": "vcheck_prev_week", "topic_id": "mishnah", "day_id": "sunday",
               "week_offset": -1, "order": 9, "final_page": 1})
progress_map = {"schema": "otzarot.progress-map/1", "booklet": {"number": 0, "edition": "days", "parsha": "בדיקה"},
                "topics": [{"id": "mishnah", "label_he": "משנה", "order": 4}],
                "days": [{"id": d, "label_he": label, "order": i + 1} for i, (d, label) in enumerate(days)],
                "checks": checks}
writer = pypdf.PdfWriter(clone_from=pypdf.PdfReader(src))
writer.add_attachment("otzarot-progress-map.v1.json", json.dumps(progress_map, ensure_ascii=False, indent=1).encode("utf-8"))
writer.write(out)
