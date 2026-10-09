package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledActionPrimaryOn
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

private const val BUILD_NUMBER = "2026.10.09.4-PROD"
private const val APP_VERSION = "2.4.0"
private const val REQUIRED_TAPS_TO_UNLOCK = 10

@Composable
fun AboutAragonScreen(
  currentProviderName: String,
  workspacePath: String,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier
) {
  var buildTapCount by remember { mutableIntStateOf(0) }
  var isDevUnlocked by remember { mutableStateOf(false) }
  var unlockBannerMessage by remember { mutableStateOf<String?>(null) }

  val scrollState = rememberScrollState()

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(AmoledBackground)
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Top Hero Brand Card
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = AmoledSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Aragon Pure White Logo Emblem
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = Color(0xFF111111),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
          modifier = Modifier.size(72.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              painter = painterResource(id = R.drawable.ic_aragon_logo),
              contentDescription = "Aragon Logo",
              tint = Color.White,
              modifier = Modifier.size(42.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = "ARAGON",
          style = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp
          ),
          color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text = "Autonomous Intelligence Kernel",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = InterFontFamily,
          color = AmoledIconGreyLight,
          fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Badge pill
        Surface(
          shape = RoundedCornerShape(6.dp),
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Description Overview
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = AmoledSurfaceElevated,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          text = "Overview",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = AmoledTextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = "Aragon is a deterministic autonomous agent kernel engineered for real-world execution. Unlike conversational chatbots, Aragon operates in an isolated sandbox with full access to terminal execution, Python 3 runtimes, file synthesis, and verifiable evidence generation.",
          style = MaterialTheme.typography.bodySmall,
          color = AmoledTextSecondary,
          lineHeight = 19.sp
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Architecture Pillars Cards
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = AmoledSurfaceElevated,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Cognitive Architecture",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = AmoledTextPrimary
        )

        FeatureRow(
          icon = Icons.Default.Terminal,
          title = "Deterministic Tool Sandbox",
          description = "Isolated workspace runtime with Python 3.12, CSV data processing, and native Word docx artifact synthesis."
        )

        FeatureRow(
          icon = Icons.Default.Security,
          title = "5-Stage Cognitive Discipline",
          description = "Objective Intent → Dynamic Plan → Tool Execution → Sensory Observation → Empirical Verification."
        )

        FeatureRow(
          icon = Icons.Default.Code,
          title = "Multi-Model Orchestration",
          description = "Seamless failover between Autonomous Sandbox Engine, NVIDIA NIM, and Google Gemini."
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Runtime Diagnostics
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = AmoledSurfaceElevated,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "System Diagnostics",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = AmoledTextPrimary
        )

        DiagnosticRow(label = "Active Engine", value = currentProviderName)
        DiagnosticRow(label = "Workspace", value = workspacePath)
        DiagnosticRow(label = "Execution Boundary", value = "Isolated Sandbox Mode")
        DiagnosticRow(label = "Platform", value = "Android 15+ (Compose M3)")
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Interactive Build Number Card with 10-Click Secret Unlock
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = if (isDevUnlocked) Color(0xFF141F14) else Color(0xFF111111),
      border = androidx.compose.foundation.BorderStroke(
        1.dp,
        if (isDevUnlocked) AmoledStatusSuccess.copy(alpha = 0.5f) else AmoledBorder
      ),
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable {
          val nextCount = buildTapCount + 1
          if (nextCount >= REQUIRED_TAPS_TO_UNLOCK) {
            buildTapCount = 0
            isDevUnlocked = true
            unlockBannerMessage = "Developer Configuration & API Settings Unlocked!"
            onOpenSettings()
          } else {
            buildTapCount = nextCount
            val remaining = REQUIRED_TAPS_TO_UNLOCK - nextCount
            if (remaining in 1..6) {
              unlockBannerMessage = "You are $remaining tap${if (remaining == 1) "" else "s"} away from Developer Configuration"
            }
          }
        }
        .testTag("about_build_number_card")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center
        ) {
          Icon(
            imageVector = if (isDevUnlocked) Icons.Default.LockOpen else Icons.Default.Build,
            contentDescription = null,
            tint = if (isDevUnlocked) AmoledStatusSuccess else AmoledIconGreyLight,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Build Information",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AmoledTextPrimary
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Version $APP_VERSION",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = JetBrainsMonoFontFamily,
          color = AmoledTextSecondary,
          fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
          text = "Build Number: $BUILD_NUMBER",
          style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
          ),
          color = if (isDevUnlocked) AmoledStatusSuccess else AmoledTextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Hint / status feedback
        Text(
          text = if (isDevUnlocked) {
            "✓ Developer Configuration Unlocked (Tap to open)"
          } else {
            "Tap build number 10 times to unlock settings"
          },
          style = MaterialTheme.typography.labelSmall,
          fontFamily = InterFontFamily,
          color = if (isDevUnlocked) AmoledStatusSuccess else AmoledTextMuted,
          fontSize = 11.sp,
          textAlign = TextAlign.Center
        )

        // Dynamic tap hint
        AnimatedVisibility(
          visible = unlockBannerMessage != null,
          enter = fadeIn(tween(150)),
          exit = fadeOut(tween(150))
        ) {
          unlockBannerMessage?.let { msg ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (isDevUnlocked) Color(0xFF102810) else Color(0xFF222222),
              border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
              modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
            ) {
              Text(
                text = msg,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = if (isDevUnlocked) AmoledStatusSuccess else AmoledIconGreyLight,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
              )
            }
          }
        }

        if (isDevUnlocked) {
          Spacer(modifier = Modifier.height(12.dp))
          Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(
              containerColor = AmoledActionPrimary,
              contentColor = AmoledActionPrimaryOn
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open API & Model Settings", style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(24.dp))
  }
}

@Composable
private fun FeatureRow(
  icon: ImageVector,
  title: String,
  description: String
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top
  ) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = Color(0xFF181818),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.size(32.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(16.dp)
        )
      }
    }

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = AmoledTextPrimary
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = AmoledTextMuted,
        fontSize = 11.sp,
        lineHeight = 16.sp
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
      fontSize = 11.sp
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
      color = AmoledTextPrimary,
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium
    )
  }
}
