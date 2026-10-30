package com.dreamkeeper.ui.nav

/** 说梦的四个入口：首页大钮 / 锁屏小组件 / 通知 / 快捷方式（Plan 1 只用到 HOME_BUTTON） */
enum class RecordEntry { HOME_BUTTON, LOCK_TILE, NOTIFICATION, SHORTCUT }

sealed interface DreamRoute {
    val route: String

    data object Calendar : DreamRoute { override val route = "calendar" }
    data object Library : DreamRoute { override val route = "library" }

    /**
     * 当日列表（设计 §11 的中间层，D35）：`dateIso` 形如 `2026-11-08`。
     * 参数就存在 route 里、不放进 nav arguments 的 Bundle 里也没关系——
     * `composable("day/{dateIso}")` 从 `arguments` 取到的就是这个字符串，解析时用 `LocalDate.parse`。
     */
    data class Day(val dateIso: String) : DreamRoute { override val route = "day/$dateIso" }

    data object Search : DreamRoute { override val route = "search" }
    data object Tags : DreamRoute { override val route = "tags" }

    /** 设置页：只在日历顶栏有入口，**不是**底部栏的第三个 tab（见 DreamKeeperRoot 的 showBottomBar） */
    data object Settings : DreamRoute { override val route = "settings" }
    data class Detail(val dreamId: String) : DreamRoute { override val route = "detail/$dreamId" }
    data class Record(val entry: RecordEntry) : DreamRoute { override val route = "record/${entry.name}" }

    /** 追加录音（Plan 4 §0.4）：复用录音页往既有 WAV 尾部追加，不新建梦。 */
    data class Append(val dreamId: String) : DreamRoute { override val route = "append/$dreamId" }
    data class FollowUp(val dreamId: String) : DreamRoute { override val route = "followup/$dreamId" }
}
