#!/usr/bin/env python3
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

from toc_template import (
    analysis_toc_config,
    external_links_from_config,
    insert_toc_links,
    make_return_button_image,
    make_toc_pdf,
)
from generate_local_label import DEFAULT_BRAND

PARENT_SCRIPTS_DIR = Path(__file__).resolve().parents[2] / 'scripts'
if str(PARENT_SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(PARENT_SCRIPTS_DIR))
from generate_edition_label import make_label as make_edition_label
import importlib
source_contract = importlib.import_module('16060510_source_contract')


DAYS_ORDER = ["יום ראשון", "יום שני", "יום שלישי", "יום רביעי", "יום חמישי", "יום שישי", "שבת קודש"]
TOPIC_ORDER = [
    "נביאים וכתובים",
    "משנה",
    "שולחן ערוך",
    "משנה ברורה",
    "זוהר",
    "תהילים",
    'ליקוטי מוהר"ן',
    "ליקוטי הלכות",
    "ספרי רבינו על פי הסדר",
    "שמות הצדיקים / ספר המידות",
    "ליקוטי תפילות",
]
KADMON_MARKERS = {
    'ליקוטי מוהר"ן': 'עמוד ליקוטי מוהר"ן הקודם',
    "ליקוטי הלכות": "עמוד ליקוטי הלכות הקודם",
}
CONTEXT_TOPIC_PATTERNS = [
    ("משנה ברורה", ["משנה ברורה"]),
    ('ליקוטי מוהר"ן', ['ליקוטי מוהר"ן', "ליקוטי מוהרן", "מוהר\"ן", "מוהרן"]),
    ("ליקוטי הלכות", ["ליקוטי הלכות"]),
]
CONTEXT_ALLOWED_TOPICS = {topic for topic, _patterns in CONTEXT_TOPIC_PATTERNS}
PREVIOUS_WEEK_TOPICS = [
    "משנה ברורה",
    'ליקוטי מוהר"ן',
    "ליקוטי הלכות",
]
DAF_YOMI_TOPIC = "\u05d4\u05d3\u05e3 \u05d4\u05d9\u05d5\u05de\u05d9"
EXPECTED_DAF_YOMI_PAGES_PER_DAY = 4
EXPECTED_DAF_YOMI_TOTAL_PAGES = 28
MANUAL_ANSWERS_KEY = "manual_user_answers"
BOOKLET_METADATA_CONFIRMED_KEY = "booklet_metadata_confirmed_by_user"
PREVIOUS_WEEK_CONFIRMED_KEY = "previous_week_pages_confirmed_by_user"
DAF_YOMI_PREVIOUS_WEEK_CONFIRMED_KEY = "daf_yomi_previous_week_confirmed_by_user"
DAF_YOMI_DUPLICATE_AMUD_CONFIRMED_KEY = "daf_yomi_duplicate_amud_confirmed_by_user"
DEFAULT_PRE_TOC_PAGE = Path(__file__).resolve().parents[1] / "assets" / "pre-toc-page.pdf"


def manual_user_answers(analysis):
    answers = analysis.get(MANUAL_ANSWERS_KEY, {})
    return answers if isinstance(answers, dict) else {}


def is_user_confirmed(analysis, key):
    return manual_user_answers(analysis).get(key) is True


def require_user_confirmation(analysis, key, message):
    if not is_user_confirmed(analysis, key):
        raise RuntimeError(message)


def require_booklet_metadata_confirmation(analysis):
    cfg = analysis_toc_config(analysis)
    missing = [key for key in ("booklet_number", "parsha_name", "date_label") if not cfg.get(key)]
    if missing:
        raise RuntimeError(
            "Before building, ask the user for the exact booklet metadata and store "
            + ", ".join(missing)
            + " in analysis.lefi_yamim_6. Keep booklet_number in Arabic digits and require user confirmation."
        )
    require_user_confirmation(
        analysis,
        BOOKLET_METADATA_CONFIRMED_KEY,
        "Before building, ask the user to confirm the exact booklet name/date label. "
        f"Set analysis.{MANUAL_ANSWERS_KEY}.{BOOKLET_METADATA_CONFIRMED_KEY}=true only after the user answers.",
    )


def daf_yomi_previous_week_answered(analysis):
    daf_yomi = analysis.get("daf_yomi", {})
    for key in ("previous_week_gemara_page", "previous_week_gemara_source_page", "previous_week_page"):
        if key in daf_yomi:
            return True
    for key in ("daf_yomi_previous_week_gemara_page", "daf_yomi_previous_week_page"):
        if key in analysis:
            return True
    return False


def normalized_duplicate_amud(value):
    if value is None:
        return None
    text = str(value).strip().lower()
    if text in {"a", "א", "עמוד א", "עמוד א'", "amud a", "page a"}:
        return "a"
    if text in {"b", "ב", "עמוד ב", "עמוד ב'", "amud b", "page b"}:
        return "b"
    if text in {"both", "a+b", "ab", "a,b", "א+ב", "א,ב", "גם א וגם ב", "עמוד א ועמוד ב"}:
        return "both"
    return None


def daf_yomi_duplicate_amud_required(analysis):
    for run in analysis.get("daf_yomi", {}).get("day_runs", []):
        page_count = run.get("page_count")
        expected = run.get("expected_page_count", EXPECTED_DAF_YOMI_PAGES_PER_DAY)
        if run.get("status") != "ok" or page_count != expected:
            return True
    return bool(analysis.get("daf_yomi_duplicates"))


def daf_yomi_duplicate_amud_answered(analysis):
    if analysis.get("daf_yomi_duplicates"):
        return all(normalized_duplicate_amud(value) in {"a", "b", "both"} for value in analysis.get("daf_yomi_duplicates", {}).values())
    for run in analysis.get("daf_yomi", {}).get("day_runs", []):
        page_count = run.get("page_count")
        expected = run.get("expected_page_count", EXPECTED_DAF_YOMI_PAGES_PER_DAY)
        if run.get("status") != "ok" or page_count != expected:
            if normalized_duplicate_amud(run.get("duplicate_amud")) not in {"a", "b", "both"}:
                return False
    return True


