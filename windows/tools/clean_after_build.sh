#!/usr/bin/env bash
# Rule 20 (Android D259): run from the repository root after every build step (Git Bash).
# Stops the Gradle daemons (they hold the unpacked native libraries), then removes the scratch the build leaves: inside the project
# (the temp folders under build/) and in the system Temp (only what the builds and tests of this project make there).
# Build outputs (compiled classes, dist/) are not scratch and stay; `./gradlew clean` removes the rest when wanted.
cd "$(dirname "$0")/.." || exit 1
[ -n "$JAVA_HOME" ] && ../gradlew --stop -q >/dev/null 2>&1

# In the project.
rm -rf shared/build/test-tmp desktopApp/build/test-tmp build/tmp shared/build/tmp desktopApp/build/tmp desktopApp/build/compose/tmp build/st build/live
find . -path ./.git -prune -o -type d \( -iname temp -o -iname tmp \) -not -path "*/build/*" -not -path "./.gradle/*" -print | while read -r d; do
    echo "a temp folder outside build/: $d (look at it; not removed)"
done

# In the system Temp: only what our builds leave there.
T="${TEMP:-/tmp}"
rm -rf "$T"/notio-test* "$T"/notio-sync* "$T"/notio-live* "$T"/notio-selftest* "$T"/notio-smoke* "$T"/notio-0*.png "$T"/jna-* 2>/dev/null
rm -f "$T"/sqlite-*-sqlitejdbc.dll* "$T"/kotlin-daemon.*.log* "$T"/kotlin-compiler-in-*otioindows*.alive 2>/dev/null

echo "project build/: $(du -sm build shared/build desktopApp/build 2>/dev/null | awk '{s+=$1} END {print s}') MB; Temp: $(du -sm "$T" 2>/dev/null | cut -f1) MB"
