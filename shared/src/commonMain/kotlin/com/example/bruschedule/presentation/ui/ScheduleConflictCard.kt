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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.usecase.ScheduleLessonConflict
import com.example.bruschedule.ui.theme.ScheduleColors

@Composable
fun ScheduleConflictCard(
    conflict: ScheduleLessonConflict,
    onMovePersonalLesson: () -> Unit,
    onDeletePersonalLesson: () -> Unit,
) {
    val personal = conflict.personalLesson
    val websiteSummary = conflict.websiteLessons.take(2).joinToString(" · ") { lesson ->
        "${lesson.subjectName} · ${lesson.lessonDate} ${lesson.startTime}–${lesson.endTime}"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ScheduleColors.amber.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, ScheduleColors.amber.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Личная пара пересекается с расписанием сайта", color = ScheduleColors.amber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Ваша: ${personal.subjectName} · ${personal.lessonDate} ${personal.startTime}–${personal.endTime}",
                color = ScheduleColors.foreground,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "С сайта: $websiteSummary${if (conflict.websiteLessons.size > 2) " · ещё ${conflict.websiteLessons.size - 2}" else ""}",
                color = ScheduleColors.muted,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (conflict.linkedDeadlines.isNotEmpty()) {
                Text(
                    "Связанных дедлайнов: ${conflict.linkedDeadlines.size}. При переносе пары можно перенести и их даты.",
                    color = ScheduleColors.dim,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onMovePersonalLesson, modifier = Modifier.weight(1f)) {
                    Text(
                        "Изменить день / время",
                        color = ScheduleColors.cyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onDeletePersonalLesson, modifier = Modifier.weight(1f)) {
                    Text(
                        "Удалить мою пару",
                        color = ScheduleColors.danger,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
