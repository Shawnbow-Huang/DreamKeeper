package com.dreamkeeper.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 3.2 的唯一被测对象：纯函数 [RecordingCommands.parse]。
 *
 * 这里**不**断言 Service 的任何状态（JVM 单测里 `Service` 跑不了），
 * 只钉「action + dreamId → 命令」这一层映射：非法的输入一律 null，
 * 调用方（`RecordingService.onStartCommand`）拿到 null 只做 `stopSelf()`。
 *
 * action 字符串直接用 [RecordingService] 的常量断言：它们是 PendingIntent /
 * `startForegroundService` 两侧**唯一**的线格式，改一个字符就断链。
 */
class RecordingCommandTest {

    @Test
    fun `start without dream id is invalid`() {
        assertNull(
            "START 缺 dreamId 就是非法命令（否则会落到 files/audio/.wav）",
            RecordingCommands.parse(RecordingService.ACTION_START, null),
        )
    }

    @Test
    fun `start with blank dream id is invalid`() {
        assertNull(RecordingCommands.parse(RecordingService.ACTION_START, "   "))
    }

    @Test
    fun `start with dream id parses`() {
        assertEquals(
            RecordingCommand.Start("d1"),
            RecordingCommands.parse(RecordingService.ACTION_START, "d1"),
        )
    }

    @Test
    fun `stop parses`() {
        assertEquals(RecordingCommand.Stop, RecordingCommands.parse(RecordingService.ACTION_STOP, null))
    }

    @Test
    fun `cancel parses`() {
        assertEquals(RecordingCommand.Cancel, RecordingCommands.parse(RecordingService.ACTION_CANCEL, null))
    }

    @Test
    fun `unknown action is invalid`() {
        assertNull(RecordingCommands.parse("whatever", "d1"))
    }

    @Test
    fun `null action is invalid`() {
        assertNull(RecordingCommands.parse(null, null))
    }

    @Test
    fun `action strings keep their wired values`() {
        assertEquals("com.dreamkeeper.action.START", RecordingService.ACTION_START)
        assertEquals("com.dreamkeeper.action.STOP", RecordingService.ACTION_STOP)
        assertEquals("com.dreamkeeper.action.CANCEL", RecordingService.ACTION_CANCEL)
        assertEquals("dreamId", RecordingService.EXTRA_DREAM_ID)
        assertEquals("entry", RecordingService.EXTRA_ENTRY)
        assertEquals(42, RecordingService.NOTIFICATION_ID)
    }
}
