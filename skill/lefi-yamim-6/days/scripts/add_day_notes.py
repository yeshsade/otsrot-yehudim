#!/usr/bin/env python3
import argparse
import colorsys
import json
import sys
import tempfile
from contextlib import nullcontext
from pathlib import Path

from toc_template import (
    analysis_toc_config,
    continuation_rects_for_page,
    external_links_from_config,
    insert_toc_links,
    make_continuation_button_images,
    make_return_button_image,
    toc_button_rects,
)


DAYS = [
    ("sunday", "יום ראשון"),
    ("monday", "יום שני"),
    ("tuesday", "יום שלישי"),
    ("wednesday", "יום רביעי"),
    ("thursday", "יום חמישי"),
    ("friday", "יום שישי"),
    ("shabbos", "שבת קודש"),
]
DAY_TEXT_TO_SLUG = {text: slug for slug, text in DAYS}
NOTE_SUBTITLE = "לנצח עוד יום"
REQUIRED_TOPICS = ["פרשת השבוע", "תיקון הכללי", "השלמות"]
DAF_YOMI_TOPIC = "הדף היומי"
EXPECTED_DAF_YOMI_PAGES_PER_DAY = 4
CONTINUATION_TOPICS = {
    DAF_YOMI_TOPIC,
    "נביאים וכתובים",
    "משנה",
    "שולחן ערוך",
    "משנה ברורה",
    "זוהר",
    'ליקוטי מוהר"ן',
    "ליקוטי הלכות",
    "ספרי רבינו על פי הסדר",
    "שמות הצדיקים / ספר המידות",
    "ליקוטי תפילות",
}
CONTEXT_ALLOWED_TOPICS = {
    "משנה ברורה",
    'ליקוטי מוהר"ן',
    "ליקוטי הלכות",
}
PREVIOUS_WEEK_TOPICS = [
    "משנה ברורה",
    'ליקוטי מוהר"ן',
    "ליקוטי הלכות",
]
MANUAL_ANSWERS_KEY = "manual_user_answers"
BOOKLET_METADATA_CONFIRMED_KEY = "booklet_metadata_confirmed_by_user"
PREVIOUS_WEEK_CONFIRMED_KEY = "previous_week_pages_confirmed_by_user"
DAF_YOMI_PREVIOUS_WEEK_CONFIRMED_KEY = "daf_yomi_previous_week_confirmed_by_user"
DAF_YOMI_DUPLICATE_AMUD_CONFIRMED_KEY = "daf_yomi_duplicate_amud_confirmed_by_user"
DEFAULT_TOC_PAGE_NUMBER = 3


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
            "Before adding day notes, ask the user for the exact booklet metadata and store "
            + ", ".join(missing)
            + " in analysis.lefi_yamim_6. Keep booklet_number in Arabic digits and require user confirmation."
        )
    require_user_confirmation(
        analysis,
        BOOKLET_METADATA_CONFIRMED_KEY,
        "Before adding day notes, ask the user to confirm the exact booklet name/date label. "
        f"Set analysis.{MANUAL_ANSWERS_KEY}.{BOOKLET_METADATA_CONFIRMED_KEY}=true only after the user answers.",
    )


def require_deps():
    missing = []
    try:
        import fitz  # noqa: F401
    except ImportError:
        missing.append("PyMuPDF")
    try:
        from PIL import Image  # noqa: F401
    except ImportError:
        missing.append("Pillow")
    if missing:
        raise SystemExit("Missing required Python package(s): " + ", ".join(missing))


def unique_path(path):
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


def count_range(topic_ranges, blank_pages, name):
    start, end = topic_ranges[name]
    blank = set(blank_pages)
    return sum(1 for page in range(start, end + 1) if page not in blank)


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
        "Before adding day notes, previous-week supplement pages must be confirmed by the user. "
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


