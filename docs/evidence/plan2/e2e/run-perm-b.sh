#!/usr/bin/env bash
# Task 9 9.2 path B: RECORD_AUDIO already denied (revoked in path A), then a cold
# start and a press on 说梦. Expect a guidance surface instead of a crash.
set -u
ADB="C:/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe"
E="docs/evidence/plan2/perm"
exec > >(tee "$E/p2-perm-03-B.log") 2>&1

four () {
  echo "  pidof        : $("$ADB" shell pidof com.dreamkeeper | tr -d '\r')"
  echo "  pkg RECORD   : $("$ADB" shell dumpsys package com.dreamkeeper | grep RECORD_AUDIO | tail -1 | sed 's/^ *//' | tr -d '\r')"
  echo "  audio files  : $("$ADB" shell run-as com.dreamkeeper ls files/audio 2>/dev/null | wc -l | tr -d ' \r')"
}

"$ADB" shell am force-stop com.dreamkeeper
"$ADB" shell logcat -c
echo "=== shared device_date"
"$ADB" shell date
"$ADB" shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 2
echo "=== BEFORE (denied, calendar up)"
four
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-04-B-calendar.png" | tail -1
echo "=== tap 说梦 with permission denied at $(date +%H:%M:%S)"
"$ADB" shell input tap 540 2075
sleep 1.6
"$ADB" shell date
echo "=== AFTER"
four
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-05-B-denied.png" | tail -1
echo "--- logcat slice"
"$ADB" shell logcat -d -v threadtime | grep -E "FATAL|AndroidRuntime|SecurityException|AudioRecord|RECORD_AUDIO|permission|dreamkeeper" | tail -25
echo "--- FATAL count=$("$ADB" shell logcat -d -v threadtime | grep -c 'FATAL EXCEPTION' | tr -d '\r')"
echo "--- top activity"
"$ADB" shell dumpsys activity activities | grep -E "topResumedActivity" | head -1
echo "--- press 说梦 a second time (must not loop dialogs)"
"$ADB" shell input tap 540 2075
sleep 1.0
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-06-B-denied-twice.png" | tail -1
"$ADB" shell dumpsys activity activities | grep -E "topResumedActivity" | head -1
