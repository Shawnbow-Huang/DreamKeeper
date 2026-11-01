package com.dreamkeeper.stt

import java.io.File

/**
 * 「内置模型优先，系统识别器兜底」的选择器。
 *
 * 为什么不是二选一写死：
 * · 内置模型是随包发的，正常永远可用——它才是主路径；
 * · 但 APK 可能被裁剪（有的应用商店会按 ABI 拆包）、或老设备装的是 armeabi 以外的架构，
 *   这时系统识别器（如果厂商真装了离线中文包）仍可救场；
 * · 两边都不可用时，把**内置**的那句原因抛给用户：「内置模型缺失，请重装」比
 *   「本机没有离线识别能力」更接近用户能采取的动作。
 *
 * 探测结果按进程缓存：内置引擎的探测是查资产、系统引擎自己也会缓存，
 * 这里只在第一次问的时候决定走哪条路。
 */
class PreferredSttEngine(
    private val bundled: SttEngine,
    private val system: SttEngine,
) : SttEngine {

    @Volatile
    private var chosen: SttEngine? = null

    override suspend fun availability(): SttAvailability {
        val bundledState = bundled.availability()
        if (bundledState is SttAvailability.Available) {
            chosen = bundled
            return bundledState
        }
        val systemState = system.availability()
        if (systemState is SttAvailability.Available) {
            chosen = system
            return systemState
        }
        return bundledState
    }

    override suspend fun transcribe(file: File): SttResult {
        val target = chosen ?: run {
            availability()
            chosen
        } ?: return SttResult.Failed("没有可用的语音识别引擎")
        return target.transcribe(file)
    }
}
