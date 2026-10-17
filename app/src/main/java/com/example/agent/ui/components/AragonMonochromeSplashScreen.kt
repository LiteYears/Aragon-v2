package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-precision, avant-garde monochrome black & white animated splash screen.
 * Features a hypnotic, hyper-smooth sacred geometry & cybernetic quantum core animation,
 * mathematical Lissajous curves, 3D tesseract projection wireframes, and dynamic typography.
 */
@Composable
fun AragonMonochromeSplashScreen(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  durationMillis: Long = 3200L
) {
  var isExiting by remember { mutableStateOf(false) }
  val exitAlpha = remember { Animatable(1f) }
  val exitScale = remember { Animatable(1f) }

  // Auto-dismiss countdown
  LaunchedEffect(Unit) {
    delay(durationMillis)
    isExiting = true
    exitAlpha.animateTo(0f, animationSpec = tween(400, easing = FastOutSlowInEasing))
    onDismiss()
  }

  val triggerExit = {
    if (!isExiting) {
      isExiting = true
      onDismiss()
    }
  }

  val infiniteTransition = rememberInfiniteTransition(label = "aragon_splash_infinite")

  // Harmonic orbital rotations
  val rotationSlow by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(22000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "rotation_slow"
  )

  val rotationCounter by infiniteTransition.animateFloat(
    initialValue = 360f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
      animation = tween(14000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "rotation_counter"
  )

  val rotationTesseract by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(8000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "rotation_tesseract"
  )

  // Pulsing breathing scale for sacred polygons
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 0.88f,
    targetValue = 1.14f,
    animationSpec = infiniteRepeatable(
      animation = tween(2600, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulse_scale"
  )

  // Quantum ripple phase
  val ripplePhase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(2200, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "ripple_phase"
  )

  // Lissajous curve morph factor
  val morphFactor by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = (2 * PI).toFloat(),
    animationSpec = infiniteRepeatable(
      animation = tween(6000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "morph_factor"
  )

  // Letter spacing and reveal
  val titleTracking = remember { Animatable(4f) }
  val titleAlpha = remember { Animatable(0f) }
  val subAlpha = remember { Animatable(0f) }

  LaunchedEffect(Unit) {
    titleAlpha.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    titleTracking.animateTo(12f, animationSpec = tween(1600, easing = FastOutSlowInEasing))
  }

  LaunchedEffect(Unit) {
    delay(500)
    subAlpha.animateTo(1f, animationSpec = tween(900, easing = FastOutSlowInEasing))
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .alpha(exitAlpha.value)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = triggerExit
      )
      .testTag("aragon_monochrome_splash"),
    contentAlignment = Alignment.Center
  ) {
    // 1. Dynamic Sacred Geometry & Cybernetic Core Canvas
    Canvas(
      modifier = Modifier
        .size(340.dp)
        .testTag("splash_geometry_canvas")
    ) {
      val center = Offset(size.width / 2f, size.height / 2f)
      val baseRadius = size.minDimension * 0.44f

      // A. Outer Star Dial Ring with 24 Tick Marks
      rotate(rotationSlow, center) {
        drawCircle(
          color = Color(0xFF262626),
          radius = baseRadius,
          center = center,
          style = Stroke(width = 1f)
        )

        drawCircle(
          color = Color(0xFF404040),
          radius = baseRadius * 0.94f,
          center = center,
          style = Stroke(
            width = 1.2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 14f), 0f)
          )
        )

        // 24 Radial tick notches
        val tickCount = 24
        for (i in 0 until tickCount) {
          val angle = (i * (2 * PI / tickCount)).toFloat()
          val isMajor = i % 6 == 0
          val rOuter = baseRadius
          val rInner = if (isMajor) baseRadius - 12f else baseRadius - 6f
          val start = Offset(center.x + cos(angle) * rInner, center.y + sin(angle) * rInner)
          val end = Offset(center.x + cos(angle) * rOuter, center.y + sin(angle) * rOuter)
          drawLine(
            color = if (isMajor) Color.White else Color(0xFF666666),
            start = start,
            end = end,
            strokeWidth = if (isMajor) 1.6f else 1f
          )
        }
      }

      // B. Concentric Counter-Rotating Sacred Dodecagram (12-vertex star)
      rotate(rotationCounter, center) {
        val starRadius = baseRadius * 0.76f * pulseScale
        val path = Path()
        val numPoints = 12
        for (i in 0 until numPoints) {
          val angle = (i * (2 * PI / numPoints)).toFloat()
          // Alternating vertices between outer and inner radius
          val r = if (i % 2 == 0) starRadius else starRadius * 0.62f
          val px = center.x + cos(angle) * r
          val py = center.y + sin(angle) * r
          if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        drawPath(
          path = path,
          color = Color(0xFFCCCCCC).copy(alpha = 0.55f),
          style = Stroke(width = 1.4f)
        )

        // Diagonal chord lattice between opposite vertices
        for (i in 0 until numPoints step 2) {
          val angleA = (i * (2 * PI / numPoints)).toFloat()
          val angleB = ((i + 4) * (2 * PI / numPoints)).toFloat()
          val p1 = Offset(center.x + cos(angleA) * starRadius * 0.62f, center.y + sin(angleA) * starRadius * 0.62f)
          val p2 = Offset(center.x + cos(angleB) * starRadius * 0.62f, center.y + sin(angleB) * starRadius * 0.62f)
          drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = p1,
            end = p2,
            strokeWidth = 1f
          )
        }
      }

      // C. Hypnotic Mathematical Lissajous Toroidal Curve
      val lissajousPath = Path()
      val steps = 180
      val lissRadius = baseRadius * 0.52f
      for (i in 0..steps) {
        val t = (i * (2 * PI / steps)).toFloat()
        // 3:2 frequency ratio with continuous phase shift
        val lx = center.x + lissRadius * sin(3f * t + morphFactor)
        val ly = center.y + lissRadius * cos(2f * t)
        if (i == 0) lissajousPath.moveTo(lx, ly) else lissajousPath.lineTo(lx, ly)
      }
      drawPath(
        path = lissajousPath,
        color = Color.White.copy(alpha = 0.85f),
        style = Stroke(
          width = 1.6f,
          cap = StrokeCap.Round,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), morphFactor * 10f)
        )
      )

      // D. Inner 3D Tesseract / Rotating Square Projection
      rotate(rotationTesseract, center) {
        val boxR = baseRadius * 0.28f * (1f / pulseScale)
        // Outer projected square
        val p1 = Offset(center.x - boxR, center.y - boxR)
        val p2 = Offset(center.x + boxR, center.y - boxR)
        val p3 = Offset(center.x + boxR, center.y + boxR)
        val p4 = Offset(center.x - boxR, center.y + boxR)

        // Inner nested diamond
        val dR = boxR * 0.62f
        val d1 = Offset(center.x, center.y - dR)
        val d2 = Offset(center.x + dR, center.y)
        val d3 = Offset(center.x, center.y + dR)
        val d4 = Offset(center.x - dR, center.y)

        drawRect(
          color = Color(0xFFAAAAAA).copy(alpha = 0.7f),
          topLeft = Offset(center.x - boxR, center.y - boxR),
          size = Size(boxR * 2, boxR * 2),
          style = Stroke(width = 1.2f)
        )

        val diamondPath = Path().apply {
          moveTo(d1.x, d1.y)
          lineTo(d2.x, d2.y)
          lineTo(d3.x, d3.y)
          lineTo(d4.x, d4.y)
          close()
        }
        drawPath(
          path = diamondPath,
          color = Color.White,
          style = Stroke(width = 1.6f)
        )

        // Tesseract hyper-chords connecting outer corners to diamond points
        drawLine(Color.White.copy(alpha = 0.4f), p1, d1, strokeWidth = 1f)
        drawLine(Color.White.copy(alpha = 0.4f), p2, d2, strokeWidth = 1f)
        drawLine(Color.White.copy(alpha = 0.4f), p3, d3, strokeWidth = 1f)
        drawLine(Color.White.copy(alpha = 0.4f), p4, d4, strokeWidth = 1f)
      }

      // E. Pulsing Quantum Singularity Ripples
      val rippleR1 = baseRadius * 0.2f + (baseRadius * 0.6f * ripplePhase)
      val rippleAlpha1 = (1f - ripplePhase).coerceIn(0f, 1f) * 0.6f
      drawCircle(
        color = Color.White.copy(alpha = rippleAlpha1),
        radius = rippleR1,
        center = center,
        style = Stroke(width = 1f)
      )

      val secondPhase = (ripplePhase + 0.5f) % 1f
      val rippleR2 = baseRadius * 0.2f + (baseRadius * 0.6f * secondPhase)
      val rippleAlpha2 = (1f - secondPhase).coerceIn(0f, 1f) * 0.6f
      drawCircle(
        color = Color.White.copy(alpha = rippleAlpha2),
        radius = rippleR2,
        center = center,
        style = Stroke(width = 1f)
      )

      // F. Central Brilliant Singularity Core
      drawCircle(
        color = Color.White,
        radius = 4.5f * pulseScale,
        center = center
      )
      drawCircle(
        color = Color.White.copy(alpha = 0.35f),
        radius = 12f * pulseScale,
        center = center
      )
    }

    // 2. Foreground Typography and Monolithic Branding
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      // Top Status Bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "SYS // V2.6.2",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = Color(0xFF666666),
          letterSpacing = 2.sp
        )

        Surface(
          shape = RoundedCornerShape(4.dp),
          color = Color(0xFF141414),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
          modifier = Modifier
            .clickable(onClick = triggerExit)
            .testTag("splash_skip_button")
        ) {
          Text(
            text = "SKIP [ESC]",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }

      // Center Spacing (Canvas is drawn in Box background)
      Spacer(modifier = Modifier.weight(1f))

      // Bottom Monolith Identity Cluster
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 20.dp)
      ) {
        // Main Logo Text
        Text(
          text = "A R A G O N",
          fontFamily = InterFontFamily,
          fontWeight = FontWeight.ExtraBold,
          fontSize = 24.sp,
          letterSpacing = titleTracking.value.sp,
          color = Color.White,
          modifier = Modifier.alpha(titleAlpha.value)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Cybernetic Subtitle
        Text(
          text = "AUTONOMOUS REASONING KERNEL",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.SemiBold,
          fontSize = 10.sp,
          letterSpacing = 3.sp,
          color = Color(0xFFAAAAAA),
          modifier = Modifier.alpha(subAlpha.value)
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Monospaced Diagnostic Pipeline indicator
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
          modifier = Modifier.alpha(subAlpha.value)
        ) {
          Box(
            modifier = Modifier
              .size(5.dp)
              .clip(RoundedCornerShape(1.dp))
              .background(Color.White)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "DETERMINISTIC SANDBOX · MICROVM SYNTHESIS",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 8.5.sp,
            color = Color(0xFF777777),
            letterSpacing = 1.sp
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress bar line
        Box(
          modifier = Modifier
            .width(180.dp)
            .height(1.5.dp)
            .background(Color(0xFF222222))
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(ripplePhase)
              .height(1.5.dp)
              .background(Color.White)
          )
        }
      }
    }
  }
}
