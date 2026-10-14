#!/usr/bin/env bash
# Puts one delivery into a folder of its own, as FitEasy does (owner, 2026-10-08: "build the releases like FitEasy, each into
# its own folder with the name of the program, the version and the date of the release"):
#
#     dist/Notio-android-<android version>-windows-<windows version>-<date>/
#         Notio-by-vibic-<android version>-debug.apk
#         Notio-by-vibic-<android version>-release.apk
#         Notio-<windows version>.msi
#         Notio-<windows version>.exe
#         Notio-<windows version>-portable.zip
#         manifest.json        the versions, the builds, the date, the Git revision, the size and SHA-256 of every file
#
# Run from the repository root after `gradlew :packageDelivery` (it leaves the five files in dist/):
#
#     bash tools/publish_delivery.sh [tests=<n>] [date=<yyyy-mm-dd>] [android=<version>] [windows=<version>] [source=<revision>]
#
# Without arguments the versions come from version.properties, the date is today and the revision is HEAD. A release APK that
# Gradle left unsigned (no android/keystore.properties) is signed here with the key of the project, private/signing/debug.keystore
# (the debug key of this machine only when that file is absent), as docs/DELIVERY.md says. Both APKs are then checked: a
# certificate other than the one of every delivered Notio stops the script (D344). Nothing is deleted: the files are moved, an
# existing folder is filled again.
set -euo pipefail

cd "$(dirname "$0")/.."
prop() { grep -E "^$1=" version.properties | head -1 | cut -d= -f2- | tr -d '\r'; }

android="$(prop android.version)"
android_build="$(prop android.build)"
windows="$(prop windows.version)"
windows_build="$(prop windows.build)"
date="$(date +%Y-%m-%d)"
source="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
tests=""
custom=""
for arg in "$@"; do
    case "$arg" in
        tests=*) tests="${arg#tests=}" ;;
        date=*) date="${arg#date=}" ;;
        android=*) android="${arg#android=}"; custom=1 ;;
        windows=*) windows="${arg#windows=}"; custom=1 ;;
        source=*) source="${arg#source=}" ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done
# The build numbers in version.properties belong to its own versions only.
if [ -n "$custom" ]; then android_build=""; windows_build=""; fi

debug="Notio-by-vibic-$android-debug.apk"
release="Notio-by-vibic-$android-release.apk"
unsigned="Notio-by-vibic-$android-release-unsigned.apk"
files=("$debug" "$release" "Notio-$windows.msi" "Notio-$windows.exe" "Notio-$windows-portable.zip")
target="dist/Notio-android-$android-windows-$windows-$date"
mkdir -p "$target"
# The Linux builds of the same desktop version (D129) go into the same folder when they were made: they are built on Linux
# (`./gradlew :desktopApp:releaseLinux`, docs/LINUX_RU.md) and copied into dist/. A delivery without them is still whole.
for f in "Notio-$windows-linux-x64.bin" "Notio-$windows-x86_64.AppImage" "Notio-$windows-linux-x64.tar.gz" "notio_${windows}_amd64.deb" "notio-$windows-1.x86_64.rpm"; do
    if [ -f "dist/$f" ] || [ -f "$target/$f" ]; then files+=("$f"); fi
done

# The certificate of every delivered Notio APK (D344): an APK signed with another key does not install over the app.
certificate="e146abb83b22728ed8ab345d0d8443902505c539dd93b2ed52219d9d73b0f979"
key="private/signing/debug.keystore"
[ -f "$key" ] || key="${USERPROFILE:-$HOME}/.android/debug.keystore"
sdk="${ANDROID_HOME:-}"
if [ -z "$sdk" ] && [ -f local.properties ]; then
    sdk="$(grep -E '^sdk\.dir=' local.properties | cut -d= -f2- | tr -d '\r' | sed -e 's/\\:/:/g' -e 's/\\\\/\//g')"
