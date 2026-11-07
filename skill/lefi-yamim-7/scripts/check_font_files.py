#!/usr/bin/env python3
"""Check repository font availability without requiring the build dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def inspect(directory):
    profile = json.loads((ROOT / 'assets/font-profile.json').read_text(encoding='utf-8'))
    result = []
    for name, spec in profile['files'].items():
        path = directory / spec['file']
        if not path.is_file() and directory.is_dir():
            path = next((p for p in directory.iterdir() if p.name.casefold() == spec['file'].casefold()), path)
        row = {'profile_key': name, 'file': spec['file'], 'family': spec['family'], 'style': spec['style']}
        if not path.is_file():
            row['status'] = 'missing'
        else:
            row['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            row['status'] = 'exact_match' if row['sha256'] == spec['sha256'] else 'different_file'
        result.append(row)
    return {'font_files_ready': all(r['status'] == 'exact_match' for r in result), 'fonts': result,
            'scope': 'File identity only; full family/style/character coverage is checked by typography.audit_profile before building.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fonts-dir', type=Path, default=Path(os.environ.get('OTZAROT_FONTS_DIR', ROOT / 'assets/fonts')))
    args = parser.parse_args()
    report = inspect(args.fonts_dir)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['font_files_ready'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
