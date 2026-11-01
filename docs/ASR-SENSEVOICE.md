# 内置离线语音识别（SenseVoice Small）

> 版本：0.11.0 起 · 决策 D563–D566 · 2026-11-01

## 为什么内置

在 0.10.x 里，转写走的是 **Android 13+ 系统离线识别**（`SpeechRecognizer` + `EXTRA_AUDIO_SOURCE`
读 WAV，`EXTRA_PREFER_OFFLINE=true`）。这条路的代价在真机上暴露得很直接：

- 中文离线包是**厂商/GMS 私有资产**，App 既不能打包、也不能代替用户安装；
- 没装离线包时只能提示「去下载语音包」，而不少 ROM 的「语音输入」设置里根本没有这一项；
- 识别服务不应答时，界面表现和死机一样（0.10.1 加了 90s 上限，只是把它变成「可预期的失败」）。

用户决定（2026-11-01）：**不做在线 ASR（没有预算），把 SenseVoice Small 内置进 APK**。

## 技术选择

| 方案 | 体积 | 中文质量 | 是否可打包 | 结论 |
| --- | --- | --- | --- | --- |
| Vosk small | ~44MB | 中等，无标点 | AAR 可直接依赖 | 备选 |
| Vosk big | ~1.36GB | 好 | 体积不可接受 | 否 |
| **sherpa-onnx + SenseVoice Small int8** | ~239MB + 49MB 运行时 | 好，自带标点与 ITN | 官方 AAR + 模型可下载 | **采用** |
| 在线 Whisper / 云 ASR | 0 | 最好 | 要 Key、要联网、要钱 | 用户否决 |

- 运行时：`sherpa-onnx` v2.0.1 官方 AAR（Apache-2.0），含
  `arm64-v8a / armeabi-v7a / x86_64` 三套 JNI（x86 32 位已剔除）。
- 模型：`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` 的 `model.int8.onnx`
  （239,233,841 B）+ `tokens.txt`（315,894 B），来源 FunASR / SenseVoice，
  权重许可见 `android/app/src/main/assets/sensevoice/MODEL-LICENSE.txt`。
- 运行时 AAR 没有 Maven 坐标，只能从 GitHub Release 取；模型从 hf-mirror 取。

## 代码结构

| 文件 | 职责 |
| --- | --- |
| `stt/SenseVoiceEngine.kt` | `SttEngine` 的内置实现：lazy 初始化、分段解码、日志 `DreamStt` |
| `stt/SegmentPlan.kt` | 25s 分段 + 0.8s 重叠 + 文本去重（纯 JVM 算法，可单测） |
| `stt/PcmReader.kt` | WAV → float 采样的按段读法（不整文件进内存） |
| `stt/PreferredSttEngine.kt` | 内置优先 → 系统兜底 → 都不可用时报内置原因 |
| `di/AppContainer.kt` | `sttEngine = PreferredSttEngine(SenseVoiceEngine, SystemSttEngine)` |

关键取舍：

1. **分段而不是整段**：单条录音上限 4 小时，4h × 16k × 4B ≈ 920MB，必须按段读。
   25s 段 + 0.8s 重叠，重叠部分用「最长公共前后缀」去重（`mergeSegments`），
   最多比 8 个字；宁可多留一个字，也不把真说过的话当重复删掉。
2. **模型只加载一次**：`OfflineRecognizer` 常驻 200MB 级内存，进程内单例；
   `decode` 用 Mutex 串行——同一个 onnx session 不能并发 decode。
3. **资产不压缩**：`.onnx` 走 `noCompress`，sherpa 通过 `AAssetManager` 直接读；
   压缩过的 239MB 资产每次都要解压一遍，既慢又可能拿不到连续缓冲。

## 大二进制不入库

`android/app/libs/*.aar` 与 `android/app/src/main/assets/sensevoice/model.int8.onnx` 都在
`.gitignore` 里。首次参与开发时执行：

```bash
bash scripts/fetch-asr.sh
```

脚本会下载并按 SHA256 校验：

| 文件 | SHA256 |
| --- | --- |
| `sherpa-onnx-2.0.1.aar` | `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` |
| `model.int8.onnx` | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` |
| `tokens.txt` | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` |

缺失时 Gradle 的 `checkBundledAsr` 会在 `preBuild` 直接报错并提示上面这条命令。

## 验证

| 层 | 命令 | 期望 |
| --- | --- | --- |
| 纯算法 | `./gradlew :app:testDebugUnitTest --tests "com.dreamkeeper.stt.*"` | 44 例绿（分段/去重/采样/选择器） |
| 真推理 | `./gradlew :app:connectedDebugAndroidTest` | `SenseVoiceInstrumentedTest` 1 例绿，logcat `DreamStt: sensevoice done chars=14`、`SenseVoiceProof: text=…` |
| 全量 | `./gradlew :app:testDebugUnitTest --rerun-tasks` | 66 类 / 526 例 |

模拟器实测（x86_64，官方 `test_wavs/zh.wav`）：5.6s 音频 → 1 段 → 14 字，
耗时约 1.7s（含首次模型加载）。

## 已知限制

- **安装包大**：0.11.0 的 universal APK 约 323MiB（3 个 ABI + 模型）。后续可考虑 ABI split，
  arm64 单包能省掉另外两份 onnxruntime（每份 15–25MB）。
- **首次转写慢**：模型加载与第一段解码合计 1–2s；之后常驻内存。
- **长录音的接缝**：25s 硬切 + 0.8s 重叠，极端情况下仍可能在接缝处丢半个字；
  更稳的做法是接 VAD（Silero）按静音切，留待后续。
- **仅 CPU**：没有接 NNAPI/QNN；低端机上 1 小时录音的转写耗时会明显长于实时。
- 系统识别器仍作为兜底保留：内置模型被应用商店按 ABI 拆包裁掉时还能用。
