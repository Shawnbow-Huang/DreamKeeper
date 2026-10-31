# Plan 5 证据目录

命名：`p5-<case>-<nn>-<state>`。case：asset（资产生成）、icon（图标/启动屏）、settings（档位）、ui（空态/打磨）、reli（可靠性）、release（发布）、device（真机清单）。

## txt 首三行

- host-only：`HOST_TIME=2026-10-31T…Z`、`DEVICE_TIME=n/a`、`REPLAY=<命令>`
- 设备：`APK_SHA256=<前16>`、`DEVICE_TIME=<adb shell date 原文>`、`REPLAY=<步骤>`
- 证据目录与 REPLAY 里 Key 命中必须 0；`grep -cE "sk-[A-Za-z0-9]{16,}"` 记入证据。

## 截图口径

- 每张 PNG 配同名 `.txt`：`PNG_SHA256=`、字节数、`nearWhite=<占比>` 与阈值。
- `scripts/check-near-white.ps1 -Path <png> -Threshold 245 -MaxRatio 0.01 -TopOffset 100`；状态栏白像素在 txt 里写「状态栏白图标，TopOffset 100 已排除；实测 N 像素」。
- 设备截图：`powershell -File scripts/capture-screen.ps1 -OutPath <path> -Serial emulator-5554`。

## 复跑命令

```
cd android
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks --console=plain
./gradlew --no-daemon :app:assembleRelease
```

## 插画门槛（p5-asset-00-master.txt 必记）

- 近白：RGB 三通道 ≥245 的像素占比 ≤0.1%；≥250 = 0；无 #FFFFFF。
- 亮度：相对亮度 Y 均值 ≤0.06、P99 ≤0.35。
- AI 味清单 12 项 0 fail（见 `p5-asset-01-checklist.md`）：无月亮/星星/梦网/眼睛/时钟；无文字/水印/伪文字；无人形/人手/手指；无 3D 高光/HDR/bloom/彩虹渐变；无过度对称；100% 可见纸纹/笔触；无无意义粒子；颜色 ≤6 且落在锁定色板；物体结构合理；风格一致；每张只有一个超现实元素；安静不惊悚不甜腻。
