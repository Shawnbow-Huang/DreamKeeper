#!/usr/bin/env bash
# Task 9 9.2 recovery: appops back to allow + pm grant, then a full short chain
# (说梦 -> 按住 -> 松手 -> 追问页 -> 就这样) must work again, and dumpsys must show
# "RECORD_AUDIO: granted=true" (saved into the evidence).
set -u
ADB="C:/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe"
E="docs/evidence/plan2/perm"
exec > >(tee "$E/p2-perm-14-recovery.log") 2>&1

"$ADB" shell am force-stop com.dreamkeeper
"$ADB" shell appops set --uid com.dreamkeeper RECORD_AUDIO allow
"$ADB" shell pm grant com.dreamkeeper android.permission.RECORD_AUDIO
echo "=== shared device_date"
"$ADB" shell date
echo "=== grant state (must be granted=true)"
"$ADB" shell dumpsys package com.dreamkeeper | grep RECORD_AUDIO
echo "=== appops"
"$ADB" shell cmd appops get com.dreamkeeper RECORD_AUDIO | head -2
"$ADB" shell logcat -c
"$ADB" shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 2
"$ADB" shell input tap 540 2075
sleep 0.9
echo "=== record 2 s at $(date +%H:%M:%S)"
"$ADB" shell input swipe 539 1545 539 1545 2000
sleep 1.4
"$ADB" shell date
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-15-recovery-followup.png" | tail -1
echo "--- newest wav"
"$ADB" shell run-as com.dreamkeeper ls -lt files/audio/ | head -2
echo "--- FATAL count=$("$ADB" shell logcat -d -v threadtime | grep -c 'FATAL EXCEPTION' | tr -d '\r')"
"$ADB" shell input tap 539 2178
sleep 1.2
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-perm-16-recovery-done.png" | tail -1
"$ADB" shell date
