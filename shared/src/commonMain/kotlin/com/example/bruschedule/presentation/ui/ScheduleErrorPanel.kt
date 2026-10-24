package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.ScheduleErrorInfo
import com.example.bruschedule.ui.theme.ScheduleColors

private const val ERROR_REPORT_BOT_URL = "https://t.me/schedule_suggestion_bot"

@Composable
fun ScheduleErrorPanel(
    error: ScheduleErrorInfo,
    modifier: Modifier = Modifier,
    context: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = ScheduleColors.danger.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, ScheduleColors.danger.copy(alpha = 0.24f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(error.message, color = ScheduleColors.foreground, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            context?.let { Text(it, color = ScheduleColors.muted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text("Код ошибки: ${error.code}", color = ScheduleColors.cyan, fontSize = 9.sp, lineHeight = 12.sp)
            Text(
                "Чтобы помочь, отправьте скриншот ошибки и коротко опишите, что вы делали перед её появлением.",
                color = ScheduleColors.dim,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onRetry?.let {
                    TextButton(onClick = it) { Text("Повторить", color = ScheduleColors.danger, fontSize = 10.sp) }
                }
                TextButton(onClick = { runCatching { uriHandler.openUri(ERROR_REPORT_BOT_URL) } }) {
                    Text("Сообщить об ошибке ↗", color = ScheduleColors.cyan, fontSize = 10.sp)
                }
            }
        }
    }
}
