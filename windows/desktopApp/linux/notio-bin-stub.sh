#!/bin/sh
# Notio @VERSION@ for Linux in one file (D88): the app folder with its own Java is packed after the last line of this script.
# The first start unpacks it into the cache of this user, later starts run it from there at once.
# The data is kept in the folder Notio-data beside this file, as the portable Notio of Windows keeps it beside Notio.exe;
# when that place cannot be written to, in ~/.local/share/notio. NOTIO_HOME, when set, says where instead.
set -e
self=$(readlink -f "$0")
here=$(dirname "$self")
base="${XDG_CACHE_HOME:-$HOME/.cache}/notio"
app="$base/app-@VERSION@-@STAMP@"

if [ ! -x "$app/Notio/bin/Notio" ]; then
    tmp="$app.tmp.$$"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    # Everything after the line of the mark is the archive.
    tail -n +@LINES@ "$self" | tar -xzf - -C "$tmp"
    rm -rf "$app"
    mv "$tmp" "$app"
    # What an older file of Notio unpacked goes: only this version stays in the cache.
    for old in "$base"/app-*; do
        [ "$old" = "$app" ] || rm -rf "$old"
    done
fi

if [ -z "${NOTIO_HOME:-}" ]; then
    if [ -d "$here/Notio-data" ] || [ -w "$here" ]; then
        NOTIO_HOME="$here/Notio-data"
        export NOTIO_HOME
    fi
fi

# "Start with the system" must name this file, not the copy in the cache, which moves with every version (D129).
NOTIO_BIN="$self"
export NOTIO_BIN
exec "$app/Notio/bin/Notio" "$@"
exit 0
__NOTIO_ARCHIVE_BELOW__
