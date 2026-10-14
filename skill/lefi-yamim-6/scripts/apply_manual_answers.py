#!/usr/bin/env python3
"""Apply user answers and deterministically recompute derived analysis fields."""

import argparse
import importlib.util
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DAYS_ENGINE = ROOT / "days" / "scripts" / "build_lefi_yamim.py"
TOPICS = [
    "הדף היומי", "פרשת השבוע", "נביאים וכתובים", "משנה", "שולחן ערוך",
    "משנה ברורה", "זוהר", "תהילים", 'ליקוטי מוהר"ן', "ליקוטי הלכות",
    "ספרי רבינו על פי הסדר", "שמות הצדיקים / ספר המידות", "ליקוטי תפילות",
    "תיקון הכללי", "השלמות",
]
PREVIOUS_WEEK_TOPICS = {"משנה ברורה", 'ליקוטי מוהר"ן', "ליקוטי הלכות"}


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temp, path)


def load_days_engine():
    scripts_dir = str(DAYS_ENGINE.parent)
    if scripts_dir not in sys.path:
        sys.path.insert(0, scripts_dir)
    spec = importlib.util.spec_from_file_location("lefi_yamim_6_days_engine", DAYS_ENGINE)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def checked_page(value, total, label, allow_none=False):
    if value is None and allow_none:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= total:
        raise ValueError(f"{label} must be a source page from 1 through {total}, or null when allowed")
    return value


def normalized_ranges(base, overrides, total, absent=()):
    result = {name: list(bounds) for name, bounds in base.items()}
    for name, bounds in (overrides or {}).items():
        if name not in TOPICS:
            raise ValueError(f"Unsupported topic range: {name}")
        if name in absent and bounds is None:
            result[name] = [1, 0]
            continue
        if not isinstance(bounds, list) or len(bounds) != 2:
            raise ValueError(f"Range for {name} must be [start, end]")
        start = checked_page(bounds[0], total, f"{name} start")
        end = checked_page(bounds[1], total, f"{name} end")
        if start > end:
            raise ValueError(f"Range for {name} starts after it ends")
        result[name] = [start, end]
    missing = [name for name in TOPICS if name not in result]
    if missing:
        raise ValueError("Missing topic ranges: " + ", ".join(missing))
    for name in absent:
        if name not in {'פרשת השבוע', 'השלמות'}:
            raise ValueError('Unsupported absent topic')
        result[name] = [1, 0]
    for name, (start, end) in result.items():
        if name not in absent and not 1 <= start <= end <= total:
            raise ValueError(f'Invalid range for {name}')
    ordered = sorted((start, end, name) for name, (start, end) in result.items() if name not in absent)
    for (_s1, e1, n1), (s2, _e2, n2) in zip(ordered, ordered[1:]):
        if s2 <= e1:
            raise ValueError(f"Current-week topic ranges overlap: {n1} and {n2}")
    return result


