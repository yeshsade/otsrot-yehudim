"""Validate derived assignments against the same engine, without deduplicating Tehillim."""
import hashlib
import json
from pathlib import Path


def fingerprint(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate(analysis, engine, source=None, require_review=True):
    path = Path(source or analysis['input_pdf']).resolve()
    if path != Path(analysis['input_pdf']).resolve():
        raise ValueError('Analysis belongs to another source PDF')
    if not path.exists():
        raise ValueError('Source PDF is missing')
    if analysis.get('source_sha256') != fingerprint(path):
        raise ValueError('Source fingerprint is missing or changed; reapply the recorded answers')
    absent = set(analysis.get('absent_topics', []))
    if absent - {'פרשת השבוע', 'השלמות'}:
        raise ValueError('Only explicitly absent parsha/hashlamot are supported')
    total = analysis['total_original_pages']
    ranges = analysis['topic_ranges']
    owners = {}
    for topic, (start, end) in ranges.items():
        if topic in absent:
            if end >= start:
                raise ValueError('An absent topic has a nonempty range')
            continue
        if not 1 <= start <= end <= total:
            raise ValueError(f'Invalid topic range: {topic} {start}-{end}')
        for page in range(start, end + 1):
            if page in owners:
                raise ValueError(f'Overlapping current-week ranges on source page {page}')
            owners[page] = topic
    special = {'פרשת השבוע', 'תיקון הכללי', 'השלמות'}
    ordered = sorted(((topic, bounds[0]) for topic, bounds in ranges.items()
                      if topic not in special), key=lambda item: item[1])
    expected = engine.assign_days(str(path), analysis['blank_pages'], ranges, ordered)
    context = {p for p in analysis.get('previous_week_pages', {}).values() if p is not None}
    gemara = analysis.get('daf_yomi', {}).get('previous_week_gemara_page')
    if gemara is not None:
        context.add(gemara)
    expected = {day: [page for page in pages if page not in context] for day, pages in expected.items()}
    actual = analysis['day_pages']
    if set(actual) != set(expected):
        raise ValueError('Missing or unexpected weekday')
    for day, pages in actual.items():
        if len(pages) != len(set(pages)):
            raise ValueError(f'A page repeats within {day}')
        if pages != expected[day]:
            extra = sorted(set(pages) - set(expected[day]))
            missing = sorted(set(expected[day]) - set(pages))
            raise ValueError(f'Day assignment differs from recomputation: {day}; extra={extra}, missing={missing}')
    daily = {page for page, topic in owners.items() if topic not in special}
    daily -= set(analysis['blank_pages']) | context
    assigned = {page for pages in actual.values() for page in pages}
    if daily != assigned:
        raise ValueError(f'Daily coverage differs: missing={sorted(daily-assigned)}, extra={sorted(assigned-daily)}')
    shared = {}
    for day, pages in actual.items():
        for page in pages:
            shared.setdefault(page, []).append(day)
    for page, days in shared.items():
        if len(days) > 1 and owners.get(page) != 'תהילים':
            raise ValueError(f'Non-Tehillim source page {page} belongs to multiple days')
    manual = analysis.get('manual_user_answers', {})
    if require_review and manual.get('source_map_confirmed_by_user') is not True:
        report_path = analysis.get('source_review_report')
        if not report_path or not Path(report_path).is_file():
            raise ValueError('An independent source review report is required before building')
        review = json.loads(Path(report_path).read_text(encoding='utf-8'))
        import importlib
        audit = importlib.import_module('17590510_output_audit')
        if (review.get('ok') is not True or review.get('method') != 'white_weekday_headers_v1'
                or review.get('source_sha256') != analysis['source_sha256']
                or review.get('map_sha256') != audit.map_hash(analysis)):
            raise ValueError('Source review is failed, stale, or belongs to another map')
    return {'ok': True, 'shared_tehillim_pages': [p for p, ds in shared.items() if len(ds) > 1],
            'daily_unique_pages': len(assigned), 'source_sha256': analysis['source_sha256']}
