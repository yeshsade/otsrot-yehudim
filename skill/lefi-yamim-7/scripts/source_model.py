"""Auditable source ownership and measured learning regions for Skill 7.

No booklet-specific page numbers live in this module. Ambiguous semantic
boundaries must be supplied in the source-hash-bound review file.
"""
from collections import Counter, defaultdict
import hashlib
from pathlib import Path
import sys

import fitz

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "days/scripts"))
import build_lefi_yamim as legacy

DAYS = [("sunday", "יום ראשון"), ("monday", "יום שני"),
        ("tuesday", "יום שלישי"), ("wednesday", "יום רביעי"),
        ("thursday", "יום חמישי"), ("friday", "יום שישי"), ("saturday", "שבת קודש")]
NAMES = ["הדף היומי", "פרשת השבוע", "נביאים וכתובים", "משנה", "שולחן ערוך",
         "משנה ברורה", "זוהר", "תהילים", 'ליקוטי מוהר"ן', "ליקוטי הלכות",
         "ספרי רבינו על פי הסדר", "שמות הצדיקים / ספר המידות", "ליקוטי תפילות",
         "תיקון הכללי", "השלמות"]
IDS = ["gemara", "parsha", "nach", "mishnah", "shulchan_aruch", "mishnah_berurah",
       "zohar", "tehillim", "likutey_moharan", "likutey_halakhot", "sifrei_rabeinu",
       "combined_midot_tzadikim", "likutey_tefilot", "tikkun_haklali", "supplements"]
TOPICS = dict(zip(IDS, NAMES))
NAME_IDS = dict(zip(NAMES, IDS))
PREVIOUS = ("mishnah_berurah", "likutey_moharan", "likutey_halakhot", "gemara")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def upright(page, sideways):
    if sideways:
        page.set_rotation(270)
    return page


def spans(page, body=True):
    for block in page.get_text("dict")["blocks"]:
        if block["type"] != 0:
            continue
        for line in block["lines"]:
            for item in line["spans"]:
                r = fitz.Rect(item["bbox"]) * page.rotation_matrix
                if body and (r.y0 < page.rect.height * .08 or r.y1 > page.rect.height * .97):
                    continue
                if body and ("fbrimona" in item["font"].lower() or item["color"] == 0xffffff):
                    continue
                yield {**item, "rect": r}


def weekly_markers(page):
    result = []
    for item in spans(page, body=False):
        if item["color"] != 0xffffff:
            continue
        for slug, name in DAYS:
            if item["text"].strip() == name:
                result.append((slug, item["rect"]))
    return result


def column_of(rect, width, columns=4):
    center = (rect.x0 + rect.x1) / 2
    if columns == 2:
        return 1 if center >= width / 2 else 2
    return 1 if center >= width * .73 else 2 if center >= width / 2 else 3 if center >= width * .27 else 4


def union(rects):
    if not rects:
        raise ValueError("Empty learning region")
    return fitz.Rect(min(r.x0 for r in rects), min(r.y0 for r in rects),
                     max(r.x1 for r in rects), max(r.y1 for r in rects))


def learning_font(pages):
    return learning_font_from_spans(item for page in pages for item in spans(page))