def apply_days(base, answers):
    out = json.loads(json.dumps(base, ensure_ascii=False))
    total = int(out["total_original_pages"])
    cfg = out.setdefault("lefi_yamim_6", {})
    for source, target in {"number": "booklet_number", "booklet_number": "booklet_number", "parsha": "parsha_name", "parsha_name": "parsha_name", "date_label": "date_label"}.items():
        if source in answers.get("booklet", {}):
            cfg[target] = str(answers["booklet"][source])
    if not str(cfg.get("booklet_number", "")).isdigit():
        raise ValueError("Booklet number must use Arabic digits")

    absent = set(answers.get('absent_topics', []))
    if answers.get('no_parsha_this_week') is True:
        absent.add('פרשת השבוע')
    ranges = normalized_ranges(out["topic_ranges"], answers.get("topic_ranges"), total, absent)
    out['absent_topics'] = sorted(absent)
    previous = answers.get("previous_week_pages", {})
    if set(previous) != PREVIOUS_WEEK_TOPICS:
        raise ValueError("previous_week_pages must contain exactly Mishnah Berurah, Likutei Moharan, and Likutei Halakhot")
    checked_previous = {}
    for topic, page in previous.items():
        page = checked_page(page, total, f"Previous-week page for {topic}", allow_none=True)
        if page is not None and ranges[topic][0] <= page <= ranges[topic][1]:
            raise ValueError(f"Previous-week page {page} for {topic} is inside its current-week range")
        checked_previous[topic] = page

    engine = load_days_engine()
    tuple_ranges = {name: tuple(bounds) for name, bounds in ranges.items()}
    special = {"פרשת השבוע", "תיקון הכללי", "השלמות"}
    sorted_topics = sorted(
        ((name, bounds[0]) for name, bounds in tuple_ranges.items() if name not in special),
        key=lambda item: item[1],
    )
    out["topic_ranges"] = ranges
    out["day_pages"] = engine.assign_days(out["input_pdf"], out["blank_pages"], tuple_ranges, sorted_topics)

    old_duplicates = {run.get("day"): run.get("duplicate_amud") for run in out.get("daf_yomi", {}).get("day_runs", []) if run.get("duplicate_amud")}
    old_duplicates.update(answers.get("daf_yomi_duplicate_amud", {}))
    daf = engine.calculate_daf_yomi_day_runs(out["day_pages"], tuple_ranges, out["blank_pages"])
    for run in daf.get("day_runs", []):
        if run.get("day") in old_duplicates:
            run["duplicate_amud"] = old_duplicates[run["day"]]
    if 'previous_week_gemara_page' not in answers:
        raise ValueError('The previous-week Gemara answer is still missing; do not interpret omission as none')
    daf["previous_week_gemara_page"] = checked_page(answers["previous_week_gemara_page"], total, "Previous-week Gemara page", allow_none=True)
    short_days = answers.get('daf_yomi_short_days', {})
    if set(short_days) - {run['day'] for run in daf.get('day_runs', [])}:
        raise ValueError('Unknown weekday in the short Daf Yomi answers')
    for run in daf.get('day_runs', []):
        exception = short_days.get(run['day'])
        if exception is not None:
            if exception.get('source_pages') != run['source_pages'] or not exception.get('reason'):
                raise ValueError('A short Daf Yomi day needs its exact source pages and a recorded reason')
            run['expected_page_count'] = run['page_count']
            run['status'] = 'ok'
            run['short_day_reason'] = exception['reason']
    context = {p for p in checked_previous.values() if p is not None}
    if daf['previous_week_gemara_page'] is not None:
        context.add(daf['previous_week_gemara_page'])
    out['day_pages'] = {day: [p for p in pages if p not in context] for day, pages in out['day_pages'].items()}
    out["daf_yomi"] = daf
    out["previous_week_pages"] = checked_previous
    out.setdefault("manual_user_answers", {}).update({
        "booklet_metadata_confirmed_by_user": True,
        "previous_week_pages_confirmed_by_user": True,
        "daf_yomi_previous_week_confirmed_by_user": True,
        "daf_yomi_duplicate_amud_confirmed_by_user": True,
    })
    if "no_parsha_this_week" in answers:
        out["manual_user_answers"]["no_parsha_this_week"] = answers["no_parsha_this_week"] is True
    out["manual_answers_applied_by_script"] = True
    contract = importlib.import_module('16060510_source_contract')
    out['source_sha256'] = contract.fingerprint(out['input_pdf'])
    out['source_map_validation'] = contract.validate(out, engine, require_review=False)
    # Never inherit a review flag from an older analysis after recomputation.
    out['manual_user_answers']['source_map_confirmed_by_user'] = answers.get('source_map_confirmed_by_user') is True
    out.pop('source_review_report', None)
    if answers.get('source_review_report'):
        out['source_review_report'] = str(Path(answers['source_review_report']).resolve())
    return out


def apply_topics(base, answers, total):
    out = json.loads(json.dumps(base, ensure_ascii=False))
    starts = answers.get("topic_starts_original")
    absent = set(answers.get('absent_topics', []))
    if answers.get('no_parsha_this_week') is True:
        absent.add('פרשת השבוע')
    active = [name for name in TOPICS if name not in absent]
    if not isinstance(starts, dict) or set(starts) != set(active):
        raise ValueError('Provide exactly the active topics; absent topics must not get fallback starts')
    checked = {name: checked_page(starts[name], total, f"TOC start for {name}") for name in active}
    if [checked[name] for name in active] != sorted(checked.values()):
        raise ValueError('Active topic starts must be in source-page order')
    out["topic_starts_original"] = checked
    out['absent_topics'] = sorted(absent)
    out["topic_starts_applied_by_script"] = True
    return out


def main():
    parser = argparse.ArgumentParser(description="Apply manual answers without hand-editing derived fields")
    parser.add_argument("--answers", required=True)
    parser.add_argument("--days-analysis", required=True)
    parser.add_argument("--days-output", required=True)
    parser.add_argument("--topics-analysis")
    parser.add_argument("--topics-output")
    args = parser.parse_args()
    answers = load_json(args.answers)
    days = apply_days(load_json(args.days_analysis), answers)
    atomic_json(args.days_output, days)
    if bool(args.topics_analysis) != bool(args.topics_output):
        raise ValueError("Provide both --topics-analysis and --topics-output, or neither")
    if args.topics_analysis:
        topics = apply_topics(load_json(args.topics_analysis), answers, int(days["total_original_pages"]))
        topics['input_pdf'] = days['input_pdf']
        topics['source_sha256'] = days['source_sha256']
        topics['source_map_confirmed_by_user'] = days['manual_user_answers']['source_map_confirmed_by_user']
        atomic_json(args.topics_output, topics)
    print(f"DAYS_ANALYSIS={args.days_output}")
    if args.topics_output:
        print(f"TOPICS_ANALYSIS={args.topics_output}")
    print("DERIVED_FIELDS_RECOMPUTED=topic_ranges,day_pages,daf_yomi,topic_starts_original")


if __name__ == "__main__":
    main()
