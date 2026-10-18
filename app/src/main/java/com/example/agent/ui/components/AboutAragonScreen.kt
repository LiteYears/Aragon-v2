package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

import androidx.compose.runtime.saveable.rememberSaveable

private const val BUILD_NUMBER = "2026.10.09.4-PROD"
private const val APP_VERSION = "2.4.0"
private const val REQUIRED_TAPS_TO_UNLOCK = 10

/**
 * System Architecture & About Screen.
 * Pure typography design. Retains the 10-tap hidden settings unlock on build number.
 */
@Composable
fun AboutAragonScreen(
  currentProviderName: String,
  workspacePath: String,
  sandboxBoundary: String = "E2B Cloud Firecracker MicroVM (Isolated)",
  onOpenSettings: () -> Unit,
  onReplaySplash: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  var buildTapCount by remember { mutableIntStateOf(0) }
  var isDevUnlocked by rememberSaveable { mutableStateOf(false) }
  var unlockBannerMessage by remember { mutableStateOf<String?>(null) }

  val scrollState = rememberScrollState()

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(AmoledBackground)
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp, vertical = 12.dp)
      .widthIn(max = 680.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Top Hero Brand Card
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = AmoledSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Aragon Logo Emblem
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.size(54.dp)
        ) {
          AragonLogoEmblem(
            tint = Color.White,
            modifier = Modifier.size(46.dp)
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "ARAGON",
          style = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp
          ),
          color = Color.White
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
          text = "Autonomous Intelligence Kernel",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = InterFontFamily,
          color = AmoledIconGreyLight,
          fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
          shape = RoundedCornerShape(4.dp),
          color = Color(0xFF1A1A1A),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle)
        ) {
          Text(
            text = "ENTERPRISE RUNTIME",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Description Overview
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = AmoledSurfaceElevated,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Text(
          text = "Overview",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = AmoledTextPrimary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text = "Aragon is a deterministic autonomous agent kernel engineered for real-world execution. Operating in an isolated sandbox with terminal execution, Python 3 runtimes, file synthesis, and verifiable evidence generation.",
          style = MaterialTheme.typography.bodySmall,
          color = AmoledTextSecondary,
          lineHeight = 18.sp
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Replay Splash Intro Animation card
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = AmoledSurfaceElevated,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Sacred Monochrome Intro",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AmoledTextPrimary
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = "Replay the hypnotic mathematical quantum singularity splash animation.",
            style = MaterialTheme.typography.bodySmall,
            color = AmoledTextSecondary,
            fontSize = 11.sp
          )
        }
        Spacer(modifier = Modifier.width(10.dp))
        AragonHeaderButton(
          text = "PLAY",
          onClick = onReplaySplash,
          variant = AragonButtonVariant.PRIMARY,
          testTag = "replay_splash_intro_btn"
        )
      }
    }

    // Architecture Pillars Cards (Pure text badges)
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = AmoledSurfaceElevated,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = "Cognitive Architecture",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = AmoledTextPrimary
        )

        FeatureRow(
          tag = "TOOL",
          title = "Native Sandbox Toolchain",
          description = "Full workspace runtime with Python 3, precision file patching, pattern grep, asset downloading, and JSON/CSV dataset processing."
        )

        FeatureRow(
          tag = "WEB",
          title = "Autonomous Web Research System",
          description = "Multi-step search, HTML page retrieval, form submission, recursive link crawling, and structured table extraction."
        )

        FeatureRow(
          tag = "5-STG",
          title = "5-Stage Cognitive Discipline",
          description = "Objective Intent → Dynamic Plan → Tool Execution → Sensory Observation → Empirical Verification."
        )

        FeatureRow(
          tag = "ORCH",
          title = "Multi-Model Orchestration",
          description = "Run NVIDIA NIM cloud models, Google Gemini, or the built-in engine; transient provider failures recover automatically."
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Runtime Diagnostics
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = AmoledSurfaceElevated,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Text(
          text = "System Diagnostics",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = AmoledTextPrimary
        )

        DiagnosticRow(label = "Active Engine", value = currentProviderName)
        DiagnosticRow(label = "Workspace", value = workspacePath)
        DiagnosticRow(label = "Execution Boundary", value = sandboxBoundary)
        DiagnosticRow(label = "Platform", value = "Android 7.0+ • Jetpack Compose (Material 3)")
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Direct Settings & Keys Configuration Button
    AragonActionButton(
      text = "SETTINGS & KEYS CONFIGURATION",
      onClick = onOpenSettings,
      modifier = Modifier.fillMaxWidth(),
      variant = AragonButtonVariant.PRIMARY,
      height = 36.dp,
      testTag = "about_open_settings_direct_btn"
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Interactive Build Number Card with 10-Click Secret Unlock
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = if (isDevUnlocked) Color(0xFF141F14) else Color(0xFF111111),
      border = androidx.compose.foundation.BorderStroke(
        1.dp,
        if (isDevUnlocked) AmoledStatusSuccess.copy(alpha = 0.5f) else AmoledBorder
      ),
      modifier = Modifier
        .fillMaxWidth()
        .clickable(
          role = androidx.compose.ui.semantics.Role.Button,
          onClickLabel = if (isDevUnlocked) "Open Settings & Configuration" else "Build info"
        ) {
          if (isDevUnlocked) {
            onOpenSettings()
          } else {
            val nextCount = buildTapCount + 1
            if (nextCount >= REQUIRED_TAPS_TO_UNLOCK) {
              buildTapCount = 0
              isDevUnlocked = true
              unlockBannerMessage = "Settings & Configuration Unlocked!"
              onOpenSettings()
            } else {
              buildTapCount = nextCount
              val remaining = REQUIRED_TAPS_TO_UNLOCK - nextCount
              if (remaining in 1..6) {
                unlockBannerMessage = "You are $remaining tap${if (remaining == 1) "" else "s"} away from Settings & Configuration"
              }
            }
          }
        }
        .testTag("about_build_number_card")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = if (isDevUnlocked) "[SETTINGS UNLOCKED]" else "[BUILD INFO]",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 10.sp,
          color = if (isDevUnlocked) AmoledStatusSuccess else AmoledActionPrimary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text = "Version $APP_VERSION ($BUILD_NUMBER)",
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
          color = AmoledTextSecondary,
          fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
          text = if (isDevUnlocked) "Tap to open Settings & Configuration" else "Tap build number to reveal Settings & Configuration",
          style = MaterialTheme.typography.labelSmall,
          color = AmoledTextMuted,
          fontSize = 10.sp,
          textAlign = TextAlign.Center
        )

        AnimatedVisibility(visible = unlockBannerMessage != null) {
          unlockBannerMessage?.let { msg ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = msg,
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = if (isDevUnlocked) AmoledStatusSuccess else AmoledActionPrimary,
              fontSize = 10.sp,
              textAlign = TextAlign.Center
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(28.dp))
  }
}

@Composable
private fun FeatureRow(
  tag: String,
  title: String,
  description: String
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.Top
  ) {
    Surface(
      shape = RoundedCornerShape(4.dp),
      color = Color(0xFF161616),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.size(width = 38.dp, height = 24.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
          text = tag,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 8.sp,
          color = AmoledActionPrimary
        )
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        color = AmoledTextPrimary
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = AmoledTextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp
      )
    }
  }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = AmoledTextMuted,
      fontSize = 11.sp,
      modifier = Modifier.padding(end = 8.dp)
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
      fontWeight = FontWeight.Medium,
      color = AmoledTextPrimary,
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.End,
      modifier = Modifier.weight(1f, fill = false)
    )
  }
}
