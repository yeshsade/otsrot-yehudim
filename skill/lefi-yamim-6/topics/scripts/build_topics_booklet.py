import argparse,json
from pathlib import Path
import fitz
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
def validated_page(value,label,total):
 try:page=int(value)
 except (TypeError,ValueError) as exc:raise RuntimeError(f'{label} is not a page number: {value!r}') from exc
 if page<1 or page>total:raise RuntimeError(f'{label} page {page} is outside source 1-{total}.')
 return page

def analyze(interior,source_map_path):
 """Build an explicit topics map from a user-confirmed daily source map."""
 source_map=json.loads(Path(source_map_path).read_text(encoding='utf8'))
 manual=source_map.get('manual_user_answers') or {};validation=source_map.get('source_map_validation') or {}
 if manual.get('source_map_confirmed_by_user') is not True or validation.get('ok') is not True:
  raise RuntimeError('Topics build requires the user-confirmed validated source map. Do not infer topic starts from layout alone.')
 doc=fitz.open(interior);total=doc.page_count;doc.close()
 if source_map.get('total_original_pages') and int(source_map['total_original_pages'])!=total:raise RuntimeError('Source map page count does not match the interior PDF.')
 ranges=source_map.get('topic_ranges') or {};absent=set(source_map.get('absent_topics') or [])
 if absent-set(TOPICS):raise RuntimeError('Unknown absent topic(s): '+', '.join(sorted(absent-set(TOPICS))))
 missing=[topic for topic in TOPICS if topic not in ranges]
 if missing:raise RuntimeError('Source map lacks topic range(s): '+', '.join(missing))
 previous=source_map.get('previous_week_pages') or {};starts={};topic_ranges={}
 for topic in TOPICS:
  raw=ranges[topic]
  if not isinstance(raw,(list,tuple)) or len(raw)!=2:raise RuntimeError(f'Invalid source range for {topic}: {raw!r}')
  try:start=int(raw[0]);end=int(raw[1])
  except (TypeError,ValueError) as exc:raise RuntimeError(f'Invalid source range for {topic}: {raw!r}') from exc
  if topic in absent:
   if end>=start:raise RuntimeError(f'{topic} is declared absent but has a nonempty range.')
   topic_ranges[topic]=None;continue
  start=validated_page(start,topic+' start',total)
  if end<start or end>total:raise RuntimeError(f'Invalid active range for {topic}: {start}-{end}.')
  context=previous.get(topic)
  if topic in {'משנה ברורה','ליקוטי מוהר"ן','ליקוטי הלכות'} and context not in (None,'','אין'):
   values=context if isinstance(context,list) else [context]
   start=min([start]+[validated_page(value,topic+' previous-week',total) for value in values])
  starts[topic]=start;topic_ranges[topic]=[start,end]
 blank=sorted({validated_page(value,'blank',total) for value in source_map.get('blank_pages') or []})
 rotated=sorted({validated_page(value,'rotated',total) for value in source_map.get('rotated_original') or []})
 active=[topic for topic in TOPICS if topic not in absent]
 if len(starts)!=len(active):raise RuntimeError('Active topic start count mismatch.')
 return {'input_pdf':str(Path(interior).resolve()),'page_count':total,'blank_pages':blank,'sideways_pages':rotated,'topic_starts_original':starts,'topic_ranges_original':topic_ranges,'absent_topics':sorted(absent),'active_topic_count':len(active),'source_map':str(Path(source_map_path).resolve()),'source_map_confirmed_by_user':True}

