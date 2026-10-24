package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DeadlineLinkType
import com.example.bruschedule.domain.model.DeadlineReminderIntervals
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonRecurrence
import com.example.bruschedule.domain.model.TaskReminderMode
import com.example.bruschedule.domain.model.TypographyRole
import com.example.bruschedule.presentation.DeadlineDraft
import com.example.bruschedule.presentation.ScheduleUiState
import com.example.bruschedule.ui.theme.ScheduleColors
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlineEditorBottomSheet(
    state: ScheduleUiState,
    initialLesson: Lesson? = null,
    initialLessonRecurrence: LessonRecurrence = LessonRecurrence.NONE,
    initialTask: AcademicTask? = null,
    lessonToPersist: Lesson? = null,
    onDismiss: () -> Unit,
    onSave: (DeadlineDraft) -> Unit,
) {
    val contextualLesson = initialLesson
        ?: initialTask?.lessonId?.let { linkedId -> state.availableLessons.firstOrNull { it.id == linkedId } }
    val editorKey = initialTask?.id ?: contextualLesson?.id
    val initialProfileId = initialTask?.profileId ?: contextualLesson?.profileId
        ?: state.selectedProfile?.id ?: state.profiles.firstOrNull()?.id
    val initialLinkType = initialTask?.linkType
        ?: if (contextualLesson == null) DeadlineLinkType.NONE else DeadlineLinkType.LESSON
    val fallbackDueDate = initialTask?.dueDate?.let { dueMillis ->
        Instant.fromEpochMilliseconds(dueMillis).toLocalDateTime(TimeZone.currentSystemDefault()).date
    } ?: contextualLesson?.lessonDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.coerceAtLeast(state.todayDate) ?: state.todayDate
    val initialDueDate = if (initialTask != null) fallbackDueDate.toString()
        else fallbackDueDate.plus(DatePeriod(days = 7)).toString()
    val initialSubject = initialTask?.subjectNameSnapshot?.ifBlank { contextualLesson?.subjectName.orEmpty() }
        ?: contextualLesson?.subjectName.orEmpty()

    var title by remember(editorKey) { mutableStateOf(initialTask?.taskTitle.orEmpty()) }
    var description by remember(editorKey) { mutableStateOf(initialTask?.description.orEmpty()) }
    var dueDate by remember(editorKey, state.todayDate) { mutableStateOf(initialDueDate) }
    var selectedLink by remember(editorKey) { mutableStateOf(initialLinkType) }
    var selectedProfileId by remember(editorKey, initialProfileId) { mutableStateOf(initialProfileId) }
    var selectedSubject by remember(editorKey) { mutableStateOf(initialSubject) }
    var selectedLessonId by remember(editorKey) {
        mutableStateOf(initialTask?.lessonId ?: contextualLesson?.id.takeIf { initialLinkType == DeadlineLinkType.LESSON })
    }
    var reminderMode by remember(editorKey) { mutableStateOf(initialTask?.reminderMode ?: TaskReminderMode.INHERIT) }
    var reminderOffsets by remember(editorKey, state.deadlineReminderPreferences.offsetsMinutes) {
        mutableStateOf(
            initialTask?.reminderOffsetsMinutes?.takeIf { it.isNotEmpty() }?.toSet()
                ?: state.deadlineReminderPreferences.offsetsMinutes.toSet(),
        )
    }
    var dateError by remember(editorKey) { mutableStateOf<String?>(null) }

    val allLessons = remember(state.availableLessons, contextualLesson) {
        (listOfNotNull(contextualLesson) + state.availableLessons).distinctBy(Lesson::id)
    }
    val profileLessons = allLessons.filter { it.profileId == selectedProfileId }
        .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }))
    val subjects = profileLessons.map { it.subjectName.trim() }.filter(String::isNotBlank).distinct().sorted()
    val availableProfileLessons = profileLessons
    val selectedLinkedLesson = availableProfileLessons.firstOrNull { it.id == selectedLessonId }
    val selectedProfile = state.profiles.firstOrNull { it.id == selectedProfileId }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun changeAssociation(type: DeadlineLinkType) {
        selectedLink = type
        if (type != DeadlineLinkType.NONE && selectedProfileId == null) {
            selectedProfileId = state.selectedProfile?.id ?: state.profiles.firstOrNull()?.id
        }
        if (type == DeadlineLinkType.SUBJECT && selectedSubject !in subjects) {
            selectedSubject = subjects.firstOrNull().orEmpty()
        }
        if (type == DeadlineLinkType.LESSON && availableProfileLessons.none { it.id == selectedLessonId }) {
            selectedLessonId = availableProfileLessons.firstOrNull()?.id
        }
    }

    val validDate = runCatching { LocalDate.parse(dueDate.trim()) }.getOrNull()
    val canSave = title.isNotBlank() && validDate != null && when (selectedLink) {
        DeadlineLinkType.NONE -> true
        DeadlineLinkType.SUBJECT -> selectedProfile != null && selectedSubject.isNotBlank()
        DeadlineLinkType.LESSON -> selectedProfile != null && availableProfileLessons.any { it.id == selectedLessonId }
    }

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
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ПЛАНИРОВАНИЕ · ДЕДЛАЙН", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.3.sp)
            Text(if (initialTask == null) "Новая задача" else "Изменить дедлайн", color = ScheduleColors.foreground, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Название задания") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = typographyTextStyle(TypographyRole.BODY),
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Комментарий · необязательно") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = typographyTextStyle(TypographyRole.BODY),
                minLines = 2,
                shape = RoundedCornerShape(13.dp),
            )

            Text("СРОК ВЫПОЛНЕНИЯ", color = ScheduleColors.dim, fontSize = 9.sp, letterSpacing = 1.sp)
            OutlinedTextField(
                value = dueDate,
                onValueChange = {
                    dueDate = it
                    dateError = if (it.length >= 10 && runCatching { LocalDate.parse(it.trim()) }.isFailure) {
                        "Проверьте дату: формат ГГГГ-ММ-ДД"
                    } else null
                },
                label = { Text("Дата · ГГГГ-ММ-ДД") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = typographyTextStyle(TypographyRole.BODY),
                singleLine = true,
                isError = dateError != null,
                shape = RoundedCornerShape(13.dp),
            )
            dateError?.let { Text(it, color = ScheduleColors.danger, fontSize = 10.sp) }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                listOf(1 to "Завтра", 3 to "+3 дня", 7 to "+неделя").forEach { (days, label) ->
                    ChoicePill(label, selected = false) {
                        dueDate = state.todayDate.plus(DatePeriod(days = days)).toString()
                        dateError = null
                    }
                }
            }
            Text("Время срока по умолчанию — 09:00.", color = ScheduleColors.dim, fontSize = 9.sp)

            Text("ПРИВЯЗКА",  color = ScheduleColors.dim, fontSize = 9.sp, letterSpacing = 1.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoicePill("Предмет", selectedLink == DeadlineLinkType.SUBJECT, Modifier.weight(1f)) {
                    changeAssociation(DeadlineLinkType.SUBJECT)
                }
                ChoicePill("Пара", selectedLink == DeadlineLinkType.LESSON, Modifier.weight(1f)) {
                    changeAssociation(DeadlineLinkType.LESSON)
                }
                ChoicePill("Без привязки", selectedLink == DeadlineLinkType.NONE, Modifier.weight(1f)) {
                    changeAssociation(DeadlineLinkType.NONE)
                }
            }

            when (selectedLink) {
                DeadlineLinkType.NONE -> Text(
                    "Общий дедлайн — без связи с профилем, предметом или парой.",
                    color = ScheduleColors.muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                DeadlineLinkType.SUBJECT, DeadlineLinkType.LESSON -> {
                    if (state.profiles.isNotEmpty()) {
                        SelectionField(
                            label = "ПРОФИЛЬ",
                            value = selectedProfile?.name ?: "Выберите профиль",
                            options = state.profiles.map { it.id to it.name },
                            onSelect = { profileId ->
                                selectedProfileId = profileId
                                val nextLessons = allLessons.filter { it.profileId == profileId }
                                val nextSubjects = nextLessons.map { it.subjectName.trim() }.filter(String::isNotBlank).distinct().sorted()
                                val nextAvailableLessons = nextLessons.sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }))
                                if (selectedLink == DeadlineLinkType.SUBJECT && selectedSubject !in nextSubjects) {
                                    selectedSubject = nextSubjects.firstOrNull().orEmpty()
                                }
                                if (selectedLink == DeadlineLinkType.LESSON && nextAvailableLessons.none { it.id == selectedLessonId }) {
                                    selectedLessonId = nextAvailableLessons.firstOrNull()?.id
                                }
                            },
                        )
                    }
                    if (selectedLink == DeadlineLinkType.SUBJECT) {
                        if (subjects.isNotEmpty()) {
                            SelectionField(
                                label = "ПРЕДМЕТ",
                                value = selectedSubject.ifBlank { "Выберите предмет" },
                                options = subjects.map { it to it },
                                onSelect = { selectedSubject = it },
                            )
                        } else {
                            Text("В этом профиле пока нет предметов расписания.", color = ScheduleColors.muted, fontSize = 10.sp)
                        }
                        Text("Дедлайн будет связан с предметом, но не с одной конкретной датой занятий.", color = ScheduleColors.dim, fontSize = 10.sp, lineHeight = 15.sp)
                    } else if (availableProfileLessons.isNotEmpty()) {
                        if (initialTask?.linkType == DeadlineLinkType.LESSON &&
                            availableProfileLessons.none { it.id == initialTask.lessonId }
                        ) {
                            Text(
                                "Исходная пара удалена или перенесена. Выберите новую пару и при необходимости измените срок.",
                                color = ScheduleColors.danger,
                                fontSize = 10.sp,
                                lineHeight = 15.sp,
                            )
                        }
                        SelectionField(
                            label = "КОНКРЕТНАЯ ПАРА",
                            value = availableProfileLessons.firstOrNull { it.id == selectedLessonId }?.let(::lessonLabel) ?: "Выберите пару",
                            options = availableProfileLessons.map { it.id to lessonLabel(it) },
                            onSelect = { selectedLessonId = it },
                        )
                        Text("Дедлайн будет связан с выбранным занятием, датой и временем.", color = ScheduleColors.dim, fontSize = 10.sp, lineHeight = 15.sp)
                        selectedLinkedLesson?.let { lesson ->
                            TextButton(
                                onClick = {
                                    dueDate = lesson.lessonDate
                                    dateError = null
                                },
                            ) {
                                Text("Поставить срок на дату пары", color = ScheduleColors.cyan, fontSize = 10.sp)
                            }
                        }
                    } else {
                        Text("В этом профиле пока нет занятий. Выберите другой профиль или тип привязки.", color = ScheduleColors.muted, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
            }

            Text("НАПОМИНАНИЯ", color = ScheduleColors.dim, fontSize = 9.sp, letterSpacing = 1.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoicePill("По умолчанию", reminderMode == TaskReminderMode.INHERIT, Modifier.weight(1f)) { reminderMode = TaskReminderMode.INHERIT }
                ChoicePill("Индивидуально", reminderMode == TaskReminderMode.CUSTOM, Modifier.weight(1f)) { reminderMode = TaskReminderMode.CUSTOM }
                ChoicePill("Выключить", reminderMode == TaskReminderMode.NONE, Modifier.weight(1f)) { reminderMode = TaskReminderMode.NONE }
            }
            when (reminderMode) {
                TaskReminderMode.INHERIT -> Text(
                    if (state.deadlineReminderPreferences.enabled) {
                        "Общие настройки: ${state.deadlineReminderPreferences.offsetsMinutes.joinToString(" · ") { DeadlineReminderIntervals.label(it) }} до срока."
                    } else {
                        "Общие напоминания выключены в настройках приложения."
                    },
                    color = ScheduleColors.muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                TaskReminderMode.CUSTOM -> {
                    Text("Выберите одно или несколько напоминаний до срока.", color = ScheduleColors.muted, fontSize = 10.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        DeadlineReminderIntervals.choices.forEach { minutes ->
                            ChoicePill(
                                DeadlineReminderIntervals.label(minutes),
                                minutes in reminderOffsets,
                            ) {
                                reminderOffsets = if (minutes in reminderOffsets) {
                                    reminderOffsets - minutes
                                } else {
                                    reminderOffsets + minutes
                                }
                            }
                        }
                    }
                    if (!state.deadlineReminderPreferences.enabled) {
                        Text("Сначала включите напоминания в настройках приложения.", color = ScheduleColors.amber, fontSize = 10.sp)
                    }
                }
                TaskReminderMode.NONE -> Text("Для этой задачи уведомления отправляться не будут.", color = ScheduleColors.muted, fontSize = 10.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Отмена", color = ScheduleColors.muted)
                }
                Button(
                    onClick = {
                        dateError = if (validDate == null) "Проверьте дату: используйте формат ГГГГ-ММ-ДД" else null
                        if (canSave) {
                            onSave(
                                DeadlineDraft(
                                    title = title,
                                    description = description,
                                    dueDateIso = dueDate,
                                    linkType = selectedLink,
                                    profileId = selectedProfileId.takeIf { selectedLink != DeadlineLinkType.NONE },
                                    subjectName = when (selectedLink) {
                                        DeadlineLinkType.SUBJECT -> selectedSubject
                                        DeadlineLinkType.LESSON -> availableProfileLessons.firstOrNull { it.id == selectedLessonId }?.subjectName
                                            ?: contextualLesson?.subjectName.orEmpty()
                                        DeadlineLinkType.NONE -> ""
                                    },
                                    lessonId = selectedLessonId.takeIf { selectedLink == DeadlineLinkType.LESSON },
                                    reminderMode = reminderMode,
                                    reminderOffsetsMinutes = reminderOffsets.toList().sortedDescending(),
                                    lessonToPersist = lessonToPersist?.takeIf {
                                        selectedLink == DeadlineLinkType.LESSON && selectedLessonId == it.id
                                    },
                                    taskId = initialTask?.id,
                                    isCompleted = initialTask?.isCompleted ?: false,
                                    lessonRecurrence = initialLessonRecurrence,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.weight(1.4f),
                    enabled = canSave && (reminderMode != TaskReminderMode.CUSTOM || reminderOffsets.isNotEmpty()),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ScheduleColors.indigo, contentColor = ScheduleColors.background),
                ) { Text(if (initialTask == null) "Сохранить дедлайн" else "Сохранить изменения", fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(3.dp))
        }
    }
}

@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) ScheduleColors.indigo.copy(alpha = 0.18f) else ScheduleColors.elevatedSurface,
        border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo else ScheduleColors.outline),
    ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) ScheduleColors.cyan else ScheduleColors.muted,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectionField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember(label, options) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = ScheduleColors.dim, fontSize = 9.sp, letterSpacing = 1.sp)
        Box {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                color = ScheduleColors.elevatedSurface,
                border = BorderStroke(1.dp, ScheduleColors.outline),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value, color = ScheduleColors.foreground, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("⌄", color = ScheduleColors.muted, modifier = Modifier.padding(start = 8.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, title) ->
                    DropdownMenuItem(
                        text = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = { onSelect(id); expanded = false },
                    )
                }
            }
        }
    }
}

private fun lessonLabel(lesson: Lesson): String =
    "${lesson.subjectName.ifBlank { "Своя пара" }} · ${lesson.lessonDate} · ${lesson.startTime}"
