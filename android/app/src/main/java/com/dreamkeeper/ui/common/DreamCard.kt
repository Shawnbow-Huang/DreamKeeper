package com.dreamkeeper.ui.common

import com.dreamkeeper.ui.theme.DreamColors
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

/**
 * 梦卡片：标题 / 正文首句 / 标签三行。Task 7 的列表与时间轴直接复用，不许再写一份。
 *
 * 卡片**自己不叠外边距**：12dp 的卡间距由调用方的
 * `LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp))` 给；
 * 卡里再写 12dp 会变成 24dp，而且两处样式从此各改各的。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DreamCard(
    dream: Dream,
    tags: List<Tag>,
    onClick: () -> Unit,
    previewText: String? = null,
) {
    val texts = cardText(dream.title, previewText ?: dream.faithfulText ?: dream.rawTranscript)
    val heading = texts.heading
    val body = texts.body

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DreamColors.nightRaised)
            .clickable { onClick() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (heading.isNotBlank()) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleLarge,
                color = DreamColors.paper,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = DreamColors.inkDim,
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
                        color = DreamColors.inkDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(DreamColors.nightRaised)
                            .border(1.dp, DreamColors.line, RoundedCornerShape(50))
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

/** 卡片两行各放什么文字（D63）。`heading` 用标题字重，`body` 用次级字重。 */
data class CardText(val heading: String, val body: String)

/**
 * 决定卡片的两行文字。
 *
 * 有标题：标题当 heading，正文首句当 body。
 * 没标题：正文首句当 heading，**剩下的部分**才是 body——不能把首句同时塞进两行，
 * 那会在卡片上把同一句话上下各画一遍（任务 6/7 的截图里就是这样）。
 */
fun cardText(title: String?, text: String?): CardText {
    val first = firstSentence(text)
    val realTitle = title?.trim().orEmpty()
    if (realTitle.isNotEmpty()) {
        return CardText(realTitle, first.ifBlank { NO_TEXT_HINT })
    }
    if (first.isNotBlank()) return CardText(first, restBelowFirstSentence(text))
    // 既没标题也没正文（录完但转写失败/还没转写）：不许画一张没有内容的空卡（D214）
    return CardText(NO_TEXT_HEADING, NO_TEXT_HINT)
}

/** 既没标题也没正文时的占位标题（D214） */
internal const val NO_TEXT_HEADING = "未转写"

/** 没有文字时的副行文案（D214）：卡片得能被认出来是一条梦，不能是一条空白圆角条 */
internal const val NO_TEXT_HINT = "这条梦还没有文字"

/**
 * 去掉首句之后剩下的正文；太短就当作没有（不足 [MIN_REST_CHARS] 字）。
 *
 * 首句是「满 40 字 + …」的截断形态时直接返回空：那是截断不是句读，
 * 把被截断的尾巴再画一遍比不画更糟。
 */
fun restBelowFirstSentence(text: String?): String {
    val trimmed = text?.trim().orEmpty()
    val first = firstSentence(trimmed)
    if (first.isEmpty() || first.endsWith("…")) return ""
    val rest = trimmed.removePrefix(first).trim()
    return if (rest.length >= MIN_REST_CHARS) rest else ""
}

/** 剩下的正文短于这个长度就不单独占一行 */
private const val MIN_REST_CHARS = 4
