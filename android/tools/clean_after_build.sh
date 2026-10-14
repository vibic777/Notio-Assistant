#!/usr/bin/env bash
# Rule 20 (D259): run from the repository root after every build step (Git Bash).
# Stops the Gradle daemons (they hold the unpacked native libraries), then removes the scratch the build leaves:
# inside the project (the temp folders under build/, signing leftovers) and in the system Temp (only files our builds make).
# Build outputs themselves (app/build/intermediates, outputs, the compiled classes) are not scratch and stay: removing them
# only makes the next build slow; `./gradlew clean` removes all of it when wanted.
cd "$(dirname "$0")/.." || exit 1
[ -n "$JAVA_HOME" ] && ../gradlew --stop -q >/dev/null 2>&1

# In the project.
rm -rf build/sqlite-tmp app/build/test-tmp app/build/tmp/testDebugUnitTest app/build/tmp/testReleaseUnitTest
rm -f app/build/tmp/al.apk app/build/tmp_*.txt dist/*.idsig
find . -path ./.git -prune -o -type d \( -iname temp -o -iname tmp \) -not -path "*/build/*" -print | while read -r d; do
    echo "a temp folder outside build/: $d (look at it; not removed)"
done

# In the system Temp: only what our builds leave there.
T="${TEMP:-/tmp}"
rm -rf "$T"/notebudget-test* "$T"/robolectric* "$T"/notio-sync* "$T"/jna-* 2>/dev/null
rm -f "$T"/sqlite-*-sqlitejdbc.dll* "$T"/conscrypt_openjdk_jni-*.dll "$T"/kotlin-daemon.*.log* 2>/dev/null

echo "project build/: $(du -sm build app/build 2>/dev/null | awk '{s+=$1} END {print s}') MB; Temp: $(du -sm "$T" 2>/dev/null | cut -f1) MB"
