"""Owner-reviewed daily runs, including duplicated amudim and their commentary.

A repeated amud is not auxiliary material. Preserve every source page and ask
which amud is duplicated before assigning days, widgets or navigation.
"""
from collections import Counter, defaultdict
from copy import deepcopy


def duplicate_amud(pages, identities):
    groups = defaultdict(list)
    for page in pages:
        identity = identities.get(page)
        if identity is not None:
            groups[identity['key']].append(page)
    amudim = {identities[ns[0]]['amud'] for ns in groups.values() if len(ns) > 1}
    return 'both' if amudim == {'a', 'b'} else next(iter(amudim), None)


def inspect_runs(days, identities):
    groups = defaultdict(list)
    for day, pages in days.items():
        for page in pages:
            identity = identities.get(page)
            if identity is not None:
                groups[identity['key']].append({'source_page': page, 'day_id': day,
                                               'amud': identity['amud']})
    duplicates = [v for v in groups.values() if len(v) > 1]
    unusual = [day for day, pages in days.items() if len(pages) != 4]
    return {'requires_owner_confirmation': bool(duplicates or unusual),
            'duplicates': duplicates, 'unusual_days': unusual,
            'day_runs': [{'day_id': day, 'source_pages': list(pages),
                          'duplicate_amud': duplicate_amud(pages, identities)}
                         for day, pages in days.items()]}


def resolve_runs(days, identities, review=None):
    """Validate input review and recompute ownership; never edit derived ranges."""
    inspection = inspect_runs(days, identities)
    if review is None:
        if inspection['requires_owner_confirmation']:
            raise ValueError('Ask the owner which Gemara amud is duplicated (a, b or both) '
                             'and confirm its daily source run before building; '
                             f'unresolved runs: {inspection}')
        return deepcopy(days), {**inspection, 'confirmed_by_owner': False}
    if not isinstance(review, dict) or review.get('confirmed_by_owner') is not True:
        raise ValueError('Gemara run review requires explicit owner confirmation')
    runs = review.get('day_runs', [])
    if not isinstance(runs, list) or len(runs) != len(days):
        raise ValueError('Gemara review must describe every actual daily run')
    resolved = {}
    for row in runs:
        day, pages = row.get('day_id'), row.get('source_pages')
        if day not in days or day in resolved:
            raise ValueError('Unknown or repeated day in Gemara review')
        if not isinstance(pages, list) or not pages or any(type(n) is not int for n in pages):
            raise ValueError('Gemara review requires source page numbers')
        if pages != sorted(set(pages)):
            raise ValueError('Gemara review pages must be unique and in source order')
        if not any(identities.get(n) is not None for n in pages):
            raise ValueError('Each Gemara day must include an actual amud')
        mode = row.get('duplicate_amud')
        if mode not in (None, 'a', 'b', 'both') or mode != duplicate_amud(pages, identities):
            raise ValueError('Confirmed duplicate amud disagrees with actual source identities')
        resolved[day] = list(pages)
    expected = [n for ns in days.values() for n in ns]
    actual = [n for day in days for n in resolved[day]]
    if Counter(actual) != Counter(expected) or len(actual) != len(set(actual)):
        raise ValueError('Gemara review must preserve every source page exactly once, including commentary')
    if actual != sorted(actual):
        raise ValueError('Gemara daily runs must retain chronological source order')
    for group in inspection['duplicates']:
        source_pages = {r['source_page'] for r in group}
        assigned = {day for day, ns in resolved.items() if source_pages.intersection(ns)}
        if len(assigned) != 1:
            raise ValueError('Copies of one Gemara amud still belong to different days; ask the owner')
    return resolved, {**inspection, 'confirmed_by_owner': True,
                      'resolved_day_runs': [{'day_id': day, 'source_pages': resolved[day],
                                            'duplicate_amud': duplicate_amud(resolved[day], identities)}
                                           for day in days]}


def validate_progress(progress, rows, days, identities):
    """Each confirmed actual amud is one unit with all its physical copies."""
    identities = {int(n): value for n, value in identities.items()}
    expected = defaultdict(set)
    for day, pages in days.items():
        for n in pages:
            identity = identities.get(n)
            if identity is not None:
                expected[day, identity['key']].add(n)
    page_rows = {r['page']: r for r in rows}
    observed = {}
    for check in progress['checks']:
        if check['topic_id'] != 'gemara':
            continue
        ns = {page_rows[n]['source_page'] for n in check['pages']}
        keys = {(check['day_id'], identities[n]['key']) for n in ns
                if identities.get(n) is not None}
        if len(keys) != 1:
            raise ValueError('Gemara progress field mixes actual amud identities')
        key = keys.pop()
        if key in observed or expected.get(key) != ns:
            raise ValueError('Duplicated Gemara amud must be one progress unit on its confirmed day')
        observed[key] = ns
    if dict(expected) != observed:
        raise ValueError('Gemara progress differs from confirmed actual daily amudim')