def require_daf_yomi_manual_answers(analysis):
    if not daf_yomi_previous_week_answered(analysis):
        raise RuntimeError(
            "Before building, ask the user whether there is a previous-week Daf Yomi Gemara source page. "
            "Record the answer in analysis.daf_yomi.previous_week_gemara_page as a source page number or null."
        )
    require_user_confirmation(
        analysis,
        DAF_YOMI_PREVIOUS_WEEK_CONFIRMED_KEY,
        "Before building, the previous-week Daf Yomi Gemara answer must be confirmed by the user. "
        f"Set analysis.{MANUAL_ANSWERS_KEY}.{DAF_YOMI_PREVIOUS_WEEK_CONFIRMED_KEY}=true only after the user answers.",
    )
    if daf_yomi_duplicate_amud_required(analysis):
        if not daf_yomi_duplicate_amud_answered(analysis):
            for run in analysis.get("daf_yomi", {}).get("day_runs", []):
                page_count = run.get("page_count")
                expected = run.get("expected_page_count", EXPECTED_DAF_YOMI_PAGES_PER_DAY)
                unusual = run.get("status") != "ok" or page_count != expected
                if unusual and normalized_duplicate_amud(run.get("duplicate_amud")) not in {"a", "b", "both"}:
                    raise RuntimeError(
                        "Ask exactly one question now: for "
                        f"{run.get('day')} source pages {run.get('source_start')}-{run.get('source_end')}, "
                        "is the duplicate amud a, amud b, or both?"
                    )
            raise RuntimeError("Ask exactly one unanswered Daf Yomi duplicate-amud question now.")
        require_user_confirmation(
            analysis,
            DAF_YOMI_DUPLICATE_AMUD_CONFIRMED_KEY,
            "Before building, the unusual Daf Yomi duplicate-amud answer must be confirmed by the user. "
            f"Set analysis.{MANUAL_ANSWERS_KEY}.{DAF_YOMI_DUPLICATE_AMUD_CONFIRMED_KEY}=true only after the user answers.",
        )


def require_deps(*names):
    missing = []
    modules = {}
    for name in names:
        try:
            modules[name] = __import__(name)
        except ImportError:
            missing.append(name)
    if missing:
        packages = {"fitz": "PyMuPDF", "PIL": "Pillow", "numpy": "numpy"}
        printable = ", ".join(packages.get(m, m) for m in missing)
        raise SystemExit(f"Missing required Python package(s): {printable}")
    return modules


def resolve_path(value, workspace=None):
    path = Path(value)
    if path.is_absolute() and path.exists():
        return path
    candidates = []
    roots = [Path.cwd()]
    if workspace:
        roots.insert(0, Path(workspace))
    for root in roots:
        direct = root / value
        if direct.exists():
            candidates.append(direct)
        for hit in root.rglob(Path(value).name):
            if hit.exists():
                candidates.append(hit)
    unique = sorted({p.resolve() for p in candidates})
    if not unique:
        raise FileNotFoundError(f"Could not find file: {value}")
    if len(unique) > 1:
        lines = "\n".join(str(p) for p in unique)
        raise RuntimeError(f"Multiple files match {value}; pass an explicit path:\n{lines}")
    return unique[0]


def default_asset(workspace, relative):
    return Path(workspace) / relative


def resolve_pre_toc_page(args, analysis, workspace):
    if getattr(args, "no_pre_toc_page", False):
        return None
    cfg = analysis.get("lefi_yamim_6") or analysis.get("lefi_yamim_5") or analysis.get("lefi_yamim_4") or {}
    value = (
        getattr(args, "pre_toc_page", None)
        or cfg.get("pre_toc_page")
        or cfg.get("pre_toc_page_pdf")
        or analysis.get("pre_toc_page")
        or analysis.get("pre_toc_page_pdf")
    )
    if value:
        return resolve_path(value, workspace)
    if DEFAULT_PRE_TOC_PAGE.exists():
        return DEFAULT_PRE_TOC_PAGE
    raise RuntimeError(
        "Missing pre-TOC page. Pass --pre-toc-page, add assets/pre-toc-page.pdf, "
        "or use --no-pre-toc-page only for internal QA builds."
    )


def insert_fitted_pdf_page(fitz, writer, source_path, target_rect):
    source = fitz.open(str(source_path))
    try:
        if len(source) < 1:
            raise RuntimeError(f"Pre-TOC PDF has no pages: {source_path}")
        source_rect = source[0].rect
        target_w = target_rect.width
        target_h = target_rect.height
        scale = min(target_w / source_rect.width, target_h / source_rect.height)
        placed_w = source_rect.width * scale
        placed_h = source_rect.height * scale
        x0 = (target_w - placed_w) / 2
        y0 = (target_h - placed_h) / 2
        page = writer.new_page(width=target_w, height=target_h)
        page.show_pdf_page(
            fitz.Rect(x0, y0, x0 + placed_w, y0 + placed_h),
            source,
            0,
            keep_proportion=True,
        )
    finally:
        source.close()


def derive_parsha_name(input_pdf, analysis):
    cfg = analysis_toc_config(analysis)
    if cfg.get("parsha_name"):
        return cfg["parsha_name"]
    stem = Path(input_pdf).stem
    for marker in ["_פנים", "_inside", "-פנים", " פנים"]:
        stem = stem.replace(marker, "")
    parts = [part for part in stem.replace("-", "_").split("_") if part and not part.isdigit()]
    if parts:
        value = parts[-1].strip()
        if value and not value.startswith("פרשת"):
            return "פרשת " + value
        return value
    return "פרשת השבוע"


def clean_delivery_stem(input_pdf):
    """Return the stable booklet stem used by delivered PDF filenames."""
    stem = Path(input_pdf).stem
    for marker in ["_פנים", "-פנים", " פנים", "_inside", "-inside", " inside", "_סופי", "-סופי", " סופי"]:
        stem = stem.replace(marker, "")
    stem = re.sub(r"[\s-]+", "_", stem)
    return re.sub(r"_+", "_", stem).strip("_")


def normalize_hebrew_quotes(text):
    return str(text).replace('"', "״").replace("''", "״").replace("׳׳", "״").replace("'", "׳").strip()


HEBREW_NUMERALS = {
    "א": 1,
    "ב": 2,
    "ג": 3,
    "ד": 4,
    "ה": 5,
    "ו": 6,
    "ז": 7,
    "ח": 8,
    "ט": 9,
    "י": 10,
    "כ": 20,
    "ך": 20,
    "ל": 30,
    "מ": 40,
    "ם": 40,
    "נ": 50,
    "ן": 50,
    "ס": 60,
    "ע": 70,
    "פ": 80,
    "ף": 80,
    "צ": 90,
    "ץ": 90,
    "ק": 100,
    "ר": 200,
    "ש": 300,
    "ת": 400,
}


