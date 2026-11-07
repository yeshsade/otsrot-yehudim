"""Resolve explicitly reviewed cover completions without rewriting input PDFs."""
from copy import deepcopy


def stage_cover_completions(base, answers, source, cover):
    """Append original cover pages to an in-memory source index, retaining origin.

    Review selectors use {"input": "cover", "page": 3}. Integers still refer
    to the uploaded interior. Internal appended indices are never owner answers.
    """
    if not any(isinstance(v, dict) for v in answers.get('previous_week', {}).values()):
        return deepcopy(base), deepcopy(answers), {}
    for key in ('source_sha256', 'cover_sha256'):
        if answers.get(key) != base[key]:
            raise ValueError('Completion review belongs to different input PDFs')
    staged_base, resolved = deepcopy(base), deepcopy(answers)
    origins = {}
    selected = []
    for topic, value in answers.get('previous_week', {}).items():
        if not isinstance(value, dict):
            continue
        n = value.get('page')
        if (set(value) != {'input', 'page'} or value['input'] != 'cover'
                or isinstance(n, bool) or not isinstance(n, int)
                or not 1 < n < len(cover)):
            raise ValueError('Invalid reviewed cover completion selector')
        if n in selected:
            raise ValueError('Cover completion page has conflicting owners')
        selected.append(n)
        origins[len(source) + len(selected)] = {'input': 'cover', 'page': n,
                                                'topic_id': topic}
    # Validate every selector before changing even the in-memory source.
    for index, origin in origins.items():
        n = origin['page']
        source.insert_pdf(cover, from_page=n - 1, to_page=n - 1)
        resolved['previous_week'][origin['topic_id']] = index
    staged_base['total_original_pages'] = len(source)
    return staged_base, resolved, origins
