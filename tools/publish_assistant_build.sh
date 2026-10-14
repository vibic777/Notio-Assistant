#!/usr/bin/env bash
# The test builds of the branch 'assistant' (Windows D152), each in its own folder:
#   dist/assistant/<windows version>-assistant.<N>-<date>/
#         Notio-<version>-assistant.<N>.msi / .exe / -portable.zip   the Windows packages of :desktopApp:release
#         ИНСТРУКЦИЯ.md                                               docs/ASSISTANT_GUIDE_RU.md
#         bot/notio_relay.py, tests/test_notio_relay.py               the module of the bot program TelegramBot, when it is there
#         manifest.json                                               the versions, the Git revision, the size and SHA-256 of every file
# Run from the root of the repository after .\gradlew.bat :desktopApp:release. Arguments: tests=<the totals of the tests>.
set -euo pipefail

tests=""
for arg in "$@"; do
    case "$arg" in
        tests=*) tests="${arg#tests=}" ;;
        *) echo "unknown argument: $arg" >&2; exit 1 ;;
    esac
done

prop() { grep -E "^$1=" version.properties | cut -d= -f2 | tr -d '\r'; }
windows="$(prop windows.version)"
windows_build="$(prop windows.build)"
test_build="$(prop assistant.build)"
[ -n "$test_build" ] || { echo "version.properties has no assistant.build: this is not the branch 'assistant'" >&2; exit 1; }
shown="$windows-assistant.$test_build"
date="$(date +%F)"
source="$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- . ':!dist' || echo '-changed')"
target="dist/assistant/$shown-$date"

[ -e "$target" ] && { echo "$target is there already: raise assistant.build in version.properties first" >&2; exit 1; }
for f in "Notio-$windows.msi" "Notio-$windows.exe" "Notio-$windows-portable.zip"; do
    [ -f "dist/$f" ] || { echo "dist/$f is missing: run .\\gradlew.bat :desktopApp:release first" >&2; exit 1; }
done

mkdir -p "$target"
mv -f "dist/Notio-$windows.msi" "$target/Notio-$shown.msi"
mv -f "dist/Notio-$windows.exe" "$target/Notio-$shown.exe"
mv -f "dist/Notio-$windows-portable.zip" "$target/Notio-$shown-portable.zip"
cp docs/ASSISTANT_GUIDE_RU.md "$target/ИНСТРУКЦИЯ.md"
files=("Notio-$shown.msi" "Notio-$shown.exe" "Notio-$shown-portable.zip" "ИНСТРУКЦИЯ.md")

bot="../TelegramBot"
if [ -f "$bot/bot/notio_relay.py" ]; then
    mkdir -p "$target/bot" "$target/tests"
    cp "$bot/bot/notio_relay.py" "$target/bot/notio_relay.py"
    files+=("bot/notio_relay.py")
    if [ -f "$bot/tests/test_notio_relay.py" ]; then cp "$bot/tests/test_notio_relay.py" "$target/tests/test_notio_relay.py"; files+=("tests/test_notio_relay.py"); fi
fi

{
    echo "{"
    echo "  \"program\": \"Notio\","
    echo "  \"branch\": \"assistant\","
    echo "  \"date\": \"$date\","
    echo "  \"source\": \"$source\","
    echo "  \"windows\": { \"version\": \"$windows\", \"build\": $windows_build, \"test\": \"$shown\" },"
    echo "  \"contributor\": \"vibic777\","
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
