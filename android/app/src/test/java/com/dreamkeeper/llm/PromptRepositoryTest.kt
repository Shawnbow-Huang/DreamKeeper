package com.dreamkeeper.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * Task 6：提示词资产与渲染契约。
 *
 * 期望值全部来自计划 §0.1/§0.2/§0.4–§0.7 和 `prompts/` 里的真源，不从 [PromptRepository] 的实现反推：
 * 哈希在测试里独立算，槽名、标记名、钉死的指纹都是手写字面量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = AssetPromptSource(context)
    private val repo = PromptRepository(assets)

    // 计划 §0.1 钉死的 sha256 前 8 位；prompts/*.md 由 .gitattributes 钉成 LF（D284）。
    private val expectedSha256_8 = mapOf(
        "faithful" to "82c6e1f1",
        "novel" to "cc497720",
        "novel_revise" to "45a17e0a",
        "novel_plan" to "8a176536",
        "novel_draft" to "4e9ddb3d",
        "novel_polish" to "f937c885",
    )

    @Test
    fun `faithful v3 事实守恒与压缩口径都在提示词里`() {
        val spec = PromptSpecs.FAITHFUL
        assertEquals("v3", spec.version)
        assertEquals("prompts/faithful.v3.md", spec.assetPath)
        val text = PromptRepository(assets).render(
            spec.promptId,
            mapOf("待理顺转写" to "呃，我昨天做了一个梦，然后然后就去海边了。"),
        ).text
        assertTrue(text.contains("事实守恒"))
        assertTrue(text.contains("压缩到原文的五六成"))
        assertTrue(text.contains("【只做标点】"))
        assertTrue(text.contains("【上一版违规清单】"))
        assertTrue(text.contains("4 点 4 分"))
    }

    // 计划 §0.7：每个槽下面那行 `<…>` 提示语只用于给模型看，替换槽时必须连它一起替换。
    private val slotHintPrefixes = listOf(
        "<rawTranscript",
        "<faithfulText",
        "<mood 候选",
        "<用户写的剧情",
        "<当前这版的",
        "<用户原话",
        "<user / assistant",
        "<PLAN 阶段",
        "<DRAFT 阶段",
        "<用户想怎么写",
    )

    private val transcript = "我梦见车站。等了好久，车一直不来。"

    private val faithfulSlots: Map<String, String?> = mapOf(
        "待理顺转写" to transcript,
    )

    private val novelSlots: Map<String, String?> = mapOf(
        "原始转写" to transcript,
        "理顺稿" to "我站在站台上等末班车。",
        "标签 / 选填" to "发紧、怀旧",
        "用户想法 / 选填" to "多写点楼道的细节",
    )

    private val reviseSlots: Map<String, String?> = mapOf(
        "原始转写" to transcript,
        "当前小说稿" to """{"title":"末班车没有人","body":"站台上没有别人。"}""",
        "当前改动清单" to "[]",
        "用户这一条意见" to "太文绉绉了",
        "最近三轮对话" to "user：结尾太说教\nassistant：已把结尾收短。",
    )

    private fun sampleSlots(promptId: String): Map<String, String?> = when (promptId) {
        "faithful" -> faithfulSlots
        "novel" -> novelSlots
        "novel_revise" -> reviseSlots
        "novel_plan" -> mapOf(
            "原始转写" to transcript,
            "原样稿" to "我梦见车站。",
            "用户想法 / 选填" to null,
        )
        "novel_draft" -> mapOf(
            "规划 JSON" to "{}",
            "原样稿" to "我梦见车站。",
            "用户想法 / 选填" to null,
        )
        "novel_polish" -> mapOf(
            "规划 JSON" to "{}",
            "小说草稿 JSON" to "{\"body\":\"x\"}",
        )
        else -> error("测试数据缺 promptId=$promptId")
    }

    /** 输入块（围栏 ```` ``` ```` 对）结束的位置，字符下标，指向结束围栏之后。 */
    private fun inputBlockEnd(text: String): Int {
        val inputAt = text.indexOf("## 输入")
        require(inputAt >= 0) { "送出串里没有「## 输入」：${text.take(40)}" }
        val open = text.indexOf("```", inputAt)
        require(open >= 0) { "输入块没有起始围栏" }
        val close = text.indexOf("```", open + 3)
        require(close >= 0) { "输入块没有结束围栏" }
        return close + 3
    }

    private fun sha256_8(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(8)

    // ---------- 1 资产 ----------

    @Test
    fun `三份提示词资产的 sha256_8 与钉死值一致且无 CRLF`() {
        for (spec in PromptSpecs.ALL) {
            val bytes = assets.read(spec.assetPath)
            val text = String(bytes, Charsets.UTF_8)

            assertEquals(spec.promptId, expectedSha256_8[spec.promptId], sha256_8(bytes))
            // Spec 里钉的值也必须是这份手写期望值，不能是从字节反算出来的"自适应"值。
            assertEquals(spec.promptId, expectedSha256_8[spec.promptId], spec.sha256_8)
            assertFalse("${spec.promptId} 资产里出现 CR", text.contains("\r"))
        }
    }

    // ---------- 2 剥头 ----------

    @Test
    fun `三份送出串都从「## 输入」起且已剥掉头部元数据`() {
        for (spec in PromptSpecs.ALL) {
            val text = repo.render(spec, sampleSlots(spec.promptId)).text

            assertTrue("${spec.promptId} 开头是：${text.take(60)}", text.startsWith("## 输入"))
            assertFalse("${spec.promptId} 残留 frontmatter", text.contains("sourceOfTruth:"))
            assertFalse("${spec.promptId} 残留对齐口径", text.contains("对齐口径"))
            assertFalse("${spec.promptId} 残留 frontmatter kind", text.contains("kind:"))
        }
    }

    // ---------- 3 剥尾 ----------

    @Test
    fun `三份送出串都不含尾部「变更记录」`() {
        for (spec in PromptSpecs.ALL) {
            val text = repo.render(spec, sampleSlots(spec.promptId)).text
            assertFalse("${spec.promptId} 还带着尾部变更记录", text.contains("变更记录"))
        }
    }

    // ---------- 4 槽 ----------

    @Test
    fun `九个槽全部替换且不残留占位符与槽提示行`() {
        for (spec in PromptSpecs.ALL) {
            val values = sampleSlots(spec.promptId)
            val text = repo.render(spec, values).text

            for (slot in spec.slots) {
                assertFalse(
                    "${spec.promptId} 残留槽【${slot.name}】",
                    text.contains("【${slot.name}】"),
                )
                val value = values[slot.name]
                if (value == null) {
                    // 可选槽传 null = 整对删除，此时只要求不残留占位符（上面已断言）。
                    assertFalse("${spec.promptId} 可选槽【${slot.name}】为 null 却出现了槽名", text.contains(slot.name))
                } else {
                    assertTrue("${spec.promptId} 的槽【${slot.name}】没落进送出串", text.contains(value))
                }
            }
            for (hint in slotHintPrefixes) {
                assertFalse("${spec.promptId} 残留槽提示行 $hint", text.contains(hint))
            }
        }
    }

    // ---------- 5 缺必填 ----------

    @Test
    fun `缺失必填槽抛 IllegalArgumentException`() {
        for (spec in PromptSpecs.ALL) {
            val full = sampleSlots(spec.promptId)
            for (slot in spec.slots.filter { it.required }) {
                val missing = full.toMutableMap().apply { remove(slot.name) }
                val error = assertThrows(IllegalArgumentException::class.java) {
                    repo.render(spec, missing)
                }
                assertTrue(
                    "${spec.promptId}/${slot.name} 的报错太笼统：${error.message}",
                    error.message.orEmpty().contains("【${slot.name}】"),
                )
            }
        }
    }

    // ---------- 6 可选槽 ----------

    @Test
    fun `可选槽为 null 不抛且不残留占位符`() {
        for (spec in PromptSpecs.ALL.filter { s -> s.slots.any { !it.required } }) {
            val slots = sampleSlots(spec.promptId).toMutableMap()
            for (slot in spec.slots.filter { !it.required }) slots[slot.name] = null

            val text = repo.render(spec, slots).text
            for (slot in spec.slots.filter { !it.required }) {
                assertFalse(
                    "${spec.promptId} 可选槽为 null 仍残留【${slot.name}】",
                    text.contains("【${slot.name}】"),
                )
            }
            assertTrue("${spec.promptId} 渲染结果为空", text.length > 100)
        }

        // §0.4 的唯一例外：只有【待理顺转写】允许空串（输入为空时正确输出就是空）。
        val empty = repo.render(PromptSpecs.FAITHFUL, mapOf("待理顺转写" to "")).text
        assertFalse("空转写渲染后仍残留【待理顺转写】", empty.contains("【待理顺转写】"))
        assertTrue(empty.startsWith("## 输入"))
    }

    // ---------- 6b 必填槽的空值（D308，Lead 验收补测） ----------

    @Test
    fun `必填槽传空串或纯空白也抛 IllegalArgumentException`() {
        for (spec in PromptSpecs.ALL) {
            val full = sampleSlots(spec.promptId)
            for (slot in spec.slots.filter { it.required && !it.allowEmpty }) {
                for (blank in listOf("", "   ", "\u3000")) {
                    val bad = full.toMutableMap().apply { put(slot.name, blank) }
                    val error = assertThrows(IllegalArgumentException::class.java) {
                        repo.render(spec, bad)
                    }
                    assertTrue(
                        "${spec.promptId}/${slot.name} 传「$blank」应抛错，实际：${error.message}",
                        error.message.orEmpty().contains("【${slot.name}】"),
                    )
                }
            }
        }
        // 例外：【待理顺转写】allowEmpty=true，空串与空白都放行（§0.4），渲染后不得残留槽位。
        for (blank in listOf("", "   ")) {
            val text = repo.render(PromptSpecs.FAITHFUL, mapOf("待理顺转写" to blank)).text
            assertFalse("空转写仍残留槽位：$blank", text.contains("【待理顺转写】"))
            assertTrue(text.startsWith("## 输入"))
        }
    }

    // ---------- 7 只做标点开关 ----------

    @Test
    fun `只做标点开关生效并落在输入块之后`() {
        val spec = PromptSpecs.FAITHFUL
        val slots = sampleSlots("faithful")
        val off = repo.render(spec, slots).text
        val on = repo.render(spec, slots, markers = setOf(PromptMarker.PUNCTUATION_ONLY)).text

        // 关着的时候模板正文里本来就有「带 `【只做标点】` 标记时…」——字在不在不能当开关（§0.3）。
        assertTrue(off.contains("【只做标点】"))
        assertFalse("开关关着却追加了开关指令", off.contains("本轮按保守档输出"))

        val directiveAt = on.indexOf("本轮按保守档输出")
        assertTrue("开关开着但没追加开关指令", directiveAt > 0)
        assertTrue(
            "开关指令没写清只动标点：${on.substring(directiveAt, minOf(directiveAt + 40, on.length))}",
            on.substring(directiveAt, minOf(directiveAt + 80, on.length)).contains("标点"),
        )

        val cut = inputBlockEnd(off)
        assertTrue("开关指令落在输入块里（应追加在输入块之后）", directiveAt > cut)
        assertEquals("追加不得改动输入块及其之前的任何字", off.substring(0, cut), on.substring(0, cut))
        assertTrue("追加不得改动输入块之后的正文", on.endsWith(off.substring(cut)))
        assertTrue("开关块应先出现【只做标点】再出现指令", on.substring(cut, directiveAt).contains("【只做标点】"))
    }

    // ---------- 8 上一版违规清单 ----------

    @Test
    fun `上一版违规清单带载荷追加在输入块之后`() {
        val spec = PromptSpecs.FAITHFUL
        val slots = sampleSlots("faithful")
        val payload = "第 2 段：把「站台」改成了「月台」，不能改专名。\n第 5 段：删掉了原句的「一直」，不能删。"
        val text = repo.render(
            spec,
            slots,
            markers = setOf(PromptMarker.PREVIOUS_VIOLATIONS),
            payloads = mapOf(PromptMarker.PREVIOUS_VIOLATIONS to payload),
        ).text

        // 关着的时候载荷一个字都不该进送出串。
        assertFalse("开关关着却带上了违规清单载荷", repo.render(spec, slots).text.contains("把「站台」改成了「月台」"))

        val cut = inputBlockEnd(text)
        val payloadAt = text.indexOf(payload)
        assertTrue("违规清单载荷没进送出串", payloadAt > 0)
        assertTrue("违规清单必须在输入块之后", payloadAt > cut)
        assertEquals(
            "违规清单标记与载荷之间不该夹别的东西",
            "【上一版违规清单】",
            text.substring(cut, payloadAt).trim(),
        )
    }

    // ---------- 9 头部注释 ----------

    @Test
    fun `三个渲染结果都不含 HTML 注释与 promptId`() {
        for (spec in PromptSpecs.ALL) {
            val text = repo.render(spec, sampleSlots(spec.promptId)).text
            assertFalse("${spec.promptId} 残留 HTML 注释头", text.contains("<!--"))
            assertFalse("${spec.promptId} 残留 HTML 注释尾", text.contains("-->"))
            assertFalse("${spec.promptId} 残留 promptId 字段", text.contains("promptId:"))
        }
    }

    /**
     * Plan 4 T5：novel.v2.1 在 v2 三槽之后加第 4 个**可选**槽【用户想法 / 选填】。
     *
     * 为什么必须 allowEmpty=true 且 null 要整对删除：对话框允许「不写想法直接生成」，
     * 此时槽整对消失，模型看到的行为必须与 v2 完全一致；留一个空 `【用户想法 / 选填】`
     * 反而会让模型以为「用户说了空话」。
     */
    @Test
    fun novelV2_1HasFourSlotsAndIdeaSlotIsOptionalButAllowsEmpty() {
        val spec = PromptSpecs.byId("novel")
        assertEquals("v2.1", spec.version)
        assertEquals(
            listOf("原始转写", "理顺稿", "标签 / 选填", "用户想法 / 选填"),
            spec.slots.map { it.name },
        )
        val idea = spec.slots.last()
        assertFalse("想法槽不能是必填", idea.required)
        assertTrue("想法槽必须允许显式空串", idea.allowEmpty)

        val withIdea = repo.render(spec, novelSlots).text
        assertTrue("写了想法就要送进模型", withIdea.contains("多写点楼道的细节"))
        assertFalse("槽提示行必须随槽一起被替换", withIdea.contains("<用户写的剧情"))

        val without = novelSlots.toMutableMap().apply { put("用户想法 / 选填", null) }
        val noIdea = repo.render(spec, without).text
        assertFalse("空想法要把整对槽删掉", noIdea.contains("【用户想法 / 选填】"))
        assertFalse("空想法不许留提示行", noIdea.contains("<用户写的剧情"))
    }
}
