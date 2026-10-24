package com.example.bruschedule

import com.example.bruschedule.domain.model.TypographyPreferences
import com.example.bruschedule.domain.model.TypographyRole
import kotlin.test.Test
import kotlin.test.assertEquals

class TypographyPreferencesTest {
    @Test
    fun everyTextRoleHasAnIndependentContinuousScaleWithinOneHundredAndOneHundredFiftyPercent() {
        val adjusted = TypographyPreferences().withScale(TypographyRole.BODY, 1.37f)

        assertEquals(1.1f, adjusted.displayScale)
        assertEquals(1.1f, adjusted.headingScale)
        assertEquals(1.37f, adjusted.bodyScale)
        assertEquals(1.1f, adjusted.captionScale)
        assertEquals(1.0f, TypographyPreferences.normalizeScale(0.5f))
        assertEquals(1.5f, TypographyPreferences.normalizeScale(2f))
    }

    @Test
    fun nonFiniteScaleFallsBackToDefault() {
        assertEquals(TypographyPreferences.DEFAULT_FONT_SCALE, TypographyPreferences.normalizeScale(Float.NaN))
        assertEquals(TypographyPreferences.DEFAULT_FONT_SCALE, TypographyPreferences.normalizeScale(Float.POSITIVE_INFINITY))
    }
}
