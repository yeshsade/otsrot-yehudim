#!/usr/bin/env python3
import argparse
import colorsys
import sys
from pathlib import Path

import fitz
from PIL import Image, ImageDraw, ImageFont


def rtl(text):
    return text[::-1] if sys.platform.startswith('win') else text


def recolor_theme(img, color):
    color = color.strip().lstrip('#')
    rgb = tuple(int(color[i:i + 2], 16) / 255 for i in (0, 2, 4))
    target_h = colorsys.rgb_to_hsv(*rgb)[0]
    pixels = img.load()
    for y in range(img.height):
        for x in range(img.width):
            # Preserve the original WhatsApp and Gmail brand icons.
            if 245 <= x <= 525 and 1810 <= y <= 1995:
                continue
            r, g, b = pixels[x, y]
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            # Include pale blue antialiasing and shadows, not only saturated blue.
            if 0.48 <= h <= 0.60 and s >= 0.005:
                nr, ng, nb = colorsys.hsv_to_rgb(target_h, s, v)
                pixels[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255))


def centered(draw, width, text, y, font, fill):
    shaped = rtl(text)
    box = draw.textbbox((0, 0), shaped, font=font)
    x = (width - (box[2] - box[0])) / 2
    draw.text((x, y), shaped, font=font, fill=fill)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--template', required=True)
    parser.add_argument('--parsha', required=True)
    parser.add_argument('--date', required=True)
    parser.add_argument('--color', required=True)
    parser.add_argument('--output-png', required=True)
    parser.add_argument('--output-pdf', required=True)
    args = parser.parse_args()

    img = Image.open(args.template).convert('RGB')
    recolor_theme(img, args.color)
    draw = ImageDraw.Draw(img)
    background = img.getpixel((700, 30))
    draw.rectangle((165, 25, 1235, 224), fill=background)

    color = args.color.strip().lstrip('#')
    base_rgb = tuple(int(color[i:i + 2], 16) for i in (0, 2, 4))
    line_rgb = base_rgb
    title_rgb = tuple(max(0, round(channel * 0.36)) for channel in base_rgb)
    subtitle_rgb = tuple(max(0, round(channel * 0.70)) for channel in base_rgb)
    draw.rectangle((80, 151, 1320, 157), fill=line_rgb)

    def hebrew_font(size):
        for candidate in (r'C:\Windows\Fonts\Guttman Hatzvi-Bold.TTF', r'C:\Windows\Fonts\davidbd.ttf', r'C:\Windows\Fonts\arialbd.ttf'):
            if Path(candidate).exists():
                return ImageFont.truetype(candidate, size)
        return ImageFont.load_default()

    title_font = hebrew_font(78)
    subtitle_font = hebrew_font(31)
    centered(draw, img.width, args.parsha, 43, title_font, title_rgb)
    centered(draw, img.width, f'לפי נושאים • {args.date}', 172, subtitle_font, subtitle_rgb)

    output_png = Path(args.output_png)
    output_pdf = Path(args.output_pdf)
    output_png.parent.mkdir(parents=True, exist_ok=True)
    output_pdf.parent.mkdir(parents=True, exist_ok=True)
    img.save(output_png, optimize=True, compress_level=9)

    doc = fitz.open()
    page = doc.new_page(width=468, height=680.25)
    page.insert_image(page.rect, filename=str(output_png))
    doc.save(
        output_pdf,
        garbage=4,
        deflate=True,
        deflate_images=True,
        deflate_fonts=True,
        use_objstms=1,
    )
    print(f'TOC_PNG={output_png}')
    print(f'TOC_PDF={output_pdf}')


if __name__ == '__main__':
    main()
