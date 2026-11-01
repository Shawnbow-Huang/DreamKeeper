package com.dreamkeeper.llm

import android.content.Context
import java.security.MessageDigest

/** 渲染好的小说化提示词（正文已含 skill 原件）。 */
data class NovelPrompt(val text: String)

/**
 * 小说化 v3 的三段提示词组装。
 *
 * - 模板走 [PromptRepository]：保留槽校验、指纹校验、剥头剥尾；
 * - skill 英文原件按阶段追加在正文之后（不翻译），顺序固定，保证 [bundleSha8] 可复现。
 */
class NovelPrompts(private val context: Context) {

    private val repo = PromptRepository(AssetPromptSource(context))
    private val skills = SkillLibrary(context.assets)

    fun renderPlan(raw: String, faithful: String, idea: String?): NovelPrompt =
        appendSkills(
            repo.render(
                PromptSpecs.NOVEL_PLAN,
                mapOf(
                    "原始转写" to raw,
                    "原样稿" to faithful,
                    "用户想法 / 选填" to idea,
                ),
            ).text,
            SkillStage.PLAN,
        )

    fun renderDraft(planJson: String, faithful: String, idea: String?): NovelPrompt =
        appendSkills(
            repo.render(
                PromptSpecs.NOVEL_DRAFT,
                mapOf(
                    "规划 JSON" to planJson,
                    "原样稿" to faithful,
                    "用户想法 / 选填" to idea,
                ),
            ).text,
            SkillStage.DRAFT,
        )

    fun renderPolish(planJson: String, draftJson: String): NovelPrompt =
        appendSkills(
            repo.render(
                PromptSpecs.NOVEL_POLISH,
                mapOf(
                    "规划 JSON" to planJson,
                    "小说草稿 JSON" to draftJson,
                ),
            ).text,
            SkillStage.POLISH,
        )

    /** 三段模板 + MANIFEST 的组合指纹，前 8 位。 */
    fun bundleSha8(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            PromptSpecs.NOVEL_PLAN,
            PromptSpecs.NOVEL_DRAFT,
            PromptSpecs.NOVEL_POLISH,
        ).forEach { spec ->
            digest.update(context.assets.open(spec.assetPath).use { it.readBytes() })
        }
        digest.update(context.assets.open("skills/story/MANIFEST.json").use { it.readBytes() })
        return digest.digest().joinToString("") { "%02x".format(it) }.take(8)
    }

    private fun appendSkills(base: String, stage: SkillStage): NovelPrompt {
        val files = skills.filesFor(stage)
        val block = buildString {
            append("\n\n## 参考资料（英文原件，逐字注入，不翻译）\n")
            files.forEach { file ->
                append("\n### ").append(file.name)
                if (file.truncated) append("（节选）")
                append("\n\n").append(file.text).append("\n")
            }
        }
        return NovelPrompt(base.trimEnd() + block)
    }
}
