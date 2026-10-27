"""Duplicate amud review must precede layout, even across two normal-size runs."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import fitz
ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'days/scripts')]
import source_model
from gemara_review import inspect_runs, resolve_runs, validate_progress


def fixture():
    days = {slug: list(range(2 + 4*i, 6 + 4*i)) for i, (slug, _) in enumerate(source_model.DAYS)}
    identities = {n: None for ns in days.values() for n in ns}
    for day, ns in days.items():
        for n, amud in [(ns[1], 'a'), (ns[2], 'b')]:
            identities[n] = {'key': day + amud, 'amud': amud}
    identities[16] = None
    identities[17] = identities[19] = {'key': 'new-tractate-2a', 'amud': 'a'}
    reviewed = deepcopy(days);reviewed['wednesday'] = [14,15];reviewed['thursday'] = list(range(16,22))
    review = {'confirmed_by_owner': True, 'day_runs': [
        {'day_id': day, 'source_pages': ns, 'duplicate_amud': 'a' if day == 'thursday' else None}
        for day, ns in reviewed.items()]}
    return days, identities, reviewed, review


class GemaraReviewTests(unittest.TestCase):
    def test_two_normal_size_runs_cannot_hide_cross_day_duplicate(self):
        days, ids, _, _ = fixture()
        audit = inspect_runs(days, ids)
        self.assertEqual(audit['unusual_days'], [])
        self.assertTrue(audit['requires_owner_confirmation'])
        self.assertEqual([r['source_page'] for r in audit['duplicates'][0]], [17,19])
        with self.assertRaisesRegex(ValueError, 'Ask the owner'):
            resolve_runs(days, ids)

    def test_confirmed_runs_keep_both_copies_and_all_commentary(self):
        days, ids, expected, review = fixture();original = deepcopy(days)
        got, audit = resolve_runs(days, ids, review)
        self.assertEqual(got, expected);self.assertEqual(days, original)
        self.assertEqual(sorted(n for ns in got.values() for n in ns), list(range(2,30)))
        self.assertTrue(audit['confirmed_by_owner'])
        self.assertEqual(len(audit['resolved_day_runs'][4]['source_pages']), 6)

    def test_missing_confirmation_wrong_amud_or_lost_commentary_rejected(self):
        days, ids, _, review = fixture()
        for change in ['unconfirmed','wrong_amud','lost_commentary','unresolved_days']:
            r = deepcopy(review)
            if change == 'unconfirmed':r['confirmed_by_owner'] = False
            elif change == 'wrong_amud':r['day_runs'][4]['duplicate_amud'] = 'b'
            elif change == 'lost_commentary':r['day_runs'][4]['source_pages'].remove(16)
            else:
                r['day_runs'][3]['source_pages'] = days['wednesday']
                r['day_runs'][4]['source_pages'] = days['thursday']
                r['day_runs'][4]['duplicate_amud'] = None
            with self.subTest(change=change), self.assertRaises(ValueError):
                resolve_runs(days, ids, r)

    def test_regular_week_needs_no_duplicate_question(self):
        days, ids, _, _ = fixture();ids[16] = {'key':'wednesdayb','amud':'b'};ids[17] = None
        got, audit = resolve_runs(days, ids)
        self.assertEqual(got, days);self.assertFalse(audit['requires_owner_confirmation'])

    def test_b_and_both_duplicate_patterns_retain_every_commentary(self):
        for mode, pattern in [('b',[None,'a','b',None,'b',None]),
                              ('both',[None,'a',None,'a','b',None,'b',None])]:
            days, ids, runs, start = {}, {}, [], 2
            for day, _ in source_model.DAYS:
                kinds = pattern if day == 'thursday' else [None,'a','b',None]
                ns = list(range(start,start+len(kinds)));days[day] = ns
                for n, kind in zip(ns,kinds):
                    ids[n] = {'key':day+kind,'amud':kind} if kind else None
                runs.append({'day_id':day,'source_pages':ns,
                             'duplicate_amud':mode if day=='thursday' else None})
                start += len(kinds)
            with self.subTest(mode=mode):
                got, _ = resolve_runs(days,ids,{'confirmed_by_owner':True,'day_runs':runs})
                self.assertEqual(got,days)

    def model_case(self, confirmed):
        days, ids, _, review = fixture()
        base = {'source_sha256':'source','cover_sha256':'cover','total_original_pages':29,
                'rotated_original':[],'blank_pages':[],'topics':{'הדף היומי':2},
                'topic_ranges':{'הדף היומי':[2,29]},
                'day_pages':{name:days[slug] for slug,name in source_model.DAYS}}
        answers = {'source_sha256':'source','cover_sha256':'cover',
                   'booklet':{'issue':'846','parsha':'ויצא','week_start':'2026-11-15'},
                   'previous_week':{t:None for t in source_model.PREVIOUS}}
        if confirmed:answers['gemara_review'] = review
        with fitz.open() as doc:
            for _ in range(29):doc.new_page()
            with patch.object(source_model,'gemara_identity',side_effect=lambda p:ids[p.number+1]), \
                 patch.object(source_model,'tehillim_regions',return_value={d:[] for d in days}) as teh:
                if confirmed:
                    got = source_model.model(base, answers, doc)
                    self.assertEqual(got['days']['wednesday'],[14,15])
                    self.assertEqual(got['days']['thursday'],list(range(16,22)))
                    return got
                with self.assertRaisesRegex(ValueError,'Ask the owner'):
                    source_model.model(base, answers, doc)
                teh.assert_not_called()

    def test_source_model_stops_before_measurement_without_owner_answer(self):
        self.model_case(False)

    def test_source_model_recomputes_both_affected_days(self):
        self.model_case(True)

    def test_progress_requires_one_field_for_both_confirmed_copies(self):
        _, ids, days, _ = fixture();rows=[{'page':n,'source_page':n} for ns in days.values() for n in ns]
        groups={}
        for day, ns in days.items():
            for n in ns:
                if ids[n] is not None:groups.setdefault((day,ids[n]['key']),[]).append(n)
        progress={'checks':[{'topic_id':'gemara','day_id':day,'pages':ns} for (day,_),ns in groups.items()]}
        validate_progress(progress,rows,days,ids)
        duplicate=next(c for c in progress['checks'] if c['pages']==[17,19]);duplicate['pages']=[17]
        progress['checks'].append({'topic_id':'gemara','day_id':'thursday','pages':[19]})
        with self.assertRaisesRegex(ValueError,'one progress unit'):
            validate_progress(progress,rows,days,ids)


if __name__ == '__main__':unittest.main()
