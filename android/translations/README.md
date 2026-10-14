# Translations (D128, D132)

The app has ten languages: en (default, `app/src/main/res/values`), ru, be, fr, de, es, it, zh (Simplified), ja, ko.
ru, fr, de are edited directly in `app/src/main/res/values-<tag>/strings.xml`.
be, es, it, zh, ja, ko are **generated**: edit `translations/src/<tag>.tsv` / `<tag>.partN.tsv` (`key<TAB>text`, a plural item is
`plural:<name>:<quantity>`), then run `python translations/gen_lang.py <tag>` from the repository root. A missing key stops the generator.
When a string is added to the English file, add it to every language (`LocalizationTest` fails otherwise).
The translations were written by the agent and have NOT been read by native speakers: do that before a public release.
New strings for all ten languages at once: `python translations/add_strings.py translations/src/batch_<name>.tsv` (columns: key en ru be fr de
es it zh ja ko). If a string was ever put straight into a generated `values-<tag>/strings.xml`, run
`python translations/sync_tsv_from_xml.py <tag>` first: it copies what is missing from the tsv files into `<tag>.part_synced.tsv`, so the
generator can rebuild the file again (D205).
**Before and after any change to the strings, run `python translations/gen_lang.py --check be es it zh ja ko`** (D291): it writes
nothing and says whether a rebuild would give exactly the strings.xml there is. If it would not, a string was edited straight in the
xml: `python translations/sync_tsv_from_xml.py <tag>` copies new strings into `<tag>.part_synced.tsv` and writes changed texts into
the tsv line that wins for that key, in place (a later file wins; `<tag>.tsv` is read last), then `--check` must pass. Never run the
generator while `--check` fails: it would roll the xml edits back (T1, found 2026-10-01 — the help texts of six languages).
