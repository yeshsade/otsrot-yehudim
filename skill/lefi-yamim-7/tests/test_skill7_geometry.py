"""Geometry and PDF round-trip regressions, not just CLI smoke tests."""
from pathlib import Path
import sys
import unittest

import fitz

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "days/scripts"))
from placement import Canvas, box_count, measured_regions
from source_model import column_of, validate_answers
from add_vcheck_boxes import CandidateBox, make_checkbox_appearances, add_checkbox
from gemara_checkboxes import join_shared_fields, set_checkbox_state


class Skill7GeometryTests(unittest.TestCase):
    def test_owner_answers_are_bound_to_both_sources_and_not_assumed(self):
        base = {"source_sha256": "source", "cover_sha256": "cover", "total_original_pages": 160}
        answers = {"source_sha256": "source", "cover_sha256": "cover",
                   "booklet": {"week_start": "2026-11-08", "issue": "845", "parsha": "תולדות"},
                   "previous_week": {"mishnah_berurah": 67, "likutey_moharan": 99,
                                     "likutey_halakhot": 152, "gemara": 151}}
        self.assertEqual(validate_answers(base, answers)["gemara"], 151)
        with self.assertRaises(ValueError):
            validate_answers(base, {**answers, "cover_sha256": "another-cover"})
        with self.assertRaises(ValueError):
            validate_answers(base, {**answers, "previous_week": {}})

    def test_thresholds_and_straight_page_exception(self):
        for height, expected in [(30, 1), (30.001, 2), (60, 2), (60.001, 3), (90, 3)]:
            self.assertEqual(box_count(height, 90, True), expected)
        self.assertEqual(box_count(12, 90, False), 3)
        self.assertEqual(box_count(0, 90, True), 0)
        with self.assertRaises(ValueError):
            box_count(100, 90, True)

    def test_commentary_is_reference_height_not_learning(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            for x in (50, 190, 360, 500):
                page.insert_text((x, 60), "LEARNING " * 4, fontsize=12, fontname="cour")
                page.insert_text((x, 75), "LEARNING " * 4, fontsize=12, fontname="cour")
                for y in range(100, 401, 25):
                    page.insert_text((x, y), "commentary " * 4, fontsize=7, fontname="helv")
            regions = measured_regions(page, "mishnah")
            self.assertEqual(len(regions), 4)
            for region in regions:
                self.assertLess(region["rect"].y1, 90)
                self.assertGreater(region["full_rect"].y1, 390)
                self.assertEqual(box_count(region["rect"].height, region["full_rect"].height, True), 1)

    def test_short_final_column_uses_full_column_reference(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            for x, bottom in ((40, 400), (190, 400), (360, 110), (510, 110)):
                for y in range(60, bottom, 15):
                    page.insert_text((x, y), "study text", fontsize=9)
            regions = measured_regions(page, "sifrei_rabeinu")
            self.assertEqual([box_count(r["rect"].height, r["full_rect"].height, True)
                              for r in regions], [1, 1, 3, 3])

    def test_image_only_ink_and_reserved_controls_block_candidates(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            page.draw_rect(fitz.Rect(28, 649, 40, 661), fill=(0, 0, 0))
            canvas = Canvas(page)
            self.assertFalse(canvas.clear(fitz.Rect(28, 649, 40, 661)))
            rect = canvas.lower_corner()
            self.assertGreater(rect.x0, 40)
            self.assertFalse(canvas.clear(rect))

    def test_crowded_header_keeps_continuation_and_return_clear(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            for rect in ((327, 15, 445, 25), (70, 14, 115, 25), (30, 34, 440, 627)):
                page.draw_rect(fitz.Rect(rect), fill=(0, 0, 0))
            canvas = Canvas(page)
            previous = canvas.button("previous")
            back = canvas.button("return")
            self.assertFalse(previous.intersects(back))
            self.assertGreaterEqual(previous.width, 150 * .75)
            self.assertGreater(back.y0, 627)

    def test_dense_footer_moves_next_to_clear_top_margin(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            page.draw_rect(fitz.Rect(10, 420, 670, 467), fill=(0, 0, 0))
            rect = Canvas(page).button("next")
            self.assertLess(rect.y1, 40)
            self.assertGreaterEqual(rect.width, 150 * .75)

    def test_landscape_with_full_edges_uses_rotated_side_control(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            for rect in ((0, 0, 680, 35), (0, 433, 680, 468), (45, 35, 635, 433)):
                page.draw_rect(fitz.Rect(rect), fill=(0, 0, 0))
            rect = Canvas(page).button("next")
            self.assertGreater(rect.height, rect.width)
            self.assertGreaterEqual(rect.height, 150 * .75)
            self.assertLess(rect.x1, 45)

    def test_nach_font_is_recognized_per_page_not_previous_subset_name(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            for x in (50, 370):
                for y in (65, 82):
                    page.insert_text((x, y), "VERSES " * 5, fontsize=13, fontname="cour")
                for y in range(120, 390, 20):
                    page.insert_text((x, y), "commentary " * 5, fontsize=7, fontname="helv")
            regions = measured_regions(page, "nach", font="DifferentSubsetFromEarlierPage")
            self.assertEqual([r["column"] for r in regions], [1, 2])
            self.assertTrue(all(r["rect"].y1 < 100 for r in regions))

    def test_rotated_page_normalization_preserves_source_appearance(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            page.insert_text((60, 120), "Source geometry", fontsize=16)
            page.set_rotation(270)
            before = page.get_pixmap().samples
            page.remove_rotation()
            self.assertEqual(page.rotation, 0)
            self.assertEqual(page.get_pixmap().samples, before)
            self.assertGreater(page.rect.width, page.rect.height)

    def test_same_amud_has_shared_pdf_parent_and_independent_other_amud(self):
        doc = fitz.open()
        for _ in range(3):
            doc.new_page(width=468, height=680)
        appearances = make_checkbox_appearances(doc)
        for index, name in enumerate(("vcheck_gemara_a", "vcheck_gemara_a", "vcheck_gemara_b")):
            page = doc[index]
            r = fitz.Rect(28, 649, 40, 661)
            add_checkbox(page, fitz, CandidateBox("gemara", "gemara", index + 1, 1, 1,
                         tuple(r), r, name), appearances)
        doc = join_shared_fields(doc)
        raw = doc.tobytes(garbage=4, deflate=True)
        doc.close()
        with fitz.open(stream=raw, filetype="pdf") as saved:
            first, second, other = saved[0], saved[1], saved[2]
            a = next(first.widgets())
            b = next(second.widgets())
            self.assertEqual(saved.xref_get_key(a.xref, "Parent"), saved.xref_get_key(b.xref, "Parent"))
            self.assertEqual(saved.xref_get_key(a.xref, "Parent")[0], "xref")
            set_checkbox_state(saved, a.field_name, True)
            updated = saved.tobytes()
        with fitz.open(stream=updated, filetype="pdf") as saved:
            pages = [saved[i] for i in range(3)]
            self.assertEqual([next(page.widgets()).field_value for page in pages], ["Yes", "Yes", "Off"])
            self.assertEqual([saved.xref_get_key(next(page.widgets()).xref, "AS")[1] for page in pages], ["/Yes", "/Yes", "/Off"])


if __name__ == "__main__":
    unittest.main()
