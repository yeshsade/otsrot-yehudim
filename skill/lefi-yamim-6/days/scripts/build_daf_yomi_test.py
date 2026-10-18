#!/usr/bin/env python3
import argparse
import json
import sys
import tempfile
from pathlib import Path

from add_day_notes import DAYS, continuation_label, continuation_rects, draw_continuation_label, insert_goto_link, make_continuation_label_images


DAF_YOMI_TOPIC = "\u05d4\u05d3\u05e3 \u05d4\u05d9\u05d5\u05de\u05d9"
EXPECTED_DAF_YOMI_PAGES_PER_DAY = 4


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


def parse_duplicate(value):
    try:
        page_range, amud = value.split(":", 1)
        start_text, end_text = page_range.split("-", 1)
        start = int(start_text)
        end = int(end_text)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Use --duplicate like 18-23:a, 24-29:b, or 30-37:both") from exc
    amud = amud.strip().lower()
    if amud not in {"a", "b", "both"}:
        raise argparse.ArgumentTypeError("Duplicate amud must be 'a', 'b', or 'both'")
    if start > end:
        raise argparse.ArgumentTypeError("Duplicate source range start must be before end")
    return (start, end), amud

def find_daf_yomi_range(analysis):
    ranges = analysis.get("topic_ranges", {})
    if DAF_YOMI_TOPIC in ranges:
        return tuple(ranges[DAF_YOMI_TOPIC])
    for name, bounds in ranges.items():
        if "\u05d3\u05e3" in name and "\u05d9\u05d5\u05de\u05d9" in name:
            return tuple(bounds)
    candidates = [tuple(bounds) for bounds in ranges.values() if tuple(bounds)[0] == 2]
    if len(candidates) == 1:
        return candidates[0]
    raise RuntimeError("Could not find the daf yomi topic range in the analysis JSON.")


def build_day_runs(analysis, duplicate_by_range):
    start, end = find_daf_yomi_range(analysis)
    blank_pages = set(analysis.get("blank_pages", []))
    day_pages = analysis.get("day_pages", {})
    runs = []
    seen_ranges = set()
    for _slug, day_text in DAYS:
        pages = [
            int(page)
            for page in day_pages.get(day_text, [])
            if start <= int(page) <= end and int(page) not in blank_pages
        ]
        if not pages:
            continue
        source_range = (pages[0], pages[-1])
        duplicate_amud = duplicate_by_range.get(source_range)
        if len(pages) == EXPECTED_DAF_YOMI_PAGES_PER_DAY:
            status = "normal"
            if duplicate_amud:
                raise RuntimeError(f"{day_text} has a normal four-page daf yomi run; duplicate was provided for {pages[0]}-{pages[-1]}.")
        elif len(pages) == 6:
            status = "duplicate"
            if duplicate_amud not in {"a", "b"}:
                raise RuntimeError(
                    f"Daf yomi exception found on {day_text}: source pages {pages[0]}-{pages[-1]}. "
                    "Ask whether the duplication is on page a, page b, or both, then pass --duplicate "
                    f"{pages[0]}-{pages[-1]}:a or --duplicate {pages[0]}-{pages[-1]}:b."
                )
            seen_ranges.add(source_range)
        elif len(pages) == 8:
            status = "duplicate"
            if duplicate_amud != "both":
                raise RuntimeError(
                    f"Daf yomi exception found on {day_text}: source pages {pages[0]}-{pages[-1]}. "
                    "This 8-page run means both amud a and amud b are duplicated; pass --duplicate "
                    f"{pages[0]}-{pages[-1]}:both after user confirmation."
                )
            seen_ranges.add(source_range)
        else:
            raise RuntimeError(
                f"Daf yomi exception found on {day_text}: source pages {pages[0]}-{pages[-1]} "
                f"have {len(pages)} pages. Do not guess; ask the user for the exact source-page range and structure."
            )
        runs.append(
            {
                "day": day_text,
                "source_pages": pages,
                "source_start": pages[0],
                "source_end": pages[-1],
                "page_count": len(pages),
                "status": status,
                "duplicate_amud": duplicate_amud,
            }
        )
    unused = set(duplicate_by_range) - seen_ranges
    if unused:
        printable = ", ".join(f"{start}-{end}" for start, end in sorted(unused))
        raise RuntimeError(f"Duplicate range(s) did not match an unusual daf yomi day: {printable}")
    return runs

