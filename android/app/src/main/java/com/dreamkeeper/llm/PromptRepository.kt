package com.dreamkeeper.llm

import android.content.Context
import android.util.Log
import java.security.MessageDigest

private const val TAG = "PromptRepository"
private const val INPUT_ANCHOR = "## 输入"
private const val TRAILING_ANCHOR = "变更记录"

/** 保守档开关的指令正文。模板正文只说了"什么是保守档"，真正开启靠这一句（§0.3）。 */
private const val PUNCTUATION_ONLY_DIRECTIVE =
    "本轮按保守档输出：只插入标点、空白、换行；一个字不删、不改、不移。"

/**
 * 提示词资产来源。接口化的理由与 [PriceCardSource] 相同（D256）：
 * 纯 JVM 单测读不到 `assets/`，把"字节从哪来"抽出去，渲染逻辑才好测。
 */
interface PromptAssetSource {
    fun read(assetPath: String): ByteArray
}

/**
 * 从 APK 资产里读提示词。
 *
 * 用 `assets.open` 而不是 `openFd`（计划 §5）：APK 里的 assets 可能被压缩，
 * `openFd` 要求条目未压缩，release 包上会直接炸。
 */
class AssetPromptSource(private val context: Context) : PromptAssetSource {
    override fun read(assetPath: String): ByteArray =
        context.assets.open(assetPath).use { stream -> stream.readBytes() }
}

/**
 * 渲染结果。`promptId` / `promptVersion` / `promptSha256` 就是 Task 8 审计要写的三列
 * （计划 §3 数据模型），这里先把值算出来，落库仍由调用方负责（§0.8：本任务不写库）。
 */
data class RenderedPrompt(
    val promptId: String,
    val promptVersion: String,
    val promptSha256: String,
    val text: String,
)

/**
 * 把提示词模板 + 槽值 + 条件标记渲染成"能直接送模型"的一段文本。
 *
 * 送出串的形状（§0.5/§0.6/§0.7）：
 *   1. 从 `## 输入` 那行起：frontmatter、`# 标题`、`> 对齐口径` 都不送；
 *   2. 到 `## …变更记录` 之前止：那份变更记录是给人看的，送模型只会污染指令；
 *   3. 槽 `【名字】` 连同它下面那行 `<…>` 提示语一起被替换（提示语是给人解释槽的，不该留在送出串里）；
 *   4. 条件标记块追加在输入块之后。
 *
 * 契约：required 的槽必须在 `slots` 里**作为 key 出现**（值可为空串的只有 allowEmpty 的那个）；
 * optional 的槽可以缺席或传 `null`，两者都表示"没有这个输入"。
 */
