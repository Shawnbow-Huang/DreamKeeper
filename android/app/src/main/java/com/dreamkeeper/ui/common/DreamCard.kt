package com.dreamkeeper.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.Tag
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.NightRaised
import com.dreamkeeper.ui.theme.Paper

/**
 * 梦卡片：标题 / 正文首句 / 标签三行。Task 7 的列表与时间轴直接复用，不许再写一份。
 *
 * 卡片**自己不叠外边距**：12dp 的卡间距由调用方的
 * `LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp))` 给；
 * 卡里再写 12dp 会变成 24dp，而且两处样式从此各改各的。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DreamCard(dream: Dream, tags: List<Tag>, onClick: () -> Unit) {
    val body = firstSentence(dream.faithfulText ?: dream.rawTranscript)
    val heading = dream.title?.takeIf { it.isNotBlank() } ?: body

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NightRaised)
            .clickable { onClick() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (heading.isNotBlank()) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleLarge,
                color = Paper,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tags.isNotEmpty()) {
            // 没有标签就整行不画，也不留空白（brief Step 5）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(NightRaised)
                            .border(1.dp, Line, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 正文首句：取到第一个 `。` / `！` / `？` / `；`（含标点），否则满 40 字截断并补 `…`。
 *
 * 取 `faithfulText`（原样整理稿）而不是 `rawTranscript`：整理稿才是用户会读的那一版。
 * Task 7 复用这个函数，不许各写一份。
 */
fun firstSentence(text: String?): String {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""

    val stop = trimmed.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '；' }
    // 标点落在第 40 个字之后时，按「满 40 字」截断，不要为了一个迟到的句号放长整段
    if (stop in 0 until MAX_SENTENCE_CHARS) return trimmed.substring(0, stop + 1)
    if (trimmed.length <= MAX_SENTENCE_CHARS) return trimmed
    return trimmed.take(MAX_SENTENCE_CHARS) + "…"
}

/** 首句上限：40 字（含标点时另算），超出补省略号 */
private const val MAX_SENTENCE_CHARS = 40