def build(a):
 info=json.loads(Path(a.analysis).read_text(encoding='utf8'));source=Path(a.interior).resolve()
 if Path(info.get('input_pdf','')).resolve()!=source:raise RuntimeError('Analysis was generated from a different interior PDF.')
 if info.get('source_map_confirmed_by_user') is not True:raise RuntimeError('Topics analysis lacks source-map confirmation.')
 if Path(a.output).exists():raise RuntimeError('Refusing to overwrite an existing output: '+a.output)
 absent=set(info.get('absent_topics') or []);active=[topic for topic in TOPICS if topic not in absent];starts=info.get('topic_starts_original') or {}
 if set(starts)!=set(active) or int(info.get('active_topic_count',-1))!=len(active):raise RuntimeError('Analysis active topics do not match the topic map.')
 if not all([a.whatsapp_url,a.email_url,a.archive_url]):raise RuntimeError('All three approved external TOC URLs are required.')
 src=fitz.open(str(source));cover=fitz.open(a.cover);toc=fitz.open(a.toc);out=fitz.open()
 try:
  if cover.page_count<4:raise RuntimeError('Cover must contain at least four pages.')
  if toc.page_count!=1:raise RuntimeError('Generated topics TOC must contain exactly one page.')
  out.insert_pdf(cover,from_page=0,to_page=0);out.insert_pdf(toc,from_page=0,to_page=0);mapping={}
  blanks=set(info.get('blank_pages') or []);kept=[n for n in range(1,src.page_count+1) if n not in blanks];runs=[]
  for n in kept:
   if not runs or n!=runs[-1][1]+1:runs.append([n,n])
   else:runs[-1][1]=n
  for start,end in runs:
   first_final=out.page_count;out.insert_pdf(src,from_page=start-1,to_page=end-1)
   for n in range(start,end+1):mapping[n]=first_final+(n-start)
  out.insert_pdf(cover,from_page=3,to_page=3)
  for topic,rect in zip(TOPICS,RECTS):
   if topic in absent:continue
   source_start=int(starts[topic])
   if source_start not in mapping:raise RuntimeError(f'Topic {topic} starts at omitted source page {source_start}.')
   out[1].insert_link({'kind':fitz.LINK_GOTO,'from':fitz.Rect(rect),'page':mapping[source_start],'to':fitz.Point(0,0)})
  for uri,rect in [(a.whatsapp_url,(90.3,600.9,122.0,631.6)),(a.email_url,(137.1,600.9,170.5,631.6)),(a.archive_url,(264.1,600.9,414.5,627.1))]:out[1].insert_link({'kind':fitz.LINK_URI,'from':fitz.Rect(rect),'uri':uri})
  sideways_final={mapping[n] for n in info.get('sideways_pages') or [] if n in mapping}
  for n in range(2,out.page_count):
   page=out[n];sideways=n in sideways_final;rect=fitz.Rect(437,282,467,404) if sideways else fitz.Rect(170,5.3,295,37.3);link_rect=fitz.Rect(437,276.3,467,398.3) if sideways else rect
   page.insert_link({'kind':fitz.LINK_GOTO,'from':link_rect,'page':1,'to':fitz.Point(0,0)});page.insert_image(rect,filename=a.button,overlay=True,rotate=-90 if sideways else 0)
  for n in sideways_final:out[n].set_rotation(270)
  out.save(a.output,garbage=4,deflate=True,deflate_images=True,deflate_fonts=True,clean=False,use_objstms=1)
 finally:src.close();cover.close();toc.close();out.close()
 final=fitz.open(a.output)
 try:
  toc_links=final[1].get_links();internal=[link for link in toc_links if link.get('kind')==fitz.LINK_GOTO];external=[link for link in toc_links if link.get('kind')==fitz.LINK_URI]
  if len(internal)!=len(active) or len(external)!=3:raise RuntimeError(f'TOC link count mismatch: {len(internal)} internal, {len(external)} external.')
  for page_number,page in enumerate(final,1):
   for link in page.get_links():
    if link.get('kind')==fitz.LINK_GOTO and (link.get('page') is None or link['page']<0 or link['page']>=final.page_count):raise RuntimeError(f'Invalid internal target on final page {page_number}.')
 finally:final.close()
 print('FINAL_PDF='+a.output);print('ACTIVE_INTERNAL_TOC_LINKS='+str(len(active)));print('ABSENT_TOPICS='+json.dumps(sorted(absent),ensure_ascii=False))

def main():
 p=argparse.ArgumentParser();s=p.add_subparsers(dest='cmd',required=True);x=s.add_parser('analyze');x.add_argument('--interior',required=True);x.add_argument('--output',required=True);x.add_argument('--source-map',required=True);b=s.add_parser('build')
 for n in ['interior','cover','toc','button','analysis','output']:b.add_argument('--'+n,required=True)
 for n in ['whatsapp-url','email-url','archive-url']:b.add_argument('--'+n,required=True)
 a=p.parse_args()
 if a.cmd=='analyze':
  result=analyze(a.interior,a.source_map);Path(a.output).parent.mkdir(parents=True,exist_ok=True);Path(a.output).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8');print('ANALYSIS_JSON='+a.output);print('ACTIVE_TOPIC_STARTS='+str(result['active_topic_count']))
 else:build(a)
if __name__=='__main__':main()



