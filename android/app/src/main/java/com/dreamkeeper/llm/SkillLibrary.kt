package com.dreamkeeper.llm

import android.content.res.AssetManager
import java.security.MessageDigest

/** 小说化 v3 的三个阶段：规划、写作、润色。 */
enum class SkillStage { PLAN, DRAFT, POLISH }

/**
 * 一个被注入提示词的英文原件。
 *
 * [text] 可能被 [SkillLibrary] 的预算裁剪过；[truncated] 为 true 时提示词侧要标注「节选」，
 * 不让模型以为看到的是全文。
 */
data class SkillFile(
    val name: String,
    val text: String,
    val bytes: Int,
    val truncated: Boolean = false,
)

/**
 * 读取内嵌的 story-skills（MIT，131 个 md）。
 *
 * 为什么只读英文原件：这些文件的写法（premise / 反前提 / scene-sequel / line-edit 清单）翻成中文会丢约束，
 * 而模型本身能读英文；翻译只保留在 schema 与系统说明里。
 *
 * 预算：每段合计不超过 [budget] 字符，超了就从最后一个文件截断并标 [SkillFile.truncated]。
 */
class SkillLibrary(
    private val assets: AssetManager,
    private val budget: Int = DEFAULT_BUDGET,
) {

    fun filesFor(stage: SkillStage): List<SkillFile> {
        val paths = when (stage) {
            SkillStage.PLAN -> listOf(
                "skills/story/skills/premise-workshop/SKILL.md",
                "skills/story/skills/theme-craft/references/controlling-idea.md",
                "skills/story/skills/plot-structure/references/short-story-form.md",
            )
            SkillStage.DRAFT -> listOf(
                "skills/story/skills/scene-craft/references/scene-sequel.md",
                "skills/story/skills/voice-style/references/prose-checks.md",
                "skills/story/skills/voice-style/references/style-sheet-guide.md",
            )
            SkillStage.POLISH -> listOf(
                "skills/story/skills/line-editing/references/line-edit-checklist.md",
                "skills/story/skills/line-editing/references/copyedit-checklist.md",
                "skills/story/skills/voice-style/references/prose-checks.md",
            )
        }
        var remaining = budget
        val out = mutableListOf<SkillFile>()
        for (path in paths) {
            if (remaining <= 0) break
            val bytes = open(path)
            val full = String(bytes, Charsets.UTF_8).trim()
            if (full.isEmpty()) throw IllegalStateException("skill 资产为空：$path")
            val take = minOf(full.length, remaining)
            remaining -= take
            out += SkillFile(
                name = path.substringAfterLast('/'),
                text = full.take(take),
                bytes = bytes.size,
                truncated = take < full.length,
            )
        }
        if (out.isEmpty()) throw IllegalStateException("skill 资产缺失：$stage")
        return out
    }

    /** 三段组合 hash（提示词三段 + MANIFEST），前 8 位；缺文件时按空串计入，保证长度稳定。 */
    fun bundleSha8(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            "prompts/novel.v3-plan.md",
            "prompts/novel.v3-draft.md",
            "prompts/novel.v3-polish.md",
        ).forEach { path ->
            digest.update(runCatching { open(path) }.getOrDefault(ByteArray(0)))
        }
        digest.update(open("skills/story/MANIFEST.json"))
        return digest.digest().joinToString("") { "%02x".format(it) }.take(8)
    }

    private fun open(path: String): ByteArray =
        runCatching { assets.open(path).use { it.readBytes() } }
            .getOrElse { throw IllegalStateException("skill 资产缺失：$path", it) }

    companion object {
        const val DEFAULT_BUDGET = 12_000
    }
}
