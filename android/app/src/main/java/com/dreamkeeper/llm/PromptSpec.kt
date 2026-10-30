package com.dreamkeeper.llm

/**
 * 一个提示词槽位。
 *
 * 槽语法是 `【名字】`（计划 §0.2），不是 `{{name}}`：模板正文里槽的下一行还有一行
 * `<…>` 提示语，渲染时**整对替换**（§0.7），所以槽绝不能只当成一次字符串 replace。
 *
 * @param required 缺这个槽就不该送模型；`null`（或 map 里没有这个 key）直接抛。
 * @param allowEmpty 允许显式传空串。全库只有【待理顺转写】是 true：用户给的转写为空时，
 *   正确输出本来就是空，这是计划 §0.4 明说的例外，不是漏校验。
 */
data class SlotSpec(
    val name: String,
    val required: Boolean,
    val allowEmpty: Boolean = false,
)

/**
 * 非槽位的条件标记（§0.3：全库正文里只有 4 处 `【…】`，其中 3 处是标记）。
 *
 * 关键事实：它们是模板正文里**本来就有的字**——faithful.v2.md 正文写着
 * 「带 `【只做标点】` 标记时…」「带 `【上一版违规清单】` 标记时…」。
 * 因此"字在不在送出串里"不能当开关用（永远在），开关只能由调用方显式 enable，
 * 渲染器把标记块追加到输入块之后。
 *
 * @param carriesPayload 是否必须由调用方带载荷。【上一版违规清单】要带渲染好的清单文本，
 *   不能退化成一个只说"注意上一版违规"的纯开关。
 */
enum class PromptMarker(
    val token: String,
    val carriesPayload: Boolean,
) {
    PUNCTUATION_ONLY("【只做标点】", carriesPayload = false),
    PREVIOUS_VIOLATIONS("【上一版违规清单】", carriesPayload = true),
}

/**
 * 一份提示词的元数据 + 契约。
 *
 * @param assetPath APK 内路径（构建期由 `syncPromptAssets` 从仓库根 `prompts/` 拷进 `assets/prompts/`）。
 * @param sha256_8 钉死的资产指纹前 8 位（§0.1）。改提示词就必须同时改这里，否则 fail fast，
 *   避免"改了提示词但评测/成本还按旧版本记账"。
 * @param slots 这份提示词要替换的槽；顺序即模板里的出现顺序。
 * @param markers 这份提示词支持的条件标记；传了未声明的标记要抛，而不是静默忽略。
 */
data class PromptSpec(
    val promptId: String,
    val version: String,
    val assetPath: String,
    val sha256_8: String,
    val slots: List<SlotSpec>,
    val markers: List<PromptMarker>,
)

/**
 * 三份提示词的登记表（Plan 4 T5 起 novel=v2.1、novel_revise=v1.1）。
 *
 * 版本号只用于日志/评测标注，**不写库**：计划 §0.8 / D267 / D285 明确不建 prompt_versions 表，
 * 提示词漂移只记 WARN，不迁移数据库。
 */
object PromptSpecs {

    val FAITHFUL = PromptSpec(
        promptId = "faithful",
        version = "v2",
        assetPath = "prompts/faithful.v2.md",
        sha256_8 = "5b9702ee",
        slots = listOf(
            SlotSpec("待理顺转写", required = true, allowEmpty = true),
        ),
        markers = listOf(PromptMarker.PUNCTUATION_ONLY, PromptMarker.PREVIOUS_VIOLATIONS),
    )

    val NOVEL = PromptSpec(
        promptId = "novel",
        version = "v2.1",
        assetPath = "prompts/novel.v2.1.md",
        sha256_8 = "cc497720",
        slots = listOf(
            SlotSpec("原始转写", required = true),
            SlotSpec("理顺稿", required = true),
            SlotSpec("标签 / 选填", required = false),
            // Plan 4 T5：用户想法是可选槽，空想法时整对删除（不是空串）。
            SlotSpec("用户想法 / 选填", required = false, allowEmpty = true),
        ),
        markers = emptyList(),
    )

    val NOVEL_REVISE = PromptSpec(
        promptId = "novel_revise",
        version = "v1.1",
        assetPath = "prompts/novel_revise.v1.1.md",
        sha256_8 = "45a17e0a",
        slots = listOf(
            SlotSpec("原始转写", required = true),
            SlotSpec("当前小说稿", required = true),
            SlotSpec("当前改动清单", required = true),
            SlotSpec("用户这一条意见", required = true),
            SlotSpec("最近三轮对话", required = false),
        ),
        markers = emptyList(),
    )

    val ALL = listOf(FAITHFUL, NOVEL, NOVEL_REVISE)

    fun byId(promptId: String): PromptSpec =
        ALL.firstOrNull { it.promptId == promptId }
            ?: throw IllegalArgumentException(
                "未知提示词 id=$promptId；已登记：${ALL.joinToString { it.promptId }}",
            )
}