class PromptRepository(
    private val source: PromptAssetSource,
    private val warn: (String) -> Unit = { message -> Log.w(TAG, message) },
) {

    fun render(
        promptId: String,
        slots: Map<String, String?>,
        markers: Set<PromptMarker> = emptySet(),
        payloads: Map<PromptMarker, String> = emptyMap(),
    ): RenderedPrompt = render(PromptSpecs.byId(promptId), slots, markers, payloads)

    fun render(
        spec: PromptSpec,
        slots: Map<String, String?>,
        markers: Set<PromptMarker> = emptySet(),
        payloads: Map<PromptMarker, String> = emptyMap(),
    ): RenderedPrompt {
        requireSupported(spec, markers)
        validateSlots(spec, slots)
        val (body, sha256) = loadStripped(spec)
        val withSlots = replaceSlots(body, spec, slots)
        val text = appendMarkers(withSlots, spec, markers, payloads)
        return RenderedPrompt(spec.promptId, spec.version, sha256, text)
    }

    /**
     * 读资产 → 验指纹 → 剥头剥尾。
     *
     * 指纹不符时先 [warn] 再抛：§0.1 要求 fail fast（宁可这一轮不出文本，也不能拿未知版本去生成/记账），
     * D285 要求"漂移只记 WARN、不写库不迁移"——两条并不冲突，前者管这一轮能不能送，后者管持久化。
     */
    private fun loadStripped(spec: PromptSpec): Pair<String, String> {
        val bytes = source.read(spec.assetPath)
        val sha256 = sha256Hex(bytes)
        val actual8 = sha256.take(8)
        if (actual8 != spec.sha256_8) {
            warn(
                "提示词资产漂移：${spec.assetPath} 期望 ${spec.sha256_8}，实际 $actual8；" +
                    "按 D285 只记 WARN、不写库，但拒绝把未知版本送模型",
            )
            throw IllegalStateException(
                "提示词资产漂移：${spec.assetPath} 期望 ${spec.sha256_8}，实际 $actual8",
            )
        }

        val lines = String(bytes, Charsets.UTF_8).lines()
        val head = lines.indexOfFirst { it.startsWith(INPUT_ANCHOR) }
        check(head >= 0) { "提示词 ${spec.assetPath} 没有「$INPUT_ANCHOR」锚点，无法剥头" }
        var tail = lines.size
        for (index in lines.indices.reversed()) {
            val line = lines[index]
            if (line.startsWith("## ") && line.contains(TRAILING_ANCHOR)) {
                tail = index
                break
            }
        }
        check(tail > head) {
            "提示词 ${spec.assetPath} 的剥尾锚点（第 ${tail + 1} 行）在剥头锚点（第 ${head + 1} 行）之前"
        }
        return lines.subList(head, tail).joinToString("\n").trimEnd() to sha256
    }

    private fun requireSupported(spec: PromptSpec, markers: Set<PromptMarker>) {
        val unsupported = markers.firstOrNull { it !in spec.markers }
        require(unsupported == null) {
            "提示词 ${spec.promptId} 未声明条件标记 ${unsupported?.token}；" +
                "它只有 ${spec.markers.joinToString { it.token }.ifEmpty { "（无条件标记）" }}"
        }
    }

    private fun validateSlots(spec: PromptSpec, slots: Map<String, String?>) {
        val declared = spec.slots.map { it.name }.toSet()
        val unknown = slots.keys.firstOrNull { it !in declared }
        require(unknown == null) {
            "提示词 ${spec.promptId} 没有槽【$unknown】；槽名写死在 PromptSpecs（计划 §0.4）"
        }
        for (slot in spec.slots) {
            val value = slots[slot.name]
            when {
                value == null && slot.required ->
                    throw IllegalArgumentException("提示词 ${spec.promptId} 缺少必填槽【${slot.name}】")
                // 可选槽缺席/为 null 都合法：模板里那两行直接不给。
                value == null -> Unit
                slot.required && !slot.allowEmpty && value.isBlank() ->
                    throw IllegalArgumentException("提示词 ${spec.promptId} 的必填槽【${slot.name}】不允许为空")
            }
        }
    }

    private fun replaceSlots(body: String, spec: PromptSpec, slots: Map<String, String?>): String {
        var lines = body.lines()
        for (slot in spec.slots) {
            lines = replaceSlot(lines, "【${slot.name}】", slots[slot.name].orEmpty())
        }
        return lines.joinToString("\n")
    }

    /**
     * 替换一个槽：`【名字】` 那行 + 紧随其后的 `<…>` 提示行整体换成槽值。
     *
     * 值按行展开（多行清单不能被压成一行）；值为空时那两行一起消失——
     * 留着空槽比删掉更糟：模型会以为"这里本来该有内容"。
     */
    private fun replaceSlot(lines: List<String>, token: String, value: String): List<String> {
        val valueLines = if (value.isEmpty()) emptyList() else value.trim().lines()
        val out = ArrayList<String>(lines.size + valueLines.size)
        var index = 0
        while (index < lines.size) {
            if (!lines[index].trimStart().startsWith(token)) {
                out.add(lines[index])
                index++
                continue
            }
            val hint = lines.getOrNull(index + 1)?.trim()
            val hasHintLine = hint != null && hint.startsWith("<") && hint.endsWith(">")
            out.addAll(valueLines)
            index += if (hasHintLine) 2 else 1
        }
        return out
    }

    private fun appendMarkers(
        body: String,
        spec: PromptSpec,
        markers: Set<PromptMarker>,
        payloads: Map<PromptMarker, String>,
    ): String {
        if (markers.isEmpty()) return body
        val block = ArrayList<String>()
        // 按 spec 里声明的顺序输出，保证同一组标记的送出串逐字稳定（可对比、可缓存）。
        for (marker in spec.markers) {
            if (marker !in markers) continue
            block.add(marker.token)
            if (marker.carriesPayload) {
                val payload = payloads[marker].orEmpty().trim()
                require(payload.isNotEmpty()) {
                    "条件标记 ${marker.token} 必须带载荷（${spec.promptId}）：它不是一个纯开关"
                }
                block.addAll(payload.lines())
            } else {
                block.add(directiveFor(marker))
            }
        }
        val insertion = markerInsertionIndex(body)
        return body.substring(0, insertion) +
            "\n\n" + block.joinToString("\n") +
            body.substring(insertion)
    }

    /** 输入块结束围栏那行**之后**的位置：标记块插在这里，输入块本身一字不动。 */
    private fun markerInsertionIndex(body: String): Int {
        val inputAt = body.indexOf(INPUT_ANCHOR)
        if (inputAt < 0) return body.length
        val open = body.indexOf("```", inputAt)
        if (open < 0) return body.length
        val close = body.indexOf("```", open + 3)
        if (close < 0) return body.length
        val lineEnd = body.indexOf('\n', close)
        return if (lineEnd < 0) body.length else lineEnd
    }

    private fun directiveFor(marker: PromptMarker): String = when (marker) {
        PromptMarker.PUNCTUATION_ONLY -> PUNCTUATION_ONLY_DIRECTIVE
        else -> "本轮启用标记 ${marker.token}。"
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
