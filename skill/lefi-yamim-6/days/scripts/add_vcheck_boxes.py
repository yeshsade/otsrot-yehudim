#!/usr/bin/env python3
"""Add persistent V-check PDF form fields to a lefi-yamim test PDF.

This script is intentionally conservative. If a topic cannot be handled
consistently, it skips the whole topic unless --strict is passed.
"""

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from add_day_notes import (  # noqa: E402
    DAYS,
    DEFAULT_TOC_PAGE_NUMBER,
    context_page_set,
    final_day_content_page,
)


TWO_COLUMN_TOPICS = {
    'ליקוטי מוהר"ן': 'likutey_moharan',
    'ליקוטי הלכות': 'likutey_halakhot',
}

FOUR_FULL_TOPICS = {
    'תהילים': 'tehillim',
    'ספרי רבינו על פי הסדר': 'sifrei_rabeinu',
    'שמות הצדיקים / ספר המידות': 'shemot_tzadikim',
    'ליקוטי תפילות': 'likutey_tefilot',
}

BOUNDARY_TOPICS = {
    'זוהר': {
        'slug': 'zohar',
        'left_template': 'zohar_left.png',
        'right_template': 'zohar_right.png',
        'min_score': 0.62,
        'title_gap_px': 12,
    },
    'משנה': {
        'slug': 'mishnah',
        'left_template': 'mishnah_left.png',
        'right_template': 'mishnah_right.png',
        'min_score': 0.45,
        'title_gap_px': 12,
    },
    'שולחן ערוך': {
        'slug': 'shulchan_aruch',
        'left_template': 'shulchan_aruch_left.png',
        'right_template': 'shulchan_aruch_right.png',
        'min_score': 0.55,
        'title_gap_px': 10,
        'black_text_only': True,
    },
}

NO_CHECKBOX_TOPICS = {
    'הדף היומי',
    'פרשת השבוע',
    'נביאים וכתובים',
    'משנה ברורה',
    'תיקון הכללי',
    'השלמות',
}

TWO_COLUMN_ENDPAGE_TOPICS = {'תהילים', 'שולחן ערוך'}
MAX_DARK_OVERLAP = 0.02
MIN_WIDGET_GAP_PT = 0.75


@dataclass
class CandidateBox:
    topic: str
    slug: str
    page_number: int
    column: int
    index: int
    visual_rect: tuple
    pdf_rect: object
    field_name: str


@dataclass
class TopicPlan:
    topic: str
    slug: str
    pages: list[int]
    candidates: list[CandidateBox] = field(default_factory=list)
    failures: list[str] = field(default_factory=list)
    kind: str = ''


def require_deps():
    missing = []
    try:
        import fitz  # noqa: F401
    except ImportError:
        missing.append('PyMuPDF')
    try:
        import cv2  # noqa: F401
    except ImportError:
        missing.append('opencv-python')
    try:
        import numpy  # noqa: F401
    except ImportError:
        missing.append('numpy')
    try:
        from PIL import Image  # noqa: F401
    except ImportError:
        missing.append('Pillow')
    if missing:
        raise SystemExit('Missing required Python package(s): ' + ', '.join(missing))


def topic_slug(topic):
    if topic in TWO_COLUMN_TOPICS:
        return TWO_COLUMN_TOPICS[topic]
    if topic in FOUR_FULL_TOPICS:
        return FOUR_FULL_TOPICS[topic]
    if topic in BOUNDARY_TOPICS:
        return BOUNDARY_TOPICS[topic]['slug']
    return None


def topic_kind(topic):
    if topic in TWO_COLUMN_TOPICS:
        return 'two-column'
    if topic in FOUR_FULL_TOPICS:
        return 'four-full'
    if topic in BOUNDARY_TOPICS:
        return 'four-boundary'
    if topic in NO_CHECKBOX_TOPICS:
        return 'none'
    return 'unknown'


def selected_topic_allowed(topic, selected):
    if not selected:
        return True
    slug = topic_slug(topic)
    return topic in selected or (slug and slug in selected)


