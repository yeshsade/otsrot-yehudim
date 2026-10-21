#!/usr/bin/env python3
import argparse
import colorsys
import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "days/scripts"))

DEFAULT_BRAND = "אוצרות יהודים"


def require_deps():
    try:
        from PIL import Image  # noqa: F401
    except ImportError:
        raise SystemExit("Missing required Python package: Pillow")


def rgba(hex_color, alpha=255):
    hex_color = hex_color.strip().lstrip("#")
    if len(hex_color) != 6:
        raise ValueError("color must be a 6-digit hex value")
    return tuple(int(hex_color[i : i + 2], 16) for i in (0, 2, 4)) + (alpha,)


def blend(c1, c2, t):
    return tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(4))


def clamp(value, low=0.0, high=1.0):
    return max(low, min(high, value))


def hex_from_hls(h, l, s, alpha=255):
    r, g, b = colorsys.hls_to_rgb(h, clamp(l), clamp(s))
    return (int(r * 255), int(g * 255), int(b * 255), alpha)


def palette_from_color(hex_color):
    r, g, b, _a = rgba(hex_color)
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    s = clamp(max(s, 0.35), 0.25, 0.7)
    return {
        "top": (255, 255, 255, 255),
        "bottom": hex_from_hls(h, 0.96, s * 0.4),
        "header": hex_from_hls(h, 0.86, s * 0.58, 248),
        "header_line": hex_from_hls(h, 0.66, s * 0.78, 205),
        "border": hex_from_hls(h, 0.72, s * 0.84),
        "inner": hex_from_hls(h, 0.9, s * 0.48, 220),
        "brand": hex_from_hls(h, 0.28, s * 0.95),
        "title": hex_from_hls(h, 0.24, s),
        "date": hex_from_hls(h, 0.34, s * 0.8),
        "ornament": hex_from_hls(h, 0.38, s * 0.85, 215),
        "grid": hex_from_hls(h, 0.93, s * 0.18),
        "preview_bg": hex_from_hls(h, 0.97, s * 0.14)[:3],
    }


def rtl(text):
    import sys
    if sys.platform.startswith("win"):
        return str(text)[::-1]
    return str(text)


from typography import load_font, ensure_text


def get_font(size, scale, candidates):
    # Compatibility for callers of the original cover-label helper.
    role = "heading" if any("hatzvi" in str(p).lower() for p in candidates) else "bold"
    return load_font(role, size * scale)


def font_set(scale):
    return {"title": load_font("heading", 105 * scale),
            "date": load_font("heading", 47 * scale),
            "brand": load_font("heading", 38 * scale)}


def draw_center(draw, width, text, font, y, fill, scale, stroke=None, stroke_width=0):
    shaped = rtl(text)
    bbox = draw.textbbox((0, 0), shaped, font=font, stroke_width=stroke_width * scale)
    x = (width * scale - (bbox[2] - bbox[0])) / 2
    draw.text(
        (x, y * scale),
        shaped,
        font=font,
        fill=fill,
        stroke_width=stroke_width * scale,
        stroke_fill=stroke or fill,
    )


def rounded_gradient(size, box, radius, top, bottom):
    from PIL import Image, ImageDraw

    grad = Image.new("RGBA", size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for y in range(int(box[1]), int(box[3]) + 1):
        t = (y - box[1]) / max(1, box[3] - box[1])
        gd.line([(int(box[0]), y), (int(box[2]), y)], fill=blend(top, bottom, t), width=1)
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=radius, fill=255)
    return Image.composite(grad, Image.new("RGBA", size, (0, 0, 0, 0)), mask)


def draw_clean_ornament(draw, width, y, color, scale):
    cx = width * scale / 2
    y = y * scale
    s = scale
    draw.line([(cx - 230 * s, y), (cx - 48 * s, y)], fill=color, width=3 * s)
    draw.line([(cx + 48 * s, y), (cx + 230 * s, y)], fill=color, width=3 * s)
    draw.ellipse([cx - 18 * s, y - 18 * s, cx + 18 * s, y + 18 * s], outline=color, width=3 * s)
    draw.polygon([(cx, y - 10 * s), (cx + 10 * s, y), (cx, y + 10 * s), (cx - 10 * s, y)], fill=color)
    for side in (-1, 1):
        x = cx + side * 70 * s
        start = 200 if side < 0 else 20
        end = 340 if side < 0 else 160
        draw.arc([x - 32 * s, y - 20 * s, x + 32 * s, y + 20 * s], start, end, fill=color, width=3 * s)


def fit_font_to_width(draw, text, font, max_width, scale, min_size, candidates):
    ensure_text(font.path, text)
    shaped = rtl(text)
    current = font
    size = font.size // scale
    while size > min_size:
        bbox = draw.textbbox((0, 0), shaped, font=current, stroke_width=scale)
        if bbox[2] - bbox[0] <= max_width * scale:
            return current
        size -= 2
        current = font.font_variant(size=size * scale)
    return current


def parse_label(args):
    if args.parsha and args.date:
        return args.parsha.strip(), args.date.strip()
    if args.label:
        parts = [part.strip() for part in args.label.split("/", 1)]
        if len(parts) == 2 and all(parts):
            return parts[0], parts[1]
    raise SystemExit("Pass --label \"parsha / date\" or both --parsha and --date.")


