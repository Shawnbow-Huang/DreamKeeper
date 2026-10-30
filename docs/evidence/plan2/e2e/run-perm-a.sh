#!/usr/bin/env bash
# Task 9 9.2 path A: RECORD_AUDIO is granted and a recording is IN PROGRESS,
# then `pm revoke` fires. On API 11+ the revoke kills the process (that is
# system behaviour, not a crash). Before/after four-piece set + one shared date.
set -u
ADB="C:/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe"
E="docs/evidence/plan2/perm"; mkdir -p "$E"
exec > >(tee "$E/p2-perm-01-A.log") 2>&1

four () {
  echo "  pidof        : $("$ADB" shell pidof com.dreamkeeper | tr -d '\r')"
  echo "  pkg RECORD   : $("$ADB" shell dumpsys package com.dreamkeeper | grep RECORD_AUDIO | tail -1 | sed 's/^ *//' | tr -d '\r')"
  echo "  audio files  : $("$ADB" shell run-as com.dreamkeeper ls files/audio 2>/dev/null | wc -l | tr -d ' \r')"
}

"$ADB" shell am force-stop com.dreamkeeper
"$ADB" shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 2
echo "=== shared device_date (all three paths use this line)"
"$ADB" shell date
echo "=== BEFORE (idle)"
four
echo "=== start recording, then revoke WHILE recording"
"$ADB" shell logcat -c
"$ADB" shell input tap 540 2075
sleep 0.9
"$ADB" shell input swipe 539 1545 539 1545 6000 &
JOB=$!
sleep 1.4
echo "--- during recording (isForeground proof)"
"$ADB" shell dumpsys activity services com.dreamkeeper | grep -E "isForeground=" | head -2
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-01-A-recording.png" | tail -1
echo "--- revoke at $(date +%H:%M:%S)"
"$ADB" shell pm revoke com.dreamkeeper android.permission.RECORD_AUDIO
echo "revoke_exit=$?"
sleep 0.4
echo "=== AFTER revoke (still inside the 6 s press window)"
four
"$ADB" shell date
wait $JOB 2>/dev/null
sleep 0.6
echo "=== AFTER press window closed"
four
echo "--- logcat since -c (FATAL / AndroidRuntime / SecurityException / AudioRecord)"
"$ADB" shell logcat -d -v threadtime | grep -E "FATAL|AndroidRuntime|SecurityException|AudioRecord|RECORD_AUDIO|permission" | tail -30
echo "--- logcat: any FATAL at all?  count=$("$ADB" shell logcat -d -v threadtime | grep -c 'FATAL EXCEPTION' | tr -d '\r')"
echo "--- top activity after revoke"
"$ADB" shell dumpsys activity activities | grep -E "topResumedActivity" | head -2
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-02-A-after-revoke.png" | tail -1
