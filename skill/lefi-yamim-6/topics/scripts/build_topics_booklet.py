import argparse,json
from pathlib import Path
import fitz
import sys, importlib
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
contract = importlib.import_module('16060510_source_contract')
TOPICS=['הדף היומי','פרשת השבוע','נביאים וכתובים','משנה','שולחן ערוך','משנה ברורה','זוהר','תהילים','ליקוטי מוהר"ן','ליקוטי הלכות','ספרי רבינו על פי הסדר','שמות הצדיקים / ספר המידות','ליקוטי תפילות','תיקון הכללי','השלמות']
RECTS=[(242.7,145.8,435.9,178.8),(33.4,145.8,226.6,178.8),(242.7,187.2,435.9,219.9),(33.4,187.2,226.6,219.9),(242.7,229.0,435.9,261.1),(33.4,229.0,226.6,261.1),(242.7,270.5,435.9,302.9),(33.4,270.5,226.6,302.9),(242.7,311.6,435.9,344.7),(33.4,311.6,226.6,344.7),(242.7,353.1,435.9,386.1),(33.4,353.1,226.6,386.1),(242.7,394.5,435.9,427.2),(33.4,394.5,226.6,427.2),(138.0,436.3,331.3,468.7)]
def sunday(doc):
 r=[]
 for i,p in enumerate(doc):
  yes=False
  for b in p.get_text('rawdict')['blocks']:
   if b['type']!=0:continue
   for l in b['lines']:
    for s in l['spans']:
     t=s.get('text','') or ''.join(c.get('c','') for c in s.get('chars',[]))
     if s.get('color')==0xffffff and 'יום ראשון' in t:yes=True;break
    if yes:break
   if yes:break
  if yes and i+1!=3:r.append(i+1)
 return r
def sideways(doc):
 r=[]
 for i,p in enumerate(doc):
  ds=[l.get('dir',(1,0)) for b in p.get_text('rawdict')['blocks'] if b['type']==0 for l in b['lines']]
  if ds and sum(abs(y)>abs(x) for x,y in ds)/len(ds)>.55:r.append(i+1)
 return r
def analyze(path):
 d=fitz.open(path); ss=sunday(d); side=sideways(d); blanks=[i+1 for i,p in enumerate(d) if len(p.get_text().strip())<5]
 if len(ss)<11 or not side:raise RuntimeError('Topic or sideways-page detection failed.')
 starts={TOPICS[0]:2,TOPICS[1]:min(side)}
 starts.update(dict(zip(TOPICS[2:13],ss)))
 starts[TOPICS[13]]=starts[TOPICS[12]]+7;starts[TOPICS[14]]=starts[TOPICS[13]]+2
 d.close();return {'blank_pages':blanks,'sideways_pages':side,'topic_starts_original':starts}
def validate_topic_links(path,starts,mp):
 d=fitz.open(path);p=d[1];links=p.get_links();validated={}
 for topic,rect in zip(TOPICS,RECTS):
  if topic not in starts:continue
  box=fitz.Rect(rect);matches=[link for link in links if link.get('kind')==fitz.LINK_GOTO and fitz.Rect(link['from']).intersects(box)]
  expected=mp[starts[topic]]
  if len(matches)!=1 or matches[0].get('page')!=expected:
   d.close();raise RuntimeError(f'TOC link mismatch for {topic}: expected final page {expected+1}')
  validated[topic]=expected+1
 d.close()
 if len(validated)!=len(starts):raise RuntimeError('Active topic link count differs.')
 return validated
def build(a):
 info=json.loads(Path(a.analysis).read_text(encoding='utf8'))
 if Path(a.output).exists():raise RuntimeError('Refusing to overwrite an existing output')
 if Path(info.get('input_pdf','')).resolve()!=Path(a.interior).resolve() or info.get('source_sha256')!=contract.fingerprint(a.interior):raise RuntimeError('Topics analysis does not match the source PDF')
 if not info.get('topic_starts_applied_by_script') or not info.get('source_map_confirmed_by_user'):raise RuntimeError('Apply the recorded answers and review the source map first')
 absent=set(info.get('absent_topics',[]));starts=info['topic_starts_original']
 if absent-{'פרשת השבוע','השלמות'} or set(starts)!=set(TOPICS)-absent:raise RuntimeError('Active/absent topic map is incomplete')
 if not all([a.whatsapp_url,a.email_url,a.archive_url]):raise RuntimeError('All three external TOC URLs are required')
 src=fitz.open(a.interior);cover=fitz.open(a.cover);toc=fitz.open(a.toc);out=fitz.open();out.insert_pdf(cover,from_page=0,to_page=0);out.insert_pdf(toc,from_page=0,to_page=0);mp={}
 # Insert contiguous ranges together so shared fonts/images are not duplicated.
 blanks=set(info['blank_pages']);kept=[n for n in range(1,src.page_count+1) if n not in blanks];runs=[]
 for n in kept:
  if not runs or n!=runs[-1][1]+1:runs.append([n,n])
  else:runs[-1][1]=n
 for start,end in runs:
  first_final=out.page_count;out.insert_pdf(src,from_page=start-1,to_page=end-1)
  for n in range(start,end+1):mp[n]=first_final+(n-start)
 out.insert_pdf(cover,from_page=3,to_page=3)
 for topic,rect in zip(TOPICS,RECTS):
  if topic in starts:out[1].insert_link({'kind':fitz.LINK_GOTO,'from':fitz.Rect(rect),'page':mp[starts[topic]],'to':fitz.Point(0,0)})
 for uri,rect in [(a.whatsapp_url,(90.3,600.9,122.0,631.6)),(a.email_url,(137.1,600.9,170.5,631.6)),(a.archive_url,(264.1,600.9,414.5,627.1))]:
  if uri:out[1].insert_link({'kind':fitz.LINK_URI,'from':fitz.Rect(rect),'uri':uri})
 sideways_final=set(mp[n] for n in info['sideways_pages'] if n in mp)
 for n in range(2,out.page_count):
  p=out[n];sideways=n in sideways_final;rect=fitz.Rect(437,282,467,404) if sideways else fitz.Rect(170,5.3,295,37.3);link_rect=fitz.Rect(437,276.3,467,398.3) if sideways else rect;p.insert_link({'kind':fitz.LINK_GOTO,'from':link_rect,'page':1,'to':fitz.Point(0,0)});p.insert_image(rect,filename=a.button,overlay=True,rotate=-90 if sideways else 0)
 for n in sideways_final:out[n].set_rotation(270)
 out.save(a.output,garbage=4,deflate=True,deflate_images=True,deflate_fonts=True,clean=False,use_objstms=1);out.close()
 validated=validate_topic_links(a.output,info['topic_starts_original'],mp)
 print('TOPIC_LINKS_VALIDATED='+str(len(validated)));print('TOPIC_LINK_TARGETS='+json.dumps(validated,ensure_ascii=False));print('FINAL_PDF='+a.output)
def main():
 p=argparse.ArgumentParser();s=p.add_subparsers(dest='cmd',required=True);x=s.add_parser('analyze');x.add_argument('--interior',required=True);x.add_argument('--output',required=True);b=s.add_parser('build')
 for n in ['interior','cover','toc','button','analysis','output']:b.add_argument('--'+n,required=True)
 for n in ['whatsapp-url','email-url','archive-url']:b.add_argument('--'+n)
 a=p.parse_args();
 if a.cmd=='analyze':Path(a.output).write_text(json.dumps(analyze(a.interior),ensure_ascii=False,indent=2),encoding='utf8')
 else:build(a)
if __name__=='__main__':main()



