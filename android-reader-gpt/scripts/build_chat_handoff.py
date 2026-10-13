#!/usr/bin/env python3
"""Build an offline, illustrated project handoff without rebuilding the Android app."""
import argparse
import base64
import hashlib
import html
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
READER = ROOT / 'android-reader'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inline(value):
    value = html.escape(value)
    value = re.sub(r'`([^`]+)`', r'<code dir="auto">\1</code>', value)
    value = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', value)
    return value


def render(markdown, pictures):
    """Render the controlled Markdown source, with tables and embedded figures."""
    lines = markdown.splitlines()
    blocks, navigation = [], []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        if line.startswith('```'):
            language, code = line[3:], []
            i += 1
            while i < len(lines) and not lines[i].startswith('```'):
                code.append(lines[i])
                i += 1
            blocks.append('<pre dir="' + ('rtl' if language == 'text' else 'ltr') + '"><code>'
                          + html.escape('\n'.join(code)) + '</code></pre>')
            i += 1
            continue
        heading = re.match(r'^(#{1,4}) (.+)$', line)
        if heading:
            level, title = len(heading[1]), heading[2]
            anchor = f'section-{len(navigation) + 1}' if level == 2 else f'sub-{i}'
            if level == 2:
                navigation.append((anchor, title))
            blocks.append(f'<h{level} id="{anchor}">{inline(title)}</h{level}>')
            i += 1
            continue
        picture = re.fullmatch(r'!\[([^]]*)\]\(([^)]+)\)', line)
        if picture:
            alt, path = picture.groups()
            record = pictures[path]
            style = 'history' if path.split('/')[-1].startswith('history') else 'phone'
            blocks.append(f'<figure class="{style}"><img loading="lazy" src="{record["data"]}" '
                          f'alt="{html.escape(alt, quote=True)}" width="{record["width"]}" '
                          f'height="{record["height"]}"><figcaption>{inline(alt)} · '
                          f'{inline(record["scope"])}</figcaption></figure>')
            i += 1
            continue
        if line.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].startswith('|'):
                cells = [c.strip() for c in lines[i].strip().strip('|').split('|')]
                if not all(re.fullmatch(r':?-+:?', c) for c in cells):
                    rows.append(cells)
                i += 1
            content = []
            for number, cells in enumerate(rows):
                tag = 'th' if number == 0 else 'td'
                content.append('<tr>' + ''.join(f'<{tag} dir="auto">{inline(c)}</{tag}>' for c in cells) + '</tr>')
            blocks.append('<div class="table-wrap"><table><thead>' + content[0] + '</thead><tbody>'
                          + ''.join(content[1:]) + '</tbody></table></div>')
            continue
        if line.startswith('- ') or re.match(r'^\d+\. ', line):
            ordered = not line.startswith('- ')
            tag, items = ('ol' if ordered else 'ul'), []
            pattern = r'^\d+\. (.*)' if ordered else r'^- (.*)'
            while i < len(lines):
                match = re.match(pattern, lines[i])
                if not match:
                    break
                items.append('<li>' + inline(match[1]) + '</li>')
                i += 1
            blocks.append(f'<{tag}>' + ''.join(items) + f'</{tag}>')
            continue
        paragraph = [line]
        i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r'^(#|\||-|```|!\[|\d+\. )', lines[i]):
            paragraph.append(lines[i])
            i += 1
        blocks.append('<p>' + inline(' '.join(paragraph)) + '</p>')
    return '\n'.join(blocks), navigation


def tracked_and_visible(relative):
    output = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard',
                                     '-z', relative], cwd=ROOT)
    return sorted(set(Path(p.decode()) for p in output.split(b'\0') if p))


