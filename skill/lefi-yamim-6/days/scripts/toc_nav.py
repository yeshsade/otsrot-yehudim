def continuation_rects_for_page(fitz, page):
    width, height = page.rect.width, page.rect.height
    visual = {
        "previous": fitz.Rect(max(width - 10 - 150, 0), 8, max(width - 10, 0), 8 + 36),
        "next": fitz.Rect(10, max(height - 8 - 36, 0), 10 + 150, max(height - 8, 0)),
    }
    if page.rotation in {90, 270}:
        return {key: rect * page.derotation_matrix for key, rect in visual.items()}
    return visual
