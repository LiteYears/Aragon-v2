package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Pure AMOLED Dark Color Scheme
private val AmoledColorScheme = darkColorScheme(
  primary = AmoledActionPrimary,
  onPrimary = AmoledActionPrimaryOn,
  primaryContainer = AmoledSurfaceElevated,
  onPrimaryContainer = AmoledTextPrimary,

  secondary = AmoledIconGreyLight,
  onSecondary = Color(0xFF121212),
  secondaryContainer = AmoledSurfaceVariant,
  onSecondaryContainer = AmoledTextSecondary,

  tertiary = AmoledIconGrey,
  onTertiary = Color(0xFF000000),
  tertiaryContainer = AmoledSurfaceVariant,
  onTertiaryContainer = AmoledTextSecondary,

  background = AmoledBackground,
  onBackground = AmoledTextPrimary,

  surface = AmoledSurface,
  onSurface = AmoledTextPrimary,
  surfaceVariant = AmoledSurfaceVariant,
  onSurfaceVariant = AmoledTextSecondary,

  surfaceContainer = AmoledSurfaceElevated,
  surfaceContainerHigh = Color(0xFF222222),

  outline = AmoledIconGreyDark,
  outlineVariant = AmoledBorder,

  error = AmoledStatusError,
  onError = Color(0xFF000000),
  errorContainer = Color(0xFF1E1010),
  onErrorContainer = AmoledStatusError
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = AmoledColorScheme,
    typography = Typography,
    content = content
  )
}
