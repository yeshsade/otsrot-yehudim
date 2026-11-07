import sys
import unittest
from pathlib import Path
import fitz

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from source_staging import stage_cover_completions


class CoverCompletionTests(unittest.TestCase):
    def documents(self):
        source, cover = fitz.open(), fitz.open()
        source.new_page().insert_text((40, 40), 'Interior')
        for n in range(4):
            cover.new_page(width=400, height=600).insert_text((40, 40), f'Cover {n+1}')
        return source, cover

    def test_cover_completion_preserves_origin_text_dimensions_and_review(self):
        source, cover = self.documents()
        base = {'source_sha256': 'a', 'cover_sha256': 'b', 'total_original_pages': 1}
        answers = {**base, 'previous_week': {'gemara': {'input': 'cover', 'page': 3}}}
        staged, resolved, origins = stage_cover_completions(base, answers, source, cover)
        self.assertEqual(source[1].get_text(), cover[2].get_text())
        self.assertEqual(source[1].rect, cover[2].rect)
        self.assertEqual(source[0].get_text().strip(), 'Interior')
        self.assertEqual(resolved['previous_week']['gemara'], 2)
        self.assertEqual(origins[2]['page'], 3)
        self.assertEqual(staged['total_original_pages'], 2)
        self.assertEqual(base['total_original_pages'], 1)
        self.assertEqual(answers['previous_week']['gemara'], {'input': 'cover', 'page': 3})
        source.close();cover.close()

    def test_invalid_or_conflicting_selectors_do_not_change_source(self):
        for previous in ({'gemara': {'input': 'cover', 'page': 4}},
                         {'gemara': {'input': 'cover', 'page': 3},
                          'likutey_moharan': {'input': 'cover', 'page': 3}}):
            source, cover = self.documents()
            base = {'source_sha256': 'a', 'cover_sha256': 'b'}
            with self.assertRaises(ValueError):
                stage_cover_completions(base, {**base, 'previous_week': previous}, source, cover)
            self.assertEqual(len(source), 1)
            source.close();cover.close()
