package com.dreamkeeper.llm.gates

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 用冻结向量逐例校验 Kotlin 闸门 G1–G6 与 Python 参考实现同集合。
 *
 * 刻意不用 Robolectric / org.json：本测试是纯 JVM 逻辑校验，JSON 解析由文件内的小解析器
 * 完成，避免 android.jar 的 stub JSONObject 在 plain JUnit4 下抛 "not mocked"。
 * 期望值全部来自 docs/evidence/plan6/p6-faithful-gate-vectors.json（冻结向量）。
 */
class FaithfulGatesTest {

    @Test
    fun case01() {
        assertEquals("向量条数", 17, vectors().size)
        assertEquals("PUNCT 长度", 34, FaithfulGates.PUNCT.length)
        assertCase(1)
    }

    @Test
    fun case02() = assertCase(2)

    @Test
    fun case03() = assertCase(3)

    @Test
    fun case04() = assertCase(4)

    @Test
    fun case05() = assertCase(5)

    @Test
    fun case06() = assertCase(6)

    @Test
    fun case07() = assertCase(7)

    @Test
    fun case08() = assertCase(8)

    @Test
    fun case09() = assertCase(9)

    @Test
    fun case10() = assertCase(10)

    @Test
    fun case11() = assertCase(11)

    @Test
    fun case12() = assertCase(12)

    @Test
    fun case13() = assertCase(13)

    @Test
    fun case14() = assertCase(14)

    @Test
    fun case15() = assertCase(15)

    @Test
    fun case16() = assertCase(16)

    @Test
    fun case17() = assertCase(17)

    private fun assertCase(id: Int) {
        val c = vectors().first { it.id == id }
        val report = FaithfulGates.check(c.input, c.output, c.mode)
        assertEquals("case $id ${c.name} mode", c.mode, report.mode)
        // G6 只适用于 faithful；revise 是整体改写，不该被改字审计误伤。
        val expectedGates = if (c.mode == GateMode.FAITHFUL) Gate.values().toList() else Gate.values().filter { it != Gate.G6 }
        assertEquals("case $id ${c.name} 闸门数", expectedGates.size, report.results.size)
        assertEquals(
            "case $id ${c.name} 闸门顺序",
            expectedGates,
            report.results.map { it.gate },
        )
        assertEquals("case $id ${c.name} failed 集合", c.expectFailed, report.failed)
        assertEquals(
            "case $id ${c.name} failed 顺序",
            Gate.values().filter { it in c.expectFailed },
            report.failed.toList(),
        )
        assertEquals("case $id ${c.name} passed", c.expectPass, report.passed)

        val faithfulFailed = c.faithfulFailed ?: return
        val faithful = FaithfulGates.check(c.input, c.output, GateMode.FAITHFUL)
        assertEquals("case $id ${c.name} faithful failed", faithfulFailed, faithful.failed)
        assertEquals("case $id ${c.name} faithful passed", faithfulFailed.isEmpty(), faithful.passed)
    }

    // ---- 冻结向量读取（plain JUnit4，不依赖 android/org.json） ----

    private data class VectorCase(
        val id: Int,
        val name: String,
        val input: String,
        val output: String,
        val mode: GateMode,
        val expectPass: Boolean,
        val expectFailed: Set<Gate>,
        val faithfulFailed: Set<Gate>?,
    )

