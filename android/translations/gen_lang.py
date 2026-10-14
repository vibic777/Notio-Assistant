# -*- coding: utf-8 -*-
"""Builds app/src/main/res/values-<tag>/strings.xml from translations/src/<tag>.tsv and the English strings.xml.

TSV: one entry per line, `key<TAB>text`; a plural item is `plural:<name>:<quantity><TAB>text`.
In the text write plain characters (apostrophes, quotes, & and < are escaped here); `\\n` stays as the two characters
backslash-n (a line break in the app). Placeholders (%1$d, %2$s) are kept as they are.

Usage: python translations/gen_lang.py [--check] <tag> [<tag> ...]      (run from the repository root)
Missing keys are listed and nothing is written, so a half-finished file never reaches the app.
--check writes nothing: it says whether the tsv files would give exactly the strings.xml there is now (exit 1 if not), so a
string edited straight in the xml is found before a rebuild rolls it back (T1; fix with sync_tsv_from_xml.py).
"""
import re
import sys

ROOT = __file__.replace("\\", "/").rsplit("/translations/", 1)[0] + "/"
EN = ROOT + "app/src/main/res/values/strings.xml"

PLURAL_QUANTITIES = {
    "zh": ["other"], "ja": ["other"], "ko": ["other"],
    "es": ["one", "many", "other"], "it": ["one", "many", "other"],
    "be": ["one", "few", "many", "other"],
}
LOCALE_FOLDER = {"zh": "values-zh", "ja": "values-ja", "ko": "values-ko", "es": "values-es", "it": "values-it", "be": "values-be"}


def esc(text):
    text = text.replace("\\'", "'").replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'").replace('"', '\\"')
    if text[:1] in "@?":
        text = "\\" + text
    return text


def sources(tag):
    """The tsv files of [tag] in the order they are read: a key in a later file wins over the same key in an earlier one."""
    import glob
    return sorted(glob.glob(ROOT + "translations/src/%s.tsv" % tag) + glob.glob(ROOT + "translations/src/%s.part*.tsv" % tag))


def load(tag):
    table = {}
    files = sources(tag)
    lines = []
    for f in files:
        lines += open(f, encoding="utf-8").read().split("\n")
    for n, line in enumerate(lines, 1):
        if not line.strip() or line.startswith("#"):
            continue
        key, sep, text = line.partition("\t")
        if not sep:
            raise SystemExit("%s.tsv line %d has no tab" % (tag, n))
        table[key.strip()] = text.rstrip("\r")
    return table


def build(tag, check=False):
    table = load(tag)
    english = open(EN, encoding="utf-8").read()
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    missing = []
    used = set()
    for m in re.finditer(r'<string name="([^"]+)"([^>]*)>([^<]*)</string>|<plurals name="([^"]+)">(.*?)</plurals>|<!--(.*?)-->', english, re.S):
        if m.group(6) is not None:
            out.append("    <!--%s-->" % m.group(6))
        elif m.group(1) is not None:
            name = m.group(1)
            if "translatable=\"false\"" in m.group(2):
                continue
            if name not in table:
                missing.append(name)
                continue
            used.add(name)
            # An attribute such as formatted="false" (a string with a literal %) is kept in every language.
            keep = ' formatted="false"' if 'formatted="false"' in m.group(2) else ''
            out.append('    <string name="%s"%s>%s</string>' % (name, keep, esc(table[name])))
        else:
            name = m.group(4)
            items = []
            for q in PLURAL_QUANTITIES[tag]:
                key = "plural:%s:%s" % (name, q)
                if key not in table:
                    missing.append(key)
                    continue
                used.add(key)
                items.append('        <item quantity="%s">%s</item>' % (q, esc(table[key])))
            out.append('    <plurals name="%s">' % name)
            out.extend(items)
            out.append("    </plurals>")
    extra = set(table) - used
    if missing:
        print("%s: %d missing: %s" % (tag, len(missing), ", ".join(missing[:60])))
        return False
    if extra:
        print("%s: unknown keys in the tsv: %s" % (tag, ", ".join(sorted(extra)[:30])))
        return False
    out.append("</resources>")
    folder = ROOT + "app/src/main/res/" + LOCALE_FOLDER[tag]
    text = "\n".join(out) + "\n"
    import os
    if check:
        path = folder + "/strings.xml"
        now = open(path, encoding="utf-8").read().replace("\r\n", "\n") if os.path.exists(path) else ""
        if now == text:
            print("%s: the tsv files give exactly the current strings.xml" % tag)
            return True
        a, b = now.split("\n"), text.split("\n")
        differ = [i for i in range(max(len(a), len(b))) if (a[i] if i < len(a) else None) != (b[i] if i < len(b) else None)]
        print("%s: a rebuild would change %d lines of strings.xml, first at line %d" % (tag, len(differ), differ[0] + 1))
        return False
    os.makedirs(folder, exist_ok=True)
    open(folder + "/strings.xml", "w", encoding="utf-8", newline="\n").write(text)
    print("%s: written %d entries" % (tag, len(used)))
    return True


if __name__ == "__main__":
    ok = True
    check = "--check" in sys.argv[1:]
    for t in [a for a in sys.argv[1:] if a != "--check"]:
        ok = build(t, check) and ok
    sys.exit(0 if ok else 1)
