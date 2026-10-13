#!/usr/bin/env python3
"""Sync the exact approved bilingual settings catalog to Android's shared asset."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    root = Path(__file__).resolve().parents[1]
    source = json.loads((root / "design/settings-copy.approved.json").read_text())
    if source["status"] != "approved":
        raise ValueError("Android settings must use explicitly approved wording")
    entries = source["entries"]
    digest = hashlib.sha256(json.dumps(entries, ensure_ascii=False, sort_keys=True,
                                      separators=(",", ":")).encode()).hexdigest()
    if digest != source["entries_sha256"]:
        raise ValueError("Approved wording was modified without a new approval record")
    ids = [entry["id"] for entry in entries]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate copy IDs")
    for entry in entries:
        for language in ("he", "en"):
            if not isinstance(entry[language], str) or (not entry[language] and not entry["id"].endswith("_desc")):
                raise ValueError("Invalid approved wording: " + entry["id"])
    asset = {
        "version": source["version"], "status": "approved", "source_entries_sha256": digest,
        "entries": [{key: entry[key] for key in ("id", "he", "en")} for entry in entries],
    }
    android = root / "android/app/src/main"
    (android / "assets/settings-copy.json").write_text(json.dumps(asset, ensure_ascii=False, indent=2) + "\n")
    resources = ET.Element("resources")
    for entry in entries:
        ET.SubElement(resources, "item", {"type": "id", "name": "otzr_" + entry["id"]})
    ET.indent(resources)
    (android / "res/values/otzr_settings_ids.xml").write_text(ET.tostring(resources, encoding="unicode") + "\n")
    print(f"Synced {len(entries)} exact approved settings entries, version {source['version']}")


if __name__ == "__main__":
    main()
