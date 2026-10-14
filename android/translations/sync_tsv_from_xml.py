# -*- coding: utf-8 -*-
"""Brings translations/src/<tag>*.tsv back in step with the generated values-<tag>/strings.xml.

Strings that are in the built strings.xml but in none of the tsv files (added straight to the xml once) are copied into
translations/src/<tag>.part_synced.tsv. Strings whose text in the xml differs from the tsv (edited straight in the xml, T1) are
written into the tsv line that wins for that key (the last file that has it), in place. After running it,
`python translations/gen_lang.py --check <tag>` must say that the tsv files give exactly the strings.xml there is.

Usage: python translations/sync_tsv_from_xml.py <tag> [<tag> ...]      (run from the repository root)
"""
import html
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_lang  # noqa: E402


def unesc(text):
    """The reverse of gen_lang.esc: from the xml text back to the plain text of a tsv line."""
    if text[:2] in ("\\@", "\\?"):
        text = text[1:]
    text = text.replace("\\'", "'").replace('\\"', '"')
    return html.unescape(text)


def winning_lines(tag):
    """For each key, the file and line index that gen_lang.load takes its text from (the last one)."""
    where = {}
    for path in gen_lang.sources(tag):
        for i, line in enumerate(open(path, encoding="utf-8").read().split("\n")):
            if line.strip() and not line.startswith("#") and "\t" in line:
                where[line.partition("\t")[0].strip()] = (path, i)
    return where


def update_changed(tag, current):
    """Writes the xml's text into the winning tsv line of every key whose text differs; returns how many."""
    table = gen_lang.load(tag)
    where = winning_lines(tag)
    edits = {}
    for key, text in current.items():
        if key in table and table[key] != text:
            path, i = where[key]
            edits.setdefault(path, {})[i] = "%s\t%s" % (key, text)
    for path, lines_at in edits.items():
        lines = open(path, encoding="utf-8").read().split("\n")
        for i, line in lines_at.items():
            lines[i] = line
        open(path, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
    return sum(len(v) for v in edits.values())


def sync(tag):
    table = gen_lang.load(tag)
    xml_path = gen_lang.ROOT + "app/src/main/res/%s/strings.xml" % gen_lang.LOCALE_FOLDER[tag]
    xml = open(xml_path, encoding="utf-8").read()
    rows = []
    current = {}
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>|<plurals name="([^"]+)">(.*?)</plurals>', xml, re.S):
        if m.group(1) is not None:
            current[m.group(1)] = unesc(m.group(2))
            if m.group(1) not in table:
                rows.append((m.group(1), unesc(m.group(2))))
        else:
            for q, text in re.findall(r'<item quantity="([^"]+)">(.*?)</item>', m.group(4), re.S):
                key = "plural:%s:%s" % (m.group(3), q)
                current[key] = unesc(text)
                if key not in table:
                    rows.append((key, unesc(text)))
    changed = update_changed(tag, current)
    if rows:
        path = gen_lang.ROOT + "translations/src/%s.part_synced.tsv" % tag
        with open(path, "a", encoding="utf-8", newline="\n") as f:
            f.write("".join("%s\t%s\n" % r for r in rows))
    print("%s: %d strings copied from strings.xml, %d texts brought in step" % (tag, len(rows), changed))


if __name__ == "__main__":
    for t in sys.argv[1:]:
        sync(t)
