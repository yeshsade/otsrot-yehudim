#!/usr/bin/env python3
import argparse
import json
import sys
import tempfile
from pathlib import Path

from add_day_notes import DAYS, continuation_rects, draw_continuation_label, insert_goto_link, make_continuation_label_images


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


def parse_source_range(value):
    try:
        start_text, end_text = value.split("-", 1)
        start = int(start_text)
        end = int(end_text)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Use --source-range like 43-56") from exc
    if start > end:
        raise argparse.ArgumentTypeError("Source range start must be before end")
    return start, end


def build_day_runs(analysis, source_range):
    start, end = source_range
    blank_pages = set(analysis.get("blank_pages", []))
    runs = []
    for _slug, day_text in DAYS:
        pages = [
            int(page)
            for page in analysis.get("day_pages", {}).get(day_text, [])
            if start <= int(page) <= end and int(page) not in blank_pages
        ]
        if not pages:
            continue
        runs.append(
            {
                "day": day_text,
                "source_pages": pages,
                "source_start": pages[0],
                "source_end": pages[-1],
                "page_count": len(pages),
            }
        )
    if len(runs) != len(DAYS):
        raise RuntimeError(f"Expected {len(DAYS)} weekday runs, found {len(runs)}. Do not guess.")
    return runs


def render_page(doc, page_1based, output_path, zoom=1.25):
    import fitz

    page = doc[page_1based - 1]
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
    pix.save(str(output_path))


