package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.DeadlineReminderIntervals
import com.example.bruschedule.domain.model.DeadlineReminderPreferences
import com.example.bruschedule.domain.model.AcademicTrack
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.model.University
import com.example.bruschedule.domain.model.ScheduleErrorInfo
import com.example.bruschedule.domain.model.TypographyPreferences
import com.example.bruschedule.domain.model.TypographyRole
import com.example.bruschedule.domain.usecase.SemesterCoveragePolicy
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleAppTheme
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.presentation.ScheduleUiState
import com.example.bruschedule.presentation.ScheduleViewModel
import com.example.bruschedule.ui.theme.ScheduleColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

@Composable
fun ScheduleSettingsScreen(
    state: ScheduleUiState,
    viewModel: ScheduleViewModel,
    contentPadding: PaddingValues,
    focusScheduleManagement: Boolean = false,
) {
    val sync = state.syncState
    val scrollState = rememberScrollState()
    val scheduleManagementRequester = remember { BringIntoViewRequester() }
    var showDeleteConfirmation by remember(state.selectedProfile?.id) { mutableStateOf(false) }
    var profileSelectorExpanded by remember(state.selectedProfile?.id, state.profiles.size) { mutableStateOf(false) }
    LaunchedEffect(focusScheduleManagement) {
        if (focusScheduleManagement) {
            withFrameNanos { }
            scheduleManagementRequester.bringIntoView()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScheduleColors.background)
            .padding(contentPadding)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 21.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Настройки", color = ScheduleColors.foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("Оформление и напоминания.", color = ScheduleColors.muted, fontSize = 12.sp, lineHeight = 18.sp)

        AppearanceSettingsCard(
            state = state,
            onThemeSelected = viewModel::setTheme,
            onLessonColorSelected = viewModel::setLessonColor,
            onResetLessonColors = viewModel::resetLessonColors,
        )

        TypographySettingsCard(
            preferences = state.typography,
            onScaleChanged = viewModel::setTypographyScale,
            onResetAll = viewModel::resetTypographyScales,
        )

        SemesterScheduleSettingsCard(
            preferences = state.semesterSchedulePreferences,
            university = state.selectedUniversity,
            todayDate = state.todayDate,
            selectedProfileId = state.selectedProfile?.id,
            selectedProfileName = state.selectedProfile?.name,
            onTrackSelected = viewModel::setAcademicTrack,
            onUniversitySessionEndDateChanged = viewModel::setUniversitySessionEndDate,
            onProfileSessionEndDateChanged = viewModel::setProfileSessionEndDate,
        )

        DeadlineReminderSettingsCard(
            preferences = state.deadlineReminderPreferences,
            onChange = viewModel::setDeadlineReminderPreferences,
        )

        Surface(
            modifier = Modifier.bringIntoViewRequester(scheduleManagementRequester),
            shape = RoundedCornerShape(21.dp),
            color = ScheduleColors.surface,
            border = BorderStroke(1.dp, ScheduleColors.outline),
        ) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("УПРАВЛЕНИЕ РАСПИСАНИЕМ", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
                Box {
                    Surface(
                        onClick = { profileSelectorExpanded = state.profiles.isNotEmpty() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        color = ScheduleColors.elevatedSurface,
                        border = BorderStroke(1.dp, ScheduleColors.outline),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Выбранное расписание", color = ScheduleColors.dim, fontSize = 8.sp, letterSpacing = 0.6.sp)
                                Text(
                                    state.selectedProfile?.name ?: "Добавьте расписание",
                                    color = ScheduleColors.foreground,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    when {
                                        state.isRefreshing -> "Обновляем"
                                        sync?.lastError != null -> "Ошибка загрузки"
                                        sync?.lastSuccessfulAtEpochMillis != null -> "Синхронизировано"
                                        else -> "Не загружено"
                                    },
                                    color = when {
                                        state.isRefreshing -> ScheduleColors.amber
                                        sync?.lastError != null -> ScheduleColors.danger
                                        sync?.lastSuccessfulAtEpochMillis != null -> ScheduleColors.mint
                                        else -> ScheduleColors.dim
                                    },
                                    fontSize = 8.sp,
                                )
                                Text("⌄", color = ScheduleColors.cyan, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = profileSelectorExpanded,
                        onDismissRequest = { profileSelectorExpanded = false },
                    ) {
                        state.profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(profile.name, color = ScheduleColors.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (profile.type == ScheduleProfileType.GROUP) "Группа" else "Преподаватель",
                                            color = ScheduleColors.muted,
                                            fontSize = 9.sp,
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (profile.id == state.selectedProfile?.id) Text("✓", color = ScheduleColors.cyan, fontSize = 13.sp)
                                },
                                onClick = {
                                    profileSelectorExpanded = false
                                    viewModel.selectProfile(profile.id)
                                },
                            )
                        }
                    }
                }
                if (state.profiles.isEmpty()) {
                    Text("Сначала добавьте расписание на главном экране.", color = ScheduleColors.dim, fontSize = 9.sp)
                }
                HorizontalDividerLine()
                JournalLine("Последнее успешное обновление", sync?.lastSuccessfulAtEpochMillis?.let(::formatSyncTimestamp) ?: "Пока нет")
                JournalLine("Последняя попытка", sync?.lastAttemptAtEpochMillis?.let(::formatSyncTimestamp) ?: "Ещё не запускали")
                JournalLine("Локальный сейв", sync?.previousSnapshotAtEpochMillis?.let(::formatSyncTimestamp) ?: "Пока не создан")
                Text(
                    "Профиль не загружается автоматически. Для обновления нажмите кнопку ниже.",
                    color = ScheduleColors.dim,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )
                sync?.lastError?.let { error ->
                    ScheduleErrorPanel(
                        error = ScheduleErrorInfo.decodeStored(error),
                        context = "Последняя попытка обновления. Сохранённое расписание не удалено.",
                        onRetry = viewModel::refreshSelectedProfile,
                    )
                }
                Button(
                    onClick = viewModel::refreshSelectedProfile,
                    enabled = state.selectedProfile != null && !state.isRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ScheduleColors.indigo, contentColor = ScheduleColors.background),
                ) {
                    Text(if (state.isRefreshing) "Обновляем расписание…" else "Обновить расписание", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = viewModel::rollbackSelectedProfile,
                    enabled = sync?.canRollback == true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ScheduleColors.cyan),
                    border = BorderStroke(1.dp, if (sync?.canRollback == true) ScheduleColors.cyan.copy(alpha = 0.5f) else ScheduleColors.outline),
                ) {
                    Text("↶  Восстановить локальный сейв", fontWeight = FontWeight.Medium)
                }
                OutlinedButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = state.selectedProfile != null && !state.isRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ScheduleColors.danger),
                    border = BorderStroke(1.dp, ScheduleColors.danger.copy(alpha = 0.45f)),
                ) {
                    Text("Удалить это расписание", fontWeight = FontWeight.Medium)
                }
            }
        }

        InfoCard()
        Spacer(Modifier.height(12.dp))
    }

    if (showDeleteConfirmation) {
        val profileName = state.selectedProfile?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Удалить расписание?") },
            text = {
                Text(
                    "Профиль «$profileName», сохранённые пары, ручные правки и связанные задания будут удалены с устройства.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteSelectedProfile()
                    },
                ) {
                    Text("Удалить", color = ScheduleColors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Отмена", color = ScheduleColors.muted)
                }
            },
            containerColor = ScheduleColors.surface,
            titleContentColor = ScheduleColors.foreground,
            textContentColor = ScheduleColors.muted,
        )
    }
}

