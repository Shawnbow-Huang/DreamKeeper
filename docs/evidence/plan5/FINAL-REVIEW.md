# Plan 5 终审（UI 打磨 · 离线美术 · 档位 UI · 发布就绪）

> 分支 `plan5-polish`（从 Plan 4 的 `2735d57` 切出）。本文件是 T8 终验记录：对照基线、逐任务结论、PENDING 白名单与风险收口。
> **结论：候选版（0.9.0 / versionCode 2）在模拟器上通过；真机项按白名单 PENDING，不冒充 PASS。**

## 一、基线 vs 终态

| 项 | Plan 4 收尾（`2735d57`） | Plan 5 终态（`5771377`） |
| --- | --- | --- |
| 版本 | 0.1.0（versionCode 1） | **0.9.0（versionCode 2）**，release 签名 |
| JVM 测试 | 49 类 / 418 例 | **52 类 / 443 例 / 0 失败** |
| 美术资产 | 无（只有矢量占位图标） | 4 张插画（72,220 B 合计）+ 图标前景 + adaptive/monochrome/splash |
| 设置页 | 只有 DeepSeek Key | Key + 模型/思考档位 + 关于卡 |
| 空态 | 纯文字 | 插画 + 标题 + 一句话（库/小说/素材；日视图空态不可达，见 D489） |
| 发布包 | 未签名 9,612,189 B | **已签名 9,695,958 B**，冷启中位 436ms |
| 偏差编号 | D275–D414 | 新增 **D461–D491** |

## 二、任务表

| 任务 | 结论 | 交付 |
| --- | --- | --- |
| T0 契约冻结 | ✅ | 计划 §0.9 D461–D470、证据 README、decisions.md（`9f3f742`） |
| T1 美术资产 | ✅ | 4 张插画 + 图标前景 + `AssetCatalogTest` 5 例（`f9d5236`） |
| T2 档位 UI | ✅ | `GenerationOptions/GenerationPrefs` + 设置页 + 请求体透传（`d4d1c68`） |
| T3 图标/启动屏 | ✅ | `mipmap-anydpi-v26/v33` + Manifest + splash 底色（`8d67693`） |
| T4 空态组件 | ✅ | `EmptyState` + 库/日/小说/素材四处（`dcfd732`） |
| T5 可靠性 A1–A4 | ✅ | 尾巴补转写、重试接线、生成闸门、4h/128MB 守卫（`b9df90b`） |
| T6 UI/A11y | ✅ | chips 触控 48dp、关于卡、FlowRow 防截断（`48490b6`） |
| T7 发布就绪 | ✅ | 0.9.0 签名包、冷启 ×3、首启空态、RELEASE/CHANGELOG（`5771377`） |
| T8 终验 | ✅ | 本文件 + ACCEPTANCE `## 3f` + 看板 9/9 |

## 三、证据索引（`docs/evidence/plan5/`，23 份）

- 资产：`p5-asset-00-master.txt`（4 张插画 sha/体积/meanY/P99/近白 + 提示词与归一化）。
- 档位：`p5-settings-00-tiers.txt`（变异原文 + 20/0 + 51/431）、`p5-settings-01-default.png/.txt`、`p5-settings-02-persist.png/.txt`。
- 图标：`p5-icon-01-icon.png/.txt`、`p5-icon-02-splash.png/.txt`、`p5-icon-03-badging.txt`。
- 可靠性：`p5-t5-reliability.txt`（A1–A4 改法 + 12 例 + 变异 + 52/443）。
- UI/A11y：`p5-ui-05-fontscale.png`、`p5-ui-06-version.png`、`p5-a11y-01-bounds.txt`（1.3× 下 17 个 clickable ≥126px）。
- 发布：`p5-release-01-build.txt`（版本/签名证书/资产/体积）、`p5-release-02-coldstart.txt`（安装/冷启/首启为空/秘密 grep）、`p5-release-03-splash.png`（screenrecord 抽帧）。
- 首启空态：`p5-empty-00-calendar.png`、`p5-empty-01-library.png`、`p5-empty-02-novel.png`、`p5-empty-03-media.png`（nearWhite 均 0）。

