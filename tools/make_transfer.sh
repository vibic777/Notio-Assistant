#!/usr/bin/env bash
# Packs what another machine needs to go on with Notio into transfer/ (ignored by Git): see docs/MOVE_RU.md.
#   bash tools/make_transfer.sh             the whole history as one file, the private notes, the list of what to do there
#   bash tools/make_transfer.sh --with-key  also the key the APKs are signed with (keep that folder to yourself)
# Nothing is sent anywhere: the folder is carried by hand (a USB stick, your own disk).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
    echo "There are changes that are not committed: commit them first, or they stay on this machine." >&2
    git status --short >&2
    exit 1
fi

out=transfer
rm -rf "$out"
mkdir -p "$out"

# Every branch and the whole history in one file; `git clone Notio.bundle Notio` makes the repository from it.
git bundle create "$out/Notio.bundle" --all
git bundle verify "$out/Notio.bundle" >/dev/null

# What Git does not carry.
[ -d private ] && cp -r private "$out/private"
# The copy of the history before it was rewritten is of no use on a new machine.
rm -f "$out"/private/*.bundle

if [ "${1:-}" = "--with-key" ]; then
    # The key of the project (D344); the debug key of this machine only when the project has none.
    key="private/signing/debug.keystore"
    [ -f "$key" ] || key="${USERPROFILE:-$HOME}/.android/debug.keystore"
    if [ -f "$key" ]; then
        mkdir -p "$out/key"
        cp "$key" "$out/key/debug.keystore"
        echo "The signing key is in $out/key: without it the new machine cannot make APKs that update the installed app."
    else
        echo "No key at $key" >&2
    fi
fi

head=$(git rev-parse --short HEAD)
cat > "$out/README.txt" <<EOF
Notio, packed $(date +%Y-%m-%d) at commit $head.

1. git clone Notio.bundle Notio        (the repository with its whole history)
2. cd Notio; git config user.name vibic777; git config user.email vibic@yandex.ru
3. copy the folder private/ into Notio/private/
4. the key (if key/ is here): put key/debug.keystore into <home>/.android/debug.keystore
5. go on by docs/MOVE_RU.md (what to install) and docs/LINUX_RU.md (the Linux build in WSL)
EOF

echo "Packed into $out/:"
ls -la "$out"
