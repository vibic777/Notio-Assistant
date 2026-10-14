# -*- coding: utf-8 -*-
"""Adds new strings to all ten languages from one table.

Usage: python translations/add_strings.py translations/src/batch_<name>.tsv   (run from the repository root)

The table has one string per line, columns separated by TAB, in this order:
    key  en  ru  be  fr  de  es  it  zh  ja  ko
Lines starting with # and blank lines are ignored. `\\n` stays as the two characters backslash-n (a line break in the app).
en, ru, fr, de are appended to app/src/main/res/values*/strings.xml (a key that is already there is skipped);
be, es, it, zh, ja, ko go to translations/src/<tag>.part_<name>.tsv and the six generated files are rebuilt.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_lang  # noqa: E402

ORDER = ["en", "ru", "be", "fr", "de", "es", "it", "zh", "ja", "ko"]
DIRECT = {"en": "values", "ru": "values-ru", "fr": "values-fr", "de": "values-de"}
GENERATED = ["be", "es", "it", "zh", "ja", "ko"]


def main(path):
    name = os.path.basename(path).replace("batch_", "").rsplit(".", 1)[0]
    rows = []
    for n, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.rstrip("\r").split("\t")
        if len(cols) != len(ORDER) + 1:
            raise SystemExit("%s line %d: %d columns, expected %d" % (path, n, len(cols), len(ORDER) + 1))
        rows.append(cols)
    root = gen_lang.ROOT
    en_path = root + "app/src/main/res/values/strings.xml"
    existing = set(re.findall(r'<string name="([^"]+)"', open(en_path, encoding="utf-8").read()))
    fresh = [r for r in rows if r[0] not in existing]
    for i, lang in enumerate(ORDER):
        if lang in DIRECT:
            p = root + "app/src/main/res/%s/strings.xml" % DIRECT[lang]
            text = open(p, encoding="utf-8").read()
            add = "".join('    <string name="%s">%s</string>\n' % (r[0], gen_lang.esc(r[i + 1])) for r in fresh)
            text = text.replace("</resources>", add + "</resources>")
            open(p, "w", encoding="utf-8", newline="\n").write(text)
        else:
            p = root + "translations/src/%s.part_%s.tsv" % (lang, name)
            open(p, "w", encoding="utf-8", newline="\n").write("".join("%s\t%s\n" % (r[0], r[i + 1]) for r in rows))
    print("added %d new strings (%d rows in the table)" % (len(fresh), len(rows)))
    ok = True
    for tag in GENERATED:
        ok = gen_lang.build(tag) and ok
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main(sys.argv[1])