def load_analysis(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def final_topic_entries(analysis):
    """Map source-topic pages to final pages after weekday-note insertion."""
    blank_pages = set(analysis['blank_pages'])
    context_pages = context_page_set(analysis)
    topic_ranges = {name: tuple(bounds) for name, bounds in analysis['topic_ranges'].items()}
    parsha_start, parsha_end = topic_ranges['פרשת השבוע']

    next_page = DEFAULT_TOC_PAGE_NUMBER + 1
    for original_page in range(parsha_start, parsha_end + 1):
        if original_page not in blank_pages:
            next_page += 1

    result = {}
    for day_index, (_day_slug, day_text) in enumerate(DAYS):
        for original_page in analysis['day_pages'].get(day_text, []):
            if original_page in blank_pages or original_page in context_pages:
                continue
            topic = None
            for topic_name, (topic_start, topic_end) in topic_ranges.items():
                if topic_start <= original_page <= topic_end:
                    topic = topic_name
                    break
            if topic:
                result.setdefault(topic, []).append(
                    (final_day_content_page(next_page, day_index), original_page)
                )
            next_page += 1
    return result


def final_topic_pages(analysis):
    return {
        topic: sorted(final_page for final_page, _original_page in entries)
        for topic, entries in final_topic_entries(analysis).items()
    }


def link_count(doc):
    return sum(len(page.get_links()) for page in doc)


def widget_count(doc):
    total = 0
    for page in doc:
        widgets = page.widgets()
        if widgets:
            total += sum(1 for _ in widgets)
    return total


def render_page(page, zoom):
    import fitz
    import numpy as np
    import cv2

    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
    arr = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)
    if pix.n > 3:
        arr = arr[:, :, :3]
    gray = cv2.cvtColor(arr, cv2.COLOR_RGB2GRAY)
    return arr, gray


def smooth(values, window):
    import numpy as np

    if window <= 1:
        return values
    kernel = np.ones(window, dtype=float) / float(window)
    return np.convolve(values, kernel, mode='same')


def text_bbox(gray, x0, x1, *, y_calc_start=0.18, y_calc_end=0.92, dark_threshold=170):
    import numpy as np

    h, w = gray.shape
    x0 = max(0, int(x0))
    x1 = min(w, int(x1))
    if x1 - x0 < 40:
        return None
    y0 = int(h * y_calc_start)
    y1 = int(h * y_calc_end)
    mask = (gray < dark_threshold).astype('uint8')
    calc = mask[y0:y1, x0:x1]
    ys, xs = np.where(calc > 0)
    if len(xs) < 30:
        return None
    tx0 = x0 + int(np.percentile(xs, 3))
    tx1 = x0 + int(np.percentile(xs, 97))
    if tx1 <= tx0:
        return None
    col = mask[:, tx0:tx1]
    row_density = smooth(col.mean(axis=1), 11)
    rows = np.where((np.arange(h) > int(h * 0.08)) & (np.arange(h) < int(h * 0.94)) & (row_density > 0.004))[0]
    if len(rows) == 0:
        return None
    return (tx0, int(rows.min()), tx1, int(rows.max()))


def text_bbox_above(gray, x0, x1, y_stop, *, dark_threshold=170, black_text_only=False):
    import numpy as np

    h, w = gray.shape
    x0 = max(0, int(x0))
    x1 = min(w, int(x1))
    y_stop = max(1, min(h - 1, int(y_stop)))
    threshold = 125 if black_text_only else dark_threshold
    mask = (gray < threshold).astype('uint8')
    calc_y0 = int(h * 0.10)
    calc_y1 = max(calc_y0 + 10, min(y_stop, int(h * 0.92)))
    calc = mask[calc_y0:calc_y1, x0:x1]
    ys, xs = np.where(calc > 0)
    if len(xs) < 20:
        return None
    tx0 = x0 + int(np.percentile(xs, 3))
    tx1 = x0 + int(np.percentile(xs, 97))
    row_density = smooth(mask[:, tx0:tx1].mean(axis=1), 11)
    rows = np.where((np.arange(h) > int(h * 0.08)) & (np.arange(h) < y_stop) & (row_density > 0.004))[0]
    if len(rows) == 0:
        return None
    return (tx0, int(rows.min()), tx1, int(rows.max()))


