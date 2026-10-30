package com.dreamkeeper.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.ErrorText
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.NightRaised
import com.dreamkeeper.ui.theme.Paper

/**
 * 六个快捷改稿按钮（D335）——文案逐字固定，测试与截图都按这个清单对。
 */
val REVISE_QUICK_PICKS = listOf(
    "太文绉绉",
    "细节太多",
    "结尾太说教",
    "人称不对",
    "多点环境",
    "少点形容",
)

/**
 * 点快捷按钮只往输入框里**填**，不直接发送；已有内容时用中文逗号续写，之后仍可编辑（D335）。
 */
fun appendQuickPick(current: String, pick: String): String =
    if (current.isBlank()) pick else current.trimEnd() + "，" + pick

/**
 * 小说化 tab 底部的改稿输入条（§0.8）：常驻在正文滚动区之下、PlaybackBar 之上。
 *
 * 不用 `BottomSheet`——[DetailScreen] 的正文和输入条要同屏可见，弹层会盖住正文。
 * 失败时 [error] 已在 ViewModel 里转成人话，这里只负责加前缀与「旧版还在」保底。
 */
@Composable
fun ReviseSheet(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
    error: String?,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Night)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(REVISE_QUICK_PICKS) { pick ->
                Row(
                    modifier = Modifier
                        .background(NightRaised, RoundedCornerShape(18.dp))
                        .clickable(enabled = !sending) { onValueChange(appendQuickPick(value, pick)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = pick, color = InkDim, fontSize = 12.sp, maxLines = 1)
                }
            }
        }

        if (sending) {
            Text(
                text = "正在改…",
                color = InkDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (error != null) {
            Text(
                text = "没改成：$error · 旧版还在",
                color = ErrorText,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = !sending,
                placeholder = {
                    Text(text = placeholder, color = InkDim, fontSize = 14.sp)
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ember,
                    unfocusedBorderColor = Line,
                    focusedTextColor = Paper,
                    unfocusedTextColor = Paper,
                    cursorColor = Ember,
                ),
            )
            TextButton(
                onClick = onSend,
                enabled = !sending && value.isNotBlank(),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Text(text = "发送", color = if (sending || value.isBlank()) InkDim else Ember)
            }
        }
    }
}