def hebrew_year_to_int(text):
    text = normalize_hebrew_quotes(text)
    text = re.sub(r"^ה[׳״']", "", text)
    total = sum(HEBREW_NUMERALS.get(ch, 0) for ch in text)
    if not total:
        return None
    if total < 1000:
        total += 5000
    return total


def normalize_date_label(text):
    text = normalize_hebrew_quotes(text)
    text = text.replace("–", "-").replace("—", "-")
    text = re.sub(r"\s+", " ", text).strip(" ,-")
    text = re.sub(r"\s+תש[א-ת״׳]+$", "", text).strip(" ,-")
    same_month = re.match(r"^(.+?)\s+([א-ת]+)\s*-\s*(.+?)\s+\2$", text)
    if same_month:
        return f"{same_month.group(1)} {same_month.group(2)} עד {same_month.group(3)} {same_month.group(2)}"
    compact_same_month = re.match(r"^(.+?)\s*-\s*(.+?)\s+([א-ת]+)$", text)
    if compact_same_month:
        return f"{compact_same_month.group(1)} {compact_same_month.group(3)} עד {compact_same_month.group(2)} {compact_same_month.group(3)}"
    return text


def derive_toc_metadata_from_cover(cover_pdf):
    fitz = require_deps("fitz")["fitz"]
    doc = fitz.open(str(cover_pdf))
    try:
        text = " ".join(doc[0].get_text("text").split())
    finally:
        doc.close()

    meta = {}
    match = re.search(r"(?:שבת\s+)?פרשת\s+([^,]+),\s*(.*?)(?:-\s*חוברת|\s+חוברת|$)", text)
    if match:
        parsha = normalize_hebrew_quotes(match.group(1))
        if parsha:
            meta["parsha_name"] = parsha if parsha.startswith("פרשת") else "פרשת " + parsha
        raw_date = normalize_hebrew_quotes(match.group(2))
        year_match = re.search(r"(תש[א-ת״׳']+)", raw_date)
        if year_match:
            meta["hebrew_year"] = hebrew_year_to_int(year_match.group(1))
        date_label = normalize_date_label(raw_date)
        if date_label:
            if year_match:
                year_label = normalize_hebrew_quotes(year_match.group(1))
                if year_label not in date_label:
                    date_label = f"{date_label} {year_label}"
            meta["date_label"] = date_label
    else:
        parsha_match = re.search(r"פרשת\s+([א-ת\"׳״']+)", text)
        if parsha_match:
            meta["parsha_name"] = "פרשת " + normalize_hebrew_quotes(parsha_match.group(1))
    email_match = re.search(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", text)
    if email_match:
        meta["email_url"] = "mailto:" + email_match.group(0)
    return meta


def toc_config_from_args(args, analysis, input_pdf, cover_pdf):
    cfg = analysis_toc_config(analysis)
    cover_meta = derive_toc_metadata_from_cover(cover_pdf)
    for key in ("parsha_name", "date_label", "hebrew_year", "email_url"):
        if not cfg.get(key) and cover_meta.get(key):
            cfg[key] = cover_meta[key]
    if getattr(args, "parsha_name", None):
        cfg["parsha_name"] = args.parsha_name
    elif not cfg.get("parsha_name"):
        cfg["parsha_name"] = derive_parsha_name(input_pdf, analysis)
    if getattr(args, "date_label", None):
        cfg["date_label"] = args.date_label
    if not cfg.get("date_label"):
        raise RuntimeError("Could not determine the Sunday-to-Shabbos date label from the cover. Check the dates and pass --date-label.")
    if getattr(args, "whatsapp_url", None):
        cfg["whatsapp_url"] = args.whatsapp_url
    if getattr(args, "email_url", None):
        cfg["email_url"] = args.email_url
    if getattr(args, "archive_url", None):
        cfg["archive_url"] = args.archive_url
    return cfg


def detect_rotated(pdf_path):
    mods = require_deps("fitz", "numpy")
    fitz, np = mods["fitz"], mods["numpy"]
    doc = fitz.open(pdf_path)
    rotated = []
    try:
        for i in range(1, len(doc) - 1):
            blocks = doc[i].get_text("rawdict").get("blocks", [])
            chars = []
            for block in blocks:
                if block.get("type") != 0:
                    continue
                for line in block.get("lines", []):
                    for span in line.get("spans", []):
                        for ch in span.get("chars", []):
                            bb = ch.get("bbox")
                            if bb:
                                chars.append(((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2))
            if len(chars) < 10:
                continue
            xs = np.array([c[0] for c in chars[:200]])
            ys = np.array([c[1] for c in chars[:200]])
            dx = np.abs(np.diff(xs))
            dy = np.abs(np.diff(ys))
            mask = (dx < 100) & (dy < 100)
            if mask.sum() >= 3 and dy[mask].mean() / (dx[mask].mean() + 0.1) > 0.9:
                rotated.append(i + 1)
    finally:
        doc.close()
    return sorted(set(rotated))


def detect_blank(pdf_path):
    fitz = require_deps("fitz")["fitz"]
    doc = fitz.open(pdf_path)
    try:
        return sorted(i + 1 for i in range(len(doc)) if len(doc[i].get_text().strip()) < 5)
    finally:
        doc.close()


def span_text(span):
    return span.get("text", "") or "".join(ch.get("c", "") for ch in span.get("chars", []))


def detect_topics(pdf_path, rotated_original):
    if not rotated_original:
        raise RuntimeError("Could not detect the rotated פרשת השבוע page.")
    fitz = require_deps("fitz")["fitz"]
    doc = fitz.open(pdf_path)
    yom_rishon = []
    try:
        for i in range(len(doc)):
            found = False
            for block in doc[i].get_text("rawdict").get("blocks", []):
                if block.get("type") != 0:
                    continue
                for line in block.get("lines", []):
                    for span in line.get("spans", []):
                        if span.get("color") == 0xFFFFFF and "יום ראשון" in span_text(span):
                            yom_rishon.append(i + 1)
                            found = True
                            break
                    if found:
                        break
                if found:
                    break
    finally:
        doc.close()

    yom_rishon = [p for p in yom_rishon if p != 3]
    topics = {"הדף היומי": 2, "פרשת השבוע": min(rotated_original)}
    for i, name in enumerate(TOPIC_ORDER):
        if i < len(yom_rishon):
            topics[name] = yom_rishon[i]

    required = ["ליקוטי תפילות"]
    missing = [name for name in required if name not in topics]
    if missing:
        raise RuntimeError("Could not detect required topic(s): " + ", ".join(missing))
    topics["תיקון הכללי"] = topics["ליקוטי תפילות"] + 7
    topics["השלמות"] = topics["תיקון הכללי"] + 2
    return topics


def build_topic_ranges(pdf_path, topics):
    fitz = require_deps("fitz")["fitz"]
    total_orig = len(fitz.open(pdf_path))
    special = {"פרשת השבוע", "תיקון הכללי", "השלמות"}
    sorted_topics = sorted([(name, pg) for name, pg in topics.items() if name not in special], key=lambda x: x[1])
    if len(sorted_topics) < 3:
        raise RuntimeError("Too few topics were detected to calculate ranges.")
    topic_ranges = {}
    for idx, (name, start) in enumerate(sorted_topics):
        end = sorted_topics[idx + 1][1] - 1 if idx + 1 < len(sorted_topics) else topics["תיקון הכללי"] - 1
        topic_ranges[name] = (start, end)
    topic_ranges["הדף היומי"] = (topics["הדף היומי"], topics["פרשת השבוע"] - 1)
    topic_ranges["פרשת השבוע"] = (topics["פרשת השבוע"], topics[sorted_topics[1][0]] - 1)
    topic_ranges["תיקון הכללי"] = (topics["תיקון הכללי"], topics["השלמות"] - 1)
    topic_ranges["השלמות"] = (topics["השלמות"], total_orig - 1)
    return sorted_topics, topic_ranges, total_orig


def topic_for_original_page(orig, topic_ranges):
    for topic, bounds in topic_ranges.items():
        if topic in {"פרשת השבוע", "תיקון הכללי", "השלמות"}:
            continue
        start, end = bounds
        if start <= orig <= end:
            return topic
    return None


def classify_context_topic(text):
    compact = " ".join(text.split())
    for topic, patterns in CONTEXT_TOPIC_PATTERNS:
        if any(pattern in compact for pattern in patterns):
            return topic
    return None


def detect_context_pages(pdf_path, blank_pages, topic_ranges):
    fitz = require_deps("fitz")["fitz"]
    blank_set = set(blank_pages)
    context_pages = []
    unassigned = []
    start, end = topic_ranges.get("השלמות", (1, 0))
    doc = fitz.open(pdf_path)
    try:
        for orig in range(start, end + 1):
            if orig in blank_set:
                continue
            text = doc[orig - 1].get_text()
            topic = classify_context_topic(text)
            item = {"page": orig, "topic": topic, "source": "השלמות"}
            if topic:
                context_pages.append(item)
            else:
                unassigned.append(item)
    finally:
        doc.close()
    return context_pages, unassigned


def calculate_daf_yomi_day_runs(day_pages, topic_ranges, blank_pages):
    start, end = topic_ranges[DAF_YOMI_TOPIC]
    blank_set = set(blank_pages)
    runs = []
    for day in DAYS_ORDER:
        pages = [
            int(page)
            for page in day_pages.get(day, [])
            if start <= int(page) <= end and int(page) not in blank_set
        ]
        if not pages:
            continue
        item = {
            "day": day,
            "source_pages": pages,
            "source_start": pages[0],
            "source_end": pages[-1],
            "page_count": len(pages),
            "expected_page_count": EXPECTED_DAF_YOMI_PAGES_PER_DAY,
            "duplicate_amud": None,
        }
        item["status"] = "ok" if len(pages) == EXPECTED_DAF_YOMI_PAGES_PER_DAY else "needs_duplicate_amud"
        runs.append(item)
    return {
        "topic_range": [start, end],
        "expected_pages_per_day": EXPECTED_DAF_YOMI_PAGES_PER_DAY,
        "expected_total_pages": EXPECTED_DAF_YOMI_TOTAL_PAGES,
        "day_runs": runs,
    }


def is_kadmon_page(doc, page_0based, marker_text):
    all_white = []
    for block in doc[page_0based].get_text("rawdict").get("blocks", []):
        if block.get("type") != 0:
            continue
        for line in block.get("lines", []):
            for span in line.get("spans", []):
                if span.get("color", 0) == 0xFFFFFF:
                    all_white.append(span_text(span))
    combined = "".join(all_white)
    return marker_text in combined or "הקודם" in combined


def assign_days(pdf_path, blank_pages, topic_ranges, sorted_topics):
    fitz = require_deps("fitz")["fitz"]
    blank_set = set(blank_pages)
    doc = fitz.open(pdf_path)
    page_day = {}
    try:
        dy_s, dy_e = topic_ranges["הדף היומי"]
        dy_day_positions = {}
        for orig in range(dy_s, dy_e + 1):
            if orig in blank_set:
                continue
            text = doc[orig - 1].get_text()
            for day in DAYS_ORDER:
                if day in text and day not in dy_day_positions:
                    dy_day_positions[day] = orig
                    break

        sorted_dy = sorted(dy_day_positions.items(), key=lambda x: x[1])
        for i, (day, pos) in enumerate(sorted_dy):
            pre = pos - 1
            if pre >= dy_s and pre not in blank_set:
                page_day[pre] = day
            end = sorted_dy[i + 1][1] - 2 if i + 1 < len(sorted_dy) else dy_e
            for orig in range(pos, end + 1):
                if orig not in blank_set:
                    page_day[orig] = day

        daily_topics = [name for name, _ in sorted_topics]
        for name in daily_topics:
            if name in {"הדף היומי", "תהילים"}:
                continue
            s, e = topic_ranges[name]
            current_day = "יום ראשון"
            kadmon_marker = KADMON_MARKERS.get(name)
            if kadmon_marker:
                pre = s - 1
                if pre >= 1 and pre not in blank_set and is_kadmon_page(doc, pre - 1, kadmon_marker):
                    page_day[pre] = "יום ראשון"
            for orig in range(s, e + 1):
                if orig in blank_set:
                    continue
                if kadmon_marker and is_kadmon_page(doc, orig - 1, kadmon_marker):
                    page_day[orig] = "יום ראשון"
                    continue
                text = doc[orig - 1].get_text()
                new_day = next((day for day in DAYS_ORDER if day in text), None)
                if new_day and new_day != current_day:
                    page_day[orig] = new_day
                    current_day = new_day
                else:
                    page_day[orig] = current_day

        day_pages = {day: [] for day in DAYS_ORDER}
        for orig, day in page_day.items():
            day_pages[day].append(orig)

        teh_s, teh_e = topic_ranges["תהילים"]
        teh_markers = {}
        for orig in range(teh_s, teh_e + 1):
            if orig in blank_set:
                continue
            text = doc[orig - 1].get_text()
            for day in DAYS_ORDER:
                if day in text and day not in teh_markers:
                    teh_markers[day] = orig
        sorted_teh = sorted(teh_markers.items(), key=lambda x: x[1])
        for i, (day, marker_page) in enumerate(sorted_teh):
            end_page = sorted_teh[i + 1][1] if i + 1 < len(sorted_teh) else teh_e
            for orig in range(marker_page, end_page + 1):
                if orig not in blank_set and orig not in day_pages[day]:
                    day_pages[day].append(orig)

        for day in DAYS_ORDER:
            day_pages[day].sort(
                key=lambda p: (
                    next((i for i, (name, _) in enumerate(sorted_topics) if topic_ranges[name][0] <= p <= topic_ranges[name][1]), 99),
                    p,
                )
            )
        return day_pages
    finally:
        doc.close()


def analyze(args):
    input_pdf = resolve_path(args.input, args.workspace)
    cover_pdf = resolve_path(args.cover, args.workspace)
    rotated_original = detect_rotated(str(input_pdf))
    blank_pages = detect_blank(str(input_pdf))
    topics = detect_topics(str(input_pdf), rotated_original)
    sorted_topics, topic_ranges, total_orig = build_topic_ranges(str(input_pdf), topics)
    # תיקון הכללי מודפס לעיתים כרצף רוחבי. זיהוי עמוד בודד אינו מספיק:
    # אם אחד מעמודי הרצף זוהה כסרוק לרוחב, מסובבים את כל רצף תיקון הכללי.
    tikun_start, tikun_end = topic_ranges["תיקון הכללי"]
    tikun_pages = set(range(tikun_start, tikun_end + 1))
    if tikun_pages.intersection(rotated_original):
        rotated_original = sorted(set(rotated_original).union(tikun_pages))

    warnings = []
    dy_count = topics["פרשת השבוע"] - topics[DAF_YOMI_TOPIC]
    if dy_count != EXPECTED_DAF_YOMI_TOTAL_PAGES:
        warnings.append(f"גמרא מכילה {dy_count} עמודים; צפוי {EXPECTED_DAF_YOMI_TOTAL_PAGES}. חובה לבדוק לפני מסירה.")

    day_pages = assign_days(str(input_pdf), blank_pages, topic_ranges, sorted_topics)
    daf_yomi = calculate_daf_yomi_day_runs(day_pages, topic_ranges, blank_pages)
    for run in daf_yomi["day_runs"]:
        if run["status"] != "ok":
            warnings.append(
                f"דף יומי חריג ב{run['day']}: עמודי מקור {run['source_start']}-{run['source_end']} "
                f"({run['page_count']} עמודים במקום {run['expected_page_count']}). "
                "קודם מציגים למשתמש את היום ואת טווח עמודי המקור; רק אחר כך שואלים אם הכפל בעמוד א או בעמוד ב."
            )
    context_pages, unassigned_context_pages = detect_context_pages(str(input_pdf), blank_pages, topic_ranges)
    if unassigned_context_pages:
        pages = [item["page"] for item in unassigned_context_pages]
        warnings.append(f"עמודי השלמות לא משויכים: {pages}. יש לשייך לנושא לפני בנייה סופית.")
    previous_week_probe = {"context_pages": context_pages, "previous_week_pages": {}}
    missing_previous_week = [
        topic for topic in PREVIOUS_WEEK_TOPICS if topic not in previous_week_answered_topics(previous_week_probe)
    ]
    if missing_previous_week:
        warnings.append(
            "יש לשאול שאלה אחת בלבד: מהו עמוד השבוע הקודם של "
            + missing_previous_week[0]
            + "? לאחר התשובה עוברים לנושא הבא. אם אין, לרשום null/אין."
        )
    warnings.append(
        "Before add_day_notes, ask whether there is a previous-week Daf Yomi Gemara source page. "
        "If yes, record it as daf_yomi.previous_week_gemara_page; if no, record null. "
        "Do not leave the field absent."
    )
    metadata = derive_toc_metadata_from_cover(cover_pdf)
    booklet_match = re.search(r'(?<!\d)(\d{3,})(?!\d)', input_pdf.stem)
    if booklet_match:
        metadata['booklet_number'] = booklet_match.group(1)

    analysis = {
        "input_pdf": str(input_pdf),
        "cover_pdf": str(cover_pdf),
        "rotated_original": rotated_original,
        "blank_pages": blank_pages,
        "topics": topics,
        "topic_ranges": {k: list(v) for k, v in topic_ranges.items()},
        "day_pages": day_pages,
        "daf_yomi": daf_yomi,
        "context_pages": context_pages,
        "unassigned_context_pages": unassigned_context_pages,
        "previous_week_topics_required": PREVIOUS_WEEK_TOPICS,
        "total_original_pages": total_orig,
        "warnings": warnings,
        "lefi_yamim_6": metadata,
    }
    analysis_out = Path(args.analysis_out) if args.analysis_out else input_pdf.with_suffix(".lefi-yamim-analysis.json")
    analysis_out.parent.mkdir(parents=True, exist_ok=True)
    analysis_out.write_text(json.dumps(analysis, ensure_ascii=False, indent=2), encoding="utf-8")
    print_summary(analysis)
    for warning in warnings:
        print(f"WARNING: {warning}")
    print(f"ANALYSIS_JSON={analysis_out}")


def print_summary(analysis):
    print("עמודים מסובבים:", analysis["rotated_original"])
    print("עמודים ריקים:", analysis["blank_pages"])
    print("נושאים שזוהו:")
    for name, page in sorted(analysis["topics"].items(), key=lambda x: x[1]):
        start, end = analysis["topic_ranges"].get(name, ("?", "?"))
        print(f"  {name}: starts {page}, range {start}-{end}")
    print("חלוקת עמודים לימים:")
    for day in DAYS_ORDER:
        print(f"  {day}: {len(analysis['day_pages'].get(day, []))} עמ'")
    daf_yomi = analysis.get("daf_yomi", {})
    if daf_yomi.get("day_runs"):
        print("דף יומי לפי עמודי מקור:")
        for run in daf_yomi["day_runs"]:
            print(
                f"  {run['day']}: {run['source_start']}-{run['source_end']} "
                f"({run['page_count']} עמ') {run['status']}"
            )
    context_pages = analysis.get("context_pages", [])
    if context_pages:
        print("עמודי הקשר משבוע שעבר:")
        for item in context_pages:
            print(f"  עמ' {item['page']} -> {item['topic']}")
    unassigned = analysis.get("unassigned_context_pages", [])
    if unassigned:
        print("עמודי השלמות לא משויכים:")
        for item in unassigned:
            print(f"  עמ' {item['page']} -> ?")


def add_orig(writer, reader, orig, blank_set, orig_to_new_all, state):
    if orig in blank_set:
        return None
    writer.insert_pdf(reader, from_page=orig - 1, to_page=orig - 1)
    orig_to_new_all.setdefault(orig, []).append(state["new_idx"])
    state["new_idx"] += 1
    return state["new_idx"] - 1


def is_no_previous_week_page(value):
    if value is None or value is False:
        return True
    if isinstance(value, str):
        return value.strip().lower() in {"", "none", "null", "no", "אין"}
    return False


def add_previous_week_item(items, seen, page, topic, source):
    if topic not in CONTEXT_ALLOWED_TOPICS:
        raise RuntimeError(f"Previous-week page {page} is assigned to unsupported topic {topic}. Do not guess.")
    page = int(page)
    for item in items:
        if item["page"] == page and item["topic"] != topic:
            raise RuntimeError(
                f"Previous-week page {page} is assigned to both {item['topic']} and {topic}. Ask the user."
            )
    key = (page, topic)
    if key in seen:
        return
    seen.add(key)
    items.append({"page": page, "topic": topic, "source": source})


def previous_week_page_items(analysis):
    items = []
    seen = set()
    previous_week_pages = analysis.get("previous_week_pages", {})
    explicit_topics = set(previous_week_pages) if isinstance(previous_week_pages, dict) else set()
    for item in analysis.get("context_pages", []):
        topic = item.get("topic")
        page = item.get("page")
        if topic in explicit_topics:
            continue
        if topic and page:
            add_previous_week_item(items, seen, page, topic, item.get("source", "context_pages"))

    if isinstance(previous_week_pages, dict):
        for topic, value in previous_week_pages.items():
            if topic not in CONTEXT_ALLOWED_TOPICS:
                raise RuntimeError(f"previous_week_pages contains unsupported topic {topic}. Do not guess.")
            if is_no_previous_week_page(value):
                continue
            values = value if isinstance(value, list) else [value]
            for page in values:
                if not is_no_previous_week_page(page):
                    add_previous_week_item(items, seen, page, topic, "previous_week_pages")
    return items


def previous_week_answered_topics(analysis):
    previous_week_pages = analysis.get("previous_week_pages", {})
    if isinstance(previous_week_pages, dict):
        return {topic for topic in previous_week_pages if topic in CONTEXT_ALLOWED_TOPICS}
    return set()


def require_previous_week_answers(analysis):
    missing = [topic for topic in PREVIOUS_WEEK_TOPICS if topic not in previous_week_answered_topics(analysis)]
    if missing:
        raise RuntimeError(
            "Ask exactly one question now: what is the previous-week source page for "
            + missing[0]
            + "? Record a source page number or null/אין, then ask the next missing topic separately."
        )
    require_user_confirmation(
        analysis,
        PREVIOUS_WEEK_CONFIRMED_KEY,
        "Before building, previous-week supplement pages must be confirmed by the user. "
        f"Set analysis.{MANUAL_ANSWERS_KEY}.{PREVIOUS_WEEK_CONFIRMED_KEY}=true only after the user answers.",
    )


def context_pages_by_topic(analysis):
    grouped = {}
    for item in previous_week_page_items(analysis):
        grouped.setdefault(item["topic"], []).append(item["page"])
    for pages in grouped.values():
        pages.sort()
    return grouped


def context_page_set(analysis):
    return {item["page"] for item in previous_week_page_items(analysis)}


def build_pdf(args):
    fitz = require_deps("fitz")["fitz"]
    analysis = json.loads(Path(args.analysis).read_text(encoding="utf-8"))
    require_booklet_metadata_confirmation(analysis)
    require_previous_week_answers(analysis)
    require_daf_yomi_manual_answers(analysis)

    workspace = Path(args.workspace) if args.workspace else Path(analysis["input_pdf"]).parent
    input_pdf = resolve_path(args.input, workspace)
    cover_pdf = resolve_path(args.cover, workspace)
    pre_toc_page = resolve_pre_toc_page(args, analysis, workspace)
    source_contract.validate(analysis, sys.modules[__name__], input_pdf)
    hex_color = detect_circle_hex(str(cover_pdf))
    toc_cfg = toc_config_from_args(args, analysis, input_pdf, cover_pdf)
    if not all(external_links_from_config(toc_cfg).values()):
        raise RuntimeError('All three external TOC URLs are required')
    toc_dir = workspace / "__lefi_yamim_6_assets"
    toc_dir.mkdir(parents=True, exist_ok=True)
    days_toc_pdf = Path(args.days_toc) if args.days_toc else toc_dir / "toc-lefi-yamim-6.pdf"
    if not args.days_toc:
        make_toc_pdf(
            days_toc_pdf,
            toc_cfg["parsha_name"],
            toc_cfg["date_label"],
            color=hex_color,
            png_output=toc_dir / "toc-lefi-yamim-6.png",
            no_parsha=manual_user_answers(analysis).get("no_parsha_this_week") is True,
        )
    else:
        days_toc_pdf = resolve_path(args.days_toc, workspace)
    if args.button_img:
        button_img = resolve_path(args.button_img, workspace)
    else:
        button_img = make_return_button_image(toc_dir / "return-to-toc.png", color=hex_color)

    reader = fitz.open(str(input_pdf))
    cover = fitz.open(str(cover_pdf))
    days_toc = fitz.open(str(days_toc_pdf))
    writer = fitz.open()
    if len(cover) < 4:
        raise RuntimeError("Cover PDF must contain at least 4 pages; page 1 is the front cover and page 4 is the back cover.")
    writer.insert_pdf(cover, from_page=0, to_page=0)
    pre_toc_count = 0
    if pre_toc_page:
        insert_fitted_pdf_page(fitz, writer, pre_toc_page, cover[0].rect)
        pre_toc_count = 1
    writer.insert_pdf(days_toc, from_page=0, to_page=0)
    toc_page_number = 2 + pre_toc_count
    first_content_page = toc_page_number + 1

    blank_set = set(analysis["blank_pages"])
    topic_ranges = {k: tuple(v) for k, v in analysis["topic_ranges"].items()}
    day_pages = analysis["day_pages"]
    context_items = previous_week_page_items(analysis)
    context_orig_set = {item["page"] for item in context_items}
    state = {"new_idx": first_content_page}
    orig_to_new_all = {}

    parsha_new_pages = []
    for orig in range(topic_ranges["פרשת השבוע"][0], topic_ranges["פרשת השבוע"][1] + 1):
        pn = add_orig(writer, reader, orig, blank_set, orig_to_new_all, state)
        if pn:
            parsha_new_pages.append(pn)

    day_first_new = {}
    for day in DAYS_ORDER:
        for orig in day_pages.get(day, []):
            if orig in context_orig_set:
                continue
            pn = add_orig(writer, reader, orig, blank_set, orig_to_new_all, state)
            if pn and day not in day_first_new:
                day_first_new[day] = pn

    tikun_first_new = state["new_idx"]
    for orig in range(topic_ranges["תיקון הכללי"][0], topic_ranges["תיקון הכללי"][1] + 1):
        add_orig(writer, reader, orig, blank_set, orig_to_new_all, state)

    hash_first_new = state["new_idx"]
    for item in context_items:
        add_orig(writer, reader, item["page"], blank_set, orig_to_new_all, state)
    for orig in range(topic_ranges["השלמות"][0], topic_ranges["השלמות"][1] + 1):
        if orig in context_orig_set:
            continue
        add_orig(writer, reader, orig, blank_set, orig_to_new_all, state)

    writer.insert_pdf(cover, from_page=3, to_page=3)
    total = len(writer)
    rotated_new = set()
    for orig in analysis["rotated_original"]:
        for pn in orig_to_new_all.get(orig, []):
            rotated_new.add(pn)
            page = writer[pn - 1]
            page.set_rotation((page.rotation + 270) % 360)

    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False, dir=workspace) as tmp1:
        tmp1_path = tmp1.name
    writer.save(
        tmp1_path,
        garbage=4,
        deflate=True,
        deflate_images=True,
        deflate_fonts=True,
        clean=False,
        use_objstms=1,
    )
    writer.close()
    reader.close()
    cover.close()
    days_toc.close()

    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False, dir=workspace) as tmp2:
        tmp2_path = tmp2.name
    add_buttons_and_links(
        fitz,
        tmp1_path,
        str(button_img),
        rotated_new,
        total,
        parsha_new_pages,
        day_first_new,
        tikun_first_new,
        hash_first_new,
        tmp2_path,
        toc_page_number,
        external_links_from_config(toc_cfg),
    )

    out_dir = Path(args.out_dir) if args.out_dir else workspace / "לפי ימים"
    out_dir.mkdir(parents=True, exist_ok=True)
    base_name = clean_delivery_stem(input_pdf)
    final_out = Path(args.output) if getattr(args, 'output', None) else unique_output_path(out_dir / f"{base_name}_לפי_ימים.pdf")
    if final_out.exists():
        raise RuntimeError('Refusing to overwrite an existing output')
    final_out.parent.mkdir(parents=True, exist_ok=True)
    doc = fitz.open(tmp2_path)
    doc.save(
        str(final_out),
        garbage=4,
        deflate=True,
        deflate_images=True,
        deflate_fonts=True,
        clean=False,
        use_objstms=1,
    )
    doc.close()

    label_png = None
    label_preview = None
    if not getattr(args, "no_label", False):
        label_png = Path(args.label_output) if getattr(args, "label_output", None) else toc_dir / "weekly-label.png"
        label_preview = Path(args.label_preview) if getattr(args, "label_preview", None) else toc_dir / "weekly-label-preview.png"
        make_edition_label(
            toc_cfg["parsha_name"],
            toc_cfg["date_label"],
            hex_color,
            "days",
            label_png,
            preview=label_preview,
            brand=getattr(args, "brand", None) or DEFAULT_BRAND,
        )
        attach_label(
            argparse.Namespace(
                pdf=str(final_out),
                label_url=None,
                label_png=str(label_png),
                label_crop="none",
                timeout_seconds=120,
            )
        )

    os.unlink(tmp1_path)
    os.unlink(tmp2_path)
    print(f"FINAL_PDF={final_out}")
    print(f"FILE_SIZE_MB={final_out.stat().st_size / 1024 / 1024:.1f}")
    print(f"COVER_CIRCLE_HEX={hex_color}")
    if pre_toc_page:
        print(f"PRE_TOC_PAGE={pre_toc_page}")
    print(f"TOC_PAGE={toc_page_number}")
    if label_png:
        print(f"LABEL_PNG={label_png}")
    if label_preview:
        print(f"LABEL_PREVIEW={label_preview}")


