package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.bruschedule.ui.theme.ScheduleColors

@Composable
internal fun BottomSheetHandle() {
    Box(
        Modifier
            .padding(top = 14.dp, bottom = 6.dp)
            .width(38.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(ScheduleColors.dim),
    )
}
