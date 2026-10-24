package com.example.bruschedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bruschedule.data.AppGraph
import com.example.bruschedule.presentation.MainDestination
import com.example.bruschedule.presentation.ScheduleViewModel
import com.example.bruschedule.presentation.ui.DeadlinesDashboardScreen
import com.example.bruschedule.presentation.ui.LocalTypographyPreferences
import com.example.bruschedule.presentation.ui.ScheduleHomeScreen
import com.example.bruschedule.presentation.ui.ScheduleSettingsScreen
import com.example.bruschedule.presentation.ui.Text
import com.example.bruschedule.ui.theme.ScheduleColors
import com.example.bruschedule.ui.theme.ScheduleTheme

@Composable
fun App(graph: AppGraph) {
    val viewModel: ScheduleViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ScheduleViewModel(
                    schedules = graph.scheduleRepository,
                    syncRepository = graph.syncRepository,
                    tasks = graph.taskRepository,
                    profileCatalog = graph.profileCatalog,
                    refreshSchedule = graph.refreshSchedule,
                    appearancePreferences = graph.appearancePreferences,
                    deadlineReminderPreferencesRepository = graph.deadlineReminderPreferences,
                    semesterSchedulePreferencesRepository = graph.semesterSchedulePreferences,
                    dailyMessagesRepository = graph.dailyMessages,
                    typographyPreferencesRepository = graph.typographyPreferences,
                    universityRepository = graph.universityRepository,
                    deadlineNotificationScheduler = graph.deadlineNotificationScheduler,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    ScheduleTheme(appearance = state.appearance) {
        CompositionLocalProvider(LocalTypographyPreferences provides state.typography) {
            Scaffold(
                containerColor = ScheduleColors.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.height(88.dp),
                        containerColor = ScheduleColors.surface,
                        tonalElevation = 4.dp,
                    ) {
                        NavigationDestination(
                            selected = state.destination == MainDestination.SCHEDULE,
                            destination = MainDestination.SCHEDULE,
                            label = "Расписание",
                            onClick = { viewModel.showDestination(MainDestination.SCHEDULE) },
                        )
                        NavigationDestination(
                            selected = state.destination == MainDestination.DEADLINES,
                            destination = MainDestination.DEADLINES,
                            label = "Дедлайны",
                            onClick = { viewModel.showDestination(MainDestination.DEADLINES) },
                        )
                        NavigationDestination(
                            selected = state.destination == MainDestination.SETTINGS || state.destination == MainDestination.SCHEDULE_MANAGEMENT,
                            destination = MainDestination.SETTINGS,
                            label = "Настройки",
                            onClick = { viewModel.showDestination(MainDestination.SETTINGS) },
                        )
                    }
                },
            ) { innerPadding ->
                AnimatedContent(
                    targetState = state.destination,
                    modifier = Modifier,
                    transitionSpec = {
                        (fadeIn() + slideInHorizontally { it / 18 }) togetherWith
                            (fadeOut() + slideOutHorizontally { -it / 18 })
                    },
                    label = "main-navigation",
                ) { destination ->
                    when (destination) {
                        MainDestination.SCHEDULE -> ScheduleHomeScreen(state, viewModel, innerPadding)
                        MainDestination.DEADLINES -> DeadlinesDashboardScreen(state, viewModel, innerPadding)
                        MainDestination.SETTINGS -> ScheduleSettingsScreen(state, viewModel, innerPadding)
                        MainDestination.SCHEDULE_MANAGEMENT -> ScheduleSettingsScreen(
                            state = state,
                            viewModel = viewModel,
                            contentPadding = innerPadding,
                            focusScheduleManagement = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavigationDestination(
    selected: Boolean,
    destination: MainDestination,
    label: String,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = if (selected) ScheduleColors.indigo.copy(alpha = 0.16f) else Color.Transparent,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NavigationGlyph(
                        destination = destination,
                        color = if (selected) ScheduleColors.cyan else ScheduleColors.muted,
                        accent = if (selected) ScheduleColors.cyan else ScheduleColors.muted,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        },
        label = {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) ScheduleColors.foreground else ScheduleColors.muted,
            )
        },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = ScheduleColors.cyan,
            unselectedIconColor = ScheduleColors.muted,
            selectedTextColor = ScheduleColors.foreground,
            unselectedTextColor = ScheduleColors.muted,
            indicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun NavigationGlyph(
    destination: MainDestination,
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        when (destination) {
            MainDestination.SCHEDULE -> drawCalendarGlyph(color, accent)
            MainDestination.DEADLINES -> drawChecklistGlyph(color, accent)
            MainDestination.SETTINGS, MainDestination.SCHEDULE_MANAGEMENT -> drawSettingsGlyph(color)
        }
    }
}

private fun DrawScope.drawCalendarGlyph(color: Color, accent: Color) {
    val unit = minOf(size.width, size.height) / 24f
    val stroke = 1.8f * unit
    drawRoundRect(
        color = color,
        topLeft = Offset(3.6f * unit, 5f * unit),
        size = Size(16.8f * unit, 16f * unit),
        cornerRadius = CornerRadius(3f * unit),
        style = Stroke(width = stroke),
    )
    drawLine(color, Offset(3.8f * unit, 10.2f * unit), Offset(20.2f * unit, 10.2f * unit), stroke, StrokeCap.Round)
    listOf(8f, 16f).forEach { x ->
        drawLine(color, Offset(x * unit, 3f * unit), Offset(x * unit, 7.4f * unit), stroke, StrokeCap.Round)
    }
    for (row in 0..1) {
        for (column in 0..2) {
            val x = (6f + column * 5f) * unit
            val y = (13.2f + row * 4.2f) * unit
            drawRoundRect(
                color = if (row == 0 && column == 1) accent else color,
                topLeft = Offset(x, y),
                size = Size(2.4f * unit, 2.4f * unit),
                cornerRadius = CornerRadius(0.7f * unit),
            )
        }
    }
}

private fun DrawScope.drawChecklistGlyph(color: Color, accent: Color) {
    val unit = minOf(size.width, size.height) / 24f
    val stroke = 1.7f * unit
    drawRoundRect(
        color = color,
        topLeft = Offset(4.3f * unit, 4f * unit),
        size = Size(15.4f * unit, 17.3f * unit),
        cornerRadius = CornerRadius(2.8f * unit),
        style = Stroke(width = stroke),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(9f * unit, 2.6f * unit),
        size = Size(6f * unit, 4.2f * unit),
        cornerRadius = CornerRadius(1.4f * unit),
    )
    for (row in 0..2) {
        val y = (9.3f + row * 4.8f) * unit
        drawLine(color, Offset(7f * unit, y), Offset(8.2f * unit, y + 1.2f * unit), stroke, StrokeCap.Round)
        drawLine(accent, Offset(8.2f * unit, y + 1.2f * unit), Offset(10.2f * unit, y - 1.4f * unit), stroke, StrokeCap.Round)
        drawLine(color, Offset(12f * unit, y), Offset(17.5f * unit, y), stroke, StrokeCap.Round)
    }
}

private fun DrawScope.drawSettingsGlyph(color: Color) {
    val unit = minOf(size.width, size.height) / 24f
    val center = Offset(size.width / 2f, size.height / 2f)
    val diagonals = 0.7071068f
    val directions = listOf(
        Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f),
        Offset(diagonals, diagonals), Offset(-diagonals, diagonals),
        Offset(diagonals, -diagonals), Offset(-diagonals, -diagonals),
    )
    directions.forEach { direction ->
        drawLine(
            color = color,
            start = Offset(center.x + direction.x * 7f * unit, center.y + direction.y * 7f * unit),
            end = Offset(center.x + direction.x * 9f * unit, center.y + direction.y * 9f * unit),
            strokeWidth = 2.5f * unit,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(color, radius = 7f * unit, center = center, style = Stroke(width = 2f * unit))
    drawCircle(color, radius = 2.6f * unit, center = center, style = Stroke(width = 1.8f * unit))
}
