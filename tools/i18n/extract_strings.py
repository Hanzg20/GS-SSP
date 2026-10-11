"""Move hard-coded UI text in Compose files into Android string resources.

    python tools/i18n/extract_strings.py <module_res_dir> <R_import> <prefix> <file.kt>...

Replaces plain literals (no "$" templates) in the safe Compose spots
    Text("..."), label/placeholder/title = { Text("...") }, contentDescription = "..."
with stringResource(R.string.<prefix>_<words>), adds the imports, and appends
the English text to <module_res_dir>/values/strings.xml. Anything else (text
built with "$", strings used outside composables) is left for a human -- run
again after editing. Prints every key it added, for the translations.
"""
import os, re, sys
from xml.sax.saxutils import escape

res_dir, r_import, prefix, files = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
values = os.path.join(res_dir, 'values', 'strings.xml')
os.makedirs(os.path.dirname(values), exist_ok=True)
existing = open(values, encoding='utf-8').read() if os.path.exists(values) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
keys = dict(re.findall(r'<string name="([^"]+)">(.*?)</string>', existing))
by_text = {v: k for k, v in keys.items()}
added = []

SPOTS = [
    re.compile(r'(?<![\w.])Text\(\s*"((?:[^"\\$]|\\.)*)"'),
    re.compile(r'(contentDescription\s*=\s*)"((?:[^"\\$]|\\.)*)"'),
]


def android_escape(t):
    # Kotlin literal -> resource text: unescape Kotlin, escape for aapt.
    t = t.replace('\\"', '"').replace("\\'", "'").replace('\\n', '\n')
    t = escape(t).replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n')
    if t.startswith('@') or t.startswith('?'):
        t = '\\' + t
    return t


def key_for(text):
    xml = android_escape(text)
    if xml in by_text:
        return by_text[xml]
    words = re.findall(r'[A-Za-z0-9]+', text.lower())[:5] or ['text']
    base = f"{prefix}_" + '_'.join(words)
    k, n = base, 2
    while k in keys:
        k, n = f"{base}_{n}", n + 1
    keys[k] = xml
    by_text[xml] = k
    added.append((k, xml))
    return k


for path in files:
    src = open(path, encoding='utf-8', newline='').read()
    out = src

    def text_sub(m):
        lit = m.group(1)
        if not re.search(r'[A-Za-z]', lit):
            return m.group(0)
        return f'Text(stringResource(R.string.{key_for(lit)})'

    def cd_sub(m):
        lit = m.group(2)
        if not re.search(r'[A-Za-z]', lit):
            return m.group(0)
        return f'{m.group(1)}stringResource(R.string.{key_for(lit)})'

    out = SPOTS[0].sub(text_sub, out)
    out = SPOTS[1].sub(cd_sub, out)
    if out != src:
        nl = '\r\n' if '\r\n' in out else '\n'
        for imp in ['androidx.compose.ui.res.stringResource', r_import]:
            if f'import {imp}{nl}' not in out:
                out = re.sub(r'(\nimport [^\n]+\n)', lambda m: m.group(1) + f'import {imp}{nl}', out, count=1)
        open(path, 'w', encoding='utf-8', newline='').write(out)
        print(f'edited {path}')

if added:
    body = ''.join(f'    <string name="{k}">{v}</string>\n' for k, v in added)
    existing = existing.replace('</resources>', body + '</resources>')
    open(values, 'w', encoding='utf-8').write(existing)
for k, v in added:
    print(f'{k}\t{v}')