fi
signer="$(ls "$sdk"/build-tools/*/apksigner.bat "$sdk"/build-tools/*/apksigner 2>/dev/null | sort | tail -1 || true)"
[ -n "$signer" ] || { echo "apksigner was not found (ANDROID_HOME or sdk.dir of local.properties)" >&2; exit 1; }

# The release APK: signed by Gradle, or signed here with the key of the project (docs/DELIVERY.md). A fresh unsigned APK in
# dist/ is signed even when the folder of the delivery already holds a release APK: that is how a delivery is issued again.
if [ ! -f "dist/$release" ] && [ -f "dist/$unsigned" ]; then
    "$signer" sign --ks "$key" --ks-key-alias androiddebugkey \
        --ks-pass pass:android --key-pass pass:android --out "dist/$release" "dist/$unsigned" 2>/dev/null
    "$signer" verify "dist/$release" 2>/dev/null
    rm -f "dist/$unsigned" dist/*.idsig
fi

for f in "${files[@]}"; do
    if [ -f "dist/$f" ]; then mv -f "dist/$f" "$target/$f"; fi
    [ -f "$target/$f" ] || { echo "missing: $f (run gradlew :packageDelivery first)" >&2; exit 1; }
done

# Both APKs carry the certificate of the project, or the delivery is not made (D344: 0.9.100 and 0.9.101 went out signed
# with the debug key of a new build machine).
for f in "$debug" "$release"; do
    found="$("$signer" verify --print-certs "$target/$f" 2>/dev/null | grep -m1 'certificate SHA-256 digest' | sed 's/.*: *//' | tr -d '\r' || true)"
    [ "$found" = "$certificate" ] || { echo "$f is signed with another key (${found:-no certificate}): put the key of the project into private/signing/debug.keystore and build again" >&2; exit 1; }
done

# The release APK is the build the tests never run (D336): R8 takes out every string no code refers to, and the help of
# 0.9.96 - 0.9.98 showed the names of its strings because of it. A delivery whose release APK lacks them is not made.
aapt="$(ls "${ANDROID_HOME:-$(grep -E '^sdk\.dir=' local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r' | sed -e 's/\\:/:/g' -e 's/\\\\/\//g')}"/build-tools/*/aapt2.exe "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt2 2>/dev/null | sort | tail -1 || true)"
if [ -n "$aapt" ]; then
    names="$("$aapt" dump resources "$target/$release" 2>/dev/null | grep -o 'string/[a-z_0-9]*' | sort -u)"
    for key in help_start_title help_start_body help_sync_body help_cat_start help_icons_title help_search_hint glossary_goal; do
        echo "$names" | grep -qx "string/$key" || { echo "the release APK has no string $key: the help would show names, not texts" >&2; exit 1; }
    done
else
    echo "aapt2 was not found: the strings of the release APK were not checked" >&2
fi

{
    echo "{"
    echo "  \"program\": \"Notio\","
    echo "  \"date\": \"$date\","
    echo "  \"source\": \"$source\","
    echo "  \"android\": { \"version\": \"$android\"${android_build:+, \"build\": $android_build} },"
    echo "  \"windows\": { \"version\": \"$windows\"${windows_build:+, \"build\": $windows_build} },"
    echo "  \"contributor\": \"vibic777\","
    echo "  \"releaseSigning\": \"The key of the project, certificate SHA-256 $certificate (docs/DELIVERY.md)\","
    if [ -n "$tests" ]; then echo "  \"tests\": \"$tests\","; fi
    echo "  \"artifacts\": ["
    last=$((${#files[@]} - 1))
    for i in "${!files[@]}"; do
        f="${files[$i]}"
        sep=","; [ "$i" -eq "$last" ] && sep=""
        echo "    { \"file\": \"$f\", \"bytes\": $(stat -c %s "$target/$f"), \"sha256\": \"$(sha256sum "$target/$f" | cut -d' ' -f1)\" }$sep"
    done
    echo "  ]"
    echo "}"
} > "$target/manifest.json"

echo "$target"