    private fun vectorsFile(): File = File(repoRoot(), "docs/evidence/plan6/p6-faithful-gate-vectors.json")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "docs/evidence/plan3").isDirectory &&
                File(dir, "android/app/build.gradle.kts").isFile
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        throw AssertionError("从 ${File("").absolutePath} 向上找不到仓库根目录")
    }

    private fun vectors(): List<VectorCase> = VECTORS

    private fun loadVectors(): List<VectorCase> {
        val file = vectorsFile()
        check(file.isFile) { "缺少冻结向量：${file.absolutePath}" }
        val root = MiniJson.parse(file.readText()) as? Map<*, *>
            ?: error("向量 JSON 根节点不是对象")
        val rawCases = root["cases"] as? List<*> ?: error("向量 JSON 缺少 cases")
        return rawCases.mapIndexed { index, raw ->
            val map = raw as? Map<*, *> ?: error("case[$index] 不是对象")
            VectorCase(
                id = (map["id"] as Number).toInt(),
                name = map["name"] as String,
                input = map["input"] as String,
                output = map["output"] as String,
                mode = GateMode.valueOf((map["mode"] as String).uppercase()),
                expectPass = map["expect_pass"] as Boolean,
                expectFailed = gateSet(map["expect_failed"], "case[$index].expect_failed"),
                faithfulFailed = (map["faithful_failed"] as? List<*>)?.let {
                    gateSet(it, "case[$index].faithful_failed")
                },
            )
        }
    }

    private fun gateSet(raw: Any?, what: String): Set<Gate> {
        val list = raw as? List<*> ?: error("$what 不是数组")
        return list.mapTo(LinkedHashSet()) { Gate.valueOf(it as String) }
    }

    private object MiniJson {
        fun parse(text: String): Any? {
            val normalized = if (text.startsWith('\uFEFF')) text.substring(1) else text
            val parser = Parser(normalized)
            val value = parser.readValue()
            parser.skipWhitespace()
            require(parser.pos == normalized.length) {
                "JSON 尾部有 ${normalized.length - parser.pos} 个多余字符"
            }
            return value
        }

        private class Parser(private val s: String) {
            var pos: Int = 0

            fun skipWhitespace() {
                while (pos < s.length && s[pos].isWhitespace()) pos++
            }

            fun readValue(): Any? {
                skipWhitespace()
                require(pos < s.length) { "JSON 意外结束 @ $pos" }
                return when (s[pos]) {
                    '{' -> readObject()
                    '[' -> readArray()
                    '"' -> readString()
                    't' -> readLiteral("true", true)
                    'f' -> readLiteral("false", false)
                    'n' -> readLiteral("null", null)
                    else -> {
                        require(s[pos] == '-' || s[pos].isDigit()) {
                            "JSON 非法字符 '${s[pos]}' @ $pos"
                        }
                        readNumber()
                    }
                }
            }

            private fun readObject(): Map<String, Any?> {
                expect('{')
                val result = LinkedHashMap<String, Any?>()
                skipWhitespace()
                if (peek() == '}') {
                    pos++
                    return result
                }
                while (true) {
                    skipWhitespace()
                    val key = readString()
                    skipWhitespace()
                    expect(':')
                    result[key] = readValue()
                    skipWhitespace()
                    when (peek()) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return result
                        }
                        else -> error("JSON 对象缺少 ',' 或 '}' @ $pos")
                    }
                }
            }

            private fun readArray(): List<Any?> {
                expect('[')
                val result = ArrayList<Any?>()
                skipWhitespace()
                if (peek() == ']') {
                    pos++
                    return result
                }
                while (true) {
                    result.add(readValue())
                    skipWhitespace()
                    when (peek()) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return result
                        }
                        else -> error("JSON 数组缺少 ',' 或 ']' @ $pos")
                    }
                }
            }

            private fun readString(): String {
                skipWhitespace()
                require(peek() == '"') { "JSON 期望字符串 @ $pos" }
                pos++
                val sb = StringBuilder()
                while (pos < s.length) {
                    val c = s[pos++]
                    when {
                        c == '"' -> return sb.toString()
                        c == '\\' -> {
                            require(pos < s.length) { "JSON 转义意外结束 @ $pos" }
                            when (val e = s[pos++]) {
                                '"' -> sb.append('"')
                                '\\' -> sb.append('\\')
                                '/' -> sb.append('/')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    require(pos + 4 <= s.length) { "JSON \\u 转义不足 4 位 @ $pos" }
                                    val hex = s.substring(pos, pos + 4)
                                    pos += 4
                                    sb.append(hex.toInt(16).toChar())
                                }
                                else -> error("JSON 非法转义 \\$e @ $pos")
                            }
                        }
                        else -> sb.append(c)
                    }
                }
                error("JSON 字符串未闭合")
            }

            private fun readLiteral(literal: String, value: Any?): Any? {
                skipWhitespace()
                require(s.startsWith(literal, pos)) { "JSON 期望 $literal @ $pos" }
                pos += literal.length
                return value
            }

            private fun readNumber(): Any {
                skipWhitespace()
                val start = pos
                if (s[pos] == '-') pos++
                while (pos < s.length && (
                        s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' ||
                            s[pos] == 'E' || s[pos] == '+' || s[pos] == '-'
                        )
                ) {
                    pos++
                }
                val raw = s.substring(start, pos)
                return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                    raw.toDouble()
                } else {
                    raw.toLong()
                }
            }

            private fun expect(c: Char) {
                skipWhitespace()
                require(pos < s.length && s[pos] == c) {
                    "JSON 期望 '$c' @ $pos，实际 '${if (pos < s.length) s[pos] else "EOF"}'"
                }
                pos++
            }

            private fun peek(): Char {
                require(pos < s.length) { "JSON 意外结束 @ $pos" }
                return s[pos]
            }
        }
    }

    private companion object {
        private val VECTORS: List<VectorCase> by lazy { FaithfulGatesTest().loadVectors() }
    }
}
