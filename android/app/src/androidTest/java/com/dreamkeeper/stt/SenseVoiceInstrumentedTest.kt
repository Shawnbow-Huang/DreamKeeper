package com.dreamkeeper.stt

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 内置 SenseVoice 的**真机证据**（D563）。
 *
 * JVM 单测只能证明切段数学对；这个用例把 APK 里的 239MB 模型真的读一遍、
 * 跑一次 native 推理，断言「转出来的文字里有汉字」。
 *
 * 用 `test_wavs/zh.wav`（sherpa-onnx 官方样例，中文），随 androidTest 打包，
 * 不进正式 APK。
 */
@RunWith(AndroidJUnit4::class)
class SenseVoiceInstrumentedTest {

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    private fun copyTestWav(): File {
        val wav = File(targetContext.cacheDir, "sensevoice-zh.wav")
        if (!wav.exists()) {
            testContext.assets.open("zh.wav").use { input ->
                wav.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return wav
    }

    @Test
    fun bundledModelTranscribesChineseAudio() {
        val engine = SenseVoiceEngine(targetContext)
        val availability = runBlocking { engine.availability() }
        assertTrue("内置模型应当可用：$availability", availability is SttAvailability.Available)

        val wav = copyTestWav()
        assertTrue("测试音频必须是 16k 单声道 16bit", isSupportedWav(wav))

        val result = runBlocking { engine.transcribe(wav) }
        assertTrue("转写失败：${(result as? SttResult.Failed)?.reason}", result is SttResult.Ok)
        val text = (result as SttResult.Ok).text
        // 证据用：把真实转写结果打到 logcat（TAG 只在本测试里用，不写进生产代码）。
        android.util.Log.i("SenseVoiceProof", "engine=${System.getProperty("os.arch")} text=$text")
        assertTrue("转写结果为空", text.isNotBlank())
        assertTrue("转写结果里没有汉字：$text", text.any { it in '\u4e00'..'\u9fff' })
        wav.delete()
    }
}