def calculate_base_targets(analysis, toc_page_number=DEFAULT_TOC_PAGE_NUMBER):
    topic_ranges = {k: tuple(v) for k, v in analysis["topic_ranges"].items()}
    blank_pages = set(analysis["blank_pages"])
    missing = [name for name in REQUIRED_TOPICS if name not in topic_ranges]
    if missing:
        raise RuntimeError("analysis is missing required topic range(s): " + ", ".join(missing))

    parsha_count = count_range(topic_ranges, blank_pages, REQUIRED_TOPICS[0])
    context_pages = context_page_set(analysis)
    context_count = len([page for page in context_pages if page not in blank_pages])
    day_counts = {}
    for _slug, day_text in DAYS:
        pages = analysis["day_pages"].get(day_text, [])
        day_counts[day_text] = len([page for page in pages if page not in blank_pages and page not in context_pages])
        if day_counts[day_text] == 0:
            raise RuntimeError(f"analysis has no pages for {day_text}")

    tikun_count = count_range(topic_ranges, blank_pages, REQUIRED_TOPICS[1])
    hash_start, hash_end = topic_ranges[REQUIRED_TOPICS[2]]
    remaining_hashlamot_count = sum(
        1 for page in range(hash_start, hash_end + 1) if page not in blank_pages and page not in context_pages
    )
    hashlamot_count = context_count + remaining_hashlamot_count

    # Base build layout from build_lefi_yamim.py:
    # 1 cover, 2 pre-TOC page, 3 weekday TOC, 4.. parsha, then days, tikun, hashlamot, back cover.
    first_content_page = toc_page_number + 1
    next_page = first_content_page + parsha_count
    day_first = {}
    for _slug, day_text in DAYS:
        day_first[day_text] = next_page
        next_page += day_counts[day_text]
    tikun_first = next_page
    hashlamot_first = tikun_first + tikun_count
    back_cover = hashlamot_first + hashlamot_count
    return {
        "toc": toc_page_number,
        "parsha": first_content_page,
        "day_first": day_first,
        "tikun": tikun_first,
        "hashlamot": hashlamot_first if hashlamot_count else None,
        "back_cover": back_cover,
        "counts": {
            "parsha": parsha_count,
            "days": day_counts,
            "tikun": tikun_count,
            "hashlamot": hashlamot_count,
        },
    }


def has_parsha(analysis):
    return "פרשת השבוע" not in set(analysis.get("absent_topics") or [])


def base_hashlamot_sequence(analysis):
    topic_ranges = {k: tuple(v) for k, v in analysis["topic_ranges"].items()}
    blank_pages = set(analysis["blank_pages"])
    context_pages = context_page_set(analysis)
    pages = [item["page"] for item in previous_week_page_items(analysis) if item["page"] not in blank_pages]
    start, end = topic_ranges[REQUIRED_TOPICS[2]]
    for orig in range(start, end + 1):
        if orig in blank_pages or orig in context_pages:
            continue
        pages.append(orig)
    return pages


def topic_for_original_page(orig, topic_ranges):
    for topic, bounds in topic_ranges.items():
        if topic not in CONTINUATION_TOPICS:
            continue
        start, end = bounds
        if start <= orig <= end:
            return topic
    return None


def calculate_base_continuation_segments(analysis, toc_page_number=DEFAULT_TOC_PAGE_NUMBER):
    """Return day/topic page runs in the base PDF before day-note insertion."""
    blank_pages = set(analysis["blank_pages"])
    topic_ranges = {name: tuple(bounds) for name, bounds in analysis["topic_ranges"].items()}
    context_pages = context_page_set(analysis)
    parsha_start, parsha_end = topic_ranges[REQUIRED_TOPICS[0]]

    # Base layout: cover, pre-TOC page, TOC, parsha block, then all weekday blocks.
    next_page = toc_page_number + 1
    for orig in range(parsha_start, parsha_end + 1):
        if orig not in blank_pages:
            next_page += 1

    segments = {slug: {} for slug, _day_text in DAYS}
    for slug, day_text in DAYS:
        for orig in analysis["day_pages"].get(day_text, []):
            if orig in blank_pages or orig in context_pages:
                continue
            topic = topic_for_original_page(orig, topic_ranges)
            if topic:
                segments[slug].setdefault(topic, []).append(next_page)
            next_page += 1

    tikun_start, tikun_end = topic_ranges[REQUIRED_TOPICS[1]]
    for orig in range(tikun_start, tikun_end + 1):
        if orig not in blank_pages:
            next_page += 1

    context_topic_by_page = {item["page"]: item["topic"] for item in previous_week_page_items(analysis)}
    context_segments = {}
    for orig in base_hashlamot_sequence(analysis):
        topic = context_topic_by_page.get(orig)
        if topic:
            context_segments.setdefault(topic, []).append(next_page)
        next_page += 1
    return segments, context_segments


def final_day_content_page(base_page, day_index):
    # add_day_notes inserts one note page before each weekday block.
    return base_page + day_index + 1


def final_after_day_notes_page(base_page):
    return base_page + len(DAYS)


DAF_YOMI_PREVIOUS_WEEK_KEYS = (
    "previous_week_gemara_page",
    "previous_week_gemara_source_page",
    "previous_week_page",
)
DAF_YOMI_PREVIOUS_WEEK_ROOT_KEYS = (
    "daf_yomi_previous_week_gemara_page",
    "daf_yomi_previous_week_page",
)


