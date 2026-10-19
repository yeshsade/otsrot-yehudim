"""Source-based Gemara planning shared by full builds and topic previews."""

from pathlib import Path

import fitz

from layout_rules import checkbox_rect, gemara_identity


def plan_gemara(doc, analysis, entries, size=12):
    from add_day_notes import (DAYS, DAF_YOMI_TOPIC,
                               calculate_base_continuation_segments, final_day_content_page)
    from add_vcheck_boxes import CandidateBox, TopicPlan

    source_path = Path(analysis["input_pdf"])
    if not source_path.is_file():
        raise FileNotFoundError(f"Pristine source PDF is required: {source_path}")
    segments, _ = calculate_base_continuation_segments(analysis)
    final_days = {}
    for index, (slug, _) in enumerate(DAYS):
        for number in segments.get(slug, {}).get(DAF_YOMI_TOPIC, []):
            final_days[final_day_content_page(number, index)] = slug
    plan = TopicPlan(DAF_YOMI_TOPIC, "gemara", [], kind="gemara")
    per_day = {}
    with fitz.open(source_path) as source:
        for final, original in entries:
            identity = gemara_identity(source[original - 1])
            if identity is None:
                continue
            day = final_days[final]
            per_day.setdefault(day, set()).add(identity["amud"])
            rect = checkbox_rect(doc[final - 1], size)
            field = f'vcheck_gemara_{day}_{identity["key"]}'
            plan.pages.append(final)
            plan.candidates.append(CandidateBox(
                DAF_YOMI_TOPIC, "gemara", final, 1,
                1 if identity["amud"] == "a" else 2, tuple(rect), rect, field))
    if set(per_day) != {slug for slug, _ in DAYS} or any(amud != {"a", "b"} for amud in per_day.values()):
        raise ValueError(f"Gemara recognition must find amud a and b for every day: {per_day}")
    return plan


def join_shared_fields(doc):
    """Make repeated logical fields actual PDF parent/Kids fields.

    Identical names alone are insufficient for synchronized Acrobat widgets.
    PyMuPDF's supported join_duplicates operation creates shared field parents.
    The caller verifies page count and the complete navigation after saving.
    """
    names = [w.field_name for page in doc for w in page.widgets() or ()]
    if len(names) == len(set(names)):
        return doc
    clone = fitz.open()
    clone.insert_pdf(doc, widgets=True, join_duplicates=True)
    clone.set_metadata(doc.metadata)
    doc.close()
    # PyMuPDF joins the names but leaves a separate /V in each child. Those
    # values override the shared parent, so the appearances can diverge.
    # A PDF field has one value; its widget children only own appearance state.
    for page in clone:
        for widget in page.widgets() or ():
            kind, ref = clone.xref_get_key(widget.xref, "Parent")
            if kind != "xref":
                continue
            parent = int(ref.split()[0])
            clone.xref_set_key(parent, "V", "/Off")
            clone.xref_set_key(parent, "Ff", "0")
            for key in ("V", "FT", "Ff"):
                clone.xref_set_key(widget.xref, key, "null")
    return clone


def set_checkbox_state(doc, field_name, checked):
    """Field-level editor for scripts, preserving all custom V appearances.

    Editing one annotation with Widget.update is not a field-level operation
    in every PyMuPDF release. Update the shared value and all /AS states.
    PDF viewers should likewise operate on the parent field, not a lone kid.
    """
    found = False
    for page in doc:
        for widget in page.widgets() or ():
            if widget.field_name != field_name:
                continue
            found = True
            state = widget.on_state() if checked else "Off"
            kind, ref = doc.xref_get_key(widget.xref, "Parent")
            parent = int(ref.split()[0]) if kind == "xref" else widget.xref
            doc.xref_set_key(parent, "V", "/" + state)
            doc.xref_set_key(widget.xref, "AS", "/" + state)
    if not found:
        raise KeyError(field_name)
