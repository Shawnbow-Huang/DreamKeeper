#!/usr/bin/env bash
# Task 9 9.2 path C (second attempt): `cmd appops set --uid ... RECORD_AUDIO ignore`
# is the only form that actually flips the op on API 37 (the plain `appops set`
# left "Uid mode: foreground"). Runtime permission is still granted=true.
# We record only WHAT HAPPENS (brief 0.8): exception class, or a zero-filled take.
set -u
ADB="C:/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe"
E="docs/evidence/plan2/perm"
exec > >(tee "$E/p2-perm-12-C2.log") 2>&1

four () {
  echo "  pidof        : $("$ADB" shell pidof com.dreamkeeper | tr -d '\r')"
  echo "  pkg RECORD   : $("$ADB" shell dumpsys package com.dreamkeeper | grep RECORD_AUDIO | tail -1 | sed 's/^ *//' | tr -d '\r')"
  echo "  appops RECORD: $("$ADB" shell cmd appops get com.dreamkeeper RECORD_AUDIO | head -1 | tr -d '\r')"
  echo "  audio files  : $("$ADB" shell run-as com.dreamkeeper ls files/audio 2>/dev/null | wc -l | tr -d ' \r')"
}

"$ADB" shell am force-stop com.dreamkeeper
echo "=== shared device_date"
"$ADB" shell date
"$ADB" shell logcat -c
"$ADB" shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 2
echo "=== BEFORE (granted=true, appops uid-mode=ignore)"
four
"$ADB" shell input tap 540 2075
sleep 0.9
echo "=== press 2.5 s at $(date +%H:%M:%S)"
"$ADB" shell input swipe 539 1545 539 1545 2500
sleep 1.0
"$ADB" shell date
echo "=== AFTER"
four
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-13-C2-appops-uid-ignore.png" | tail -1
echo "--- logcat slice"
"$ADB" shell logcat -d -v threadtime | grep -E "SecurityException|UnsupportedOperationException|IllegalStateException|AudioRecord|AudioFlinger|AppOps|RECORD_AUDIO|RecordingService|dreamkeeper|FATAL|录制|录音" | grep -v "adbd service" | tail -40
echo "--- FATAL count=$("$ADB" shell logcat -d -v threadtime | grep -c 'FATAL EXCEPTION' | tr -d '\r')"
echo "--- newest wavs"
"$ADB" shell run-as com.dreamkeeper ls -lt files/audio/ | head -3
