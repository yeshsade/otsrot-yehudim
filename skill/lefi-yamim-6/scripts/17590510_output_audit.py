"""Independent source-heading and final-PDF audit, not a call to assign_days."""
import argparse
from collections import Counter
import hashlib
import json
import re
import shutil
from pathlib import Path
import fitz
import numpy as np

if not __debug__:
    raise RuntimeError('The audit must run without Python -O/-OO; optimized mode disables required checks')

DAYS=['יום ראשון','יום שני','יום שלישי','יום רביעי','יום חמישי','יום שישי','שבת קודש']
SPECIAL={'פרשת השבוע','תיקון הכללי','השלמות'}


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()


def map_hash(a):
    value={k:a.get(k) for k in ['topic_ranges','day_pages','previous_week_pages','daf_yomi','blank_pages','absent_topics']}
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True).encode()).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write(path,data):
    path=Path(path)
    if path.exists():
        raise ValueError('Refusing to overwrite an existing audit report')
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def white_days(page):
    result=set()
    for block in page.get_text('dict')['blocks']:
        if block['type']!=0:
            continue
        for line in block['lines']:
            for span in line['spans']:
                if span['color']==0xffffff:
                    text=re.sub(r'\s+','',span['text'])
                    result.update(day for day in DAYS if re.sub(r'\s+','',day) in text)
    return result


def review(a,omissions):
    """Build expected topic/day blocks only from original white weekday headings."""
    expected={day:[] for day in DAYS}
    topic_days={}
    context={p for p in a['previous_week_pages'].values() if p is not None}
    gemara=a['daf_yomi']['previous_week_gemara_page']
    if gemara is not None:
        context.add(gemara)
    with fitz.open(a['input_pdf']) as source:
        assert len(source)==a['total_original_pages'],'Source page count differs'
        for topic,(start,end) in a['topic_ranges'].items():
            if topic in SPECIAL:
                continue
            found={day:[] for day in DAYS}
            for n in range(start,end+1):
                if n in context or n in a['blank_pages']:
                    continue
                days=white_days(source[n-1])
                assert len(days)<=1,f'Ambiguous weekday heading on source page {n}'
                for day in days:
                    found[day].append(n)
            assert all(found.values()),f'Missing source weekday heading in {topic}: {found}'
            anchors=[min(found[day]) for day in DAYS]
            assert anchors==sorted(anchors),f'Weekday headings out of order: {topic}'
            if topic=='הדף היומי':
                anchors=[n-1 for n in anchors]
            assert anchors[0]==start,f'Topic start not supported by heading: {topic}'
            groups={}
            for i,day in enumerate(DAYS):
                boundary=anchors[i+1] if i<6 else end+1
                stop=boundary if topic=='תהילים' and i<6 else boundary-1
                pages=[n for n in range(anchors[i],stop+1) if n not in context and n not in a['blank_pages']]
                groups[day]=pages
                expected[day].extend(pages)
            topic_days[topic]=groups
        ordered=sorted((bounds[0],t) for t,bounds in a['topic_ranges'].items() if t not in SPECIAL)
        order={t:i for i,(_n,t) in enumerate(ordered)}
        owner={n:t for t,(s,e) in a['topic_ranges'].items() for n in range(s,e+1)}
        for day in DAYS:
            expected[day].sort(key=lambda n:(order[owner[n]],n))
            assert expected[day]==a['day_pages'][day],f'Source headings contradict day map: {day}'
        covered=set(n for pages in expected.values() for n in pages)|context
        for t in ['פרשת השבוע','תיקון הכללי','השלמות']:
            s,e=a['topic_ranges'][t]
            covered.update(n for n in range(s,e+1) if n not in a['blank_pages'])
        omitted=set(range(1,len(source)+1))-covered
        assert omitted==set(omissions),f'Unexplained omissions: {sorted(omitted-set(omissions))}'
        for n,reason in omissions.items():
            assert 1<=n<=len(source) and reason,'Omission needs a page and reason'
            if n in a['blank_pages']:
                assert not source[n-1].get_text().strip(),'Omitted blank page has text'
    return {'ok':True,'method':'white_weekday_headers_v1','source_sha256':sha(a['input_pdf']),
        'map_sha256':map_hash(a),'expected_day_pages':expected,'source_topic_day_pages':topic_days,
        'omissions':{str(n):reason for n,reason in omissions.items()},
        'previous_week_pages':a['previous_week_pages'],'previous_week_gemara_page':gemara,
        'limitations':'Topic identities and non-learning/blank omissions still require agent visual review. White headings verify day placement, not the semantic identity of a topic.'}


def navigation(doc):
    return [[(l.get('kind'),l.get('page'),l.get('uri'),tuple(round(v,3) for v in l['from']),
              tuple(round(v,3) for v in l.get('to',[])),l.get('file'),l.get('nameddest'))
             for l in page.get_links()] for page in doc]


def pixels(page,zoom=1):
    pix=page.get_pixmap(matrix=fitz.Matrix(zoom,zoom),alpha=False)
    return np.frombuffer(pix.samples,dtype=np.uint8).reshape(pix.height,pix.width,pix.n)[:,:,:3]


