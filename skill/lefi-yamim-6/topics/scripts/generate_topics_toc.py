#!/usr/bin/env python3
import argparse
import colorsys
import sys
from pathlib import Path

import fitz
from PIL import Image, ImageDraw, ImageFont


TOPICS = [
    'הדף היומי', 'פרשת השבוע', 'נביאים וכתובים', 'משנה', 'שולחן ערוך',
    'משנה ברורה', 'זוהר', 'תהילים', 'ליקוטי מוהר"ן', 'ליקוטי הלכות',
    'ספרי רבינו על פי הסדר', 'שמות הצדיקים / ספר המידות', 'ליקוטי תפילות',
    'תיקון הכללי', 'השלמות',
]
TOPIC_RECTS = [
    (242.7, 145.8, 435.9, 178.8), (33.4, 145.8, 226.6, 178.8),
    (242.7, 187.2, 435.9, 219.9), (33.4, 187.2, 226.6, 219.9),
    (242.7, 229.0, 435.9, 261.1), (33.4, 229.0, 226.6, 261.1),
    (242.7, 270.5, 435.9, 302.9), (33.4, 270.5, 226.6, 302.9),
    (242.7, 311.6, 435.9, 344.7), (33.4, 311.6, 226.6, 344.7),
    (242.7, 353.1, 435.9, 386.1), (33.4, 353.1, 226.6, 386.1),
    (242.7, 394.5, 435.9, 427.2), (33.4, 394.5, 226.6, 427.2),
    (138.0, 436.3, 331.3, 468.7),
]
PDF_PAGE_W = 468
PDF_PAGE_H = 680.25


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


def hide_absent_topic_buttons(img, absent_topics, background):
    """Remove misleading static topic buttons for explicitly absent holiday topics."""
    draw = ImageDraw.Draw(img)
    sx = img.width / PDF_PAGE_W
    sy = img.height / PDF_PAGE_H
    for topic, rect in zip(TOPICS, TOPIC_RECTS):
        if topic not in absent_topics:
            continue
        x1, y1, x2, y2 = rect
        pad_x, pad_y = 7 * sx, 7 * sy
        draw.rounded_rectangle(
            (x1 * sx - pad_x, y1 * sy - pad_y, x2 * sx + pad_x, y2 * sy + pad_y),
            radius=12 * sx,
            fill=background,
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--template', required=True)
    parser.add_argument('--parsha', required=True)
    parser.add_argument('--date', required=True)
    parser.add_argument('--color', required=True)
    parser.add_argument('--output-png', required=True)
    parser.add_argument('--output-pdf', required=True)
    parser.add_argument('--absent-topic', action='append', default=[])
    args = parser.parse_args()

    absent_topics = set(args.absent_topic)
    unknown_absent = absent_topics - set(TOPICS)
    if unknown_absent:
        raise SystemExit('Unknown absent topic(s): ' + ', '.join(sorted(unknown_absent)))

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

    title_font = ImageFont.truetype(r'C:\Windows\Fonts\Guttman Hatzvi-Bold.TTF', 78)
    subtitle_font = ImageFont.truetype(r'C:\Windows\Fonts\DAVIDB.TTF', 31)
    centered(draw, img.width, args.parsha, 43, title_font, title_rgb)
    centered(draw, img.width, f'לפי נושאים • {args.date}', 172, subtitle_font, subtitle_rgb)
    hide_absent_topic_buttons(img, absent_topics, background)

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
    print(f'ACTIVE_TOPIC_BUTTONS={len(TOPICS) - len(absent_topics)}')


if __name__ == '__main__':
    main()
