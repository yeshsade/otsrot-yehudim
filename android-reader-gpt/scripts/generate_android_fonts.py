#!/usr/bin/env python3
"""Instantiate the selected Noto Sans font for consistent rendering on Android 6+."""
import hashlib
import json
from pathlib import Path

import fontTools
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont


def main():
    root = Path(__file__).resolve().parents[1]
    source = root / "design/assets/fonts/NotoSansHebrew.ttf"
    metadata = json.loads((source.parent / "sources.json").read_text())
    expected = next(entry["sha256"] for entry in metadata if entry["filename"] == source.name)
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != expected:
        raise ValueError("Selected font source checksum mismatch")
    target = root / "android/app/src/main/assets/fonts"
    target.mkdir(parents=True, exist_ok=True)
    outputs = {}
    for name, weight in (("Regular", 400), ("Medium", 550), ("SemiBold", 600)):
        font = instantiateVariableFont(TTFont(source), {"wght": weight, "wdth": 100}, inplace=True)
        file = target / f"NotoSansHebrew-{name}.ttf"
        font.save(file)
        outputs[name] = {"weight": weight, "filename": file.name,
                         "sha256": hashlib.sha256(file.read_bytes()).hexdigest()}
    record = {"source": "design/assets/fonts/NotoSansHebrew.ttf", "source_sha256": digest,
              "fonttools_version": fontTools.__version__, "width": 100, "weights": outputs,
              "license": "SIL Open Font License 1.1; kept in APK assets"}
    (source.parent / "android-static-sources.json").write_text(json.dumps(record, indent=2) + "\n")
    print("Generated static Android font weights 400, 550 and 600 from the verified selected font")


if __name__ == "__main__":
    main()
