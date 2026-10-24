package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.TypographyRole
import com.example.bruschedule.domain.model.University
import com.example.bruschedule.presentation.ProfileSearchUiState
import com.example.bruschedule.ui.theme.ScheduleColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileBottomSheet(
    searchState: ProfileSearchUiState,
    university: University,
    onClose: () -> Unit,
    onSearch: (ScheduleProfileType, String) -> Unit,
    onSelectCandidate: (ProfileCandidate) -> Unit,
) {
    var selectedType by remember(university.id) { mutableStateOf(searchState.type) }
    var query by remember(university.id) { mutableStateOf(searchState.query) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = ScheduleColors.surface,
        contentColor = ScheduleColors.foreground,
        dragHandle = { BottomSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text("НОВОЕ РАСПИСАНИЕ", color = ScheduleColors.cyan, fontSize = 9.sp, letterSpacing = 1.3.sp)
            Text("Найти профиль", color = ScheduleColors.foreground, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${university.name}. Введите код группы или имя преподавателя.",
                color = ScheduleColors.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTypeChip("Группа", selectedType == ScheduleProfileType.GROUP) {
                    selectedType = ScheduleProfileType.GROUP
                }
                ProfileTypeChip("Преподаватель", selectedType == ScheduleProfileType.TEACHER) {
                    selectedType = ScheduleProfileType.TEACHER
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = typographyTextStyle(TypographyRole.BODY),
                singleLine = true,
                label = {
                    Text(if (selectedType == ScheduleProfileType.GROUP) "Код или название группы" else "Имя преподавателя")
                },
                placeholder = {
                    Text(if (selectedType == ScheduleProfileType.GROUP) "Например, МПР-261 или 41" else "Например, Агеева Т.Н.")
                },
                shape = RoundedCornerShape(14.dp),
            )
            Button(
                onClick = { onSearch(selectedType, query.trim()) },
                enabled = !searchState.loading && query.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ScheduleColors.indigo, contentColor = ScheduleColors.background),
            ) {
                if (searchState.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp,
                        color = ScheduleColors.background,
                    )
                    Spacer(Modifier.width(9.dp))
                }
                Text(if (searchState.loading) "Ищем расписание…" else "Искать расписание", fontWeight = FontWeight.SemiBold)
            }

            searchState.error?.let { error ->
                ScheduleErrorPanel(error = error, onRetry = { onSearch(selectedType, query.trim()) })
            }
            searchState.candidates.forEach { candidate ->
                ProfileCandidateRow(candidate = candidate, onClick = { onSelectCandidate(candidate) })
            }
            if (!searchState.loading && searchState.candidates.isEmpty() && query.isNotBlank() && searchState.error == null) {
                Text(
                    "Ничего не найдено. Проверьте запрос или попробуйте позже.",
                    color = ScheduleColors.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("Закрыть", color = ScheduleColors.muted)
            }
        }
    }
}

@Composable
private fun ProfileTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) ScheduleColors.indigo.copy(alpha = 0.14f) else ScheduleColors.elevatedSurface,
        border = BorderStroke(1.dp, if (selected) ScheduleColors.indigo else ScheduleColors.outline),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            color = if (selected) ScheduleColors.foreground else ScheduleColors.muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ProfileCandidateRow(candidate: ProfileCandidate, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = candidate.scheduleAvailable,
        shape = RoundedCornerShape(14.dp),
        color = ScheduleColors.elevatedSurface,
        border = BorderStroke(1.dp, ScheduleColors.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    candidate.name,
                    color = if (candidate.scheduleAvailable) ScheduleColors.foreground else ScheduleColors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    candidate.subtitle.ifBlank { if (candidate.type == ScheduleProfileType.GROUP) "Группа" else "Преподаватель" },
                    color = ScheduleColors.muted,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (candidate.scheduleAvailable) "＋" else "…",
                modifier = Modifier.padding(start = 10.dp),
                color = if (candidate.scheduleAvailable) ScheduleColors.cyan else ScheduleColors.dim,
                fontSize = 18.sp,
            )
        }
    }
}
