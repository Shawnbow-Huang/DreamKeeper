package com.dreamkeeper.stt

import com.dreamkeeper.audio.WavMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 分段与拼接的纯数学（D563）。
 *
 * 这段逻辑一旦错了，表面症状是「转写偶尔吞字/重复半句」——最难复现的那类 bug，
 * 所以每条边界都钉死在这里，而不是等真机录音碰运气。
 */
class SegmentPlanTest {

    @Test
    fun `plan 按 chunk 切且相邻段重叠`() {
        val ranges = SegmentPlan.plan(totalSamples = 1000, chunkSamples = 400, overlapSamples = 100)
        assertEquals(listOf(0..399, 300..699, 600..999), ranges)
    }

    @Test
    fun `最后一段截到末尾`() {
        val ranges = SegmentPlan.plan(totalSamples = 900, chunkSamples = 400, overlapSamples = 100)
        assertEquals(0..399, ranges[0])
        assertEquals(300..699, ranges[1])
        assertEquals(600..899, ranges[2])
    }

    @Test
    fun `一分钟录音切三段 每段不超过 25 秒`() {
        val ranges = SegmentPlan.planForRecording(16_000 * 60)
        assertEquals(3, ranges.size)
        assertEquals(0, ranges.first().first)
        assertEquals(16_000 * 60 - 1, ranges.last().last)
        assertTrue(ranges.all { it.last - it.first + 1 <= 25 * 16_000 })
    }

    @Test
    fun `空音频与非法参数返回空计划`() {
        assertTrue(SegmentPlan.plan(0, 400, 100).isEmpty())
        assertTrue(SegmentPlan.plan(100, 0, 0).isEmpty())
    }

    @Test
    fun `重叠部分只出现一次`() {
        val merged = SegmentPlan.mergeSegments(listOf("他说今天下雨", "今天下雨了别出门"))
        assertEquals("他说今天下雨了别出门", merged)
    }

    @Test
    fun `英文接缝同样去重`() {
        val merged = SegmentPlan.mergeSegments(listOf("hello world", "world again"))
        assertEquals("hello world again", merged)
    }

    @Test
    fun `没有重叠时补逗号而不是硬贴`() {
        val merged = SegmentPlan.mergeSegments(listOf("第一段", "第二段"))
        assertEquals("第一段，第二段", merged)
    }

    @Test
    fun `真机接缝：上一段补了句号 下一段补了逗号 仍只保留一份`() {
        // 2026-11-01 vivo 92 秒录音的三处真实接缝（截取）
        assertEquals(
            "那就去澳门就跟好像要坐宇宙飞船",
            SegmentPlan.mergeSegments(listOf("那就去澳。", "，去澳门就跟好像要坐宇宙飞船")),
        )
        assertEquals(
            "比如说4.4点钟出发，6点钟到啊，那到底",
            SegmentPlan.mergeSegments(listOf("比如说4.4点钟出发，6点钟到。", "，6点钟到啊，那到底")),
        )
        assertEquals(
            "然后但是呢走到一个店门口呢，所以说不允许",
            SegmentPlan.mergeSegments(listOf("然后但是呢走到一个店门口呢。", "，店门口呢，所以说不允许")),
        )
    }

    @Test
    fun `没有重叠时不会误删前一段的句号`() {
        assertEquals(
            "第一句。第二句",
            SegmentPlan.mergeSegments(listOf("第一句。", "第二句")),
        )
    }

    @Test
    fun `全空段返回空串`() {
        assertEquals("", SegmentPlan.mergeSegments(listOf("", "   ", "")))
    }
}

/** WAV 读法与格式判断（D563）：只认 16bit 单声道 16kHz，别的格式不喂给模型。 */
class PcmReaderTest {

