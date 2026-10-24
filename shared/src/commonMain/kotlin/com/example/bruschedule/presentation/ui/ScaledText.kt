package com.example.bruschedule.presentation.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.TypographyPreferences
import com.example.bruschedule.domain.model.TypographyRole

val LocalTypographyPreferences = staticCompositionLocalOf { TypographyPreferences() }

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    val preferences = LocalTypographyPreferences.current
    val baseFontSize = when {
        fontSize != TextUnit.Unspecified -> fontSize
        style.fontSize != TextUnit.Unspecified -> style.fontSize
        else -> 14.sp
    }
    val role = when {
        baseFontSize.value >= DISPLAY_TEXT_MIN_SP -> TypographyRole.DISPLAY
        baseFontSize.value >= HEADING_TEXT_MIN_SP -> TypographyRole.HEADING
        baseFontSize.value >= BODY_TEXT_MIN_SP -> TypographyRole.BODY
        else -> TypographyRole.CAPTION
    }
    val scale = preferences.scaleFor(role)

    MaterialText(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize.scaledBy(scale),
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing.scaledBy(scale),
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight.scaledBy(scale),
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style.scaledBy(scale),
    )
}

@Composable
fun typographyTextStyle(role: TypographyRole, base: TextStyle = LocalTextStyle.current): TextStyle =
    base.scaledBy(LocalTypographyPreferences.current.scaleFor(role))

private fun TextStyle.scaledBy(scale: Float): TextStyle = copy(
    fontSize = fontSize.scaledBy(scale),
    letterSpacing = letterSpacing.scaledBy(scale),
    lineHeight = lineHeight.scaledBy(scale),
)

private fun TextUnit.scaledBy(scale: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else this * scale

private const val DISPLAY_TEXT_MIN_SP = 22f
private const val HEADING_TEXT_MIN_SP = 15f
private const val BODY_TEXT_MIN_SP = 11f
