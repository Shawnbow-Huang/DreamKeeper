package com.dreamkeeper.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.dreamkeeper.audio.WavMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 系统 on-device 识别器的唯一实现。
 *
 * 三条容易写错的地方，全部按 Task 7 §0.1–§0.4 处理：
 * 1. **探测是 suspend 的**：`checkRecognitionSupport` 要等回调；只有 installed 表里
 *    有中文才报 `Available`，online 表命中只报 `Unavailable("只有在线识别")`。
 * 2. **不 seek**：`EXTRA_AUDIO_SOURCE` 不解析 WAV 容器，也不许把整文件 fd 丢过去。
 *    一律 `ParcelFileDescriptor.createPipe()`，IO 线程把 `[44, EOF)` 拷进写端后关写端。
 * 3. **主线程**：`SpeechRecognizer` 的创建/调用/`destroy()` 都在 `Dispatchers.Main.immediate`。
 */
class SystemSttEngine(
    private val context: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SttEngine {

    private val probeLock = Mutex()

    @Volatile
    private var cachedAvailability: SttAvailability? = null

    override suspend fun availability(): SttAvailability {
        cachedAvailability?.let { return it }
        return probeLock.withLock {
            cachedAvailability?.let { return@withLock it }
            val probed = runCatching { probeAvailability() }
                .getOrElse { error ->
                    Log.w(TAG, "可用性探测异常", error)
                    SttAvailability.Unavailable("可用性探测失败")
                }
            cachedAvailability = probed
            Log.i(TAG, "availability=$probed")
            probed
        }
    }

    // ------------------------------------------------------------ 可用性探测

    private suspend fun probeAvailability(): SttAvailability = withContext(mainDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return@withContext SttAvailability.Unavailable("系统版本过低")
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return@withContext SttAvailability.Unavailable("本机没有离线识别能力")
        }
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (error: Throwable) {
            Log.w(TAG, "创建离线识别器失败", error)
            return@withContext SttAvailability.Unavailable("离线识别器创建失败")
        }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // 31–32 有离线识别，但没有 EXTRA_AUDIO_SOURCE；不能假装能转文件。
                return@withContext SttAvailability.AvailableButNoFileInput
            }
            val support = awaitRecognitionSupport(recognizer)
                ?: return@withContext SttAvailability.Unavailable("识别服务没有返回语言支持")
            // 证据 p2-stt-02 要原文四张表：语言标签不是隐私内容，可以直接进日志。
            val installed = support.getInstalledOnDeviceLanguages()
            val supported = support.getSupportedOnDeviceLanguages()
            val pending = support.getPendingOnDeviceLanguages()
            val online = support.getOnlineLanguages()
            Log.i(
                TAG,
                "support installed=$installed supported=$supported pending=$pending online=$online",
            )
            mapSupport(
                installed = installed,
                supported = supported,
                pending = pending,
                online = online,
                locale = LOCALE,
            )
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun awaitRecognitionSupport(recognizer: SpeechRecognizer): RecognitionSupport? =
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            suspendCancellableCoroutine<RecognitionSupport?> { continuation ->
                val callback = object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (continuation.isActive) continuation.resume(support)
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "checkRecognitionSupport onError=$error")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                try {
                    recognizer.checkRecognitionSupport(
                        baseIntent(),
                        ContextCompat.getMainExecutor(context),
                        callback,
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "checkRecognitionSupport 抛异常", error)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    // ---------------------------------------------------------------- 转写

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override suspend fun transcribe(file: File): SttResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return SttResult.Failed("系统不支持文件转写")
        }
        val fileBytes = runCatching { file.length() }.getOrElse { error ->
            Log.w(TAG, "读不到录音文件", error)
            return SttResult.Failed("读不到录音文件")
        }
        val dataBytes = WavMath.dataBytes(fileBytes)
        if (dataBytes <= 0L) return SttResult.Failed("录音是空的")

        val durationMs = WavMath.durationMs(fileBytes)
        val ranges = segmentRanges(dataBytes, durationMs)
        Log.i(
            TAG,
            "transcribe start file=${file.name} fileBytes=$fileBytes dataBytes=$dataBytes " +
                "durationMs=$durationMs segments=${ranges.size}",
        )
        val startedAt = SystemClock.elapsedRealtime()
        val texts = ArrayList<String>(ranges.size)
        for ((index, range) in ranges.withIndex()) {
            val segmentMs = (range.last - range.first + 1) / WavMath.BYTES_PER_MS
            // 上限 90s：真机上离线包缺失或服务不应答时，不能让用户对着「正在转写…」等 3 分钟。
            val timeoutMs = minOf(MAX_SEGMENT_TIMEOUT_MS, maxOf(MIN_SEGMENT_TIMEOUT_MS, 2 * segmentMs))
            val result = transcribeSegment(
                file = file,
                startDataByte = range.first,
                endDataByteExclusive = range.last + 1,
                timeoutMs = timeoutMs,
                index = index,
            )
            if (result is SttResult.Failed) {
                Log.i(
                    TAG,
                    "transcribe end elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                        "result=Failed(${result.reason})",
                )
                return result
            }
            texts += (result as SttResult.Ok).text
        }
        val joined = texts.joinToString("").trim()
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (!isMeaningfulTranscript(joined)) {
            Log.i(TAG, "transcribe end elapsedMs=$elapsedMs result=Failed(没有识别出可用文字)")
            return SttResult.Failed("没有识别出可用文字")
        }
        // §10：日志只记长度，不记转写内容。
        Log.i(TAG, "transcribe end elapsedMs=$elapsedMs result=Ok(chars=${joined.length})")
        return SttResult.Ok(joined)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun transcribeSegment(
        file: File,
        startDataByte: Long,
        endDataByteExclusive: Long,
        timeoutMs: Long,
        index: Int,
    ): SttResult = withContext(mainDispatcher) {
        val expectedBytes = endDataByteExclusive - startDataByte
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val piped = AtomicLong(0L)

        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (error: Throwable) {
            Log.w(TAG, "segment $index 创建识别器失败", error)
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
            return@withContext SttResult.Failed("离线识别器创建失败")
        }

        val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pump = pumpScope.launch {
            piped.set(pumpData(file, startDataByte, endDataByteExclusive, writeEnd))
        }

        try {
            val intent = segmentIntent(readEnd)
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<SttResult> { continuation ->
                    val listener = object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.i(TAG, "segment $index onReadyForSpeech")
                        }

                        override fun onBeginningOfSpeech() = Unit

                        override fun onRmsChanged(rmsdB: Float) = Unit

                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() {
                            Log.i(TAG, "segment $index onEndOfSpeech")
                        }

                        override fun onError(error: Int) {
                            Log.i(TAG, "segment $index onError=$error")
                            if (continuation.isActive) {
                                continuation.resume(SttResult.Failed("识别失败（$error）"))
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            val verdict = if (isMeaningfulTranscript(text)) {
                                SttResult.Ok(text.trim())
                            } else {
                                SttResult.Failed("没有识别出可用文字")
                            }
                            if (continuation.isActive) continuation.resume(verdict)
                        }

                        override fun onPartialResults(partialResults: Bundle?) = Unit

                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    }
                    continuation.invokeOnCancellation { runCatching { recognizer.cancel() } }
                    recognizer.setRecognitionListener(listener)
                    try {
                        recognizer.startListening(intent)
                    } catch (error: Throwable) {
                        Log.w(TAG, "segment $index startListening 抛异常", error)
                        if (continuation.isActive) {
                            continuation.resume(SttResult.Failed("识别启动失败"))
                        }
                    }
                }
            } ?: run {
                runCatching { recognizer.cancel() }
                SttResult.Failed("识别超时")
            }

            // 正常结束时写端已关，泵很快退出；最多等 1 秒拿准确的字节数写进证据日志。
            withTimeoutOrNull(1_000) { pump.join() }
            Log.i(
                TAG,
                "segment $index piped=${piped.get()} expected=$expectedBytes budgetMs=$timeoutMs " +
                    "result=${if (result is SttResult.Ok) "Ok(chars=${result.text.length})" else (result as SttResult.Failed).reason}",
            )
            result
        } finally {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
            runCatching { readEnd.close() }
            pumpScope.cancel()
        }
    }

    // ---------------------------------------------------------------- 管道

    /**
     * 只把 `[44 + start, 44 + end)` 拷进写端，然后由 `AutoCloseOutputStream.use`
     * 关闭写端——识别器只在音频关闭时才认为会话结束。**不 seek 共享 fd**。
     */
    private fun pumpData(
        file: File,
        startDataByte: Long,
        endDataByteExclusive: Long,
        writeEnd: ParcelFileDescriptor,
    ): Long {
        var written = 0L
        try {
            FileInputStream(file).use { input ->
                var toSkip = WavMath.HEADER_BYTES + startDataByte
                while (toSkip > 0L) {
                    val skipped = input.skip(toSkip)
                    if (skipped > 0L) {
                        toSkip -= skipped
                    } else if (input.read() < 0) {
                        Log.w(TAG, "跳过 WAV 头时提前 EOF")
                        return written
                    } else {
                        toSkip -= 1
                    }
                }
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
                    val buffer = ByteArray(PIPE_BUFFER_BYTES)
                    var remaining = endDataByteExclusive - startDataByte
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        remaining -= read
                    }
                    output.flush()
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "管道拷贝中断：${error.message}")
            runCatching { writeEnd.close() }
        }
        return written
    }

    // ------------------------------------------------------------ Intent / 分段

    private fun baseIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // §0.4：必须显式中文 + 优先离线；不给系统「顺手走云端」的机会。
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LOCALE)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

    private fun segmentIntent(readEnd: ParcelFileDescriptor): Intent = baseIntent().apply {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, WavMath.SAMPLE_RATE)
    }

    /**
     * ≤45 s 单段；超过则按 35 s 切，尾段不足 5 s 时并入上一段。
     * 返回值是「数据段内」的字节区间，`pumpData` 负责加 44 字节头偏移。
     */
    internal fun segmentRanges(dataBytes: Long, durationMs: Long): List<LongRange> {
        if (durationMs <= SINGLE_SEGMENT_LIMIT_MS ||
            dataBytes <= WavMath.bytesForMs(SINGLE_SEGMENT_LIMIT_MS)
        ) {
            return listOf(0L until dataBytes)
        }
        val maxBytes = WavMath.bytesForMs(SEGMENT_MS)
        val tailBytes = WavMath.bytesForMs(MIN_TAIL_MS)
        val ranges = ArrayList<LongRange>()
        var start = 0L
        while (start < dataBytes) {
            var end = minOf(start + maxBytes, dataBytes)
            if (dataBytes - end < tailBytes) end = dataBytes
            ranges += start until end
            start = end
        }
        return ranges
    }

    private companion object {
        const val TAG = "DreamStt"
        const val LOCALE = "zh-CN"

        /** 单段上限（§0.3：≤45 s 不切）。 */
        const val SINGLE_SEGMENT_LIMIT_MS = 45_000L

        /** 长录音按 35 s 切段（落在 30–45 s 区间内）。 */
        const val SEGMENT_MS = 35_000L

        /** 尾段短于 5 s 就并入上一段，避免一个几乎没内容的片段单独识别。 */
        const val MIN_TAIL_MS = 5_000L

        /** 每段预算：至少 60 s，长段按 2 倍段长。 */
        const val MIN_SEGMENT_TIMEOUT_MS = 60_000L

        /** 单段硬上限；超时按失败收口，UI 才能给出「手动补字」。 */
        const val MAX_SEGMENT_TIMEOUT_MS = 90_000L

        const val PROBE_TIMEOUT_MS = 5_000L
        const val PIPE_BUFFER_BYTES = 16 * 1024
    }
}

/**
 * §0.1 的四张语言表 → 四档可用性。纯函数，单测不依赖 Android。
 *
 * `installed` 里有中文才算真的能离线转；`supported`/`pending` 只说明「可以下载」；
 * **`online` 命中绝不能报 Available**——那会把「必须联网」伪装成本机可用。
 */
internal fun mapSupport(
    installed: List<String>,
    supported: List<String>,
    pending: List<String>,
    online: List<String>,
    locale: String = "zh-CN",
): SttAvailability {
    val languagePrefix = locale.substringBefore('-').lowercase()
    fun List<String>.hasChinese(): Boolean = any { tag ->
        tag.trim().lowercase().startsWith(languagePrefix)
    }
    return when {
        installed.hasChinese() -> SttAvailability.Available
        supported.hasChinese() || pending.hasChinese() -> SttAvailability.NeedsDownload(locale)
        online.hasChinese() -> SttAvailability.Unavailable("只有在线识别")
        else -> SttAvailability.Unavailable("本机没有中文离线识别")
    }
}
