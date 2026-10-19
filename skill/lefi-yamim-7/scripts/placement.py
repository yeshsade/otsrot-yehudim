"""Measured checkbox planning and collision-free shared control placement."""
from collections import defaultdict
import math

import fitz
import numpy as np

from source_model import spans, union, column_of, learning_font_from_spans


def box_count(learning_height, full_height, landscape):
    if learning_height <= 0:
        return 0
    if full_height <= 0 or learning_height > full_height + .05:
        raise ValueError("Invalid measured learning/full column heights")
    return min(3, math.ceil(3 * learning_height / full_height - 1e-9)) if landscape else 3


def measured_regions(page, topic, tehillim=None, font=None):
    landscape = page.rect.width > page.rect.height
    columns = 4 if landscape else 2
    items = list(spans(page))
    full = defaultdict(list)
    learned = defaultdict(list)
    # Font subsets can differ even between the two halves of one page.
    selected = {}
    if topic in {"mishnah", "shulchan_aruch", "zohar", "nach"}:
        for half in (1, 2):
            half_items = [s for s in items if (1 if s["rect"].x0 >= page.rect.width / 2 else 2) == half]
            if half_items:
                selected[half] = learning_font_from_spans(half_items)
    from source_model import gray_backgrounds
    gray = gray_backgrounds(page) if topic == "shulchan_aruch" else []
    if topic == "tehillim":
        return [{"column": r["column"], "rect": fitz.Rect(r["rect"]),
                 "full_rect": fitz.Rect(r["full_rect"])} for r in tehillim or []]
    for item in items:
        r = item["rect"]
        if item["size"] < 5:
            continue
        col = column_of(r, page.rect.width, columns)
        full[col].append(r)
        half = 1 if r.x0 >= page.rect.width / 2 else 2
        active_font = selected.get(half, font)
        if active_font and item["font"] != active_font:
            continue
        if gray and any(bg.contains((r.tl + r.br) / 2) for bg in gray):
            continue
        # Grey future-week letters can also be text rather than background.
        rgb = [(item["color"] >> shift) & 255 for shift in (16, 8, 0)]
        if topic == "shulchan_aruch" and sum(rgb) / 3 > 125:
            continue
        learned[col].append(r)
    if topic == "nach":
        # One unit per HALF, not one per text/commentary column.
        halves = defaultdict(list)
        for rects in learned.values():
            for r in rects:
                halves[1 if r.x0 >= page.rect.width / 2 else 2].append(r)
        if set(halves) != {1, 2}:
            raise ValueError(f"Expected two verse halves on page {page.number + 1}, font={font}, halves={list(halves)}")
        return [{"column": half, "rect": union(rects), "full_rect": union(rects)}
                for half, rects in sorted(halves.items())]
    if not learned:
        raise ValueError(f"No learned text for {topic}, page {page.number + 1}")
    reference = union([r for rects in full.values() for r in rects])
    regions = []
    for col, rects in sorted(learned.items()):
        if not rects:
            continue
        full_rect = union(full[col])
        if landscape:
            # A short final column must not become its own full-height
            # denominator. Full neighboring columns establish the page's
            # study-column extent, including any commentary.
            full_rect.y0, full_rect.y1 = reference.y0, reference.y1
        regions.append({"column": col, "rect": union(rects), "full_rect": full_rect})
    return regions


