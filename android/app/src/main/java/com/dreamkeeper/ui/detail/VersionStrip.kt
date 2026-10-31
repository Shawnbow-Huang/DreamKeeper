package com.dreamkeeper.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamkeeper.data.db.NovelMessageEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.Paper
import com.dreamkeeper.ui.theme.NightRaised
import java.util.Locale
import org.json.JSONArray

/**
 * Task 9 §0.6 / D355：版本线条目文案。
 *
 * 有说明时：「vN · 摘要 · 改动 k 处」；没有对应 assistant 消息（历史/种子版本）时只写
 * 「vN · 无说明」，**不带改动数**——版本级改动数只有新格式 `changeLogJson` 能算，
 * 旧种子的正文套正则会把这个 seed 的总改动数安到每个版本上。
 */
/**
 * 成本行文案（T6b）：0 微元（没有真实请求、或历史全失败）返回 null——UI 不显示，
 * 别拿「¥0.0000」占一行。单位是微元（1e-6 元），四位小数能看清一次调用的量级。
 */
fun costLabel(microYuan: Long): String? =
    if (microYuan <= 0L) null else String.format(Locale.US, "累计 ¥%.4f", microYuan / 1_000_000.0)

fun versionLabel(n: Int, summary: String?, changeCount: Int): String {
    val clean = summary?.trim().orEmpty()
    return if (clean.isEmpty()) {
        // D444：生成/改稿版本不一定有 assistant 摘要（GENERATE 版本没有），
        // 但 changeLog 能给出「这一版补了几处」。有数就显示数，别一律「无说明」。
        if (changeCount > 0) "v$n · 改动 $changeCount 处" else "v$n · 无说明"
    } else {
        "v$n · $clean · 改动 $changeCount 处"
    }
}

/** 版本条上的选中态：null = 没挑，按「最新版」显示。 */
fun isSelectedVersion(version: Int, selectedVersion: Int?): Boolean =
    selectedVersion == version

/** `novel_versions.changeLogJson` 里的保留条数；旧行没有这一列值就按 0 算（D355）。 */
fun changeCount(version: NovelVersionEntity): Int {
    val raw = version.changeLogJson ?: return 0
    return try {
        JSONArray(raw).length()
    } catch (e: org.json.JSONException) {
        0
    }
}

/** 版本对应的 assistant 说明（`novel_messages.versionId` 一对一指回该版）。 */
fun summaryFor(version: NovelVersionEntity, messages: List<NovelMessageEntity>): String? =
    messages.lastOrNull { it.versionId == version.id && it.role == "ASSISTANT" }?.content

/**
 * 版本条只出**小说版本**：`promptId == "faithful"` 的行是整理稿（T3 起落版本表），
 * 不属于小说线，混进来会让用户把整理稿选成「当前小说」（D332 的现场症状）。
 */
fun visibleVersions(versions: List<NovelVersionEntity>): List<NovelVersionEntity> =
    versions.filter { it.promptId != "faithful" }

/**
 * 版本条：横向滚动的版本线，点一下选中该版。
 *
 * 不包 `BottomSheet`（§0.8）；列表按新→旧，默认选中 `selectedVersion ?: 最新版`。
 */
@Composable
fun VersionStrip(
    versions: List<NovelVersionEntity>,
    messages: List<NovelMessageEntity>,
    selectedVersion: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    currentVersion: Int? = null,
) {
    val shown = visibleVersions(versions)
    if (shown.isEmpty()) return
    val active = selectedVersion ?: currentVersion ?: shown.first().version
    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(shown, key = { it.id }) { version ->
                VersionChip(
                    label = versionLabel(version.version, summaryFor(version, messages), changeCount(version)),
                    selected = isSelectedVersion(version.version, active),
                    onClick = { onSelect(version.version) },
                )
            }
        }
        Text(
            text = if (selectedVersion != null) {
                "当前 v$active · 已选中"
            } else {
                "当前 v$active"
            },
            color = if (selectedVersion != null) Ember else InkDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun VersionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) Ember.copy(alpha = 0.18f) else NightRaised
    // 可视仍是 36dp 胶囊，点击区扩到 48dp（Plan 5 T6 / A11y：触控目标 ≥48dp）。
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .background(background, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (selected) Paper else InkDim,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
