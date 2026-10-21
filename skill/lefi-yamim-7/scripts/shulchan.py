"""Shulchan Aruch study ends above the visual Be'er Hagolah boundary.

The original Skill 6 templates establish that boundary. Every black study
style above it is included, with future gray material excluded. Font subset
names and which style happens to be slightly larger do not define learning.
"""
from collections import defaultdict
import math

import fitz

from source_model import ROOT, spans, union, column_of, gray_backgrounds, sha256
from add_vcheck_boxes import render_page, load_boundary_templates, match_template, BOUNDARY_TOPICS

ZOOM = 2.2


def commentary_boundaries(page):
    if page.rotation:
        raise ValueError("Normalize Shulchan Aruch rotation before measuring")
    spec = BOUNDARY_TOPICS['שולחן ערוך']
    folder = ROOT / 'days/assets/vcheck-templates'
    templates = load_boundary_templates(folder, spec)
    _, gray = render_page(page, ZOOM)
    height, width = gray.shape
    landscape = page.rect.width > page.rect.height
    rois = [(0, width // 2), (width // 2, width)] if landscape else [(0, width)]
    result = []
    for index, (x0, x1) in enumerate(rois):
        # In portrait, try both original crops of the same caption.
        choices = [(index, templates[index])] if landscape else list(enumerate(templates))
        candidates = [(i, match_template(gray, template, x0, x1)) for i, template in choices]
        candidates = [(i, m) for i, m in candidates if m is not None]
        if not candidates:
            raise ValueError(f"No Be'er Hagolah boundary, page {page.number + 1}, half {index + 1}")
        template_index, match = max(candidates, key=lambda pair: pair[1]['score'])
        if match['score'] < spec['min_score']:
            raise ValueError(f"Weak Be'er Hagolah boundary, page {page.number + 1}, "
                             f"half {index + 1}: {match['score']:.3f}")
        cutoff = (match['y'] - spec['title_gap_px']) / ZOOM
        if not page.rect.height * .15 < cutoff < page.rect.height * .95:
            raise ValueError("Be'er Hagolah boundary is outside the study area")
        template_name = spec['left_template'] if template_index == 0 else spec['right_template']
        result.append({'x0': x0 / ZOOM, 'x1': min(x1 / ZOOM, page.rect.width),
                       'cutoff_y': cutoff, 'score': match['score'],
                       'template': template_name, 'template_sha256': sha256(folder / template_name)})
    return result


def measure_shulchan(page):
    boundaries = commentary_boundaries(page)
    gray = gray_backgrounds(page)
    items = [s for s in spans(page) if s['size'] >= 5]
    if not items:
        raise ValueError('No selectable Shulchan Aruch text; source review required')
    reference = union([s['rect'] for s in items])
    columns = 4 if page.rect.width > page.rect.height else 2
    learned, full = defaultdict(list), defaultdict(list)
    for item in items:
        rect = item['rect']
        col = column_of(rect, page.rect.width, columns)
        full[col].append(rect)
        center = (rect.tl + rect.br) / 2
        boundary = next((b for b in boundaries if b['x0'] <= center.x <= b['x1']), None)
        rgb = [(item['color'] >> shift) & 255 for shift in (16, 8, 0)]
        if (boundary is None or center.y >= boundary['cutoff_y'] or sum(rgb) / 3 > 125
                or any(bg.contains(center) for bg in gray)):
            continue
        learned[col].append(rect)
    regions = []
    for col, rects in sorted(learned.items()):
        full_rect = union(full[col])
        if columns == 4:
            full_rect.y0, full_rect.y1 = reference.y0, reference.y1
        regions.append({'column': col, 'rect': union(rects), 'full_rect': full_rect})
    return {'regions': regions, 'boundaries': boundaries, 'gray_regions': [list(r) for r in gray]}


def audit_shulchan_coverage(page, regions, checks=None):
    """Check original glyphs, independently of the planner's selected spans.

    Never infer expected columns from existing widgets or the progress map.
    This detects the former single-font result even when its map is consistent.
    """
    boundaries = commentary_boundaries(page)
    gray = gray_backgrounds(page)
    columns = 4 if page.rect.width > page.rect.height else 2
    expected, full = defaultdict(list), defaultdict(list)
    for block in page.get_text('rawdict')['blocks']:
        if block['type'] != 0:
            continue
        for line in block['lines']:
            for span in line['spans']:
                if span['size'] < 5 or 'fbrimona' in span['font'].lower() or span['color'] == 0xffffff:
                    continue
                span_rect = fitz.Rect(span['bbox'])
                if span_rect.y0 < page.rect.height * .08 or span_rect.y1 > page.rect.height * .97:
                    continue
                # Assign a glyph to its original text column, including symbols
                # whose position is near a gutter, using the enclosing span.
                col = column_of(span_rect, page.rect.width, columns)
                rgb = [(span['color'] >> shift) & 255 for shift in (16, 8, 0)]
                for char in span['chars']:
                    if not char['c'].strip():
                        continue
                    rect = fitz.Rect(char['bbox'])
                    full[col].append(rect)
                    center = (rect.tl + rect.br) / 2
                    boundary = next((b for b in boundaries if b['x0'] <= center.x <= b['x1']), None)
                    if (boundary and center.y < boundary['cutoff_y'] and sum(rgb) / 3 <= 125
                            and not any(bg.contains(center) for bg in gray)):
                        expected[col].append(rect)
    actual = {r['column']: r for r in regions}
    if len(actual) != len(regions) or actual.keys() != expected.keys():
        raise ValueError(f"Shulchan Aruch coverage: expected active columns {sorted(expected)}, "
                         f"got {sorted(actual)}, page {page.number + 1}")
    reference = union([r for rects in full.values() for r in rects])
    report = []
    for col, glyphs in sorted(expected.items()):
        region = fitz.Rect(actual[col]['rect'])
        tolerant = region + (-.1, -.1, .1, .1)
        if any(not tolerant.contains(rect) for rect in glyphs):
            raise ValueError(f"Shulchan Aruch coverage omits study glyphs, page {page.number + 1}, column {col}")
        expected_rect = union(glyphs)
        if abs(region.y0 - expected_rect.y0) > .1 or abs(region.y1 - expected_rect.y1) > .1:
            raise ValueError(f"Shulchan Aruch region extends beyond study text, column {col}")
        full_rect = reference if columns == 4 else union(full[col])
        height = full_rect.height
        count = min(3, math.ceil(3 * expected_rect.height / height - 1e-9)) if columns == 4 else 3
        if checks is not None:
            actual_count = sum(item['column'] == col for item in checks)
            if actual_count != count:
                raise ValueError(f"Shulchan Aruch checkbox count: column {col}, expected {count}, got {actual_count}")
        report.append({'column': col, 'learning_rect': list(expected_rect),
                       'full_height': height, 'expected_checkboxes': count, 'glyphs_checked': len(glyphs)})
    return {'passed': True, 'columns': report, 'boundaries': boundaries,
            'gray_regions': [list(r) for r in gray]}
