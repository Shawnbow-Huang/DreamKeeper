package com.dreamkeeper.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * RecorderCore 的「读循环 / 按单调钟补等长零帧 / rebase / 收尾原因 / 振幅」契约。
 *
 * 期望值一律在测试侧独立算（[WavMath.BYTES_PER_MS] 或 16 样本每毫秒），
 * 不拿 [RecorderStop.zeroFillBytes] 反推，避免实现写错时测试跟着错。
 */
class RecorderCoreTest {

    // ------------------------------------------------------------ 对照组：不补零

    @Test
    fun `frozen clock writes three real blocks and never zero fills`() {
        val clock = FakeClock(0L)
        val source = ScriptedSource(
            clock,
            Read.Bytes(BLOCK, 0x11),
            Read.Bytes(BLOCK, 0x22),
            Read.Bytes(BLOCK, 0x33),
        )
        val sink = RecordingSink()
        val seen = mutableListOf<Pair<Long, Int>>()

        val stop = RecorderCore(source, sink, { clock.now }).run(0L) { bytes, db -> seen += bytes to db }

        assertEquals("只算真实 PCM", 3L * BLOCK, stop.bytes)
        assertEquals("时钟没走，一字节零都不许补", 0L, stop.zeroFillBytes)
        assertEquals("只有三次真实 write", listOf(BLOCK, BLOCK, BLOCK), sink.writeLengths)
        assertEquals(
            "onChunk 的 bytes 是本次 run 的真实字节累计",
            listOf(3_200L, 6_400L, 9_600L),
            seen.map { it.first },
        )
        assertEquals("源耗尽后按 STOPPED 收尾", "stopped", stop.reason)
        assertEquals("收尾必须 finish 一次", 1, sink.finishCount)
        assertTrue("收尾前必须 sync", sink.syncCount >= 1)
    }

    // ------------------------------------------------------------ 补零组

    @Test
    fun `gap over threshold writes zeros before the real block`() {
        val clock = FakeClock(250L)
        val source = ScriptedSource(clock, Read.Bytes(BLOCK, 0x11))
        val sink = RecordingSink()
        // 契约独立算法：250 ms = 4000 样本；已读到的块 1600 样本；缺口 2400 样本 → 4800 B
        val expectedZero = zeroBytesFor(250L)

        val stop = RecorderCore(source, sink, { clock.now }).run(0L) { _, _ -> }

        assertEquals("测试侧常量先用公式钉死", 4_800L, expectedZero)
        assertEquals("补零字节 = 样本缺口 × 2", expectedZero, stop.zeroFillBytes)
        assertEquals(BLOCK.toLong(), stop.bytes)
        assertEquals("先写 4800 B 零，再写 3200 B 真实块", listOf(4_800, BLOCK), sink.writeLengths)
        val out = sink.data.toByteArray()
        assertTrue(
            "先补零：out[0,4800) 必须全 0",
            out.copyOfRange(0, expectedZero.toInt()).all { it == 0.toByte() },
        )
        assertArrayEquals(
            "零之后才是刚读到的真实块",
            ByteArray(BLOCK) { 0x11.toByte() },
            out.copyOfRange(expectedZero.toInt(), expectedZero.toInt() + BLOCK),
        )
    }

    @Test
    fun `larger gap fills an even number of bytes`() {
        val clock = FakeClock(300L)
        val source = ScriptedSource(clock, Read.Bytes(BLOCK, 0x77))
        val sink = RecordingSink()
        val expectedZero = zeroBytesFor(300L) // 4800 - 1600 = 3200 样本 → 6400 B

        val stop = RecorderCore(source, sink, { clock.now }).run(0L) { _, _ -> }

        assertEquals(6_400L, expectedZero)
        assertEquals("补零字节一定偶数", 0L, stop.zeroFillBytes % 2)
        assertEquals(expectedZero, stop.zeroFillBytes)
        assertEquals(listOf(6_400, BLOCK), sink.writeLengths)
    }

    @Test
    fun `gap exactly at threshold writes no zero fill`() {
        val clock = FakeClock(200L)
        val source = ScriptedSource(clock, Read.Bytes(BLOCK, 0x11))
        val sink = RecordingSink()
        // 200 ms = 3200 样本，块本身 1600 样本 → 缺口恰 1600（= 100 ms × 16），严格 > 才补
        val deficit = 200L * SAMPLES_PER_MS - BLOCK / 2

        val stop = RecorderCore(source, sink, { clock.now }).run(0L) { _, _ -> }

        assertEquals("缺口必须恰在门槛上", 1_600L, deficit)
        assertEquals(0L, stop.zeroFillBytes)
        assertEquals(listOf(BLOCK), sink.writeLengths)
    }