def match_template(gray, template, x0, x1):
    import cv2

    roi = gray[:, int(x0):int(x1)]
    th, tw = template.shape[:2]
    if roi.shape[0] < th or roi.shape[1] < tw:
        return None
    result = cv2.matchTemplate(roi, template, cv2.TM_CCOEFF_NORMED)
    _min_v, max_v, _min_loc, max_loc = cv2.minMaxLoc(result)
    return {
        'score': float(max_v),
        'x': int(x0) + int(max_loc[0]),
        'y': int(max_loc[1]),
        'w': int(tw),
        'h': int(th),
    }


def visual_rect_to_pdf(page, fitz, rect, zoom):
    pdf_rect = fitz.Rect(*(value / float(zoom) for value in rect))
    if page.rotation in {90, 270}:
        pdf_rect = pdf_rect * page.derotation_matrix
    return pdf_rect


def rect_pixels(rect):
    x0, y0, x1, y1 = rect
    return (int(math.floor(x0)), int(math.floor(y0)), int(math.ceil(x1)), int(math.ceil(y1)))


def dark_overlap(gray, rect, dark_threshold=170):
    import numpy as np

    h, w = gray.shape
    x0, y0, x1, y1 = rect_pixels(rect)
    x0 = max(0, min(w, x0))
    x1 = max(0, min(w, x1))
    y0 = max(0, min(h, y0))
    y1 = max(0, min(h, y1))
    if x1 <= x0 or y1 <= y0:
        return 1.0
    crop = gray[y0:y1, x0:x1]
    return float((crop < dark_threshold).mean())


def page_link_rects(page):
    rects = []
    for link in page.get_links():
        rect = link.get('from')
        if rect is not None:
            # Links are reported in the rotated page view, while widget.rect
            # and planned PDF rectangles use unrotated PDF coordinates.
            if page.rotation:
                rect = rect * page.derotation_matrix
            rects.append(rect)
    return rects


def expanded_rect(fitz, rect, gap):
    return fitz.Rect(rect.x0 - gap, rect.y0 - gap, rect.x1 + gap, rect.y1 + gap)


def validate_candidate(
    page,
    fitz,
    gray,
    visual_rect,
    zoom,
    *,
    dark_threshold=170,
    reserved_pdf_rects=(),
    min_widget_gap_pt=MIN_WIDGET_GAP_PT,
):
    pdf_rect = visual_rect_to_pdf(page, fitz, visual_rect, zoom)
    if pdf_rect.is_empty or pdf_rect.is_infinite:
        return None, 'invalid widget rectangle'
    if dark_overlap(gray, visual_rect, dark_threshold=dark_threshold) > MAX_DARK_OVERLAP:
        return None, 'checkbox would touch dark pixels/text'
    for link_rect in page_link_rects(page):
        if pdf_rect.intersects(link_rect):
            return None, 'checkbox would intersect an existing link/button'
    protected_rect = expanded_rect(fitz, pdf_rect, float(min_widget_gap_pt))
    if any(protected_rect.intersects(rect) for rect in reserved_pdf_rects):
        return None, 'checkbox would intersect or crowd another checkbox'
    return pdf_rect, None


def find_clean_candidate(
    page,
    fitz,
    gray,
    visual_rect,
    zoom,
    *,
    dark_threshold=170,
    reserved_pdf_rects=(),
):
    """Return a nearby clean rectangle, preferring the exact requested spot."""
    h, w = gray.shape
    x0, y0, x1, y1 = visual_rect
    box_w = x1 - x0
    box_h = y1 - y0
    y_shifts = [0, -8, -16, -24, -32, -44, -56, 8, 16, 24, 32, 44, 56]
    x_shifts = [0, -8, 8, -16, 16, -24, 24, -32, 32, -44, 44, -56, 56, -72, 72]
    last_err = None
    for dy in y_shifts:
        for dx in x_shifts:
            nx0 = max(2, min(w - box_w - 2, x0 + dx))
            ny0 = max(2, min(h - box_h - 2, y0 + dy))
            rect = (nx0, ny0, nx0 + box_w, ny0 + box_h)
            pdf_rect, err = validate_candidate(
                page,
                fitz,
                gray,
                rect,
                zoom,
                dark_threshold=dark_threshold,
                reserved_pdf_rects=reserved_pdf_rects,
            )
            if not err:
                return pdf_rect, None, rect
            last_err = err
    return None, last_err or 'no clean nearby checkbox position', visual_rect


