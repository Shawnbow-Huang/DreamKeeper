#!/usr/bin/env bash
# Task 9 9.5: whole-library rawAudioMs audit.
# Precondition (brief 0.11): am force-stop + ONE cold start so AudioScan.repair has
# finished, then enumerate. Placeholder / open files are reported as SKIP-OPEN and
# never enter the diff. Verdict: (bytes-44)//32 == rawAudioMs, tolerance 0.
set -u
export MSYS2_ARG_CONV_EXCL='*'
ADB="C:/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe"
E="docs/evidence/plan2/audit"; mkdir -p "$E/pull"
exec > >(tee "$E/p2-audit-01-run.log") 2>&1

"$ADB" shell am force-stop com.dreamkeeper
echo "=== shared device_date"
"$ADB" shell date
echo "=== cold start so the scan runs to completion"
"$ADB" shell am start -W -n com.dreamkeeper/.MainActivity
sleep 4
"$ADB" shell am force-stop com.dreamkeeper
echo "=== file listing after scan"
"$ADB" shell run-as com.dreamkeeper ls -l files/audio/ | tee "$E/p2-audit-02-files.txt"
echo "=== headers (first 44 bytes) of every wav"
for f in $("$ADB" shell run-as com.dreamkeeper ls files/audio/ | tr -d '\r'); do
  echo "-- $f"
  "$ADB" shell run-as com.dreamkeeper od -An -tx1 -N 44 "files/audio/$f" | tr -d '\r'
done | tee "$E/p2-audit-03-headers.txt"
echo "=== pull every wav + db + wal"
for f in $("$ADB" shell run-as com.dreamkeeper ls files/audio/ | tr -d '\r'); do
  cmd /c "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe exec-out run-as com.dreamkeeper cat files/audio/$f > D:\DreamKeeper\\docs\\evidence\\plan2\\audit\\pull\\$f"
done
cmd /c "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe exec-out run-as com.dreamkeeper cat databases/dreamkeeper.db > D:\DreamKeeper\docs\evidence\plan2\audit\dreamkeeper.db"
cmd /c "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe exec-out run-as com.dreamkeeper cat databases/dreamkeeper.db-wal > D:\DreamKeeper\docs\evidence\plan2\audit\dreamkeeper.db-wal"
ls -l "$E/pull"
