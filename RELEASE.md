# 梦匣 · 发布说明（0.11.0 内置离线语音识别）

> 本版是 **模拟器验收的候选版（0.11.0 / versionCode 5）**，不是公开的 v1。
> 相对 0.10.1：内置 SenseVoice Small 离线转写（随包 239MB 模型 + sherpa-onnx 运行时），不再依赖系统中文语音包；universal APK 约 323MiB。
> 真机项按 [docs/QA-REAL-DEVICE.md](QA-REAL-DEVICE.md) 的清单执行；模拟器 PENDING 见 `docs/evidence/plan5/FINAL-REVIEW.md` 与 `docs/evidence/plan6/FINAL-REVIEW.md`。

## 构建

```bash
bash scripts/fetch-asr.sh      # 首次构建前：拉取 AAR 与模型（不入库）
cd android
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"   # Windows
./gradlew --no-daemon :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名依赖 `android/keystore.properties`（**不入仓**）：

```properties
storeFile=C:/Users/<you>/.dreamkeeper/dreamkeeper-release.jks
storePassword=…
keyAlias=dreamkeeper
keyPassword=…
```

文件缺失时也能构建，只是产出 `app-release-unsigned.apk`，装不上真机（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。

## 安装

```bash
adb uninstall com.dreamkeeper   # 与 debug 包签名不同，必须先卸
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

## 版本要点

- 数据：schema v3（转写水位 `dreams.transcriptMs`），首次启动自动建库；升级路径 v1→v3 与 v2→v3 都有迁移测试。
- 功能：语音记录 → 整理稿（忠实模式）/ 小说化（手动，可写想法）→ 对话改稿 / 语音补录 → 导出 TXT/DOCX/PDF。
- 设置页：DeepSeek Key、小说化模型（Flash / V4 Pro）、思考档位（关/低/高/最高）；思考开着时服务端忽略 temperature。
- 隐私：全部数据在本机；只有生成时把文本发给你配置的模型服务；音频不出本机。无账号、无云同步、无回收站。

## 已知限制（本候选版）

| 项 | 说明 |
| --- | --- |
| 真机验证 | 未做（设备不可用）；麦克风/系统 STT/长录音只做过模拟器与 JVM 级验证 |
| 系统 STT | 模拟器无中文离线包，真机 STT 正路径仍待验（P1）；转写失败时可手动补字 |
| 长录音 | 4h 硬上限、开录前 128MB 空间守卫；真机连续 4h 未录过（P9） |
| 云 STT | 不做（设计 §8 的云端兜底推迟到 Plan 6，D466） |
| 回收站 | 不做（D416，Plan 6） |
| API 26–28 | 无对应系统镜像，未验证；代码 minSdk=26，导出按钮在 <29 会提示不可用 |
| 导出 | TXT/DOCX/PDF 结构已在模拟器与脚本断言；设备上的打开态无查看器，未目视验收 |

## 验收材料

- `docs/evidence/plan5/FINAL-REVIEW.md`：T0–T8 对照、提交清单、PENDING 与风险结论。
- `docs/evidence/plan5/`：构建/冷启/空态/图标/插画/档位/可靠性证据。
- `docs/evidence/plan1/ACCEPTANCE.md`：需求验收表（`## 3f` 为 Plan 5 部分）。