def y_positions(top, bottom, size):
    height = bottom - top
    if height < size * 5:
        return None
    return [
        int(top + height * 0.33),
        int(top + height * 0.66),
        int(max(top + size, bottom - size)),
    ]


def make_box_rects_for_lanes(lanes, top, bottom, size):
    ys = y_positions(top, bottom, size)
    if not ys:
        return []
    rects = []
    for lane_x in lanes:
        for y in ys:
            rects.append((lane_x, y, lane_x + size, y + size))
    return rects


def plan_two_column_topic(doc, fitz, topic, slug, pages, zoom, size_px, gap_px):
    plan = TopicPlan(topic=topic, slug=slug, pages=pages, kind='two-column')
    for page_number in pages:
        page = doc[page_number - 1]
        _rgb, gray = render_page(page, zoom)
        h, w = gray.shape
        bbox = text_bbox(gray, 0, w)
        if not bbox:
            plan.failures.append(f'page {page_number}: no reliable text bbox')
            continue
        tx0, ty0, tx1, ty1 = bbox
        lanes = [max(2, tx0 - gap_px - size_px), min(w - size_px - 2, tx1 + gap_px)]
        rects = make_box_rects_for_lanes(lanes, ty0, ty1, size_px)
        if len(rects) != 6:
            plan.failures.append(f'page {page_number}: could not place 6 boxes')
            continue
        reserved_pdf_rects = [widget.rect for widget in page.widgets() or []]
        for idx, rect in enumerate(rects, 1):
            pdf_rect, err, clean_rect = find_clean_candidate(
                page, fitz, gray, rect, zoom, reserved_pdf_rects=reserved_pdf_rects
            )
            if err:
                plan.failures.append(f'page {page_number}: {err}')
                continue
            column = 1 if idx <= 3 else 2
            box_index = ((idx - 1) % 3) + 1
            field_name = f'vcheck_{slug}_p{page_number:03d}_c{column}_{box_index}'
            plan.candidates.append(CandidateBox(topic, slug, page_number, column, box_index, clean_rect, pdf_rect, field_name))
            reserved_pdf_rects.append(pdf_rect)
    return plan


def imread_gray(path):
    import cv2
    import numpy as np

    path = Path(path)
    data = np.fromfile(str(path), dtype=np.uint8)
    if data.size == 0:
        return None
    return cv2.imdecode(data, cv2.IMREAD_GRAYSCALE)


def load_boundary_templates(templates_dir, spec):
    left = imread_gray(templates_dir / spec['left_template'])
    right = imread_gray(templates_dir / spec['right_template'])
    if left is None or right is None:
        raise FileNotFoundError(f"missing template(s) for {spec['slug']} in {templates_dir}")
    return left, right


