# 第三方组件与许可证

本应用（梦匣 / DreamKeeper）除 AndroidX、Compose、OkHttp、Room 等常规依赖外，
自 0.11.0 起随包分发以下第三方组件。它们的许可证允许再分发，但要求保留声明。

## sherpa-onnx 2.0.1

- 项目：https://github.com/k2-fsa/sherpa-onnx
- 用途：内置离线语音识别的推理运行时（JNI + onnxruntime）
- 许可证：Apache License 2.0
- 分发形式：`android/app/libs/sherpa-onnx-2.0.1.aar`（不入库，由 `scripts/fetch-asr.sh` 拉取）
- 说明：AAR 内含 `libonnxruntime.so`（MIT）与 `libsherpa-onnx-jni.so` 等原生库。

```
Copyright 2022-2025 k2-fsa / sherpa-onnx contributors
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
```

## SenseVoice Small（sherpa-onnx 导出格式）

- 模型：`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`
- 来源：https://www.modelscope.cn/models/iic/SenseVoiceSmall 与
  https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
- 用途：中文（及英/日/韩/粤）离线语音转写模型
- 许可证：随模型分发的 `LICENSE` 指向 FunASR 项目（MIT）：
  https://github.com/modelscope/FunASR?tab=readme-ov-file#license
- 分发形式：`android/app/src/main/assets/sensevoice/model.int8.onnx` +
  `tokens.txt` + `MODEL-LICENSE.txt`（模型文件不入库，脚本拉取并校验 SHA256）

## 相关文档

- 选型与验证细节：`docs/ASR-SENSEVOICE.md`
- 决策记录：`docs/superpowers/plans/2026-10-31-plan6-providers.md` 的 D563–D566

## story-skills（小说化 v3 内嵌写作方法）

- 来源：https://github.com/danjdewhurst/story-skills
- 许可证：MIT
- 版权：Copyright (c) danjdewhurst 及贡献者
- 用法：把 `skills/**/*.md` 原样内嵌到 `android/app/src/main/assets/skills/story/`，按阶段注入提示词（PLAN/DRAFT/POLISH）。
- 归档 sha256：`1943297babdcdfedc5652a3c60b713a9c9d73e5c04fd093ed4e767cf85f355d4`（ref=main，131 个 md，816,099 B）。
- 说明：内容不翻译、不裁剪语义；只在总长度超预算时从最后一个文件截断，并在提示词里标「节选」。
