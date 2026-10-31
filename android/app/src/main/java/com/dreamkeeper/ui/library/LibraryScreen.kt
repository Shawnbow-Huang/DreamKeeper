package com.dreamkeeper.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.ui.common.DreamCard
import com.dreamkeeper.ui.common.cardText
import com.dreamkeeper.ui.common.firstSentence
import com.dreamkeeper.ui.day.DayViewModel
import com.dreamkeeper.R
import com.dreamkeeper.ui.common.EmptyState
import androidx.compose.ui.res.painterResource
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.EmberDeep
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.Paper
import com.dreamkeeper.ui.theme.PaperLit
import com.dreamkeeper.ui.theme.PaperSurface

/** 分段控件总宽：两段各 76dp。见 [LayoutSwitch] 为什么不用「无界宽度 + weight」 */
private val SwitchWidth = 152.dp

/** 时间轴左侧轨道宽：竖线在正中，圆点也居中，两者自然对齐（D60） */
private val RailWidth = 20.dp

/** 时间轴每条下方留 20dp：视觉上是「条目之间 20dp」，但这段留白里有竖线，所以线是连续的 */
private val EntryGap = 20.dp

/**
 * 「全部」页（设计 §11 第一层，D62）：全部梦境的时间轴 / 卡片流两种排布。
 *
 * 只有顶部一条（标题 + 分段控件），没有搜索图标——`DreamRoute.Search` 现在还是空屏，
 * 摆一个点了没反应的放大镜比没有更糟（D61）。
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpen: (String) -> Unit,
    onOpenTags: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(Night)) {
        LibraryHeader(layout = state.layout, onLayout = vm::setLayout, onOpenTags = onOpenTags)

        if (!state.loaded) {
            // 首帧：还没读到库。留白比「还没有梦」准确（终审 P1-2）
            Box(modifier = Modifier.fillMaxSize())
        } else if (state.items.isEmpty()) {
            EmptyLibrary()
        } else {
            when (state.layout) {
                LibraryLayout.TIMELINE -> Timeline(entries = state.items, onOpen = onOpen)
                LibraryLayout.CARDS -> CardStream(entries = state.items, onOpen = onOpen)
            }
        }
    }
}

/** 顶部：左边标题，右边**一个**分段控件 + 「标签」文字按钮（brief §0.1 的入口） */
@Composable
private fun LibraryHeader(
    layout: LibraryLayout,
    onLayout: (LibraryLayout) -> Unit,
    onOpenTags: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "全部梦",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = Paper,
        )
        // 文字按钮而不是图标：这一版没有图标资源，摆一个来路不明的放大镜/齿轮更像故障
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .clickable { onOpenTags() }
                .semantics { contentDescription = "标签" }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "标签", style = MaterialTheme.typography.bodyMedium, color = InkDim)
        }
        LayoutSwitch(current = layout, onLayout = onLayout)
    }
}

/**
 * 自绘分段控件：两段等宽、整高 48dp、1dp `Line` 描边 + 999dp 圆角。
 *
 * 不用 M3 `SegmentedButton`：它默认 40dp 高，49dp 的触控下限要再补 `heightIn`；
 * 而它的选中态是自绘的 `checked` 语义，样式也没法只改底色。
 *
 * 为什么给总宽 `SwitchWidth` 而不只是 `weight(1f)`：`Row` 里的 `weight` 只有在**有界宽度**下
 * 才能平分——不指定宽度时，这个控件会被父 `Row` 按整行宽测量，两个 weight 段各吃一半，
 * 把左边的「全部梦」挤成 0 宽。定宽之后 weight 才回到「两段各 76dp」的本意。
 */
@Composable
private fun LayoutSwitch(current: LibraryLayout, onLayout: (LibraryLayout) -> Unit) {
    Row(
        modifier = Modifier
            .width(SwitchWidth)
            .heightIn(min = 48.dp)
            // IntrinsicSize.Min：让子项拿到**固定**高度，不然 weight 段的背景下沿只到文字底，
            // 描边里会露出 4dp 的透明带（fillMaxHeight 在无界高度下等于 wrap）
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Line, RoundedCornerShape(999.dp)),
    ) {
        SwitchSegment("时间轴", LibraryLayout.TIMELINE, current, onLayout)
        SwitchSegment("卡片", LibraryLayout.CARDS, current, onLayout)
    }
}

@Composable
private fun RowScope.SwitchSegment(
    label: String,
    value: LibraryLayout,
    current: LibraryLayout,
    onLayout: (LibraryLayout) -> Unit,
) {
    val selected = current == value
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // 未选中就是**没有背景**，不是透明色叠一层：叠加会盖掉圆角外侧的描边
            .then(if (selected) Modifier.background(PaperLit) else Modifier)
            .clickable { onLayout(value) }
            .semantics {
                contentDescription = "切换到$label"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Paper else InkFaint,
            maxLines = 1,
        )
    }
}

/**
 * 时间轴：左侧 1.5dp `Ember` 竖线 + 每条的 `Ember` 圆点。
 *
 * 竖线用 `Ember` 而不是 `Line #232A36`：后者在 Night 上只有 1.34:1，截图里等于没有线，
 * 「竖线与圆点对齐」就没有可判对象（D60）。
 */
@Composable
private fun Timeline(entries: List<LibraryItem>, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        items(entries, key = { it.dream.id }) { item ->
            TimelineEntry(item = item, onClick = { onOpen(item.dream.id) })
        }
    }
}

@Composable
private fun TimelineEntry(item: LibraryItem, onClick: () -> Unit) {
    val dream = item.dream
    // D214：时间轴和卡片必须走同一个文案分派，否则卡片修好了、时间轴还是一条只有日期没内容的空行
    val heading = cardText(dream.title, previewText(item.finalNovelText, dream)).heading

    // 2026-11-01 视觉改版：时间轴从"一根琥珀竖线 + 灰字"改成"一叠纸"。
    // 每条梦是一张暖纸卡（PaperSurface），日期是注脚、正文是宋体 21sp；
    // 竖线圆点全部去掉——存在感由纸面与字重承担，不再靠强调色刷屏。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PaperSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column {
            Text(
                text = DayViewModel.titleOf(dream.date),
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = heading,
                style = MaterialTheme.typography.titleLarge,
                color = Paper,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 卡片流：一行日期 + 一张 [DreamCard]。
 *
 * 卡间距 12dp 只由 `verticalArrangement` 给——`DreamCard` 自己不叠外边距，
 * 在这里再写一遍就会变成 24dp，两处样式从此各改各的。
 */
@Composable
private fun CardStream(entries: List<LibraryItem>, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.dream.id }) { item ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = DayViewModel.titleOf(item.dream.date),
                    modifier = Modifier.padding(bottom = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
                DreamCard(
                    dream = item.dream,
                    tags = item.tags,
                    previewText = previewText(item.finalNovelText, item.dream),
                    onClick = { onOpen(item.dream.id) },
                )
            }
        }
    }
}

/**
 * 空态：两行，**不给箭头**（D38）。
 *
 * 这个页面没有说梦钮（唯一的说梦钮在日历页），指向一个不存在的按钮的箭头是自相矛盾；
 * 原稿「按下面那个钮」也是错的，下面只有底部栏。debug 构建恒播种，这屏本轮进不了截图（D59）。
 */
@Composable
private fun EmptyLibrary() {
    EmptyState(
        illustration = painterResource(R.drawable.illus_library_empty),
        title = "还没有梦",
        hint = "去日历页，按那个橙色的钮",
    )
}