def is_no_daf_yomi_previous_week_page(value):
    return is_no_previous_week_page(value)


def daf_yomi_previous_week_gemara_page(analysis):
    daf_yomi = analysis.get("daf_yomi", {})
    for key in DAF_YOMI_PREVIOUS_WEEK_KEYS:
        value = daf_yomi.get(key)
        if not is_no_daf_yomi_previous_week_page(value):
            return int(value)
    for key in DAF_YOMI_PREVIOUS_WEEK_ROOT_KEYS:
        value = analysis.get(key)
        if not is_no_daf_yomi_previous_week_page(value):
            return int(value)
    return None


def unassigned_hashlamot_base_page(analysis, source_page, toc_page_number=DEFAULT_TOC_PAGE_NUMBER):
    base_targets = calculate_base_targets(analysis, toc_page_number)
    base_page = base_targets.get("hashlamot")
    if not base_page:
        return None
    for orig in base_hashlamot_sequence(analysis):
        if orig == source_page:
            return base_page
        base_page += 1
    return None


def continuation_rects(fitz, page):
    return continuation_rects_for_page(fitz, page)


def make_continuation_label_images(out_dir, color="#6DCFF6"):
    return make_continuation_button_images(out_dir, color=color)


def draw_continuation_label(page, rect, label_path):
    rotate = page.rotation if page.rotation in {90, 270} else 0
    page.insert_image(rect, filename=str(label_path), overlay=True, rotate=rotate)


def target_top_left_point(fitz, target_pdf_page):
    point = fitz.Point(0, 0)
    if target_pdf_page.rotation in {90, 270}:
        return point * target_pdf_page.derotation_matrix
    return point


def insert_goto_link(fitz, doc, page, rect, target_page):
    target_pdf_page = doc[target_page - 1]
    page.insert_link({
        "kind": fitz.LINK_GOTO,
        "from": rect,
        "page": target_page - 1,
        "to": target_top_left_point(fitz, target_pdf_page),
    })


def return_button_rect_for_page(fitz, page):
    button_w = 125
    button_h = 32
    x1 = max((page.rect.width - button_w) / 2, 0)
    return fitz.Rect(x1, 5, x1 + button_w, 5 + button_h)


def add_return_buttons_to_pages(fitz, doc, page_numbers, image_path, toc_page_number=DEFAULT_TOC_PAGE_NUMBER):
    for page_number in page_numbers:
        page = doc[page_number - 1]
        rect = return_button_rect_for_page(fitz, page)
        page.insert_image(rect, filename=str(image_path), overlay=True)
        page.insert_link({"kind": fitz.LINK_GOTO, "from": rect, "page": toc_page_number - 1, "to": fitz.Point(0, 0)})


def find_daf_yomi_range(analysis):
    topic_ranges = analysis.get("topic_ranges", {})
    if DAF_YOMI_TOPIC in topic_ranges:
        return tuple(topic_ranges[DAF_YOMI_TOPIC])
    for name, bounds in topic_ranges.items():
        if "׳“׳£" in name and "׳™׳•׳׳™" in name:
            return tuple(bounds)
    candidates = [tuple(bounds) for bounds in topic_ranges.values() if tuple(bounds)[0] == 2]
    if len(candidates) == 1:
        return candidates[0]
    return None


def daf_yomi_previous_week_answered(analysis):
    if not find_daf_yomi_range(analysis):
        return True
    daf_yomi = analysis.get("daf_yomi", {})
    if isinstance(daf_yomi, dict) and any(key in daf_yomi for key in DAF_YOMI_PREVIOUS_WEEK_KEYS):
        return True
    return any(key in analysis for key in DAF_YOMI_PREVIOUS_WEEK_ROOT_KEYS)


def require_daf_yomi_previous_week_answer(analysis):
    if not daf_yomi_previous_week_answered(analysis):
        raise RuntimeError(
            "Before adding day notes, ask the user whether there is a previous-week Daf Yomi Gemara source page. "
            "Record the answer in analysis.daf_yomi.previous_week_gemara_page as a source page number or null/אין."
        )
    require_user_confirmation(
        analysis,
        DAF_YOMI_PREVIOUS_WEEK_CONFIRMED_KEY,
        "Before adding day notes, the previous-week Daf Yomi Gemara answer must be confirmed by the user. "
        f"Set analysis.{MANUAL_ANSWERS_KEY}.{DAF_YOMI_PREVIOUS_WEEK_CONFIRMED_KEY}=true only after the user answers.",
    )


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

