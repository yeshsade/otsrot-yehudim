#!/usr/bin/env python3
"""Build Skill 7 from pristine sources, with one canonical placement manifest.

Usage: python scripts/build_booklet.py --help
"""
import argparse
from collections import Counter, defaultdict
import json
import os
from pathlib import Path
import subprocess
import sys

import fitz

from source_model import (ROOT, DAYS, TOPICS, IDS, NAME_IDS, discover, model, sha256,
                          spans, column_of)
from typography import audit_profile
from shulchan import measure_shulchan, audit_shulchan_coverage
from placement import Canvas, box_count, measured_regions, button_rect
from layout_rules import gemara_identity
from add_vcheck_boxes import CandidateBox, make_checkbox_appearances, add_checkbox
from gemara_checkboxes import join_shared_fields
from toc_template import (make_toc_pdf, make_return_button_image, make_continuation_button_images,
                          insert_toc_links, default_toc_config, external_links_from_config, font_candidates)
from add_day_notes import make_note_pdf, font_path
from generate_edition_label import make_label
import build_lefi_yamim as legacy

MAP_NAME = "otzarot-progress-map.v1.json"
GROUPED = {"zohar", "likutey_halakhot", "sifrei_rabeinu"}
NO_CONTINUATION = {"parsha", "tehillim", "tikkun_haklali", "introduction"}


