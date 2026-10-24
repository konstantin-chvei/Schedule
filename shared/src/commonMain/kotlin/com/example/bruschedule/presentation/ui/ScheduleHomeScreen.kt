package com.example.bruschedule.presentation.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonRecurrence
import com.example.bruschedule.domain.model.LessonStickerPalette
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleErrorInfo
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.University
import com.example.bruschedule.domain.model.UniversitySourceType
import com.example.bruschedule.domain.usecase.ScheduleLessonConflict
import com.example.bruschedule.presentation.LessonUiItem
import com.example.bruschedule.presentation.MainDestination
import com.example.bruschedule.presentation.ScheduleUiState
import com.example.bruschedule.presentation.ScheduleViewModel
import com.example.bruschedule.ui.theme.ScheduleColors
import com.example.bruschedule.ui.theme.LessonThemeMapper
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

@Composable
fun ScheduleHomeScreen(
    state: ScheduleUiState,
    viewModel: ScheduleViewModel,
    contentPadding: PaddingValues,
) {
    var addProfileVisible by remember { mutableStateOf(false) }
    var showSemesterCalendar by remember { mutableStateOf(false) }
    var lessonInEditor by remember { mutableStateOf<Lesson?>(null) }
    var isNewLesson by remember { mutableStateOf(false) }
    var deadlineEditorVisible by remember { mutableStateOf(false) }
    var deadlineInitialLesson by remember { mutableStateOf<Lesson?>(null) }
    var deadlineLessonToPersist by remember { mutableStateOf<Lesson?>(null) }
    var deadlineLessonRecurrence by remember { mutableStateOf(LessonRecurrence.NONE) }
    var conflictToDelete by remember { mutableStateOf<Lesson?>(null) }
    var showUniversityPicker by remember { mutableStateOf(false) }
    var stickerPickerLesson by remember { mutableStateOf<Lesson?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScheduleColors.background)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        ScheduleTopBar(dailyMessage = state.dailyMessage)
        Spacer(Modifier.height(18.dp))
        UniversitySelectionPanel(
            university = state.selectedUniversity,
            onClick = { showUniversityPicker = true },
        )
        Spacer(Modifier.height(18.dp))

        if (state.isLoading) {
            ScheduleLoadingStatusCard(title = "Загружаем сохранённые данные", detail = "Поднимаем расписание из локальной базы.")
        } else if (state.profiles.isEmpty()) {
            FirstProfileCard(onAdd = { addProfileVisible = true })
        } else {
            ProfileRail(
                profiles = state.profiles,
                selectedProfileId = state.selectedProfile?.id,
                onSelect = viewModel::selectProfile,
                onAdd = { addProfileVisible = true },
            )
            Spacer(Modifier.height(22.dp))
            WeekCalendarRail(
                selectedDate = state.selectedDate,
                today = state.todayDate,
                datesWithLessons = state.weekDatesWithLessons,
                allDatesWithLessons = state.allScheduleDatesWithLessons,
                coverageStartDate = state.scheduleCoverageStartDate,
                coverageEndDate = state.scheduleCoverageEndDate,
                showCycleIndicator = state.hasRemoteSchedule,
                showSemesterCalendar = showSemesterCalendar,
                onToggleSemesterCalendar = { showSemesterCalendar = !showSemesterCalendar },
                onSelect = viewModel::selectDate,
                onPreviousWeek = { viewModel.changeWeek(-1) },
                onNextWeek = { viewModel.changeWeek(1) },
            )
            Spacer(Modifier.height(20.dp))
            DaySummary(
                date = state.selectedDate,
                lessonCount = state.lessons.size,
                pendingTaskCount = state.lessons.sumOf { it.pendingTaskCount },
            )
            Spacer(Modifier.height(14.dp))

            val syncError = state.syncState?.lastError
                ?.takeIf(String::isNotBlank)
                ?.let { ScheduleErrorInfo.decodeStored(it) }
            when {
                !state.hasRemoteSchedule && state.isRefreshing -> ScheduleLoadingStatusCard(
                    title = "Загружаем расписание",
                    detail = "Получаем расписание и сохраняем его на устройстве. Это может занять несколько секунд.",
                )
                !state.hasRemoteSchedule && syncError != null -> ScheduleErrorStatusCard(
                    error = syncError,
                    onOpenSettings = { viewModel.showDestination(MainDestination.SCHEDULE_MANAGEMENT) },
                )
                !state.hasRemoteSchedule -> ScheduleNotLoadedCard(
                    onOpenSettings = { viewModel.showDestination(MainDestination.SCHEDULE_MANAGEMENT) },
                )
                state.isRefreshing -> ScheduleRefreshingBanner()
                syncError != null -> CachedScheduleWarning(
                    error = syncError,
                    onOpenSettings = { viewModel.showDestination(MainDestination.SCHEDULE_MANAGEMENT) },
                )
            }
            Spacer(Modifier.height(12.dp))

            if (state.scheduleConflicts.isNotEmpty()) {
                Text("НАКЛАДКИ ПАР", color = ScheduleColors.amber, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
                state.scheduleConflicts.forEach { conflict ->
                    ScheduleConflictCard(
                        conflict = conflict,
                        onMovePersonalLesson = {
                            isNewLesson = false
                            lessonInEditor = conflict.personalLesson
                        },
                        onDeletePersonalLesson = { conflictToDelete = conflict.personalLesson },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            if (state.lessons.isEmpty() && state.hasRemoteSchedule) {
                EmptyDayCard(
                    date = state.selectedDate,
                    onAddLesson = {
                        viewModel.newLessonDraft()?.let {
                            isNewLesson = true
                            lessonInEditor = it
                        }
                    },
                )
            } else if (state.lessons.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.lessons.forEach { item ->
                        LessonCard(
                            item = item,
                            onClick = {
                                isNewLesson = false
                                lessonInEditor = item.lesson
                            },
                            onRate = { rating -> viewModel.saveLessonRating(item.lesson.id, rating) },
                            onAddSticker = { stickerPickerLesson = item.lesson },
                            onToggleSticker = { sticker -> viewModel.toggleLessonSticker(item.lesson.id, sticker) },
                        )
                    }
                    TextButton(
                        onClick = {
                            viewModel.newLessonDraft()?.let {
                                isNewLesson = true
                                lessonInEditor = it
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("＋ Добавить свою пару", color = ScheduleColors.muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (addProfileVisible) {
        AddProfileBottomSheet(
            searchState = state.profileSearch,
            university = state.selectedUniversity,
            onClose = { addProfileVisible = false },
            onSearch = viewModel::searchProfiles,
            onSelectCandidate = { candidate ->
                viewModel.addProfileCandidate(candidate)
                addProfileVisible = false
            },
        )
    }

    if (showUniversityPicker) {
        AlertDialog(
            onDismissRequest = { showUniversityPicker = false },
            title = { Text("Выберите вуз") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.universities.forEach { university ->
                        UniversityChoiceRow(
                            university = university,
                            selected = university.id == state.selectedUniversity.id,
                            onClick = {
                                viewModel.selectUniversity(university.id)
                                showUniversityPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUniversityPicker = false }) { Text("Готово", color = ScheduleColors.cyan) }
            },
            containerColor = ScheduleColors.surface,
            titleContentColor = ScheduleColors.foreground,
            textContentColor = ScheduleColors.muted,
        )
    }

    stickerPickerLesson?.let { lesson ->
        AlertDialog(
            onDismissRequest = { stickerPickerLesson = null },
            title = { Text("Выберите стикер") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    LessonStickerPalette.choices.chunked(5).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { sticker ->
                                val selected = sticker in lesson.stickers
                                Surface(
                                    onClick = {
                                        viewModel.toggleLessonSticker(lesson.id, sticker)
                                        stickerPickerLesson = null
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) ScheduleColors.indigo.copy(alpha = 0.16f) else ScheduleColors.elevatedSurface,
                                    border = BorderStroke(1.dp, if (selected) ScheduleColors.cyan else ScheduleColors.outline),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(sticker, color = ScheduleColors.foreground, fontSize = 21.sp)
                                    }
                                }
                            }
                            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { stickerPickerLesson = null }) { Text("Закрыть", color = ScheduleColors.muted) }
            },
            containerColor = ScheduleColors.surface,
            titleContentColor = ScheduleColors.foreground,
            textContentColor = ScheduleColors.muted,
        )
    }

    val editingLesson = lessonInEditor
    if (editingLesson != null) {
        LessonActionBottomSheet(
            lesson = editingLesson,
            isNew = isNewLesson,
            linkedDeadlineCount = state.deadlines.count { it.task.lessonId == editingLesson.id },
            minimumLessonDate = state.scheduleCoverageStartDate,
            maximumLessonDate = state.scheduleCoverageEndDate,
            onDismiss = { lessonInEditor = null },
            onSaveLesson = { saved, moveDeadlinesWithPair, recurrence ->
                val scheduleChanged = isNewLesson || saved.hasEditableChangesFrom(editingLesson)
                if (scheduleChanged) {
                    val locationChanged = saved.hasSchedulePositionChanged(editingLesson)
                    viewModel.saveCustomLesson(
                        lesson = saved,
                        recurrence = recurrence,
                        detachFromWebsiteSource = locationChanged,
                        moveDeadlinesWithPair = moveDeadlinesWithPair,
                    )
                } else if (saved.notes != editingLesson.notes) {
                    viewModel.saveLessonNotes(editingLesson.id, saved.notes)
                }
                lessonInEditor = null
            },
            onCreateTask = { lesson, recurrence ->
                val shouldPersistLesson = isNewLesson || editingLesson.isCustom || lesson.hasEditableChangesFrom(editingLesson)
                val locationChanged = lesson.hasSchedulePositionChanged(editingLesson)
                val lessonToPersist = if (shouldPersistLesson) {
                    lesson.copy(
                        isCustom = true,
                        sourceKey = if (locationChanged) "custom:${lesson.id}" else lesson.sourceKey,
                    )
                } else {
                    lesson.copy(isCustom = false)
                }
                if (!shouldPersistLesson && lesson.notes != editingLesson.notes) {
                    viewModel.saveLessonNotes(editingLesson.id, lesson.notes)
                }
                deadlineInitialLesson = lessonToPersist
                deadlineLessonToPersist = lessonToPersist.takeIf { shouldPersistLesson }
                deadlineLessonRecurrence = if (isNewLesson && shouldPersistLesson) recurrence else LessonRecurrence.NONE
                lessonInEditor = null
                deadlineEditorVisible = true
            },
        )
    }
    if (deadlineEditorVisible) {
        DeadlineEditorBottomSheet(
            state = state,
            initialLesson = deadlineInitialLesson,
            initialLessonRecurrence = deadlineLessonRecurrence,
            lessonToPersist = deadlineLessonToPersist,
            onDismiss = {
                deadlineEditorVisible = false
                deadlineInitialLesson = null
                deadlineLessonToPersist = null
                deadlineLessonRecurrence = LessonRecurrence.NONE
            },
            onSave = { draft ->
                viewModel.addTask(draft)
                deadlineEditorVisible = false
                deadlineInitialLesson = null
                deadlineLessonToPersist = null
                deadlineLessonRecurrence = LessonRecurrence.NONE
            },
        )
    }
    conflictToDelete?.let { lesson ->
        val linkedDeadlineCount = state.deadlines.count { it.task.lessonId == lesson.id }
        AlertDialog(
            onDismissRequest = { conflictToDelete = null },
            title = { Text("Удалить личную пару?") },
            text = {
                Text(
                    if (linkedDeadlineCount == 0) {
                        "Пара «${lesson.subjectName}» будет удалена с устройства."
                    } else {
                        "Дедлайны ($linkedDeadlineCount) сохранятся. Их связь с парой будет заменена привязкой к предмету, если он известен; сама пара будет удалена."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCustomLesson(lesson.id)
                        conflictToDelete = null
                    },
                ) { Text("Удалить пару", color = ScheduleColors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { conflictToDelete = null }) { Text("Отмена", color = ScheduleColors.muted) }
            },
            containerColor = ScheduleColors.surface,
            titleContentColor = ScheduleColors.foreground,
            textContentColor = ScheduleColors.muted,
        )
    }
}

@Composable
private fun ScheduleTopBar(dailyMessage: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandCalendarGlyph()
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Schedule", color = ScheduleColors.foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(
                dailyMessage,
                color = ScheduleColors.muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UniversitySelectionPanel(
    university: University,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = ScheduleColors.surface,
        border = BorderStroke(1.dp, ScheduleColors.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(11.dp), color = ScheduleColors.indigo.copy(alpha = 0.13f)) {
                Text("ВУЗ", modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = ScheduleColors.cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    university.name,
                    color = ScheduleColors.foreground,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (university.sourceType) {
                        UniversitySourceType.BRU_CATALOG,
                        UniversitySourceType.BSU_HTML_AND_SHEETS -> "РАСПИСАНИЯ ДОСТУПНЫ"
                        UniversitySourceType.DIRECT_HTML -> "ПОКА НЕДОСТУПНО"
                    },
                    color = ScheduleColors.dim,
                    fontSize = 8.sp,
                    letterSpacing = 0.8.sp,
                )
            }
            Text("СМЕНИТЬ  ⌄", color = ScheduleColors.cyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun UniversityChoiceRow(
    university: University,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) ScheduleColors.indigo.copy(alpha = 0.13f) else ScheduleColors.elevatedSurface,
        border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo else ScheduleColors.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(university.name, color = ScheduleColors.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (university.sourceType) {
                        UniversitySourceType.BRU_CATALOG,
                        UniversitySourceType.BSU_HTML_AND_SHEETS -> "Расписания доступны"
                        UniversitySourceType.DIRECT_HTML -> "Пока недоступно"
                    },
                    color = ScheduleColors.muted,
                    fontSize = 9.sp,
                )
            }
            if (selected) Text("✓", color = ScheduleColors.cyan, fontSize = 16.sp)
        }
    }
}

@Composable
private fun BrandCalendarGlyph() {
    val ink = ScheduleColors.foreground
    val accent = ScheduleColors.cyan
    Canvas(Modifier.size(45.dp)) {
        drawBrandCalendarGlyph(ink, accent)
    }
}

private fun DrawScope.drawBrandCalendarGlyph(ink: Color, accent: Color) {
    val unit = minOf(size.width, size.height) / 44f
    val stroke = 2.1f * unit
    val left = 5f * unit
    val top = 7f * unit
    val frameSize = Size(34f * unit, 30f * unit)

    listOf(14f, 30f).forEach { x ->
        drawLine(ink, Offset(x * unit, 3f * unit), Offset(x * unit, 12f * unit), stroke, StrokeCap.Round)
    }
    drawRoundRect(
        color = ink,
        topLeft = Offset(left, top),
        size = frameSize,
        cornerRadius = CornerRadius(5f * unit),
        style = Stroke(width = stroke),
    )
    drawLine(ink, Offset(left, 17f * unit), Offset(left + frameSize.width, 17f * unit), stroke, StrokeCap.Round)

    val cellSize = 3.2f * unit
    val cellGap = 2.1f * unit
    val gridWidth = cellSize * 4f + cellGap * 3f
    val gridLeft = (size.width - gridWidth) / 2f
    for (row in 0..1) {
        for (column in 0..3) {
            val x = gridLeft + column * (cellSize + cellGap)
            val y = (21f + row * 7f) * unit
            drawRoundRect(
                color = if (row == 0 && column == 2) accent else ink.copy(alpha = 0.9f),
                topLeft = Offset(x, y),
                size = Size(cellSize, cellSize),
                cornerRadius = CornerRadius(1f * unit),
            )
        }
    }
}

@Composable
private fun ProfileRail(
    profiles: List<com.example.bruschedule.domain.model.ScheduleProfile>,
    selectedProfileId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionEyebrow("МОИ РАСПИСАНИЯ", trailing = "${profiles.size} проф.")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            profiles.forEach { profile ->
                val selected = profile.id == selectedProfileId
                Surface(
                    onClick = { onSelect(profile.id) },
                    shape = RoundedCornerShape(15.dp),
                    color = if (selected) ScheduleColors.indigo.copy(alpha = 0.14f) else ScheduleColors.surface,
                    border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo.copy(alpha = 0.7f) else ScheduleColors.outline),
                ) {
                    Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                        Text(
                            profile.name,
                            color = if (selected) ScheduleColors.foreground else ScheduleColors.muted,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        Text(
                            if (profile.type == ScheduleProfileType.TEACHER) "ПРЕПОДАВАТЕЛЬ" else "ГРУППА",
                            color = if (selected) ScheduleColors.cyan else ScheduleColors.dim,
                            fontSize = 8.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
            Surface(
                onClick = onAdd,
                shape = RoundedCornerShape(15.dp),
                color = ScheduleColors.surface,
                border = BorderStroke(1.dp, ScheduleColors.outline),
            ) {
                Text("＋", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = ScheduleColors.cyan, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun WeekCalendarRail(
    selectedDate: LocalDate,
    today: LocalDate,
    datesWithLessons: Set<String>,
    allDatesWithLessons: Set<String>,
    coverageStartDate: LocalDate?,
    coverageEndDate: LocalDate?,
    showCycleIndicator: Boolean,
    showSemesterCalendar: Boolean,
    onToggleSemesterCalendar: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
) {
    val monday = selectedDate.minus(DatePeriod(days = selectedDate.dayOfWeek.isoDayNumber - 1))
    val days = (0..6).map { monday.plus(DatePeriod(days = it)) }
    val firstCoveredWeek = coverageStartDate?.startOfWeek()
    val lastCoveredWeek = coverageEndDate?.startOfWeek()
    val hasSemesterCycle = showCycleIndicator && coverageStartDate != null && coverageEndDate != null &&
        coverageEndDate > coverageStartDate
    val canGoPrevious = firstCoveredWeek != null && monday.minus(DatePeriod(days = 7)) >= firstCoveredWeek
    val canGoNext = lastCoveredWeek != null && monday.plus(DatePeriod(days = 7)) <= lastCoveredWeek
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionEyebrow(
                    if (showSemesterCalendar) "СЕМЕСТР · ОБЗОР" else "НЕДЕЛЯ · ${weekNumberHint(monday)}",
                    trailing = if (hasSemesterCycle) coverageEndDate?.let { "ДО ${it.toRuDate().uppercase()}" } else null,
                )
                Text(
                    if (showSemesterCalendar && coverageStartDate != null && coverageEndDate != null) {
                        "${coverageStartDate.toRuDate()} — ${coverageEndDate.toRuDate()}"
                    } else {
                        "${monday.toRuDate()} — ${monday.plus(DatePeriod(days = 6)).toRuDate()}"
                    },
                    color = ScheduleColors.muted,
                    fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WeekArrow("‹", onPreviousWeek, enabled = canGoPrevious)
                WeekArrow("›", onNextWeek, enabled = canGoNext)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalendarModePill("Неделя", selected = !showSemesterCalendar, modifier = Modifier.weight(1f), onClick = { if (showSemesterCalendar) onToggleSemesterCalendar() })
            CalendarModePill("Весь семестр", selected = showSemesterCalendar, modifier = Modifier.weight(1f), onClick = { if (!showSemesterCalendar) onToggleSemesterCalendar() })
        }
        if (showSemesterCalendar) {
            SemesterMonthGrid(
                selectedDate = selectedDate,
                today = today,
                datesWithLessons = allDatesWithLessons,
                coverageStartDate = coverageStartDate,
                coverageEndDate = coverageEndDate,
                onSelect = onSelect,
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val showAllDays = maxWidth >= 364.dp
                val pillGap = if (showAllDays) 5.dp else 8.dp
                val pillWidth = if (showAllDays) {
                    ((maxWidth - 42.dp) / 7f).coerceAtMost(52.dp)
                } else {
                    ((maxWidth - 12.dp - 40.dp) / 6f).coerceIn(36.dp, 56.dp)
                }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Spacer(Modifier.width(6.dp))
                    days.forEachIndexed { index, date ->
                        if (index > 0) Spacer(Modifier.width(pillGap))
                        DayPill(
                            date = date,
                            pillWidth = pillWidth,
                            selected = date == selectedDate,
                            isToday = date == today,
                            hasLessons = date.toString() in datesWithLessons,
                            enabled = coverageStartDate != null && coverageEndDate != null &&
                                date >= coverageStartDate && date <= coverageEndDate,
                            onClick = { onSelect(date) },
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun CalendarModePill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (selected) ScheduleColors.indigo.copy(alpha = 0.17f) else ScheduleColors.surface,
        border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo else ScheduleColors.outline),
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 10.dp),
            color = if (selected) ScheduleColors.cyan else ScheduleColors.muted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SemesterMonthGrid(
    selectedDate: LocalDate,
    today: LocalDate,
    datesWithLessons: Set<String>,
    coverageStartDate: LocalDate?,
    coverageEndDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    if (coverageStartDate == null || coverageEndDate == null) {
        Text("Нет данных о периоде расписания.", color = ScheduleColors.muted, fontSize = 11.sp)
        return
    }
    val months = mutableListOf<LocalDate>()
    var cursor = LocalDate(coverageStartDate.year, coverageStartDate.monthNumber, 1)
    val finalMonth = LocalDate(coverageEndDate.year, coverageEndDate.monthNumber, 1)
    while (cursor <= finalMonth) {
        months += cursor
        cursor = cursor.plus(DatePeriod(months = 1))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        months.forEach { firstOfMonth ->
            val daysInMonth = firstOfMonth.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).dayOfMonth
            val emptyCells = firstOfMonth.dayOfWeek.isoDayNumber - 1
            val cells: List<LocalDate?> = List(emptyCells) { null } +
                (0 until daysInMonth).map { day -> firstOfMonth.plus(DatePeriod(days = day)) }
            val paddedCells = cells + List((7 - cells.size % 7) % 7) { null }
            Surface(
                shape = RoundedCornerShape(17.dp),
                color = ScheduleColors.surface,
                border = BorderStroke(1.dp, ScheduleColors.outline),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        "${monthLabel(firstOfMonth.monthNumber)} ${firstOfMonth.year}",
                        color = ScheduleColors.foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { day ->
                            Text(day, modifier = Modifier.weight(1f), color = ScheduleColors.dim, fontSize = 8.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    paddedCells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            week.forEach { date ->
                                Box(Modifier.weight(1f).height(38.dp), contentAlignment = Alignment.Center) {
                                    if (date != null) {
                                        val inCoverage = date >= coverageStartDate && date <= coverageEndDate
                                        SemesterDayCell(
                                            date = date,
                                            selected = date == selectedDate,
                                            isToday = date == today,
                                            hasLessons = date.toString() in datesWithLessons,
                                            enabled = inCoverage,
                                            onClick = { onSelect(date) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SemesterDayCell(
    date: LocalDate,
    selected: Boolean,
    isToday: Boolean,
    hasLessons: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(35.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(when {
                selected -> ScheduleColors.indigo.copy(alpha = 0.24f)
                else -> Color.Transparent
            })
            .border(
                width = if (selected || isToday) 1.dp else 0.dp,
                color = when { selected -> ScheduleColors.indigo; isToday -> ScheduleColors.cyan.copy(alpha = 0.6f); else -> Color.Transparent },
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(date.dayOfMonth.toString(), color = if (enabled) ScheduleColors.foreground else ScheduleColors.dim.copy(alpha = 0.45f), fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Box(Modifier.padding(top = 2.dp).size(3.dp).clip(CircleShape).background(if (hasLessons) ScheduleColors.cyan else Color.Transparent))
    }
}

private fun monthLabel(monthNumber: Int): String = when (monthNumber) {
    1 -> "Январь"
    2 -> "Февраль"
    3 -> "Март"
    4 -> "Апрель"
    5 -> "Май"
    6 -> "Июнь"
    7 -> "Июль"
    8 -> "Август"
    9 -> "Сентябрь"
    10 -> "Октябрь"
    11 -> "Ноябрь"
    else -> "Декабрь"
}

@Composable
private fun WeekArrow(symbol: String, onClick: () -> Unit, enabled: Boolean) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(13.dp),
        color = if (enabled) ScheduleColors.surface else ScheduleColors.background,
        border = BorderStroke(1.dp, if (enabled) ScheduleColors.outline else ScheduleColors.outline.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.size(width = 44.dp, height = 42.dp), contentAlignment = Alignment.Center) {
            Text(symbol, color = if (enabled) ScheduleColors.foreground else ScheduleColors.dim.copy(alpha = 0.55f), fontSize = 25.sp)
        }
    }
}

@Composable
private fun DayPill(
    date: LocalDate,
    pillWidth: Dp,
    selected: Boolean,
    isToday: Boolean,
    hasLessons: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, animationSpec = tween(180), label = "day-select-scale")
    val borderColor by animateColorAsState(
        if (selected) ScheduleColors.indigo else if (isToday) ScheduleColors.cyan.copy(alpha = 0.6f) else ScheduleColors.outline,
        animationSpec = tween(180),
        label = "day-select-border",
    )
    Column(
        modifier = Modifier
            .width(pillWidth)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) ScheduleColors.indigo.copy(alpha = 0.16f) else ScheduleColors.surface)
            .border(1.dp, borderColor, RoundedCornerShape(17.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(weekdayShort(date), color = when {
            selected -> ScheduleColors.cyan
            enabled -> ScheduleColors.muted
            else -> ScheduleColors.dim.copy(alpha = 0.45f)
        }, fontSize = 9.sp, letterSpacing = 0.5.sp)
        Text(
            date.dayOfMonth.toString().padStart(2, '0'),
            color = if (enabled) ScheduleColors.foreground else ScheduleColors.dim.copy(alpha = 0.45f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> ScheduleColors.indigo
                        hasLessons -> ScheduleColors.mint
                        isToday -> ScheduleColors.cyan
                        else -> Color.Transparent
                    },
                ),
        )
    }
}

@Composable
private fun DaySummary(date: LocalDate, lessonCount: Int, pendingTaskCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SummaryTile(
            modifier = Modifier.weight(1f),
            eyebrow = "${weekdayLong(date).uppercase()} · ${date.toRuDate()}",
            value = lessonCount.toString().padStart(2, '0'),
            caption = "пар в расписании",
            accent = ScheduleColors.cyan,
        )
        SummaryTile(
            modifier = Modifier.weight(1f),
            eyebrow = "АКАДЕМИЧЕСКИЕ ЗАДАЧИ",
            value = pendingTaskCount.toString().padStart(2, '0'),
            caption = "открытых задач",
            accent = if (pendingTaskCount > 0) ScheduleColors.amber else ScheduleColors.mint,
        )
    }
}

@Composable
private fun SummaryTile(
    modifier: Modifier,
    eyebrow: String,
    value: String,
    caption: String,
    accent: Color,
) {
    Surface(
        modifier = modifier.heightIn(min = 98.dp),
        shape = RoundedCornerShape(19.dp),
        color = ScheduleColors.surface,
        border = BorderStroke(1.dp, ScheduleColors.outline),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(eyebrow, color = ScheduleColors.dim, fontSize = 8.sp, letterSpacing = 0.75.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(value, color = accent, fontSize = 26.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(caption, color = ScheduleColors.muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun LessonCard(
    item: LessonUiItem,
    onClick: () -> Unit,
    onRate: (Int) -> Unit,
    onAddSticker: () -> Unit,
    onToggleSticker: (String) -> Unit,
) {
    val lesson = item.lesson
    val accent = LessonThemeMapper.accent(lesson)
    val stroke by animateColorAsState(
        if (item.isCurrent) accent.copy(alpha = 0.85f) else ScheduleColors.outline,
        animationSpec = tween(250),
        label = "lesson-current-stroke",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .shadow(if (item.isCurrent) 14.dp else 0.dp, RoundedCornerShape(21.dp), ambientColor = accent.copy(alpha = 0.18f), spotColor = accent.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(21.dp),
        color = if (item.isCurrent) ScheduleColors.elevatedSurface else ScheduleColors.surface,
        border = BorderStroke(if (item.isCurrent) 1.2.dp else 0.7.dp, stroke),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 102.dp)
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(accent),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.width(61.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(lesson.startTime, color = ScheduleColors.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(lesson.endTime, color = ScheduleColors.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(1.dp))
                    Text("${lesson.slotNumber.takeIf { it > 0 } ?: "—"} ПАРА", color = ScheduleColors.dim, fontSize = 8.sp, letterSpacing = 0.4.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypeTag(lesson.type, accent)
                        if (item.isCurrent) TypeTagLabel("СЕЙЧАС", ScheduleColors.mint)
                        if (lesson.isCustom) TypeTagLabel("МОЁ", ScheduleColors.cyan)
                    }
                    Text(
                        lesson.subjectName.ifBlank { "Новая пара" },
                        color = ScheduleColors.foreground,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (lesson.teacherName.isNotBlank()) {
                        Text(lesson.teacherName, color = ScheduleColors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (lesson.classroom.isNotBlank()) {
                        Text("⌖  ${lesson.classroom}", color = ScheduleColors.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (lesson.notes.isNotBlank()) {
                        Text(
                            "Заметка · ${lesson.notes}",
                            color = ScheduleColors.cyan,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Surface(
                                onClick = onAddSticker,
                                modifier = Modifier.size(30.dp).semantics { contentDescription = "Добавить стикер" },
                                shape = RoundedCornerShape(9.dp),
                                color = ScheduleColors.elevatedSurface,
                                border = BorderStroke(1.dp, ScheduleColors.outline),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("+", color = ScheduleColors.muted, fontSize = 19.sp, lineHeight = 20.sp)
                                }
                            }
                            lesson.stickers.filter { it in LessonStickerPalette.choices }.forEach { sticker ->
                                Surface(
                                    onClick = { onToggleSticker(sticker) },
                                    modifier = Modifier.size(30.dp),
                                    shape = RoundedCornerShape(9.dp),
                                    color = accent.copy(alpha = 0.12f),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(sticker, color = ScheduleColors.foreground, fontSize = 17.sp)
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            (1..5).forEach { rating ->
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .clickable { onRate(rating) }
                                        .semantics { contentDescription = "Оценка $rating из 5" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (rating <= (lesson.rating ?: 0)) "★" else "☆",
                                        color = if (rating <= (lesson.rating ?: 0)) ScheduleColors.amber else ScheduleColors.dim,
                                        fontSize = 18.sp,
                                        lineHeight = 20.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                if (item.pendingTaskCount > 0) {
                    Surface(shape = CircleShape, color = accent.copy(alpha = 0.14f)) {
                        Text(
                            item.pendingTaskCount.toString(),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeTag(type: LessonType, accent: Color) {
    val label = when (type) {
        LessonType.LECTURE -> "ЛЕКЦИЯ"
        LessonType.LABORATORY -> "ЛАБОРАТОРНАЯ"
        LessonType.PRACTICE -> "ПРАКТИКА"
        LessonType.UNKNOWN -> "ЗАНЯТИЕ"
    }
    TypeTagLabel(label, accent)
}

@Composable
private fun TypeTagLabel(label: String, accent: Color) {
    Surface(shape = RoundedCornerShape(7.dp), color = accent.copy(alpha = 0.12f)) {
        Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = accent, fontSize = 8.sp, letterSpacing = 0.55.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ScheduleLoadingStatusCard(title: String, detail: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = ScheduleColors.elevatedSurface,
        border = BorderStroke(1.dp, ScheduleColors.cyan.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(21.dp), color = ScheduleColors.cyan, strokeWidth = 2.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = ScheduleColors.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = ScheduleColors.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ScheduleNotLoadedCard(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = ScheduleColors.surface,
        border = BorderStroke(1.dp, ScheduleColors.outline),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("РАСПИСАНИЕ ЕЩЁ НЕ ЗАГРУЖЕНО", color = ScheduleColors.cyan, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text("Чтобы загрузить расписание и сохранить его офлайн, откройте настройки и нажмите «Обновить расписание».", color = ScheduleColors.muted, fontSize = 11.sp, lineHeight = 16.sp)
            TextButton(onClick = onOpenSettings) { Text("Открыть настройки  →", color = ScheduleColors.cyan, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ScheduleErrorStatusCard(error: ScheduleErrorInfo, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ScheduleErrorPanel(
            error = error,
            context = "Локальная копия пока отсутствует. Сохранённые данные не изменены.",
        )
        TextButton(onClick = onOpenSettings) { Text("Открыть настройки  →", color = ScheduleColors.cyan, fontSize = 11.sp) }
    }
}

@Composable
private fun ScheduleRefreshingBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ScheduleColors.cyan.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, ScheduleColors.cyan.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(15.dp), color = ScheduleColors.cyan, strokeWidth = 1.7.dp)
            Text("Обновляем семестровый цикл · сохранённое расписание доступно", color = ScheduleColors.muted, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun CachedScheduleWarning(error: ScheduleErrorInfo, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ScheduleErrorPanel(
            error = error,
            context = "Показана сохранённая локальная копия расписания.",
        )
        TextButton(onClick = onOpenSettings) { Text("Открыть настройки  →", color = ScheduleColors.cyan, fontSize = 11.sp) }
    }
}

@Composable
private fun EmptyDayCard(date: LocalDate, onAddLesson: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ScheduleColors.surface,
        border = BorderStroke(1.dp, ScheduleColors.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(21.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text("◌", color = ScheduleColors.indigo, fontSize = 24.sp)
            Text("Окно для своих дел", color = ScheduleColors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("На ${weekdayLong(date).lowercase()} занятий нет. Можно добавить личную пару или оставить день свободным.", color = ScheduleColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
            Button(
                onClick = onAddLesson,
                colors = ButtonDefaults.buttonColors(containerColor = ScheduleColors.indigo, contentColor = ScheduleColors.background),
                shape = RoundedCornerShape(13.dp),
            ) { Text("＋ Своя пара", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun FirstProfileCard(onAdd: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = ScheduleColors.surface,
        border = BorderStroke(1.dp, ScheduleColors.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Добавьте расписание", color = ScheduleColors.foreground, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Найдите свою группу или преподавателя.",
                color = ScheduleColors.muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(containerColor = ScheduleColors.indigo, contentColor = ScheduleColors.background),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Найти расписание  →", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun SectionEyebrow(text: String, trailing: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = ScheduleColors.dim, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        trailing?.let { Text(it, color = ScheduleColors.dim, fontSize = 9.sp) }
    }
}

private fun Lesson.hasSchedulePositionChanged(original: Lesson): Boolean =
    lessonDate != original.lessonDate ||
        startTime != original.startTime ||
        endTime != original.endTime ||
        slotNumber != original.slotNumber

private fun Lesson.hasEditableChangesFrom(original: Lesson): Boolean =
    subjectName != original.subjectName ||
        teacherName != original.teacherName ||
        classroom != original.classroom ||
        lessonDate != original.lessonDate ||
        dayOfWeek != original.dayOfWeek ||
        startTime != original.startTime ||
        endTime != original.endTime ||
        slotNumber != original.slotNumber ||
        type != original.type

private fun LocalDate.startOfWeek(): LocalDate =
    minus(DatePeriod(days = dayOfWeek.isoDayNumber - 1))

private fun LocalDate.toRuDate(): String =
    "${dayOfMonth.toString().padStart(2, '0')} ${monthNameShort(monthNumber)}"

private fun weekdayShort(date: LocalDate): String = when (date.dayOfWeek.isoDayNumber) {
    1 -> "ПН"
    2 -> "ВТ"
    3 -> "СР"
    4 -> "ЧТ"
    5 -> "ПТ"
    6 -> "СБ"
    else -> "ВС"
}

private fun weekdayLong(date: LocalDate): String = when (date.dayOfWeek.isoDayNumber) {
    1 -> "Понедельник"
    2 -> "Вторник"
    3 -> "Среда"
    4 -> "Четверг"
    5 -> "Пятница"
    6 -> "Суббота"
    else -> "Воскресенье"
}

private fun monthNameShort(month: Int): String = when (month) {
    1 -> "янв"
    2 -> "фев"
    3 -> "мар"
    4 -> "апр"
    5 -> "май"
    6 -> "июн"
    7 -> "июл"
    8 -> "авг"
    9 -> "сен"
    10 -> "окт"
    11 -> "ноя"
    else -> "дек"
}

private fun weekNumberHint(monday: LocalDate): String = "${monday.dayOfMonth.toString().padStart(2, '0')}.${monday.monthNumber.toString().padStart(2, '0')}"