    @Test
    fun `gap under threshold writes no zero fill`() {
        val clock = FakeClock(100L)
        val source = ScriptedSource(clock, Read.Bytes(BLOCK, 0x11))
        val sink = RecordingSink()

        val stop = RecorderCore(source, sink, { clock.now }).run(0L) { _, _ -> }

        assertEquals(0L, stop.zeroFillBytes)
        assertEquals(listOf(BLOCK), sink.writeLengths)
    }

    // ------------------------------------------------------------ read 的三种返回值

    @Test
    fun `read returning zero does not end the run`() {
        val clock = FakeClock(0L)
        val source = ScriptedSource(clock, Read.Empty(), Read.Bytes(BLOCK, 0x55))
        val sink = RecordingSink()

        val stop = RecorderCore(source, sink, { clock.now }).run(0L) { _, _ -> }

        assertEquals("空读只是暂时没数据，必须继续", BLOCK.toLong(), stop.bytes)
        assertEquals("stopped", stop.reason)
        assertTrue("read()==0 之后还要再读", source.readCount >= 3)
        assertEquals(1, sink.writeLengths.size)
    }

    @Test
    fun `read error ends with dead`() {
        val clock = FakeClock(0L)
        val sink = RecordingSink()

        val stop = RecorderCore(ScriptedSource(clock, Read.Dead()), sink, { clock.now }).run(0L) { _, _ -> }

        assertEquals("dead", stop.reason)
        assertEquals(0L, stop.bytes)
        assertTrue(sink.writeLengths.isEmpty())
        assertEquals("读错误也要回填头", 1, sink.finishCount)
    }

    @Test
    fun `stopped source ends with stopped`() {
        val clock = FakeClock(0L)
        val source = ScriptedSource(clock, initialState = SourceState.STOPPED)
        val sink = RecordingSink()

        val stop = RecorderCore(source, sink, { clock.now }).run(0L) { _, _ -> }

        assertEquals("stopped", stop.reason)
        assertEquals("已经 STOPPED 就不该再读", 0, source.readCount)
        assertEquals(0L, stop.bytes)
    }

    @Test
    fun `external stop ends with user`() {
        val clock = FakeClock(0L)
        val source = ScriptedSource(clock, Read.Bytes(BLOCK, 0x11))
        val sink = RecordingSink()
        val core = RecorderCore(source, sink, { clock.now })

        val stop = core.run(0L) { _, _ -> core.stop() }

        assertEquals("user", stop.reason)
        assertEquals(BLOCK.toLong(), stop.bytes)
        assertEquals("RecorderCore.stop() 必须把停转达给 source", 1, source.stopCalls)
        assertEquals("user 收尾也要回填头", 1, sink.finishCount)
    }

    // ------------------------------------------------------------ 时钟采样时机

    @Test
    fun `clock is sampled before read so time spent inside read is not a gap`() {
        val clock = FakeClock(0L)
        // read 内部耗掉 250 ms；契约要求时钟在调用 read 之前采样，所以这 250 ms 不算缺口
        val source = ScriptedSource(clock, Read.Bytes(BLOCK, 0x11, advanceMs = 250L))
        val sink = RecordingSink()

        val stop = RecorderCore(source, sink, { clock.now }).run(0L) { _, _ -> }

        assertEquals("read 内耗掉的时间不补零", 0L, stop.zeroFillBytes)
        assertEquals(listOf(BLOCK), sink.writeLengths)
    }

    // ------------------------------------------------------------ 追加 / rebase

    @Test
    fun `existing pcm bytes are not counted as a gap when clock is frozen`() {
        val clock = FakeClock(0L)
        val sink = RecordingSink(initialBytes = 1_044L) // 44 B 头 + 1000 B PCM

        val stop = RecorderCore(ScriptedSource(clock, Read.Bytes(BLOCK, 0x11)), sink, { clock.now })
            .run(0L) { _, _ -> }

        assertEquals(0L, stop.zeroFillBytes)
        assertEquals(BLOCK.toLong(), stop.bytes)
        assertEquals(1_044L + BLOCK, sink.bytesWritten())
    }