def unique_output_path(path):
    if not path.exists():
        return path
    stem = path.with_suffix("")
    suffix = path.suffix
    i = 2
    while True:
        candidate = Path(f"{stem}_v{i}{suffix}")
        if not candidate.exists():
            return candidate
        i += 1


def add_buttons_and_links(fitz, tmp_in, button_img, rotated_new, total, parsha_new_pages, day_first_new, tikun_first_new, hash_first_new, tmp_out, toc_page_number, external_links=None):
    doc = fitz.open(tmp_in)
    toc_idx = toc_page_number - 1
    first_content_page = toc_page_number + 1
    try:
        for pn in range(first_content_page, total + 1):
            page = doc[pn - 1]
            if pn in rotated_new:
                img_rect = fitz.Rect(437, 282, 467, 404)
                page.insert_image(img_rect, filename=button_img, overlay=True, rotate=-90)
            else:
                img_rect = fitz.Rect(170, 5, 295, 37)
                page.insert_image(img_rect, filename=button_img, overlay=True)
            page.insert_link({"kind": fitz.LINK_GOTO, "from": img_rect, "page": toc_idx, "to": fitz.Point(0, 0)})

        day_to_key = {
            DAYS_ORDER[0]: "sunday",
            DAYS_ORDER[1]: "monday",
            DAYS_ORDER[2]: "tuesday",
            DAYS_ORDER[3]: "wednesday",
            DAYS_ORDER[4]: "thursday",
            DAYS_ORDER[5]: "friday",
            DAYS_ORDER[6]: "shabbos",
        }
        targets = {day_to_key[day]: day_first_new[day] for day in DAYS_ORDER if day in day_first_new}
        if parsha_new_pages:
            targets["parsha"] = parsha_new_pages[0]
        targets["tikun"] = tikun_first_new

        toc_page = doc[toc_idx]
        insert_toc_links(fitz, toc_page, targets, external_links)
        doc.save(
            tmp_out,
            garbage=4,
            deflate=True,
            deflate_images=True,
            deflate_fonts=True,
            clean=False,
            use_objstms=1,
        )
    finally:
        doc.close()