def daf_yomi_endpoints(run, original_to_test_page):
    pages = run["source_pages"]
    mapped = [original_to_test_page[orig] for orig in pages]
    if len(mapped) == 4:
        first_gemara = mapped[1]
        last_b_gemara = mapped[2]
        model = ["peirush-a", "gemara-a", "gemara-b", "peirush-b"]
    elif len(mapped) == 6 and run.get("duplicate_amud") == "a":
        first_gemara = mapped[1]
        last_b_gemara = mapped[4]
        model = ["peirush-a1", "gemara-a1", "peirush-a2", "gemara-a2", "gemara-b", "peirush-b"]
    elif len(mapped) == 6 and run.get("duplicate_amud") == "b":
        first_gemara = mapped[1]
        last_b_gemara = mapped[4]
        model = ["peirush-a", "gemara-a", "gemara-b1", "peirush-b1", "gemara-b2", "peirush-b2"]
    elif len(mapped) == 8 and run.get("duplicate_amud") == "both":
        first_gemara = mapped[1]
        last_b_gemara = mapped[6]
        model = ["peirush-a1", "gemara-a1", "peirush-a2", "gemara-a2", "gemara-b1", "peirush-b1", "gemara-b2", "peirush-b2"]
    else:
        raise RuntimeError(f"Unsupported daf yomi run: {run}")
    return {
        "first_gemara_page": first_gemara,
        "last_b_gemara_page": last_b_gemara,
        "model": model,
    }

def render_page(doc, page_1based, output_path, zoom=1.25):
    import fitz

    page = doc[page_1based - 1]
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
    pix.save(str(output_path))