def plan_four_topic(doc, fitz, topic, slug, pages, zoom, size_px, gap_px, *, boundary_spec=None, templates_dir=None, allow_single_half_pages=None):
    plan = TopicPlan(topic=topic, slug=slug, pages=pages, kind='four-boundary' if boundary_spec else 'four-full')
    allow_single_half_pages = set(allow_single_half_pages or ())
    templates = None
    if boundary_spec:
        templates = load_boundary_templates(templates_dir, boundary_spec)
    for page_number in pages:
        page = doc[page_number - 1]
        allow_single_half = page_number in allow_single_half_pages and boundary_spec is None
        _rgb, gray = render_page(page, zoom)
        h, w = gray.shape
        halves = [(0, w // 2), (w // 2, w)]
        page_rects = []
        for half_index, (hx0, hx1) in enumerate(halves):
            bbox = None
            if boundary_spec:
                template = templates[half_index]
                match = match_template(gray, template, hx0, hx1)
                if not match or match['score'] < boundary_spec['min_score']:
                    score = 'none' if not match else f"{match['score']:.3f}"
                    plan.failures.append(f'page {page_number}: weak boundary match half {half_index + 1} score={score}')
                    continue
                y_stop = max(1, match['y'] - int(boundary_spec.get('title_gap_px', 10)))
                bbox = text_bbox_above(
                    gray,
                    hx0,
                    hx1,
                    y_stop,
                    black_text_only=bool(boundary_spec.get('black_text_only')),
                )
            else:
                bbox = text_bbox(gray, hx0, hx1)
            if not bbox:
                if not allow_single_half:
                    plan.failures.append(f'page {page_number}: no reliable text bbox half {half_index + 1}')
                continue
            tx0, ty0, tx1, ty1 = bbox
            lanes = [max(2, tx0 - gap_px - size_px), min(w - size_px - 2, tx1 + gap_px)]
            page_rects.extend((half_index, rect) for rect in make_box_rects_for_lanes(lanes, ty0, ty1, size_px))
        expected_page_boxes = {6, 12} if allow_single_half else {12}
        if len(page_rects) not in expected_page_boxes:
            expected_label = '/'.join(str(value) for value in sorted(expected_page_boxes))
            plan.failures.append(f'page {page_number}: expected {expected_label} boxes, got {len(page_rects)}')
            continue
        reserved_pdf_rects = [widget.rect for widget in page.widgets() or []]
        for idx, (_half_index, rect) in enumerate(page_rects, 1):
            pdf_rect, err, clean_rect = find_clean_candidate(
                page, fitz, gray, rect, zoom, reserved_pdf_rects=reserved_pdf_rects
            )
            if err:
                plan.failures.append(f'page {page_number}: {err}')
                continue
            column = ((idx - 1) // 3) + 1
            box_index = ((idx - 1) % 3) + 1
            field_name = f'vcheck_{slug}_p{page_number:03d}_c{column}_{box_index}'
            plan.candidates.append(CandidateBox(topic, slug, page_number, column, box_index, clean_rect, pdf_rect, field_name))
            reserved_pdf_rects.append(pdf_rect)
    return plan


def make_checkbox_appearances(doc):
    def form(stream):
        xref = doc.get_new_xref()
        doc.update_object(
            xref,
            '<</Type/XObject/Subtype/Form/FormType 1/BBox[0 0 10 10]/Resources<<>>>>',
        )
        doc.update_stream(xref, stream.encode('ascii'))
        return xref

    box = 'q 1 1 1 rg 0 0 10 10 re f 0 0 0 RG .75 w .4 .4 9.2 9.2 re S '
    normal_v = '1.1 w 1 J 1 j 2.5 7.5 m 5 2.5 l 7.5 7.5 l S '
    rotate_270_v = '1.1 w 1 J 1 j 7.5 7.5 m 2.5 5 l 7.5 2.5 l S '
    rotate_90_v = '1.1 w 1 J 1 j 2.5 7.5 m 7.5 5 l 2.5 2.5 l S '
    return {
        'off': form(box + 'Q'),
        0: form(box + normal_v + 'Q'),
        90: form(box + rotate_90_v + 'Q'),
        270: form(box + rotate_270_v + 'Q'),
    }


def add_checkbox(page, fitz, candidate, appearances):
    before = {item[0] for item in page.annot_xrefs()}
    widget = fitz.Widget()
    widget.field_name = candidate.field_name
    widget.field_type = fitz.PDF_WIDGET_TYPE_CHECKBOX
    widget.rect = candidate.pdf_rect
    widget.field_value = 'Off'
    widget.border_width = 0
    page.add_widget(widget)
    widget.update()

    new_xrefs = [item[0] for item in page.annot_xrefs() if item[0] not in before]
    if not new_xrefs:
        raise RuntimeError(f'could not locate new widget xref for {candidate.field_name}')
    widget_xref = new_xrefs[-1]
    rotation = page.rotation if page.rotation in {90, 270} else 0
    doc = page.parent
    doc.xref_set_key(
        widget_xref,
        'AP',
        f'<</N<</Off {appearances["off"]} 0 R/Yes {appearances[rotation]} 0 R>>>>',
    )
    doc.xref_set_key(widget_xref, 'AS', '/Off')
    doc.xref_set_key(widget_xref, 'V', '/Off')
    doc.xref_set_key(widget_xref, 'BS', '<</W 0>>')


def canonical_link(link):
    rect = link.get('from')
    point = link.get('to')
    return (
        link.get('kind'),
        None if rect is None else tuple(round(float(value), 3) for value in (rect.x0, rect.y0, rect.x1, rect.y1)),
        link.get('page'),
        None if point is None else tuple(round(float(value), 3) for value in (point.x, point.y)),
        link.get('uri'),
        link.get('file'),
        link.get('nameddest'),
    )


def navigation_signature(doc):
    return [
        sorted((canonical_link(link) for link in page.get_links()), key=repr)
        for page in doc
    ]


def invalid_internal_links(doc, fitz):
    failures = []
    for page_number, page in enumerate(doc, 1):
        for link in page.get_links():
            if link.get('kind') == fitz.LINK_GOTO:
                target = link.get('page', -1)
                if not 0 <= target < doc.page_count:
                    failures.append((page_number, target))
    return failures


def widget_link_intersections(doc):
    failures = []
    for page_number, page in enumerate(doc, 1):
        links = page_link_rects(page)
        for widget in page.widgets() or []:
            if any(widget.rect.intersects(rect) for rect in links):
                failures.append((page_number, widget.field_name))
    return failures


def widget_widget_intersections(doc):
    failures = []
    for page_number, page in enumerate(doc, 1):
        widgets = list(page.widgets() or [])
        for index, first in enumerate(widgets):
            for second in widgets[index + 1:]:
                intersection = first.rect & second.rect
                if not intersection.is_empty and intersection.get_area() > 0:
                    failures.append(
                        (page_number, first.field_name, second.field_name, intersection.get_area())
                    )
    return failures


def planned_candidate_intersections(plans):
    by_page = {}
    for plan in plans:
        for candidate in plan.candidates:
            by_page.setdefault(candidate.page_number, []).append(candidate)
    failures = []
    for page_number, candidates in by_page.items():
        for index, first in enumerate(candidates):
            for second in candidates[index + 1:]:
                intersection = first.pdf_rect & second.pdf_rect
                if not intersection.is_empty and intersection.get_area() > 0:
                    failures.append(
                        (page_number, first.field_name, second.field_name, intersection.get_area())
                    )
    return failures


def write_previews(doc, pages_by_topic, preview_dir, zoom=1.35):
    preview_dir.mkdir(parents=True, exist_ok=True)
    rendered = []
    for topic, pages in pages_by_topic.items():
        if not pages:
            continue
        topic_slug_value = topic_slug(topic) or safe_slug(topic)
        sample_pages = pages if topic in BOUNDARY_TOPICS else pages[:1]
        for page_number in sample_pages:
            out = preview_dir / f'vcheck_{topic_slug_value}_p{page_number:03d}.png'
            pix = doc[page_number - 1].get_pixmap(matrix=__import__('fitz').Matrix(zoom, zoom), alpha=False)
            pix.save(str(out))
            rendered.append(str(out))
    return rendered


def safe_slug(text):
    text = re.sub(r'\W+', '_', text, flags=re.UNICODE).strip('_')
    return text or 'topic'


def run(args):
    require_deps()
    import fitz

    pdf_path = Path(args.pdf)
    analysis = load_analysis(args.analysis)
    output = Path(args.output) if args.output else pdf_path.with_name(pdf_path.stem + '_vcheck_test.pdf')
    preview_dir = Path(args.preview_dir) if args.preview_dir else output.with_suffix('').with_name(output.stem + '_qa')
    templates_dir = Path(args.templates_dir) if args.templates_dir else Path(__file__).resolve().parents[1] / 'assets' / 'vcheck-templates'
    selected = set(args.topics or [])

    if output.resolve() == pdf_path.resolve():
        raise RuntimeError('output must be a different file; never modify the input PDF in place')

    doc = fitz.open(str(pdf_path))
    before_links = link_count(doc)
    before_widgets = widget_count(doc)
    before_navigation = navigation_signature(doc)
    entries_by_topic = final_topic_entries(analysis)
    rotated_original = set(analysis.get('rotated_original', []))

    all_plans = []
    skipped = {}
    included_pages = {}
    zoom = float(args.zoom)
    size_px = int(round(float(args.box_size_pt) * zoom))
    gap_px = int(round(float(args.gap_pt) * zoom))

    for topic, entries in sorted(
        entries_by_topic.items(),
        key=lambda item: min((entry[0] for entry in item[1]), default=999999),
    ):
        if not selected_topic_allowed(topic, selected):
            continue
        kind = topic_kind(topic)
        slug = topic_slug(topic)
        pages = sorted(final_page for final_page, _original_page in entries)
        if kind in {'none', 'unknown'} or not slug:
            skipped[topic] = f'skipped kind={kind}'
            continue
        if not pages or max(pages) > len(doc):
            skipped[topic] = 'analysis-derived page is outside final PDF'
            continue

        groups = []
        if kind == 'two-column':
            groups.append(('two-column', pages))
        else:
            four_pages = []
            two_pages = []
            for final_page, original_page in entries:
                if topic in TWO_COLUMN_ENDPAGE_TOPICS and original_page not in rotated_original:
                    two_pages.append(final_page)
                else:
                    four_pages.append(final_page)
            if four_pages:
                groups.append(('four-column', sorted(four_pages)))
            if two_pages:
                groups.append(('two-column', sorted(two_pages)))

        topic_plans = []
        topic_failures = []
        for layout, layout_pages in groups:
            if layout == 'two-column':
                plan = plan_two_column_topic(
                    doc, fitz, topic, slug, layout_pages, zoom, size_px, gap_px
                )
                expected = 6 * len(layout_pages)
            elif kind == 'four-boundary':
                plan = plan_four_topic(
                    doc,
                    fitz,
                    topic,
                    slug,
                    layout_pages,
                    zoom,
                    size_px,
                    gap_px,
                    boundary_spec=BOUNDARY_TOPICS[topic],
                    templates_dir=templates_dir,
                )
                expected = 12 * len(layout_pages)
            else:
                allow_single_half_pages = (
                    {max(layout_pages)} if topic == 'תהילים' and layout_pages else set()
                )
                plan = plan_four_topic(
                    doc,
                    fitz,
                    topic,
                    slug,
                    layout_pages,
                    zoom,
                    size_px,
                    gap_px,
                    allow_single_half_pages=allow_single_half_pages,
                )
                if allow_single_half_pages:
                    final_page = max(layout_pages)
                    final_page_boxes = sum(
                        1 for candidate in plan.candidates if candidate.page_number == final_page
                    )
                    expected = 12 * (len(layout_pages) - 1) + final_page_boxes
                else:
                    expected = 12 * len(layout_pages)

            if plan.failures or len(plan.candidates) != expected:
                reason = '; '.join(plan.failures[:8])
                if len(plan.failures) > 8:
                    reason += f'; plus {len(plan.failures) - 8} more'
                topic_failures.append(
                    f'{layout}: {reason or f"expected {expected}, got {len(plan.candidates)}"}'
                )
            topic_plans.append(plan)

        if topic_failures:
            reason = ' | '.join(topic_failures)
            if args.strict:
                raise RuntimeError(f'topic {topic} failed: {reason}')
            skipped[topic] = reason
            continue

        all_plans.extend(topic_plans)
        included_pages[topic] = pages

    planned_widget_conflicts = planned_candidate_intersections(all_plans)
    if planned_widget_conflicts:
        doc.close()
        raise RuntimeError(
            f'planned checkboxes intersect each other: {planned_widget_conflicts[:8]}'
        )

    appearances = make_checkbox_appearances(doc)
    added = 0
    for plan in all_plans:
        for candidate in plan.candidates:
            page = doc[candidate.page_number - 1]
            add_checkbox(page, fitz, candidate, appearances)
            added += 1

    if added == 0:
        raise RuntimeError('no checkboxes were added; all candidate topics were skipped or unavailable')

    output.parent.mkdir(parents=True, exist_ok=True)
    doc.save(
        str(output),
        garbage=0,
        deflate=True,
        deflate_images=True,
        deflate_fonts=True,
        clean=False,
        use_objstms=1,
    )
    doc.close()

    out_doc = fitz.open(str(output))
    after_links = link_count(out_doc)
    after_widgets = widget_count(out_doc)
    after_navigation = navigation_signature(out_doc)
    invalid_targets = invalid_internal_links(out_doc, fitz)
    widget_link_conflicts = widget_link_intersections(out_doc)
    widget_widget_conflicts = widget_widget_intersections(out_doc)
    navigation_identical = before_navigation == after_navigation
    if not navigation_identical:
        out_doc.close()
        raise RuntimeError('navigation changed after adding widgets')
    if invalid_targets:
        out_doc.close()
        raise RuntimeError(f'invalid internal navigation targets: {invalid_targets[:8]}')
    if widget_link_conflicts:
        out_doc.close()
        raise RuntimeError(f'checkboxes intersect navigation links: {widget_link_conflicts[:8]}')
    if widget_widget_conflicts:
        out_doc.close()
        raise RuntimeError(f'checkboxes intersect each other: {widget_widget_conflicts[:8]}')

    previews = write_previews(out_doc, included_pages, preview_dir)
    page_count = len(out_doc)
    out_doc.close()

    included_topics = {}
    for topic, pages in included_pages.items():
        included_topics[topic] = {
            'pages': pages,
            'checkboxes': sum(
                len(plan.candidates) for plan in all_plans if plan.topic == topic
            ),
        }

    summary = {
        'input_pdf': str(pdf_path),
        'output_pdf': str(output),
        'analysis_json': str(args.analysis),
        'page_count': page_count,
        'links_before': before_links,
        'links_after': after_links,
        'navigation_identical': navigation_identical,
        'all_internal_targets_valid': not invalid_targets,
        'no_checkbox_covers_link': not widget_link_conflicts,
        'no_checkbox_overlaps': not widget_widget_conflicts,
        'widgets_before': before_widgets,
        'widgets_after': after_widgets,
        'checkboxes_added': added,
        'included_topics': included_topics,
        'skipped_topics': skipped,
        'preview_dir': str(preview_dir),
        'previews': previews,
    }
    summary_path = preview_dir / 'vcheck-summary.json'
    preview_dir.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')

    print(f'OUTPUT_PDF={output}')
    print(f'PAGE_COUNT={summary["page_count"]}')
    print(f'CHECKBOXES_ADDED={added}')
    print(f'LINKS_BEFORE={before_links}')
    print(f'LINKS_AFTER={after_links}')
    print(f'NAVIGATION_IDENTICAL={navigation_identical}')
    print(f'NO_CHECKBOX_OVERLAPS={not widget_widget_conflicts}')
    print(f'WIDGETS_BEFORE={before_widgets}')
    print(f'WIDGETS_AFTER={after_widgets}')
    print(f'PREVIEW_DIR={preview_dir}')
    print(f'SUMMARY_JSON={summary_path}')
    if skipped:
        print('SKIPPED_TOPICS=' + json.dumps(skipped, ensure_ascii=False, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description='Add persistent V-check PDF form widgets to a lefi-yamim test PDF.')
    parser.add_argument('--pdf', required=True, help='Final lefi-yamim PDF after add_day_notes.py.')
    parser.add_argument('--analysis', required=True, help='Analysis JSON used for the build.')
    parser.add_argument('--output', help='Output test PDF. Defaults to *_vcheck_test.pdf next to input.')
    parser.add_argument('--preview-dir', help='Directory for rendered QA previews and summary JSON.')
    parser.add_argument('--templates-dir', help='Directory containing boundary templates for zohar/mishnah/shulchan_aruch.')
    parser.add_argument('--topics', nargs='*', help='Optional topic names or slugs to include for focused testing.')
    parser.add_argument('--strict', action='store_true', help='Fail instead of skipping a topic when placement is unsafe.')
    parser.add_argument('--zoom', type=float, default=2.2, help='Render zoom used for detection. Keep 2.2 unless recalibrating.')
    parser.add_argument('--box-size-pt', type=float, default=10.0, help='Checkbox size in PDF points.')
    parser.add_argument('--gap-pt', type=float, default=14.0, help='Safety gap from detected text in PDF points.')
    args = parser.parse_args()
    try:
        run(args)
    except Exception as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
