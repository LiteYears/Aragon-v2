package com.example.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Pure Jetpack Compose vector renderer for the Aragon Architectural Emblem.
 * Zero XML resource lookup dependencies, immune to runtime resource resolution issues,
 * and renders with pixel-perfect precision at 120 FPS.
 */
@Composable
fun AragonLogoEmblem(
  modifier: Modifier = Modifier,
  tint: Color = Color.White,
  contentDescriptionText: String = "Aragon Logo"
) {
  Canvas(
    modifier = modifier.semantics {
      contentDescription = contentDescriptionText
    }
  ) {
    val scaleX = size.width / 48f
    val scaleY = size.height / 48f

    fun sx(x: Float) = x * scaleX
    fun sy(y: Float) = y * scaleY

    // 1. Left Stela: M23,6 L10,40 L16,40 L21.5,25 L23,20 Z
    val leftStela = Path().apply {
      moveTo(sx(23f), sy(6f))
      lineTo(sx(10f), sy(40f))
      lineTo(sx(16f), sy(40f))
      lineTo(sx(21.5f), sy(25f))
      lineTo(sx(23f), sy(20f))
      close()
    }
    drawPath(leftStela, tint)

    // 2. Right Stela: M25,6 L25,20 L26.5,25 L32,40 L38,40 Z
    val rightStela = Path().apply {
      moveTo(sx(25f), sy(6f))
      lineTo(sx(25f), sy(20f))
      lineTo(sx(26.5f), sy(25f))
      lineTo(sx(32f), sy(40f))
      lineTo(sx(38f), sy(40f))
      close()
    }
    drawPath(rightStela, tint)

    // 3. Central Keystone / Diamond: M24,12 L29,21 L24,29 L19,21 Z
    val diamond = Path().apply {
      moveTo(sx(24f), sy(12f))
      lineTo(sx(29f), sy(21f))
      lineTo(sx(24f), sy(29f))
      lineTo(sx(19f), sy(21f))
      close()
    }
    drawPath(diamond, tint)

    // 4. Lower Transverse Horizon Beam: M18,33 L30,33 L31.5,36.5 L16.5,36.5 Z
    val beam = Path().apply {
      moveTo(sx(18f), sy(33f))
      lineTo(sx(30f), sy(33f))
      lineTo(sx(31.5f), sy(36.5f))
      lineTo(sx(16.5f), sy(36.5f))
      close()
    }
    drawPath(beam, tint)

    // 5. Micro Grounding Dot: M23,40 L25,40 L25,42 L23,42 Z
    val dot = Path().apply {
      moveTo(sx(23f), sy(40f))
      lineTo(sx(25f), sy(40f))
      lineTo(sx(25f), sy(42f))
      lineTo(sx(23f), sy(42f))
      close()
    }
    drawPath(dot, tint)
  }
}
