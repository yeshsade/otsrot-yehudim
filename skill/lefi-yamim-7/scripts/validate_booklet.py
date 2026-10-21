"""Independent round-trip audits in one physical coordinate system."""
from collections import Counter, defaultdict
import json
from pathlib import Path

import fitz

from source_model import sha256
from placement import button_rect
from shulchan import audit_shulchan_coverage


def validate_source_dimensions(page, original):
    # Physical normalization may swap width and height but may not add margins.
    if max(abs(a - b) for a, b in zip(sorted((page.rect.width, page.rect.height)),
           sorted((original.rect.width, original.rect.height)))) > .02:
        raise ValueError("Source page dimensions changed; added margins are forbidden")


def validate_internal_destination(doc, link):
    if link.get("kind") != fitz.LINK_GOTO:
        return
    if not 0 <= link.get("page", -1) < len(doc):
        raise ValueError("Invalid internal link target")
    point = link.get("to")
    if point is None or abs(point.x) > .02 or abs(point.y) > .02:
        raise ValueError("Internal link must land at the target page's top, not outside it")


def validate(path, source, source_model, placements, rows, navigation, before_hashes, interior, cover):
    doc = fitz.open(path)
    if len(doc) != len(rows):
        raise ValueError("Page count differs from provenance manifest")
    progress = json.loads(doc.embfile_get("otzarot-progress-map.v1.json"))
    if progress["pages"] != rows:
        raise ValueError("Embedded page map differs from actual build provenance")
    actual, planned = defaultdict(list), defaultdict(list)
    for item in placements:
        planned[item["field"]].append(item["pages"][0])
    widget_count = link_count = 0
    shulchan_coverage = []
    for row, page in zip(rows, doc):
        if page.rotation:
            raise ValueError("Mixed annotation coordinate systems")
        n = row.get("source_page")
        if n:
            validate_source_dimensions(page, source[n - 1])
            original = "".join(source[n - 1].get_text().split())
            if "".join(page.get_text().split()) != original:
                raise ValueError(f"Source text changed on page {page.number + 1}")
        if row["topic_id"] == "shulchan_aruch":
            page_checks = [item for item in placements if item["pages"] == [page.number + 1]]
            regions = {}
            for item in page_checks:
                col = item["column"]
                region = item.get("learning_rect")
                if region is None or (col in regions and regions[col]["rect"] != region):
                    raise ValueError("Inconsistent Shulchan Aruch learning regions")
                regions[col] = {"column": col, "rect": region}
            coverage = audit_shulchan_coverage(page, list(regions.values()), page_checks)
            shulchan_coverage.append({"page": page.number + 1, "source_page": n, **coverage})
        words = [fitz.Rect(word[:4]) for word in page.get_text("words")]
        widgets = list(page.widgets() or [])
        links = page.get_links()
        for index, widget in enumerate(widgets):
            widget_count += 1
            actual[widget.field_name].append(page.number + 1)
            if not page.rect.contains(widget.rect):
                raise ValueError("Widget outside page")
            if any(widget.rect.intersects(word) for word in words):
                raise ValueError(f"Widget covers text on page {page.number + 1}: {widget.field_name}")
            if any(widget.rect.intersects(other.rect) for other in widgets[index + 1:]):
                raise ValueError("Widgets overlap")
            if any(widget.rect.intersects(link["from"]) for link in links):
                raise ValueError("Widget covers navigation")
            matches = [item for item in placements if item["field"] == widget.field_name
                       and item["pages"] == [page.number + 1]]
            if len(matches) != 1 or max(abs(a - b) for a, b in zip(widget.rect, matches[0]["rect"])) > .02:
                raise ValueError("Widget differs from canonical placement list")
        for link in links:
            link_count += 1
            validate_internal_destination(doc, link)
            if not page.rect.contains(link["from"]):
                raise ValueError("Link is outside the page")
        source_links = [item for item in navigation if item["source"] == page.number + 1]
        for expected in source_links:
            if not any(max(abs(a - b) for a, b in zip(expected["rect"],
                       button_rect(page, expected["kind"], scale))) < .02
                       for scale in (1, .875, .75)):
                raise ValueError("Navigation control moved from its approved anchor")
            original = button_rect(page, expected["kind"])
            actual_rect = fitz.Rect(expected["rect"])
            if max(abs(a - b) for a, b in zip(actual_rect, original)) >= .02:
                if not any(original.intersects(widget.rect) for widget in widgets):
                    raise ValueError("Navigation shrink was not required by a checkbox collision")
            if any(actual_rect.intersects(fitz.Rect(other["rect"]))
                   for other in source_links if other is not expected):
                raise ValueError("Navigation controls overlap each other")
            matches = [link for link in links if link.get("page") == expected["target"] - 1
                and max(abs(a - b) for a, b in zip(link["from"], expected["rect"])) < .02]
            if len(matches) != 1:
                raise ValueError("Navigation target or clickable area changed")
    if dict(actual) != dict(planned):
        raise ValueError("Saved widgets differ from planned field/page ownership")
    mapped = {item["field"]: item["pages"] for item in progress["checks"]}
    if mapped != dict(planned):
        raise ValueError("Embedded map is not the actual set of fields")
    for field, pages in actual.items():
        if len(pages) <= 1:
            continue
        parents = set()
        for number in pages:
            page = doc[number - 1]
            widget = next(w for w in page.widgets() if w.field_name == field)
            kind, parent = doc.xref_get_key(widget.xref, "Parent")
            if kind != "xref":
                raise ValueError("Repeated logical checkbox is not a shared parent/Kids field")
            parents.add(parent)
        if len(parents) != 1:
            raise ValueError("Repeated Gemara widgets are not synchronized fields")
    input_size = Path(interior).stat().st_size + Path(cover).stat().st_size
    ratio = Path(path).stat().st_size / input_size
    if ratio > 1.35:
        raise ValueError(f"Unexplained output size inflation: {ratio:.3f}x")
    if [sha256(interior), sha256(cover)] != before_hashes:
        raise ValueError("Input PDF was modified")
    result = {"passed": True, "pages": len(doc), "checkbox_widgets": widget_count,
        "logical_fields": len(actual), "links": link_count,
        "source_sha256": before_hashes[0], "cover_sha256": before_hashes[1],
        "output_sha256": sha256(path), "output_to_source_size_ratio": ratio,
        "source_text_preserved": True, "source_coverage_verified": True,
        "zero_text_overlaps": True, "zero_widget_overlaps": True,
        "zero_widget_link_overlaps": True, "navigation_matches_manifest": True, "navigation_uses_fixed_anchors": True,
        "all_internal_links_land_at_target_top": True,
        "transparent_navigation_over_source_text_allowed": True,
        "progress_map_matches_widgets": True, "source_files_unchanged": True,
        "shulchan_source_coverage": shulchan_coverage}
    doc.close()
    return result
