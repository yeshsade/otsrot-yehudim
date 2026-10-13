#!/usr/bin/env python3
"""Export the visual comparison with embedded, licensed Hebrew fonts."""

import argparse
import base64
import hashlib
import html
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--refinement", action="store_true", help="Build refinements of direction 3 with the font of direction 1")
    mode.add_argument("--selected", action="store_true", help="Build selected direction B with day and night modes")
    mode.add_argument("--settings", action="store_true", help="Build the selected design with settings and a draft copy editor")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    fonts = root / "design/assets/fonts"
    metadata = json.loads((fonts / "sources.json").read_text())
    if args.settings:
        config = json.loads((root / "design/selected-day-night.json").read_text())
        config.update(
            title="מסך ההגדרות — נוסחים לבדיקה",
            intro="מסך ההגדרות באותו עיצוב ב, עם הכתב של 1 ומצבי יום ולילה. אפשר לבחור אפשרויות ולראות את הנוסחים בהקשר שלהם.",
            scope="דוגמה בדפדפן. העדפות הדגמה נשמרות בדפדפן; כיבוי המסך, סיבובו ודפדוף PDF אינם משתנים במכשיר. בהמשך הדף נמצא עורך הנוסחים בעברית ובאנגלית.",
            output_name="settings-preview.html",
        )
        config["css_files"] = config["css_files"] + ["settings-preview.css"]
        config["preference"] = dict(config["preference"], settings=True, initialView="settings",
                                    scope="settings-copy-review", storageKey="pdf-reader-settings-demo-2026-10-12")
    elif args.selected:
        config = json.loads((root / "design/selected-day-night.json").read_text())
    elif args.refinement:
        config = json.loads((root / "design/modern-refinement.json").read_text())
    else:
        config = {
            "title": "שלוש שפות עיצוב לאותה אפליקציה",
            "intro": "אותם מסמכים, אותו סדר ואותו מיקום של הפעולות. כאן בוחרים את המראה: הצבעים, הכתב, הסמלים והכפתורים. אפשר לבחור במספר או להגיד שאף כיוון לא מתאים.",
            "scope": "דוגמאות עיצוב בלבד, עם כריכות וטקסט להמחשה. אפשר לעבור גם למסך הקריאה ולראות את אותו עיצוב שם. נוסחי ההגדרות עדיין טיוטה.",
            "output_name": "choose-design.html",
            "directions": [
                {"id": "1", "className": "clean", "icons": "outline", "name": "לבן וכחול", "description": "כתב פשוט, קווים דקים וסמלים עדינים. כפתור כחול ברור על רקע לבן."},
                {"id": "2", "className": "classic", "icons": "outline", "name": "גוון נייר וכתב קלאסי", "description": "רקע בגוון נייר, כתב ספרי, צבע יין וקווים ישרים. כפתור עם מסגרת."},
                {"id": "3", "className": "bold", "icons": "filled", "name": "צבעוני ומודגש", "description": "פס עליון בצבע עמוק, כתב מעוגל, סמלים מלאים ומשטחים עם פינות מעוגלות."}
            ],
            "preference": {"scope": "visual-design-directions", "revision": "2026-10-12", "storageKey": "pdf-reader-visual-directions-2026-10-12"}
        }
    names = {"NotoSansHebrew": "Reader Sans", "NotoSerifHebrew": "Reader Serif", "Rubik": "Reader Rubik"}
    css, licenses = [], []
    for entry in metadata:
        if config.get("fonts") and entry["family"] not in config["fonts"]:
            continue
        data = (fonts / entry["filename"]).read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError("Font checksum mismatch: " + entry["filename"])
        encoded = base64.b64encode(data).decode("ascii")
        css.append("@font-face{font-family:'" + names[entry["family"]] + "';src:url(data:font/ttf;base64," + encoded + ") format('truetype');font-weight:100 900;font-display:block}")
        license_text = (fonts / entry["license_file"]).read_text()
        licenses.append(entry["family"] + "\n" + license_text)
    template = (root / "design/visual-directions.template.html").read_text()
    css_files = config.get("css_files", [config["css_file"]] if config.get("css_file") else [])
    extra_css = "\n".join((root / "design" / name).read_text() for name in css_files)
    theme_controls = ""
    preference = dict(config["preference"])
    if config["preference"].get("dayNight"):
        copy = json.loads((root / "design/settings-copy.draft.json").read_text())
        labels = {entry["id"]: entry["he"] for entry in copy["entries"]}
        preference["themeNames"] = {"day": labels["theme_day"], "night": labels["theme_dark"]}
        theme_controls = '<div class="theme-controls" role="group" aria-label="תצוגת יום או לילה"><button class="view-button" data-theme-choice="day" aria-pressed="true">' + html.escape(labels["theme_day"]) + '</button><button class="view-button" data-theme-choice="night" aria-pressed="false">' + html.escape(labels["theme_dark"]) + '</button></div>'
    substitutions = {
        "__FONT_CSS__": "\n".join(css),
        "__REVIEW_TITLE__": html.escape(config["title"]),
        "__REVIEW_INTRO__": html.escape(config["intro"]),
        "__REVIEW_SCOPE__": html.escape(config["scope"]),
        "__FEEDBACK_HINT__": html.escape(config.get("feedback_hint", "אם רוצים — למשל „הצבעים של 1, הכתב של 2”")),
        "__EXTRA_CSS__": extra_css,
        "__DIRECTIONS_JSON__": json.dumps(config["directions"], ensure_ascii=False).replace("<", "\\u003c"),
        "__PREFERENCE_JSON__": json.dumps(preference, ensure_ascii=False).replace("<", "\\u003c")
    }
    substitutions["__THEME_CONTROLS__"] = theme_controls
    substitutions["__REVIEW_SECTION_ATTRS__"] = "hidden" if config["preference"].get("selectedDesign") else ""
    copy_catalog = json.loads((root / "design/settings-copy.draft.json").read_text())
    substitutions["__COPY_CATALOG_JSON__"] = json.dumps(copy_catalog if args.settings else None, ensure_ascii=False).replace("<", "\\u003c")
    substitutions["__SETTINGS_SCRIPT__"] = (root / "design/settings-preview.js").read_text() if args.settings else ""
    substitutions["__SETTINGS_INIT__"] = "initializeSettingsReview();" if args.settings else ""
    substitutions["__COPY_EDITOR__"] = (root / "design/settings-copy-editor.html").read_text() if args.settings else ""
    substitutions["__SETTINGS_NAV__"] = '<button class="view-button" data-view="settings" aria-pressed="false">הגדרות</button>' if args.settings else ""
    if args.settings:
        document_copy = dict(copy_catalog, status="draft")
        (args.output_directory).mkdir(parents=True, exist_ok=True)
        (args.output_directory / "settings-copy.draft.json").write_text(json.dumps(document_copy, ensure_ascii=False, indent=2) + "\n")
    document = template
    for marker, value in substitutions.items():
        if document.count(marker) != (2 if marker == "__REVIEW_TITLE__" else 1):
            raise ValueError("Unexpected marker count: " + marker)
        document = document.replace(marker, value)
    document += "\n<!-- Embedded font copyright and license notices:\n" + "\n\n".join(licenses).replace("--", "—") + "\n-->\n"
    args.output_directory.mkdir(parents=True, exist_ok=True)
    output = args.output_directory / config["output_name"]
    output.write_text(document)
    (args.output_directory / "FONT-LICENSES.txt").write_text("\n\n".join(licenses))
    selected_metadata = [entry for entry in metadata if not config.get("fonts") or entry["family"] in config["fonts"]]
    (args.output_directory / "font-sources.json").write_text(json.dumps(selected_metadata, indent=2))
    print(output)


if __name__ == "__main__":
    main()
