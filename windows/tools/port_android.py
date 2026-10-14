"""
Ports a Compose file of the Android app to the desktop app (AGENT.md D17): the package becomes com.notio.desktop, the domain and
common classes come from com.notio.shared.*, R.string / R.plurals become the resource names read by AndroidRes, Android-only
imports are dropped. What is left (Context, Toast, the app container) is fixed by hand and shows up at once as compile errors.

    python tools/port_android.py <android file> <desktop file>
"""
import re
import sys

PACKAGES = {
    'com.notebudget.app.domain.model.': 'com.notio.shared.domain.',
    'com.notebudget.app.domain.usecase.': 'com.notio.shared.domain.',
    'com.notebudget.app.core.common.': 'com.notio.shared.domain.',
    'com.notebudget.app.data.transfer.': 'com.notio.shared.transfer.',
    'com.notebudget.app.data.sync.': 'com.notio.shared.sync.',
}
DROP = ['android.', 'androidx.lifecycle', 'androidx.activity', 'com.notebudget.app.R', 'LocalContext', 'LocalLifecycleOwner',
        'KeyboardOptions', 'KeyboardCapitalization', 'KeyboardType', 'rememberAppContainer', 'ui.res.stringResource',
        'ui.res.pluralStringResource', 'imePadding', 'collectAsStateWithLifecycle', 'rememberSaveable']


def port(src: str) -> str:
    s = re.sub(r'^package com\.notebudget\.app[a-z.]*', 'package com.notio.desktop', src, flags=re.M)
    out = []
    for line in s.split('\n'):
        if line.startswith('import '):
            # The Skia-backed android.graphics / android.text of the desktop (D26) keep their imports.
            if any(d in line for d in DROP) and not line.startswith(('import android.graphics', 'import android.text.TextPaint')):
                continue
            for a, b in PACKAGES.items():
                line = line.replace(a, b)
            # Desktop-side ports of the phone's UI helpers live in the same package: no import needed.
            if re.match(r'^import com\.notebudget\.app\.(core\.ui|feature)\.', line):
                continue
        out.append(line)
    s = '\n'.join(out)
    for a, b in PACKAGES.items():
        s = s.replace(a, b)
    s = s.replace('com.notebudget.app.R.string.', 'R.string.').replace('com.notebudget.app.R.plurals.', 'R.plurals.')
    s = re.sub(r'com\.notebudget\.app\.(core\.ui|feature)(\.[a-z]+)*\.', '', s)
    s = s.replace('androidx.compose.ui.res.stringResource(', 'stringResource(')
    s = s.replace('androidx.compose.ui.res.pluralStringResource(', 'pluralStringResource(')
    s = re.sub(r'pluralStringResource\(R\.plurals\.([a-z0-9_]+)', r'pluralStringResource("\1"', s)
    s = re.sub(r'stringResource\(R\.string\.([a-z0-9_]+)', r'stringResource("\1"', s)
    s = re.sub(r'context\.getString\(R\.string\.([a-z0-9_]+)', r'res.str("\1"', s)
    s = re.sub(r'R\.string\.([a-z0-9_]+)', r'"\1"', s)
    s = re.sub(r'R\.plurals\.([a-z0-9_]+)', r'"\1"', s)
    s = s.replace('collectAsStateWithLifecycle(', 'collectAsState(').replace('rememberSaveable', 'remember')
    s = re.sub(r'@StringRes\s*', '', s)
    # The phone's own dialog wrapper is Material's AlertDialog on the desktop.
    if 'AppAlertDialog(' in s:
        s = s.replace('AppAlertDialog(', 'AlertDialog(')
        if 'import androidx.compose.material3.AlertDialog' not in s:
            s = s.replace('\nimport ', '\nimport androidx.compose.material3.AlertDialog\nimport ', 1)
    # A physical keyboard needs no soft-keyboard hints.
    s = re.sub(r'\n\s*keyboardOptions = KeyboardOptions\([^\n]*\),', '', s)
    # The phone's Context only reads texts in ported code: it becomes the AndroidRes of the language.
    s = re.sub(r'\bcontext: (android\.content\.)?Context\b', 'context: AndroidRes', s)
    s = s.replace('context::getString', 'context::str').replace('context.getString(', 'context.str(')
    s = s.replace('val context = LocalContext.current', 'val context = LocalRes.current')
    # A string resource id passed around becomes its name.
    s = re.sub(r'(fun \w*[Rr]es\([^)]*\)): Int\?', r'\1: String?', s)
    s = re.sub(r'\b(\w*[Rr]es|label|description|invalid\w*): Int\b', r'\1: String', s)
    s = re.sub(r'\btitle: Int\b', 'title: String', s)
    # A function that picks a string resource by a `when` returns its name.
    def res_fun(m):
        body = s_ref[0][m.end():m.end() + 1500]
        head = body.split('\n}\n', 1)[0]
        return m.group(1) + ': String' + m.group(2) if '-> "' in head else m.group(0)
    s_ref = [s]
    s = re.sub(r'(fun [\w.]+\([^)]*\)): Int( = when)', res_fun, s)
    return s


if __name__ == '__main__':
    src = open(sys.argv[1], encoding='utf-8').read()
    open(sys.argv[2], 'w', encoding='utf-8').write(port(src))
    print('ported', sys.argv[1], '->', sys.argv[2])
