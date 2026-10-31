package com.dreamkeeper.ui.detail

import com.dreamkeeper.ui.theme.DreamColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dreamkeeper.ui.common.glassDialogModifier
import androidx.compose.ui.unit.dp
import com.dreamkeeper.export.ExportFormat
import com.dreamkeeper.export.ExportResult

/**
 * 导出面板（Task 10 §0.3）。
 *
 * 只做呈现与回调，不碰 MediaStore：真正的写盘在 [com.dreamkeeper.export.AndroidExporter]，
 * 分享由 DetailScreen 用 `ACTION_SEND` 完成（本文件不引 Context）。
 *
 * 三种不可导出状态都有明确文案，按钮不静默消失：
 * - API 26–28 不导出（D379）；
 * - 没有可导出的正文（§0.4 闸门）；
 * - 正在导出（防重入）。
 */
@Composable
fun ExportSheet(
    exporting: ExportFormat?,
    saved: ExportResult?,
    error: String?,
    canExport: Boolean,
    apiSupported: Boolean,
    onExport: (ExportFormat) -> Unit,
    onShare: (ExportResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val labelStyle = MaterialTheme.typography.bodyMedium
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = glassDialogModifier(),
        containerColor = Color.Transparent,
        title = {
            Text(text = "导出", color = DreamColors.paper, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !apiSupported -> Text(
                        text = "系统版本过低，导出需要 Android 10 及以上",
                        color = DreamColors.inkDim,
                        style = labelStyle,
                    )

                    !canExport -> Text(
                        text = "这篇还没有可导出的正文",
                        color = DreamColors.inkDim,
                        style = labelStyle,
                    )

                    else -> {
                        for (format in ExportFormat.values()) {
                            OutlinedButton(
                                onClick = { onExport(format) },
                                enabled = exporting == null,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = DreamColors.paper,
                                    disabledContentColor = DreamColors.inkDim,
                                ),
                            ) {
                                Text(text = formatLabel(format), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (exporting != null) {
                            Text(text = "正在导出…", color = DreamColors.inkDim, style = labelStyle)
                        }
                        saved?.let { result ->
                            Text(
                                text = "已保存到 下载/DreamKeeper/${result.displayName}",
                                color = DreamColors.ember,
                                style = labelStyle,
                            )
                            TextButton(onClick = { onShare(result) }) {
                                Text(text = "分享", color = DreamColors.ember, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        error?.let { message ->
                            Text(text = message, color = DreamColors.errorText, style = labelStyle)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭", color = DreamColors.paper)
            }
        },
    )
}

/** 按钮名只描述格式，不改写格式的扩展名。 */
private fun formatLabel(format: ExportFormat): String = "导出为 ${format.extension}"
