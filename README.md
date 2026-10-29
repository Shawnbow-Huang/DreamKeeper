# DreamKeeper · 梦境记录

趁梦还在的时候把它抓住。

醒来抬手就说，说完就有两份东西：**一份一个字都没改的原样记录，一份读得通的小说**。

- 完整的设置决策与数据模型：[设计文档](docs/superpowers/specs/2026-10-29-dreamkeeper-design.md)

---

## 仓库结构

```
DreamKeeper/
├── android/        Android 工程根（Gradle settings 在这一层）
├── prompts/        提示词与评测集：跨平台共享资产，不进任何单一平台的目录
├── docs/           设计文档、决策记录
└── README.md
```

### 为什么是这样

**根目录是中立的。** 平台目录（`android/`、将来的 `ios/`）只是消费者，不是仓库的主人。

这里的判断依据不是"为了好看"，而是**提示词和评测集是真正跨平台的东西**：

- 模型换平台不会变，同一句「禁止新增人物关系」在 iOS 上意思完全一样。
- 评测集（20 条真实梦境 + 打分表）是产品资产，不是 Android 资产。iOS 版必须用同一套标准验收，否则两个版本会各自漂移。

所以 `prompts/` 和 `docs/` 待在平台目录外面；`android/` 只是引用它们的一层壳。

### iOS 呢

**现在不建 `ios/` 目录。** 空目录 git 根本不会跟踪，建了也只是视觉安慰；里面放个 `.gitkeep` 更是假象——它看起来像"已经在做了"。

真开始做 iOS 那天，`mkdir ios` 是一秒钟的事。**现在要的不是那个文件夹，是这个位置已经被人想过了。**

---

## 平台现状

| 平台 | 状态 |
|---|---|
| Android | 第一阶段开发中（Kotlin + Jetpack Compose） |
| iOS | 未开始，需要一台 Mac |

跨端复用范围（诚实版）：数据模型、提示词、评测集、产品规则可直接复用；
**UI、录音、系统集成基本要重写**。这是选原生的代价，在选的时候就该知道，而不是两年后才发现。

---

## 构建（Android）

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17+（Android Studio 自带 jbr 即可）与 Android SDK 36。
