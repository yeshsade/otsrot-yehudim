"""One owner-supplied font profile for every generated UI component.

Font binaries remain local; family identity and hashes are part of the skill.
Missing fonts never select a different installed typeface.
"""
import hashlib
import json
import os
from pathlib import Path

import fitz
from PIL import ImageFont

ROOT = Path(__file__).resolve().parents[2]
PROFILE = json.loads((ROOT / "assets/font-profile.json").read_text(encoding="utf-8"))


def _resolve(role, directory):
    spec = PROFILE["files"][PROFILE["roles"][role]]
    folder = Path(directory)
    path = folder / spec["file"]
    if not path.is_file() and folder.is_dir():
        path = next((p for p in folder.iterdir() if p.name.casefold() == spec["file"].casefold()), path)
    if not path.is_file():
        raise FileNotFoundError(f"Original font missing: {spec['file']}. Supply --fonts-dir or OTZAROT_FONTS_DIR; no substitute font is selected.")
    if hashlib.sha256(path.read_bytes()).hexdigest() != spec["sha256"]:
        raise ValueError(f"Font file differs from the owner's supplied font: {path.name}")
    actual = ImageFont.truetype(str(path), 16).getname()
    if actual != (spec["family"], spec["style"]):
        raise ValueError(f"Wrong font family/style for {path.name}: {actual}")
    return str(path.resolve())


def font_file(role, directory=None):
    folder = directory or os.environ.get("OTZAROT_FONTS_DIR") or ROOT / "assets/fonts"
    return _resolve(role, str(Path(folder).resolve()))


def ensure_text(path, text):
    face = fitz.Font(fontfile=str(path))
    missing = sorted({c for c in text if not c.isspace() and not face.has_glyph(ord(c))})
    if missing:
        raise ValueError(f"The original font lacks these characters: {missing}; review typography instead of replacing the whole font")


def load_font(role, size):
    return ImageFont.truetype(font_file(role), size)


def audit_profile(texts=()):
    result = {"profile_id": PROFILE["id"], "roles": {}}
    sample = "אבגדהוזחטיכלמנסעפצקרשתךםןףץ 0123456789 • – ׳״ " + " ".join(texts)
    for role, key in PROFILE["roles"].items():
        path = font_file(role)
        ensure_text(path, sample)
        result["roles"][role] = dict(PROFILE["files"][key])
    result["passed"] = True
    return result
