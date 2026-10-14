#!/usr/bin/env python3
import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "days" / "scripts"))
import generate_local_label as base


def write_preview(image, color, preview):
    preview = Path(preview)
    palette = base.palette_from_color(color)
    width, height = image.size
    canvas = Image.new("RGB", (width + 140, height + 140), palette["preview_bg"])
    draw = ImageDraw.Draw(canvas)
    for y in range(0, height + 140, 36):
        draw.line([(0, y), (width + 140, y)], fill=palette["grid"][:3], width=1)
    for x in range(0, width + 140, 36):
        draw.line([(x, 0), (x, height + 140)], fill=palette["grid"][:3], width=1)
    canvas.paste(image, (70, 70), image)
    preview.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(preview)


def make_label(parsha, date, color, edition, output, preview=None, brand=base.DEFAULT_BRAND):
    output = Path(output)
    base.make_label(parsha, date, output, color, brand=brand, preview=None)
    image = Image.open(output).convert("RGBA")
    draw = ImageDraw.Draw(image)
    is_days = edition == "days"
    text = "לפי ימים" if is_days else "לפי נושאים"
    font = ImageFont.truetype(r"C:\Windows\Fonts\DAVIDB.TTF", 54)
    shaped = base.rtl(text)
    box = draw.textbbox((0, 0), shaped, font=font)
    width = box[2] - box[0]
    x = (image.width - width) // 2
    y = 540
    ink = base.palette_from_color(color)["brand"]
    if is_days:
        draw.rounded_rectangle((x - 34, y - 7, x + width + 34, y + 64), radius=24, fill=ink)
        draw.text((x, y), shaped, font=font, fill="white")
    else:
        draw.text((x, y), shaped, font=font, fill=ink)
    image.save(output)
    if preview:
        write_preview(image, color, preview)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--edition", choices=["days", "topics"], required=True)
    parser.add_argument("--parsha", required=True)
    parser.add_argument("--date", required=True)
    parser.add_argument("--color", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--preview")
    parser.add_argument("--brand", default=base.DEFAULT_BRAND)
    args = parser.parse_args()
    make_label(args.parsha, args.date, args.color, args.edition, args.output, args.preview, args.brand)
    print(f"LABEL_PNG={args.output}")
    if args.preview:
        print(f"PREVIEW_PNG={args.preview}")


if __name__ == "__main__":
    main()