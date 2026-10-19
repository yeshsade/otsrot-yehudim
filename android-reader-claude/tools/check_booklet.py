#!/usr/bin/env python3
# Lean Otzarot reader — booklet checker. License: GPLv3.
"""
בודק חוברת "לפי ימים" לפני שהיא יוצאת: תיבות הסימון והמפה הנסתרת, לפי docs/TOPICS.md.

שימוש:  python3 check_booklet.py <booklet.pdf>
יציאה 0 = הכול תקין, 1 = נמצאו בעיות (הן מודפסות).

נבדק:
  1. יש מפה מצורפת otzarot-progress-map.v1.json, היא JSON תקין, ובפורמט otzarot.progress-map/1.
  2. כל תיבה (vcheck_*) רשומה במפה פעם אחת בדיוק, וכל רשומה במפה מצביעה על תיבה קיימת.
  3. לכל תיבה יש נושא ויום מוכרים, עמוד אמיתי, טור, ושם/כותרת שורה איפה שצריך.
  4. מספר התיבות תואם להחלטות: 1–3 בכל טור בעמוד מסובב, 3 בכל טור בעמוד ישר, 4 בנביאים, 1 במשנה ברורה.
     תהילים לא נבדק בינתיים (נדחה).
  5. לכל עמוד בחוברת יש רשומה ב־pages.
  6. תיבה לא מכסה טקסט, קישור או תיבה אחרת.
דורש PyMuPDF (pip install pymupdf).
"""
import json
import re
import sys
from collections import Counter, defaultdict

import pymupdf as fitz

MAP_NAME = "otzarot-progress-map.v1.json"
SCHEMA = "otzarot.progress-map/1"

# כללי הכמות, לפי ההחלטות (TOPICS.md, ו־SKILL7-AGREED-DECISIONS-2026-10-19 של GPT):
# נושאים בטורים בעמוד מסובב: 1 עד 3 תיבות בכל טור, לפי אורך הלימוד ("כלל השלישים").
PER_COLUMN_ROTATED = {"mishnah", "shulchan_aruch", "zohar", "sifrei_rabeinu", "sefer_hamidot", "shemot_tzadikim", "likutey_tefilot"}
# עמודים ישרים: נשאר כמו קודם, 3 בכל טור.
PER_COLUMN_STRAIGHT = {"likutey_moharan", "likutey_halakhot"}
# תיבות ליום בנושאים שהתיבה בהם היא יחידה שלמה. None = משתנה (נבדק רק שיש לפחות אחת).
PER_DAY = {
    "gemara": None,          # תיבה לכל עמוד גמרא (א׳ וב׳ נפרדים); עמוד שנפרס על שני עמודי חוברת = שדה אחד
    "nach": {4},             # תיבה בכל חצי עמוד, בסוף הפסוקים
    "mishnah_berurah": {1},  # תיבה אחת בפינה הימנית למטה
}
SKIP_COUNT = {"tehillim"}    # נדחה ("בוא נדלג עכשיו על תהילים")
NEEDS_LABEL = {"gemara", "nach"}
NEEDS_GROUP = {"zohar", "sifrei_rabeinu", "likutey_halakhot"}
NO_CHECKS = {"parsha", "tikkun_haklali"}
CHECK_KEYS = ("id", "field", "topic_id", "day_id", "week_offset", "order", "pages", "page_in_day", "column")


