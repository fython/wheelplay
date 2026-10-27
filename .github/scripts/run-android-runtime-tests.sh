#!/usr/bin/env bash
set -euo pipefail

# Bound every adb operation: a stuck device must not consume the entire job timeout.
adb_command() { timeout --kill-after=5s 120s adb -s "${ANDROID_SERIAL:-emulator-5554}" "$@"; }
collect_diagnostics() {
  local result=$?
  trap - EXIT
  timeout --kill-after=5s 15s adb -s "${ANDROID_SERIAL:-emulator-5554}" logcat -d -v threadtime > runtime-logcat.log 2>&1 || true
  timeout --kill-after=5s 15s adb -s "${ANDROID_SERIAL:-emulator-5554}" shell dumpsys activity services > runtime-services.log 2>&1 || true
  exit "$result"
}
trap collect_diagnostics EXIT

run_test() {
  local logfile=$1 test_class=$2 runner=$3 status=0
  adb_command shell am instrument -w -r -e class "$test_class" "$runner" > "$logfile" 2>&1 || status=$?
  cat "$logfile"
  # am instrument can exit zero even when JUnit fails or the process crashes.
  if [[ "$status" != 0 ]] || ! grep -Eq '^OK \([1-9][0-9]* tests?\)' "$logfile"; then
    echo "::error::Runtime test failed ($test_class, adb exit $status); see $logfile and runtime-logcat.log"
    return 1
  fi
}

adb_command logcat -c
adb_command install -r runtime-tests/common-debug-androidTest.apk
result=0
run_test browser-runtime.log com.shilapi.xcertplay.web.BrowserPageRuntimeTest \
  com.shilapi.xcertplay.host.test/androidx.test.runner.AndroidJUnitRunner || result=1

adb_command install -r plugin-host/mobile-debug.apk
adb_command install -r plugin-sdk/projection-plugin-sample/build/outputs/apk/debug/projection-plugin-sample-debug.apk
adb_command install -r plugin-tests/mobile-debug-androidTest.apk
run_test plugin-runtime.log moe.feng.wheelplay.plugin.PluginSurfaceIntegrationTest \
  moe.feng.wheelplay.debug.test/androidx.test.runner.AndroidJUnitRunner || result=1
exit "$result"