def build_topic_test(args):
    require_deps()
    import fitz

    input_pdf = Path(args.input)
    analysis = json.loads(Path(args.analysis).read_text(encoding="utf-8"))
    day_runs = build_day_runs(analysis, args.source_range)

    source = fitz.open(str(input_pdf))
    out_doc = fitz.open()
    original_to_test_page = {}
    rotated_original = set(analysis.get("rotated_original", []))
    try:
        previous_context_test_page = None
        previous_context_orig = None
        if args.previous_context_page:
            previous_context_orig = int(args.previous_context_page)
            if previous_context_orig < 1 or previous_context_orig > len(source):
                raise RuntimeError(f"--previous-context-page {previous_context_orig} is outside the source PDF.")
        for run in day_runs:
            for orig in run["source_pages"]:
                if orig == previous_context_orig:
                    raise RuntimeError(f"--previous-context-page {orig} is already inside the topic source range.")
                out_doc.insert_pdf(source, from_page=orig - 1, to_page=orig - 1)
                test_page_number = len(out_doc)
                original_to_test_page[orig] = test_page_number
                if orig in rotated_original:
                    page = out_doc[test_page_number - 1]
                    page.set_rotation((page.rotation + 270) % 360)
        if previous_context_orig:
            out_doc.insert_pdf(source, from_page=previous_context_orig - 1, to_page=previous_context_orig - 1)
            previous_context_test_page = len(out_doc)
            if previous_context_orig in rotated_original:
                page = out_doc[previous_context_test_page - 1]
                page.set_rotation((page.rotation + 270) % 360)
    finally:
        source.close()

    test_pages_by_day = {
        run["day"]: [original_to_test_page[orig] for orig in run["source_pages"]]
        for run in day_runs
    }

    link_records = []
    with tempfile.TemporaryDirectory() as tmp:
        label_paths = make_continuation_label_images(Path(tmp))
        if previous_context_test_page:
            first_run = day_runs[0]
            first_day = first_run["day"]
            first_topic_page = test_pages_by_day[first_day][0]

            page = out_doc[previous_context_test_page - 1]
            rect = continuation_rects(fitz, page)["next"]
            insert_goto_link(fitz, out_doc, page, rect, first_topic_page)
            draw_continuation_label(page, rect, label_paths["next"])
            link_records.append(
                {
                    "type": "previous-week-next",
                    "day": "previous-week-at-end",
                    "source_test_page": previous_context_test_page,
                    "target_test_page": first_topic_page,
                    "source_original_page": int(args.previous_context_page),
                    "target_day": first_day,
                }
            )

            page = out_doc[first_topic_page - 1]
            rect = continuation_rects(fitz, page)["previous"]
            insert_goto_link(fitz, out_doc, page, rect, previous_context_test_page)
            draw_continuation_label(page, rect, label_paths["previous"])
            link_records.append(
                {
                    "type": "first-day-previous-week",
                    "day": first_day,
                    "source_test_page": first_topic_page,
                    "target_test_page": previous_context_test_page,
                    "source_original_page": first_run["source_pages"][0],
                    "target_original_page": int(args.previous_context_page),
                }
            )

        for index, run in enumerate(day_runs):
            day = run["day"]
            pages = test_pages_by_day[day]
            previous_run = day_runs[index - 1] if index > 0 else None
            next_run = day_runs[index + 1] if index + 1 < len(day_runs) else None

            if previous_run:
                source_page_number = pages[0]
                target_page_number = test_pages_by_day[previous_run["day"]][-1]
                page = out_doc[source_page_number - 1]
                rect = continuation_rects(fitz, page)["previous"]
                insert_goto_link(fitz, out_doc, page, rect, target_page_number)
                draw_continuation_label(page, rect, label_paths["previous"])
                link_records.append(
                    {
                        "type": "previous",
                        "day": day,
                        "source_test_page": source_page_number,
                        "target_test_page": target_page_number,
                        "source_original_page": run["source_pages"][0],
                        "target_day": previous_run["day"],
                    }
                )

            if next_run:
                source_page_number = pages[-1]
                target_page_number = test_pages_by_day[next_run["day"]][0]
                page = out_doc[source_page_number - 1]
                rect = continuation_rects(fitz, page)["next"]
                insert_goto_link(fitz, out_doc, page, rect, target_page_number)
                draw_continuation_label(page, rect, label_paths["next"])
                link_records.append(
                    {
                        "type": "next",
                        "day": day,
                        "source_test_page": source_page_number,
                        "target_test_page": target_page_number,
                        "source_original_page": run["source_pages"][-1],
                        "target_day": next_run["day"],
                    }
                )

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    out_doc.save(str(output), garbage=4, deflate=True, deflate_images=True, deflate_fonts=True, clean=False, use_objstms=1)

    summary = {
        "topic": args.topic,
        "input_pdf": str(input_pdf),
        "output_pdf": str(output),
        "source_range": list(args.source_range),
        "page_count": len(out_doc),
        "previous_context_page": int(args.previous_context_page) if args.previous_context_page else None,
        "previous_context_test_page": previous_context_test_page,
        "previous_context_position": "end" if previous_context_test_page else None,
        "rotated_original_pages": sorted(orig for run in day_runs for orig in run["source_pages"] if orig in rotated_original),
        "day_runs": day_runs,
        "test_pages_by_day": test_pages_by_day,
        "links": link_records,
    }
    if args.preview_dir:
        preview_dir = Path(args.preview_dir)
        preview_dir.mkdir(parents=True, exist_ok=True)
        (preview_dir / "topic-test-links.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        for record in link_records:
            render_page(out_doc, record["source_test_page"], preview_dir / f"{record['type']}-{record['source_test_page']:02d}.png")

    page_count = len(out_doc)
    out_doc.close()
    print(f"OUTPUT_PDF={output}")
    print(f"PAGE_COUNT={page_count}")
    print(f"LINKS={len(link_records)}")
    if args.preview_dir:
        print(f"PREVIEW_DIR={args.preview_dir}")


def main():
    parser = argparse.ArgumentParser(description="Build a topic-only QA PDF with almost-transparent continuation links.")
    parser.add_argument("--input", required=True, help="Original inside PDF.")
    parser.add_argument("--analysis", required=True, help="Analysis JSON from build_lefi_yamim.py analyze.")
    parser.add_argument("--topic", default="topic")
    parser.add_argument("--source-range", required=True, type=parse_source_range, help="Source-PDF range, e.g. 43-56.")
    parser.add_argument("--previous-context-page", type=int, help="Source-PDF page for a previous-week context/completion page.")
    parser.add_argument("--output", required=True)
    parser.add_argument("--preview-dir")
    args = parser.parse_args()
    try:
        build_topic_test(args)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
