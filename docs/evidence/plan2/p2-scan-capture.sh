#!/usr/bin/env bash
# Plan 2 Task 6 真机取证：启动扫描 / WAV 头修复 / 孤儿恢复 / 反向孤儿 / 干净 Noop / 0 字节。
#
# 四态全部走真实路径（§0-13）：
#   ① 有行 + 占位头：录 3s 松手 → 追问页补录 2s → 补录中 am force-stop
#   ② 有文件无行：长按录 3s → 录制中 am force-stop
#   ③ 有行无文件：run-as rm 掉 ② 恢复出的 WAV
#   ④ 干净：断言 ① 修复后的文件 sha256/mtime 与库里 updatedAt 在重启前后一字不差
#   ⑤ 补充：run-as touch 一个 0 字节 UUID 名 WAV（库无行）→ 重启后文件被删、不建行
#
# 跑法：cd /d/DreamKeeper && bash docs/evidence/plan2/p2-scan-capture.sh
# 依赖：emulator-5554、ffmpeg/ffprobe、python、adb 绝对路径；设备时区 Asia/Shanghai。
set -u

export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../../.." || exit 1
ROOT=$(pwd)

ADB=/c/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe
ADBW='C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe'
DEV="-s emulator-5554"
E=docs/evidence/plan2
A=$E/audio
WORK=.tmp-evidence/scan
mkdir -p "$E" "$A" "$WORK"
rm -f "$WORK"/* 2>/dev/null

APK=android/app/build/outputs/apk/debug/app-debug.apk
APK_SHA=$(sha256sum "$APK" | awk '{print $1}')

log() { echo "[$(date +%H:%M:%S)] $*"; }
device_date() { "$ADB" $DEV shell "date '+%Y-%m-%d %H:%M:%S %Z'" | tr -d '\r'; }
device_day() { "$ADB" $DEV shell "date '+%Y-%m-%d'" | tr -d '\r'; }

header() { # out artifact file_sha pre_state_by
    cat > "$1" <<EOF
apk_sha256: $APK_SHA
device_date: $(device_date)
replay: bash docs/evidence/plan2/p2-scan-capture.sh
artifact: $2
file_sha256: $3
pre_state_by: $4
EOF
}

pull_wav() { # id out(forward-slash path)
    cmd /c "$ADBW $DEV exec-out run-as com.dreamkeeper cat files/audio/$1.wav > $2"
}
wav_bytes() { "$ADB" $DEV shell run-as com.dreamkeeper stat -c %s "files/audio/$1.wav" | tr -d '\r'; }
wav_mtime() { "$ADB" $DEV shell run-as com.dreamkeeper stat -c %Y "files/audio/$1.wav" | tr -d '\r'; }
wav_sha_dev() { "$ADB" $DEV shell run-as com.dreamkeeper sha256sum "files/audio/$1.wav" | tr -d '\r' | awk '{print $1}'; }
last_audio_id() {
    "$ADB" $DEV shell "run-as com.dreamkeeper ls -t files/audio" | tr -d '\r' | grep '\.wav$' | head -1 | sed 's/\.wav$//'
}

pull_db() {
    "$ADB" $DEV shell am force-stop com.dreamkeeper >/dev/null 2>&1
    sleep 0.6
    rm -f "$WORK"/dreamkeeper.db "$WORK"/dreamkeeper.db-wal "$WORK"/dreamkeeper.db-shm
    local listed
    listed=$("$ADB" $DEV shell "run-as com.dreamkeeper ls databases" | tr -d '\r')
    for f in dreamkeeper.db dreamkeeper.db-wal dreamkeeper.db-shm; do
        if echo "$listed" | grep -qx "$f"; then
            cmd /c "$ADBW $DEV exec-out run-as com.dreamkeeper cat databases/$f > $WORK/$f"
        fi
    done
    DB_SHA=$(sha256sum "$WORK/dreamkeeper.db" | awk '{print $1}')
}

stats_tsv() {
    : > "$WORK/stats.tsv"
    local files f id b
    files=$("$ADB" $DEV shell "run-as com.dreamkeeper ls files/audio" | tr -d '\r' | grep '\.wav$')
    for f in $files; do
        id=$(basename "$f" .wav)
        b=$("$ADB" $DEV shell run-as com.dreamkeeper stat -c %s "files/audio/$f" | tr -d '\r')
        printf '%s\t%s\n' "$id" "$b" >> "$WORK/stats.tsv"
    done
}

db_report() { # out [extra python args...]
    local out=$1
    shift
    local args=()
    while IFS=$'\t' read -r id bytes; do
        [ -n "${id:-}" ] && args+=(--stats "$id=$bytes")
    done < "$WORK/stats.tsv"
    python docs/evidence/plan2/p2-scan-db.py "$WORK/dreamkeeper.db" "${args[@]}" "$@" > "$out"
    echo "db_report_exit: $?" >> "$out"
}

validate() { # out label wav
    header "$1" "$3" "$(sha256sum "$3" | awk '{print $1}')" "validator run on $3"
    {
        echo "---- validator output ----"
        python docs/evidence/plan2/p2-scan-validate.py "$3" --label "$2"
        echo "validate_exit_code: $?"
    } >> "$1"
}

# ---------------------------------------------------------------------------
log "APK sha256=$APK_SHA"
log "setup: clear data, grant permissions, keep screen on"
"$ADB" $DEV shell svc power stayon true >/dev/null
"$ADB" $DEV shell pm clear com.dreamkeeper >/dev/null
"$ADB" $DEV shell pm grant com.dreamkeeper android.permission.RECORD_AUDIO
"$ADB" $DEV shell pm grant com.dreamkeeper android.permission.POST_NOTIFICATIONS
"$ADB" $DEV shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 7

# ---------------------------------------------------------------------------
log "state 1: record 3s, release, patch 2s in follow-up, force-stop during patch"
"$ADB" $DEV shell input tap 541 2053
sleep 2
"$ADB" $DEV shell input swipe 541 1542 541 1542 3000
sleep 3
FOLLOW=$("$ADB" $DEV shell uiautomator dump /sdcard/p2-scan-u.xml >/dev/null 2>&1; "$ADB" $DEV shell cat /sdcard/p2-scan-u.xml | tr -d '\r')
echo "follow_up_page_seen: $(echo "$FOLLOW" | grep -c '追问')"

"$ADB" $DEV shell input swipe 540 1984 540 1984 2000 &
PATCH_PID=$!
sleep 1.4
"$ADB" $DEV shell am force-stop com.dreamkeeper
wait $PATCH_PID 2>/dev/null
sleep 1

ID1=$(last_audio_id)
log "state 1 dreamId=$ID1"
[ -n "$ID1" ] || { echo "FATAL: no wav after state 1"; exit 1; }

pull_wav "$ID1" "$A/p2-scan-01-before.wav"
B1=$(wav_bytes "$ID1")
S1_DEV=$(wav_sha_dev "$ID1")
S1_HOST=$(sha256sum "$A/p2-scan-01-before.wav" | awk '{print $1}')
pull_db
stats_tsv
db_report "$WORK/db-before.txt"

header "$E/p2-scan-01-before.txt" "audio/p2-scan-01-before.wav" "$S1_HOST" \
    "App: 3s 录音松手存草稿，追问页补录 2s 时 am force-stop（占位头由 markOpen 写回）"
{
    echo "audio_id: $ID1"
    echo "file_bytes: $B1"
    echo "expected_ms_if_repaired: $(( (B1 - 44) / 32 ))"
    echo "device_sha256: $S1_DEV"
    echo "host_sha256: $S1_HOST"
    echo "sha256_match: $([ "$S1_DEV" = "$S1_HOST" ] && echo yes || echo NO)"
    echo "hex_head_48: $(xxd -p -l 48 "$A/p2-scan-01-before.wav" | tr -d '\n')"
    echo "---- db (before restart) ----"
    cat "$WORK/db-before.txt"
} >> "$E/p2-scan-01-before.txt"
validate "$E/p2-scan-01-validate.txt" before "$A/p2-scan-01-before.wav"

# ---------------------------------------------------------------------------
log "restart: scan repairs placeholder header"
"$ADB" $DEV shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 7
"$ADB" $DEV shell am force-stop com.dreamkeeper
sleep 1

pull_wav "$ID1" "$A/p2-scan-02-repaired.wav"
B2=$(wav_bytes "$ID1")
S2_DEV=$(wav_sha_dev "$ID1")
S2_HOST=$(sha256sum "$A/p2-scan-02-repaired.wav" | awk '{print $1}')
MT2=$(wav_mtime "$ID1")
pull_db
stats_tsv
db_report "$WORK/db-after.txt"

header "$E/p2-scan-02-after.txt" "audio/p2-scan-02-repaired.wav" "$S2_HOST" \
    "App: 重启触发 AudioScan.repair，行与文件都已存在（§0-13 ①）"
{
    echo "audio_id: $ID1"
    echo "file_bytes: $B2"
    echo "device_sha256: $S2_DEV"
    echo "host_sha256: $S2_HOST"
    echo "sha256_match: $([ "$S2_DEV" = "$S2_HOST" ] && echo yes || echo NO)"
    echo "hex_head_48: $(xxd -p -l 48 "$A/p2-scan-02-repaired.wav" | tr -d '\n')"
    echo "---- db (after restart) ----"
    cat "$WORK/db-after.txt"
} >> "$E/p2-scan-02-after.txt"
validate "$E/p2-scan-02-validate.txt" after "$A/p2-scan-02-repaired.wav"

header "$E/p2-scan-03-db.txt" "databases/dreamkeeper.db" "$DB_SHA" \
    "App: state 1 重启修复后只读导出 Room 库（force-stop 后取 db+wal）"
db_report "$WORK/db-03.txt"
cat "$WORK/db-03.txt" >> "$E/p2-scan-03-db.txt"

# ---------------------------------------------------------------------------
log "state 4: clean file must stay byte- and mtime-identical across restart"
S4A=$(wav_sha_dev "$ID1")
M4A=$(wav_mtime "$ID1")
pull_db
UPD4A=$(python - "$WORK/dreamkeeper.db" "$ID1" <<'PY'
import sqlite3, sys, os
con = sqlite3.connect('file:' + os.path.abspath(sys.argv[1]).replace(os.sep, '/') + '?mode=ro', uri=True)
row = con.execute('select updatedAt from dreams where id=?', (sys.argv[2],)).fetchone()
print(row[0] if row else 'MISSING')
PY
)
"$ADB" $DEV shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 6
"$ADB" $DEV shell am force-stop com.dreamkeeper
sleep 1
S4B=$(wav_sha_dev "$ID1")
M4B=$(wav_mtime "$ID1")
pull_db
UPD4B=$(python - "$WORK/dreamkeeper.db" "$ID1" <<'PY'
import sqlite3, sys, os
con = sqlite3.connect('file:' + os.path.abspath(sys.argv[1]).replace(os.sep, '/') + '?mode=ro', uri=True)
row = con.execute('select updatedAt from dreams where id=?', (sys.argv[2],)).fetchone()
print(row[0] if row else 'MISSING')
PY
)
header "$E/p2-scan-04-clean.txt" "databases/dreamkeeper.db" "$DB_SHA" \
    "App: state 1 修复后的干净 WAV；重启只读，扫描应 Noop（§0-13 ④）"
{
    echo "audio_id: $ID1"
    echo "file_bytes: $(wav_bytes "$ID1")"
    echo "sha256_before_restart: $S4A"
    echo "sha256_after_restart:  $S4B"
    echo "sha256_identical: $([ "$S4A" = "$S4B" ] && echo yes || echo NO)"
    echo "mtime_before_restart: $M4A"
    echo "mtime_after_restart:  $M4B"
    echo "mtime_identical: $([ "$M4A" = "$M4B" ] && echo yes || echo NO)"
    echo "updatedAt_before_restart: $UPD4A"
    echo "updatedAt_after_restart:  $UPD4B"
    echo "updatedAt_identical: $([ "$UPD4A" = "$UPD4B" ] && echo yes || echo NO)"
} >> "$E/p2-scan-04-clean.txt"

# ---------------------------------------------------------------------------
log "state 2: start a take and force-stop mid-recording (file without row)"
"$ADB" $DEV shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 5
"$ADB" $DEV shell input tap 541 2053
sleep 2
"$ADB" $DEV shell input swipe 541 1542 541 1542 3000 &
HOLD_PID=$!
sleep 1.5
"$ADB" $DEV shell am force-stop com.dreamkeeper
wait $HOLD_PID 2>/dev/null
sleep 1

ID2=$(last_audio_id)
log "state 2 dreamId=$ID2"
[ -n "$ID2" ] || { echo "FATAL: no wav after state 2"; exit 1; }
pull_wav "$ID2" "$A/p2-scan-06-orphan-before.wav"
B6=$(wav_bytes "$ID2")
S6=$(wav_sha_dev "$ID2")
S6_HOST=$(sha256sum "$A/p2-scan-06-orphan-before.wav" | awk '{print $1}')
pull_db
stats_tsv
header "$E/p2-scan-06-orphan-before.txt" "audio/p2-scan-06-orphan-before.wav" "$S6_HOST" \
    "App: 长按手势 3000ms，开始录音约 1.5s 时 am force-stop；DONE 前不写库，所以有文件无行（§0-13 ② 前半）"
{
    echo "audio_id: $ID2"
    echo "file_bytes: $B6"
    echo "expected_ms_on_recovery: $(( (B6 - 44) / 32 ))"
    echo "device_sha256: $S6"
    echo "host_sha256: $S6_HOST"
    echo "sha256_match: $([ "$S6" = "$S6_HOST" ] && echo yes || echo NO)"
    echo "hex_head_48: $(xxd -p -l 48 "$A/p2-scan-06-orphan-before.wav" | tr -d '\n')"
} >> "$E/p2-scan-06-orphan-before.txt"
db_report "$WORK/db-06b.txt" --all
cat "$WORK/db-06b.txt" >> "$E/p2-scan-06-orphan-before.txt"
echo "db_has_row_for_orphan: $(python - "$WORK/dreamkeeper.db" "$ID2" <<'PY'
import sqlite3, sys, os
con = sqlite3.connect('file:' + os.path.abspath(sys.argv[1]).replace(os.sep, '/') + '?mode=ro', uri=True)
print('yes' if con.execute('select 1 from dreams where id=?', (sys.argv[2],)).fetchone() else 'no')
PY
)" >> "$E/p2-scan-06-orphan-before.txt"

log "restart: orphan recovered as today's draft"
"$ADB" $DEV shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 7
"$ADB" $DEV shell am force-stop com.dreamkeeper
sleep 1
pull_db
stats_tsv
header "$E/p2-scan-06-orphan-after.txt" "databases/dreamkeeper.db" "$DB_SHA" \
    "App: 重启触发 AudioScan.recoverDraft（§0-13 ② 后半）"
db_report "$WORK/db-06a.txt" --all
cat "$WORK/db-06a.txt" >> "$E/p2-scan-06-orphan-after.txt"
echo "today_on_device: $(device_day)" >> "$E/p2-scan-06-orphan-after.txt"
echo "recovered_bytes: $(wav_bytes "$ID2")" >> "$E/p2-scan-06-orphan-after.txt"
echo "expected_ms: $(( ($(wav_bytes "$ID2") - 44) / 32 ))" >> "$E/p2-scan-06-orphan-after.txt"

# ---------------------------------------------------------------------------
log "state 3: delete the recovered wav, row must be marked missing"
"$ADB" $DEV shell "run-as com.dreamkeeper rm files/audio/$ID2.wav"
"$ADB" $DEV shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 7
"$ADB" $DEV shell am force-stop com.dreamkeeper
sleep 1
pull_db
stats_tsv
header "$E/p2-scan-04-file-missing.txt" "databases/dreamkeeper.db" "$DB_SHA" \
    "adb: run-as rm files/audio/$ID2.wav，行仍在 → 重启触发 markMissing（§0-13 ③）"
{
    echo "deleted_audio_id: $ID2"
    db_report "$WORK/db-04.txt" --all
    cat "$WORK/db-04.txt"
} >> "$E/p2-scan-04-file-missing.txt"

# ---------------------------------------------------------------------------
log "state 5: zero-byte uuid wav without a row must be deleted"
ZERO_ID=00000000-0000-4000-8000-000000000000
"$ADB" $DEV shell "run-as com.dreamkeeper touch files/audio/$ZERO_ID.wav"
echo "zero_byte_created: $("$ADB" $DEV shell run-as com.dreamkeeper stat -c %s "files/audio/$ZERO_ID.wav" | tr -d '\r')"
"$ADB" $DEV shell am start -n com.dreamkeeper/.MainActivity >/dev/null
sleep 7
"$ADB" $DEV shell am force-stop com.dreamkeeper
sleep 1
EXISTS5=$("$ADB" $DEV shell "run-as com.dreamkeeper test -f files/audio/$ZERO_ID.wav && echo yes || echo no" | tr -d '\r')
pull_db
header "$E/p2-scan-05-zero-byte.txt" "databases/dreamkeeper.db" "$DB_SHA" \
    "adb: run-as touch 0 字节 UUID 名 WAV（库无行）→ 重启应删文件且不建行（§0-7 ⑤）"
{
    echo "zero_audio_id: $ZERO_ID"
    echo "zero_file_sha256_before: 0-byte file has no content (empty sha256 e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855)"
    echo "file_exists_after_restart: $EXISTS5"
    echo "row_exists_after_restart: $(python - "$WORK/dreamkeeper.db" "$ZERO_ID" <<'PY'
import sqlite3, sys, os
con = sqlite3.connect('file:' + os.path.abspath(sys.argv[1]).replace(os.sep, '/') + '?mode=ro', uri=True)
print('yes' if con.execute('select 1 from dreams where id=?', (sys.argv[2],)).fetchone() else 'no')
PY
)"
    db_report "$WORK/db-05.txt" --all
    cat "$WORK/db-05.txt"
} >> "$E/p2-scan-05-zero-byte.txt"

log "done. artifacts:"
ls -l "$E"/p2-scan-* "$A"/p2-scan-*
