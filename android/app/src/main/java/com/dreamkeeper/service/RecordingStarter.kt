package com.dreamkeeper.service

import android.content.Context
import android.content.Intent

/**
 * Task 4.2：录音命令的可注入入口。
 *
 * ViewModel 只发三条命令，不持有也不查询 Service 实例；「录了多久 / 文件在哪 / 有没有失败」
 * 全部由 [RecordingSession] 这一条状态流回答。测试只 fake 这个接口。
 */
interface RecordingStarter {
    fun start(dreamId: String)
    fun stop()
    fun cancel()
}

/**
 * 真实现：只走 startForegroundService(ACTION_START) / startService(ACTION_STOP|CANCEL)。
 *
 * 必须传 Activity context（见 MainActivity）：Android 12+ 从后台启动前台服务会抛
 * ForegroundServiceStartNotAllowedException，而这里的调用点永远是用户按住录音钮、
 * 界面可见的时刻，所以 Activity context 既安全又能拿到启动豁免。
 */
class AndroidRecordingStarter(private val context: Context) : RecordingStarter {

    override fun start(dreamId: String) {
        context.startForegroundService(
            intent(RecordingService.ACTION_START)
                .putExtra(RecordingService.EXTRA_DREAM_ID, dreamId),
        )
    }

    override fun stop() {
        context.startService(intent(RecordingService.ACTION_STOP))
    }

    override fun cancel() {
        context.startService(intent(RecordingService.ACTION_CANCEL))
    }

    private fun intent(action: String): Intent =
        Intent(context, RecordingService::class.java).setAction(action)
}
