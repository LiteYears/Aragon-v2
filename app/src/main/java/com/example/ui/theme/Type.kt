package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

// Bundled Google Fonts
val InterFontFamily = FontFamily(
  Font(R.font.inter, FontWeight.Normal)
)

val JetBrainsMonoFontFamily = FontFamily(
  Font(R.font.jetbrains_mono, FontWeight.Normal)
)

// App Typography using Inter for clean, modern readability
val Typography = Typography(
  displayLarge = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.5).sp
  ),
  displayMedium = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 26.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.25).sp
  ),
  headlineMedium = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp
  ),
  titleLarge = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.15).sp
  ),
  titleMedium = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 21.sp,
    letterSpacing = (-0.1).sp
  ),
  titleSmall = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp
  ),
  bodyLarge = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.sp
  ),
  bodyMedium = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.sp
  ),
  bodySmall = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 17.sp,
    letterSpacing = 0.sp
  ),
  labelLarge = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 17.sp,
    letterSpacing = 0.1.sp
  ),
  labelMedium = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.2.sp
  ),
  labelSmall = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.3.sp
  )
)