    @Test
    fun `existing pcm bytes still reduce the deficit of the current window`() {
        val clock = FakeClock(250L)
        val sink = RecordingSink(initialBytes = 1_044L)

        val stop = RecorderCore(ScriptedSource(clock, Read.Bytes(BLOCK, 0x11)), sink, { clock.now })
            .run(0L) { _, _ -> }

        // 若把绝对偏移 1044 B 当成本窗口已写字节，缺口会算成 1878 样本（3756 B）而不是 2400（4800 B）
        assertEquals(4_800L, stop.zeroFillBytes)
        assertEquals(1_044L + 4_800L + BLOCK, sink.bytesWritten())
    }

    @Test
    fun `rebase restarts both origins`() {
        val clock = FakeClock(250L)
        val source = ScriptedSource(
            clock,
            Read.Bytes(BLOCK, 0x11),
            Read.Bytes(BLOCK, 0x22),
        )
        val sink = RecordingSink()
        val core = RecorderCore(source, sink, { clock.now })
        var firstChunk = true

        val stop = core.run(0L) { _, _ ->
            if (firstChunk) {
                firstChunk = false
                core.rebase() // 新窗口原点到 clock()=250、已写 8000 B
                clock.now += 250L // 暂停结束，新窗口又走了 250 ms
            }
        }

        assertEquals("两个 250 ms 缺口各补 4800 B", 9_600L, stop.zeroFillBytes)
        assertEquals(2L * BLOCK, stop.bytes)
        assertEquals("每次都是先零后真实块", listOf(4_800, BLOCK, 4_800, BLOCK), sink.writeLengths)
        val out = sink.data.toByteArray()
        assertTrue("第二窗口 [8000,12800) 全 0", out.copyOfRange(8_000, 12_800).all { it == 0.toByte() })
        assertArrayEquals(
            "第二窗口的零之后才是第二块",
            ByteArray(BLOCK) { 0x22.toByte() },
            out.copyOfRange(12_800, 16_000),
        )
    }

    // ------------------------------------------------------------ 振幅

    @Test
    fun `all zero block reports silence floor instead of int min`() {
        val clock = FakeClock(0L)
        val dbs = mutableListOf<Int>()

        RecorderCore(ScriptedSource(clock, Read.Bytes(BLOCK, 0x00)), RecordingSink(), { clock.now })
            .run(0L) { _, db -> dbs += db }

        assertEquals("全零块直接取 silenceFloorDb", listOf(-60), dbs)
        assertTrue("绝不能漏出 Int.MIN_VALUE", dbs.single() != Int.MIN_VALUE)
    }

    @Test
    fun `non silent block stays within the db floor and maps to ui range`() {
        val clock = FakeClock(0L)
        val dbs = mutableListOf<Int>()

        RecorderCore(ScriptedSource(clock, Read.Bytes(BLOCK, 0x40)), RecordingSink(), { clock.now })
            .run(0L) { _, db -> dbs += db }

        val db = dbs.single() // 恒定样本 0x4040 = 16448 → 约 -5.98 dBFS
        assertTrue("振幅必须在 [-60, 0]：$db", db in -60..0)
        assertTrue("非静音块必须高于下限：$db", db > -60)
        val ui = (db - (-60)) / 60f
        assertTrue("UI 映射 (db-floor)/-floor 必须在 0..1：$ui", ui in 0f..1f)
        assertEquals("下限映射到 0", 0f, (-60 - (-60)) / 60f, 0f)
        assertEquals("满幅映射到 1", 1f, (0 - (-60)) / 60f, 0f)
    }

    @Test
    fun `silence floor is configurable`() {
        val clock = FakeClock(0L)
        val dbs = mutableListOf<Int>()

        RecorderCore(
            ScriptedSource(clock, Read.Bytes(BLOCK, 0x00)),
            RecordingSink(),
            clock = { clock.now },
            silenceFloorDb = -40,
        ).run(0L) { _, db -> dbs += db }

        assertEquals(listOf(-40), dbs)
    }

    // ------------------------------------------------------------ 异常收尾

    @Test
    fun `sink write failure ends with error and still finishes`() {
        val clock = FakeClock(0L)
        val sink = RecordingSink(failOnWrite = true)

        val stop = RecorderCore(ScriptedSource(clock, Read.Bytes(BLOCK, 0x11)), sink, { clock.now })
            .run(0L) { _, _ -> }

        assertEquals("error", stop.reason)
        assertEquals(0L, stop.bytes)
        assertEquals("异常也要回填头", 1, sink.finishCount)
    }

