"""Visual commentary boundaries, mixed study styles and source-based coverage."""
from pathlib import Path
import sys
import unittest

import fitz
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'days/scripts')]
from shulchan import measure_shulchan, audit_shulchan_coverage
from placement import box_count


class ShulchanTests(unittest.TestCase):
    def page_with_boundary(self, doc, *, portrait=False, short_column=False, gray_column=False):
        page = doc.new_page(width=468 if portrait else 680, height=680 if portrait else 468)
        width, height = page.rect.width, page.rect.height
        caption_y = 500 if portrait else 350
        xs = [270, 55] if portrait else [510, 360, 205, 55]
        if short_column or gray_column:
            top = 55 if gray_column else 140
            page.draw_rect(fitz.Rect(45, top, 185, caption_y - 10), fill=(.85, .85, .85), color=None)
        for x in xs:
            page.insert_text((x, 65), 'Chapter heading', fontsize=11.1, fontname='tibo')
            for y in range(90, int(caption_y) - 20, 35):
                page.insert_text((x, y), 'Regular law', fontsize=11.6, fontname='cour')
            # The slightly larger style is still learning, and only part of it.
            page.insert_text((x, caption_y - 17), 'Rema law', fontsize=11.9, fontname='hebo')
        caption = ROOT / 'days/assets/vcheck-templates/shulchan_aruch_right.png'
        with Image.open(caption) as image:
            iw, ih = image.size
        for x in ([205] if portrait else [170, 510]):
            page.insert_image(fitz.Rect(x, caption_y, x + iw / 2.2, caption_y + ih / 2.2), filename=str(caption))
        for x in ([55, 270] if portrait else [55, 360]):
            for y in range(int(caption_y) + 35, int(height) - 22, 15):
                page.insert_text((x, y), 'Commentary below the visual boundary', fontsize=7)
        return page

    def test_mixed_font_study_is_complete_in_all_four_columns(self):
        with fitz.open() as doc:
            page = self.page_with_boundary(doc)
            regions = measure_shulchan(page)['regions']
            self.assertEqual([r['column'] for r in regions], [1, 2, 3, 4])
            coverage = audit_shulchan_coverage(page, regions)
            for col in coverage['columns']:
                self.assertLess(col['learning_rect'][1], 65)
                self.assertGreater(col['learning_rect'][3], 330)
                self.assertLess(col['learning_rect'][3], 350)

    def test_short_column_uses_measured_thirds_instead_of_fixed_twelve_boxes(self):
        with fitz.open() as doc:
            page = self.page_with_boundary(doc, short_column=True)
            regions = measure_shulchan(page)['regions']
            counts = {r['column']: box_count(r['rect'].height, r['full_rect'].height, True) for r in regions}
            self.assertEqual(counts, {1: 3, 2: 3, 3: 3, 4: 1})
            audit_shulchan_coverage(page, regions)

    def test_fully_gray_column_does_not_require_a_checkbox(self):
        with fitz.open() as doc:
            page = self.page_with_boundary(doc, gray_column=True)
            regions = measure_shulchan(page)['regions']
            self.assertEqual([r['column'] for r in regions], [1, 2, 3])
            audit_shulchan_coverage(page, regions)

    def test_source_coverage_rejects_missing_column_and_truncated_study(self):
        with fitz.open() as doc:
            page = self.page_with_boundary(doc)
            regions = measure_shulchan(page)['regions']
            with self.assertRaisesRegex(ValueError, 'expected active columns'):
                audit_shulchan_coverage(page, regions[:-1])
            regions[0]['rect'].y1 -= 40
            with self.assertRaisesRegex(ValueError, 'omits study glyphs'):
                audit_shulchan_coverage(page, regions)

    def test_unknown_boundary_fails_without_selecting_the_largest_font(self):
        with fitz.open() as doc:
            page = doc.new_page(width=680, height=468)
            page.insert_text((510, 100), 'Law', fontsize=11.6)
            page.insert_text((510, 140), 'Larger style', fontsize=11.9, fontname='hebo')
            with self.assertRaisesRegex(ValueError, "Be'er Hagolah boundary"):
                measure_shulchan(page)

    def test_portrait_page_retains_three_boxes_per_active_column(self):
        with fitz.open() as doc:
            page = self.page_with_boundary(doc, portrait=True)
            regions = measure_shulchan(page)['regions']
            self.assertEqual([r['column'] for r in regions], [1, 2])
            self.assertTrue(all(box_count(r['rect'].height, r['full_rect'].height, False) == 3 for r in regions))
            audit_shulchan_coverage(page, regions)


if __name__ == '__main__':
    unittest.main()