@Composable
private fun TypographySettingsCard(
    preferences: TypographyPreferences,
    onScaleChanged: (TypographyRole, Float) -> Unit,
    onResetAll: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(21.dp), color = ScheduleColors.surface, border = BorderStroke(1.dp, ScheduleColors.outline)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ТИПОГРАФИКА", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                Text("Размеры текста", color = ScheduleColors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Отдельные ползунки 100–150%; предпросмотр обновляется сразу.", color = ScheduleColors.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
            Surface(shape = RoundedCornerShape(14.dp), color = ScheduleColors.elevatedSurface) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("РАСПИСАНИЕ", color = ScheduleColors.foreground, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                    Text("Заголовок карточки", color = ScheduleColors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Обычный текст расписания на день", color = ScheduleColors.muted, fontSize = 13.sp)
                    Text("Мелкая подпись · время 09:40", color = ScheduleColors.dim, fontSize = 10.sp)
                }
            }

            TypographyScaleControl(
                role = TypographyRole.DISPLAY,
                title = "Крупные заголовки · от 22 sp",
                scale = preferences.displayScale,
                onScaleChanged = onScaleChanged,
            )
            HorizontalDividerLine()
            TypographyScaleControl(
                role = TypographyRole.HEADING,
                title = "Заголовки · 15–21 sp",
                scale = preferences.headingScale,
                onScaleChanged = onScaleChanged,
            )
            HorizontalDividerLine()
            TypographyScaleControl(
                role = TypographyRole.BODY,
                title = "Основной текст · 11–14 sp",
                scale = preferences.bodyScale,
                onScaleChanged = onScaleChanged,
            )
            HorizontalDividerLine()
            TypographyScaleControl(
                role = TypographyRole.CAPTION,
                title = "Подписи · до 10 sp",
                scale = preferences.captionScale,
                onScaleChanged = onScaleChanged,
            )
            TextButton(onClick = onResetAll) {
                Text("Сбросить все размеры к 100%", color = ScheduleColors.cyan, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TypographyScaleControl(
    role: TypographyRole,
    title: String,
    scale: Float,
    onScaleChanged: (TypographyRole, Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, color = ScheduleColors.foreground, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text("${(scale * 100).roundToInt()}%", color = ScheduleColors.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = scale,
            onValueChange = { onScaleChanged(role, it) },
            valueRange = TypographyPreferences.MIN_FONT_SCALE..TypographyPreferences.MAX_FONT_SCALE,
            steps = 0,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = ScheduleColors.cyan,
                activeTrackColor = ScheduleColors.indigo,
                inactiveTrackColor = ScheduleColors.outline,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("100%", color = ScheduleColors.dim, fontSize = 9.sp)
            Text("150%", color = ScheduleColors.dim, fontSize = 9.sp)
        }
    }
}

private const val PROJECT_TELEGRAM_BOT_URL = "https://t.me/schedule_suggestion_bot"

@Composable
private fun SemesterScheduleSettingsCard(
    preferences: SemesterSchedulePreferences,
    university: University,
    todayDate: LocalDate,
    selectedProfileId: String?,
    selectedProfileName: String?,
    onTrackSelected: (String, AcademicTrack) -> Unit,
    onUniversitySessionEndDateChanged: (String, String?) -> Unit,
    onProfileSessionEndDateChanged: (String, String?) -> Unit,
) {
    val universityId = university.id
    val selectedTrack = preferences.trackForUniversity(universityId)
    val universityDate = preferences.sessionEndDateIsoForUniversity(universityId)
    val profileDateOverride = selectedProfileId?.let(preferences.profileSessionEndDatesIso::get)
    val profileDate = profileDateOverride ?: universityDate
    var universityDateText by remember(universityId, selectedTrack, universityDate) {
        mutableStateOf(formatIsoDateForInput(universityDate))
    }
    var profileDateText by remember(selectedProfileId, profileDate) {
        mutableStateOf(formatIsoDateForInput(profileDate))
    }
    Surface(shape = RoundedCornerShape(21.dp), color = ScheduleColors.surface, border = BorderStroke(1.dp, ScheduleColors.outline)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("СЕССИЯ ${academicYearLabel(todayDate)} · ${university.name.uppercase()}", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                Text("Период календаря", color = ScheduleColors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Расписание доступно с сегодняшнего дня до недели после окончания сессии. Даты по умолчанию ориентировочные — проверьте их для своего факультета и курса.", color = ScheduleColors.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
            AcademicTrack.availableForUniversity(universityId).forEach { track ->
                val selected = selectedTrack == track
                Surface(
                    onClick = { onTrackSelected(universityId, track) },
                    shape = RoundedCornerShape(13.dp),
                    color = if (selected) ScheduleColors.indigo.copy(alpha = 0.14f) else ScheduleColors.elevatedSurface,
                    border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo else ScheduleColors.outline),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(track.label, color = ScheduleColors.foreground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                sessionTrackHint(track),
                                color = if (selected) ScheduleColors.cyan else ScheduleColors.muted,
                                fontSize = 9.sp,
                                lineHeight = 13.sp,
                            )
                        }
                        Text(if (selected) "✓" else "○", color = if (selected) ScheduleColors.cyan else ScheduleColors.dim, fontSize = 14.sp)
                    }
                }
            }
            OutlinedTextField(
                value = universityDateText,
                onValueChange = { value ->
                    val formatted = maskRussianDate(value)
                    universityDateText = formatted
                    if (formatted.length == 10) {
                        parseRussianDate(formatted)?.let { date ->
                            onUniversitySessionEndDateChanged(universityId, date)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = typographyTextStyle(TypographyRole.BODY),
                label = { Text("Последний день сессии для вуза") },
                placeholder = { Text("ДД.ММ.ГГГГ") },
                singleLine = true,
            )
            if (preferences.hasUniversitySessionEndDateOverride(universityId)) {
                TextButton(onClick = { onUniversitySessionEndDateChanged(universityId, null) }) {
                    Text("Использовать дату по умолчанию для вуза", color = ScheduleColors.cyan, fontSize = 10.sp)
                }
            }
            Text(
                "Покрытие автоматически продлевается ещё на ${SemesterCoveragePolicy.DAYS_AFTER_SESSION} дней после указанной даты.",
                color = ScheduleColors.dim,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )
            if (selectedProfileId != null) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val profileLabel = selectedProfileName ?: "выбранного расписания"
                    Text("ОТДЕЛЬНО ДЛЯ ЭТОГО РАСПИСАНИЯ", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 0.9.sp)
                    Text(
                        "Факультеты могут заканчивать сессию в разные даты. Для «$profileLabel» можно задать свою; календарь захватит ещё 7 дней.",
                        color = ScheduleColors.muted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                    OutlinedTextField(
                        value = profileDateText,
                        onValueChange = { value ->
                            val formatted = maskRussianDate(value)
                            profileDateText = formatted
                            if (formatted.length == 10) {
                                parseRussianDate(formatted)?.let { date ->
                                    onProfileSessionEndDateChanged(selectedProfileId, date)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = typographyTextStyle(TypographyRole.BODY),
                        label = { Text("Последний день сессии для расписания") },
                        placeholder = { Text("ДД.ММ.ГГГГ") },
                        singleLine = true,
                    )
                    if (profileDateOverride != null) {
                        TextButton(onClick = { onProfileSessionEndDateChanged(selectedProfileId, null) }) {
                            Text("Использовать дату вуза", color = ScheduleColors.cyan, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun academicYearLabel(date: LocalDate): String {
    val startYear = if (date.monthNumber >= 9) date.year else date.year - 1
    return "$startYear/${(startYear + 1).toString().takeLast(2)}"
}

private fun sessionTrackHint(track: AcademicTrack): String {
    val end = runCatching { LocalDate.parse(track.defaultSessionEndDateIso) }.getOrNull() ?: return track.hint
    val calendarEnd = end.plus(DatePeriod(days = SemesterCoveragePolicy.DAYS_AFTER_SESSION))
    return "${track.hint} · дата по умолчанию до ${end.toRussianDate()}, календарь до ${calendarEnd.toRussianDate()}"
}

private fun formatIsoDateForInput(isoDate: String): String =
    runCatching { LocalDate.parse(isoDate).toRussianDate() }.getOrDefault("")

private fun maskRussianDate(value: String): String {
    val digits = value.filter(Char::isDigit).take(8)
    return when {
        digits.length <= 2 -> digits
        digits.length <= 4 -> "${digits.take(2)}.${digits.drop(2)}"
        else -> "${digits.take(2)}.${digits.substring(2, 4)}.${digits.drop(4)}"
    }
}

private fun parseRussianDate(value: String): String? {
    val parts = value.split('.')
    if (parts.size != 3) return null
    val day = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    return runCatching { LocalDate(year, month, day).toString() }.getOrNull()
}

private fun LocalDate.toRussianDate(): String =
    "${dayOfMonth.toString().padStart(2, '0')}.${monthNumber.toString().padStart(2, '0')}.$year"

@Composable
private fun DeadlineReminderSettingsCard(
    preferences: DeadlineReminderPreferences,
    onChange: (DeadlineReminderPreferences) -> Unit,
) {
    Surface(shape = RoundedCornerShape(21.dp), color = ScheduleColors.surface, border = BorderStroke(1.dp, ScheduleColors.outline)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ДЕДЛАЙНЫ", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                    Text("Напоминания", color = ScheduleColors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Общие значения для задач с режимом «По умолчанию».", color = ScheduleColors.muted, fontSize = 10.sp, lineHeight = 14.sp)
                }
                Switch(
                    checked = preferences.enabled,
                    onCheckedChange = { onChange(preferences.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ScheduleColors.cyan,
                        checkedTrackColor = ScheduleColors.indigo,
                        uncheckedThumbColor = ScheduleColors.dim,
                        uncheckedTrackColor = ScheduleColors.elevatedSurface,
                        uncheckedBorderColor = ScheduleColors.outline,
                    ),
                )
            }
            if (preferences.enabled) {
                HorizontalDividerLine()
                Text("НАПОМИНАТЬ ЗАРАНЕЕ", color = ScheduleColors.dim, fontSize = 8.sp, letterSpacing = 0.9.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DeadlineReminderIntervals.choices.forEach { minutes ->
                        val selected = minutes in preferences.offsetsMinutes
                        Surface(
                            onClick = {
                                val next = if (selected) {
                                    if (preferences.offsetsMinutes.size <= 1) preferences.offsetsMinutes
                                    else preferences.offsetsMinutes - minutes
                                } else {
                                    preferences.offsetsMinutes + minutes
                                }
                                onChange(preferences.copy(offsetsMinutes = next.distinct().sortedDescending()))
                            },
                            shape = RoundedCornerShape(11.dp),
                            color = if (selected) ScheduleColors.indigo.copy(alpha = 0.17f) else ScheduleColors.elevatedSurface,
                            border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo else ScheduleColors.outline),
                        ) {
                            Text(
                                DeadlineReminderIntervals.label(minutes),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                color = if (selected) ScheduleColors.cyan else ScheduleColors.muted,
                                fontSize = 9.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Text("Можно выбрать несколько интервалов. Уведомления доставляются устройством и работают без сети.", color = ScheduleColors.dim, fontSize = 9.sp, lineHeight = 13.sp)
            } else {
                Text("Все напоминания о дедлайнах приостановлены.", color = ScheduleColors.dim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun InfoCard() {
    val uriHandler = LocalUriHandler.current
    Surface(shape = RoundedCornerShape(21.dp), color = ScheduleColors.surface, border = BorderStroke(1.dp, ScheduleColors.outline)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ОБРАТНАЯ СВЯЗЬ", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { uriHandler.openUri(PROJECT_TELEGRAM_BOT_URL) }) {
                Text("Telegram-бот ↗", color = ScheduleColors.cyan, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AppearanceSettingsCard(
    state: ScheduleUiState,
    onThemeSelected: (ScheduleAppTheme) -> Unit,
    onLessonColorSelected: (LessonType, String) -> Unit,
    onResetLessonColors: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(21.dp),
        color = ScheduleColors.surface,
        border = BorderStroke(1.dp, ScheduleColors.outline),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ВНЕШНИЙ ВИД", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                Text("Своя палитра", color = ScheduleColors.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Тема и акценты сохраняются на устройстве и применяются сразу.", color = ScheduleColors.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ScheduleAppTheme.entries.forEach { theme ->
                    ThemeChoice(
                        theme = theme,
                        selected = state.appearance.theme == theme,
                        onClick = { onThemeSelected(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDividerLine()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ЦВЕТА ЗАНЯТИЙ", color = ScheduleColors.foreground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("Выберите акцент для каждого типа пары", color = ScheduleColors.muted, fontSize = 9.sp)
                }
                TextButton(onClick = onResetLessonColors) {
                    Text("Сбросить", color = ScheduleColors.cyan, fontSize = 10.sp)
                }
            }
            LessonAccentEditor(
                label = "Лекция",
                type = LessonType.LECTURE,
                colorHex = state.appearance.lessonAccents.lecture,
                onColorSelected = onLessonColorSelected,
            )
            LessonAccentEditor(
                label = "Лабораторная",
                type = LessonType.LABORATORY,
                colorHex = state.appearance.lessonAccents.laboratory,
                onColorSelected = onLessonColorSelected,
            )
            LessonAccentEditor(
                label = "Практика",
                type = LessonType.PRACTICE,
                colorHex = state.appearance.lessonAccents.practice,
                onColorSelected = onLessonColorSelected,
            )
            LessonAccentEditor(
                label = "Другое",
                type = LessonType.UNKNOWN,
                colorHex = state.appearance.lessonAccents.unknown,
                onColorSelected = onLessonColorSelected,
            )
        }
    }
}

@Composable
private fun ThemeChoice(
    theme: ScheduleAppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val preview = themePreview(theme)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (selected) ScheduleColors.elevatedSurface else ScheduleColors.surface,
        border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo else ScheduleColors.outline),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(31.dp),
                shape = RoundedCornerShape(8.dp),
                color = preview.background,
                border = BorderStroke(1.dp, preview.outline),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(Modifier.size(width = 25.dp, height = 5.dp).background(preview.accent, RoundedCornerShape(4.dp)))
                    Text("Aa", color = preview.foreground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(theme.label, color = ScheduleColors.foreground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                when (theme) {
                    ScheduleAppTheme.BLUE -> "Насыщенный синий"
                    ScheduleAppTheme.BLACK -> "AMOLED"
                    ScheduleAppTheme.WHITE -> "Светлая"
                },
                color = ScheduleColors.dim,
                fontSize = 7.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LessonAccentEditor(
    label: String,
    type: LessonType,
    colorHex: String,
    onColorSelected: (LessonType, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = ScheduleColors.muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(colorHex.uppercase(), color = ScheduleColors.dim, fontSize = 9.sp, letterSpacing = 0.4.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LESSON_COLOR_PRESETS.forEach { color ->
                ColorSwatch(
                    colorHex = color,
                    selected = colorHex.equals(color, ignoreCase = true),
                    onClick = { onColorSelected(type, color) },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(colorHex: String, selected: Boolean, onClick: () -> Unit) {
    val color = com.example.bruschedule.ui.theme.LessonThemeMapper.parseHex(colorHex) ?: ScheduleColors.indigo
    Box(
        modifier = Modifier
            .size(25.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, if (selected) ScheduleColors.foreground else ScheduleColors.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", color = if (color.luminance() < 0.48f) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private data class ThemePreview(
    val background: Color,
    val accent: Color,
    val foreground: Color,
    val outline: Color,
)

private fun themePreview(theme: ScheduleAppTheme): ThemePreview = when (theme) {
    ScheduleAppTheme.BLUE -> ThemePreview(Color(0xFF0A0D12), Color(0xFF8FD9F4), Color(0xFFF2F5FA), Color(0xFF263241))
    ScheduleAppTheme.BLACK -> ThemePreview(Color.Black, Color(0xFF83A9FF), Color(0xFFF5F7FA), Color(0xFF262B35))
    ScheduleAppTheme.WHITE -> ThemePreview(Color(0xFFF4F6FA), Color(0xFF3C57D8), Color(0xFF152033), Color(0xFFD8DFEA))
}

private val LESSON_COLOR_PRESETS = listOf(
    "#A7B9FF",
    "#FF8998",
    "#FFC979",
    "#8FD9F4",
    "#8FDFC0",
    "#B89BFF",
    "#F4B7D4",
    "#A6B0C2",
)

@Composable
private fun JournalLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = ScheduleColors.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(value, color = ScheduleColors.foreground, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HorizontalDividerLine() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(ScheduleColors.outline))
}

private fun formatSyncTimestamp(epochMillis: Long): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val date = dateTime.date
    val time = dateTime.time
    return "${date.dayOfMonth.toString().padStart(2, '0')}.${date.monthNumber.toString().padStart(2, '0')}.${date.year} · ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