## 四、需求对照

| 需求 | 状态 |
| --- | --- |
| #1 模型 + 思考档位按钮 | ✅ 设置页两组 chips，透传到 faithful/novel/revise 请求体；faithful 固定关思考 |
| #6 美学增强（离线资源） | ✅ 四张插画 + 图标前景 + adaptive/monochrome（App 内不接图像模型，D461） |
| 需求 A 语音追述 | ✅（Plan 4 交付，本计划未回退；T5 补了「尾巴永不补」的坑） |
| 需求 B 小说化手动触发 | ✅（Plan 4 交付，本计划未回退；档位 UI 让默认行为可调） |

## 五、PENDING 白名单（**实现缺陷不许进此表**）

| ID | 项 | 为什么现在做不了 | 补齐条件 |
| --- | --- | --- | --- |
| P1 | 系统 STT 中文正路径 | 模拟器无中文离线包；FakeEngine JVM 已覆盖切段/写回 | 真机，录 20 条并与人工转写对比 |
| P2 | 真机安装/麦克风/通知/音量键 | 无物理设备 | 真机清单逐项跑 |
| P3 | API 26–28 | SDK 只有 `android-37.0` 镜像 | 装 API 28 镜像建 AVD |
| P4 | 导出三格式打开态 | AOSP 无 PDF/Office 查看器（仅 htmlviewer） | 真机装查看器目视 |
| P5 | 4h 长录音真机 | 模拟器不可连录 4h；JVM 守卫已覆盖边界 | 真机 + 存储余量监控 |
| P6 | D408 分流设备验证 | 无中文 IME | 真机中文输入 |
| P7 | 进程被杀后 Running 恢复 | `GenerationTracker` 仍是内存态（Plan 4 D-项），本计划只做跨页不丢 | 落库或前台服务重放 |
| P8 | 追加与生成并发（服务层） | VM 层互斥有单测；跨进程路径未压测 | 仪器化/压力脚本 |
| P9 | TalkBack 逐项朗读 | 模拟器无中文 TTS | 真机 TalkBack 走查 |
| P10 | 日视图空态 | 设计使然：空日不可点（`CalendarScreen.kt:267`），UI 无法到达（D489） | 若产品要保留该空态，加「从日视图删光后停留」测试入口 |
| P11 | 2.0× 字号 | v1 只承诺 1.3× | 需要时补布局适配与截图 |

## 六、风险结论

- **R1 签名/版本**：keystore 在仓库外、`keystore.properties` 被 gitignore；`git ls-files` 对密钥文件 0 命中；证书 SHA-256 已入证据。版本 0.9.0 与 `versionCode 2` 一致，未来 1.0.0 必须升 versionCode。
- **R2 「无真机却宣称通过」**：本文件结论只写「候选版通过」，P1–P11 全部显式挂起。
- **R3 美术拉高 nearWhite 假红**：插画按 `Threshold 250 / MaxRatio 0.005` 单独判（实际 0），UI 截图按 `TopOffset 100 / Threshold 245 / MaxRatio 0` 判，两套口径都写进 README。
- **R4 档位 UI 误改 Key/引依赖**：`GenerationPrefs` 用独立 SharedPreferences，不碰 `ApiKeyStore`、不引新依赖；JVM 5 例覆盖清洗与非法值。
- **R5 P7/P8 误标 PASS**：P7/P8 只做 JVM 确定性测试，仍挂 PENDING。

## 七、复跑

```bash
cd android
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks   # 期望 52 类 443 例
./gradlew --no-daemon :app:assembleRelease                    # 需要 keystore.properties
python ../scripts/check-near-white.py ../docs/evidence/plan5/p5-empty-01-library.png --top-offset 100
```

## 八、结论

Plan 5 的 8 个任务全部完成并通过 Lead 独立复跑与设备证据核对；发布物为 **0.9.0 候选版**。真机验收清单（P1–P11）随本文件交付，未冒充通过。
