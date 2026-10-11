"""Turn t("中文", "English") pairs (feature/apex's old two-language helper) into string resources.

    python tools/i18n/convert_t_pairs.py <res_dir> <prefix> <file.kt>

Each pair becomes str(R.string.<key>[, args]) -- English into values/, Chinese
into values-zh/. "$x" / "${expr}" templates become positional %N$s arguments,
matched by expression between the two languages. Pairs it can't convert
(an expression in one language only) are reported and left alone.
"""
import os, re, sys
from xml.sax.saxutils import escape

res_dir, prefix, path = sys.argv[1], sys.argv[2], sys.argv[3]
PAIR = re.compile(r'\bt\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)')
TEMPLATE = re.compile(r'\$\{((?:[^{}]|\{[^{}]*\})*)\}|\$([A-Za-z_][A-Za-z0-9_]*)')


def to_res(text, exprs):
    """Kotlin literal with templates -> (resource text, ok). exprs: expr -> index (shared)."""
    out, pos, ok = '', 0, True
    for m in TEMPLATE.finditer(text):
        expr = (m.group(1) or m.group(2)).strip()
        if expr not in exprs:
            ok = False
            break
        out += text[pos:m.start()] + '\x00%d\x00' % exprs[expr]
        pos = m.end()
    out += text[pos:]
    out = out.replace('\\"', '"').replace("\\'", "'").replace('\\n', '\n').replace('\\$', '$')
    out = escape(out).replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n')
    out = re.sub('\x00(\\d+)\x00', lambda m: '%' + m.group(1) + '$s', out)
    if out.startswith('@') or out.startswith('?'):
        out = '\\' + out
    return out, ok


def load(p):
    return open(p, encoding='utf-8').read() if os.path.exists(p) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'


en_path = os.path.join(res_dir, 'values', 'strings.xml')
zh_path = os.path.join(res_dir, 'values-zh', 'strings.xml')
os.makedirs(os.path.dirname(en_path), exist_ok=True)
os.makedirs(os.path.dirname(zh_path), exist_ok=True)
en_xml, zh_xml = load(en_path), load(zh_path)
keys = set(re.findall(r'<string name="([^"]+)"', en_xml))
seen = {}
added_en, added_zh, skipped = [], [], []

src = open(path, encoding='utf-8', newline='').read()


def sub(m):
    zh, en = m.group(1), m.group(2)
    exprs = {}
    for t in TEMPLATE.finditer(en):
        e = (t.group(1) or t.group(2)).strip()
        exprs.setdefault(e, len(exprs) + 1)
    en_res, ok1 = to_res(en, exprs)
    zh_res, ok2 = to_res(zh, exprs)
    zh_exprs = {(t.group(1) or t.group(2)).strip() for t in TEMPLATE.finditer(zh)}
    if not (ok1 and ok2) or zh_exprs != set(exprs):
        skipped.append(m.group(0)[:100])
        return m.group(0)
    if (en_res, zh_res) in seen:
        key = seen[(en_res, zh_res)]
    else:
        words = re.findall(r'[A-Za-z0-9]+', re.sub(r'%\d\$s', ' ', en_res).lower())[:5] or ['text']
        base = f'{prefix}_' + '_'.join(words)
        key, n = base, 2
        while key in keys:
            key, n = f'{base}_{n}', n + 1
        keys.add(key)
        seen[(en_res, zh_res)] = key
        added_en.append((key, en_res))
        added_zh.append((key, zh_res))
    args = ''.join(', ' + e for e, _ in sorted(exprs.items(), key=lambda kv: kv[1]))
    return f'str(R.string.{key}{args})'


out = PAIR.sub(sub, src)
open(path, 'w', encoding='utf-8', newline='').write(out)
en_xml = en_xml.replace('</resources>', ''.join(f'    <string name="{k}">{v}</string>\n' for k, v in added_en) + '</resources>')
zh_xml = zh_xml.replace('</resources>', ''.join(f'    <string name="{k}">{v}</string>\n' for k, v in added_zh) + '</resources>')
open(en_path, 'w', encoding='utf-8').write(en_xml)
open(zh_path, 'w', encoding='utf-8').write(zh_xml)
print(f'converted {len(added_en)} pairs')
for s in skipped:
    print('SKIPPED:', s)