def detect_circle_hex(pdf_path):
    fitz = require_deps("fitz")["fitz"]
    doc = fitz.open(pdf_path)
    try:
        page = doc[0]
        pw, ph = page.rect.width, page.rect.height
        candidates = []
        for drawing in page.get_drawings():
            rect = drawing.get("rect")
            fill = drawing.get("fill")
            if not (fill and rect) or len(fill) < 3:
                continue
            if rect.x0 < pw * 0.5 or rect.y0 < ph * 0.55:
                continue
            if (rect.x1 - rect.x0) < 25 or (rect.y1 - rect.y0) < 25:
                continue
            r, g, b = fill[:3]
            if max(r, g, b) < 0.2 or min(r, g, b) > 0.85 or max(r, g, b) - min(r, g, b) < 0.08:
                continue
            area = (rect.x1 - rect.x0) * (rect.y1 - rect.y0)
            candidates.append((area, (r, g, b)))
        if not candidates:
            return "80D6F7"
        r, g, b = sorted(candidates, reverse=True)[0][1]
        return f"{int(r * 255):02X}{int(g * 255):02X}{int(b * 255):02X}"
    finally:
        doc.close()


def validate_pdf(fitz, pdf_path):
    doc = fitz.open(str(pdf_path))
    try:
        if doc.page_count < 1:
            raise RuntimeError(f"PDF has no pages after label attach: {pdf_path}")
        doc[0].get_text("text")
    finally:
        doc.close()