def build_daf_yomi_test(args):
    require_deps()
    import fitz

    input_pdf = Path(args.input)
    analysis = json.loads(Path(args.analysis).read_text(encoding="utf-8"))
    duplicate_by_range = dict(args.duplicate or [])
    day_runs = build_day_runs(analysis, duplicate_by_range)

    source = fitz.open(str(input_pdf))
    out_doc = fitz.open()
    original_to_test_page = {}
    try:
        for run in day_runs:
            for orig in run["source_pages"]:
                out_doc.insert_pdf(source, from_page=orig - 1, to_page=orig - 1)
                original_to_test_page[orig] = len(out_doc)
        previous_week_test_page = None
        if args.previous_week_gemara_page:
            previous_week_orig = int(args.previous_week_gemara_page)
            if previous_week_orig in original_to_test_page:
                raise RuntimeError(
                    f"--previous-week-gemara-page {previous_week_orig} is already inside the current daf yomi week."
                )
            if previous_week_orig < 1 or previous_week_orig > len(source):
                raise RuntimeError(f"--previous-week-gemara-page {previous_week_orig} is outside the source PDF.")
            out_doc.insert_pdf(source, from_page=previous_week_orig - 1, to_page=previous_week_orig - 1)
            previous_week_test_page = len(out_doc)
    finally:
        source.close()

    endpoints_by_day = {
        run["day"]: daf_yomi_endpoints(run, original_to_test_page)
        for run in day_runs
    }

    link_records = []
    with tempfile.TemporaryDirectory() as tmp:
        label_paths = make_continuation_label_images(Path(tmp), topics=(DAF_YOMI_TOPIC,))
        if previous_week_test_page:
            first_run = day_runs[0]
            first_day = first_run["day"]
            first_gemara_page = endpoints_by_day[first_day]["first_gemara_page"]

            page = out_doc[previous_week_test_page - 1]
            rect = continuation_rects(fitz, page)["next"]
            insert_goto_link(fitz, out_doc, page, rect, first_gemara_page)
            label = continuation_label(DAF_YOMI_TOPIC, first_day)
            draw_continuation_label(page, rect, label_paths[label])
            link_records.append(
                {
                    "type": "previous-week-next",
                    "day": "previous-week",
                    "source_test_page": previous_week_test_page,
                    "target_test_page": first_gemara_page,
                    "source_original_page": int(args.previous_week_gemara_page),
                    "target_day": first_day,
                    "label": label,
                }
            )

            page = out_doc[first_gemara_page - 1]
            rect = continuation_rects(fitz, page)["previous"]
            insert_goto_link(fitz, out_doc, page, rect, previous_week_test_page)
            label = continuation_label(DAF_YOMI_TOPIC, "שבוע שעבר")
            draw_continuation_label(page, rect, label_paths[label])
            link_records.append(
                {
                    "type": "first-week-previous",
                    "day": first_day,
                    "source_test_page": first_gemara_page,
                    "target_test_page": previous_week_test_page,
                    "source_original_page": first_run["source_pages"][1],
                    "target_original_page": int(args.previous_week_gemara_page),
                    "label": label,
                }
            )

        for index, run in enumerate(day_runs):
            day = run["day"]
            endpoints = endpoints_by_day[day]
            previous_run = day_runs[index - 1] if index > 0 else None
            next_run = day_runs[index + 1] if index + 1 < len(day_runs) else None

            if previous_run:
                source_page_number = endpoints["first_gemara_page"]
                target_page_number = endpoints_by_day[previous_run["day"]]["last_b_gemara_page"]
                page = out_doc[source_page_number - 1]
                rect = continuation_rects(fitz, page)["previous"]
                insert_goto_link(fitz, out_doc, page, rect, target_page_number)
                label = continuation_label(DAF_YOMI_TOPIC, previous_run["day"])
                draw_continuation_label(page, rect, label_paths[label])
                link_records.append(
                    {
                        "type": "previous",
                        "day": day,
                        "source_test_page": source_page_number,
                        "target_test_page": target_page_number,
                        "source_original_page": run["source_pages"][endpoints["model"].index("gemara-a") if "gemara-a" in endpoints["model"] else 1],
                        "target_day": previous_run["day"],
                        "label": label,
                    }
                )

            if next_run:
                source_page_number = endpoints["last_b_gemara_page"]
                target_page_number = endpoints_by_day[next_run["day"]]["first_gemara_page"]
                page = out_doc[source_page_number - 1]
                rect = continuation_rects(fitz, page)["next"]
                insert_goto_link(fitz, out_doc, page, rect, target_page_number)
                label = continuation_label(DAF_YOMI_TOPIC, next_run["day"])
                draw_continuation_label(page, rect, label_paths[label])
                link_records.append(
                    {
                        "type": "next",
                        "day": day,
                        "source_test_page": source_page_number,
                        "target_test_page": target_page_number,
                        "source_original_page": run["source_pages"][endpoints["model"].index("gemara-b") if "gemara-b" in endpoints["model"] else 4],
                        "target_day": next_run["day"],
                        "label": label,
                    }
                )

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    out_doc.save(str(output), garbage=4, deflate=True, deflate_images=True, deflate_fonts=True, clean=False, use_objstms=1)

    summary = {
        "input_pdf": str(input_pdf),
        "output_pdf": str(output),
        "page_count": len(out_doc),
        "previous_week_gemara_page": int(args.previous_week_gemara_page) if args.previous_week_gemara_page else None,
        "previous_week_test_page": previous_week_test_page,
        "day_runs": day_runs,
        "endpoints_by_day": endpoints_by_day,
        "links": link_records,
    }
    if args.preview_dir:
        preview_dir = Path(args.preview_dir)
        preview_dir.mkdir(parents=True, exist_ok=True)
        (preview_dir / "daf-yomi-test-links.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
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
    parser = argparse.ArgumentParser(description="Build a daf-yomi-only QA PDF with almost-transparent continuation links.")
    parser.add_argument("--input", required=True, help="Original inside PDF.")
    parser.add_argument("--analysis", required=True, help="Analysis JSON from build_lefi_yamim.py analyze.")
    parser.add_argument("--previous-week-gemara-page", type=int, help="Source-PDF page for the previous week's final Gemara page. It is kept after the current week in this QA PDF, not moved before it.")
    parser.add_argument("--duplicate", action="append", type=parse_duplicate, help="Source range and duplicated amud, e.g. 18-23:a.")
    parser.add_argument("--output", required=True)
    parser.add_argument("--preview-dir")
    args = parser.parse_args()
    try:
        build_daf_yomi_test(args)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
