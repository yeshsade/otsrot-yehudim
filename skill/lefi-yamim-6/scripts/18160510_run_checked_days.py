"""One fail-closed command: pristine source -> build -> notes -> widgets -> audit -> checked copy."""
import argparse
from datetime import datetime
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
from apply_manual_answers import load_days_engine

ROOT=Path(__file__).resolve().parents[1]
AUDIT=importlib.import_module('17590510_output_audit')


def call(script,*args):
    subprocess.run([sys.executable,str(script),*map(str,args)],check=True,
                   env={**os.environ,'PYTHONIOENCODING':'utf-8'})


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ['analysis','review','work-dir','output','whatsapp-url','email-url','archive-url']:
        p.add_argument('--'+flag,required=True)
    args=p.parse_args()
    a=json.loads(Path(args.analysis).read_text(encoding='utf-8'))
    r=json.loads(Path(args.review).read_text(encoding='utf-8'))
    if Path(args.output).exists():
        raise ValueError('Refusing to overwrite an existing checked output')
    if r['source_sha256']!=AUDIT.sha(a['input_pdf']) or r['map_sha256']!=AUDIT.map_hash(a):
        raise ValueError('Review does not match the current source/map')
    AUDIT.review(a,{int(n):reason for n,reason in r['omissions'].items()})
    stamp=datetime.now().strftime('%H%M%d%m')
    work=Path(args.work_dir).resolve()/f'{stamp}_checked_days_run'
    work.mkdir(parents=True,exist_ok=False)
    engine=load_days_engine()
    color=engine.detect_circle_hex(a['cover_pdf'])
    cfg=a['lefi_yamim_6']
    toc=work/f'{stamp}_toc.pdf';button=work/f'{stamp}_return.png'
    engine.make_toc_pdf(toc,cfg['parsha_name'],cfg['date_label'],color=color,
                        png_output=work/f'{stamp}_toc.png',
                        no_parsha=a.get('manual_user_answers',{}).get('no_parsha_this_week') is True)
    engine.make_return_button_image(button,color=color)
    base=work/f'{stamp}_base.pdf';notes=work/f'{stamp}_notes.pdf';widgets=work/f'{stamp}_widgets.pdf'
    notes_qa=work/f'{stamp}_navigation';widgets_qa=work/f'{stamp}_checkboxes'
    urls=['--whatsapp-url',args.whatsapp_url,'--email-url',args.email_url,'--archive-url',args.archive_url]
    call(ROOT/'days/scripts/build_lefi_yamim.py','build','--workspace',work,
         '--input',a['input_pdf'],'--cover',a['cover_pdf'],'--analysis',args.analysis,
         '--days-toc',toc,'--button-img',button,'--output',base,
         '--label-output',work/f'{stamp}_label.png','--label-preview',work/f'{stamp}_label_preview.png',*urls)
    call(ROOT/'days/scripts/add_day_notes.py','--pdf',base,'--analysis',args.analysis,
         '--color',color,'--notes-dir',work/f'{stamp}_day_cards','--preview-dir',notes_qa,'--output',notes,*urls)
    call(ROOT/'days/scripts/add_vcheck_boxes.py','--pdf',notes,'--analysis',args.analysis,
         '--output',widgets,'--preview-dir',widgets_qa,'--strict')
    report=work/f'{stamp}_final_audit.json'
    call(ROOT/'scripts/17590510_output_audit.py','final','--analysis',args.analysis,
         '--review',args.review,'--pdf',widgets,'--before-widgets',notes,
         '--continuation',notes_qa/'continuation-links.json',
         '--checkbox-summary',widgets_qa/'vcheck-summary.json','--report',report,
         '--checked-output',args.output)
    print('CHECKED_OUTPUT='+str(Path(args.output).resolve()))
    print('FINAL_AUDIT='+str(report))
    print('VISUAL_REVIEW_REQUIRED=cover,TOC,weekday cards,boundary previews,checked states')


if __name__=='__main__':
    main()
