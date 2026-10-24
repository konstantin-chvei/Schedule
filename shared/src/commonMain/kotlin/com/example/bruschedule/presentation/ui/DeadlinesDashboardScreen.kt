package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.presentation.DeadlineUiItem
import com.example.bruschedule.presentation.ScheduleUiState
import com.example.bruschedule.presentation.ScheduleViewModel
import com.example.bruschedule.ui.theme.ScheduleColors
import com.example.bruschedule.ui.theme.LessonThemeMapper
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

private enum class DeadlineFilter(val label: String) {
    ACTIVE("Активные"),
    COMPLETED("Закрытые"),
    ALL("Все"),
}

@Composable
fun DeadlinesDashboardScreen(
    state: ScheduleUiState,
    viewModel: ScheduleViewModel,
    contentPadding: PaddingValues,
) {
    var showEditor by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<AcademicTask?>(null) }
    var filter by remember { mutableStateOf(DeadlineFilter.ACTIVE) }
    val openTasks = state.deadlines.count { !it.task.isCompleted }
    val filteredDeadlines = when (filter) {
        DeadlineFilter.ACTIVE -> state.deadlines.filterNot { it.task.isCompleted }
        DeadlineFilter.COMPLETED -> state.deadlines.filter { it.task.isCompleted }
        DeadlineFilter.ALL -> state.deadlines
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ScheduleColors.background).padding(contentPadding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 21.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Дедлайны", color = ScheduleColors.foreground, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text("Задачи и важные даты.", color = ScheduleColors.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                DeadlineSummaryTile("АКТИВНЫЕ", openTasks.toString().padStart(2, '0'), ScheduleColors.amber, Modifier.weight(1f))
                DeadlineSummaryTile("ВСЕГО", state.deadlines.size.toString().padStart(2, '0'), ScheduleColors.indigo, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { editingTask = null; showEditor = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ScheduleColors.indigo, contentColor = ScheduleColors.background),
            ) {
                Text("＋  Добавить дедлайн", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(5.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                DeadlineFilter.entries.forEach { candidate ->
                    FilterPill(
                        label = candidate.label,
                        selected = filter == candidate,
                        modifier = Modifier.weight(1f),
                        onClick = { filter = candidate },
                    )
                }
            }
        }

        if (filteredDeadlines.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = ScheduleColors.surface,
                    border = BorderStroke(1.dp, ScheduleColors.outline),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(if (state.deadlines.isEmpty()) "◈" else "✓", color = ScheduleColors.indigo, fontSize = 24.sp)
                        Text(
                            when {
                                state.deadlines.isEmpty() -> "Список чист"
                                filter == DeadlineFilter.ACTIVE -> "Все задачи закрыты"
                                else -> "Пока пусто"
                            },
                            color = ScheduleColors.foreground,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (state.deadlines.isEmpty()) "Добавьте дедлайн здесь или из карточки пары. Его можно связать с предметом, своей парой или оставить без привязки."
                            else "Здесь появятся задания из выбранного раздела.",
                            color = ScheduleColors.muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        } else {
            items(filteredDeadlines, key = { it.task.id }) { item ->
                DeadlineCard(
                    item = item,
                    nowEpochMillis = state.nowEpochMillis,
                    onCompletedChange = { completed -> viewModel.setTaskCompleted(item.task.id, completed) },
                    onEdit = { editingTask = item.task; showEditor = true },
                )
            }
        }
    }

    if (showEditor) {
        DeadlineEditorBottomSheet(
            state = state,
            initialTask = editingTask,
            onDismiss = { showEditor = false; editingTask = null },
            onSave = { draft ->
                viewModel.addTask(draft)
                showEditor = false
                editingTask = null
            },
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) ScheduleColors.indigo.copy(alpha = 0.16f) else ScheduleColors.surface,
        border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo.copy(alpha = 0.8f) else ScheduleColors.outline),
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 10.dp),
            color = if (selected) ScheduleColors.cyan else ScheduleColors.muted,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DeadlineSummaryTile(label: String, value: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(17.dp), color = ScheduleColors.surface, border = BorderStroke(1.dp, ScheduleColors.outline)) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ScheduleColors.dim, fontSize = 8.sp, letterSpacing = 0.9.sp)
            Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DeadlineCard(
    item: DeadlineUiItem,
    nowEpochMillis: Long,
    onCompletedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val task = item.task
    val zone = TimeZone.currentSystemDefault()
    val today = Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(zone).date
    val dueDate = Instant.fromEpochMilliseconds(task.dueDate).toLocalDateTime(zone).date
    val remainingDays = today.daysUntil(dueDate)
    val urgent = !task.isCompleted && remainingDays <= 1
    val accent = when {
        task.isCompleted -> ScheduleColors.mint
        remainingDays < 0 -> ScheduleColors.danger
        remainingDays <= 1 -> ScheduleColors.amber
        else -> ScheduleColors.cyan
    }
    Surface(
        shape = RoundedCornerShape(19.dp),
        color = ScheduleColors.surface,
        border = BorderStroke(1.dp, if (urgent) accent.copy(alpha = 0.42f) else ScheduleColors.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Checkbox(
                    checked = task.isCompleted,
                    onCheckedChange = onCompletedChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = ScheduleColors.mint,
                        uncheckedColor = ScheduleColors.dim,
                        checkmarkColor = ScheduleColors.background,
                    ),
                )
                Text(if (task.isCompleted) "ЗАКРЫТ" else "ЗАКРЫТЬ", color = if (task.isCompleted) ScheduleColors.mint else ScheduleColors.dim, fontSize = 7.sp, letterSpacing = 0.3.sp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.lessonType?.let { type ->
                        Surface(shape = CircleShape, color = LessonThemeMapper.typeAccent(type).copy(alpha = 0.18f)) {
                            Spacer(Modifier.size(7.dp).background(LessonThemeMapper.typeAccent(type), CircleShape))
                        }
                    }
                    Text(
                        item.linkLabel,
                        color = if (item.linkedLessonMissing) ScheduleColors.danger else ScheduleColors.cyan,
                        fontSize = 8.sp,
                        letterSpacing = 0.6.sp,
                        fontWeight = if (item.linkedLessonMissing) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(item.subjectName, color = ScheduleColors.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.profileName?.let {
                        Text("· $it", color = ScheduleColors.dim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item.linkDetail?.let {
                    Text(
                        it,
                        color = if (item.linkedLessonMissing) ScheduleColors.danger else ScheduleColors.dim,
                        fontSize = 9.sp,
                        maxLines = if (item.linkedLessonMissing) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    task.taskTitle,
                    color = if (task.isCompleted) ScheduleColors.muted else ScheduleColors.foreground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.description.isNotBlank()) {
                    Text(task.description, color = ScheduleColors.dim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(formatDeadlineDate(task.dueDate), color = ScheduleColors.foreground, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        task.isCompleted -> "ГОТОВО"
                        remainingDays < 0 -> "ПРОСРОЧЕНО"
                        remainingDays == 0 -> "СЕГОДНЯ"
                        remainingDays == 1 -> "ЗАВТРА"
                        else -> "ЧЕРЕЗ $remainingDays ДН."
                    },
                    color = accent,
                    fontSize = 8.sp,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Изменить", color = ScheduleColors.cyan, fontSize = 8.sp)
                }
            }
        }
    }
}

private fun formatDeadlineDate(epochMillis: Long): String {
    val date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.dayOfMonth.toString().padStart(2, '0')}.${date.monthNumber.toString().padStart(2, '0')}"
}