def require_daf_yomi_duplicate_amud_confirmation(analysis):
    if not daf_yomi_duplicate_amud_required(analysis):
        return
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
        "Before adding day notes, the unusual Daf Yomi duplicate-amud answer must be confirmed by the user. "
        f"Set analysis.{MANUAL_ANSWERS_KEY}.{DAF_YOMI_DUPLICATE_AMUD_CONFIRMED_KEY}=true only after the user answers.",
    )


def daf_yomi_run_lookup(analysis):
    daf_range = find_daf_yomi_range(analysis)
    if not daf_range:
        return {}
    start, end = daf_range
    blank_pages = set(analysis.get("blank_pages", []))
    explicit = {}
    for item in analysis.get("daf_yomi", {}).get("day_runs", []):
        source_start = item.get("source_start")
        source_end = item.get("source_end")
        if source_start and source_end:
            explicit[(int(source_start), int(source_end))] = normalized_duplicate_amud(item.get("duplicate_amud"))
    for key, value in analysis.get("daf_yomi_duplicates", {}).items():
        try:
            source_start, source_end = [int(part) for part in key.split("-", 1)]
        except ValueError:
            continue
        explicit[(source_start, source_end)] = normalized_duplicate_amud(value)

    lookup = {}
    for _slug, day_text in DAYS:
        pages = [
            int(page)
            for page in analysis.get("day_pages", {}).get(day_text, [])
            if start <= int(page) <= end and int(page) not in blank_pages
        ]
        if pages:
            source_range = (pages[0], pages[-1])
            lookup[day_text] = {
                "source_pages": pages,
                "source_start": pages[0],
                "source_end": pages[-1],
                "duplicate_amud": explicit.get(source_range),
            }
    return lookup


def daf_yomi_link_endpoints(day_text, final_pages, run_lookup):
    run = run_lookup.get(day_text, {})
    if len(final_pages) == EXPECTED_DAF_YOMI_PAGES_PER_DAY:
        return {
            "previous_source": final_pages[1],
            "next_source": final_pages[2],
        }
    duplicate_amud = normalized_duplicate_amud(run.get("duplicate_amud"))
    if len(final_pages) == 6:
        if duplicate_amud not in {"a", "b"}:
            start = run.get("source_start", "?")
            end = run.get("source_end", "?")
            raise RuntimeError(
                f"Daf yomi exception on {day_text}: source pages {start}-{end}. "
                "Ask whether the duplication is on page a, page b, or both before adding continuation links."
            )
        return {
            "previous_source": final_pages[1],
            "next_source": final_pages[4],
        }
    if len(final_pages) == 8:
        if duplicate_amud != "both":
            start = run.get("source_start", "?")
            end = run.get("source_end", "?")
            raise RuntimeError(
                f"Daf yomi exception on {day_text}: source pages {start}-{end}. "
                "This 8-page run requires duplicate_amud='both' after user confirmation."
            )
        return {
            "previous_source": final_pages[1],
            "next_source": final_pages[6],
        }
    start = run.get("source_start", "?")
    end = run.get("source_end", "?")
    raise RuntimeError(
        f"Daf yomi exception on {day_text}: source pages {start}-{end} have {len(final_pages)} pages. Do not guess."
    )

