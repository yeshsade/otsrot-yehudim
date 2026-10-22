"""Prior-week reference pages retain navigation without any progress widgets.

Fixtures isolate four previous-week topics. Discovery and the weekly external
checker are supplied for the small source; writing, maps, links and validation
use the real pipeline in both editions.
"""
import contextlib
import io
import json
from pathlib import Path
import random
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import fitz
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'days/scripts')]
import build_booklet as builder
from validate_booklet import validate_progress_scope


class PreviousWeekCompletionTests(unittest.TestCase):
    def test_both_editions_preserve_all_prior_topics_without_progress(self):
        topics = ['gemara', 'mishnah_berurah', 'likutey_moharan', 'likutey_halakhot']
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            interior, cover = folder / 'source.pdf', folder / 'cover.pdf'
            with fitz.open() as source:
                for n in range(8):
                    page = source.new_page(width=468, height=680)
                    for x in (60, 260):
                        for y in (70, 90, 110):
                            page.insert_text((x, y), f'Study source {n + 1}', fontsize=11)
                source.save(interior)
            image = Image.frombytes('RGB', (768, 768), random.Random(9).randbytes(768 * 768 * 3))
            buffer = io.BytesIO()
            image.save(buffer, format='PNG')
            with fitz.open() as source:
                for _ in range(4):
                    source.new_page(width=468, height=680)
                source[0].insert_image(source[0].rect, stream=buffer.getvalue())
                source.save(cover)
            owners = {i + 1: topic for i, topic in enumerate(topics * 2)}
            model = {'owners': owners, 'days': {'sunday': [1, 2, 3, 4]},
                     'contexts': {5: topics[0], 6: topics[1], 7: topics[2], 8: topics[3]},
                     'tehillim': {}}
            answers = {'booklet': {'issue': 'test', 'parsha': 'תולדות',
                       'date_label': 'כ״ח חשון', 'week_start': '2026-11-08'}}
            checker = folder / 'weekly-checker-placeholder.py'
            checker.write_text('# A full-week checker does not apply to this fixture.\n')
            real_run = subprocess.run

            def run(command, **kwargs):
                if str(command[1]).endswith('generate_topics_toc.py'):
                    return real_run(command, **kwargs)
                return SimpleNamespace(returncode=0, stdout='✓ תקין', stderr='')

            for edition in ('days', 'topics'):
                with self.subTest(edition=edition), \
                     patch.object(builder, 'discover', return_value={}), \
                     patch.object(builder, 'model', return_value=model), \
                     patch.object(builder, 'DAYS', [('sunday', 'יום ראשון')]), \
                     patch.object(builder, 'IDS', topics), \
                     patch.object(builder, 'gemara_identity', return_value={'amud': 'a', 'key': 'amud_a'}), \
                     patch.object(builder.legacy, 'detect_circle_hex', return_value='#C3A181'), \
                     patch.object(builder.subprocess, 'run', side_effect=run), \
                     contextlib.redirect_stdout(io.StringIO()):
                    output = builder.build(interior, cover, answers, folder / edition, checker, edition)
                with fitz.open(output) as pdf:
                    progress = json.loads(pdf.embfile_get(builder.MAP_NAME))
                    prior = [row for row in progress['pages'] if row['week_offset'] == -1]
                    self.assertEqual({row['source_page'] for row in prior}, {5, 6, 7, 8})
                    self.assertEqual(len(progress['checks']), 14)
                    self.assertTrue(all(check['week_offset'] == 0 for check in progress['checks']))
                    for row in prior:
                        page = pdf[row['page'] - 1]
                        self.assertEqual(list(page.widgets() or ()), [])
                        self.assertIn(f"Study source {row['source_page']}", page.get_text())
                        current = next(r for r in progress['pages'] if r.get('source_page') == row['source_page'] - 4)
                        self.assertTrue(any(link.get('page') == current['page'] - 1 for link in page.get_links()))
                        self.assertTrue(any(link.get('page') == row['page'] - 1 for link in pdf[current['page'] - 1].get_links()))
                audit = json.loads((output.parent / 'validation.json').read_text())
                self.assertTrue(audit['previous_week_pages_have_no_checkboxes'])
                self.assertTrue(audit['progress_checks_cover_current_week_only'])

    def test_validator_rejects_prior_field_even_if_mislabeled_current(self):
        rows = [{'page': 1, 'week_offset': 0}, {'page': 2, 'week_offset': -1}]
        cases = [
            {'pages': [2], 'week_offset': -1},
            {'pages': [2], 'week_offset': 0},
            {'pages': [1, 2], 'week_offset': 0},
            {'pages': [1], 'week_offset': -1},
        ]
        for item in cases:
            for source in ('map', 'placements'):
                with self.subTest(item=item, source=source):
                    progress = {'checks': [item] if source == 'map' else []}
                    placements = [item] if source == 'placements' else []
                    with self.assertRaisesRegex(ValueError, 'Previous-week completion'):
                        validate_progress_scope(progress, placements, rows)
        validate_progress_scope({'checks': [{'pages': [1], 'week_offset': 0}]}, [], rows)


if __name__ == '__main__':
    unittest.main()
