"""Skill 7 placement policy, applied before drawing, never to delivered PDFs."""

import hashlib

import fitz
import numpy as np

PREVIOUS = "העמוד הקודם"
NEXT = "העמוד הבא"
BOX_SIZE = 12.0
SAFETY_GAP = 6.0


def spans(page):
    for block in page.get_text("dict")["blocks"]:
        if block["type"] == 0:
            for line in block["lines"]:
                yield from line["spans"]


def gemara_identity(page):
    """Recognize the source's Shas typography, not page parity or adjacency.

    Hadassah commentary is deliberately excluded. Unknown layouts fail closed
    when the caller is processing the Gemara range. Raw encoded header text is
    retained for identity: these sources do not have a usable Hebrew cmap.
    """
    items = list(spans(page))
    total = sum(len(s["text"].strip()) for s in items)
    shas = sum(len(s["text"].strip()) for s in items if "shas" in s["font"].lower())
    commentary = sum(len(s["text"].strip()) for s in items if "hadassah" in s["font"].lower())
    if commentary > total * .5 and shas == 0:
        return None
    if total == 0 or shas < total * .25:
        raise ValueError(f"Unrecognized Gemara/commentary typography on source page {page.number + 1}")
    headers = [s["text"].strip() for s in items
               if "vilna" in s["font"].lower() and s["size"] >= 12
               and s["bbox"][1] < page.mediabox.height * .12
               and len(s["text"].strip()) < 12
               and s["text"].strip().startswith((".", ":"))]
    if len(headers) != 1:
        raise ValueError(f"Ambiguous amud heading on source page {page.number + 1}: {headers}")
    header = headers[0]
    return {"key": hashlib.sha256(header.encode()).hexdigest()[:16],
            "amud": "a" if header.startswith(".") else "b", "source_header": header}


def is_gemara(page):
    # Used outside a known topic range: unrelated book pages are not errors.
    if not any("shas" in s["font"].lower() for s in spans(page)):
        return False
    return gemara_identity(page) is not None


def checkbox_anchor(page, size=BOX_SIZE):
    """Lower left, below the learning text; not the page center."""
    visual = fitz.Rect(28, page.rect.height - 31, 28 + size, page.rect.height - 31 + size)
    return visual * page.derotation_matrix


def padded(rect, gap=SAFETY_GAP):
    return fitz.Rect(rect) + (-gap, -gap, gap, gap)


def clear_of_content(page, rect, gap=2):
    region = padded(rect, gap)
    if not page.mediabox.contains(region):
        return False
    for block in page.get_text("rawdict")["blocks"]:
        if block["type"] != 0:
            continue
        for line in block["lines"]:
            for span in line["spans"]:
                for char in span.get("chars", []):
                    if char["c"].strip() and region.intersects(fitz.Rect(char["bbox"])):
                        return False
    # Include raster lettering, page numbers, day tabs and grey marks.
    pix = page.get_pixmap(matrix=fitz.Matrix(2, 2), clip=region * page.rotation_matrix,
                         alpha=False, annots=True)
    if not pix.width or not pix.height:
        return False
    data = np.frombuffer(pix.samples, dtype=np.uint8).reshape(-1, pix.n)
    return not np.any(data[:, :3].mean(axis=1) < 220)


def clear_of_controls(page, rect, reserved=(), gap=SAFETY_GAP):
    region = padded(rect, gap)
    obstacles = list(reserved) + [link["from"] * page.derotation_matrix for link in page.get_links()]
    obstacles += [widget.rect for widget in page.widgets() or ()]
    return not any(region.intersects(fitz.Rect(other)) for other in obstacles)


def checkbox_rect(page, size=BOX_SIZE):
    """Use the nearest safe lower-left position, otherwise stop."""
    initial = checkbox_anchor(page, size) * page.rotation_matrix
    # Stay in the left half and the lower margin; never move over learning text.
    for offset in range(0, int(page.rect.width / 2 - initial.x1) + 1, 2):
        rect = (initial + (offset, 0, offset, 0)) * page.derotation_matrix
        if clear_of_content(page, rect) and clear_of_controls(page, rect):
            return rect
    raise ValueError(f"No safe bottom-left checkbox position on page {page.number + 1}")


def navigation_rect(page, direction, preferred):
    """Shrink image and hit area together, keeping the original target.

    Never shrink below the approved 75% size. Reserve the Gemara checkbox
    before drawing the navigation, so the button yields to it.
    """
    if direction not in {"previous", "next"}:
        raise ValueError(f"Unknown navigation direction: {direction}")
    reserve = [checkbox_anchor(page)] if is_gemara(page) else []
    visual = preferred * page.rotation_matrix
    for scale in (1, .875, .75):
        width, height = visual.width * scale, visual.height * scale
        rect = fitz.Rect(visual.x1 - width,
                         visual.y0 if direction == "previous" else visual.y1 - height,
                         visual.x1,
                         visual.y0 + height if direction == "previous" else visual.y1)
        rect *= page.derotation_matrix
        if clear_of_content(page, rect) and clear_of_controls(page, rect, reserve):
            return rect
    raise ValueError(f"No safe readable {direction} button on page {page.number + 1}")
