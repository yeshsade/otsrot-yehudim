"""Original family identity, missing assets, and cover-resize regressions."""
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

from PIL import Image, ImageDraw, ImageFont, ImageChops

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'days/scripts'))
from typography import font_file, load_font, audit_profile
from generate_local_label import fit_font_to_width


class TypographyTests(unittest.TestCase):
    def test_uploaded_faces_and_complete_hebrew_date(self):
        report = audit_profile(['כ״ח חשון – ד׳ כסלו תשפ״ז'])
        self.assertEqual(report['roles']['bold']['family'], 'David')
        self.assertEqual(report['roles']['day_title']['family'], 'Guttman David')
        self.assertEqual(load_font('heading', 30).getname(), ('Guttman Hatzvi', 'Bold'))

    def test_missing_font_never_selects_installed_substitute(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(FileNotFoundError, 'Original font missing'):
                font_file('bold', folder)

    def test_renamed_system_font_is_not_the_original(self):
        with tempfile.TemporaryDirectory() as folder:
            source = ImageFont.truetype('DejaVuSans-Bold.ttf', 20).path
            shutil.copyfile(source, Path(folder) / 'davidbd.ttf')
            with self.assertRaisesRegex(ValueError, "differs from the owner's"):
                font_file('bold', folder)

    def test_resizing_date_preserves_original_family(self):
        font = load_font('heading', 60)
        draw = ImageDraw.Draw(Image.new('RGB', (700, 100), 'white'))
        fitted = fit_font_to_width(draw, 'כ״ח חשון – ד׳ כסלו תשפ״ז', font, 300, 1, 20, [])
        self.assertEqual(fitted.path, font.path)
        self.assertEqual(fitted.getname(), ('Guttman Hatzvi', 'Bold'))
        self.assertLess(fitted.size, font.size)

    def test_generic_labels_keep_original_29_pixel_letter_size(self):
        from toc_template import make_continuation_button_images
        with tempfile.TemporaryDirectory() as folder:
            original = make_continuation_button_images(Path(folder) / 'original')
            named = make_continuation_button_images(Path(folder) / 'named',
                labels={'previous': 'העמוד הקודם', 'next': 'העמוד הבא'},
                font_file=font_file('bold'))
            for kind in original:
                with Image.open(original[kind]) as a, Image.open(named[kind]) as b:
                    self.assertEqual(a.size, (600, 144))
                    self.assertIsNone(ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox())

    def test_navigation_background_keeps_original_transparency(self):
        from toc_template import make_continuation_button_images, make_return_button_image
        with tempfile.TemporaryDirectory() as folder:
            images = make_continuation_button_images(Path(folder))
            images['return'] = make_return_button_image(Path(folder) / 'return.png')
            for kind, path in images.items():
                with Image.open(path) as img:
                    self.assertEqual(img.mode, 'RGBA')
                    self.assertEqual(img.getpixel((0, 0))[3], 0)
                    self.assertEqual(img.getpixel((50, 40))[3], 128 if kind == 'return' else 84)


if __name__ == '__main__':
    unittest.main()
