#!/usr/bin/env python3
"""Build the standalone screen prototype and the unapproved settings catalog."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    catalog = json.loads((root / "design/settings-copy.draft.json").read_text())
    assert catalog["status"] == "draft"
    ids = [entry["id"] for entry in catalog["entries"]]
    assert len(set(ids)) == len(ids)
    for entry in catalog["entries"]:
        for key in ("he", "en", "meaning"):
            assert isinstance(entry[key], str)
            assert entry[key] or (entry["id"].endswith("_desc") and key in ("he", "en"))
    template = (root / "design/prototype.template.html").read_text()
    assert template.count("__COPY_JSON__") == 1
    payload = json.dumps(catalog, ensure_ascii=False).replace("<", "\\u003c")
    page = template.replace("__COPY_JSON__", payload)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "reader-prototype.html").write_text(page)
    (args.output / "settings-copy.draft.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    lines = [
        "# נוסחי ההגדרות — טיוטה לתיקון ואישור",
        "",
        "כל הנוסחים טיוטה. עריכה או בחירת תכונה אינן אישור לשילוב נוסח.",
        "ההקשר לכל הפריטים הוא מסך ההגדרות, מלבד כפתור פתיחתו ממסכי הבית והקריאה.",
        "ב־HTML אפשר לערוך את שתי השפות ולהוריד קובץ טיוטה מתוקן. אישור מפורש יתועד בנפרד.",
        "",
        "| מזהה | עברית | English | משמעות והקשר בעברית | מצב |",
        "| --- | --- | --- | --- | --- |",
    ]
    for entry in catalog["entries"]:
        values = [entry["id"], entry["he"], entry["en"], entry["meaning"], "טיוטה"]
        lines.append("| " + " | ".join(value.replace("|", "\\|") for value in values) + " |")
    (args.output / "settings-copy-review.md").write_text("\n".join(lines) + "\n")
    print("Created standalone prototype and", len(ids), "unapproved text entries in", args.output)


if __name__ == "__main__":
    main()