def add_continuation_links(fitz, doc, analysis, color="#6DCFF6", temp_parent=None, toc_page_number=DEFAULT_TOC_PAGE_NUMBER):
    base_segments, base_context_segments = calculate_base_continuation_segments(analysis, toc_page_number)
    daf_yomi_runs = daf_yomi_run_lookup(analysis)
    final_segments = {}
    for day_index, (slug, _day_text) in enumerate(DAYS):
        final_segments[slug] = {}
        for topic, pages in base_segments.get(slug, {}).items():
            final_segments[slug][topic] = [final_day_content_page(page, day_index) for page in pages]
    final_context_segments = {
        topic: [final_after_day_notes_page(page) for page in pages]
        for topic, pages in base_context_segments.items()
    }

    link_records = []
    if temp_parent:
        temp_context = nullcontext(Path(temp_parent) / "continuation-buttons")
    else:
        temp_context = tempfile.TemporaryDirectory()
    with temp_context as tmp:
        label_paths = make_continuation_label_images(Path(tmp), color=color)
        for topic, pages in final_context_segments.items():
            sunday_pages = final_segments.get("sunday", {}).get(topic, [])
            if not pages or not sunday_pages:
                continue
            source = pages[-1]
            target = sunday_pages[0]
            page = doc[source - 1]
            rect = continuation_rects(fitz, page)["next"]
            insert_goto_link(fitz, doc, page, rect, target)
            draw_continuation_label(page, rect, label_paths["next"])
            link_records.append({"type": "previous-week-next", "day": "׳™׳•׳ ׳¨׳׳©׳•׳", "topic": topic, "source": source, "target": target})

            page = doc[target - 1]
            rect = continuation_rects(fitz, page)["previous"]
            insert_goto_link(fitz, doc, page, rect, source)
            draw_continuation_label(page, rect, label_paths["previous"])
            link_records.append({"type": "first-day-previous-week", "day": "׳™׳•׳ ׳¨׳׳©׳•׳", "topic": topic, "source": target, "target": source})

        previous_week_orig = daf_yomi_previous_week_gemara_page(analysis)
        if previous_week_orig:
            previous_week_base = unassigned_hashlamot_base_page(analysis, previous_week_orig, toc_page_number)
            if previous_week_base is None:
                raise RuntimeError(
                    f"Daf yomi previous-week Gemara page {previous_week_orig} was not found as an unassigned hashlamot page."
                )
            previous_week_final = final_after_day_notes_page(previous_week_base)
            sunday_pages = final_segments.get("sunday", {}).get(DAF_YOMI_TOPIC, [])
            if not sunday_pages:
                raise RuntimeError("Daf yomi previous-week page was provided, but Sunday daf yomi pages were not found.")
            sunday_endpoints = daf_yomi_link_endpoints(DAYS[0][1], sunday_pages, daf_yomi_runs)
            first_sunday_gemara = sunday_endpoints["previous_source"]

            page = doc[previous_week_final - 1]
            rect = continuation_rects(fitz, page)["next"]
            insert_goto_link(fitz, doc, page, rect, first_sunday_gemara)
            draw_continuation_label(page, rect, label_paths["next"])
            link_records.append(
                {
                    "type": "daf-yomi-previous-week-next",
                    "day": DAYS[0][1],
                    "topic": DAF_YOMI_TOPIC,
                    "source": previous_week_final,
                    "target": first_sunday_gemara,
                    "source_original_page": previous_week_orig,
                }
            )

            page = doc[first_sunday_gemara - 1]
            rect = continuation_rects(fitz, page)["previous"]
            insert_goto_link(fitz, doc, page, rect, previous_week_final)
            draw_continuation_label(page, rect, label_paths["previous"])
            link_records.append(
                {
                    "type": "daf-yomi-first-day-previous-week",
                    "day": DAYS[0][1],
                    "topic": DAF_YOMI_TOPIC,
                    "source": first_sunday_gemara,
                    "target": previous_week_final,
                    "target_original_page": previous_week_orig,
                }
            )

        for day_index, (slug, day_text) in enumerate(DAYS):
            day_segments = final_segments.get(slug, {})
            prev_slug = DAYS[day_index - 1][0] if day_index > 0 else None
            next_slug = DAYS[day_index + 1][0] if day_index + 1 < len(DAYS) else None

            for topic, pages in day_segments.items():
                if not pages:
                    continue
                if topic == DAF_YOMI_TOPIC:
                    current_daf_endpoints = daf_yomi_link_endpoints(day_text, pages, daf_yomi_runs)
                if prev_slug and final_segments.get(prev_slug, {}).get(topic):
                    prev_pages = final_segments[prev_slug][topic]
                    if topic == DAF_YOMI_TOPIC:
                        prev_day_text = DAYS[day_index - 1][1]
                        prev_daf_endpoints = daf_yomi_link_endpoints(prev_day_text, prev_pages, daf_yomi_runs)
                        source = current_daf_endpoints["previous_source"]
                        target = prev_daf_endpoints["next_source"]
                    else:
                        source = pages[0]
                        target = prev_pages[-1]
                    page = doc[source - 1]
                    rect = continuation_rects(fitz, page)["previous"]
                    insert_goto_link(fitz, doc, page, rect, target)
                    draw_continuation_label(page, rect, label_paths["previous"])
                    link_records.append(
                        {"type": "previous", "day": day_text, "topic": topic, "source": source, "target": target}
                    )

                if next_slug and final_segments.get(next_slug, {}).get(topic):
                    next_pages = final_segments[next_slug][topic]
                    if topic == DAF_YOMI_TOPIC:
                        next_day_text = DAYS[day_index + 1][1]
                        next_daf_endpoints = daf_yomi_link_endpoints(next_day_text, next_pages, daf_yomi_runs)
                        source = current_daf_endpoints["next_source"]
                        target = next_daf_endpoints["previous_source"]
                    else:
                        source = pages[-1]
                        target = next_pages[0]
                    page = doc[source - 1]
                    rect = continuation_rects(fitz, page)["next"]
                    insert_goto_link(fitz, doc, page, rect, target)
                    draw_continuation_label(page, rect, label_paths["next"])
                    link_records.append({"type": "next", "day": day_text, "topic": topic, "source": source, "target": target})

    return {"segments": final_segments, "context_segments": final_context_segments, "links": link_records}


