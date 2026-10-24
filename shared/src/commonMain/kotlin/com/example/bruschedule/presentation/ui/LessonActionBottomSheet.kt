package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonNotes
import com.example.bruschedule.domain.model.LessonRecurrence
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.TypographyRole
import com.example.bruschedule.ui.theme.ScheduleColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonActionBottomSheet(
    lesson: Lesson,
    isNew: Boolean,
    linkedDeadlineCount: Int = 0,
    minimumLessonDate: LocalDate? = null,
    maximumLessonDate: LocalDate? = null,
    onDismiss: () -> Unit,
    onSaveLesson: (Lesson, Boolean, LessonRecurrence) -> Unit,
    onCreateTask: (Lesson, LessonRecurrence) -> Unit,
) {
    var subject by remember(lesson.id) { mutableStateOf(lesson.subjectName) }
    var teacher by remember(lesson.id) { mutableStateOf(lesson.teacherName) }
    var classroom by remember(lesson.id) { mutableStateOf(lesson.classroom) }
    var date by remember(lesson.id) { mutableStateOf(lesson.lessonDate) }
    var start by remember(lesson.id) { mutableStateOf(lesson.startTime) }
    var end by remember(lesson.id) { mutableStateOf(lesson.endTime) }
    var slot by remember(lesson.id) { mutableStateOf(lesson.slotNumber.takeIf { it > 0 }?.toString().orEmpty()) }
    var type by remember(lesson.id) { mutableStateOf(lesson.type) }
    var notes by remember(lesson.id) { mutableStateOf(lesson.notes) }
    var recurrence by remember(lesson.id) { mutableStateOf(LessonRecurrence.NONE) }
    var moveLinkedDeadlines by remember(lesson.id) { mutableStateOf(false) }
    var validationError by remember(lesson.id) { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fieldTextStyle = typographyTextStyle(TypographyRole.BODY)
    val editedLesson = lesson.copy(
        subjectName = subject.trim(),
        teacherName = teacher.trim(),
        classroom = classroom.trim(),
        lessonDate = date.trim(),
        dayOfWeek = runCatching { LocalDate.parse(date.trim()).dayOfWeek.isoDayNumber }.getOrDefault(lesson.dayOfWeek),
        startTime = start.trim(),
        endTime = end.trim(),
        slotNumber = slot.toIntOrNull() ?: lesson.slotNumber,
        type = type,
        colorHex = LessonTypePalette.accent(type),
        isCustom = true,
        notes = notes.trim(),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ScheduleColors.surface,
        contentColor = ScheduleColors.foreground,
        dragHandle = { BottomSheetHandle() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${if (isNew) "НОВАЯ" else "РУЧНАЯ ПРАВКА"} · ПАРА", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.2.sp)
            Text(if (isNew) "Добавить пару" else "Изменить расписание", color = ScheduleColors.foreground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Предмет") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = fieldTextStyle,
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
            )
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { Text("Преподаватель") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = fieldTextStyle,
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
            )
            OutlinedTextField(
                value = classroom,
                onValueChange = { classroom = it },
                label = { Text("Корпус / аудитория") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = fieldTextStyle,
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.take(LessonNotes.MAX_LENGTH) },
                label = { Text("Заметки · необязательно") },
                placeholder = { Text("Например: ручку и линейку") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = fieldTextStyle,
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(13.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Дата · ГГГГ-ММ-ДД") },
                    modifier = Modifier.weight(1.2f),
                    textStyle = fieldTextStyle,
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                )
                OutlinedTextField(
                    value = slot,
                    onValueChange = { slot = it },
                    label = { Text("Пара №") },
                    modifier = Modifier.weight(0.8f),
                    textStyle = fieldTextStyle,
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Начало") },
                    modifier = Modifier.weight(1f),
                    textStyle = fieldTextStyle,
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("Конец") },
                    modifier = Modifier.weight(1f),
                    textStyle = fieldTextStyle,
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                )
            }
            LessonTypeDropdown(type, onTypeChange = { type = it })
            if (isNew) {
                LessonRecurrenceDropdown(recurrence, onRecurrenceChange = { recurrence = it })
                Text(
                    "Повторы создаются до конца периода расписания. Каждый экземпляр можно изменить отдельно.",
                    color = ScheduleColors.dim,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )
            }
            validationError?.let { Text(it, color = ScheduleColors.danger, fontSize = 11.sp) }
            Text("Ручные пары не перезаписываются при синхронизации с сайтом.", color = ScheduleColors.dim, fontSize = 10.sp, lineHeight = 15.sp)
            if (linkedDeadlineCount > 0) {
                Surface(shape = RoundedCornerShape(12.dp), color = ScheduleColors.elevatedSurface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = moveLinkedDeadlines,
                            onCheckedChange = { moveLinkedDeadlines = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = ScheduleColors.cyan,
                                uncheckedColor = ScheduleColors.dim,
                                checkmarkColor = ScheduleColors.background,
                            ),
                        )
                        Text(
                            "Перенести $linkedDeadlineCount связанных дедлайнов на дату этой пары",
                            color = ScheduleColors.muted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Закрыть", color = ScheduleColors.muted) }
                Button(
                    onClick = {
                        validationError = validateLesson(editedLesson, minimumLessonDate, maximumLessonDate)
                        if (validationError == null) onSaveLesson(editedLesson, moveLinkedDeadlines, recurrence)
                    },
                    modifier = Modifier.weight(1.2f),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ScheduleColors.indigo, contentColor = ScheduleColors.background),
                ) { Text("Сохранить правку", fontWeight = FontWeight.SemiBold) }
            }
            Button(
                onClick = {
                    validationError = validateLesson(editedLesson, minimumLessonDate, maximumLessonDate)
                    if (validationError == null) onCreateTask(editedLesson, recurrence)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ScheduleColors.elevatedSurface, contentColor = ScheduleColors.cyan),
            ) { Text("＋ Добавить дедлайн к этой паре") }
        }
    }
}

@Composable
private fun LessonTypeDropdown(type: LessonType, onTypeChange: (LessonType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ТИП ЗАНЯТИЯ", color = ScheduleColors.dim, fontSize = 9.sp, letterSpacing = 1.sp)
        Box {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                color = ScheduleColors.elevatedSurface,
                border = BorderStroke(1.dp, ScheduleColors.outline),
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label(), color = ScheduleColors.foreground, fontSize = 12.sp)
                    Text("⌄", color = ScheduleColors.muted)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LessonType.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.label()) },
                        onClick = { onTypeChange(candidate); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun LessonRecurrenceDropdown(
    recurrence: LessonRecurrence,
    onRecurrenceChange: (LessonRecurrence) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ПОВТОРЕНИЕ", color = ScheduleColors.dim, fontSize = 9.sp, letterSpacing = 1.sp)
        Box {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                color = ScheduleColors.elevatedSurface,
                border = BorderStroke(1.dp, ScheduleColors.outline),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(recurrence.label(), color = ScheduleColors.foreground, fontSize = 12.sp)
                    Text("⌄", color = ScheduleColors.muted)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LessonRecurrence.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label()) },
                        onClick = {
                            onRecurrenceChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun LessonRecurrence.label(): String = when (this) {
    LessonRecurrence.NONE -> "Без повтора"
    LessonRecurrence.WEEKLY -> "Раз в неделю"
    LessonRecurrence.BIWEEKLY -> "Раз в две недели"
    LessonRecurrence.MONTHLY -> "Раз в месяц"
}

private fun validateLesson(
    lesson: Lesson,
    minimumLessonDate: LocalDate?,
    maximumLessonDate: LocalDate?,
): String? {
    if (lesson.subjectName.isBlank()) return "Название предмета не может быть пустым"
    val start = runCatching { LocalTime.parse(lesson.startTime) }.getOrNull()
    val end = runCatching { LocalTime.parse(lesson.endTime) }.getOrNull()
    if (start == null || end == null || end <= start) return "Укажите корректное время начала и окончания в формате ЧЧ:ММ"
    val date = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull()
        ?: return "Дата должна быть в формате ГГГГ-ММ-ДД"
    if (minimumLessonDate != null && date < minimumLessonDate) return "Дата пары не может быть раньше текущего периода расписания"
    if (maximumLessonDate != null && date > maximumLessonDate) return "Дата пары выходит за конец выбранного периода расписания"
    return null
}

private fun LessonType.label(): String = when (this) {
    LessonType.LECTURE -> "Лекция"
    LessonType.LABORATORY -> "Лабораторная"
    LessonType.PRACTICE -> "Практика"
    LessonType.UNKNOWN -> "Другое / неизвестно"
}
