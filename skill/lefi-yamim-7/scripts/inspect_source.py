#!/usr/bin/env python3
"""Discover source geometry and create an UNANSWERED review template."""
import argparse
import json
from pathlib import Path

from source_model import discover, sha256, PREVIOUS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interior", required=True)
    parser.add_argument("--cover", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=False)
    discovery = discover(args.interior, args.cover)
    template = {
        "source_sha256": discovery["source_sha256"],
        "cover_sha256": discovery["cover_sha256"],
        "booklet": {"issue": "ASK_OWNER_OR_READ_COVER", "parsha": "ASK_OWNER_OR_READ_COVER",
                    "week_start": "YYYY-MM-DD", "date_label": "EXACT_COVER_DATE_LABEL"},
        "previous_week": {topic: "ASK_OWNER_SOURCE_PAGE_OR_EXPLICIT_NULL" for topic in PREVIOUS},
        "gemara_review": None,
        "tehillim": {"boundaries_reviewed": False, "exclude_after_psalms": [],
                     "review_provenance": "Inspect the seven black weekly tabs and the last Psalm150 verse; exclude helpers/prayers and future gray reading."},
        "owner_answers_provenance": {}
    }
    for filename, value in [("discovery.json", discovery), ("answers-UNCONFIRMED.json", template)]:
        (output / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"SOURCE_REVIEW_REQUIRED={output / 'answers-UNCONFIRMED.json'}")


if __name__ == "__main__":
    main()