    private fun wavFile(samples: ShortArray, sampleRate: Int = 16_000, channels: Int = 1, bits: Int = 16): File {
        val data = samples.size * 2
        val header = ByteArray(WavMath.HEADER_BYTES)
        "RIFF".toByteArray().copyInto(header, 0)
        WavMath.writeU32(header, 4, (36 + data).toLong())
        "WAVEfmt ".toByteArray().copyInto(header, 8)
        WavMath.writeU32(header, 16, 16)
        header[20] = 1
        header[22] = channels.toByte()
        WavMath.writeU32(header, 24, sampleRate.toLong())
        WavMath.writeU32(header, 28, (sampleRate * channels * bits / 8).toLong())
        header[32] = (channels * bits / 8).toByte()
        header[34] = bits.toByte()
        "data".toByteArray().copyInto(header, 36)
        WavMath.writeU32(header, 40, data.toLong())
        val body = ByteArray(data)
        samples.forEachIndexed { index, value ->
            body[index * 2] = (value.toInt() and 0xFF).toByte()
            body[index * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        val file = Files.createTempFile("pcm", ".wav").toFile()
        file.writeBytes(header + body)
        return file
    }

    @Test
    fun `采样数与 LE 转换正确`() {
        val file = wavFile(shortArrayOf(0, 16384, -16384, -32768))
        assertEquals(4, PcmReader.sampleCount(file))
        val samples = PcmReader.read(file, 0, 4)
        assertEquals(0f, samples[0], 0.0001f)
        assertEquals(0.5f, samples[1], 0.0001f)
        assertEquals(-0.5f, samples[2], 0.0001f)
        assertEquals(-1f, samples[3], 0.0001f)
        file.delete()
    }

    @Test
    fun `越界的起始与长度被夹到实际范围`() {
        val file = wavFile(shortArrayOf(1, 2, 3))
        assertTrue(PcmReader.read(file, 10, 5).isEmpty())
        assertEquals(1, PcmReader.read(file, 2, 99).size)
        assertTrue(PcmReader.read(file, 0, 0).isEmpty())
        file.delete()
    }

    @Test
    fun `格式判断只放行 16k 单声道 16bit`() {
        val ok = wavFile(shortArrayOf(0, 1))
        assertTrue(isSupportedWav(ok))
        ok.delete()
        val stereo = wavFile(shortArrayOf(0, 1), channels = 2)
        assertFalse(isSupportedWav(stereo))
        stereo.delete()
        val wrongRate = wavFile(shortArrayOf(0, 1), sampleRate = 44_100)
        assertFalse(isSupportedWav(wrongRate))
        wrongRate.delete()
    }
}

/** 二选一引擎：内置优先、系统兜底、都不行时报内置的原因。 */
class PreferredSttEngineTest {

    private class FixedEngine(
        private val state: SttAvailability,
        private val result: SttResult = SttResult.Ok("文字"),
    ) : SttEngine {
        var transcribed = false
        override suspend fun availability(): SttAvailability = state
        override suspend fun transcribe(file: File): SttResult {
            transcribed = true
            return result
        }
    }

    @Test
    fun `内置可用时走内置`() = kotlinx.coroutines.test.runTest {
        val bundled = FixedEngine(SttAvailability.Available)
        val system = FixedEngine(SttAvailability.Available)
        val engine = PreferredSttEngine(bundled, system)
        assertEquals(SttAvailability.Available, engine.availability())
        assertTrue(engine.transcribe(File("x.wav")) is SttResult.Ok)
        assertTrue(bundled.transcribed)
        assertFalse(system.transcribed)
    }

    @Test
    fun `内置不可用时退化到系统`() = kotlinx.coroutines.test.runTest {
        val bundled = FixedEngine(SttAvailability.Unavailable("模型缺失"))
        val system = FixedEngine(SttAvailability.Available)
        val engine = PreferredSttEngine(bundled, system)
        assertEquals(SttAvailability.Available, engine.availability())
        engine.transcribe(File("x.wav"))
        assertTrue(system.transcribed)
    }

    @Test
    fun `两边都不可用时报内置的原因`() = kotlinx.coroutines.test.runTest {
        val bundled = FixedEngine(SttAvailability.Unavailable("模型缺失"))
        val system = FixedEngine(SttAvailability.NeedsDownload("zh-CN"))
        val engine = PreferredSttEngine(bundled, system)
        val state = engine.availability()
        assertEquals(SttAvailability.Unavailable("模型缺失"), state)
        assertEquals(SttResult.Failed("没有可用的语音识别引擎"), engine.transcribe(File("x.wav")))
    }
}
