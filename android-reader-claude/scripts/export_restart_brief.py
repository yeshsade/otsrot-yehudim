#!/usr/bin/env python3
"""Export the restart brief as Markdown and a standalone Hebrew HTML document."""

import argparse
import html
import json
import re
from pathlib import Path


def inline(text):
    escaped = html.escape(text)
    escaped = re.sub(r"`([^`]+)`", r'<code dir="ltr">\1</code>', escaped)
    return re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", escaped)


def render(markdown):
    blocks, toc, paragraph = [], [], []
    list_kind = None
    fenced = False
    code_lines = []

    def flush():
        nonlocal list_kind
        if paragraph:
            blocks.append("<p>" + inline(" ".join(paragraph)) + "</p>")
            paragraph.clear()
        if list_kind:
            blocks.append(f"</{list_kind}>")
            list_kind = None

    for line in markdown.splitlines():
        if line.startswith("```"):
            if fenced:
                blocks.append('<pre dir="rtl"><code>' + html.escape("\n".join(code_lines)) + "</code></pre>")
                code_lines = []
            else:
                flush()
            fenced = not fenced
            continue
        if fenced:
            code_lines.append(line)
            continue
        heading = re.match(r"^(#{1,4}) (.+)$", line)
        if heading:
            flush()
            level, title = len(heading[1]), heading[2]
            if level == 1:
                continue  # The title is displayed in the document header.
            if level == 2:
                anchor = f"section-{len(toc) + 1}"
                toc.append((anchor, title))
                blocks.append(f'<h2 id="{anchor}">{inline(title)}</h2>')
            else:
                blocks.append(f"<h{level}>{inline(title)}</h{level}>")
            continue
        item = re.match(r"^(?:([-*]) |(\d+)\. )(.+)$", line)
        if item:
            kind = "ul" if item[1] else "ol"
            if paragraph or list_kind != kind:
                flush()
            if not list_kind:
                blocks.append(f"<{kind}>")
                list_kind = kind
            blocks.append("<li>" + inline(item[3]) + "</li>")
        elif not line.strip():
            flush()
        else:
            if list_kind:
                flush()
            paragraph.append(line)
    flush()
    if fenced:
        raise ValueError("Unclosed Markdown code block")
    return "\n".join(blocks), toc


STYLE = """
:root{color-scheme:light;--paper:#fff;--canvas:#f3f5f4;--ink:#172b2a;--muted:#526562;--accent:#176557;--line:#dbe5e1}
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:var(--canvas);color:var(--ink);font:17px/1.85 Arial,sans-serif}
.wrap{max-width:1120px;margin:auto;padding:36px 28px}.eyebrow{color:var(--accent);font-weight:bold;font-size:14px}
h1{font-size:clamp(27px,4vw,39px);line-height:1.35;margin:12px 0}header p{max-width:760px;color:var(--muted);margin:0 0 18px}
.tags{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}.tag{border:1px solid var(--line);background:var(--paper);border-radius:20px;padding:3px 13px;font-size:14px}
.actions{display:flex;flex-wrap:wrap;gap:10px;margin:22px 0 10px}button{font:inherit;font-size:15px;font-weight:bold;min-height:46px;border:1px solid var(--accent);border-radius:10px;padding:9px 18px;background:var(--accent);color:white;cursor:pointer}
button.secondary{background:var(--paper);color:var(--accent)}button:hover{filter:brightness(.95)}a{color:var(--accent)}a:focus-visible,button:focus-visible,textarea:focus-visible{outline:3px solid #ca8a04;outline-offset:4px}
#status{min-height:28px;font-size:14px;color:var(--accent)}.layout{display:grid;grid-template-columns:230px minmax(0,1fr);gap:28px;align-items:start;margin-top:20px}
nav{position:sticky;top:20px;padding:18px;border:1px solid var(--line);border-radius:12px;background:var(--paper);font-size:14px;line-height:1.6}nav strong{display:block;margin-bottom:8px}nav a{display:block;text-decoration:none;padding:7px 0;border-bottom:1px solid var(--line)}nav a:last-child{border-bottom:0}
article{background:var(--paper);padding:28px 34px;border:1px solid var(--line);border-radius:14px;min-width:0}h2{font-size:23px;line-height:1.5;margin:36px 0 16px;scroll-margin-top:24px;border-top:1px solid var(--line);padding-top:24px}h2:first-child{margin-top:0}h3{font-size:19px;margin:26px 0 10px}p{margin:0 0 16px}ul,ol{padding-inline-start:25px;margin:10px 0 20px}li{padding-inline-start:4px;margin-bottom:10px}
code{font:14px/1.7 monospace;overflow-wrap:anywhere;unicode-bidi:isolate;background:#eef3f1;border-radius:4px;padding:2px 4px}pre{white-space:pre-wrap;overflow-wrap:anywhere;border:1px solid var(--line);border-radius:10px;background:#f4f8f6;padding:20px;font:16px/1.9 Arial,sans-serif}pre code{font:inherit;background:transparent;padding:0;unicode-bidi:normal}
footer{color:var(--muted);font-size:13px;margin-top:28px}#fallback{width:100%;min-height:220px;direction:rtl;font:16px/1.7 Arial,sans-serif;margin-top:12px;border:1px solid var(--line);border-radius:10px;padding:16px}
@media(max-width:820px){.wrap{padding:22px 16px}.layout{grid-template-columns:1fr;gap:18px}nav{position:static}nav a{display:inline-block;margin-inline-end:16px;border:0}article{padding:22px 20px}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}
@media print{body{background:white;font-size:12pt}.wrap{max-width:none;padding:0}.actions,.tags,nav,#status,#fallback{display:none}.layout{display:block;margin:0}article{border:0;padding:0}header{border-bottom:1px solid #999;margin-bottom:20px}h2,h3{break-after:avoid}pre{font-size:11pt}footer{font-size:9pt}}
"""


