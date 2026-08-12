package com.hereliesaz.graffitixr.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.design.R

import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font as GoogleFontCompat

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val RobotoCondensedFont = GoogleFont("Roboto Condensed")

val RobotoCondensedFamily = FontFamily(
    GoogleFontCompat(googleFont = RobotoCondensedFont, fontProvider = provider),
    GoogleFontCompat(googleFont = RobotoCondensedFont, fontProvider = provider, weight = FontWeight.Medium),
    GoogleFontCompat(googleFont = RobotoCondensedFont, fontProvider = provider, weight = FontWeight.Bold)
)

// Set of Material typography styles to start with.
// Body/title/label sizes are set for comfortable reading on phone screens.
// Display/headline sizes are decorative and stay large.
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.9.sp
    ),
    titleLarge = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.9.sp
    ),
    labelSmall = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        letterSpacing = 0.9.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.9.sp
    ),
    displayLarge = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.9.sp
    ),
    displaySmall = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.9.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.9.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.9.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.12.sp
    ),
    titleSmall = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 8.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.9.sp
    ),
    bodySmall = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 7.sp,
        lineHeight = 10.sp,
        letterSpacing = 0.9.sp
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoCondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 7.sp,
        lineHeight = 10.sp,
        letterSpacing = 0.9.sp
    )
)