def source_zip(output, relative, notices=None):
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for relative_path in tracked_and_visible(relative):
            source = ROOT / relative_path
            if source.is_file():
                # Non-runtime upstream promotional/documentation screenshots are not build inputs.
                # Keep the single transfer ZIP below the 32 MiB attachment-reader limit.
                path_text = str(relative_path)
                if path_text.startswith('android-reader/android/assets/screenshots/') or (path_text.startswith('android-reader/android/fastlane/metadata/') and '/images/' in path_text):
                    continue
                if source.suffix in ('.apk', '.aab', '.so', '.keystore', '.jks', '.pyc') or source.name == 'local.properties':
                    raise ValueError(f'Unexpected build/private file: {relative_path}')
                archive.write(source, str(relative_path))
        if notices:
            for source in sorted(notices.rglob('*')):
                if source.is_file():
                    archive.write(source, 'native-license-notices/' + str(source.relative_to(notices)))
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--artifacts', type=Path, default=Path('/workspace/artifacts'))
    args = parser.parse_args()
    out, artifacts = args.output.resolve(), args.artifacts.resolve()
    out.mkdir(parents=True, exist_ok=True)
    native = artifacts / 'pdf-reader-native-settings-2026-10-13'
    approved_path = READER / 'design/settings-copy.approved.json'
    approved = json.loads(approved_path.read_text())
    assert approved['status'] == 'approved' and approved['version'] == 4 and len(approved['entries']) == 37
    source_copy = json.dumps(approved['entries'], ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    assert hashlib.sha256(source_copy.encode()).hexdigest() == approved['entries_sha256']
    shutil.copy2(approved_path, out / 'settings-copy.approved.json')

    rows = ['| מזהה | עברית | אנגלית | משמעות בעברית |', '| --- | --- | --- | --- |']
    for entry in approved['entries']:
        cells = [entry['id'], entry['he'] or '— תיאור מוסתר —', entry['en'] or '— hidden description —', entry['meaning']]
        assert not any('|' in c or '\n' in c for c in cells)
        rows.append('| ' + ' | '.join(cells) + ' |')
    markdown = (READER / 'docs/NEW_CHAT_HANDOFF.md').read_text()
    markdown = markdown.replace('<!-- APPROVED_COPY_TABLE -->', '\n'.join(rows))
    assert '<!-- APPROVED_COPY_TABLE -->' not in markdown
    (out / 'HANDOFF.md').write_text(markdown)
    prompt = re.search(r'```text\n(.*?)\n```', markdown, re.S)[1]
    (out / 'START_NEW_CHAT.txt').write_text(prompt + '\n')

    from PIL import Image
    records = [
        ('selected-library-day.png', 'pdf-reader-day-night-2026-10-12/mobile-library-day.png', 'המחשת HTML · הכיוון שנבחר'),
        ('selected-library-night.png', 'pdf-reader-day-night-2026-10-12/mobile-library-night.png', 'המחשת HTML · הכיוון שנבחר'),
        ('selected-reader-day.png', 'pdf-reader-day-night-2026-10-12/mobile-reader-day.png', 'המחשת HTML · הכיוון שנבחר'),
        ('selected-reader-night.png', 'pdf-reader-day-night-2026-10-12/mobile-reader-night.png', 'המחשת HTML · הכיוון שנבחר'),
        ('native-settings-he-day.png', 'pdf-reader-native-settings-2026-10-13/settings-native-he-day.png', 'Android Views דרך Canvas · מימוש קיים'),
        ('native-settings-en-night.png', 'pdf-reader-native-settings-2026-10-13/settings-native-en-night.png', 'Android Views דרך Canvas · מימוש קיים'),
        ('history-three-designs.png', 'pdf-reader-visual-directions-2026-10-12/three-designs.png', 'היסטוריה · אין לבחור מחדש'),
        ('history-refinements.png', 'pdf-reader-refinement-2026-10-12/library-comparison.png', 'היסטוריה · הבחירה הסופית היא ב'),
    ]
    pictures = {}
    (out / 'images').mkdir(exist_ok=True)
    for name, origin, scope in records:
        source = artifacts / origin
        shutil.copy2(source, out / 'images' / name)
        with Image.open(source) as im:
            width, height = im.size
        pictures['images/' + name] = dict(source=origin, scope=scope, width=width, height=height,
                                        sha256=digest(source), data='data:image/png;base64,' + base64.b64encode(source.read_bytes()).decode())

    body, navigation = render(markdown, pictures)
    nav = ''.join(f'<a href="#{anchor}">{html.escape(label)}</a>' for anchor, label in navigation)
    payload = json.dumps(dict(document=markdown, prompt=prompt), ensure_ascii=False).replace('<', '\\u003c')
    font = READER / 'android/app/src/main/assets/fonts/NotoSansHebrew-Regular.ttf'
    font64 = base64.b64encode(font.read_bytes()).decode()
    style = f'''@font-face{{font-family:handoff;src:url(data:font/ttf;base64,{font64}) format("truetype");font-display:swap}}'''
    style += '''
    :root{--bg:#f5f4f9;--paper:#fff;--ink:#28243d;--muted:#656175;--line:#ded9e9;--brand:#57479f;--soft:#eeebf6}
    [data-theme=night]{--bg:#17161f;--paper:#24222f;--ink:#f3f0fa;--muted:#c0b7d0;--line:#464053;--brand:#c4b5ff;--soft:#343044}
    *{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:20px}body{margin:0;background:var(--bg);color:var(--ink);font:17px/1.8 handoff,Arial,sans-serif}
    .shell{max-width:1240px;margin:auto;padding:36px 24px}.mast{padding:28px;background:var(--paper);border:1px solid var(--line);border-radius:18px}
    .eyebrow{color:var(--brand);font-size:14px;font-weight:bold}h1{font-size:30px;line-height:1.4}h2{font-size:25px;margin-top:42px;border-top:1px solid var(--line);padding-top:24px}h3{font-size:20px}
    .layout{display:grid;grid-template-columns:220px minmax(0,1fr);gap:28px;margin-top:28px}nav{position:sticky;top:20px;align-self:start;max-height:90vh;overflow:auto;background:var(--paper);border:1px solid var(--line);border-radius:14px;padding:14px}
    nav a{display:block;color:var(--ink);font-size:13px;text-decoration:none;border-bottom:1px solid var(--line);padding:8px}nav a:hover{background:var(--soft)}article{min-width:0;background:var(--paper);border:1px solid var(--line);border-radius:18px;padding:24px 32px}article>h1{display:none}
    a{color:var(--brand)}p,li{overflow-wrap:anywhere}li{margin-bottom:10px}ul,ol{padding-inline-start:24px}code{font-size:14px;overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;border:1px solid var(--line);background:var(--soft);padding:18px;border-radius:10px;line-height:1.65}
    .table-wrap{overflow-x:auto;margin:22px 0;border:1px solid var(--line);border-radius:10px}table{border-collapse:collapse;width:100%;font-size:14px}th,td{padding:12px;text-align:start;border-bottom:1px solid var(--line);vertical-align:top;min-width:100px;overflow-wrap:anywhere}th{background:var(--soft)}
    figure{margin:26px auto;border:1px solid var(--line);padding:12px;border-radius:14px;background:var(--bg)}figure.phone{max-width:400px}img{max-width:100%;height:auto;display:block;margin:auto;border-radius:8px}figcaption{font-size:13px;line-height:1.6;margin-top:12px;color:var(--muted)}
    .actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:20px}button,.button{font:inherit;font-size:14px;min-height:44px;padding:10px 16px;border:1px solid var(--line);border-radius:10px;background:var(--soft);color:var(--ink);cursor:pointer;text-decoration:none;display:inline-block}button.primary{background:var(--brand);color:var(--paper)}
    #copy-status{font-size:14px;color:var(--brand)}#copy-fallback{width:100%;min-height:170px;font:inherit;direction:rtl;margin-top:14px}footer{font-size:13px;color:var(--muted);padding:20px}button:focus-visible,a:focus-visible{outline:3px solid var(--brand);outline-offset:3px}
    @media(max-width:800px){.shell{padding:16px 10px}.mast{padding:20px}.layout{display:block}nav{position:static;max-height:220px;margin-bottom:18px}article{padding:16px}h1{font-size:26px}h2{font-size:22px}body{font-size:16px}figure{padding:8px}}
    @media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}@media print{.shell{max-width:none;padding:0}body{background:white;color:black;font-size:11pt}.actions,nav,#copy-status,#copy-fallback{display:none}.layout{display:block}article,.mast{border:0;padding:0}figure{break-inside:avoid;max-width:310px!important}h2{break-after:avoid}table{font-size:9pt}pre{font-size:9pt}}
    '''
    download_md = 'data:text/markdown;charset=utf-8;base64,' + base64.b64encode(markdown.encode()).decode()
    document = f'''<!doctype html><html lang="he" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>חבילת מעבר — קורא PDF לאנדרואיד</title><style>{style}</style></head><body><div class="shell">
    <header class="mast"><div class="eyebrow">העברה לשיחה חדשה · 13.10.2026 · מסמך 1</div><h1>קורא PDF לאנדרואיד — כל מה שצריך להמשך</h1><p>ההחלטות, העיצוב, מצב הקוד והעבודה שנותרה. יש גרסת ניסיון עם מסך הגדרות חדש; המוצר עדיין לא הושלם. זהו סיכום מסודר, ולא תמליל מלא.</p><p>התמונות והנוסחים כלולים בתוך הקובץ והוא נפתח בלי אינטרנט. להמשך פיתוח יש לצרף גם את ZIP החבילה, שמכיל את הקוד.</p>
    <div class="actions"><button class="primary" id="copy-prompt">העתקת פרומפט הפתיחה</button><button id="copy-document">העתקת המסמך</button><a class="button" download="HANDOFF.md" href="{download_md}">שמירת MD</a><button id="theme-toggle" aria-pressed="false">יום / לילה במסמך</button><button id="print">הדפסה / PDF</button></div><p id="copy-status" role="status" aria-live="polite"></p><textarea id="copy-fallback" aria-label="טקסט להעתקה ידנית" readonly hidden></textarea></header>
    <main class="layout"><nav aria-label="תוכן עניינים">{nav}</nav><article>{body}</article></main><footer>מסמך העברה בלבד. תמונות ההמחשה אינן מסכי האפליקציה המוגמרת; הראיות והמגבלות מתוארות בסעיפים 9–10. מקור הגופן Noto Sans Hebrew ברישיון OFL המצורף לחבילה.</footer></div>
    <script type="application/json" id="handoff-source">{payload}</script><script>
    const source=JSON.parse(document.getElementById('handoff-source').textContent);
    async function copy(part){{const value=source[part];try{{if(!navigator.clipboard)throw new Error('fallback');await navigator.clipboard.writeText(value);document.getElementById('copy-status').textContent='הועתק. אפשר להדביק בשיחה החדשה.'}}catch(e){{const field=document.getElementById('copy-fallback');field.hidden=false;field.value=value;field.focus();field.select();document.getElementById('copy-status').textContent='הטקסט נבחר בתיבה. אפשר להעתיק ידנית.'}}}}
    document.getElementById('copy-prompt').addEventListener('click',()=>copy('prompt'));
    document.getElementById('copy-document').addEventListener('click',()=>copy('document'));
    document.getElementById('theme-toggle').addEventListener('click',()=>{{const night=document.documentElement.dataset.theme!=='night';document.documentElement.dataset.theme=night?'night':'day';document.getElementById('theme-toggle').setAttribute('aria-pressed',String(night))}});
    document.getElementById('print').addEventListener('click',()=>window.print());
    </script></body></html>'''
    (out / 'HANDOFF.html').write_text(document)

    (out / 'evidence').mkdir(exist_ok=True)
    for name in ('verification.json', 'native-probe.txt', 'apk-package-checks.json', 'build.log',
                 'native-checkbox-checked.pdf', 'native-checkbox-cleared.pdf'):
        shutil.copy2(native / name, out / 'evidence' / name)
    shutil.copy2(READER / 'android/app/src/androidTest/assets/synthetic-checkbox-source.pdf', out / 'evidence/synthetic-checkbox-source.pdf')
    shutil.copy2(native / 'pdf-reader-settings-0.1.0-dev.apk', out / 'pdf-reader-settings-0.1.0-dev.apk')
    install = (native / 'INSTALL.md').read_text().replace('pdf-reader-settings-0.1.0-dev-source.zip', 'pdf-reader-source.zip').replace('pdf-reader-restart-brief.md / .html', 'HANDOFF.md / .html')
    (out / 'INSTALL.md').write_text(install)
    (out / 'references').mkdir(exist_ok=True)
    for name in ('RESTART_BRIEF.md', 'PRODUCT_INTERVIEW.md', 'PROJECT_INSTRUCTIONS.md', 'FIRST_RELEASE_PLAN.md',
                 'VISUAL_DIRECTIONS.md', 'DESIGN_WORKFLOW.md', 'SETTINGS_REVIEW.md', 'NATIVE_SETTINGS.md',
                 'PROGRESS_TRACKING_OPTIONS.md', 'ANDROID_BUILD_STATUS.md', 'UPSTREAM.md'):
        shutil.copy2(READER / 'docs' / name, out / 'references' / name)
    (out / 'references/README.md').write_text('ארכיון להעמקה. HANDOFF.md והנוסחים המאושרים קובעים את המצב הנוכחי. אמירות ישנות כגון אין APK, טיוטה או פיתוח מושהה נשמרו כהיסטוריה בלבד.\n')
    shutil.copy2(READER / 'design/revisions/settings-copy.user-2026-10-12.json', out / 'references/settings-copy.user-original.json')
    shutil.copy2(READER / 'design/assets/fonts/NotoSansHebrew-OFL.txt', out / 'FONT-LICENSE.txt')
    source_zip(out / 'pdf-reader-source.zip', 'android-reader', native / 'native-license-notices')
    source_zip(out / 'booklet-skill-source.zip', 'skills/lefi-yamim-6')
    shutil.copy2(ROOT / 'docs/BOOKLET_SKILL_STATUS.md', out / 'references/BOOKLET_SKILL_STATUS.md')
    (out / 'START_HERE.md').write_text('''# העברה לשיחה חדשה

לצרף לשיחה החדשה את pdf-reader-new-chat-handoff-2026-10-13.zip ולהדביק את START_NEW_CHAT.txt.
אם אפשר לצרף רק מעט קבצים: HANDOFF.html, settings-copy.approved.json ו־pdf-reader-source.zip.

HANDOFF.html מכיל את התמונות והנוסחים ונפתח בלי אינטרנט; HANDOFF.md הוא סיכום קריא לשיחה, עם התמונות בתיקיית images. HTML או MD לבדם אינם קוד מקור של האפליקציה.

זו גרסת ניסיון חלקית: מסך הגדרות Android חדש וקורא מיובא. שמירה אוטומטית ושאר דרישות המוצר טרם הושלמו. תחילה ממשיכים בשמירה ובטיפול בקבצים. אין להתחיל שוב את השאלון או את בחירת העיצוב.

הקוד כלול בחבילה משום שהשינויים המקומיים לא נדחפו ל־GitHub. אין כאן SDK, מפתחות חתימה, סודות, קובצי מקור אמיתיים מהמחשב של המשתמש או גישה למחשב Windows.
''')
    image_metadata = {name: {k: v for k, v in record.items() if k != 'data'} for name, record in pictures.items()}
    files = {str(p.relative_to(out)): dict(bytes=p.stat().st_size, sha256=digest(p))
             for p in sorted(out.rglob('*')) if p.is_file() and p.name not in ('MANIFEST.json', 'handoff-validation.json')}
    manifest = dict(created_on='2026-10-13', document_version=1, scope='conversation summary, not full transcript',
                    approved_copy_version=4, approved_entries_sha256=approved['entries_sha256'],
                    upstream_commit='30cca2df37f65c19ff4bf31544fb8ec4e6bc9d2a',
                    images=image_metadata, files=files, source_excludes=['SDK', 'build caches', 'native .so outputs', 'signing keys', 'secrets', 'non-runtime upstream documentation/store screenshots'],
                    application_tests='Previously executed; not rerun to produce this handoff')
    (out / 'MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(output=str(out), files=len(files), images=len(pictures), html_bytes=(out / 'HANDOFF.html').stat().st_size,
                          source_bytes=(out / 'pdf-reader-source.zip').stat().st_size), ensure_ascii=False))


if __name__ == '__main__':
    main()
