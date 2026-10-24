package com.example.bruschedule.domain.model

data class TypographyPreferences(
    val displayScale: Float = DEFAULT_FONT_SCALE,
    val headingScale: Float = DEFAULT_FONT_SCALE,
    val bodyScale: Float = DEFAULT_FONT_SCALE,
    val captionScale: Float = DEFAULT_FONT_SCALE,
) {
    fun scaleFor(role: TypographyRole): Float = when (role) {
        TypographyRole.DISPLAY -> displayScale
        TypographyRole.HEADING -> headingScale
        TypographyRole.BODY -> bodyScale
        TypographyRole.CAPTION -> captionScale
    }

    fun withScale(role: TypographyRole, scale: Float): TypographyPreferences {
        val normalizedScale = normalizeScale(scale)
        return when (role) {
            TypographyRole.DISPLAY -> copy(displayScale = normalizedScale)
            TypographyRole.HEADING -> copy(headingScale = normalizedScale)
            TypographyRole.BODY -> copy(bodyScale = normalizedScale)
            TypographyRole.CAPTION -> copy(captionScale = normalizedScale)
        }
    }

    fun normalized(): TypographyPreferences = copy(
        displayScale = normalizeScale(displayScale),
        headingScale = normalizeScale(headingScale),
        bodyScale = normalizeScale(bodyScale),
        captionScale = normalizeScale(captionScale),
    )

    companion object {
        const val MIN_FONT_SCALE = 1.0f
        const val DEFAULT_FONT_SCALE = 1.1f
        const val MAX_FONT_SCALE = 1.5f

        fun normalizeScale(value: Float): Float =
            if (value.isNaN() || value.isInfinite()) DEFAULT_FONT_SCALE
            else value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }
}

enum class TypographyRole {
    DISPLAY,
    HEADING,
    BODY,
    CAPTION,
}