def make_label(parsha, date, output, color, brand=DEFAULT_BRAND, preview=None):
    require_deps()
    from PIL import Image, ImageDraw, ImageFilter

    # This is the final transparent label size. attach-label uses the image's
    # own aspect ratio when --label-crop none is passed.
    width, height, scale = 1100, 620, 2
    palette = palette_from_color(color)
    img = Image.new("RGBA", (width * scale, height * scale), (0, 0, 0, 0))
    box = [68 * scale, 70 * scale, (width - 68) * scale, (height - 76) * scale]
    radius = 44 * scale

    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle(
        [box[0] + 20 * scale, box[1] + 24 * scale, box[2] + 20 * scale, box[3] + 24 * scale],
        radius=radius,
        fill=(1, 34, 45, 66),
    )
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(24 * scale)))
    img.alpha_composite(rounded_gradient(img.size, box, radius, palette["top"], palette["bottom"]))
    draw = ImageDraw.Draw(img)

    header = [92 * scale, 94 * scale, (width - 92) * scale, 218 * scale]
    draw.rounded_rectangle(header, radius=22 * scale, fill=palette["header"], outline=palette["header_line"], width=2 * scale)
    draw.rounded_rectangle(
        [112 * scale, 109 * scale, (width - 112) * scale, 203 * scale],
        radius=16 * scale,
        outline=(255, 255, 255, 150),
        width=2 * scale,
    )
    draw.rounded_rectangle(box, radius=radius, outline=palette["border"], width=5 * scale)
    draw.rounded_rectangle(
        [108 * scale, 242 * scale, (width - 108) * scale, (height - 112) * scale],
        radius=28 * scale,
        outline=palette["inner"],
        width=2 * scale,
    )
    draw_clean_ornament(draw, width, 396, palette["ornament"], scale)

    shine = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sh = ImageDraw.Draw(shine)
    for i in range(190):
        alpha = max(0, 36 - i // 5)
        sh.line([(106 * scale + i * scale, 242 * scale), (340 * scale + i * scale, 98 * scale)], fill=(255, 255, 255, alpha), width=2 * scale)
    img.alpha_composite(shine)
    draw = ImageDraw.Draw(img)

    fonts = font_set(scale)
    title_candidates = [
        r"C:\Windows\Fonts\Guttman Hatzvi-Bold.TTF",
        r"C:\Windows\Fonts\DAVIDB.TTF",
        r"C:\Windows\Fonts\arialbd.ttf",
    ]
    fonts["title"] = fit_font_to_width(draw, parsha, fonts["title"], 720, scale, 58, title_candidates)
    fonts["date"] = fit_font_to_width(draw, date, fonts["date"], 690, scale, 28, title_candidates)

    draw_center(draw, width, brand, fonts["brand"], 124, palette["brand"], scale, stroke=(255, 255, 255, 220), stroke_width=1)
    draw_center(draw, width, parsha, fonts["title"], 248, palette["title"], scale, stroke=(255, 255, 255, 245), stroke_width=1)
    draw_center(draw, width, date, fonts["date"], 440, palette["date"], scale)

    img = img.resize((width, height), Image.Resampling.LANCZOS)
    output.parent.mkdir(parents=True, exist_ok=True)
    img.save(output)

    if preview:
        preview_img = Image.new("RGB", (width + 140, height + 140), palette["preview_bg"])
        pd = ImageDraw.Draw(preview_img)
        for y in range(0, height + 140, 36):
            pd.line([(0, y), (width + 140, y)], fill=palette["grid"][:3], width=1)
        for x in range(0, width + 140, 36):
            pd.line([(x, 0), (x, height + 140)], fill=palette["grid"][:3], width=1)
        preview_img.paste(img, (70, 70), img)
        preview.parent.mkdir(parents=True, exist_ok=True)
        preview_img.save(preview)


def unique_path(path):
    if not path.exists():
        return path
    stem = path.with_suffix("")
    suffix = path.suffix
    i = 2
    while True:
        candidate = Path(f"{stem}_v{i}{suffix}")
        if not candidate.exists():
            return candidate
        i += 1


def main():
    parser = argparse.ArgumentParser(description="Generate the local clean 'Otzarot Yehudim' weekly label PNG.")
    parser.add_argument("--label", help="Combined label text, for example: 'parsha / date'.")
    parser.add_argument("--parsha", help="First line, for example: Parshat Korach.")
    parser.add_argument("--date", help="Second line, for example: date range.")
    parser.add_argument("--color", default="6DCFF6", help="Main weekly hex color from COVER_CIRCLE_HEX.")
    parser.add_argument("--brand", default=DEFAULT_BRAND)
    parser.add_argument("--output", help="Transparent PNG output path.")
    parser.add_argument("--preview", help="Optional preview PNG path with a pale background.")
    args = parser.parse_args()

    parsha, date = parse_label(args)
    out = Path(args.output) if args.output else unique_path(Path.cwd() / "local_weekly_label.png")
    preview = Path(args.preview) if args.preview else None
    try:
        make_label(parsha, date, out, args.color, brand=args.brand, preview=preview)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)

    print(f"LABEL_PNG={out}")
    if preview:
        print(f"PREVIEW_PNG={preview}")
