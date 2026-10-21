#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import sys
from pathlib import Path


PAGE_W = 468.0
PAGE_H = 680.25
SCALE = 3
CANVAS_W = 1404
CANVAS_H = 2042

DEFAULT_ACCENT = "#6DCFF6"

INTERNAL_TOC_KEYS = {
    "sunday",
    "monday",
    "tuesday",
    "wednesday",
    "thursday",
    "friday",
    "shabbos",
    "parsha",
    "tikun",
}


def normalize_hex(value):
    text = str(value or DEFAULT_ACCENT).strip().lstrip("#")
    if len(text) != 6:
        return DEFAULT_ACCENT
    try:
        int(text, 16)
    except ValueError:
        return DEFAULT_ACCENT
    return "#" + text.upper()


def hex_to_rgb(value):
    value = normalize_hex(value).lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4))


def mix(c1, c2, t):
    a = hex_to_rgb(c1) if isinstance(c1, str) else c1
    b = hex_to_rgb(c2) if isinstance(c2, str) else c2
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def rtl(text):
    return str(text)[::-1] if sys.platform.startswith("win") else str(text)


def skill_root():
    return Path(__file__).resolve().parents[1]


def font_candidates(kind):
    from typography import font_file
    return [font_file("bold" if kind == "bold" else "regular")]


def get_font(ImageFont, kind, size):
    from typography import load_font
    return load_font("bold" if kind == "bold" else "regular", size)


def text_bbox(draw, text, fnt):
    return draw.textbbox((0, 0), rtl(text), font=fnt)


def text_width(draw, text, fnt):
    b = text_bbox(draw, text, fnt)
    return b[2] - b[0]


def wrap_text(draw, text, fnt, max_width):
    words = str(text).split()
    lines = []
    current = ""
    for word in words:
        candidate = word if not current else f"{current} {word}"
        if text_width(draw, candidate, fnt) <= max_width:
            current = candidate
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def draw_center_top(draw, text, cx, y, fnt, fill):
    vis = rtl(text)
    b = draw.textbbox((0, 0), vis, font=fnt)
    x = cx - (b[2] - b[0]) / 2 - b[0]
    draw.text((x, y - b[1]), vis, font=fnt, fill=fill)


def draw_center_box(draw, text, box, fnt, fill):
    vis = rtl(text)
    b = draw.textbbox((0, 0), vis, font=fnt)
    x1, y1, x2, y2 = box
    x = (x1 + x2) / 2 - (b[2] - b[0]) / 2 - b[0]
    y = (y1 + y2) / 2 - (b[3] - b[1]) / 2 - b[1]
    draw.text((x, y), vis, font=fnt, fill=fill)


def draw_right_top(draw, text, right_x, y, fnt, fill):
    vis = rtl(text)
    b = draw.textbbox((0, 0), vis, font=fnt)
    x = right_x - (b[2] - b[0]) - b[0]
    draw.text((x, y - b[1]), vis, font=fnt, fill=fill)


def draw_center_wrapped(draw, lines, cx, y, fnt, fill, line_gap):
    for i, line in enumerate(lines):
        draw_center_top(draw, line, cx, y + i * line_gap, fnt, fill)


def draw_right_wrapped(draw, lines, right_x, y, fnt, fill, line_gap):
    for i, line in enumerate(lines):
        draw_right_top(draw, line, right_x, y + i * line_gap, fnt, fill)


