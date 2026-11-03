#!/usr/bin/env bash
# Read-only CI: no phone/release signing key is available to source under test.
set -euo pipefail
: "${SOURCE_SHA:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_NUMBER:?}"
test "$(git rev-parse HEAD)" = "$SOURCE_SHA"
git diff --exit-code HEAD -- .
channel=candidate
if [[ ${EVENT_NAME:-} != pull_request ]]; then
  case ${GITHUB_REF_NAME:-} in preview) channel=preview;; nightly) channel=nightly;; main) channel=stable;; esac
fi
code=$((10000 + GITHUB_RUN_NUMBER))
version="0.1.0-${channel}.${GITHUB_RUN_NUMBER}+${SOURCE_SHA:0:8}"
args=(-PdetentCommit="$SOURCE_SHA" -PdetentChannel="$channel" -PdetentRun="$GITHUB_RUN_ID" -PdetentCode="$code" -PdetentVersion="$version")
mkdir -p out
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PdetentId=app.detent.launcher.lab "${args[@]}"
cp app/build/outputs/apk/debug/app-debug.apk out/detent-lab.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk out/detent-lab-test.apk
./gradlew --no-daemon :app:assembleDebug -PdetentId=app.detent.launcher.kvn "${args[@]}"
cp app/build/outputs/apk/debug/app-debug.apk out/detent-kvn.apk
python3 scripts/provenance.py create --dir out --sha "$SOURCE_SHA" --channel "$channel" --run "$GITHUB_RUN_ID" --version-code "$code"