def write_json(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def map_topics():
    ordered = []
    for topic, name in TOPICS.items():
        if topic == "combined_midot_tzadikim":
            ordered.extend([("sefer_hamidot", "ספר המידות"), ("shemot_tzadikim", "שמות הצדיקים")])
        else:
            ordered.append((topic, name))
    ordered.extend([("cover", "כריכה"), ("contents", "תוכן העניינים"),
                    ("hareni_mekasher", "הריני מקשר"), ("day_note", "יום הלימוד"),
                    ("introduction", "מבוא")])
    return [{"id": topic, "label_he": name, "order": index}
            for index, (topic, name) in enumerate(ordered)]


def normalize(page):
    """Physical landscape pages keep identical appearance and one coordinate system.

    Word boxes, widgets and get_links must use the same coordinates. Keeping
    /Rotate while mixing those APIs caused false/missed collisions in Skill 6.
    """
    if page.rotation:
        page.remove_rotation()


def build(interior, cover, answers, output, checker, edition="days"):
    output = Path(output)
    if output.exists():
        raise ValueError("Output already exists; choose a fresh output path")
    if output.resolve() in {Path(interior).resolve(), Path(cover).resolve()}:
        raise ValueError("Never overwrite sources")
    fonts = audit_profile([answers.get("booklet", {}).get("parsha", ""),
                           answers.get("booklet", {}).get("date_label", "")])
    output.mkdir(parents=True)
    work = output / "qa"
    work.mkdir()
    write_json(work / "font-audit.json", fonts)
    base = discover(interior, cover)
    write_json(work / "discovery.json", base)
    inputs_before = [sha256(interior), sha256(cover)]
    source = fitz.open(interior)
    cover_doc = fitz.open(cover)
    if len(cover_doc) < 4:
        raise ValueError("Four cover pages are required")
    src = model(base, answers, source)
    booklet = answers["booklet"]
    issue, parsha = str(booklet["issue"]), booklet["parsha"]
    date_label = booklet["date_label"]
    color = legacy.detect_circle_hex(str(cover))
    write_json(work / "source-model.json", src)
    print("SOURCE_OWNERSHIP_VALIDATED", flush=True)

    doc = fitz.open()
    rows = []
    segments = defaultdict(list)
    note_targets = {}
    source_occurrences = defaultdict(list)

    def append_pdf(pdf, first, last, row):
        start = len(doc)
        doc.insert_pdf(pdf, from_page=first, to_page=last, final=0)
        for index in range(start, len(doc)):
            normalize(doc[index])
            rows.append({"page": index + 1, "day_id": None, "week_offset": 0, **row})
        return start + 1

    def append_source(n, day=None, previous=False):
        topic = src["owners"][n]
        number = append_pdf(source, n - 1, n - 1,
                            {"source_page": n, "topic_id": topic, "day_id": day,
                             "week_offset": -1 if previous else 0})
        source_occurrences[n].append(number)
        segments[topic, day, -1 if previous else 0].append(number)
        return number

    append_pdf(cover_doc, 0, 0, {"topic_id": "cover"})
    if edition == "days":
        with fitz.open(ROOT / "days/assets/pre-toc-page.pdf") as hareni:
            append_pdf(hareni, 0, 0, {"topic_id": "hareni_mekasher"})
        toc_path = work / "days-toc.pdf"
        make_toc_pdf(toc_path, parsha, date_label, color=color)
    else:
        toc_path = work / "topics-toc.pdf"
        subprocess.run([sys.executable, str(ROOT / "topics/scripts/generate_topics_toc.py"),
            "--template", str(ROOT / "topics/assets/topics-toc-template.png"),
            "--parsha", parsha, "--date", date_label, "--color", color,
            "--output-png", str(work / "topics-toc.png"), "--output-pdf", str(toc_path)], check=True)
    with fitz.open(toc_path) as toc:
        toc_number = append_pdf(toc, 0, 0, {"topic_id": "contents"})

    if edition == "days":
        for n, topic in sorted(src["owners"].items()):
            if topic == "parsha":
                append_source(n)
        for slug, day_he in DAYS:
            note_pdf = make_note_pdf(day_he, "לנצח עוד יום", color, work / "day-notes", slug)
            with fitz.open(note_pdf) as note:
                note_targets[slug] = append_pdf(note, 0, 0, {"topic_id": "day_note", "day_id": slug})
            for n in src["days"][slug]:
                append_source(n, slug)
        for n, topic in sorted(src["owners"].items()):
            if topic == "tikkun_haklali":
                append_source(n)
        for n in sorted(src["contexts"]):
            append_source(n, previous=True)
    else:
        source_days = defaultdict(set)
        for slug, numbers in src["days"].items():
            for n in numbers:
                source_days[n].add(slug)
        for n in sorted(src["owners"]):
            choices = source_days[n]
            append_source(n, next(iter(choices)) if len(choices) == 1 else None,
                          previous=n in src["contexts"])
    append_pdf(cover_doc, 3, 3, {"topic_id": "cover"})
    cover_doc.close()
    print(f"LAYOUT_PAGES={len(doc)}", flush=True)

    # Every study source page survives; source raster introduction is intentionally
    # omitted in the historical days layout but retained in the topics layout.
    expected = set(src["owners"])
    if edition == "days":
        expected -= {n for n, topic in src["owners"].items() if topic == "introduction"}
    if set(source_occurrences) != expected:
        raise ValueError("Study source-page coverage mismatch")
    for row in rows:
        if "source_page" in row:
            actual = doc[row["page"] - 1].get_text()
            original = source[row["source_page"] - 1].get_text()
            if "".join(actual.split()) != "".join(original.split()):
                raise ValueError(f"Source text changed on final page {row['page']}")

    write_json(work / "layout.json", rows)
    doc.save(work / "source-layer.pdf", garbage=4, deflate=True, use_objstms=1)
    doc.close()
    doc = fitz.open(work / "source-layer.pdf")
    print("PLANNING_SOURCE_COLLISIONS", flush=True)
    canvases = {row["page"]: Canvas(doc[row["page"] - 1]) for row in rows
                if "source_page" in row or row["topic_id"] == "day_note"}
    checks = []
    measurements = []
    shulchan_coverage = []
    page_indices = Counter()
    for row in rows:
        n = row.get("source_page")
        topic = row["topic_id"]
        if n is None or topic in {"parsha", "tikkun_haklali", "introduction"}:
            continue
        page_number = row["page"]
        page = doc[page_number - 1]
        canvas = canvases[page_number]
        day_choices = [row["day_id"]]
        if edition == "topics" and row["week_offset"] == 0:
            day_choices = [slug for slug, _ in DAYS if n in src["days"][slug]]
        for day in day_choices:
            index_key = (topic, day, row["week_offset"])
            page_indices[index_key] += 1
            page_in_day = page_indices[index_key]
            base_check = {"topic_id": topic, "day_id": day, "week_offset": row["week_offset"],
                          "page_in_day": page_in_day, "pages": [page_number], "source_page": n}

            def add(rect, column, index, label=None, region=None, shared_key=None):
                mapped_topic = topic
                mapped_column = column
                if topic == "combined_midot_tzadikim":
                    mapped_topic = "sefer_hamidot" if column <= 2 else "shemot_tzadikim"
                    mapped_column = column if column <= 2 else column - 2
                field = (f"vcheck_{mapped_topic}_{issue}_{day or 'previous'}_" +
                         (f"{shared_key}" if shared_key else f"s{n:03d}_c{mapped_column}_{index}"))
                item = {**base_check, "topic_id": mapped_topic, "column": mapped_column,
                        "field": field, "id": field, "order": len(checks) + 1,
                        "rect": list(rect)}
                if label:
                    item["label_he"] = label
                if topic in GROUPED:
                    item["group_he"] = (("עמוד א׳" if page_in_day == 1 else "עמוד ב׳")
                                        if topic == "likutey_halakhot" else f"עמוד {page_in_day}")
                if region is not None:
                    item["learning_rect"] = list(region)
                checks.append(item)

            if topic == "gemara":
                identity = gemara_identity(source[n - 1])
                if identity is not None:
                    add(canvas.lower_corner(), 1, 1,
                        "עמוד א׳" if identity["amud"] == "a" else "עמוד ב׳",
                        shared_key=identity["key"])
                continue
            if topic == "mishnah_berurah":
                add(canvas.lower_corner(right=True), 1, 1)
                continue
            if topic == "shulchan_aruch":
                regions = measure_shulchan(page)["regions"]
                coverage = audit_shulchan_coverage(page, regions)
                shulchan_coverage.append({"page": page_number, "source_page": n,
                                          "day_id": day, **coverage})
            else:
                regions = measured_regions(page, topic,
                           tehillim=[r for r in src["tehillim"].get(day, []) if r["source_page"] == n])
            for region in regions:
                col = region["column"]
                rect = region["rect"]
                if topic == "nach":
                    add(canvas.nach(region), col, 1, f"חלק {(page_in_day - 1) * 2 + col}", region=rect)
                    continue
                count = box_count(rect.height, region["full_rect"].height, page.rect.width > page.rect.height)
                measurements.append({"page": page_number, "source_page": n, "topic_id": topic,
                    "day_id": day, "column": col, "learning_rect": list(rect),
                    "full_rect": list(region["full_rect"]), "checkboxes": count})
                for index in range(1, count + 1):
                    add(canvas.checkbox(region, index / count), col, index, region=rect)
        if page_number % 20 == 0:
            print(f"PLACED_THROUGH_PAGE={page_number}", flush=True)
    write_json(work / "measurements.json", measurements)
    write_json(work / "shulchan-coverage.json", shulchan_coverage)

    images = make_continuation_button_images(work / "buttons", color=color,
        labels={"previous": "העמוד הקודם", "next": "העמוד הבא"},
        font_file=font_path(font_candidates("bold")))
    images["return"] = make_return_button_image(work / "return.png", color=color)
    links = []
    button_conflicts = []

    def link(source_page, target_page, kind):
        page = doc[source_page - 1]
        try:
            rect = canvases[source_page].button(kind)
        except ValueError as exc:
            row = rows[source_page - 1]
            button_conflicts.append({"page": source_page, "source_page": row.get("source_page"),
                "topic_id": row["topic_id"], "kind": kind, "target": target_page,
                "original_rect": list(button_rect(page, kind)),
                "reason": str(exc)})
            return
        page.insert_image(rect, filename=str(images[kind]), overlay=True)
        page.insert_link({"kind": fitz.LINK_GOTO, "from": rect,
                          "page": target_page - 1, "to": fitz.Point(0, 0)})
        links.append({"source": source_page, "target": target_page, "kind": kind, "rect": list(rect)})

    # Controls yield to already reserved checkboxes. Visual and clickable
    # rectangles are identical, including any approved 25% reduction.
    if edition == "days":
        for topic in TOPICS:
            if topic in NO_CONTINUATION or topic == "supplements":
                continue
            runs = []
            for day, _ in DAYS:
                numbers = segments.get((topic, day, 0), [])
                if topic == "gemara":
                    numbers = [p for p in numbers if gemara_identity(source[rows[p - 1]["source_page"] - 1]) is not None]
                if numbers:
                    runs.append((day, numbers))
            prior = segments.get((topic, None, -1), [])
            if prior and runs:
                link(prior[-1], runs[0][1][0], "next")
                link(runs[0][1][0], prior[-1], "previous")
            for (_, first), (_, second) in zip(runs, runs[1:]):
                link(first[-1], second[0], "next")
                link(second[0], first[-1], "previous")
        for number in canvases:
            link(number, toc_number, "return")
        targets = {("shabbos" if day == "saturday" else day): pn for day, pn in note_targets.items()}
        for topic, key in [("parsha", "parsha"), ("tikkun_haklali", "tikun")]:
            numbers = segments.get((topic, None, 0), [])
            if numbers:
                targets[key] = numbers[0]
        insert_toc_links(fitz, doc[toc_number - 1], targets,
                         external_links_from_config(default_toc_config()))
    else:
        # A previous-week page may sit in the source appendix, away from its
        # current topic. The TOC opens it first, so provide the actual next
        # reading destination and the reciprocal context link.
        for previous_source, topic in src["contexts"].items():
            prior = segments.get((topic, None, -1), [])
            current = [r["page"] for r in rows if r["topic_id"] == topic and r["week_offset"] == 0]
            if topic == "gemara":
                current = [pn for pn in current
                           if gemara_identity(source[rows[pn - 1]["source_page"] - 1]) is not None]
            if not prior or not current:
                raise ValueError(f"Missing context/current navigation for {topic}")
            link(prior[-1], current[0], "next")
            link(current[0], prior[-1], "previous")
        for number in canvases:
            link(number, toc_number, "return")
        from importlib.util import spec_from_file_location, module_from_spec
        spec = spec_from_file_location("skill7_topics_toc", ROOT / "topics/scripts/build_topics_booklet.py")
        toc_engine = module_from_spec(spec)
        spec.loader.exec_module(toc_engine)
        for topic, rect in zip(IDS, toc_engine.RECTS):
            current_numbers = [r["page"] for r in rows if r["topic_id"] == topic
                               or (topic == "supplements" and r["week_offset"] == -1)]
            if not current_numbers:
                raise ValueError(f"No topics TOC target for {topic}")
            previous_numbers = [r["page"] for r in rows
                                if r["topic_id"] == topic and r["week_offset"] == -1]
            target = previous_numbers[0] if previous_numbers else min(current_numbers)
            doc[toc_number - 1].insert_link({"kind": fitz.LINK_GOTO, "from": fitz.Rect(rect),
                "page": target - 1, "to": fitz.Point(0, 0)})
        for uri, rect in [(default_toc_config()["whatsapp_url"], (90.3,600.9,122,631.6)),
                          (default_toc_config()["email_url"], (137.1,600.9,170.5,631.6)),
                          (default_toc_config()["archive_url"], (264.1,600.9,414.5,627.1))]:
            doc[toc_number - 1].insert_link({"kind": fitz.LINK_URI, "from": fitz.Rect(rect), "uri": uri})

    write_json(work / "navigation-placement-audit.json", {
        "passed": not button_conflicts, "policy": "fixed-anchors-checkbox-shrink-only",
        "planned_controls": len(links), "conflicts": button_conflicts})
    write_json(work / "placement-manifest.json", checks)
    write_json(work / "navigation-manifest.json", links)
    if button_conflicts:
        doc.close()
        source.close()
        raise ValueError(f"{len(button_conflicts)} controls conflict at their approved anchors; "
                         f"see {work / 'navigation-placement-audit.json'}. No delivery PDF created.")

    print(f"NAVIGATION_LINKS={len(links)} CHECKBOX_WIDGETS={len(checks)}", flush=True)
    appearances = make_checkbox_appearances(doc)
    for item in checks:
        page = doc[item["pages"][0] - 1]
        rect = fitz.Rect(item["rect"])
        add_checkbox(page, fitz, CandidateBox(item["topic_id"], item["topic_id"], page.number + 1,
                     item["column"], item["order"], tuple(rect), rect, item["field"]), appearances)
    grouped = {}
    for item in checks:
        if item["field"] in grouped:
            grouped[item["field"]]["pages"] += item["pages"]
        else:
            grouped[item["field"]] = {k: v for k, v in item.items() if k != "rect"}
            # Logical aggregation must not mutate the physical occurrence list.
            grouped[item["field"]]["pages"] = list(item["pages"])
    doc = join_shared_fields(doc)

    topic_rows = map_topics()
    for row in rows:
        if row["topic_id"] == "combined_midot_tzadikim":
            row["topic_id"] = "sefer_hamidot"
            row["topic_id_right"] = "sefer_hamidot"
            row["topic_id_left"] = "shemot_tzadikim"
        if row["topic_id"] == "tehillim":
            row["reading_regions"] = [{"day_id": slug, "column": r["column"], "rect": r["rect"]}
                for slug, regions in src["tehillim"].items() for r in regions if r["source_page"] == row["source_page"]]
            if edition == "topics" and row["day_id"] is None:
                for half, cols in [("right", {1, 2}), ("left", {3, 4})]:
                    assigned = {r["day_id"] for r in row["reading_regions"] if r["column"] in cols}
                    row["day_id_" + half] = next(iter(assigned)) if len(assigned) == 1 else None
    progress = {"schema": "otzarot.progress-map/1", "booklet": {
        "week_start": booklet["week_start"], "parsha": parsha, "issue": issue},
        "topics": topic_rows, "days": [{"id": slug, "label_he": name, "order": index}
            for index, (slug, name) in enumerate(DAYS)], "checks": list(grouped.values()), "pages": rows}
    doc.embfile_add(MAP_NAME, json.dumps(progress, ensure_ascii=False).encode(), filename=MAP_NAME,
                    desc="Skill 7 measured reading progress map")
    label = work / "edition-label.png"
    make_label(parsha, date_label, color, edition, label)
    from PIL import Image
    with Image.open(label) as img:
        aspect = img.height / img.width
    front = doc[0]
    x1, x2 = .275 * front.rect.width, .626 * front.rect.width
    height = (x2 - x1) * aspect
    cy = .339 * front.rect.height + 2.83
    front.insert_image(fitz.Rect(x1, cy - height / 2, x2, cy + height / 2), filename=str(label))
    delivery = output / f"{issue}_{parsha.replace(' ', '_')}_לפי_{'ימים' if edition == 'days' else 'נושאים'}.pdf"
    final = work / "candidate.pdf"
    doc.save(final, garbage=4, deflate=True, deflate_images=True, deflate_fonts=True, use_objstms=1)
    doc.close()
    write_json(work / "progress-map.json", progress)
    write_json(work / "placement-manifest.json", checks)
    write_json(work / "navigation-manifest.json", links)
    from validate_booklet import validate
    audit = validate(final, source, src, checks, rows, links, inputs_before, interior, cover)
    write_json(output / "validation.json", audit)
    source.close()
    result = subprocess.run([sys.executable, str(checker), str(final)], text=True, capture_output=True)
    (output / "claude-checker.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
    print(result.stdout, flush=True)
    if result.returncode or "✓ תקין" not in result.stdout:
        raise ValueError("Independent checker failed; candidate is not ready for delivery")
    audit["independent_checker"] = {"path": str(checker), "sha256": sha256(checker), "passed": True}
    write_json(output / "validation.json", audit)
    final.replace(delivery)
    print(f"VALIDATED_PDF={delivery}", flush=True)
    return delivery


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interior", required=True)
    parser.add_argument("--cover", required=True)
    parser.add_argument("--answers", required=True, help="Source-hash-bound review and owner answers JSON")
    parser.add_argument("--output-dir", required=True, help="A fresh directory; existing outputs are never patched")
    parser.add_argument("--checker", required=True, help="Reviewed independent check_booklet.py from Claude")
    parser.add_argument("--fonts-dir", help="Directory containing the four owner-supplied original fonts")
    parser.add_argument("--edition", choices=["days", "topics"], default="days")
    args = parser.parse_args()
    if args.fonts_dir:
        os.environ["OTZAROT_FONTS_DIR"] = args.fonts_dir
    answers = json.loads(Path(args.answers).read_text(encoding="utf-8"))
    build(args.interior, args.cover, answers, args.output_dir, args.checker, args.edition)


if __name__ == "__main__":
    main()
