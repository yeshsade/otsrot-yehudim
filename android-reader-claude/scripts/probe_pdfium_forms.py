#!/usr/bin/env python3
"""Exercise the Android bridge's checkbox strategy using desktop PDFium.

This is a synthetic engine probe, not an Android/WhatsApp or booklet acceptance test.
"""
import argparse
import ctypes
import hashlib
import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace

import fitz
import pypdfium2 as pdfium
import pypdfium2.raw as raw


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def signature(path):
    with fitz.open(path) as document:
        pages = []
        fields = {}
        for page in document:
            links = [{"kind": link["kind"], "page": link.get("page"), "from": list(link["from"])} for link in page.get_links()]
            pages.append({"rotation": page.rotation, "links": links})
            for widget in page.widgets() or []:
                appearance = {}
                for name in ("Off", "Yes"):
                    kind, value = document.xref_get_key(widget.xref, "AP/N/" + name)
                    if kind == "xref":
                        data = document.xref_stream(int(value.split()[0]))
                        appearance[name] = hashlib.sha256(data).hexdigest()
                fields[widget.field_name] = {
                    "page": page.number, "rect": list(widget.rect),
                    "checked": widget.field_value not in ("Off", "", None),
                    "appearance": appearance,
                }
        return {"pages": pages, "fields": fields}


def toggle(pdf, page, annotation, wanted):
    form = pdf.formenv.raw
    before = bool(raw.FPDFAnnot_IsChecked(form, annotation))
    if before == wanted:
        return "unchanged"
    raw.FORM_OnAfterLoadPage(page.raw, form)
    raw.FORM_ForceToKillFocus(form)
    method = "focus-and-space"
    if raw.FORM_SetFocusedAnnot(form, annotation):
        raw.FORM_OnChar(form, page.raw, 32, 0)
        raw.FORM_ForceToKillFocus(form)
    if bool(raw.FPDFAnnot_IsChecked(form, annotation)) != wanted:
        method = "click-fallback"
        rect = raw.FS_RECTF()
        assert raw.FPDFAnnot_GetRect(annotation, ctypes.byref(rect))
        raw.FORM_OnLButtonDown(form, page.raw, 0, (rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
        raw.FORM_OnLButtonUp(form, page.raw, 0, (rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
        raw.FORM_ForceToKillFocus(form)
    assert bool(raw.FPDFAnnot_IsChecked(form, annotation)) == wanted
    return method


def update_all(work, wanted):
    pdf = pdfium.PdfDocument(str(work))
    pdf.init_forms()
    methods = []
    try:
        for index in range(len(pdf)):
            page = pdf[index]
            try:
                for number in range(raw.FPDFPage_GetAnnotCount(page.raw)):
                    annotation = raw.FPDFPage_GetAnnot(page.raw, number)
                    try:
                        if raw.FPDFAnnot_GetSubtype(annotation) == raw.FPDF_ANNOT_WIDGET:
                            assert raw.FPDFAnnot_GetFormFieldType(pdf.formenv.raw, annotation) == raw.FPDF_FORMFIELD_CHECKBOX
                            methods.append(toggle(pdf, page, annotation, wanted))
                    finally:
                        raw.FPDFPage_CloseAnnot(annotation)
            finally:
                page.close()
        temporary = work.with_suffix(".pending.pdf")
        pdf.save(str(temporary))
    finally:
        pdf.close()
    os.replace(temporary, work)
    return methods


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    repository = Path(__file__).resolve().parents[2]
    skill_scripts = repository / "skills/lefi-yamim-6/days/scripts"
    sys.path.insert(0, str(skill_scripts))
    import add_vcheck_boxes as checkbox

    source = args.output / "synthetic-checkbox-source.pdf"
    work = args.output / "synthetic-checkbox-working.pdf"
    document = fitz.open()
    for rotation in (0, 90, 270):
        page = document.new_page(width=300, height=400)
        page.insert_text((30, 45), "Synthetic form - rotation " + str(rotation), fontsize=12)
        page.set_rotation(rotation)
    appearances = checkbox.make_checkbox_appearances(document)
    for index in range(3):
        page = document[index]
        for column, x in enumerate((75, 175), 1):
            candidate = SimpleNamespace(field_name=f"vcheck_mishnah_p{index+1:03}_c{column}_1", pdf_rect=fitz.Rect(x, 110, x+26, 136))
            checkbox.add_checkbox(page, fitz, candidate, appearances)
        page.insert_link({"kind": fitz.LINK_GOTO, "from": fitz.Rect(25, 330, 150, 350), "page": (index+1) % 3, "to": fitz.Point(0, 0)})
    document.save(source)
    document.close()
    work.write_bytes(source.read_bytes())
    original_sha = sha(source)
    baseline = signature(source)
    checked_methods = update_all(work, True)
    checked = signature(work)
    assert len(checked["fields"]) == 6
    assert all(field["checked"] for field in checked["fields"].values())
    assert checked["pages"] == baseline["pages"], "Page rotation or links changed"
    assert all(checked["fields"][name]["page"] == field["page"] and checked["fields"][name]["rect"] == field["rect"] for name, field in baseline["fields"].items()), "Widget geometry changed"
    appearances_preserved = all(checked["fields"][key]["appearance"] == value["appearance"] for key, value in baseline["fields"].items())
    with pdfium.PdfDocument(str(work)) as pdf:
        pdf.init_forms()
        for index in range(3):
            page = pdf[index]
            page.render(scale=1.5).to_pil().save(args.output / f"checked-page-{index+1}.png")
            page.close()
    undo_methods = update_all(work, False)
    undone = signature(work)
    assert all(not field["checked"] for field in undone["fields"].values())
    assert undone["pages"] == baseline["pages"]
    assert all(undone["fields"][name]["appearance"] == field["appearance"] for name, field in baseline["fields"].items()), "Appearance changed after uncheck"
    assert sha(source) == original_sha, "Source fixture was modified"
    result = {
        "scope": "Desktop PDFium; synthetic fixture with the booklet generator's actual appearance helpers",
        "android_or_whatsapp_test": False,
        "original_booklet_test": False,
        "pdfium_version": str(pdfium.PDFIUM_INFO),
        "pages": 3, "rotations": [0, 90, 270], "widgets": 6,
        "checkbox_save_reopen": "passed", "uncheck_save_reopen": "passed",
        "page_rotations_and_links": "preserved",
        "widget_names_and_geometry": "preserved",
        "custom_appearance_streams_after_uncheck": "preserved",
        "custom_appearance_streams_after_check": "preserved" if appearances_preserved else "changed",
        "source_unmodified": True,
        "check_methods": checked_methods, "uncheck_methods": undo_methods,
        "baseline": baseline, "checked": checked, "undone": undone,
    }
    (args.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in result.items() if key not in ("baseline", "checked", "undone")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
