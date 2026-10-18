package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGreyDark
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledStatusWarning
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

private const val BUILD_NUMBER = "2026.10.18.2-PROD"
private const val APP_VERSION = "2.4.2"
private const val REQUIRED_TAPS_TO_UNLOCK = 10

/**
 * System Architecture & About Screen for Aragon.
 * Pristine typography, enterprise architecture specifications, runtime diagnostics,
 * dedicated tmp scratchpad isolation details, and 10-tap developer settings unlock.
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
      .padding(horizontal = 14.dp, vertical = 10.dp)
      .widthIn(max = 680.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    // 1. Top Hero Brand Card
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = AmoledSurface,
      border = BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Aragon Logo Emblem
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.size(52.dp)
        ) {
          AragonLogoEmblem(
            tint = Color.White,
            modifier = Modifier.size(44.dp)
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "ARAGON",
          style = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
          ),
          color = Color.White
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
          text = "Autonomous Intelligence Kernel • v$APP_VERSION",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = JetBrainsMonoFontFamily,
          color = AmoledIconGreyLight,
          fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Specification Pills
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          SpecTag(text = "MICROVM")
          SpecTag(text = "MCP 2024-11-05")
          SpecTag(text = "DOCX/XLSX/PDF")
          SpecTag(text = "tmp/ ISOLATED")
        }
      }
    }

    // 2. Executive Overview Card
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = AmoledSurfaceElevated,
      border = BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "KERNEL OVERVIEW",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = AmoledActionPrimary,
            fontSize = 10.sp
          )
          Text(
            text = "DETERMINISTIC EXECUTION",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = AmoledStatusSuccess,
            fontSize = 8.5.sp
          )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = "Aragon is a production-grade autonomous cognitive agent engine designed for deterministic, verifiable task execution. Running within an isolated hardware microVM sandbox, it seamlessly orchestrates deep web research, precision document synthesis, Python 3 computational pipelines, Model Context Protocol servers, and rigorous 5-stage verification.",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = InterFontFamily,
          color = AmoledTextSecondary,
          lineHeight = 18.sp,
          fontSize = 11.5.sp
        )
      }
    }

    // 3. Architecture Pillars
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = AmoledSurfaceElevated,
      border = BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = "CORE ARCHITECTURAL PILLARS",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          color = AmoledActionPrimary,
          fontSize = 10.sp
        )

        ArchitecturePillarRow(
          tag = "SANDBOX",
          title = "E2B Cloud MicroVM & tmp/ Isolation",
          description = "Dedicated Linux x86_64 Firecracker microVM with full terminal, Python 3, and package management. Intermediate search scrapes, temporary files, and dumps are cleanly isolated inside tmp/ so they never pollute final workspace deliverables."
        )

        ArchitecturePillarRow(
          tag = "5-STG",
          title = "5-Stage Cognitive Discipline",
          description = "Every mission rigorously flows through Objective Intent Decoding → Dynamic Plan Breakdown → Deterministic Tool Invocation → Sensory Observation → Empirical Evidence Verification."
        )

        ArchitecturePillarRow(
          tag = "DOCS",
          title = "Universal Document Synthesis Engine",
          description = "Synthesizes native Microsoft Word (.docx), Excel (.xlsx), Adobe PDF, and Markdown (.md) deliverables with multi-page typography, tables, and native Right-to-Left (RTL) Arabic support."
        )

        ArchitecturePillarRow(
          tag = "MCP",
          title = "Model Context Protocol (2024-11-05)",
          description = "Compliant MCP client supporting SQLite database querying, filesystem access, HTTP fetch, and custom external JSON-RPC servers."
        )

        ArchitecturePillarRow(
          tag = "RESEARCH",
          title = "Autonomous Web & Browser Research",
          description = "Playwright-grade browser automation, multi-source web search, structured HTML parsing, recursive link crawling, and table extraction with zero hallucination."
        )

        ArchitecturePillarRow(
          tag = "NEURAL",
          title = "Zero-Failover Multi-Model Gateway",
          description = "Unified orchestration routing requests to NVIDIA NIM (Llama 3.3 70B, Nemotron, DeepSeek) or Google Gemini with intelligent failover."
        )
      }
    }

    // 4. Runtime Diagnostics
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = AmoledSurfaceElevated,
      border = BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
      ) {
        Text(
          text = "RUNTIME DIAGNOSTICS & TOPOLOGY",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          color = AmoledActionPrimary,
          fontSize = 10.sp
        )

        DiagnosticRow(label = "Active Intelligence Engine", value = currentProviderName)
        DiagnosticRow(label = "Workspace Root", value = workspacePath)
        DiagnosticRow(label = "Scratchpad Directory", value = "$workspacePath/tmp")
        DiagnosticRow(label = "Execution Boundary", value = sandboxBoundary)
        DiagnosticRow(label = "Artifact Deliverable Filter", value = "Strict Root Only (tmp/ Excluded)")
        DiagnosticRow(label = "Document Database Scope", value = "User Attachments Only")
        DiagnosticRow(label = "UI Architecture", value = "Jetpack Compose M3 AMOLED True Black")
      }
    }

    // 5. Quick Actions Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      AragonActionButton(
        text = "SETTINGS & KEYS",
        onClick = onOpenSettings,
        modifier = Modifier.weight(1f),
        variant = AragonButtonVariant.PRIMARY,
        height = 34.dp,
        testTag = "about_open_settings_direct_btn"
      )

      AragonActionButton(
        text = "REPLAY INTRO",
        onClick = onReplaySplash,
        modifier = Modifier.weight(1f),
        variant = AragonButtonVariant.MUTED,
        height = 34.dp,
        testTag = "replay_splash_intro_btn"
      )
    }

    // 6. Interactive Build Number Card with 10-Click Secret Unlock
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = if (isDevUnlocked) Color(0xFF141F14) else Color(0xFF0E0E0E),
      border = BorderStroke(
        1.dp,
        if (isDevUnlocked) AmoledStatusSuccess.copy(alpha = 0.5f) else AmoledBorderSubtle
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
              unlockBannerMessage = "Developer Mode & Settings Unlocked!"
              onOpenSettings()
            } else {
              buildTapCount = nextCount
              val remaining = REQUIRED_TAPS_TO_UNLOCK - nextCount
              if (remaining in 1..6) {
                unlockBannerMessage = "You are $remaining tap${if (remaining == 1) "" else "s"} away from Developer Mode"
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
          text = if (isDevUnlocked) "[DEVELOPER MODE ACTIVE]" else "[BUILD INFO]",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 9.5.sp,
          color = if (isDevUnlocked) AmoledStatusSuccess else AmoledActionPrimary
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
          text = "Version $APP_VERSION ($BUILD_NUMBER)",
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
          color = AmoledTextSecondary,
          fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
          text = if (isDevUnlocked) "Tap to open system settings & configurations" else "Tap 10 times to unlock direct configuration shortcut",
          style = MaterialTheme.typography.labelSmall,
          color = AmoledTextMuted,
          fontSize = 9.5.sp,
          textAlign = TextAlign.Center
        )

        AnimatedVisibility(visible = unlockBannerMessage != null, enter = fadeIn(), exit = fadeOut()) {
          unlockBannerMessage?.let { msg ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = msg,
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = if (isDevUnlocked) AmoledStatusSuccess else AmoledStatusWarning,
              fontSize = 9.5.sp,
              textAlign = TextAlign.Center
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(20.dp))
  }
}

@Composable
private fun SpecTag(text: String) {
  Surface(
    shape = RoundedCornerShape(3.dp),
    color = Color(0xFF161618),
    border = BorderStroke(1.dp, AmoledBorderSubtle)
  ) {
    Text(
      text = text,
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 8.sp,
      fontWeight = FontWeight.SemiBold,
      color = AmoledIconGreyLight,
      modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
    )
  }
}

@Composable
private fun ArchitecturePillarRow(
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
      color = Color(0xFF141416),
      border = BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.width(46.dp).height(24.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
          text = tag,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 7.5.sp,
          color = AmoledActionPrimary,
          maxLines = 1
        )
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp,
        color = AmoledTextPrimary
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = InterFontFamily,
        color = AmoledTextMuted,
        fontSize = 10.5.sp,
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
      fontSize = 10.sp,
      modifier = Modifier.padding(end = 8.dp)
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
      fontWeight = FontWeight.Medium,
      color = AmoledTextPrimary,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.End,
      modifier = Modifier.weight(1f, fill = false)
    )
  }
}
