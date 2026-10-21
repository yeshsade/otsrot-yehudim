"""Exercise shared Gemara fields through build(), manifests and real validation.

The tiny source fixture isolates one amud split across two physical pages and
an independent other amud. Source discovery/identity are supplied explicitly;
the full-week external checker is mocked because this is not a weekly booklet.
The production PDF writer, grouping, shared field join and validator are real.
"""
import contextlib
import io
import json
from pathlib import Path
import random
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
import validate_booklet
from gemara_checkboxes import set_checkbox_state


class SharedGemaraBuildTests(unittest.TestCase):
    def test_build_split_amud_preserves_occurrences_and_validates_saved_pdf(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            interior, cover = folder / 'source.pdf', folder / 'cover.pdf'
            with fitz.open() as source:
                for text in ['Amud A, first part', 'Amud A, second part', 'Amud B']:
                    page = source.new_page(width=468, height=680)
                    page.insert_text((60, 100), text, fontsize=12)
                source.save(interior)
            # A deterministic cover image keeps the real output-size guard
            # meaningful even with generated TOC/day-card assets in a tiny PDF.
            image = Image.frombytes('RGB', (768, 768), random.Random(7).randbytes(768 * 768 * 3))
            buffer = io.BytesIO()
            image.save(buffer, format='PNG')
            with fitz.open() as doc:
                for _ in range(4):
                    doc.new_page(width=468, height=680)
                doc[0].insert_image(doc[0].rect, stream=buffer.getvalue())
                doc.save(cover)
            checker = folder / 'weekly-checker-placeholder.py'
            checker.write_text('# Full-week checker is outside this tiny fixture.\n')
            answers = {'booklet': {'issue': 'test', 'parsha': 'תולדות',
                'date_label': 'כ״ח חשון', 'week_start': '2026-11-08'}}
            model = {'owners': {1: 'gemara', 2: 'gemara', 3: 'gemara'},
                     'days': {'sunday': [1, 2, 3]}, 'contexts': {}, 'tehillim': {}}

            def identity(page):
                amud = 'a' if page.number < 2 else 'b'
                return {'amud': amud, 'key': 'daf2_' + amud}

            with patch.object(builder, 'discover', return_value={}), \
                 patch.object(builder, 'model', return_value=model), \
                 patch.object(builder, 'DAYS', [('sunday', 'יום ראשון')]), \
                 patch.object(builder, 'gemara_identity', side_effect=identity), \
                 patch.object(builder.legacy, 'detect_circle_hex', return_value='#C3A181'), \
                 patch.object(builder.subprocess, 'run', return_value=SimpleNamespace(
                     returncode=0, stdout='✓ תקין', stderr='')) as external, \
                 patch.object(validate_booklet, 'validate', wraps=validate_booklet.validate) as validator, \
                 contextlib.redirect_stdout(io.StringIO()):
                output = builder.build(interior, cover, answers, folder / 'output', checker)
            self.assertEqual(validator.call_count, 1)
            self.assertEqual(external.call_count, 1)
            qa = output.parent / 'qa'
            placements = json.loads((qa / 'placement-manifest.json').read_text())
            self.assertEqual([item['pages'] for item in placements], [[5], [6], [7]])
            audit = json.loads((output.parent / 'validation.json').read_text())
            self.assertTrue(audit['passed'])
            self.assertEqual((audit['checkbox_widgets'], audit['logical_fields']), (3, 2))
            self.assertTrue(audit['all_internal_links_land_at_target_top'])
            with fitz.open(output) as pdf:
                # A TOC, short day card, and study page have different heights.
                internal = [link for page in pdf for link in page.get_links()
                            if link['kind'] == fitz.LINK_GOTO]
                self.assertTrue(internal)
                self.assertTrue(all(abs(link['to'].x) < .02 and abs(link['to'].y) < .02
                                    for link in internal))
                progress = json.loads(pdf.embfile_get(builder.MAP_NAME))
                self.assertEqual([item['pages'] for item in progress['checks']], [[5, 6], [7]])
                pages = [pdf[index] for index in (4, 5, 6)]
                widgets = [next(page.widgets()) for page in pages]
                self.assertEqual(pdf.xref_get_key(widgets[0].xref, 'Parent'),
                                 pdf.xref_get_key(widgets[1].xref, 'Parent'))
                set_checkbox_state(pdf, widgets[0].field_name, True)
                saved = pdf.tobytes()
            with fitz.open(stream=saved, filetype='pdf') as pdf:
                pages = [pdf[index] for index in (4, 5, 6)]
                self.assertEqual([next(page.widgets()).field_value for page in pages],
                                 ['Yes', 'Yes', 'Off'])


if __name__ == '__main__':
    unittest.main()
