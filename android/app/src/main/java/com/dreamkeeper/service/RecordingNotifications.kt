package com.dreamkeeper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dreamkeeper.MainActivity
import com.dreamkeeper.R
import com.dreamkeeper.ui.nav.RecordEntry

/**
 * 前台服务的常驻通知：建渠道、拼内容、挂两个 PendingIntent。
 *
 * 隐私：录音中锁屏只显示「正在录梦」，不显示梦的内容（VISIBILITY_PRIVATE），
 * 通知栏内容也不带 dreamId —— 屏幕在锁屏上被别人看到不该泄露录音主题。
 */
object RecordingNotifications {

    /** 渠道一经创建就不能改重要性；改文案要换 id（本 Task 不改）。 */
    const val CHANNEL_ID = "recording"

    private const val CHANNEL_NAME = "录音中"
    private const val CHANNEL_DESCRIPTION = "按住说梦期间常驻显示，松手后消失"

    private const val REQUEST_CONTENT = 1
    private const val REQUEST_STOP = 2

    /** 幂等：渠道已存在时什么都不做（重复 START 不会弹第二条渠道）。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 拼通知。调用前必须先 [ensureChannel]，否则 26+ 上通知会被系统直接丢掉。
     *
     * - 点本体 → [MainActivity] + `entry=NOTIFICATION`（D103：详情页没有 deep link，
     *   这条 extra 由 Task 4 的 `RecordViewModel.parseEntry` 消费）
     * - 「结束」→ `PendingIntent.getService` 打到本服务的 [RecordingCommands.ACTION_STOP]
     */
    fun build(context: Context): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                putExtra(RecordingService.EXTRA_ENTRY, RecordEntry.NOTIFICATION.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在录梦")
            .setContentText("松开就结束；点「结束」立刻停")
            .setContentIntent(contentIntent)
            .addAction(0, "结束", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