def learning_font_from_spans(items):
    counts, sizes = Counter(), defaultdict(list)
    for item in items:
        counts[item["font"]] += len(item["text"].strip())
        sizes[item["font"]].append(item["size"])
    if not counts:
        raise ValueError("No selectable source text; source review/OCR is required")
    # A few large chapter letters must not displace the actual text font.
    candidates = [font for font, n in counts.items() if n >= max(20, max(counts.values()) * .05)]
    return max(candidates, key=lambda font: sorted(sizes[font])[len(sizes[font]) // 2])


def gray_backgrounds(page):
    result = []
    for drawing in page.get_drawings():
        fill = drawing.get("fill")
        if fill and len(fill) == 3 and .65 < min(fill) < .98 and max(fill) - min(fill) < .04:
            r = drawing["rect"] * page.rotation_matrix
            if r.width > 30 and r.height > 20:
                result.append(r)
    return result


def blank_body(page):
    # Ignore page numbers; image-only study pages must never be called blank.
    pix = page.get_pixmap(matrix=fitz.Matrix(.4, .4),
                         clip=fitz.Rect(0, 45, page.rect.width, page.rect.height - 20),
                         colorspace=fitz.csGRAY, alpha=False)
    return sum(value < 200 for value in pix.samples) < 12


def discover(interior, cover):
    rotated = legacy.detect_rotated(str(interior))
    topics = legacy.detect_topics(str(interior), rotated)
    ordered, ranges, total = legacy.build_topic_ranges(str(interior), topics)
    with fitz.open(interior) as doc:
        blanks = [page.number + 1 for page in doc if blank_body(page)]
    return {"input_pdf": str(Path(interior).resolve()), "cover_pdf": str(Path(cover).resolve()),
            "source_sha256": sha256(interior), "cover_sha256": sha256(cover),
            "rotated_original": rotated, "topics": topics,
            "topic_ranges": {k: list(v) for k, v in ranges.items()},
            "total_original_pages": total, "blank_pages": blanks,
            "day_pages": legacy.assign_days(str(interior), blanks, ranges,
                [(n, start) for n, start in ordered if n not in {"פרשת השבוע", "תיקון הכללי", "השלמות"}])}


def validate_answers(base, answers):
    if answers.get("source_sha256") != base["source_sha256"]:
        raise ValueError("Answers belong to a different source PDF")
    if answers.get("cover_sha256") != base["cover_sha256"]:
        raise ValueError("Answers belong to a different cover PDF")
    previous = answers.get("previous_week", {})
    for topic in PREVIOUS:
        if topic not in previous:
            raise ValueError(f"Ask the owner for the previous-week source page of {TOPICS[topic]}")
        page = previous[topic]
        if page is not None and (isinstance(page, bool) or not isinstance(page, int)
                                 or not 1 <= page <= base["total_original_pages"]):
            raise ValueError(f"Invalid previous-week page for {topic}")
    present = [p for p in previous.values() if p is not None]
    if len(present) != len(set(present)):
        raise ValueError("One source page has conflicting previous-week owners")
    import datetime
    booklet = answers.get("booklet", {})
    week = datetime.date.fromisoformat(booklet["week_start"])
    if week.weekday() != 6 or not str(booklet.get("issue", "")).isdigit() or not booklet.get("parsha"):
        raise ValueError("A numeric issue, parsha and Sunday week_start are required")
    return previous


def tehillim_regions(doc, pages, review):
    """Exact black weekly tabs and source-reviewed exclusions, not half-page guesses."""
    if not review.get("boundaries_reviewed"):
        raise ValueError("Review Tehillim weekly boundaries and helper/prayer exclusions first")
    font = learning_font([doc[p - 1] for p in pages])
    markers = []
    for n in pages:
        page = doc[n - 1]
        for day, r in weekly_markers(page):
            markers.append({"day": day, "page": n, "column": column_of(r, page.rect.width), "y": r.y0})
    markers.sort(key=lambda x: (x["page"], x["column"], x["y"]))
    if [m["day"] for m in markers] != [slug for slug, _ in DAYS]:
        raise ValueError(f"Expected one black weekly tab per day in Tehillim: {markers}")
    excluded = review.get("exclude_after_psalms", [])
    # The verse / post-psalm prayer boundary is semantic. Require reviewed
    # geometry if a prayer page is present; never infer it from PDF page count.
    for n in pages:
        if not weekly_markers(doc[n - 1]) and not any(n == item["page"] for item in excluded):
            sizes = {s["font"] for s in spans(doc[n - 1]) if s["size"] > 8}
            if len(sizes) > 5:
                raise ValueError(f"Review unmarked Tehillim source page {n}, including any post-psalm prayers")
    result = defaultdict(list)
    for n in pages:
        page = doc[n - 1]
        gray = gray_backgrounds(page)
        by_day_col = defaultdict(list)
        for item in spans(page):
            if item["font"] != font:
                continue
            r = item["rect"]
            column = column_of(r, page.rect.width)
            pos = (n, column, r.y0)
            if any(bg.contains((r.tl + r.br) / 2) for bg in gray):
                continue
            if any(n == ex["page"] and (column > ex["column"] or
                   (column == ex["column"] and r.y0 > ex["end_y_pt"])) for ex in excluded):
                continue
            earlier = [m for m in markers if (m["page"], m["column"], m["y"]) <= pos]
            if not earlier:
                continue
            day = earlier[-1]["day"]
            by_day_col[day, column].append(r)
        for (day, column), rects in by_day_col.items():
            r = union(rects)
            full = union([s["rect"] for s in spans(page) if s["font"] == font])
            full.x0, full.x1 = r.x0, r.x1
            result[day].append({"source_page": n, "column": column,
                                "rect": list(r), "full_rect": list(full)})
    if set(result) != {d for d, _ in DAYS}:
        raise ValueError("Missing measured Tehillim day")
    return dict(result)


def model(base, answers, doc):
    previous = validate_answers(base, answers)
    context = {page: topic for topic, page in previous.items() if page is not None}
    for n, page in enumerate(doc, 1):
        upright(page, n in base["rotated_original"])
    owners = {}
    for name, (start, end) in base["topic_ranges"].items():
        for n in range(start, end + 1):
            if n not in base["blank_pages"]:
                owners[n] = NAME_IDS[name]
    owners.update(context)
    # Raster introduction before the first study topic is preserved as its own
    # source owner. Days layout follows the historical study-first ordering.
    for n in range(1, min(base["topics"].values())):
        if n not in base["blank_pages"]:
            owners[n] = "introduction"
    current = {d: [] for d, _ in DAYS}
    heb_days = dict((name, slug) for slug, name in DAYS)
    for name, numbers in base["day_pages"].items():
        current[heb_days[name]] = [n for n in numbers if n not in context
                and n not in base["blank_pages"] and owners.get(n) not in {"tehillim", "parsha", "tikkun_haklali", "supplements"}]
    teh_pages = sorted(n for n, topic in owners.items() if topic == "tehillim" and n not in context)
    teh = tehillim_regions(doc, teh_pages, answers.get("tehillim", {}))
    for day in current:
        current[day] += sorted({r["source_page"] for r in teh[day]})
        current[day].sort(key=lambda n: (IDS.index(owners[n]), n))
    unassigned = [n for n, topic in owners.items() if topic == "supplements" and n not in context]
    if unassigned:
        raise ValueError(f"Ask the owner to assign these supplement source pages: {unassigned}")
    # Every ordinary study page must have an owner/day; previous-week pages
    # must appear only in their separate supplement block.
    assigned = {n for pages in current.values() for n in pages}
    expected = {n for n, topic in owners.items() if topic not in
                {"parsha", "tikkun_haklali", "supplements", "introduction"} and n not in context}
    if assigned != expected:
        raise ValueError(f"Missing or unexpected current-week source ownership: {sorted(expected ^ assigned)}")
    return {"owners": owners, "contexts": context, "days": current, "tehillim": teh,
            "blank_pages": base["blank_pages"], "booklet": answers["booklet"]}