def main(path):
    doc = fitz.open(path)
    problems, notes = [], []

    widgets = {}
    for page in doc:
        for w in page.widgets() or []:
            if (w.field_name or "").startswith("vcheck_"):
                widgets.setdefault(w.field_name, []).append(page.number + 1)
    notes.append(f"{doc.page_count} עמודים, {sum(len(v) for v in widgets.values())} תיבות ({len(widgets)} שדות)")

    # 6. overlaps (checked even without a map)
    touching = []
    for page in doc:
        boxes = [w.rect for w in page.widgets() or [] if (w.field_name or "").startswith("vcheck_")]
        if not boxes:
            continue
        words = [fitz.Rect(t[:4]) for t in page.get_text("words")]
        links = [fitz.Rect(l["from"]) for l in page.get_links()]
        for i, r in enumerate(boxes):
            hit = next((t for t in words if r.intersects(t) and (r & t).get_area() > 4), None)
            if hit is not None:
                touching.append((page.number + 1, "טקסט"))
            elif any(r.intersects(o) for o in boxes[i + 1:] + links):
                touching.append((page.number + 1, "קישור או תיבה"))
    if touching:
        pages = sorted({p for p, _ in touching})
        problems.append(f"{len(touching)} תיבות נוגעות בטקסט, בקישור או בתיבה אחרת. עמודים: {', '.join(map(str, pages))}")

    # navigation buttons (not "back to contents") whose label sits on the page's own text: a warning, the owner decides
    targets = Counter(l.get("page") for page in doc for l in page.get_links() if l.get("kind") == fitz.LINK_GOTO)
    toc = targets.most_common(1)[0][0] if targets else None
    covered = []
    for page in doc:
        words = [fitz.Rect(t[:4]) for t in page.get_text("words")]
        for l in page.get_links():
            r = fitz.Rect(l["from"])
            if l.get("kind") != fitz.LINK_GOTO or l.get("page") == toc or (r.width < 40 and r.height < 40):
                continue
            if any(r.intersects(t) and (r & t).get_area() > 4 for t in words):
                covered.append(page.number + 1)
    if covered:
        notes.append(f"אזהרה: {len(covered)} כפתורי מעבר יושבים על טקסט של העמוד. עמודים: {', '.join(map(str, sorted(set(covered))))}")

    # 1. the map
    if MAP_NAME not in doc.embfile_names():
        problems.append(f"אין מפה: הקובץ {MAP_NAME} לא מצורף ל־PDF")
        return report(problems, notes)
    try:
        data = json.loads(doc.embfile_get(MAP_NAME).decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        problems.append(f"המפה אינה JSON תקין: {e}")
        return report(problems, notes)
    if data.get("schema") != SCHEMA:
        problems.append(f"schema הוא {data.get('schema')!r} ולא {SCHEMA!r}")

    booklet = data.get("booklet") or {}
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(booklet.get("week_start", ""))):
        problems.append("booklet.week_start חסר או לא בפורמט YYYY-MM-DD")
    for key in ("parsha", "issue"):
        if not booklet.get(key):
            problems.append(f"booklet.{key} חסר")

    topics = {t.get("id"): t for t in data.get("topics", [])}
    days = {d.get("id"): d for d in data.get("days", [])}
    if len(topics) != len(data.get("topics", [])):
        problems.append("יש מזהה נושא כפול ב־topics")
    if len(days) != len(data.get("days", [])):
        problems.append("יש מזהה יום כפול ב־days")
    if "sefer_hamidot" not in topics or "shemot_tzadikim" not in topics:
        problems.append("ספר המידות ושמות הצדיקים צריכים להיות שני נושאים נפרדים (sefer_hamidot, shemot_tzadikim)")

    # 2–4. the checks
    checks = data.get("checks", [])
    by_field = Counter(c.get("field") for c in checks)
    for field, n in by_field.items():
        if n > 1:
            problems.append(f"התיבה {field} רשומה במפה {n} פעמים")
        if field not in widgets:
            problems.append(f"המפה מצביעה על {field}, שאין בחוברת")
    for field in widgets:
        if field not in by_field:
            problems.append(f"התיבה {field} לא רשומה במפה")
    per_day = defaultdict(int)
    per_column = defaultdict(int)
    for c in checks:
        missing = [k for k in CHECK_KEYS if k not in c]
        if missing:
            problems.append(f"{c.get('id', c.get('field'))}: חסרים {', '.join(missing)}")
            continue
        t, d = c["topic_id"], c["day_id"]
        if t not in topics:
            problems.append(f"{c['id']}: נושא לא מוכר {t!r}")
        if t in NO_CHECKS:
            problems.append(f"{c['id']}: לנושא {t} לא אמורות להיות תיבות")
        if c["week_offset"] == 0 and d not in days:
            problems.append(f"{c['id']}: יום לא מוכר {d!r}")
        if not all(isinstance(p, int) and 1 <= p <= doc.page_count for p in c["pages"]):
            problems.append(f"{c['id']}: pages מחוץ לחוברת")
        elif widgets.get(c["field"]) and sorted(set(widgets[c["field"]])) != sorted(set(c["pages"])):
            problems.append(f"{c['id']}: pages במפה {c['pages']} לא תואם לעמודי התיבה {widgets[c['field']]}")
        if t in NEEDS_LABEL and not c.get("label_he"):
            problems.append(f"{c['id']}: חסר label_he (\"עמוד א׳\" / \"חלק 1\")")
        if t in NEEDS_GROUP and not c.get("group_he"):
            problems.append(f"{c['id']}: חסר group_he (\"עמוד 1\" / \"עמוד א׳\")")
        if c["week_offset"] == 0:
            per_day[(t, d)] += 1
            per_column[(t, d, c["page_in_day"], c["column"])] += 1
    for (t, d), n in sorted(per_day.items()):
        allowed = PER_DAY.get(t, "skip")
        if allowed == "skip" or t in SKIP_COUNT:
            continue
        if allowed is None and n < 1:
            problems.append(f"{t} ב־{d}: אין תיבות")
        elif allowed is not None and n not in allowed:
            problems.append(f"{t} ב־{d}: {n} תיבות, ולפי ההחלטות צריך {' או '.join(map(str, sorted(allowed)))}")
    for (t, d, page_in_day, column), n in sorted(per_column.items()):
        if t in PER_COLUMN_ROTATED and not 1 <= n <= 3:
            problems.append(f"{t} ב־{d}, עמוד {page_in_day} טור {column}: {n} תיבות, וצריך 1 עד 3")
        if t in PER_COLUMN_STRAIGHT and n != 3:
            problems.append(f"{t} ב־{d}, עמוד {page_in_day} טור {column}: {n} תיבות, ובעמוד ישר צריך 3")
    seen_topics = {t for t, _ in per_day}
    for t in ("gemara", "nach", "mishnah_berurah"):
        if t not in seen_topics:
            problems.append(f"אין תיבות לנושא {t} (נושא חדש לפי ההחלטות)")
    counts = Counter(t for t, _ in per_day)
    notes.append("תיבות לפי נושא: " + ", ".join(f"{t} {sum(n for (tt, _), n in per_day.items() if tt == t)}" for t in sorted(counts)))

    # 5. pages
    page_rows = {p.get("page") for p in data.get("pages", [])}
    missing_pages = [n for n in range(1, doc.page_count + 1) if n not in page_rows]
    if missing_pages:
        problems.append(f"ב־pages חסרים {len(missing_pages)} עמודים (למשל {missing_pages[:5]})")

    return report(problems, notes)


def report(problems, notes):
    for n in notes:
        print("•", n)
    if not problems:
        print("✓ תקין")
        return 0
    print(f"✗ נמצאו {len(problems)} בעיות:")
    for p in problems[:200]:
        print("  -", p)
    if len(problems) > 200:
        print(f"  … ועוד {len(problems) - 200}")
    return 1


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