def derive_palette(hex_color):
    hex_color = hex_color.strip().lstrip("#")
    if len(hex_color) != 6:
        raise ValueError("--color must be a 6-digit hex color")
    r = int(hex_color[0:2], 16) / 255
    g = int(hex_color[2:4], 16) / 255
    b = int(hex_color[4:6], 16) / 255
    h, _l, s = colorsys.rgb_to_hls(r, g, b)

    def hls_hex(lightness, saturation):
        rr, gg, bb = colorsys.hls_to_rgb(h, max(0, min(1, lightness)), max(0, min(1, saturation)))
        return "#%02X%02X%02X" % (int(rr * 255), int(gg * 255), int(bb * 255))

    sat = max(0.25, min(0.58, s))
    return {
        "card": hls_hex(0.96, sat * 0.65),
        "header": hls_hex(0.91, sat * 0.8),
        "line": hls_hex(0.76, sat * 0.7),
        "title": hls_hex(0.25, sat),
        "subtitle": hls_hex(0.39, sat * 0.75),
    }


import glob as _glob
_LINUX_DAVIDBD = (
    _glob.glob("/sessions/*/mnt/**/davidbd.ttf", recursive=True) +
    _glob.glob("/sessions/*/mnt/**/DAVIDBD.TTF", recursive=True) +
    _glob.glob("/sessions/*/mnt/**/david.ttf", recursive=True) +
    _glob.glob("/sessions/*/mnt/**/DAVID.TTF", recursive=True)
)
LINUX_FONTS = [
    *_LINUX_DAVIDBD,
    "/usr/share/fonts/truetype/culmus/davidbd.ttf",
    "/usr/share/fonts/truetype/culmus/david.ttf",
    "/usr/share/fonts/truetype/noto/NotoSansHebrew-Bold.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansHebrew-Bold.otf",
    "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def font_path(candidates):
    for candidate in list(candidates) + LINUX_FONTS:
        path = Path(candidate)
        if path.exists():
            return str(path)
    return None


def rtl(text):
    return str(text)[::-1]


def fit_font(ImageFont, font_file, text, start_size, max_width):
    if not font_file:
        return ImageFont.load_default()
    size = start_size
    while size > 24:
        font = ImageFont.truetype(font_file, size)
        from PIL import Image, ImageDraw

        dummy = Image.new("RGB", (10, 10))
        draw = ImageDraw.Draw(dummy)
        bbox = draw.textbbox((0, 0), rtl(text), font=font)
        if bbox[2] - bbox[0] <= max_width:
            return font
        size -= 2
    return ImageFont.truetype(font_file, size)


def draw_center(draw, canvas_w, text, font, y, fill):
    shaped = rtl(text)
    bbox = draw.textbbox((0, 0), shaped, font=font)
    x = (canvas_w - (bbox[2] - bbox[0])) / 2
    draw.text((x, y), shaped, font=font, fill=fill)


def make_note_pdf(day_text, subtitle, color, out_dir, slug):
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
    import fitz

    out_dir.mkdir(parents=True, exist_ok=True)
    palette = derive_palette(color)
    title_font_file = font_path([
        r"C:\Windows\Fonts\DAVIDB.TTF",
        r"C:\Windows\Fonts\davidbd.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ])
    subtitle_font_file = font_path([
        r"C:\Windows\Fonts\davidbd.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ])

    note_w, note_h = 380, 200
    margin = 22
    canvas_w, canvas_h = note_w + margin * 2, note_h + margin * 2
    img = Image.new("RGBA", (canvas_w, canvas_h), (0, 0, 0, 0))

    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_draw.rounded_rectangle(
        (margin + 14, margin + 16, margin + note_w + 14, margin + note_h + 16),
        radius=14,
        fill=(0, 0, 0, 54),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    img.alpha_composite(shadow)

    draw = ImageDraw.Draw(img)
    x1, y1 = margin, margin
    x2, y2 = x1 + note_w, y1 + note_h
    draw.rounded_rectangle((x1, y1, x2, y2), radius=14, fill=palette["card"], outline=palette["line"], width=2)
    draw.rectangle((x1, y1, x2, y1 + 58), fill=palette["header"])
    draw.line((x1, y1 + 58, x2, y1 + 58), fill=palette["line"], width=2)
    for hx in (x1 + 72, x2 - 72):
        draw.ellipse((hx - 9, y1 + 20, hx + 9, y1 + 38), fill="#fffdfa", outline=palette["line"], width=2)

    title_font = fit_font(ImageFont, title_font_file, day_text, 70, 340)
    subtitle_font = fit_font(ImageFont, subtitle_font_file, subtitle, 34, 320)
    draw_center(draw, canvas_w, day_text, title_font, y1 + 82, palette["title"])
    draw_center(draw, canvas_w, subtitle, subtitle_font, y1 + 152, palette["subtitle"])

    png_path = out_dir / f"day-note-{slug}.png"
    pdf_path = out_dir / f"day-note-{slug}.pdf"
    img.save(png_path)

    note_doc = fitz.open()
    page = note_doc.new_page(width=canvas_w, height=canvas_h)
    page.insert_image(fitz.Rect(0, 0, canvas_w, canvas_h), filename=str(png_path))
    note_doc.save(str(pdf_path), garbage=4, deflate=True)
    note_doc.close()
    return pdf_path


def button_rects(fitz, include_parsha=True):
    return toc_button_rects(fitz, include_parsha=include_parsha)


def existing_toc_uri_links(fitz, toc_page, include_parsha=True):
    rects = toc_button_rects(fitz, include_parsha=include_parsha)
    urls = {}
    for link in toc_page.get_links():
        if link.get("kind") != fitz.LINK_URI or not link.get("uri"):
            continue
        link_rect = link.get("from")
        if not link_rect:
            continue
        for key in ("whatsapp", "email", "archive"):
            if key in urls:
                continue
            if link_rect.intersects(rects[key]):
                urls[key] = link["uri"]
    return urls


def validate_targets(doc, final_targets):
    page_count = len(doc)
    bad = {key: target for key, target in final_targets.items() if target < 1 or target > page_count}
    if bad:
        raise RuntimeError(f"target page(s) out of range for {page_count} pages: {bad}")


def render_page(doc, page_1based, output_path, zoom=1.35):
    import fitz

    page = doc[page_1based - 1]
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
    pix.save(str(output_path))


def write_validation_previews(doc, final_targets, preview_dir, toc_page_number=DEFAULT_TOC_PAGE_NUMBER, include_parsha=True):
    import fitz
    from PIL import Image, ImageDraw

    preview_dir.mkdir(parents=True, exist_ok=True)
    zoom = 1.35

    toc_png = preview_dir / "toc-links-overlay.png"
    render_page(doc, toc_page_number, toc_png, zoom=zoom)
    img = Image.open(toc_png).convert("RGBA")
    draw = ImageDraw.Draw(img)
    for key, rect in button_rects(fitz, include_parsha=include_parsha).items():
        scaled = (rect.x0 * zoom, rect.y0 * zoom, rect.x1 * zoom, rect.y1 * zoom)
        draw.rectangle(scaled, outline=(198, 44, 44, 255), width=4)
        draw.text((scaled[0] + 6, scaled[1] + 6), f"{key}->{final_targets.get(key, 'ג€”')}", fill=(198, 44, 44, 255))
    img.save(toc_png)

    render_page(doc, final_targets["tikun"], preview_dir / "tikun-target.png", zoom=zoom)
    if final_targets["tikun"] > 1:
        render_page(doc, final_targets["tikun"] - 1, preview_dir / "tikun-previous-page.png", zoom=zoom)
    if final_targets.get("hashlamot"):
        render_page(doc, final_targets["hashlamot"], preview_dir / "hashlamot-target.png", zoom=zoom)
    render_page(doc, final_targets["thursday"], preview_dir / "thursday-note-target.png", zoom=zoom)
    (preview_dir / "targets.json").write_text(
        json.dumps({"page_count": len(doc), "targets": final_targets}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def add_notes_and_relink(args):
    require_deps()
    import fitz

    pdf_path = Path(args.pdf)
    analysis = json.loads(Path(args.analysis).read_text(encoding="utf-8"))
    include_parsha = has_parsha(analysis)
    toc_cfg = analysis_toc_config(analysis)
    if args.whatsapp_url:
        toc_cfg["whatsapp_url"] = args.whatsapp_url
    if args.email_url:
        toc_cfg["email_url"] = args.email_url
    if args.archive_url:
        toc_cfg["archive_url"] = args.archive_url
    require_booklet_metadata_confirmation(analysis)
    require_previous_week_answers(analysis)
    require_daf_yomi_previous_week_answer(analysis)
    require_daf_yomi_duplicate_amud_confirmation(analysis)
    if not pdf_path.exists():
        raise FileNotFoundError(pdf_path)
    output = Path(args.output) if args.output else unique_path(pdf_path.with_name(pdf_path.stem + "_with_day_notes.pdf"))
    notes_dir = Path(args.notes_dir) if args.notes_dir else output.parent / "day-notes"

    base_targets = calculate_base_targets(analysis, args.toc_page)
    doc = fitz.open(str(pdf_path))
    if len(doc) < base_targets["back_cover"]:
        raise RuntimeError(
            f"Base PDF has {len(doc)} pages but analysis-derived layout expects at least {base_targets['back_cover']}."
        )

    note_pdfs = {}
    for slug, day_text in DAYS:
        note_pdfs[slug] = make_note_pdf(day_text, args.note_text, args.color, notes_dir, slug)

    # Insert from the end so the original base target positions remain stable while inserting.
    for slug, day_text in reversed(DAYS):
        note_doc = fitz.open(str(note_pdfs[slug]))
        doc.insert_pdf(note_doc, start_at=base_targets["day_first"][day_text] - 1)
        note_doc.close()

    note_targets = {}
    for inserted_before, (slug, day_text) in enumerate(DAYS):
        note_targets[slug] = base_targets["day_first"][day_text] + inserted_before

    return_img = make_return_button_image(notes_dir / "return-to-toc.png", args.color)
    add_return_buttons_to_pages(fitz, doc, note_targets.values(), return_img, base_targets["toc"])

    final_targets = {
        **note_targets,
        "tikun": base_targets["tikun"] + len(DAYS),
    }
    if include_parsha:
        final_targets["parsha"] = base_targets["parsha"]
    if base_targets.get("hashlamot"):
        final_targets["hashlamot"] = base_targets["hashlamot"] + len(DAYS)
    validate_targets(doc, final_targets)

    toc = doc[base_targets["toc"] - 1]
    existing_urls = existing_toc_uri_links(fitz, toc, include_parsha=include_parsha)
    for key, cfg_key in (("whatsapp", "whatsapp_url"), ("email", "email_url"), ("archive", "archive_url")):
        if existing_urls.get(key) and not toc_cfg.get(cfg_key):
            toc_cfg[cfg_key] = existing_urls[key]
    for link in list(toc.get_links()):
        toc.delete_link(link)
    insert_toc_links(
        fitz,
        toc,
        final_targets,
        external_links_from_config(toc_cfg),
        include_parsha=include_parsha,
    )

    continuation_summary = add_continuation_links(fitz, doc, analysis, args.color, notes_dir, base_targets["toc"])

    if args.preview_dir:
        preview_dir = Path(args.preview_dir)
        write_validation_previews(
            doc,
            final_targets,
            preview_dir,
            base_targets["toc"],
            include_parsha=include_parsha,
        )
        (preview_dir / "continuation-links.json").write_text(
            json.dumps(continuation_summary, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    doc.save(str(output), garbage=4, deflate=True, deflate_images=True, deflate_fonts=True, clean=False, use_objstms=1)
    page_count = len(doc)
    doc.close()

    print(f"OUTPUT_PDF={output}")
    print(f"PAGE_COUNT={page_count}")
    print("TARGETS=" + json.dumps(final_targets, ensure_ascii=False, sort_keys=True))
    print(f"CONTINUATION_LINKS={len(continuation_summary['links'])}")
    print(f"NOTES_DIR={notes_dir}")


def main():
    parser = argparse.ArgumentParser(description="Add weekly day-note transition cards and rebuild TOC links.")
    parser.add_argument("--pdf", required=True, help="Base lefi-yamim PDF built by build_lefi_yamim.py.")
    parser.add_argument("--analysis", required=True, help="Analysis JSON used to build the base PDF.")
    parser.add_argument("--color", required=True, help="Weekly label/cover hex color, e.g. 6DCFF6.")
    parser.add_argument("--note-text", default=NOTE_SUBTITLE)
    parser.add_argument("--toc-page", type=int, default=DEFAULT_TOC_PAGE_NUMBER, help="1-based TOC page number in the base PDF. Default is 3 because page 2 is the pre-TOC page.")
    parser.add_argument("--notes-dir")
    parser.add_argument("--output")
    parser.add_argument("--preview-dir", help="Optional folder for rendered QA previews of rebuilt link targets.")
    parser.add_argument("--whatsapp-url")
    parser.add_argument("--email-url")
    parser.add_argument("--archive-url")
    args = parser.parse_args()
    try:
        add_notes_and_relink(args)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
