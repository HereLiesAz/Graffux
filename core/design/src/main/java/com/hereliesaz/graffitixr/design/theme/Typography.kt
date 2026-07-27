package com.hereliesaz.graffitixr.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.design.R

val BlackoutFontFamily = FontFamily(
    Font(R.font.blackout_midnight),
)

// Set of Material typography styles to start with.
// Body/title/label sizes are set for comfortable reading on phone screens.
// Display/headline sizes are decorative and stay large.
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.9.sp
    ),
    titleLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 19.sp,
        letterSpacing = 9.sp
    ),
    labelSmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.9.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp
    ),
    displayLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 25.sp,
        lineHeight = 28.sp,
        letterSpacing = 9.sp
    ),
    displaySmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 9.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 25.sp,
        lineHeight = 22.sp,
        letterSpacing = 9.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 9.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 9.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.12.sp
    ),
    titleSmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.9.sp
    ),
    labelMedium = TextStyle(
        fontFamily = BlackoutFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.9.sp
    )
)