    @Test
    fun `sync failure still finishes the header`() {
        val clock = FakeClock(0L)
        val sink = RecordingSink(failOnSync = true)

        val stop = RecorderCore(ScriptedSource(clock, Read.Bytes(BLOCK, 0x11)), sink, { clock.now })
            .run(0L) { _, _ -> }

        assertEquals("sync 失败也要报 error", "error", stop.reason)
        assertEquals("头必须回填：finish 仍要调一次", 1, sink.finishCount)
        assertEquals("真实字节照常记账", BLOCK.toLong(), stop.bytes)
    }

    @Test
    fun `finish failure ends with error and keeps real bytes`() {
        val clock = FakeClock(0L)
        val sink = RecordingSink(failOnFinish = true)

        val stop = RecorderCore(ScriptedSource(clock, Read.Bytes(BLOCK, 0x11)), sink, { clock.now })
            .run(0L) { _, _ -> }

        assertEquals("error", stop.reason)
        assertEquals("finish 抛异常时 sync 已经做过一次", 1, sink.syncCount)
        assertEquals(BLOCK.toLong(), stop.bytes)
    }

    @Test
    fun `source read exception ends with error`() {
        val clock = FakeClock(0L)
        val sink = RecordingSink()

        val stop = RecorderCore(ScriptedSource(clock, throwOnRead = true), sink, { clock.now })
            .run(0L) { _, _ -> }

        assertEquals("error", stop.reason)
        assertEquals(1, sink.finishCount)
    }

    private companion object {
        /** 16 kHz / 16 bit / 单声道：100 ms = 3200 B。 */
        const val BLOCK = 3_200

        /** 16 kHz = 16 样本每毫秒。 */
        const val SAMPLES_PER_MS = 16L

        /** 测试侧按契约独立算缺口字节：样本缺口 × 2。 */
        fun zeroBytesFor(gapMs: Long, blockBytes: Int = BLOCK): Long =
            (gapMs * SAMPLES_PER_MS - blockBytes / 2) * 2
    }
}

// ---------------------------------------------------------------- 假实现

private class FakeClock(var now: Long)

private sealed class Read {
    data class Bytes(val size: Int, val fill: Int, val advanceMs: Long = 0L) : Read()
    data class Empty(val advanceMs: Long = 0L) : Read()
    data class Dead(val advanceMs: Long = 0L) : Read()
}

/** 按脚本逐次返回：数据块 / 0 / <0；脚本耗尽后返回 0 并置 STOPPED。 */
private class ScriptedSource(
    private val clock: FakeClock,
    vararg reads: Read,
    private val initialState: SourceState = SourceState.RECORDING,
    private val throwOnRead: Boolean = false,
) : PcmSource {
    private val pending = reads.toMutableList()
    private var currentState = initialState

    var readCount = 0
        private set

    var stopCalls = 0
        private set

    override fun read(buffer: ByteArray): Int {
        readCount++
        if (throwOnRead) throw IllegalStateException("模拟读失败")
        if (pending.isEmpty()) {
            currentState = SourceState.STOPPED
            return 0
        }
        return when (val step = pending.removeAt(0)) {
            is Read.Bytes -> {
                require(step.size <= buffer.size) { "块 ${step.size} B 大于 buffer ${buffer.size} B" }
                buffer.fill(step.fill.toByte(), 0, step.size)
                clock.now += step.advanceMs
                step.size
            }
            is Read.Empty -> {
                clock.now += step.advanceMs
                0
            }
            is Read.Dead -> {
                clock.now += step.advanceMs
                -1
            }
        }
    }

    override fun state(): SourceState = currentState

    override fun stop() {
        stopCalls++
        currentState = SourceState.STOPPED
    }
}

private class RecordingSink(
    private val initialBytes: Long = 0L,
    private val failOnWrite: Boolean = false,
    private val failOnSync: Boolean = false,
    private val failOnFinish: Boolean = false,
) : PcmSink {
    val data = ByteArrayOutputStream()
    val writeLengths = mutableListOf<Int>()

    var syncCount = 0
        private set

    var finishCount = 0
        private set

    private var appended = 0L

    override fun write(buffer: ByteArray, length: Int) {
        require(length % 2 == 0) { "PcmSink.write 的 length 必须偶数：$length" }
        if (failOnWrite) throw IllegalStateException("模拟写失败")
        writeLengths += length
        data.write(buffer, 0, length)
        appended += length
    }

    override fun sync() {
        if (failOnSync) throw java.io.IOException("模拟 sync 失败")
        syncCount++
    }

    override fun bytesWritten(): Long = initialBytes + appended

    override fun finish() {
        if (failOnFinish) throw java.io.IOException("模拟 finish 失败")
        finishCount++
    }
}
