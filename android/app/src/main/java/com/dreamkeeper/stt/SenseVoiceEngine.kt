package com.dreamkeeper.stt

import android.content.Context
import android.os.Build
import android.util.Log
import com.dreamkeeper.audio.WavMath
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内置 SenseVoice Small 的离线转写（D563）。
 *
 * 与 [SystemSttEngine] 的关系是「二选一」，不是包装：
 * · 系统识别器依赖厂商/Google 的离线包，装不上就一个字也出不来（vivo 上就是这样卡住的）；
 * · 这里把 239MB 的 int8 模型直接打进 APK，只在首次识别时加载一次，不联网、不看系统脸色。
 *
 * 三条必须守住的边界：
 * 1. **不伪造成功**：没有可用文字就 [SttResult.Failed]，绝不回一个空串冒充 OK。
 * 2. **不整文件读入内存**：4 小时录音 = 460MB 采样，靠 [SegmentPlan] 分段 + [PcmReader] 按段读。
 * 3. **模型只加载一次**：`OfflineRecognizer` 构造要几百毫秒到数秒，且常驻 200MB 级内存；
 *    每次转写都新建会 OOM。
 */
class SenseVoiceEngine(
    private val context: Context,
    private val assetDir: String = "sensevoice",
) : SttEngine {

    private val initLock = Mutex()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var initFailure: String? = null

    @Volatile
    private var cachedAvailability: SttAvailability? = null

    /** 识别器不是线程安全的整段对象；两段音频同时 decode 会踩到同一个 onnx session。 */
    private val decodeLock = Mutex()

    override suspend fun availability(): SttAvailability {
        cachedAvailability?.let { return it }
        val probed = probeAvailability()
        cachedAvailability = probed
        return probed
    }

    private fun probeAvailability(): SttAvailability {
        if (Build.SUPPORTED_ABIS.none { it in SUPPORTED_ABIS }) {
            return SttAvailability.Unavailable("内置语音模型不支持这台机器的架构")
        }
        val model = "$assetDir/model.int8.onnx"
        val tokens = "$assetDir/tokens.txt"
        return try {
            context.assets.open(model).close()
            context.assets.open(tokens).close()
            SttAvailability.Available
        } catch (error: Throwable) {
            Log.w(TAG, "内置模型资产缺失：$model / $tokens", error)
            SttAvailability.Unavailable("内置语音模型缺失，请重装应用")
        }
    }

    override suspend fun transcribe(file: File): SttResult = withContext(Dispatchers.Default) {
        val available = availability()
        if (available !is SttAvailability.Available) {
            return@withContext SttResult.Failed(
                (available as? SttAvailability.Unavailable)?.reason ?: "内置语音模型不可用",
            )
        }
        val startedAt = System.currentTimeMillis()
        val result = try {
            recognize(file)
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "转写 OOM", error)
            null
        } catch (error: Throwable) {
            Log.w(TAG, "转写异常", error)
            null
        }
        if (result == null) {
            return@withContext SttResult.Failed(initFailure ?: "转写失败，可以手动补字")
        }
        val elapsed = System.currentTimeMillis() - startedAt
        Log.i(TAG, "sensevoice done chars=${result.length} elapsedMs=$elapsed")
        if (!isMeaningfulTranscript(result)) {
            SttResult.Failed("没有识别出文字")
        } else {
            SttResult.Ok(result)
        }
    }

    private suspend fun recognize(file: File): String? {
        val zeroCopy = ensureRecognizer() ?: return null
        val total = PcmReader.sampleCount(file)
        if (total <= 0) return ""
        decodeLock.withLock {
            val plan = SegmentPlan.planForRecording(total)
            val parts = ArrayList<String>(plan.size)
            for ((index, range) in plan.withIndex()) {
                val samples = PcmReader.read(file, range.first, range.last - range.first + 1)
                if (samples.isEmpty()) continue
                val text = decodeOnce(zeroCopy, samples)
                if (text.isNotEmpty()) parts += text
                Log.i(TAG, "segment ${index + 1}/${plan.size} samples=${samples.size} chars=${text.length}")
            }
            return SegmentPlan.mergeSegments(parts)
        }
    }

    private fun decodeOnce(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    private suspend fun ensureRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        return initLock.withLock {
            recognizer?.let { return@withLock it }
            initFailure?.let { return@withLock null }
            val created = try {
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = "$assetDir/model.int8.onnx",
                            language = "auto",
                            // 加标点：转写稿直接进「原样」页，没有标点读起来像故障日志。
                            useInverseTextNormalization = true,
                        ),
                        tokens = "$assetDir/tokens.txt",
                        numThreads = NUM_THREADS,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                OfflineRecognizer(context.assets, config)
            } catch (error: Throwable) {
                Log.w(TAG, "识别器初始化失败", error)
                initFailure = when (error) {
                    is UnsatisfiedLinkError -> "内置语音模型加载失败（CPU 架构不支持）"
                    is OutOfMemoryError -> "内存不够，加载不了内置语音模型"
                    else -> "内置语音模型初始化失败"
                }
                null
            }
            recognizer = created
            created
        }
    }

    companion object {
        private const val TAG = "DreamStt"
        private const val SAMPLE_RATE = 16_000
        private const val FEATURE_DIM = 80
        private const val NUM_THREADS = 2
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

        /** 打给 QA/日志用的一句人话：录一段音，看到这行就说明内置引擎真的在跑。 */
        internal fun describe(result: SttResult): String = when (result) {
            is SttResult.Ok -> "ok/${result.text.length}"
            is SttResult.Failed -> "failed/${result.reason}"
        }
    }
}

/** WAV 头的旁证：内置引擎只认 16bit 单声道 16kHz，别的格式直接说清楚，不要喂出噪声。 */
internal fun isSupportedWav(file: File): Boolean {
    if (file.length() < WavMath.HEADER_BYTES) return false
    val header = ByteArray(WavMath.HEADER_BYTES)
    return runCatching {
        file.inputStream().use { it.read(header) }
        val channels = header[22].toInt() and 0xFF or ((header[23].toInt() and 0xFF) shl 8)
        val sampleRate = WavMath.readU32(header, 24)
        val bits = header[34].toInt() and 0xFF or ((header[35].toInt() and 0xFF) shl 8)
        channels == 1 && sampleRate == 16_000L && bits == 16
    }.getOrDefault(false)
}
