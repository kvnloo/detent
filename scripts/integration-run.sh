#!/usr/bin/env bash
# Runs inside the emulator session so evidence is captured while the device is still up.
# Every adb call is time-bounded: a missing device must fail fast, never hang the job.
set -uo pipefail
out=integration-evidence
mkdir -p "$out"
sha=$(git rev-parse HEAD)
echo "$sha" > "$out/source-sha.txt"
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -PdetentId=app.detent.launcher.lab -PdetentChannel=nightly \
  -PdetentCommit="$sha" -PdetentRun="$GITHUB_RUN_ID"
status=$?
echo "$status" > "$out/connected-exit-code.txt"
timeout 30 adb logcat -d -t 2000 > "$out/logcat.txt" || echo "logcat capture failed" >&2
timeout 30 adb exec-out screencap -p > "$out/screen.png" || echo "screencap failed" >&2
timeout 60 adb pull /sdcard/Android/data/app.detent.launcher.lab/files/test-evidence "$out/" \
  || echo "test-evidence pull failed" >&2
exit "$status"
