package com.dreamkeeper.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.dreamkeeper.ui.common.DreamCard
import com.dreamkeeper.R
import com.dreamkeeper.ui.common.EmptyState
import androidx.compose.ui.res.painterResource
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.Paper

/**
 * 当日列表（设计 §11 第二层，D35）：日历点有梦的格子进这里，**不是**直接进详情。
 *
 * 这里是一天，不是一个月：没有月份切换，没有说梦钮，也没有底部栏；
 * 文案只留日期标题、卡片里的内容、空态两行。
 */
@Composable
fun DayScreen(
    vm: DayViewModel,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(Night)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onBack() }
                    .semantics { contentDescription = "返回日历" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = Paper)
            }
            Text(
                text = state.title,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                color = Paper,
            )
            // 与左箭头等宽的占位，让标题真的居中
            Spacer(modifier = Modifier.size(48.dp))
        }

        if (!state.loaded) {
            // 首帧：还没读到库。留白比「这天没有梦」准确（终审 P1-1）
            Box(modifier = Modifier.fillMaxSize())
        } else if (state.items.isEmpty()) {
            // 空态：插画 + 两行，不给箭头——箭头暗示「这里还有一层」，但这里就是尽头
            EmptyState(
                illustration = painterResource(R.drawable.illus_day_empty),
                title = "这天没有梦",
                hint = "回日历页看看别的日子",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                // 卡间距 12dp 只在这里给；DreamCard 自己不叠外边距
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.dream.id }) { item ->
                    DreamCard(
                        dream = item.dream,
                        tags = item.tags,
                        onClick = { onOpen(item.dream.id) },
                    )
                }
            }
        }
    }
}
