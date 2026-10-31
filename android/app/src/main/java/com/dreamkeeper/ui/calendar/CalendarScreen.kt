package com.dreamkeeper.ui.calendar

import com.dreamkeeper.ui.theme.DreamColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.ui.theme.DreamMotion
import java.time.LocalDate

/**
 * 日历首屏（设计 §11 第一层）。
 *
 * 屏上除底部栏的「日历 / 全部」外只有 5 类文案：月份标题、`共 N 天有梦`、
 * 星期表头、搜索框里的「搜索梦里的词」、`说梦`。不加任何操作提示——深夜界面里，
 * 说明文字比空白更吵。
 *
 * `onRecord` 仍是 Plan 2 的占位（红钮先有入口不接实现）。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CalendarScreen(
    vm: CalendarViewModel,
    onDayClick: (LocalDate) -> Unit,
    onSearch: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    Box(modifier = Modifier.fillMaxSize().background(DreamColors.night)) {
        // 月份水印：把原来 39% 屏高的连续真空，变成"一张纸上印着的月份"。
        // 5% 不透明度、140sp 宋体、贴右边缘出血——可读但不抢戏。
        Text(
            text = state.month.monthValue.toString().padStart(2, '0'),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 200.sp, lineHeight = 200.sp),
            color = DreamColors.paper.copy(alpha = 0.055f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 96.dp, end = 8.dp),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // 头部与星期表头固定，只有日期网格滚动（brief Step 5）
            AnimatedContent(
                targetState = state,
                contentKey = { it.month },
                transitionSpec = {
                    fadeIn(tween(DreamMotion.Quick)) togetherWith fadeOut(tween(DreamMotion.Quick))
                },
                label = "month",
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { s ->
                Column(modifier = Modifier.fillMaxSize()) {
                    MonthHeader(
                        title = s.title,
                        daysWithDreams = s.daysWithDreams.size,
                        onPrev = vm::prevMonth,
                        onNext = vm::nextMonth,
                        onToday = vm::goToday,
                        onSettings = onSettings,
                    )
                    SearchEntry(onSearch = onSearch)
                    WeekdayHeader()

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        // bottom = 72（钮）+ 24（底距）+ 16（呼吸）：给底部大钮留出滚动余量，
                        // 否则当月最后一行永远被钮盖住，而且滚到底也还是盖住
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 112.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(s.grid.size) { index ->
                            val date = s.grid[index]
                            if (date == null) {
                                // 月初/月末补空格：不画数字、不画点、不进读屏
                                Spacer(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                            } else {
                                DayCell(
                                    date = date,
                                    count = s.daysWithDreams[date],
                                    isToday = date == today,
                                    onClick = onDayClick,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 说梦大钮叠在内容之上（Box 后者盖前者），所以网格必须自己留出 112dp
        // 说梦是"光"：外层径向渐变做晕（不是 shadow——矩形阴影会在近黑底上露边），
        // 内层是实心琥珀 + 深墨字；不再用砖红填充 + 亮环补丁。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(132.dp)
                .background(
                    Brush.radialGradient(
                        0.0f to DreamColors.ember.copy(alpha = 0.30f),
                        0.55f to DreamColors.ember.copy(alpha = 0.10f),
                        1.0f to Color.Transparent,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(DreamColors.ember, CircleShape)
                .clickable { onRecord() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "说梦",
                color = DreamColors.onLamp,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        }
    }
}

@Composable
private fun MonthHeader(
    title: String,
    daysWithDreams: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowButton("‹", "上个月", onPrev)
        Column(
            // 点月标题 = 回今天：没有这个入口，goToday() 在界面上等于不存在（brief Step 5）
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable { onToday() }
                .semantics { contentDescription = "回到今天" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = DreamColors.paper)
            Text(
                "共 $daysWithDreams 天有梦",
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.inkDim,
            )
        }
        ArrowButton("›", "下个月", onNext)
        SettingsEntry(onSettings)
    }
}

/**
 * 设置入口：48dp 文字盒，照 [ArrowButton] 的写法（不引 material-icons）。
 *
 * 放在月头行最右侧（第 4 个 48dp 位），是整个应用里唯一的设置入口。
 * `contentDescription = "设置"` 既是读屏标签，也是证据脚本定位它的唯一锚点；
 * 盒子写死 48dp（420dpi 下 126px），保证 uiautomator 量到的 bounds 不小于触控下限。
 */
@Composable
private fun SettingsEntry(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable { onClick() }
            .semantics { contentDescription = "设置" },
        contentAlignment = Alignment.Center,
    ) {
        Text("设置", style = MaterialTheme.typography.labelMedium, color = DreamColors.paper)
    }
}

/**
 * 搜索入口（D39 / brief 0.6）：月头下面一条 48dp 的假输入框。
 *
 * 不用 `Icons.Outlined.Search`——仓库没有 material-icons 依赖，`Icons.*` 编译不过。
 * `clickable` 包在 48dp 高的整行容器上，而不是只包住那行 15sp 的文案；整行点哪都进搜索页。
 * `contentDescription = "搜索"` 既是读屏标签，也是证据脚本定位这个入口的唯一锚点。
 */
@Composable
private fun SearchEntry(onSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(DreamColors.nightRaised)
            .border(1.dp, DreamColors.line, RoundedCornerShape(999.dp))
            .clickable { onSearch() }
            .semantics { contentDescription = "搜索" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "搜索梦里的词",
            style = MaterialTheme.typography.bodyMedium,
            color = DreamColors.inkFaint,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArrowButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable { onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = DreamColors.paper)
    }
}

@Composable
private fun WeekdayHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
            Text(
                text = day,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.inkFaint,
            )
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    count: Int?,
    isToday: Boolean,
    onClick: (LocalDate) -> Unit,
) {
    val hasDreams = (count ?: 0) > 0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 360dp 窄屏：360 − 左右各 8dp 内边距 = 344dp，÷ 7 列 = 49.1dp/格 ≥ 48dp。
            // 所以写 heightIn(min = 48.dp) 而不是 height(48.dp)——49.1 被压成 48 会让格子变形
            .heightIn(min = 48.dp)
            .semantics { contentDescription = dayDescription(date, count) }
            // 空日点了什么都不发生：添加只有一个入口，就是红钮
            .then(if (hasDreams) Modifier.clickable { onClick(date) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                // 今天用 DreamColors.ember 描边，不用 DreamColors.line：DreamColors.line 在 DreamColors.night 上只有 1.34:1，等于看不见
                .then(if (isToday) Modifier.border(1.5.dp, DreamColors.ember, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 日期是主内容：有梦的日子用正文色 + 600 字重，没梦的退到注脚灰。
                // 旧版把日期压在次级灰、再用 4dp 琥珀点表示"存在"，等于让标题比内容更亮。
                Text(
                    text = "${date.dayOfMonth}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (hasDreams) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (hasDreams) DreamColors.paper else DreamColors.inkFaint,
                )
            }
        }
    }
}

/** 读屏要念出「几条梦记」——这也是 daysWithDreams 必须是 Map 而不是 Set 的原因（D46） */
private fun dayDescription(date: LocalDate, count: Int?): String =
    if (count == null || count == 0) "${date.year}年${date.monthValue}月${date.dayOfMonth}日，无记录"
    else "${date.year}年${date.monthValue}月${date.dayOfMonth}日，${count} 条梦记"
