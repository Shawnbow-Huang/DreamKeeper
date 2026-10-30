#!/usr/bin/env bash
# Task 3 · 前台录音服务（FGS）四连判据采集脚本（可复跑）
#
# 前置：
#   1. 已 `./gradlew :app:assembleDebug` 且 APK 内含 Task 3 临时证据钩子（见 p2-fgs-01-harness.patch）
#   2. `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
#   3. 已 `adb shell pm grant com.dreamkeeper android.permission.RECORD_AUDIO`
#      `adb shell pm grant com.dreamkeeper android.permission.POST_NOTIFICATIONS`
#   4. emulator-5554 已启动、时区 Asia/Shanghai、屏幕解锁
#
# 用法：bash docs/evidence/plan2/p2-fgs-01-capture.sh
#
# 判据四连：
#   ① dumpsys activity services 同一段 ServiceRecord：startRequested=true + isForeground=true
#      + foregroundId=42 + types=0x00000080（FOREGROUND_SERVICE_TYPE_MICROPHONE）
#   ② dumpsys notification --noredact：id=42 + flags 含 FOREGROUND_SERVICE(0x40) + ongoing
#   ③ 两次 stat 采样间隔 >= 3s，Δbytes/Δt 落在 32000 B/s ± 2%
#   ④ logcat 里 FATAL / ForegroundServiceStartNotAllowed / DidNotStartInTime 全空
#
# 额外：松手（STOP）后 WAV 头 offset 4..8 与 40..44 已回填（不再是 FF FF FF FF）
#
# 注意：全程不使用 `am start-foreground-service`（那是绕过 Activity 的直起路径，
#       会跳过「服务只能由可见 Activity 启动」的验证）。本脚本用 MainActivity 的
#       DEBUG 钩子：Activity 真正可见后（decorView.post）再 startForegroundService。

set -u

cd "$(dirname "$0")/../../.." || exit 1

ADB="${ADB:-/c/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG=com.dreamkeeper
DREAM=p2fgs01
E=docs/evidence/plan2
APK=android/app/build/outputs/apk/debug/app-debug.apk
FILE="files/audio/$DREAM.wav"

APK_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SDK=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
REL=$("$ADB" shell getprop ro.build.version.release | tr -d '\r')
DEV=$("$ADB" get-serialno | tr -d '\r')

say() { echo "[fgs] $*"; }

mkdir -p "$E/audio"

