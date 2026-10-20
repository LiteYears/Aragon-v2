package com.example.agent.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.sin

/**
 * Creative, non-standard AMOLED Micro-Loading Badge.
 * Replaces generic loading spinners with a high-precision cybernetic telemetry glyph:
 * - Concentric orbiting arc and central quantum emitter pulse
 * - Dual cadence scanning bars
 * - Monospace label with letter-spaced typography
 * Seamlessly fits pure AMOLED black-and-white theme with warm amber accents.
 */
@Composable
fun CreativeAmoledLoadingBadge(
  label: String = "RUN",
  modifier: Modifier = Modifier,
  accentColor: Color = AmoledActionPrimary,
  height: Dp = 18.dp,
  showLabel: Boolean = true,
  testTag: String = "creative_loading_badge"
) {
  val infiniteTransition = rememberInfiniteTransition(label = "creative_loader_transition")

  // Orbital rotation
  val rotationAngle = infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "orbital_rotation"
  )

  // Quantum core pulse
  val pulseScale = infiniteTransition.animateFloat(
    initialValue = 0.35f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(650, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "quantum_pulse"
  )

  // Scanning wave offset (for micro-spectrum ticks)
  val wavePhase = infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = (2 * Math.PI).toFloat(),
    animationSpec = infiniteRepeatable(
      animation = tween(1100, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "wave_phase"
  )

  val outerRingStroke = remember { Stroke(width = 1.5f) }
  val arcStroke = remember { Stroke(width = 2.4f, cap = StrokeCap.Round) }

  Surface(
    shape = RoundedCornerShape(9.dp),
    color = Color(0xFF101014),
    border = BorderStroke(0.6.dp, accentColor.copy(alpha = 0.35f)),
    modifier = modifier
      .height(height)
      .testTag(testTag)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      // 1. Non-standard geometric telemetry canvas with zero per-frame CPU allocations
      Box(
        modifier = Modifier.size(12.dp),
        contentAlignment = Alignment.Center
      ) {
        // Base static ring
        Canvas(modifier = Modifier.size(12.dp)) {
          val center = Offset(size.width / 2f, size.height / 2f)
          val radius = (size.width / 2f) - 1.dp.toPx()

          drawCircle(
            color = Color(0xFF2E2E34),
            radius = radius,
            style = outerRingStroke
          )
        }

        // Hardware-accelerated rotating orbital arc (GPU transformed)
        Canvas(
          modifier = Modifier
            .size(12.dp)
            .graphicsLayer {
              rotationZ = rotationAngle.value
            }
        ) {
          val center = Offset(size.width / 2f, size.height / 2f)
          val radius = (size.width / 2f) - 1.dp.toPx()

          drawArc(
            color = accentColor,
            startAngle = 0f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = arcStroke
          )
        }

        // Center pulsing core dot (hardware-scaled)
        Box(
          modifier = Modifier
            .size(4.dp)
            .graphicsLayer {
              val p = pulseScale.value
              scaleX = 0.6f + 0.5f * p
              scaleY = 0.6f + 0.5f * p
            }
            .background(Color.White, CircleShape)
        )
      }

      Spacer(modifier = Modifier.width(5.dp))

      // 2. Micro rhythm ticks (dynamic quantum telemetry lines)
      Canvas(modifier = Modifier.size(width = 9.dp, height = 10.dp)) {
        val barWidth = 1.6.dp.toPx()
        val spacing = 2.4.dp.toPx()
        val maxHeight = size.height
        val currentWave = wavePhase.value

        for (i in 0 until 3) {
          val hFactor = 0.35f + 0.65f * ((sin(currentWave + i * 1.5f) + 1f) / 2f)
          val barHeight = maxHeight * hFactor
          val x = i * (barWidth + spacing)
          val y = (maxHeight - barHeight) / 2f

          drawRect(
            color = if (i == 1) accentColor else Color.White.copy(alpha = 0.7f),
            topLeft = Offset(x, y),
            size = Size(barWidth, barHeight)
          )
        }
      }

      if (showLabel && label.isNotBlank()) {
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 9.sp,
          letterSpacing = 0.8.sp,
          color = accentColor
        )
      }
    }
  }
}