def shadowed_rect(img, draw, box, radius, fill, outline, width=1, shadow_alpha=22):
    from PIL import Image, ImageDraw, ImageFilter

    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(layer)
    x1, y1, x2, y2 = box
    sd.rounded_rectangle((x1 + 7, y1 + 9, x2 + 7, y2 + 9), radius=radius, fill=(0, 0, 0, shadow_alpha))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(14)))
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def glow_rect(img, draw, box, radius, color, blur=18, alpha=38, spread=5):
    from PIL import Image, ImageDraw, ImageFilter

    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(layer)
    x1, y1, x2, y2 = box
    gd.rounded_rectangle((x1 - spread, y1 - spread, x2 + spread, y2 + spread), radius=radius + spread, fill=(*color, alpha))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def sparkle(draw, cx, cy, size, color, outline):
    draw.line((cx - size, cy, cx + size, cy), fill=outline, width=2)
    draw.line((cx, cy - size, cx, cy + size), fill=outline, width=2)
    draw.line((cx - size // 2, cy - size // 2, cx + size // 2, cy + size // 2), fill=color, width=1)
    draw.line((cx - size // 2, cy + size // 2, cx + size // 2, cy - size // 2), fill=color, width=1)


def draw_button(img, draw, text, box, fnt, fill, outline, text_fill, glow=False):
    if glow:
        glow_rect(img, draw, box, 18, outline, alpha=44, spread=6)
    shadowed_rect(img, draw, box, 16, fill, outline, width=2, shadow_alpha=18)
    x1, y1, x2, y2 = box
    draw.rounded_rectangle((x1 + 5, y1 + 5, x2 - 5, y1 + 19), radius=10, fill=mix(fill, (255, 255, 255), 0.45))
    draw.line((x1 + 18, y2 - 7, x2 - 18, y2 - 7), fill=mix(outline, (255, 255, 255), 0.25), width=2)
    draw_center_box(draw, text, box, fnt, text_fill)


def section_badge(draw, text, box, fnt, fill, outline, text_fill):
    draw.rounded_rectangle(box, radius=19, fill=fill, outline=outline, width=2)
    draw_center_box(draw, text, box, fnt, text_fill)


def clean_icon(Image, ImageFilter, path, target_size):
    raw = Image.open(path).convert("RGBA")
    rgb = raw.convert("RGB")
    alpha = raw.getchannel("A")
    mask = Image.new("L", raw.size, 0)
    src = rgb.load()
    src_alpha = alpha.load()
    dst = mask.load()
    width, height = raw.size
    for y in range(height):
        for x in range(width):
            r, g, b = src[x, y]
            a = src_alpha[x, y]
            if a > 8 and max(r, g, b) > 18:
                dst[x, y] = a
    bbox = mask.getbbox()
    if not bbox:
        return Image.new("RGBA", target_size, (0, 0, 0, 0))
    icon = raw.crop(bbox)
    icon_mask = mask.crop(bbox).filter(ImageFilter.GaussianBlur(1.2))
    scale = min(target_size[0] / icon.width, target_size[1] / icon.height)
    resized_size = (max(1, int(icon.width * scale)), max(1, int(icon.height * scale)))
    icon = icon.resize(resized_size, Image.LANCZOS)
    icon_mask = icon_mask.resize(resized_size, Image.LANCZOS)
    icon.putalpha(icon_mask)
    out = Image.new("RGBA", target_size, (0, 0, 0, 0))
    out.alpha_composite(icon, ((target_size[0] - icon.width) // 2, (target_size[1] - icon.height) // 2))
    return out


def default_icon_path(name):
    return skill_root() / "assets" / name


def draw_contact_icon_button(img, draw, box, icon_path, accent):
    x1, y1, x2, y2 = box
    glow_rect(img, draw, box, 24, accent, alpha=34, spread=5)
    shadowed_rect(img, draw, box, 24, mix(accent, (255, 255, 255), 0.82), accent, width=2, shadow_alpha=16)
    from PIL import Image, ImageFilter

    icon = clean_icon(Image, ImageFilter, icon_path, (x2 - x1 - 16, y2 - y1 - 16))
    img.alpha_composite(icon, (x1 + 8, y1 + 8))


def draw_return_button_preview(img, draw, box, fonts, palette):
    x1, y1, x2, y2 = box
    accent = palette["accent"]
    dark = palette["dark"]
    deep = palette["deep"]
    glow_rect(img, draw, box, 20, accent, alpha=32, spread=4)
    shadowed_rect(img, draw, box, 20, mix(accent, (255, 255, 255), 0.82), accent, width=2, shadow_alpha=14)
    draw_center_box(draw, "חזרה לתוכן העניינים", (x1 + 60, y1, x2 - 14, y2), fonts["small_b"], dark)
    cx, cy = x1 + 38, (y1 + y2) // 2
    draw.ellipse((cx - 15, cy - 15, cx + 15, cy + 15), outline=deep, width=3)
    draw.ellipse((cx - 7, cy - 7, cx + 7, cy + 7), outline=deep, width=2)
    draw.line((cx - 12, cy + 18, cx + 4, cy + 4), fill=deep, width=3)
    draw.line((cx + 4, cy + 4, cx + 19, cy + 19), fill=deep, width=3)


def draw_square_nav_preview(img, draw, text, box, fonts, palette):
    x1, y1, x2, y2 = box
    fill = palette["button_fill_2"]
    accent = palette["accent"]
    shadowed_rect(img, draw, box, 10, fill, accent, width=2, shadow_alpha=12)
    draw.rectangle((x1 + 4, y1 + 4, x2 - 4, y1 + 11), fill=mix(fill, (255, 255, 255), 0.45))
    draw_center_box(draw, text, box, fonts["nav_small"], palette["dark"])


def make_palette(color):
    accent = hex_to_rgb(color)
    return {
        "accent": accent,
        "dark": mix(accent, (0, 0, 0), 0.67),
        "mid": mix(accent, (0, 0, 0), 0.35),
        "deep": mix(accent, (0, 0, 0), 0.50),
        "softer": mix(accent, (255, 255, 255), 0.92),
        "button_fill": mix(accent, (255, 255, 255), 0.66),
        "button_fill_2": mix(accent, (255, 255, 255), 0.76),
        "paper": (255, 255, 253),
        "ink": (25, 31, 35),
        "muted": (85, 92, 99),
        "gold": (205, 159, 52),
    }


def default_toc_config():
    cfg = {
        "booklet_number": None,
        "parsha_name": None,
        "date_label": None,
        "hebrew_year": None,
        "whatsapp_url": None,
        "email_url": None,
        "archive_url": None,
    }
    links_path = skill_root() / "config" / "default_links.json"
    if links_path.exists():
        try:
            links = json.loads(links_path.read_text(encoding="utf-8"))
        except Exception:
            links = {}
        if isinstance(links, dict):
            for key in ("whatsapp_url", "email_url", "archive_url"):
                if links.get(key):
                    cfg[key] = links[key]
    return cfg


def analysis_toc_config(analysis):
    cfg = default_toc_config()
    source = analysis.get("lefi_yamim_6") or analysis.get("lefi_yamim_5") or analysis.get("lefi_yamim_4") or analysis.get("toc") or {}
    if isinstance(source, dict):
        for key in cfg:
            if source.get(key):
                cfg[key] = source[key]
    return cfg


def render_toc_image(parsha_name, date_label, color=DEFAULT_ACCENT, whatsapp_icon=None, email_icon=None, no_parsha=False):
    from PIL import Image, ImageDraw, ImageFilter, ImageFont

    parsha_name = parsha_name or "פרשת השבוע"
    date_label = date_label or ""
    palette = make_palette(color)
    accent = palette["accent"]
    dark = palette["dark"]
    mid = palette["mid"]
    deep = palette["deep"]
    softer = palette["softer"]
    button_fill = palette["button_fill"]
    button_fill_2 = palette["button_fill_2"]
    paper = palette["paper"]
    ink = palette["ink"]
    muted = palette["muted"]
    gold = palette["gold"]

    fonts = {
        "title": get_font(ImageFont, "bold", 108),
        "subtitle": get_font(ImageFont, "bold", 35),
        "intro": get_font(ImageFont, "bold", 33),
        "intro_small": get_font(ImageFont, "bold", 29),
        "h2": get_font(ImageFont, "bold", 48),
        "body_b": get_font(ImageFont, "bold", 34),
        "small_b": get_font(ImageFont, "bold", 29),
        "btn": get_font(ImageFont, "bold", 42),
        "btn_small": get_font(ImageFont, "bold", 34),
        "nav_title": get_font(ImageFont, "bold", 53),
        "nav_body": get_font(ImageFont, "bold", 31),
        "nav_small": get_font(ImageFont, "regular", 27),
        "card_title": get_font(ImageFont, "bold", 42),
    }

    img = Image.new("RGBA", (CANVAS_W, CANVAS_H), (*paper, 255))
    draw = ImageDraw.Draw(img)

    for y in range(0, 210):
        t = y / 210
        draw.line([(0, y), (CANVAS_W, y)], fill=mix(softer, paper, t))
    draw.rectangle((0, 0, CANVAS_W, 18), fill=accent)
    draw.rectangle((80, 151, CANVAS_W - 80, 157), fill=accent)
    draw.ellipse((CANVAS_W - 148, 75, CANVAS_W - 103, 120), fill=accent)
    draw.ellipse((103, 75, 148, 120), fill=accent)
    sparkle(draw, CANVAS_W - 126, 98, 20, (255, 255, 255), mix(accent, (255, 255, 255), 0.20))
    sparkle(draw, 126, 98, 20, (255, 255, 255), mix(accent, (255, 255, 255), 0.20))
    draw_center_top(draw, parsha_name, CANVAS_W / 2, 57, fonts["title"], dark)
    draw_center_top(draw, f"לפי ימים • {date_label}", CANVAS_W / 2, 174, fonts["subtitle"], mid)

    left = 92
    right = CANVAS_W - 92
    intro_box = (left, 226, right, 410)
    shadowed_rect(img, draw, intro_box, 18, (255, 255, 255), mix(accent, (255, 255, 255), 0.35), width=2, shadow_alpha=16)
    draw.rectangle((intro_box[0] + 20, intro_box[1], intro_box[2] - 20, intro_box[1] + 6), fill=accent)
    intro = (
        "הדף היומי עם פירוש אוצרות יהודים • נ״ך • משנה • שולחן ערוך • "
        "עמוד יומי משנה ברורה • זוהר עם תרגום ללשון הזוהר • תהילים • "
        "כתבי ברסלב: ליקוטי מוהר״ן • ליקוטי הלכות • ספרי רבינו על פי הסדר • "
        "ספר המידות • שמות הצדיקים • ליקוטי תפילות"
    )
    lines = wrap_text(draw, intro, fonts["intro"], intro_box[2] - intro_box[0] - 90)
    intro_font = fonts["intro"]
    line_gap = 38
    if len(lines) > 3:
        intro_font = fonts["intro_small"]
        line_gap = 34
        lines = wrap_text(draw, intro, intro_font, intro_box[2] - intro_box[0] - 90)
    draw_center_wrapped(draw, lines, CANVAS_W / 2, intro_box[1] + 34, intro_font, ink, line_gap)
    note = "תיקון הכללי מופיע בנפרד" if no_parsha else "פרשת השבוע ותיקון הכללי מופיעים בנפרד"
    draw_center_top(draw, note, CANVAS_W / 2, intro_box[3] - 42, fonts["small_b"], mid)

    arr_box = (left, 435, right, 608)
    shadowed_rect(img, draw, arr_box, 16, softer, mix(accent, (255, 255, 255), 0.20), width=2, shadow_alpha=18)
    draw_center_box(draw, "נושאי הלימוד בחוברת ערוכים לפי ימות השבוע", (arr_box[0], arr_box[1] + 16, arr_box[2], arr_box[1] + 73), fonts["h2"], dark)
    draw_center_box(draw, "בכל יום מרוכז חלק הלימוד השייך לאותו יום", (arr_box[0], arr_box[1] + 82, arr_box[2], arr_box[1] + 126), fonts["body_b"], ink)
    draw_center_box(draw, "הקש על היום הרצוי כדי לעבור אליו", (arr_box[0], arr_box[1] + 133, arr_box[2], arr_box[3] - 10), fonts["small_b"], mid)

    btn_w, btn_h = 398, 78
    rx = CANVAS_W - 156 - btn_w
    lx = 156
    gap_y = 25
    start_y = 646
    pairs = [("יום ראשון", "יום שני"), ("יום שלישי", "יום רביעי"), ("יום חמישי", "יום שישי")]
    for row, (rtext, ltext) in enumerate(pairs):
        yy = start_y + row * (btn_h + gap_y)
        draw_button(img, draw, rtext, (rx, yy, rx + btn_w, yy + btn_h), fonts["btn"], button_fill_2, accent, dark)
        draw_button(img, draw, ltext, (lx, yy, lx + btn_w, yy + btn_h), fonts["btn"], button_fill_2, accent, dark)

    shabbos_box = ((CANVAS_W - btn_w) // 2, start_y + 3 * (btn_h + gap_y), (CANVAS_W + btn_w) // 2, start_y + 3 * (btn_h + gap_y) + btn_h)
    draw_button(img, draw, "שבת קודש", shabbos_box, fonts["btn"], button_fill_2, accent, dark)
    special_y = shabbos_box[3] + 44
    draw.rectangle((left + 150, special_y - 22, right - 150, special_y - 18), fill=mix(accent, (255, 255, 255), 0.48))
    if no_parsha:
        single_box = ((CANVAS_W - btn_w) // 2, special_y, (CANVAS_W + btn_w) // 2, special_y + btn_h)
        draw_button(img, draw, "לתיקון הכללי", single_box, fonts["btn_small"], button_fill, accent, dark, glow=True)
    else:
        draw_button(img, draw, "לפרשת השבוע", (rx, special_y, rx + btn_w, special_y + btn_h), fonts["btn_small"], button_fill, accent, dark, glow=True)
        draw_button(img, draw, "לתיקון הכללי", (lx, special_y, lx + btn_w, special_y + btn_h), fonts["btn_small"], button_fill, accent, dark, glow=True)

    nav_top = special_y + btn_h + 48
    nav_h = 405
    shadowed_rect(img, draw, (left, nav_top, right, nav_top + nav_h), 18, (255, 255, 255), mix(accent, (255, 255, 255), 0.24), width=2, shadow_alpha=22)
    draw_center_top(draw, "ניווט בתוך החוברת", CANVAS_W / 2, nav_top + 20, fonts["nav_title"], dark)
    draw_center_top(draw, "לחצו על הכפתורים למעבר מהיר בין הנושאים", CANVAS_W / 2, nav_top + 78, fonts["nav_body"], ink)

    page_box = (left + 175, nav_top + 125, right - 175, nav_top + 350)
    glow_rect(img, draw, page_box, 16, accent, alpha=28, spread=5)
    shadowed_rect(img, draw, page_box, 16, mix(accent, (255, 255, 255), 0.94), mix(accent, (255, 255, 255), 0.25), width=2, shadow_alpha=16)
    px1, py1, px2, py2 = page_box
    draw_return_button_preview(img, draw, ((px1 + px2) // 2 - 174, py1 + 16, (px1 + px2) // 2 + 174, py1 + 62), fonts, palette)
    for y, extra in [(py1 + 92, 80), (py1 + 120, 54), (py1 + 148, 112), (py1 + 176, 190)]:
        draw.rectangle((px1 + 62, y, px2 - extra, y + 5), fill=mix(accent, (255, 255, 255), 0.68))
    prev_box = (px2 - 264, py1 + 72, px2 - 38, py1 + 112)
    next_box = (px1 + 38, py2 - 58, px1 + 264, py2 - 18)
    draw_square_nav_preview(img, draw, "לעמוד הקודם", prev_box, fonts, palette)
    draw_square_nav_preview(img, draw, "לעמוד הבא", next_box, fonts, palette)

    footer_top = nav_top + nav_h + 40
    draw.rectangle((0, footer_top, CANVAS_W, CANVAS_H), fill=softer)
    draw.rectangle((0, footer_top, CANVAS_W, footer_top + 7), fill=accent)
    card_gap = 36
    card_w = (right - left - card_gap) // 2
    card_h = 260
    right_card = (left + card_w + card_gap, footer_top + 52, right, footer_top + 52 + card_h)
    left_card = (left, footer_top + 52, left + card_w, footer_top + 52 + card_h)
    shadowed_rect(img, draw, right_card, 18, (255, 255, 255), mix(accent, (255, 255, 255), 0.30), width=2, shadow_alpha=20)
    shadowed_rect(img, draw, left_card, 18, (255, 255, 255), mix(accent, (255, 255, 255), 0.30), width=2, shadow_alpha=20)
    draw_center_top(draw, "מעבר לארכיון החוברות", (right_card[0] + right_card[2]) / 2, right_card[1] + 39, fonts["card_title"], dark)
    archive_btn = (right_card[0] + 94, right_card[1] + 126, right_card[2] - 94, right_card[1] + 203)
    draw_button(img, draw, "לחוברות מפרשות קודמות", archive_btn, fonts["btn_small"], button_fill, accent, dark, glow=True)
    draw_center_top(draw, "להצטרפות לקבלת החוברות", (left_card[0] + left_card[2]) / 2, left_card[1] + 39, fonts["card_title"], dark)
    icon_y = left_card[1] + 158
    icon_size = 96
    icon_gap = 56
    total_icon_w = icon_size * 2 + icon_gap
    icon_start = (left_card[0] + left_card[2] - total_icon_w) // 2
    whatsapp_icon = Path(whatsapp_icon or default_icon_path("whatsapp.jpg"))
    email_icon = Path(email_icon or default_icon_path("gmail.png"))
    for i, icon_path in enumerate([whatsapp_icon, email_icon]):
        x1 = icon_start + i * (icon_size + icon_gap)
        if icon_path.exists():
            draw_contact_icon_button(img, draw, (x1, icon_y - icon_size // 2, x1 + icon_size, icon_y + icon_size // 2), icon_path, accent)

    return img.convert("RGB")


def canvas_rect(x1, y1, x2, y2):
    return (x1 / SCALE, y1 / SCALE, x2 / SCALE, y2 / SCALE)


def toc_rect_specs():
    btn_w, btn_h = 398, 78
    rx = CANVAS_W - 156 - btn_w
    lx = 156
    start_y = 646
    gap_y = 25
    specs = {
        "sunday": (rx, start_y, rx + btn_w, start_y + btn_h),
        "monday": (lx, start_y, lx + btn_w, start_y + btn_h),
        "tuesday": (rx, start_y + btn_h + gap_y, rx + btn_w, start_y + btn_h + gap_y + btn_h),
        "wednesday": (lx, start_y + btn_h + gap_y, lx + btn_w, start_y + btn_h + gap_y + btn_h),
        "thursday": (rx, start_y + 2 * (btn_h + gap_y), rx + btn_w, start_y + 2 * (btn_h + gap_y) + btn_h),
        "friday": (lx, start_y + 2 * (btn_h + gap_y), lx + btn_w, start_y + 2 * (btn_h + gap_y) + btn_h),
    }
    shabbos_y = start_y + 3 * (btn_h + gap_y) + 4
    specs["shabbos"] = ((CANVAS_W - btn_w) // 2, shabbos_y, (CANVAS_W + btn_w) // 2, shabbos_y + btn_h)
    special_y = shabbos_y + btn_h + 44
    specs["parsha"] = (rx, special_y, rx + btn_w, special_y + btn_h)
    specs["tikun"] = (lx, special_y, lx + btn_w, special_y + btn_h)
    footer_top = special_y + btn_h + 48 + 405 + 40
    left = 92
    right = CANVAS_W - 92
    card_gap = 36
    card_w = (right - left - card_gap) // 2
    right_card = (left + card_w + card_gap, footer_top + 52, right, footer_top + 312)
    left_card = (left, footer_top + 52, left + card_w, footer_top + 312)
    specs["archive"] = (right_card[0] + 94, right_card[1] + 126, right_card[2] - 94, right_card[1] + 203)
    icon_size = 96
    icon_gap = 56
    icon_y = left_card[1] + 158
    icon_start = (left_card[0] + left_card[2] - (icon_size * 2 + icon_gap)) // 2
    specs["whatsapp"] = (icon_start, icon_y - icon_size // 2, icon_start + icon_size, icon_y + icon_size // 2)
    gmail_x = icon_start + icon_size + icon_gap
    specs["email"] = (gmail_x, icon_y - icon_size // 2, gmail_x + icon_size, icon_y + icon_size // 2)
    return specs


def toc_button_rects(fitz):
    return {key: fitz.Rect(*canvas_rect(*spec)) for key, spec in toc_rect_specs().items()}


def external_links_from_config(config):
    def normalize(kind, value):
        if not value:
            return None
        text = str(value).strip()
        if not text:
            return None
        if kind == "email" and "@" in text and not text.lower().startswith(("mailto:", "http://", "https://")):
            return "mailto:" + text
        return text

    return {
        "whatsapp": normalize("whatsapp", config.get("whatsapp_url")),
        "email": normalize("email", config.get("email_url")),
        "archive": normalize("archive", config.get("archive_url")),
    }


def insert_toc_links(fitz, toc_page, internal_targets, external_links=None):
    rects = toc_button_rects(fitz)
    for key, target in internal_targets.items():
        if key not in rects or not target:
            continue
        toc_page.insert_link({"kind": fitz.LINK_GOTO, "from": rects[key], "page": int(target) - 1, "to": fitz.Point(0, 0)})
    for key, url in (external_links or {}).items():
        if key not in rects or not url:
            continue
        toc_page.insert_link({"kind": fitz.LINK_URI, "from": rects[key], "uri": str(url)})


def make_toc_pdf(output_pdf, parsha_name, date_label, color=DEFAULT_ACCENT, whatsapp_icon=None, email_icon=None, png_output=None, no_parsha=False):
    import fitz

    image = render_toc_image(parsha_name, date_label, color, whatsapp_icon=whatsapp_icon, email_icon=email_icon, no_parsha=no_parsha)
    output_pdf = Path(output_pdf)
    output_pdf.parent.mkdir(parents=True, exist_ok=True)
    png_path = Path(png_output) if png_output else output_pdf.with_suffix(".png")
    image.save(png_path, quality=95)
    doc = fitz.open()
    page = doc.new_page(width=PAGE_W, height=PAGE_H)
    page.insert_image(fitz.Rect(0, 0, PAGE_W, PAGE_H), filename=str(png_path))
    doc.save(str(output_pdf), garbage=4, deflate=True)
    doc.close()
    return output_pdf


def make_return_button_image(output_path, color=DEFAULT_ACCENT):
    from PIL import Image, ImageDraw, ImageFilter, ImageFont

    accent = hex_to_rgb(color)
    dark = mix(accent, (0, 0, 0), 0.64)
    deep = mix(accent, (0, 0, 0), 0.35)
    fill = mix(accent, (255, 255, 255), 0.35)
    scale = 4
    width, height = 500, 128
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    box = (18, 18, width - 18, height - 18)
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(layer)
    sd.rounded_rectangle((box[0] + 7, box[1] + 8, box[2] + 7, box[3] + 8), radius=28, fill=(0, 0, 0, 30))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(14)))
    draw.rounded_rectangle(box, radius=28, fill=(*fill, 238), outline=(*deep, 235), width=4)
    draw.rounded_rectangle((box[0] + 7, box[1] + 7, box[2] - 7, box[1] + 26), radius=18, fill=(*mix(accent, (255, 255, 255), 0.48), 128))
    draw.line((box[0] + 24, box[3] - 8, box[2] - 24, box[3] - 8), fill=(*mix(accent, (0, 0, 0), 0.22), 185), width=3)
    fnt = get_font(ImageFont, "bold", 42)
    draw_center_box(draw, "חזרה לתוכן העניינים", (96, 30, width - 32, height - 20), fnt, dark)
    cx, cy = 62, height // 2
    draw.ellipse((cx - 17, cy - 17, cx + 17, cy + 17), outline=dark, width=5)
    draw.ellipse((cx - 8, cy - 8, cx + 8, cy + 8), outline=dark, width=4)
    draw.line((cx - 18, cy + 22, cx + 3, cy + 6), fill=dark, width=5)
    draw.line((cx + 3, cy + 6, cx + 24, cy + 23), fill=dark, width=5)
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(output_path)
    return output_path


def make_continuation_button_images(out_dir, color=DEFAULT_ACCENT, labels=None, font_file=None):
    from PIL import Image, ImageDraw, ImageFilter, ImageFont

    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    accent = hex_to_rgb(color)
    deep = mix(accent, (0, 0, 0), 0.46)
    dark = mix(accent, (0, 0, 0), 0.70)
    soft = mix(accent, (255, 255, 255), 0.78)
    fnt = get_font(ImageFont, "bold", 29)

    def make(text, path):
        scale = 4
        width, height = 150 * scale, 36 * scale
        img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        gd.rounded_rectangle((5, 5, width - 5, height - 5), radius=18, fill=(*accent, 24))
        img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(7)))
        draw = ImageDraw.Draw(img)
        box = (8, 8, width - 8, height - 8)
        draw.rounded_rectangle(box, radius=12, fill=(*soft, 84), outline=(*deep, 160), width=2)
        draw.rectangle((box[0] + 2, box[1] + 2, box[2] - 2, box[1] + 8), fill=(255, 255, 255, 40))
        draw.line((box[0] + 14, box[3] - 6, box[2] - 14, box[3] - 6), fill=(*deep, 68), width=2)
        label_font = fnt
        if labels is not None:
            if not font_file:
                raise RuntimeError("A Hebrew font is required for named navigation labels.")
            # Use the same bidi engine as rtl(): Windows uses explicit
            # reversal, while Linux uses Pillow's natural Hebrew shaping.
            for size in range(29, 23, -1):
                engine = ImageFont.Layout.BASIC if sys.platform.startswith("win") else ImageFont.Layout.RAQM
                label_font = ImageFont.truetype(font_file, size, layout_engine=engine)
                if text_width(draw, text, label_font) <= width - 32:
                    break
            else:
                raise RuntimeError(f"Navigation label does not fit: {text}")
        draw_center_box(draw, text, (box[0] + 4, box[1] - 1, box[2] - 4, box[3] - 1), label_font, (*dark, 232))
        img.save(path)

    if labels is not None:
        paths = {key: out_dir / f"continuation-{key}.png" for key in labels}
        for key, text in labels.items():
            make(text, paths[key])
        return paths

    paths = {
        "previous": out_dir / "continuation-previous.png",
        "next": out_dir / "continuation-next.png",
    }
    make("העמוד הקודם", paths["previous"])
    make("העמוד הבא", paths["next"])
    return paths


def continuation_rects_for_page(fitz, page):
    width, height = page.rect.width, page.rect.height
    visual = {
        "previous": fitz.Rect(max(width - 10 - 150, 0), 8, max(width - 10, 0), 8 + 36),
        "next": fitz.Rect(10, max(height - 8 - 36, 0), 10 + 150, max(height - 8, 0)),
    }
    if page.rotation in {90, 270}:
        return {key: rect * page.derotation_matrix for key, rect in visual.items()}
    return visual
