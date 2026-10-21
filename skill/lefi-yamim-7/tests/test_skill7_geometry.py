"""Geometry and PDF round-trip regressions, not just CLI smoke tests."""
from pathlib import Path
import sys
import unittest

import fitz

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "days/scripts"))
from placement import Canvas, box_count, measured_regions, button_rect, zohar_translation_heading
from source_model import column_of, validate_answers, gray_backgrounds
from add_vcheck_boxes import CandidateBox, make_checkbox_appearances, add_checkbox
from gemara_checkboxes import join_shared_fields, set_checkbox_state
from build_booklet import map_topics
from validate_booklet import validate_source_dimensions, validate_internal_destination


class Skill7GeometryTests(unittest.TestCase):
    def test_internal_link_cannot_land_outside_correct_page(self):
        with fitz.open() as doc:
            doc.new_page(width=468, height=680)
            doc.new_page(width=468, height=244)
            link = {"kind": fitz.LINK_GOTO, "page": 1, "to": fitz.Point(0, -436)}
            with self.assertRaisesRegex(ValueError, "target page's top"):
                validate_internal_destination(doc, link)
            link["to"] = fitz.Point(0, 0)
            validate_internal_destination(doc, link)

    def test_zohar_translation_heading_encodings(self):
        for text in ("תרגום לשון הזוהר", "רהוזה ןושל םוגרת", "øäåæä ïåùì íåâøú"):
            self.assertTrue(zohar_translation_heading(text), text)
        self.assertFalse(zohar_translation_heading("תוספתא"))

    def test_zohar_mixed_study_styles_cover_every_column_before_translation(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            for col, x in enumerate((45, 190, 360, 505)):
                for y in range(60, 251, 15):
                    font = "hebo" if col in (0, 3) and y < 120 else "heit"
                    page.insert_text((x, y), "study text", fontname=font,
                                     fontsize=11 if font == "hebo" else 10.5)
                for y in range(310, 421, 15):
                    page.insert_text((x, y), "translation", fontsize=8)
            for x in (150, 465):
                page.insert_text((x, 285), "øäåæä ïåùì íåâøú", fontsize=9)
            regions = measured_regions(page, "zohar")
            self.assertEqual([r["column"] for r in regions], [1, 2, 3, 4])
            for r in regions:
                self.assertGreater(r["rect"].y1, 240)
                self.assertLess(r["rect"].y1, 275)
                self.assertGreater(r["full_rect"].y1, 410)
                self.assertEqual(box_count(r["rect"].height, r["full_rect"].height, True), 2)
            page.draw_rect(fitz.Rect(40, 40, 135, 260), color=None,
                           fill=(.9, .9, .9), overlay=False)
            self.assertEqual([r["column"] for r in measured_regions(page, "zohar")], [1, 2, 3])

    def test_zohar_missing_translation_boundary_stops(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            page.insert_text((510, 70), "study text", fontsize=11)
            with self.assertRaisesRegex(ValueError, "Zohar translation boundary"):
                measured_regions(page, "zohar")

    def test_split_subjects_keep_unique_order_before_tefilot(self):
        topics = map_topics()
        ids = [t["id"] for t in topics]
        self.assertEqual([t["order"] for t in topics], list(range(len(topics))))
        start = ids.index("sifrei_rabeinu")
        self.assertEqual(ids[start:start + 4], ["sifrei_rabeinu", "sefer_hamidot",
                                              "shemot_tzadikim", "likutey_tefilot"])
    def test_transparent_dark_fill_is_detected_as_rendered_gray(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            region = fitz.Rect(350, 240, 490, 440)
            page.draw_rect(region, fill=(.13, .12, .125), color=None, fill_opacity=.12)
            self.assertLess(page.get_drawings()[0]["fill"][0], .2)
            detected = gray_backgrounds(page)
            self.assertEqual(len(detected), 1)
            self.assertTrue(detected[0].contains(fitz.Point(400, 300)))

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

    def test_low_source_line_uses_blank_space_in_same_lower_corner(self):
        for right in (False, True):
            with self.subTest(right=right), fitz.open() as doc:
                page = doc.new_page(width=468, height=680)
                # A raster/vector line across the entire original search row.
                page.draw_rect(fitz.Rect(0, 648, 468, 651), fill=(0, 0, 0))
                canvas = Canvas(page)
                rect = canvas.lower_corner(right=right)
                self.assertEqual(rect.x0, 428 if right else 28)
                self.assertGreater(rect.y0, 651)
                self.assertLess(rect.y1, 680)
                self.assertEqual(page.rect, fitz.Rect(0, 0, 468, 680))
                self.assertFalse(canvas.clear(rect))

    def test_fully_occupied_corner_still_stops(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            page.draw_rect(fitz.Rect(0, 640, 110, 680), fill=(0, 0, 0))
            with self.assertRaisesRegex(ValueError, "No safe lower left corner"):
                Canvas(page).lower_corner()

    def test_source_dimensions_allow_orientation_only_not_added_margins(self):
        with fitz.open() as source, fitz.open() as result:
            original = source.new_page(width=468, height=680)
            rotated = result.new_page(width=680, height=468)
            validate_source_dimensions(rotated, original)
            enlarged = result.new_page(width=468, height=776)
            with self.assertRaisesRegex(ValueError, "added margins are forbidden"):
                validate_source_dimensions(enlarged, original)

    def test_controls_keep_approved_anchors_and_full_size_without_collision(self):
        cases = [
            (468, 680, {"previous": (308, 8, 458, 44), "next": (10, 636, 160, 672),
                        "return": (171.5, 5, 296.5, 37)}),
            (680, 468, {"previous": (520, 8, 670, 44), "next": (10, 424, 160, 460),
                        "return": (277.5, 5, 402.5, 37)}),
        ]
        for width, height, expected in cases:
            with self.subTest(width=width), fitz.open() as doc:
                page = doc.new_page(width=width, height=height)
                canvas = Canvas(page)
                for kind, coordinates in expected.items():
                    self.assertEqual(canvas.button(kind), fitz.Rect(coordinates))

    def test_checkbox_collision_shrinks_at_same_corner_and_reserves_click_area(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            for kind, checkbox in (("previous", (310, 12, 319, 21)),
                                   ("next", (12, 640, 21, 649))):
                with self.subTest(kind=kind):
                    canvas = Canvas(page)
                    canvas.reserve(fitz.Rect(checkbox), checkbox=True)
                    rect = canvas.button(kind)
                    self.assertEqual(rect, button_rect(page, kind, .875))
                    self.assertFalse(rect.intersects(fitz.Rect(checkbox)))
                    self.assertFalse(canvas.clear(rect))

    def test_source_ink_alone_cannot_trigger_shrinking_or_relocation(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            # The source remains visible through the original translucent asset.
            page.draw_rect(fitz.Rect(310, 12, 319, 21), fill=(0, 0, 0))
            self.assertEqual(Canvas(page).button("previous"), fitz.Rect(308, 8, 458, 44))

    def test_dense_footer_cannot_move_next_to_top_or_side(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            page.draw_rect(fitz.Rect(10, 420, 670, 467), fill=(0, 0, 0))
            self.assertEqual(Canvas(page).button("next"), fitz.Rect(10, 424, 160, 460))

    def test_crowded_header_keeps_transparent_return_at_top_center(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            page.draw_rect(button_rect(page, "return"), fill=(0, 0, 0))
            self.assertEqual(Canvas(page).button("return"), fitz.Rect(171.5, 5, 296.5, 37))

    def test_transparency_does_not_permit_covering_other_navigation(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            canvas = Canvas(page)
            canvas.reserve(fitz.Rect(310, 12, 319, 21))
            with self.assertRaisesRegex(ValueError, "review required"):
                canvas.button("previous")

    def test_checkbox_at_corner_cannot_force_more_than_25_percent_shrink(self):
        with fitz.open() as doc:
            page = doc.new_page(width=468, height=680)
            canvas = Canvas(page)
            canvas.reserve(fitz.Rect(449, 10, 457, 18), checkbox=True)
            with self.assertRaisesRegex(ValueError, "review required"):
                canvas.button("previous")

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