# 截图闸门要在深色系统 UI 下才成立：浅色主题的通知栏里「app 图标圆底」是纯白，
# 会被 check-near-white.ps1 -MaxRatio 0 判红（实测 0.234%）。采集期间切深色，结束时还原。
PREV_NIGHT=$("$ADB" shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
"$ADB" shell cmd uimode night yes >/dev/null 2>&1
say "night mode: $PREV_NIGHT -> yes (restored at the end)"

{ cat <<'EOF'
# 截图采集说明
device_theme: 采集期间 cmd uimode night yes（脚本结束时还原为采集前的值）
why: check-near-white.ps1 -TopOffset 100 -MaxRatio 0 对通知栏截图会判红：
     浅色系统主题 nearWhite=162/69120=0.234%，深色主题 nearWhite=118/69120=0.171%。
     白像素全部落在通知条目左侧「系统给 app 图标垫的白圆底」包围盒内（定位见
     p2-fgs-01-notify.txt 里的 p2-fgs-01-whitepixels.ps1 输出），不是 app 渲染异常。
result: 仍然 rc=1（判红）。该 near-white 来自 SystemUI 的通知条目样式，换主题只能减少不能消除；
     app 截图（录音页）不受影响，见 Task 4 的 p2-ux-*。数据见 p2-fgs-01-notify.txt。
EOF
} >"$E/p2-fgs-01-theme.txt" 2>&1

say "apk=$APK_SHA device=$DEV android=$REL api=$SDK"

# 0. 清场：旧文件、旧日志
"$ADB" shell run-as "$PKG" rm -f "$FILE" >/dev/null 2>&1
"$ADB" logcat -c
say "logcat cleared, $FILE removed"

# 1. 从可见 Activity 起录音（临时钩子 → decorView.post → startForegroundService）
"$ADB" shell am start -n "$PKG/.MainActivity" \
  --ez dk_debug_record true --es dk_debug_dream_id "$DREAM" --activity-clear-task >/dev/null
sleep 3
PID=$("$ADB" shell pidof "$PKG" | tr -d '\r')
NOW=$("$ADB" shell date | tr -d '\r')
say "pid=$PID date=$NOW"

REPLAY_START="adb shell am start -n $PKG/.MainActivity --ez dk_debug_record true --es dk_debug_dream_id $DREAM --activity-clear-task"

# 2. ① 服务 / 类型
{
  echo "# 判据① 前台服务（dumpsys activity services）"
  echo "apk_sha256: $APK_SHA"
  echo "device: $DEV / Android $REL (API $SDK)"
  echo "device_date: $NOW"
  echo "replay: $REPLAY_START"
  echo "replay: adb shell dumpsys activity services $PKG"
  echo "expect: 同一段 ServiceRecord 内 startRequested=true + isForeground=true + foregroundId=42 + types=0x00000080"
  echo "note: API 37 的 dumpsys 字段名是 startRequested（旧版本叫 started）；types 打十六进制，"
  echo "      0x00000080 = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE（0x80），"
  echo "      manifest 里对应 android:foregroundServiceType=\"microphone\"。"
  echo
  "$ADB" shell dumpsys activity services "$PKG" | tr -d '\r'
} >"$E/p2-fgs-01-services.txt" 2>&1

# 判据① 机器核对：对同一份 dump 做正则计数，避免肉眼误读（先算后写，避免自计数）
C1=$(grep -c 'isForeground=true foregroundId=42 types=0x00000080' "$E/p2-fgs-01-services.txt")
C2=$(grep -c 'startRequested=true' "$E/p2-fgs-01-services.txt")
C3=$(grep -c 'foregroundNoti=.*FOREGROUND_SERVICE' "$E/p2-fgs-01-services.txt")
C4=$(grep -c 'startCommandResult=2' "$E/p2-fgs-01-services.txt")
C5=$(grep -c 'infoAllowStartForeground' "$E/p2-fgs-01-services.txt")
{
  echo
  echo "--- 机器核对（对上面同一份 dump 做正则计数，期望各 >=1）"
  echo "isForeground=true + foregroundId=42 + types=0x00000080 : $C1"
  echo "startRequested=true                                    : $C2"
  echo "foregroundNoti flags 含 FOREGROUND_SERVICE             : $C3"
  echo "startCommandResult=2（START_NOT_STICKY）               : $C4"
  echo "infoAllowStartForeground（系统记录起服务时的调用方状态）: $C5"
} >>"$E/p2-fgs-01-services.txt" 2>&1

# 3. ② 通知
{
  echo "# 判据② 前台通知（dumpsys notification --noredact）"
  echo "apk_sha256: $APK_SHA"
  echo "device_date: $NOW"
  echo "replay: adb shell dumpsys notification --noredact | grep -A 12 'pkg=com.dreamkeeper'"
  echo "expect: id=42 + tag=null + flags 含 FOREGROUND_SERVICE + ongoing（NO_CLEAR / ONGOING_EVENT）"
  echo "note: Notification.flags 是位集合，dumpsys 打的是符号名："
  echo "      FLAG_FOREGROUND_SERVICE=0x40、FLAG_ONGOING_EVENT=0x2、FLAG_NO_CLEAR=0x20、FLAG_ONLY_ALERT_ONCE=0x8；"
  echo "      importance=2 即 IMPORTANCE_LOW（渠道重要性）；category=service、vis=PRIVATE 为计划要求。"
  echo
  "$ADB" shell dumpsys notification --noredact | tr -d '\r' | grep -A 12 'pkg=com.dreamkeeper'
} >"$E/p2-fgs-01-notify-dump.txt" 2>&1

# 4. ③ 字节速率（4 次 stat + /proc/uptime，同一 adb 指令内取，避免 adb 往返误差）
sample() {
  # 输出：file_bytes uptime_seconds[A] uptime_idle[B]
  "$ADB" shell "run-as $PKG stat -c %s $FILE; cat /proc/uptime" | tr -d '\r' | tr '\n' ' '
  echo
}
S1=$(sample); say "sample1: $S1"; sleep 2
S2=$(sample); say "sample2: $S2"; sleep 2
S3=$(sample); say "sample3: $S3"; sleep 2
S4=$(sample); say "sample4: $S4"

read -r B1 T1 _ <<<"$S1"
read -r B2 T2 _ <<<"$S2"
read -r B3 T3 _ <<<"$S3"
read -r B4 T4 _ <<<"$S4"

RATE=$(awk -v b1="$B1" -v t1="$T1" -v b4="$B4" -v t4="$T4" 'BEGIN{ if (t4==t1) {print "n/a"; exit} printf "%.3f", (b4-b1)/(t4-t1) }')
# 最小二乘斜率：把 100ms 分块写入带来的采样抖动平均掉
SLOPE=$(awk -v b1="$B1" -v t1="$T1" -v b2="$B2" -v t2="$T2" -v b3="$B3" -v t3="$T3" -v b4="$B4" -v t4="$T4" '
  BEGIN{ n=4; sx=t1+t2+t3+t4; sy=b1+b2+b3+b4; sxx=t1*t1+t2*t2+t3*t3+t4*t4; sxy=t1*b1+t2*b2+t3*b3+t4*b4;
         den=n*sxx-sx*sx; if (den==0) {print "n/a"; exit} printf "%.3f", (n*sxy-sx*sy)/den }')

{
  echo "# 判据③ 写入速率（Δbytes / Δuptime，单位 B/s；期望 16000 Hz × 2 B = 32000 B/s）"
  echo "apk_sha256: $APK_SHA"
  echo "device_date: $NOW"
  echo "file: $FILE"
  echo "replay: adb shell \"run-as $PKG stat -c %s $FILE; cat /proc/uptime\""
  echo "method: 每 2 s 采一次「文件字节数 + 设备开机秒数(/proc/uptime 第一列)」，共 4 次；"
  echo "        /proc/uptime 是单调时钟，不受 adb 往返与主机时钟影响；"
  echo "        run-as 只包住 stat，/proc/uptime 由 shell 用户读取（两者间隔约 5ms）。"
  echo "        rate_Bps 取首末两次的平均斜率；lsq_Bps 取 4 点的最小二乘斜率（消除 100ms 分块写入的相位抖动）。"
  echo "expect: 32000 B/s ± 2% → [31360, 32640]"
  echo
  echo "sample1: bytes=$B1 uptime=$T1"
  echo "sample2: bytes=$B2 uptime=$T2"
  echo "sample3: bytes=$B3 uptime=$T3"
  echo "sample4: bytes=$B4 uptime=$T4"
  echo
  echo "delta_bytes: $((B4 - B1))"
  echo "delta_seconds: $(awk -v t1="$T1" -v t4="$T4" 'BEGIN{printf "%.3f", t4-t1}')"
  echo "rate_Bps: $RATE"
  echo "lsq_Bps: $SLOPE"
  echo "tolerance: 31360..32640"
  awk -v r="$RATE" -v s="$SLOPE" 'BEGIN{
        ok=(r>=31360 && r<=32640) && (s>=31360 && s<=32640);
        printf "verdict: %s\n", (ok?"PASS":"FAIL");
      }'
  echo
  echo "note: 分块写入（RecorderCore 每 100ms 一次 write、每 32000 真实字节一次 fd.sync）会让"
  echo "      文件长度以 3200 字节为步长跳变，因此单次首末采样会有 ±(3200/Δt) 的相位误差；"
  echo "      Δt≈6.8s 时为 ±470 B/s（±1.5%），已用最小二乘斜率交叉验证。"
} >"$E/p2-fgs-01-bytes.txt" 2>&1

# 5. ② 通知栏截图（先展开，截图后收起）+ 截图元数据
"$ADB" shell cmd statusbar expand-notifications >/dev/null 2>&1
sleep 2
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath "$E/p2-fgs-01-notify.png" >"$E/.p2-fgs-01-capture.raw" 2>&1
CAP_RC=$?
tr -d '\r' <"$E/.p2-fgs-01-capture.raw" >"$E/.p2-fgs-01-capture.log"
"$ADB" shell cmd statusbar collapse >/dev/null 2>&1
PNG_SHA=$(sha256sum "$E/p2-fgs-01-notify.png" | cut -d' ' -f1)
say "screenshot rc=$CAP_RC sha=$PNG_SHA"

# 注意：不能写成 `powershell ... | tr ...`，否则 $? 是 tr 的退出码（曾因此把 FAIL 记成 rc=0）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-near-white.ps1 -Path "$E/p2-fgs-01-notify.png" -TopOffset 100 -MaxRatio 0 >"$E/.p2-fgs-01-nearwhite.raw" 2>&1
NW_RC=$?
tr -d '\r' <"$E/.p2-fgs-01-nearwhite.raw" >"$E/.p2-fgs-01-nearwhite.log"
say "near-white gate rc=$NW_RC"

# 闸门若因「系统给 app 图标垫的白圆底」判红，把白像素定位出来，避免把系统样式误记成 app 渲染问题
powershell -NoProfile -ExecutionPolicy Bypass -File "$E/p2-fgs-01-whitepixels.ps1" -Path "$E/p2-fgs-01-notify.png" -TopOffset 100 >"$E/.p2-fgs-01-whitepixels.raw" 2>&1
WP_RC=$?
tr -d '\r' <"$E/.p2-fgs-01-whitepixels.raw" >"$E/.p2-fgs-01-whitepixels.log"

{
  echo "# 判据② 通知栏视觉证据（p2-fgs-01-notify.png）"
  echo "apk_sha256: $APK_SHA"
  echo "device: $DEV / Android $REL (API $SDK)"
  echo "device_date: $NOW"
  echo "png_sha256: $PNG_SHA"
  echo "device_theme: dark（采集前 cmd uimode night yes；原值 $PREV_NIGHT，脚本结束时还原）"
  echo "capture_cost_seconds: 展开通知栏后 sleep 2 再截屏"
  echo "replay: adb shell cmd uimode night yes"
  echo "replay: adb shell am start -n $PKG/.MainActivity --ez dk_debug_record true --es dk_debug_dream_id $DREAM --activity-clear-task"
  echo "replay: sleep 3"
  echo "replay: adb shell cmd statusbar expand-notifications; sleep 2"
  echo "replay: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 -OutPath $E/p2-fgs-01-notify.png"
  echo "replay: adb shell cmd statusbar collapse"
  echo "replay: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-near-white.ps1 -Path $E/p2-fgs-01-notify.png -TopOffset 100 -MaxRatio 0"
  echo "replay: adb shell cmd uimode night $PREV_NIGHT"
  echo "expect: 通知栏里可见「梦匣 / 正在录梦 / 松开就结束；点「结束」立刻停」，条目左侧为 app 图标；"
  echo "        状态栏右上角出现麦克风进行中 chip（绿色），即系统认为正在使用麦克风"
  echo
  echo "--- capture-screen.ps1 输出（rc=$CAP_RC）"
  cat "$E/.p2-fgs-01-capture.log"
  echo
  echo "--- check-near-white.ps1 输出（TopOffset=100 MaxRatio=0，rc=$NW_RC，0=通过）"
  cat "$E/.p2-fgs-01-nearwhite.log"
  echo
  echo "--- 白像素来源定位（p2-fgs-01-whitepixels.ps1，rc=$WP_RC）"
  cat "$E/.p2-fgs-01-whitepixels.log"
  echo
  echo "note: 深色系统主题下通知栏底色是 #151720 一类，白像素只出现在"
  echo "      通知条目左侧「系统给 app 图标垫的白圆底」这一个包围盒里（见上面的定位输出）；"
  echo "      浅色主题下同一画面更差（0.234%，见 p2-fgs-01-theme.txt）。"
  echo "      app 自身是深色主题，白圆底是 SystemUI 的通知样式，不是 app 渲染异常。"
} >"$E/p2-fgs-01-notify.txt" 2>&1

# 6. 权限
{
  echo "# 权限面（dumpsys package）：四条 uses-permission 均需出现，且运行时/安装时均已授予"
  echo "apk_sha256: $APK_SHA"
  echo "device_date: $NOW"
  echo "replay: adb shell dumpsys package $PKG | grep -E 'RECORD_AUDIO|POST_NOTIFICATIONS|FOREGROUND_SERVICE'"
  echo "expect: requested 里 4 条齐全；FOREGROUND_SERVICE / FOREGROUND_SERVICE_MICROPHONE granted=true；"
  echo "        RECORD_AUDIO / POST_NOTIFICATIONS 运行时 granted=true（本次用 pm grant 预授权）"
  echo
  "$ADB" shell dumpsys package "$PKG" | tr -d '\r' | grep -nE 'RECORD_AUDIO|POST_NOTIFICATIONS|FOREGROUND_SERVICE'
} >"$E/p2-fgs-01-perms.txt" 2>&1

# 7. 松手（STOP）→ 收尾
"$ADB" shell am start -n "$PKG/.MainActivity" \
  --ez dk_debug_stop true --activity-clear-task >/dev/null
say "stop sent"
sleep 3
STOP_TIME=$("$ADB" shell date | tr -d '\r')

# 8. ④ logcat（整个录制窗口，含起停）
"$ADB" logcat -d >"$E/.p2-fgs-01-logcat-raw.txt" 2>&1
FATAL_N=$(grep -c 'FATAL' "$E/.p2-fgs-01-logcat-raw.txt" || true)
FGS_N=$(grep -c 'ForegroundServiceStartNotAllowed' "$E/.p2-fgs-01-logcat-raw.txt" || true)
TIME_N=$(grep -c 'DidNotStartInTime' "$E/.p2-fgs-01-logcat-raw.txt" || true)
{
  echo "# 判据④ logcat 三类关键字（$DEV，整个录制窗口：$NOW → $STOP_TIME）"
  echo "apk_sha256: $APK_SHA"
  echo "replay: adb logcat -d > logcat.txt; grep -c FATAL logcat.txt; grep -c ForegroundServiceStartNotAllowed logcat.txt; grep -c DidNotStartInTime logcat.txt"
  echo "expect: 三条计数均为 0"
  echo
  echo "FATAL=$FATAL_N"
  echo "ForegroundServiceStartNotAllowed=$FGS_N"
  echo "DidNotStartInTime=$TIME_N"
  echo
  echo "--- 本 app 相关日志（DK_DEBUG 钩子 / ActivityManager Background started FGS / RecordingService）"
  grep -E "DK_DEBUG|Background started FGS|RecordingService" "$E/.p2-fgs-01-logcat-raw.txt" | tail -n 40
  echo
  echo "--- FATAL 命中行（应为空）"
  grep 'FATAL' "$E/.p2-fgs-01-logcat-raw.txt" || echo "(none)"
  echo
  echo "--- ForegroundServiceStartNotAllowed 命中行（应为空）"
  grep 'ForegroundServiceStartNotAllowed' "$E/.p2-fgs-01-logcat-raw.txt" || echo "(none)"
  echo
  echo "--- DidNotStartInTime 命中行（应为空）"
  grep 'DidNotStartInTime' "$E/.p2-fgs-01-logcat-raw.txt" || echo "(none)"
} >"$E/p2-fgs-01-logcat.txt" 2>&1

# 9. 停止后：服务消失 + 通知消失 + WAV 头回填
SIZE=$("$ADB" shell run-as "$PKG" stat -c %s "$FILE" | tr -d '\r')
HDR=$("$ADB" shell run-as "$PKG" od -An -tx1 -N 44 "$FILE" | tr -d '\r' | tr -s ' ' '\n' | grep -v '^$')
mapfile -t H <<<"$HDR"
if [ "${#H[@]}" -lt 44 ]; then echo "header read failed: got ${#H[@]} bytes" >&2; fi
u32() { echo $((0x${H[$1]} + (0x${H[$(( $1 + 1 ))]} << 8) + (0x${H[$(( $1 + 2 ))]} << 16) + (0x${H[$(( $1 + 3 ))]} << 24))); }
RS=$(u32 4)
DS=$(u32 40)
{
  echo "# 松手后：服务消失、WAV 头回填（offset 4..8 / 40..44 不再是 FF FF FF FF）"
  echo "apk_sha256: $APK_SHA"
  echo "device_date: $STOP_TIME"
  echo "file: $FILE"
  echo "file_bytes: $SIZE"
  echo "replay: adb shell run-as $PKG stat -c %s $FILE"
  echo "replay: adb shell run-as $PKG od -An -tx1 -N 44 $FILE"
  echo "replay: adb shell dumpsys activity services $PKG   # 应无 ServiceRecord"
  echo "expect: riffSize = fileBytes-8 = $((SIZE - 8))；dataSize = fileBytes-44 = $((SIZE - 44))"
  echo
  echo "--- 前 44 字节（od -An -tx1 -N 44，每行 16 字节）"
  "$ADB" shell run-as "$PKG" od -An -tx1 -N 44 "$FILE" | tr -d '\r'
  echo
  echo "| offset | 字段 | 读到的字节 | 解释 |"
  echo "|--------|------|-----------|------|"
  echo "| 4..8   | riffSize  | ${H[4]} ${H[5]} ${H[6]} ${H[7]} | $RS（期望 $((SIZE - 8))）|"
  echo "| 40..44 | dataSize  | ${H[40]} ${H[41]} ${H[42]} ${H[43]} | $DS（期望 $((SIZE - 44))）|"
  echo
  printf 'riffSize_ok: %s\n' "$( [ "$RS" -eq $((SIZE - 8)) ] && echo yes || echo no )"
  printf 'dataSize_ok: %s\n' "$( [ "$DS" -eq $((SIZE - 44)) ] && echo yes || echo no )"
  echo
  echo "--- 停止后 dumpsys activity services（应为空）"
  "$ADB" shell dumpsys activity services "$PKG" | tr -d '\r'
  echo
  echo "--- 停止后 dumpsys notification 里本包记录（应为空）"
  "$ADB" shell dumpsys notification --noredact | tr -d '\r' | grep 'pkg=com.dreamkeeper' || echo "(none)"
} >"$E/p2-fgs-01-header.txt" 2>&1

"$ADB" shell cmd uimode night "$PREV_NIGHT" >/dev/null 2>&1
say "night mode restored to $PREV_NIGHT"

say "header riffSize=$RS dataSize=$DS file_bytes=$SIZE"
say "done: services/notify-dump/bytes/perms/logcat/header + notify.png"