def region(rect,shape,padding=0):
    rect=fitz.Rect(rect)
    return (slice(max(0,int(rect.y0-padding)),min(shape[0],int(np.ceil(rect.y1+padding)))),
            slice(max(0,int(rect.x0-padding)),min(shape[1],int(np.ceil(rect.x1+padding)))))


def final(args):
    a=load(args.analysis);r=load(args.review)
    assert r['ok'] and r['map_sha256']==map_hash(a) and r['source_sha256']==sha(a['input_pdf'])
    # Fresh independent scan, not just a trust in the prior JSON flag.
    review(a,{int(n):reason for n,reason in r['omissions'].items()})
    clean=lambda text:re.sub(r'\s+','',text)
    with fitz.open(a['input_pdf']) as source,fitz.open(args.pdf) as output,fitz.open(args.before_widgets) as before:
        assert navigation(output)==navigation(before),'Checkbox stage changed navigation'
        index={}
        for n,p in enumerate(source,1):
            text=clean(p.get_text())
            if len(text)>20:
                assert text not in index,'Ambiguous duplicate source page text'
                index[text]=n
        observed=[index.get(clean(p.get_text())) for p in output]
        occurrences={n:[i+1 for i,v in enumerate(observed) if v==n] for n in range(1,len(source)+1)}
        omitted={n for n,positions in occurrences.items() if not positions}
        assert omitted=={int(n) for n in r['omissions']},f'Missing or unexpected source pages: {omitted}'
        context=[p for p in a['previous_week_pages'].values() if p is not None]
        if a['daf_yomi']['previous_week_gemara_page'] is not None:
            context.append(a['daf_yomi']['previous_week_gemara_page'])
        teh_start,teh_end=a['topic_ranges']['תהילים']
        for n,positions in occurrences.items():
            if len(positions)>1:
                assert teh_start<=n<=teh_end,f'Unplanned repeated source page: {n}'
                assert len(positions)==2,f'Too many Tehillim occurrences: {n}'
        assert all(len(occurrences[n])==1 for n in context),'Previous-week page missing/repeated'
        # Read actual TOC destinations, not engine final-page predictions.
        toc_links=[l for l in output[2].get_links() if l['kind']==fitz.LINK_GOTO]
        day_cards=sorted({l['page']+1 for l in toc_links if observed[l['page']] is None})
        assert len(day_cards)==7,f'Expected seven day-card targets, got {day_cards}'
        assert {i+1 for i,n in enumerate(observed) if n is None}=={1,2,3,len(output),*day_cards},'Unexplained generated/blank output page'
        actual_days={}
        for i,(day,card) in enumerate(zip(DAYS,day_cards)):
            next_card=day_cards[i+1] if i<6 else occurrences[a['topic_ranges']['תיקון הכללי'][0]][0]
            actual=[n for n in observed[card:next_card-1] if n is not None]
            assert actual==r['expected_day_pages'][day],f'Final content differs from original day headings: {day}'
            actual_days[day]=actual
        first_day=day_cards[0]
        parsha=[n for n in observed[3:first_day-1] if n is not None]
        s,e=a['topic_ranges']['פרשת השבוע']
        assert parsha==[n for n in range(s,e+1) if n not in a['blank_pages']]
        # Verify context endpoints directly from source headings and user answers,
        # independently of the builder's continuation-link JSON.
        checked_context=dict(a['previous_week_pages'])
        checked_context['הדף היומי']=a['daf_yomi']['previous_week_gemara_page']
        for topic,n in checked_context.items():
            if n is None:
                continue
            sunday_sources=r['source_topic_day_pages'][topic][DAYS[0]]
            current_source=sunday_sources[1] if topic=='הדף היומי' else sunday_sources[0]
            previous_final=occurrences[n][0]
            current_final=occurrences[current_source][0]
            assert any(l.get('page')==current_final-1 for l in output[previous_final-1].get_links()),f'Wrong previous-week forward endpoint: {topic}'
            assert any(l.get('page')==previous_final-1 for l in output[current_final-1].get_links()),f'Wrong previous-week reciprocal endpoint: {topic}'
            assert any(l.get('page')==2 for l in output[previous_final-1].get_links()),f'Missing TOC return on previous-week page: {topic}'
        continuation=load(args.continuation)
        reciprocal=[]
        for link in continuation['links']:
            if 'previous-week' not in link['type']:
                continue
            s,t=link['source'],link['target']
            assert any(l.get('page')==t-1 for l in output[s-1].get_links()),f'Missing reciprocal link: {s}->{t}'
            reciprocal.append({'topic':link['topic'],'from':s,'to':t})
        assert len(reciprocal)==2*len(context),'Previous-week reciprocal link count differs'
        widgets=links=0;render_stats=[]
        for final_page,page in enumerate(output,1):
            for l in page.get_links():
                if l['kind']==fitz.LINK_GOTO:
                    assert 0<=l['page']<len(output),'Invalid link destination'
                links+=1
            # Compare in unrotated PDF coordinates, without altering the saved file.
            saved_rotation=page.rotation
            page.set_rotation(0)
            # PyMuPDF caches link coordinates in the original rotated view.
            # Reload after changing rotation before measuring/masking controls.
            page=output.reload_page(page)
            page_widgets=list(page.widgets() or [])
            page_links=page.get_links()
            for i,w in enumerate(page_widgets):
                widgets+=1
                for other in page_widgets[i+1:]:
                    assert not (w.rect & other.rect).get_area(),'Widget/widget collision'
                assert not any((w.rect & l['from']).get_area() for l in page_links),'Widget/link collision'
            n=observed[final_page-1]
            if n is not None:
                original=source[n-1]
                original.set_rotation(0)
                original_pixels=pixels(original);result_pixels=pixels(page)
                assert original_pixels.shape==result_pixels.shape,'Source page geometry changed'
                mask=np.zeros(original_pixels.shape[:2],dtype=bool)
                for l in page_links:
                    mask[region(l['from'],mask.shape,3)]=True
                # Use the actual placement zoom/orientation, not a low-resolution
                # floor/ceil crop which includes neighboring pixels outside the field.
                if page_widgets:
                    original.set_rotation(saved_rotation)
                    original=source.reload_page(original)
                    ink_pixels=pixels(original,2.2)
                for w in page_widgets:
                    visual=w.rect*original.rotation_matrix
                    bounds=[v*2.2 for v in visual]
                    x0,y0,x1,y1=bounds
                    sl=(slice(max(0,int(np.floor(y0+0.001))),min(ink_pixels.shape[0],int(np.ceil(y1-0.001)))),
                        slice(max(0,int(np.floor(x0+0.001))),min(ink_pixels.shape[1],int(np.ceil(x1-0.001)))))
                    dark=(np.min(ink_pixels[sl],axis=2)<170)
                    assert dark.size and float(dark.mean())<=0.02,f'Widget covers source ink: output {final_page}'
                    mask[region(w.rect,mask.shape,3)]=True
                original.set_rotation(0)
                source.reload_page(original)
                difference=np.max(np.abs(original_pixels.astype(np.int16)-result_pixels.astype(np.int16)),axis=2)>12
                changed=float(difference[~mask].mean())
                assert changed<=0.001,f'Rendered original content changed: final page {final_page}, fraction {changed}'
                render_stats.append({'final_page':final_page,'source_page':n,'changed_fraction_outside_controls':round(changed,7)})
            page.set_rotation(saved_rotation)
            output.reload_page(page)
        summary=load(args.checkbox_summary)
        assert summary['no_checkbox_overlaps'] is True and summary['navigation_identical'] is True
        report={'ok':True,'pdf':str(Path(args.pdf).resolve()),'pdf_sha256':sha(args.pdf),
            'source_sha256':r['source_sha256'],'page_count':len(output),'unique_source_pages_present':len(source)-len(omitted),
            'source_content_occurrences':sum(v is not None for v in observed),'omissions':r['omissions'],
            'previous_week_pages_once':True,'reciprocal_previous_week_links':reciprocal,
            'day_assignment_verified_against_source_headings':True,'source_text_preserved':True,
            'no_unplanned_duplicates':True,'widget_count':widgets,'link_count':links,
            'no_widget_widget_or_link_collisions':True,'widget_source_ink_overlap_at_most_2_percent':True,
            'ink_overlap_check_zoom':2.2,
            'navigation_preserved':True,'rendered_content_comparison':render_stats,
            'source_to_final_pages':occurrences,'limitations':r['limitations']+' Render comparison excludes control rectangles; boundary previews and controls require visual inspection.'}
        if args.checked_output:
            checked=Path(args.checked_output).resolve()
            assert not checked.exists(),'Refusing to overwrite a checked output'
            assert checked!=Path(args.pdf).resolve(),'Checked output must be a fresh file'
            report['checked_output']=str(checked)
        write(args.report,report)
        if args.checked_output:
            checked.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(args.pdf,checked)
            assert sha(checked)==report['pdf_sha256'],'Checked copy does not match audited PDF'
        print(json.dumps({k:v for k,v in report.items() if k not in ['rendered_content_comparison','source_to_final_pages']},ensure_ascii=False))


def main():
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='mode',required=True)
    rv=sub.add_parser('review');rv.add_argument('--analysis',required=True);rv.add_argument('--report',required=True)
    rv.add_argument('--omit',action='append',default=[],help='original-page=reason, verified visually by agent')
    fv=sub.add_parser('final')
    for flag in ['analysis','review','pdf','before-widgets','continuation','checkbox-summary','report']:
        fv.add_argument('--'+flag,required=True)
    fv.add_argument('--checked-output',help='Copy a fresh deliverable only after every audit passes')
    args=p.parse_args()
    if args.mode=='review':
        omissions={int(s.split('=',1)[0]):s.split('=',1)[1] for s in args.omit}
        result=review(load(args.analysis),omissions);write(args.report,result)
        print('INDEPENDENT_SOURCE_REVIEW=PASS; source white weekday headings match all twelve daily topics')
    else:
        final(args)


if __name__=='__main__':
    main()
