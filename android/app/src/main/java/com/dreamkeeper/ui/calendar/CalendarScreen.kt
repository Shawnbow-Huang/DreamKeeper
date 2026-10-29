package com.dreamkeeper.ui.calendar

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.ui.theme.DreamMotion
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.EmberDeep
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.Paper
import java.time.LocalDate

/**
 * 日历首屏（设计 §11 第一层）。
 *
 * 屏上除底部栏的「日历 / 全部」外只有 4 类文案：月份标题、`共 N 天有梦`、
 * 星期表头、`说梦`。不加任何操作提示——深夜界面里，说明文字比空白更吵。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CalendarScreen(
    vm: CalendarViewModel,
    onDayClick: (LocalDate) -> Unit,
    onSearch: () -> Unit,
    onRecord: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    Box(modifier = Modifier.fillMaxSize().background(Night)) {
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
                    )
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
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .size(72.dp)
                .border(1.5.dp, Ember, CircleShape)   // 外环：EmberDeep 只有 2.57:1，靠 Ember 边界把按钮「描」出来
                .background(EmberDeep, CircleShape)
                .clickable { onRecord() },
            contentAlignment = Alignment.Center,
        ) {
            Text("说梦", color = Paper, style = MaterialTheme.typography.titleMedium)
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
            Text(title, style = MaterialTheme.typography.titleLarge, color = Paper)
            Text(
                "共 $daysWithDreams 天有梦",
                style = MaterialTheme.typography.labelSmall,
                color = InkDim,
            )
        }
        ArrowButton("›", "下个月", onNext)
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
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = Paper)
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
                color = InkFaint,
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
                // 今天用 Ember 描边，不用 Line：Line 在 Night 上只有 1.34:1，等于看不见
                .then(if (isToday) Modifier.border(1.5.dp, Ember, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${date.dayOfMonth}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasDreams) Paper else InkDim,
                )
                if (hasDreams) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(4.dp)
                            .background(Ember, CircleShape),
                    )
                }
            }
        }
    }
}

/** 读屏要念出「几条梦记」——这也是 daysWithDreams 必须是 Map 而不是 Set 的原因（D46） */
private fun dayDescription(date: LocalDate, count: Int?): String =
    if (count == null || count == 0) "${date.year}年${date.monthValue}月${date.dayOfMonth}日，无记录"
    else "${date.year}年${date.monthValue}月${date.dayOfMonth}日，${count} 条梦记"
