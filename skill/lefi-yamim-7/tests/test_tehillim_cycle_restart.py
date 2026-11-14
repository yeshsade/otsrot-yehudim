"""Post-Psalm prayers may precede a new cycle on the same physical page."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import fitz

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import source_model


class TehillimCycleRestartTests(unittest.TestCase):
    def test_bounded_prayers_preserve_new_cycle_and_seven_days(self):
        with fitz.open() as doc:
            for _ in range(7):
                p = doc.new_page(width=680, height=468)
                for x in (520, 365, 195, 50):
                    for y in range(100, 381, 20):
                        p.insert_text((x, y), 'Psalm or prayer material', fontsize=10)

            def markers(page):
                day = source_model.DAYS[page.number][0]
                x = 220 if day == 'friday' else 550
                return [(day, fitz.Rect(x, 60, x+20, 70))]

            review = {'boundaries_reviewed': True, 'exclude_after_psalms': [
                {'page': 5, 'column': 3, 'end_y_pt': 125},
                {'page': 6, 'column': 1, 'end_y_pt': 0, 'end_column': 2}]}
            with patch.object(source_model, 'weekly_markers', side_effect=markers):
                regions = source_model.tehillim_regions(doc, list(range(1, 8)), review)
            self.assertEqual(set(regions), {day for day, _ in source_model.DAYS})
            thursday = [r for r in regions['thursday'] if r['source_page'] == 5]
            self.assertEqual({r['column'] for r in thursday}, {1, 2, 3})
            self.assertLess(next(r for r in thursday if r['column'] == 3)['rect'][3], 130)
            friday = [r for r in regions['friday'] if r['source_page'] == 6]
            self.assertEqual({r['column'] for r in friday}, {3, 4})
            self.assertEqual({r['column'] for r in regions['saturday']}, {1, 2, 3, 4})
