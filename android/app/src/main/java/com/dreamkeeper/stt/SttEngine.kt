package com.dreamkeeper.stt

import java.io.File

/**
 * 本机转写的可用档位（Task 7 / §0.1）。
 *
 * 四档而不是布尔值：`Available` 只代表「API 33+ 文件转写真的可用」；
 * 「要下载中文离线包」与「只有在线识别」是两种完全不同的失败，用户看到的文案也不同。
 * `AvailableButNoFileInput` 留给 API 31–32：有离线识别器，但没有 `EXTRA_AUDIO_SOURCE`。
 */
sealed interface SttAvailability {
    /** API 33+，本机已装中文离线包，且 `EXTRA_AUDIO_SOURCE` 可用。 */
    data object Available : SttAvailability

    /** 有离线识别器，但系统版本不支持把音频文件喂进去（API 31–32）。 */
    data object AvailableButNoFileInput : SttAvailability

    /** 中文在 supported/pending 表里：装完离线包才可能转写。 */
    data class NeedsDownload(val locale: String) : SttAvailability

    /** 明确不可用；reason 是一句能直接给用户看的中文。 */
    data class Unavailable(val reason: String) : SttAvailability
}

/** 一次转写的结果。`Failed` 只在真的没拿到可用文字时出现，绝不伪造 `Ok`。 */
sealed interface SttResult {
    data class Ok(val text: String) : SttResult
    data class Failed(val reason: String) : SttResult
}

/**
 * 系统语音识别器的窄接口。
 *
 * 只暴露「探测」与「一次性转写」：调用方（[TranscriptWriter]）不关心
 * `SpeechRecognizer` 的线程、管道与超时，全部由 [SystemSttEngine] 留在内部。
 */
interface SttEngine {
    /** 按进程缓存一次（实现方负责），不许每次转写都探测。 */
    suspend fun availability(): SttAvailability

    /** 只读 WAV 的 `[44, EOF)`，一次性转写；失败必须返回 [SttResult.Failed]。 */
    suspend fun transcribe(file: File): SttResult
}

/**
 * §0.5：终态文本 trim 后非空、且至少含一个「字母」（汉字在 Unicode 里也是 Letter）。
 *
 * 纯标点、纯数字、纯空白都不算成功——那更可能是识别器的噪声，写成 OK 就是伪造。
 */
internal fun isMeaningfulTranscript(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isNotEmpty() && trimmed.any { it.isLetter() }
}
