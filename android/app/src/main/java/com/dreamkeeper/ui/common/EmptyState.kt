package com.dreamkeeper.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint

/**
 * 统一空态：插画（可选）+ 主文案 + 副文案（D468）。
 *
 * 插画走 `drawable-nodpi/illus_*.webp`（离线生成，随 APK 分发，不联网）；
 * `contentDescription = null` —— 装饰性图片，屏幕阅读器不该停在它上面。
 * 只在数据**已加载且确实为空**时调用（首帧留白，见各页 `loaded` 分支）。
 */
@Composable
fun EmptyState(
    illustration: Painter?,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (illustration != null) {
                Image(
                    painter = illustration,
                    contentDescription = null,
                    modifier = Modifier.width(116.dp),
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = InkDim)
            if (hint != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = hint, style = MaterialTheme.typography.labelSmall, color = InkFaint)
            }
        }
    }
}
