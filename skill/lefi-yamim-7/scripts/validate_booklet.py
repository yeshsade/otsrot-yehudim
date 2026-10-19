"""Independent round-trip audits in one physical coordinate system."""
from collections import Counter, defaultdict
import json
from pathlib import Path

import fitz

from source_model import sha256


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
    for row, page in zip(rows, doc):
        if page.rotation:
            raise ValueError("Mixed annotation coordinate systems")
        n = row.get("source_page")
        if n:
            original = "".join(source[n - 1].get_text().split())
            if "".join(page.get_text().split()) != original:
                raise ValueError(f"Source text changed on page {page.number + 1}")
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
            if link.get("kind") == fitz.LINK_GOTO and not 0 <= link.get("page", -1) < len(doc):
                raise ValueError("Invalid internal link target")
            if not page.rect.contains(link["from"]):
                raise ValueError("Link is outside the page")
        source_links = [item for item in navigation if item["source"] == page.number + 1]
        for expected in source_links:
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
        "zero_widget_link_overlaps": True, "navigation_matches_manifest": True,
        "progress_map_matches_widgets": True, "source_files_unchanged": True}
    doc.close()
    return result