SCRIPT = """
const source = JSON.parse(document.getElementById('handoff-source').textContent);
const status = document.getElementById('status');
async function copyPart(part) {
  const value = source[part];
  try {
    if (!navigator.clipboard || !window.isSecureContext) throw new Error('Use fallback');
    await navigator.clipboard.writeText(value);
    status.textContent = part === 'document' ? 'המסמך הועתק. אפשר להדביק בשיחה חדשה.' : 'פרומפט הפתיחה הועתק.';
  } catch (_) {
    const field = document.getElementById('fallback');
    field.hidden = false;
    field.value = value;
    field.focus();
    field.select();
    let copied = false;
    try { copied = document.execCommand('copy'); } catch (_) {}
    status.textContent = copied ? 'הטקסט הועתק.' : 'הטקסט נבחר בתיבה. אפשר להעתיק ידנית או להשתמש בקובץ Markdown.';
    if (copied) field.hidden = true;
  }
}
document.getElementById('copy-document').addEventListener('click', () => copyPart('document'));
document.getElementById('copy-prompt').addEventListener('click', () => copyPart('prompt'));
document.getElementById('print-document').addEventListener('click', () => window.print());
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Directory for the portable files")
    args = parser.parse_args()
    source_path = Path(__file__).resolve().parents[1] / "docs" / "RESTART_BRIEF.md"
    markdown = source_path.read_text(encoding="utf-8")
    prompt_match = re.search(r"```text\n(.*?)\n```", markdown, re.S)
    if not prompt_match:
        raise ValueError("Missing restart prompt")
    prompt = prompt_match[1]
    body, toc = render(markdown)
    title = markdown.splitlines()[0].removeprefix("# ")
    version = re.search(r"גרסה: (\d+)", markdown)[1]
    date_match = re.search(r"תאריך: (\d{4})-(\d{2})-(\d{2})", markdown)
    if not date_match:
        raise ValueError("Missing document date")
    display_date = ".".join((date_match[3], date_match[2], date_match[1]))
    navigation = "\n".join(f'<a href="#{anchor}">{html.escape(label)}</a>' for anchor, label in toc)
    payload = json.dumps({"document": markdown, "prompt": prompt}, ensure_ascii=False).replace("<", "\\u003c")
    document = f'''<!doctype html>
<html lang="he" dir="rtl">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title><style>{STYLE}</style></head>
<body><div class="wrap">
<header><div class="eyebrow">מסמך אפיון להעברה • גרסה {version} • {display_date}</div>
<h1>{html.escape(title)}</h1>
<p>אפשר לשמור את הקובץ, לצרף אותו לשיחה אחרת או להעתיק את התוכן. המסמך מרכז את דרישות המוצר ואת ההחלטות הפתוחות. כיוון העיצוב נבחר ונוסחי גרסה 4 אושרו; שינויי נוסח עתידיים דורשים אישור.</p>
<div class="tags"><span class="tag">כולל פרומפט פתיחה</span><span class="tag">מתאים גם לבנייה מאפס</span><span class="tag">נפתח ללא אינטרנט</span></div>
<div class="actions"><button id="copy-document" type="button">העתקת המסמך המלא</button><button id="copy-prompt" class="secondary" type="button">העתקת פרומפט הפתיחה</button><button id="print-document" class="secondary" type="button">הדפסה / שמירה כ־PDF</button></div>
<div id="status" role="status" aria-live="polite"></div><textarea id="fallback" aria-label="טקסט להעתקה ידנית" readonly hidden></textarea></header>
<main class="layout"><nav aria-label="תוכן עניינים"><strong>בתוך המסמך</strong>{navigation}</nav><article>{body}</article></main>
<footer>קובץ עצמאי, ללא ספריות חיצוניות או טעינת נתונים מהרשת. כפתורי ההעתקה פועלים רק בלחיצה. אפיון אינו הוכחה שאפליקציה נבנתה או נבדקה.</footer>
</div><script type="application/json" id="handoff-source">{payload}</script><script>{SCRIPT}</script></body></html>
'''
    args.output.mkdir(parents=True, exist_ok=True)
    markdown_path = args.output / "pdf-reader-restart-brief.md"
    html_path = args.output / "pdf-reader-restart-brief.html"
    markdown_path.write_text(markdown, encoding="utf-8")
    html_path.write_text(document, encoding="utf-8")
    print(markdown_path)
    print(html_path)


if __name__ == "__main__":
    main()