def make_label_work_dir(parent):
    for i in range(1000):
        candidate = parent / f"__label_tmp_{os.getpid()}_{i}"
        try:
            candidate.mkdir()
            return candidate
        except FileExistsError:
            continue
    raise RuntimeError(f"Could not create label work directory in {parent}")


def attach_label_worker(args):
    mods = require_deps("fitz", "PIL")
    fitz = mods["fitz"]
    from PIL import Image

    pdf_path = Path(args.pdf)
    label_path = Path(args.label_png)
    with Image.open(label_path) as label:
        label_aspect = label.height / label.width

    doc = fitz.open(str(pdf_path))
    try:
        page = doc[0]
        pw, ph = page.rect.width, page.rect.height
        x1 = 0.275 * pw
        x2 = 0.626 * pw
        lw = x2 - x1
        lh = lw * label_aspect
        cy = 0.339 * ph + 2.83
        y1 = cy - lh / 2
        y2 = cy + lh / 2
        page.insert_image(fitz.Rect(x1, y1, x2, y2), filename=str(label_path), overlay=True)
        doc.saveIncr()
    finally:
        doc.close()


def attach_label(args):
    mods = require_deps("fitz", "PIL")
    fitz = mods["fitz"]
    from PIL import Image

    pdf_path = Path(args.pdf)
    if not pdf_path.exists():
        raise FileNotFoundError(pdf_path)
    pdf_path = pdf_path.resolve()
    timeout_seconds = max(1, int(args.timeout_seconds))

    tmp_dir = make_label_work_dir(pdf_path.parent)
    try:
        full_png = tmp_dir / "label_full.png"
        cropped_png = tmp_dir / "label_cropped.png"
        work_pdf = tmp_dir / "label_work.pdf"
        replacement_pdf = None

        if args.label_url:
            urllib.request.urlretrieve(args.label_url, full_png)
        else:
            full_png = Path(args.label_png)
        with Image.open(full_png) as label:
            if args.label_crop == "canva":
                label.crop((60, 380, 1000, 935)).save(cropped_png)
            else:
                label.save(cropped_png)

        shutil.copy2(pdf_path, work_pdf)
        cmd = [
            sys.executable,
            str(Path(__file__).resolve()),
            "_attach-label-worker",
            "--pdf",
            work_pdf.name,
            "--label-png",
            cropped_png.name,
        ]
        try:
            result = subprocess.run(
                cmd,
                cwd=str(tmp_dir),
                capture_output=True,
                text=True,
                timeout=timeout_seconds,
            )
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(f"attach-label timed out after {timeout_seconds} seconds") from exc
        if result.returncode != 0:
            details = (result.stderr or result.stdout or "").strip()
            raise RuntimeError(f"attach-label worker failed with exit code {result.returncode}: {details}")

        validate_pdf(fitz, work_pdf)
        replacement_pdf = unique_output_path(pdf_path.parent / "__label_applied_replace.pdf")
        try:
            shutil.copy2(work_pdf, replacement_pdf)
            validate_pdf(fitz, replacement_pdf)
            os.replace(replacement_pdf, pdf_path)
        finally:
            if replacement_pdf and replacement_pdf.exists():
                replacement_pdf.unlink()
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
    print(f"LABEL_ATTACHED={pdf_path}")


