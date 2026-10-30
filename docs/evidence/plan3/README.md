# Plan 3 证据约定（生成、成本、导出、对话改稿）

本目录的每条证据都要能被人**独立复算**，不靠肉眼、不靠「子 agent 说通过了」。

## 1. 命名

```
p3-<case>-<nn>-<state>.{png|txt|json|log|docx|pdf}
```

`case` 取值：`settings`（接线与设置页）· `key`（Key 存取）· `cost`（价卡与记账）· `migrate`（v2 迁移）·
`llm`（客户端与假服务器）· `prompt`（提示词资产与哈希）· `gates`（五闸门）· `gen`（生成与失败链）·
`revise`（对话改稿）· `export`（导出）· `perm`（权限）· `e2e`（端到端）。

## 2. 每份证据的头部（txt / json / log 的前三行）

```
APK_SHA256=<前 16 位或全量>
DEVICE_TIME=<adb shell date 原文>
REPLAY=<一行可直接复跑的命令>
```

png 不写文字，改为在同名 `.txt` 里记 `PNG_SHA256=` 与上面三行，互相指路。

**豁免（D378）**：`docs/evidence/plan3/` 下的**导出原件**（`<yyyyMMdd>-<标题>-v<n>.txt|docx|pdf`）是交付物，
不适用 §1 命名与 §2 头三行；其 sha256 记在 `p3-e2e-06-export.txt` 的 `EXPORT_SHA256=` 行（逐文件一行），
并注明「按入库 blob 的 LF 字节计」。导出原件的复跑命令也在该 txt 里。

## 3. 硬规矩

- **不许**用 PowerShell `>` 抓二进制（会把内容 UTF-16LE 化）：一律
  `cmd /c "`"$adb`" exec-out run-as com.dreamkeeper cat <路径> > <本地文件>"`。
- 截图统一 `scripts/capture-screen.ps1 -OutPath ...`，判深色用
  `scripts/check-near-white.ps1 -Path ... -TopOffset 100 -MaxRatio 0`（`-TopOffset 0` 会把状态栏白字算进来，永远不可能 0）。
- 设备是 **emulator-5554（1080x2400 @420dpi）**，48dp = **126px**；写「真机」时如实写「设备（emulator-5554）」。
- **极性要对**：Plan 1 里 `dumpsys … INTERNET` 是「期望为空」的负向证据；Plan 3 里它是「期望命中」。
  且 `dumpsys` 只能说明「装上的包有」，要证「这份 sha 的包有」必须 `aapt2 dump permissions <apk>`。
- **负向结论必须真的构建过**：写「release 不含明文放行」之前，先 `assembleRelease` 成功并留下 APK sha256。
- **没有 Key 就不许写「真实调用成功」**：一切生成证据标 `source=fixture`，并写清假服务器的场景名。
- 成本、导出、闸门三类证据一律附**复算命令**（`scripts/check-cost.py` / `scripts/check-export.py` / `scripts/check-faithful.py --json`）。

## 4. 一条证据长得对不对（自查清单）

1. 前三行绑定 APK 与设备时间了吗？
2. 结论是从**命令输出**读出来的，还是从某人的总结里抄的？
3. 如果有人明天重跑这条命令，会不会得到同样的判定？
4. 我的「通过」是不是其实依赖某个没跑起来的步骤（比如没构建就写 release 干净）？
