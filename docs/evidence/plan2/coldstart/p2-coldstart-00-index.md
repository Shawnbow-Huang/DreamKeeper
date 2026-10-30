apk_sha256=3979f0f61160cdc4
device_date=Fri Oct 30 19:47:08 CST 2026（batch B 起点）／19:47:26 CST 2026（batch C 起点）
rerun=powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-logcat.ps1 -Runs 3 -MaxStartMs 2000 -OutFile docs/evidence/plan2/coldstart/p2-coldstart-01-logcat.txt（把 OutFile 换成 p2-coldstart-03-logcat-b.txt 即 batch C）

# Task 9 §9.6 冷启动 ×3 + 崩溃 0 + 近白闸门

阈值写死：**`TotalTime <= 2000 ms`**，任一超限即该批不通过（脚本内置 `STARTUP GATE FAILED`）。
每批 = `adb logcat -c` → `force-stop` → `am start -W` ×3 → logcat dump；崩溃判据：
`FATAL EXCEPTION` / `ANR in com.dreamkeeper` / `signal 11` / `Force finishing activity` / `E AndroidRuntime`。

## 实测（6 次有落盘证据的冷启动 + 1 批仅 stdout）

| 批次 | 证据 | RUN1 | RUN2 | RUN3 | 中位数 | 崩溃 | 结果 |
|---|---|---|---|---|---|---|---|
| A（首批，earlier） | **报告文件被 B 批同名覆盖**，数值仅存于本次会话 stdout | 1850 | 1842 | 1809 | 1842 | 0 | OK |
| B | `p2-coldstart-02-report.txt` + `p2-coldstart-01-logcat.txt`（1,501 行） | **2080** | 1753 | 1786 | 1786 | 0 | **FAIL（1 次超限）** |
| C | `p2-coldstart-04-report-b.txt` + `p2-coldstart-03-logcat-b.txt`（1,523 行） | 1813 | 1955 | 1796 | 1813 | 0 | OK |

- 9 次里 **8 次 ≤ 2000 ms**，1 次 **2080 ms**（B 批 RUN1，超 80 ms）；min 1753 / median 1813 / max 2080
- 崩溃门：三批 `CRASHES: 0`，`FATAL EXCEPTION` 与 `ANR in com.dreamkeeper` 零命中
- **不粉饰**：按「每次 ≤ 2000 ms」的门槛，本轮**没有全过**；2080 ms 那次无 ANR、无崩溃，属首次冷启抖动 → 偏差 T9-4（D213）。
- 另一条流程偏差 T9-3（D212）：批 A 与批 B 用了同一个 `-OutFile`，B 批把 A 批的报告覆盖了，
  现在 A 批只剩 stdout 数值、没有可复跑文件；后续批次已改用 `-b` 后缀区分。

## 近白闸门（除 SystemUI 图标 bbox 外为 0）

`docs/evidence/plan2/p2-fgs-01-whitepixels.ps1 -TopOffset 100`（阈值 `min(R,G,B) > 245`，`IconX0..X1=50..210, IconY0..Y1=650..950`）：

| 截图 | nearwhite_total | in_icon_box | outside_icon_box | bbox |
|---|---|---|---|---|
| `p2-e2e-01-calendar.png` | 0 | 0 | 0 | 空 |
| `p2-e2e-02-record-idle.png` | 0 | 0 | 0 | 空 |
| `p2-e2e-04-followup.png` | 0 | 0 | 0 | 空 |
| `p2-e2e-09-detail.png` | 0 | 0 | 0 | 空 |

判据 `nearwhite_total == in_icon_box` 且 `outside == 0`：四张全 0/0/0 ⇒ **通过**（本机这几屏没有任何近白像素，
图标 bbox 内也是 0，属退化通过——如实记录，不当作「界面有白色元素被框住」的证据）。
