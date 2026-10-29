package com.dreamkeeper.ui.library

import android.content.Context

/**
 * 「全部」页的布局模式：时间轴 / 卡片流。
 *
 * 存的是**枚举名**（`TIMELINE` / `CARDS`），不是序号：以后插入第三档、或者调整枚举顺序，
 * 落盘的旧值还是同一个意思；存序号的话，插入一档就会让所有老用户的偏好静默错位。
 */
enum class LibraryLayout { TIMELINE, CARDS }

/** 布局偏好的读写口。生产实现见 [SharedPreferencesLayoutPrefs]，测试用 `FakeLayoutPrefs` */
interface LayoutPrefs { var layout: LibraryLayout }

/**
 * 生产实现：`SharedPreferences` 同步读写。
 *
 * 为什么不用 DataStore：`LayoutPrefs.layout` 是同步属性（ViewModel 构造时就要拿到初值），
 * 而 DataStore 的读是 suspend 的——上一版为此在 init 里塞了 `runBlocking(100ms)`，
 * 一个布局偏好不值得让主线程冒这个险。`SharedPreferences` 首次读盘由系统异步预加载，
 * 这里的 `getString` 命中内存缓存，代价可忽略。
 */
class SharedPreferencesLayoutPrefs(context: Context) : LayoutPrefs {

    private val prefs = context.applicationContext
        .getSharedPreferences("layout_prefs", Context.MODE_PRIVATE)

    override var layout: LibraryLayout
        get() = LibraryLayout.entries.firstOrNull { it.name == prefs.getString(KEY_LAYOUT, null) }
            ?: LibraryLayout.TIMELINE
        set(value) {
            // commit() 同步落盘。验收里有「杀进程重进仍是卡片视图」，而 apply() 是异步写：
            // 刚切完就 am force-stop，写入可能还没落盘，会偶发回退成时间轴。
            prefs.edit().putString(KEY_LAYOUT, value.name).commit()
        }

    private companion object { const val KEY_LAYOUT = "library_layout" }
}
