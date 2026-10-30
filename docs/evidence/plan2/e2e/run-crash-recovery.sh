#!/usr/bin/env bash
# Task 9 9.3: crash recovery (D185/D186 construction)
#   3 s take -> release (row written, header refilled) -> append 2 s (markOpen rewrites
#   both length fields to 0xFFFFFFFF) -> am force-stop WHILE appending -> restart
#   -> AudioScan.repair -> detail page duration must equal the repaired bytes.
set -u
ADB="C:/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe"
E="docs/evidence/plan2/e2e"
exec > >(tee "$E/p2-e2e-run-crash-recovery.log") 2>&1

"$ADB" shell am force-stop com.dreamkeeper
"$ADB" shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 2
"$ADB" shell date
"$ADB" shell input tap 540 2075          # 说梦
sleep 0.9
echo "--- take 1: 3 s"
"$ADB" shell input swipe 539 1545 539 1545 3000
"$ADB" shell date
sleep 1.2
NEW=$("$ADB" shell run-as com.dreamkeeper ls -t files/audio/ | head -1 | tr -d '\r')
B1=$("$ADB" shell run-as com.dreamkeeper stat -c %s "files/audio/$NEW" | tr -d '\r')
echo "new_file=$NEW bytes_after_take1=$B1"
echo "--- header after take 1 (should be refilled, no FF FF FF FF)"
"$ADB" shell run-as com.dreamkeeper od -An -tx1 -N 44 "files/audio/$NEW"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-e2e-13-crash-followup.png" | tail -2

echo "--- append 2 s, then force-stop WHILE appending"
"$ADB" shell input swipe 539 1964 539 1964 7000 &
JOB=$!
sleep 1.6
"$ADB" shell date
B2=$("$ADB" shell run-as com.dreamkeeper stat -c %s "files/audio/$NEW" | tr -d '\r')
echo "bytes_mid_append=$B2"
echo "--- header mid-append (markOpen: both length fields must be FF FF FF FF)"
"$ADB" shell run-as com.dreamkeeper od -An -tx1 -N 44 "files/audio/$NEW"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-e2e-14-crash-mid-append.png" | tail -2
echo "--- force-stop"
"$ADB" shell date
"$ADB" shell am force-stop com.dreamkeeper
"$ADB" shell pidof com.dreamkeeper; echo "pidof_after_kill_exit=$?"
wait $JOB 2>/dev/null
B3=$("$ADB" shell run-as com.dreamkeeper stat -c %s "files/audio/$NEW" | tr -d '\r')
echo "bytes_after_force_stop=$B3"
echo "--- header after force-stop (still placeholder: no DONE ran)"
"$ADB" shell run-as com.dreamkeeper od -An -tx1 -N 44 "files/audio/$NEW"
cmd /c "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe exec-out run-as com.dreamkeeper cat databases/dreamkeeper.db > D:\DreamKeeper\$E\p2-e2e-crash-before.db"
cmd /c "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe exec-out run-as com.dreamkeeper cat databases/dreamkeeper.db-wal > D:\DreamKeeper\$E\p2-e2e-crash-before.db-wal"
echo "wav=$NEW" | tee "$E/p2-e2e-crash-name.txt"

echo "--- restart: AudioScan.repair should run at startup"
"$ADB" shell date
"$ADB" shell am start -W -n com.dreamkeeper/.MainActivity
sleep 3
"$ADB" shell date
echo "--- header after restart"
"$ADB" shell run-as com.dreamkeeper od -An -tx1 -N 44 "files/audio/$NEW"
"$ADB" shell run-as com.dreamkeeper stat -c %s "files/audio/$NEW"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-e2e-15-crash-after-restart.png" | tail -2