class Canvas:
    """All rectangles are in the physically normalized final PDF coordinates.

    Cache source pixels and glyphs once. Planned controls are reserved before
    either their image or their annotation is written.
    """
    def __init__(self, page):
        if page.rotation:
            raise ValueError("Normalize page rotation before planning annotations")
        self.page = page
        self.words = [fitz.Rect(w[:4]) for w in page.get_text("words")]
        self.glyphs = [fitz.Rect(ch["bbox"]) for b in page.get_text("rawdict")["blocks"] if b["type"] == 0
                       for line in b["lines"] for span in line["spans"]
                       for ch in span.get("chars", []) if ch["c"].strip()]
        self.glyph_array = np.array([tuple(r) for r in self.glyphs], dtype=float).reshape(-1, 4)
        self.word_array = np.array([tuple(r) for r in self.words], dtype=float).reshape(-1, 4)
        pix = page.get_pixmap(matrix=fitz.Matrix(2, 2), colorspace=fitz.csGRAY, alpha=False)
        self.pixels = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width)
        self.reserved = [fitz.Rect(link["from"]) for link in page.get_links()]

    def clear(self, rect, gap=1):
        r = fitz.Rect(rect) + (-gap, -gap, gap, gap)
        if not self.page.rect.contains(r):
            return False
        a = self.glyph_array
        if np.any((a[:, 0] < r.x1) & (a[:, 2] > r.x0) & (a[:, 1] < r.y1) & (a[:, 3] > r.y0)):
            return False
        if any(r.intersects(g) for g in self.reserved):
            return False
        # Also honor word boxes used by the independent validator. These can
        # be broader than individual visible glyphs in legacy Hebrew fonts.
        a = self.word_array
        if np.any((a[:, 0] < rect.x1) & (a[:, 2] > rect.x0) & (a[:, 1] < rect.y1) & (a[:, 3] > rect.y0)):
            return False
        x0, y0, x1, y1 = [int(v * 2) for v in r]
        area = self.pixels[y0:y1 + 1, x0:x1 + 1]
        return area.size > 0 and not np.any(area < 220)

    def reserve(self, rect):
        self.reserved.append(fitz.Rect(rect))
        return rect

    def checkbox(self, region, fraction, size=9):
        """Small deterministic shifts around the reading-progress position."""
        r = region["rect"]
        target_y = r.y0 + r.height * fraction - size
        # Outer lane on the outside of each reading half, inner lane at its gutter.
        if self.page.rect.width > self.page.rect.height:
            outside_right = region["column"] in {1, 3}
        else:
            outside_right = region["column"] == 1
        start_x = r.x1 + 7 if outside_right else r.x0 - 7 - size
        # Search both closer to the text and farther into the margin. Page
        # numbers occupy the outermost lane on some source pages.
        for dx in (0, -2, -4, -5, *range(2, 43, 2)):
            x = start_x + (dx if outside_right else -dx)
            # Keep the final checkbox at the learning end; other checkpoints
            # may move slightly in their local neighborhood.
            offsets = (0, -1, -2, -3, -4, -6) if fraction == 1 else (0, -1, 1, -2, 2, -4, 4)
            for dy in offsets:
                y = target_y + dy
                rect = fitz.Rect(x, y, x + size, y + size)
                if rect.y0 < r.y0 or rect.y1 > r.y1 + .01:
                    continue
                if self.clear(rect):
                    return self.reserve(rect)
        raise ValueError(f"No safe checkbox position, final page {self.page.number + 1}, column {region['column']}")

    def lower_corner(self, right=False, size=12):
        width, height = self.page.rect.width, self.page.rect.height
        x0 = width - 40 if right else 28
        for step in range(0, 60, 2):
            x = x0 - step if right else x0 + step
            rect = fitz.Rect(x, height - 31, x + size, height - 31 + size)
            if self.clear(rect):
                return self.reserve(rect)
        raise ValueError(f"No safe lower {'right' if right else 'left'} corner, page {self.page.number + 1}")

    def nach(self, region, size=9):
        r = region["rect"]
        for dy in (1.3, 2, 3):
            for dx in range(0, int(r.width) - int(size), 3):
                rect = fitz.Rect(r.x0 + dx, r.y1 + dy, r.x0 + dx + size, r.y1 + dy + size)
                if self.clear(rect, gap=.5):
                    return self.reserve(rect)
        raise ValueError(f"No safe space between verses and commentary, page {self.page.number + 1}")

    def button(self, kind):
        w, h = self.page.rect.width, self.page.rect.height
        if kind == "return":
            candidates = [fitz.Rect(w / 2 - 62.5, 2, w / 2 + 62.5, 23)]
            # Compact day cards have a frame at y=21. Keep a proportional
            # 90% control entirely in their white top margin.
            candidates += [fitz.Rect(w / 2 - 56.25, 1, w / 2 + 56.25, 19.9)]
            candidates += [fitz.Rect(x, 2, x + 105, 23) for x in range(80, int(w) - 110, 10)]
            candidates += [fitz.Rect(w / 2 - 62.5, h - 23, w / 2 + 62.5, h - 2)]
        else:
            original = fitz.Rect(w - 160, 8, w - 10, 44) if kind == "previous" else fitz.Rect(10, h - 44, 160, h - 8)
            candidates = []
            for scale in (1, .875, .75):
                bw, bh = original.width * scale, original.height * scale
                top = original.y0 if kind == "previous" else original.y1 - bh
                candidates.append(fitz.Rect(original.x1 - bw, top, original.x1, top + bh))
            # Same readable 75% button, shifted within the page's end margin.
            # Never cover text just to keep a legacy coordinate.
            bw, bh = original.width * .75, original.height * .75
            for y in ([2, 5, 8] if kind == "previous" else [h - bh - 2, h - bh - 5, h - bh - 8]):
                for x in range(12, int(w - bw - 8), 8):
                    candidates.append(fitz.Rect(x, y, x + bw, y + bh))
            # Dense source pages can have no clear strip at the preferred
            # edge. Keep the readable size and destination at the other edge.
            opposite_y = h - bh - 2 if kind == "previous" else 2
            for x in range(12, int(w - bw - 8), 8):
                candidates.append(fitz.Rect(x, opposite_y, x + bw, opposite_y + bh))
            if w > h:
                # Rotated book spreads often have only narrow side margins.
                # Rotate the whole control, including its clickable rectangle.
                for scale in (1, .875, .75):
                    side_w, side_h = original.height * scale, original.width * scale
                    sides = [w - side_w - 1, 1] if kind == "previous" else [1, w - side_w - 1]
                    for x in sides:
                        for y in range(40, int(h - side_h - 8), 8):
                            candidates.append(fitz.Rect(x, y, x + side_w, y + side_h))
        for rect in candidates:
            if self.clear(rect, gap=.5):
                return self.reserve(rect)
        raise ValueError(f"No safe readable {kind} button on final page {self.page.number + 1}")
