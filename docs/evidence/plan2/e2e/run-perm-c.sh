#!/usr/bin/env bash
# Task 9 9.2 path C: permanent denial via `appops set ... RECORD_AUDIO ignore`
# while the runtime permission stays granted=true. Per brief 0.8 we only record
# WHAT ACTUALLY HAPPENS (exception class / zero-filled capture / silent no-op),
# and the verdict stays "待实测，不写判据".
set -u
ADB="C:/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe"
E="docs/evidence/plan2/perm"
exec > >(tee "$E/p2-perm-10-C.log") 2>&1

four () {
  echo "  pidof        : $("$ADB" shell pidof com.dreamkeeper | tr -d '\r')"
  echo "  pkg RECORD   : $("$ADB" shell dumpsys package com.dreamkeeper | grep RECORD_AUDIO | tail -1 | sed 's/^ *//' | tr -d '\r')"
  echo "  appops RECORD: $("$ADB" shell cmd appops get com.dreamkeeper RECORD_AUDIO | tr -d '\r')"
  echo "  audio files  : $("$ADB" shell run-as com.dreamkeeper ls files/audio 2>/dev/null | wc -l | tr -d ' \r')"
}

"$ADB" shell am force-stop com.dreamkeeper
"$ADB" shell pm grant com.dreamkeeper android.permission.RECORD_AUDIO
"$ADB" shell appops set com.dreamkeeper RECORD_AUDIO ignore
echo "=== shared device_date"
"$ADB" shell date
"$ADB" shell logcat -c
"$ADB" shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 2
echo "=== BEFORE (granted=true but appops=ignore)"
four
"$ADB" shell input tap 540 2075
sleep 0.9
echo "=== press 2.5 s at $(date +%H:%M:%S)"
"$ADB" shell input swipe 539 1545 539 1545 2500
sleep 0.8
"$ADB" shell date
echo "=== AFTER"
four
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-11-C-appops-ignore.png" | tail -1
echo "--- logcat slice (exception class hunt)"
"$ADB" shell logcat -d -v threadtime | grep -E "SecurityException|UnsupportedOperationException|IllegalStateException|AudioRecord|AudioFlinger|AppOps|RECORD_AUDIO|dreamkeeper|FATAL" | tail -40
echo "--- FATAL count=$("$ADB" shell logcat -d -v threadtime | grep -c 'FATAL EXCEPTION' | tr -d '\r')"
echo "--- top activity"
"$ADB" shell dumpsys activity activities | grep -E "topResumedActivity" | head -1
echo "--- newest wav size (if any)"
"$ADB" shell run-as com.dreamkeeper ls -l files/audio/ | tail -3
