#!/usr/bin/env python3
"""Add approved persistent V checkboxes to a topics-based booklet."""

import argparse
import json
import sys
from pathlib import Path

import fitz

SCRIPT_DIR = Path(__file__).resolve().parent
TOPICS_ROOT = SCRIPT_DIR.parent
SKILL_ROOT = TOPICS_ROOT.parent
DAYS_SCRIPTS = SKILL_ROOT / 'days' / 'scripts'
if str(DAYS_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(DAYS_SCRIPTS))
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import add_vcheck_boxes as vb  # noqa: E402
import build_topics_booklet as tb  # noqa: E402


def load_analysis(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def source_to_final_map(source_page_count, blank_pages):
    mapping = {}
    final_page = 3  # cover=1, TOC=2
    for original_page in range(1, source_page_count + 1):
        if original_page in blank_pages:
            continue
        mapping[original_page] = final_page
        final_page += 1
    return mapping


def topic_entries(analysis, source_page_count):
    starts = analysis['topic_starts_original']
    ordered = sorted(starts.items(), key=lambda item: item[1])
    blank_pages = set(analysis.get('blank_pages', []))
    mapping = source_to_final_map(source_page_count, blank_pages)
    result = {}
    for index, (topic, start) in enumerate(ordered):
        end = ordered[index + 1][1] - 1 if index + 1 < len(ordered) else source_page_count
        entries = [
            (mapping[original_page], original_page)
            for original_page in range(start, end + 1)
            if original_page in mapping
        ]
        result[topic] = entries
    return result


def run(args):
    pdf_path = Path(args.pdf)
    source_path = Path(args.source_pdf)
    analysis_path = Path(args.analysis)
    output = Path(args.output)
    preview_dir = Path(args.preview_dir) if args.preview_dir else output.with_name(output.stem + '_qa')
    templates_dir = SKILL_ROOT / 'days' / 'assets' / 'vcheck-templates'

    if output.resolve() == pdf_path.resolve():
        raise RuntimeError('output must differ from input; never modify the source PDF in place')

    analysis = load_analysis(analysis_path)
    source = fitz.open(source_path)
    source_page_count = source.page_count
    source.close()
    doc = fitz.open(pdf_path)
    before_links = vb.link_count(doc)
    before_navigation = vb.navigation_signature(doc)
    before_widgets = vb.widget_count(doc)
    entries_by_topic = topic_entries(analysis, source_page_count)
    sideways_pages = set(analysis.get('sideways_pages', []))

    zoom = float(args.zoom)
    size_px = int(round(float(args.box_size_pt) * zoom))
    gap_px = int(round(float(args.gap_pt) * zoom))
    plans = []
    included_pages = {}

    for topic, entries in sorted(
        entries_by_topic.items(),
        key=lambda item: min((entry[0] for entry in item[1]), default=999999),
    ):
        kind = vb.topic_kind(topic)
        slug = vb.topic_slug(topic)
        if kind in {'none', 'unknown'} or not slug:
            continue

        groups = []
        if kind == 'two-column':
            groups.append(('two-column', sorted(final for final, _original in entries)))
        else:
            four_pages = []
            two_pages = []
            for final_page, original_page in entries:
                if topic in vb.TWO_COLUMN_ENDPAGE_TOPICS and original_page not in sideways_pages:
                    two_pages.append(final_page)
                else:
                    four_pages.append(final_page)
            if four_pages:
                groups.append(('four-column', sorted(four_pages)))
            if two_pages:
                groups.append(('two-column', sorted(two_pages)))

        topic_plans = []
        failures = []
        single_half_pages = set()
        if topic in vb.TWO_COLUMN_ENDPAGE_TOPICS:
            for group_layout, group_pages in groups:
                if group_layout == 'four-column' and group_pages:
                    single_half_pages.add(max(group_pages))
        for layout, pages in groups:
            if layout == 'two-column':
                plan = vb.plan_two_column_topic(
                    doc, fitz, topic, slug, pages, zoom, size_px, gap_px
                )
                expected = {6 * len(pages)}
            elif kind == 'four-boundary':
                plan = vb.plan_four_topic(
                    doc,
                    fitz,
                    topic,
                    slug,
                    pages,
                    zoom,
                    size_px,
                    gap_px,
                    boundary_spec=vb.BOUNDARY_TOPICS[topic],
                    templates_dir=templates_dir,
                    allow_single_half_pages=single_half_pages,
                )
                allowed_count = len(single_half_pages.intersection(pages))
                expected = {12 * len(pages) - 6 * allowed_count, 12 * len(pages)}
            else:
                plan = vb.plan_four_topic(
                    doc, fitz, topic, slug, pages, zoom, size_px, gap_px,
                    allow_single_half_pages=single_half_pages,
                )
                allowed_count = len(single_half_pages.intersection(pages))
                expected = {12 * len(pages) - 6 * allowed_count, 12 * len(pages)}

            if plan.failures or len(plan.candidates) not in expected:
                reason = '; '.join(plan.failures[:8])
                expected_label = '/'.join(str(value) for value in sorted(expected))
                failures.append(
                    f'{layout}: {reason or f"expected {expected_label}, got {len(plan.candidates)}"}'
                )
            topic_plans.append(plan)

        if failures:
            doc.close()
            raise RuntimeError(f'topic {topic} failed: {" | ".join(failures)}')
        plans.extend(topic_plans)
        included_pages[topic] = sorted(final for final, _original in entries)

    if not plans:
        doc.close()
        raise RuntimeError('no approved checkbox topics were found')

    appearances = vb.make_checkbox_appearances(doc)
    added = 0
    for plan in plans:
        for candidate in plan.candidates:
            vb.add_checkbox(
                doc[candidate.page_number - 1],
                fitz,
                candidate,
                appearances,
            )
            added += 1

    output.parent.mkdir(parents=True, exist_ok=True)
    doc.save(
        output,
        garbage=0,
        deflate=True,
        deflate_images=True,
        deflate_fonts=True,
        clean=False,
        use_objstms=1,
    )
    doc.close()

    result = fitz.open(output)
    after_links = vb.link_count(result)
    after_navigation = vb.navigation_signature(result)
    invalid_targets = vb.invalid_internal_links(result, fitz)
    conflicts = vb.widget_link_intersections(result)
    widget_conflicts = vb.widget_widget_intersections(result)
    after_widgets = vb.widget_count(result)

    if before_navigation != after_navigation:
        result.close()
        raise RuntimeError('navigation changed after adding topic checkboxes')
    if invalid_targets:
        result.close()
        raise RuntimeError(f'invalid internal targets: {invalid_targets[:8]}')
    if conflicts:
        result.close()
        raise RuntimeError(f'checkboxes intersect navigation links: {conflicts[:8]}')

    if widget_conflicts:
        result.close()
        raise RuntimeError(f'checkboxes intersect each other: {widget_conflicts[:8]}')
    previews = vb.write_previews(result, included_pages, preview_dir)
    page_count = result.page_count
    result.close()

    included_topics = {}
    for topic, pages in included_pages.items():
        included_topics[topic] = {
            'pages': pages,
            'checkboxes': sum(
                len(plan.candidates) for plan in plans if plan.topic == topic
            ),
        }

    summary = {
        'input_pdf': str(pdf_path),
        'source_pdf': str(source_path),
        'output_pdf': str(output),
        'analysis_json': str(analysis_path),
        'page_count': page_count,
        'links_before': before_links,
        'links_after': after_links,
        'navigation_identical': before_navigation == after_navigation,
        'all_internal_targets_valid': not invalid_targets,
        'no_checkbox_covers_link': not conflicts,
        'no_checkbox_overlaps': not widget_conflicts,
        'widgets_before': before_widgets,
        'widgets_after': after_widgets,
        'checkboxes_added': added,
        'included_topics': included_topics,
        'preview_dir': str(preview_dir),
        'previews': previews,
    }
    preview_dir.mkdir(parents=True, exist_ok=True)
    summary_path = preview_dir / 'vcheck-summary.json'
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')

    print(f'OUTPUT_PDF={output}')
    print(f'PAGE_COUNT={page_count}')
    print(f'CHECKBOXES_ADDED={added}')
    print(f'LINKS_BEFORE={before_links}')
    print(f'LINKS_AFTER={after_links}')
    print(f'NAVIGATION_IDENTICAL={before_navigation == after_navigation}')
    print(f'SUMMARY_JSON={summary_path}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pdf', required=True, help='Built topics booklet before checkboxes.')
    parser.add_argument('--source-pdf', required=True, help='Original interior PDF used for the booklet.')
    parser.add_argument('--analysis', required=True, help='Topics analysis JSON.')
    parser.add_argument('--output', required=True, help='New PDF output; never the input path.')
    parser.add_argument('--preview-dir')
    parser.add_argument('--zoom', type=float, default=2.2)
    parser.add_argument('--box-size-pt', type=float, default=10.0)
    parser.add_argument('--gap-pt', type=float, default=14.0)
    args = parser.parse_args()
    try:
        run(args)
    except Exception as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