def main():
    parser = argparse.ArgumentParser(description="Build אוצרות יהודים לפי ימים PDFs.")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("analyze")
    p.add_argument("--workspace")
    p.add_argument("--input", required=True)
    p.add_argument("--cover", required=True)
    p.add_argument("--analysis-out")
    p.set_defaults(func=analyze)

    p = sub.add_parser("build")
    p.add_argument("--workspace")
    p.add_argument("--input", required=True)
    p.add_argument("--cover", required=True)
    p.add_argument("--analysis", required=True)
    p.add_argument("--days-toc")
    p.add_argument("--pre-toc-page", help="PDF page to insert between the cover and the generated TOC.")
    p.add_argument("--no-pre-toc-page", action="store_true", help="Skip the pre-TOC page; intended only for internal QA.")
    p.add_argument("--button-img")
    p.add_argument("--out-dir")
    p.add_argument("--output", help="Explicit new output path, including the requested timestamp prefix.")
    p.add_argument("--parsha-name")
    p.add_argument("--date-label")
    p.add_argument("--whatsapp-url")
    p.add_argument("--email-url")
    p.add_argument("--archive-url")
    p.add_argument("--no-label", action="store_true", help="Skip automatic local weekly label generation and attachment.")
    p.add_argument("--label-output")
    p.add_argument("--label-preview")
    p.add_argument("--brand", default=DEFAULT_BRAND)
    p.set_defaults(func=build_pdf)

    p = sub.add_parser("attach-label")
    p.add_argument("--pdf", required=True)
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--label-url")
    group.add_argument("--label-png")
    p.add_argument("--label-crop", choices=["canva", "none"], default="canva")
    p.add_argument("--timeout-seconds", type=int, default=120)
    p.set_defaults(func=attach_label)

    p = sub.add_parser("_attach-label-worker", help=argparse.SUPPRESS)
    p.add_argument("--pdf", required=True)
    p.add_argument("--label-png", required=True)
    p.set_defaults(func=attach_label_worker)

    args = parser.parse_args()
    try:
        args.func(args)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
