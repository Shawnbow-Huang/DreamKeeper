package com.dreamkeeper.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.ui.common.firstSentence
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.NightRaised
import com.dreamkeeper.ui.theme.Paper

/**
 * 搜索页（设计 §11 的搜索入口，D39 / D67）。
 *
 * 三态必须能一眼分开（brief 0.10）：没输入 → 「搜一个词，梦会自己找回来」；
 * 有输入但没命中 → 「没有找到含「X」的梦」；有结果 → 日期 + 标题或首句，命中词染 `Ember`。
 * 未输入与无结果是两句话——否则一进页面就像搜不到东西。
 *
 * [initialQuery] 只服务于 debug 钩子（`dk_prefill_search`）：`adb shell input text`
 * 打不了中文，靠 intent extra 预填才能拍到真实中文检索。结果仍由 `repo.search` 实查，不写死。
 */
@Composable
fun SearchScreen(
    repo: DreamRepository,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    initialQuery: String = "",
) {
    val vm: SearchViewModel = viewModel { SearchViewModel(repo, initialQuery = initialQuery) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val trimmed = query.trim()

    Column(modifier = Modifier.fillMaxSize().background(Night)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onBack() }
                    .semantics { contentDescription = "返回" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = Paper)
            }
            SearchField(
                value = query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
        }

        when {
            trimmed.isEmpty() -> CenteredHint("搜一个词，梦会自己找回来")

            results.isEmpty() -> CenteredHint("没有找到含「$trimmed」的梦")

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                items(results, key = { it.id }) { dream ->
                    SearchResultRow(dream = dream, rawQuery = trimmed, onOpen = onOpen)
                }
            }
        }
    }
}

/**
 * 假输入框的真身：自绘 `BasicTextField`。
 *
 * 不用 M3 `OutlinedTextField`：它自带 floating label、56dp 高、聚焦时整圈换色，
 * 和这个「一颗胶囊」的夜间界面不是一套东西；这里只要一条 48dp 的胶囊。
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Paper),
        cursorBrush = SolidColor(Ember),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(NightRaised)
                    .border(1.dp, Line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = "搜索梦里的词",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkFaint,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 32.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = InkDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchResultRow(dream: Dream, rawQuery: String, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(dream.id) }
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${dream.date.monthValue}月${dream.date.dayOfMonth}日",
            style = MaterialTheme.typography.labelSmall,
            color = InkFaint,
        )
        Text(
            text = highlight(dreamLine(dream), rawQuery),
            style = MaterialTheme.typography.bodyLarge,
            color = Paper,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 有标题用标题，没有就用正文首句——和列表卡片同一套取舍（Task 7 的 `cardText`） */
private fun dreamLine(dream: Dream): String =
    dream.title?.trim()?.takeIf { it.isNotEmpty() }
        ?: firstSentence(dream.faithfulText ?: dream.rawTranscript)

/**
 * 把命中词染成 `Ember`。
 *
 * 逐词找**下一次最早出现**的位置（不是按词表顺序画），这样「电梯 六楼」不会把
 * 后面的「电梯」漏染；同位置有长短两个词时取长的，避免只亮半个词。
 */
private fun highlight(text: String, rawQuery: String): AnnotatedString {
    val terms = rawQuery.trim()
        .split(Regex("[\\s\\u3000]+"))
        .filter { it.isNotEmpty() }
        .distinct()
    if (terms.isEmpty() || text.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val next = terms.map { text.indexOf(it, cursor) }.filter { it >= 0 }.minOrNull() ?: break
            val term = terms.filter { text.startsWith(it, next) }.maxByOrNull { it.length } ?: break
            append(text.substring(cursor, next))
            withStyle(SpanStyle(color = Ember)) { append(term) }
            cursor = next + term.length
        }
        append(text.substring(cursor))
    }
}
