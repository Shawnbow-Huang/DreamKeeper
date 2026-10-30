#!/usr/bin/env bash
# Task 9 9.4: one live take with two byte samples taken WHILE recording (t1, t2),
# plus the isForeground proof that the FGS is already up before t1 (D166).
# Everything runs inside one shell so tool-call latency cannot move the samples.
set -u
ADB="C:/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe"
E="docs/evidence/plan2/e2e"
OUT="$E/p2-e2e-launch-live-take.log"
exec > >(tee "$OUT") 2>&1

"$ADB" shell am force-stop com.dreamkeeper
"$ADB" shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 2
"$ADB" shell date
"$ADB" shell input tap 540 2075
sleep 1.0
echo "--- existing wavs before press"
"$ADB" shell run-as com.dreamkeeper ls files/audio/ 2>/dev/null

# press 5 s in the background of THIS shell
"$ADB" shell input swipe 539 1545 539 1545 5000 &
JOB=$!
sleep 1.0
echo "--- during press (new file appears)"
"$ADB" shell run-as com.dreamkeeper ls files/audio/
NEW=$("$ADB" shell run-as com.dreamkeeper ls -t files/audio/ | head -1 | tr -d '\r')
echo "new_file=$NEW"
sleep 0.3
echo "--- isForeground (must be true BEFORE t1)"
"$ADB" shell dumpsys activity services com.dreamkeeper | grep -E "isForeground|startRequested|foregroundId|types=" | head -6
echo "--- t1"
"$ADB" shell date
"$ADB" shell run-as com.dreamkeeper stat -c %s "files/audio/$NEW"
"$ADB" shell cat /proc/uptime
sleep 1.8
echo "--- t2"
"$ADB" shell date
"$ADB" shell run-as com.dreamkeeper stat -c %s "files/audio/$NEW"
"$ADB" shell cat /proc/uptime
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-e2e-11-live-take-mid.png" | tail -2
wait $JOB
sleep 1.0
echo "--- after release"
"$ADB" shell date
"$ADB" shell run-as com.dreamkeeper stat -c %s "files/audio/$NEW"
"$ADB" shell run-as com.dreamkeeper od -An -tx1 -N 44 "files/audio/$NEW"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-e2e-12-live-take-followup.png" | tail -2
echo "wav=$NEW" | tee "$E/p2-e2e-live-take-name.txt"
