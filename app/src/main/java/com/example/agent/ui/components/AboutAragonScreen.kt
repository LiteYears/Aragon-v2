package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
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

private const val BUILD_NUMBER = "2026.10.22.4-PROD"
private const val APP_VERSION = "2.5.0"
private const val REQUIRED_TAPS_TO_UNLOCK = 10

private data class CognitiveStageSpec(
  val code: String,
  val title: String,
  val subtitle: String,
  val detail: String,
  val accent: Color
)

private data class ArchitectureModuleSpec(
  val tag: String,
  val title: String,
  val summary: String,
  val detail: String,
  val metrics: String,
  val icon: ImageVector,
  val accent: Color
)

/**
 * Overhauled Pure AMOLED True Black System Architecture & Telemetry Screen for Aragon.
 * Matches the app's high-contrast monochrome + cyber-emerald/blue/amber theme with interactive
 * 5-stage pipeline inspection, expandable architectural modules, live runtime topology console,
 * and 10-tap developer settings unlock.
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
  var selectedStageIndex by rememberSaveable { mutableIntStateOf(0) }
  var expandedModuleTag by rememberSaveable { mutableStateOf<String?>("SANDBOX") }

  val scrollState = rememberScrollState()

  val pulseTransition = rememberInfiniteTransition(label = "about_hero_pulse")
  val glowAlpha by pulseTransition.animateFloat(
    initialValue = 0.22f,
    targetValue = 0.55f,
    animationSpec = infiniteRepeatable(
      animation = tween(2200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "hero_glow_alpha"
  )

  val stages = remember {
    listOf(
      CognitiveStageSpec(
        code = "01",
        title = "INTENT",
        subtitle = "Objective Decoding & Deliverable Contract",
        detail = "Parses user prompt intent, binds current-turn multimodal attachments without cross-turn leakage, and locks expected deliverable formats (.docx, .xlsx, .pdf, .py, .md).",
        accent = Color(0xFF38BDF8)
      ),
      CognitiveStageSpec(
        code = "02",
        title = "PLAN",
        subtitle = "DAG Decomposition & Capability Routing",
        detail = "Synthesizes a dependency-aware execution plan across E2B Firecracker MicroVM, Python 3 runtime, local native tools, and Model Context Protocol (MCP) servers.",
        accent = Color(0xFF818CF8)
      ),
      CognitiveStageSpec(
        code = "03",
        title = "ACTION",
        subtitle = "Parallel Wave Dispatch & Key Rotation",
        detail = "Executes independent tool waves concurrently with health-aware 15-key NVIDIA NIM failover, adaptive first-byte watchdogs, and zero-trust path validation.",
        accent = Color(0xFFF59E0B)
      ),
      CognitiveStageSpec(
        code = "04",
        title = "OBSERVE",
        subtitle = "Sensory Capture & Context Bounding",
        detail = "Captures stdout/stderr streams, OCR/PDF/Office document structures, and bounds historical context windows while keeping tool_call_id linkage intact.",
        accent = Color(0xFF10B981)
      ),
      CognitiveStageSpec(
        code = "05",
        title = "VERIFY",
        subtitle = "Empirical Deliverable Gatekeeper",
        detail = "Blocks premature completion until requested files exist on disk, pass non-zero byte & ZIP/XML/PDF structural integrity checks, and sync back from the remote sandbox.",
        accent = Color(0xFFEC4899)
      )
    )
  }

  val modules = remember {
    listOf(
      ArchitectureModuleSpec(
        tag = "SANDBOX",
        title = "E2B Firecracker MicroVM & tmp/ Isolation",
        summary = "Hardware-isolated Linux x86_64 execution with automatic deliverable synchronization.",
        detail = "Runs full bash, Python 3, pip/apt packages, and headless scripts inside an isolated cloud microVM. Scratch dumps and intermediate scrapes are confined to tmp/ so workspace deliverables remain pristine.",
        metrics = "x86_64 Linux • Auto-Sync",
        icon = Icons.Default.Terminal,
        accent = Color(0xFF10B981)
      ),
      ArchitectureModuleSpec(
        tag = "NEURAL",
        title = "Multi-Key NIM Pool & Adaptive Stream Engine",
        summary = "15-key health-scored rotation with stall recovery and two-phase socket timeouts.",
        detail = "Routes requests across NVIDIA NIM (Llama 3.3 70B, DeepSeek, Qwen, Nemotron) and Google Gemini Live. Features per-key cooldown quarantine, fast connect/TTFB watchdogs, and automatic fallback.",
        metrics = "15-Key Pool • SSE Watchdog",
        icon = Icons.Default.Memory,
        accent = Color(0xFF38BDF8)
      ),
      ArchitectureModuleSpec(
        tag = "DOCS",
        title = "Universal Office & PDF Synthesis Pipeline",
        summary = "Native & Python-backed compilation of .docx, .xlsx, .pdf, and RTL documents.",
        detail = "Synthesizes rich multi-page Word documents, multi-sheet Excel workbooks with formulas, and vector PDFs with tables, typography, and structural verification via DocumentsVerifier.",
        metrics = "DOCX • XLSX • PDF • OCR",
        icon = Icons.Default.Description,
        accent = Color(0xFFA855F7)
      ),
      ArchitectureModuleSpec(
        tag = "MCP",
        title = "Model Context Protocol (2024-11-05)",
        summary = "JSON-RPC 2.0 tool & resource bridging across local and remote MCP servers.",
        detail = "Integrates SQLite querying, HTTP fetch inspection, Playwright browser automation, and custom JSON-RPC 2.0 servers directly into the unified tool registry.",
        metrics = "JSON-RPC 2.0 • Bridged Tools",
        icon = Icons.Default.Hub,
        accent = Color(0xFFF59E0B)
      ),
      ArchitectureModuleSpec(
        tag = "RESEARCH",
        title = "Deep Web Intelligence & Anti-Loop Guard",
        summary = "Multi-engine web search, structured DOM extraction, and deduplicated reasoning.",
        detail = "Combines autonomous web search, table/article extraction, and a ReasoningDedupPipeline that prevents repetitive tool loops while preserving high-signal citations in scratchpad memory.",
        metrics = "DOM Parser • Dedup Guard",
        icon = Icons.Default.Language,
        accent = Color(0xFF06B6D4)
      ),
      ArchitectureModuleSpec(
        tag = "SECURITY",
        title = "Per-Turn Attachment Scope & Path Guard",
        summary = "Zero-trust path resolution and strict single-turn multimodal attachment scoping.",
        detail = "Consumes user-attached images and documents strictly on the prompt they were submitted with—preventing cross-turn attachment leakage—while enforcing workspace boundary checks.",
        metrics = "Single-Turn Scope • Sandboxed",
        icon = Icons.Default.Security,
        accent = Color(0xFFEC4899)
      )
    )
  }

  val diagnosticsSummaryText = remember(currentProviderName, workspacePath, sandboxBoundary) {
    buildString {
      appendLine("ARAGON KERNEL DIAGNOSTICS (v$APP_VERSION / $BUILD_NUMBER)")
      appendLine("Active Provider: $currentProviderName")
      appendLine("Execution Boundary: $sandboxBoundary")
      appendLine("Workspace Root: $workspacePath")
      appendLine("Scratchpad Isolation: $workspacePath/tmp")
      appendLine("Attachment Policy: Per-Prompt Scoped (Auto-Consumed on Send)")
      appendLine("Deliverable Gate: Strict Root Verification (DOCX/XLSX/PDF/Code)")
    }.trim()
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(AmoledBackground)
      .verticalScroll(scrollState)
      .padding(horizontal = 14.dp, vertical = 10.dp)
      .widthIn(max = 680.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // 1. ARCHITECTURAL HERO BANNER CARD
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = Color(0xFF050508),
      border = BorderStroke(1.dp, Color(0xFF1E2230)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Box(modifier = Modifier.fillMaxWidth()) {
        // Subtle Pure-AMOLED technical radial glow & grid accents
        Canvas(
          modifier = Modifier
            .matchParentSize()
        ) {
          val center = Offset(size.width * 0.5f, size.height * 0.28f)
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(
                Color(0xFF2563EB).copy(alpha = glowAlpha * 0.28f),
                Color(0xFF10B981).copy(alpha = glowAlpha * 0.10f),
                Color.Transparent
              ),
              center = center,
              radius = size.width * 0.58f
            ),
            center = center,
            radius = size.width * 0.58f
          )

          // Subtle architectural horizon lines
          val lineColor = Color.White.copy(alpha = 0.03f)
          val stepY = size.height / 5f
          for (i in 1..4) {
            drawLine(
              color = lineColor,
              start = Offset(0f, stepY * i),
              end = Offset(size.width, stepY * i),
              strokeWidth = 1f
            )
          }
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 20.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          // Top Live Status Pill
          Surface(
            shape = CircleShape,
            color = Color(0xFF07160F),
            border = BorderStroke(1.dp, AmoledStatusSuccess.copy(alpha = 0.45f))
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(AmoledStatusSuccess)
              )
              Text(
                text = "KERNEL ONLINE • DETERMINISTIC RUNTIME",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                color = AmoledStatusSuccess
              )
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // Emblem with Double-Ring Obsidian Frame
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .size(68.dp)
              .clip(RoundedCornerShape(18.dp))
              .background(
                Brush.linearGradient(
                  colors = listOf(Color(0xFF12131C), Color(0xFF07070B))
                )
              )
              .border(
                BorderStroke(
                  1.dp,
                  Brush.linearGradient(
                    colors = listOf(
                      Color.White.copy(alpha = 0.35f),
                      Color(0xFF38BDF8).copy(alpha = 0.25f),
                      Color.White.copy(alpha = 0.08f)
                    )
                  )
                ),
                RoundedCornerShape(18.dp)
              )
          ) {
            AragonLogoEmblem(
              tint = Color.White,
              modifier = Modifier.size(44.dp)
            )
          }

          Spacer(modifier = Modifier.height(12.dp))

          Text(
            text = "ARAGON",
            style = MaterialTheme.typography.headlineSmall.copy(
              fontFamily = InterFontFamily,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 5.sp
            ),
            color = Color.White
          )

          Spacer(modifier = Modifier.height(3.dp))

          Text(
            text = "Autonomous Intelligence Kernel • v$APP_VERSION",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = AmoledIconGreyLight,
            fontSize = 11.sp
          )

          Spacer(modifier = Modifier.height(10.dp))

          Text(
            text = "Production-grade autonomous cognitive engine built for verifiable software engineering, deep web research, and native document synthesis inside an isolated Firecracker MicroVM.",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            color = AmoledTextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
          )

          Spacer(modifier = Modifier.height(14.dp))

          // Specification Badges
          Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            SpecTag(text = "MICROVM", accent = Color(0xFF10B981))
            SpecTag(text = "MCP 2024-11-05", accent = Color(0xFFF59E0B))
            SpecTag(text = "DOCX/XLSX/PDF", accent = Color(0xFFA855F7))
            SpecTag(text = "15-KEY POOL", accent = Color(0xFF38BDF8))
            SpecTag(text = "tmp/ ISOLATED", accent = Color(0xFFE2E8F0))
          }

          Spacer(modifier = Modifier.height(14.dp))
          HorizontalDivider(color = Color(0xFF171A24), thickness = 1.dp)
          Spacer(modifier = Modifier.height(12.dp))

          // 4-Column Telemetry KPI Strip
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
          ) {
            HeroMetricItem(value = "5-STAGE", label = "COGNITIVE LOOP", valueColor = Color(0xFF38BDF8))
            HeroMetricDivider()
            HeroMetricItem(value = "E2B VM", label = "LINUX SANDBOX", valueColor = Color(0xFF10B981))
            HeroMetricDivider()
            HeroMetricItem(value = "15 KEYS", label = "NIM FAILOVER", valueColor = Color(0xFFF59E0B))
            HeroMetricDivider()
            HeroMetricItem(value = "STRICT", label = "ARTIFACT GATE", valueColor = Color(0xFFA855F7))
          }
        }
      }
    }

    // 2. INTERACTIVE 5-STAGE COGNITIVE PIPELINE INSPECTOR
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = Color(0xFF07070B),
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(
              imageVector = Icons.Default.AccountTree,
              contentDescription = null,
              tint = Color(0xFF38BDF8),
              modifier = Modifier.size(14.dp)
            )
            Text(
              text = "5-STAGE COGNITIVE PIPELINE",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              color = AmoledActionPrimary,
              fontSize = 10.sp,
              letterSpacing = 0.6.sp
            )
          }
          Text(
            text = "TAP STAGE TO INSPECT",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = AmoledTextMuted,
            fontSize = 8.5.sp
          )
        }

        // Interactive Stage Selector Row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          stages.forEachIndexed { index, stage ->
            val isSelected = selectedStageIndex == index
            val bg by animateColorAsState(
              targetValue = if (isSelected) stage.accent.copy(alpha = 0.16f) else Color(0xFF0D0E14),
              animationSpec = tween(180),
              label = "stage_bg_$index"
            )
            val borderCol by animateColorAsState(
              targetValue = if (isSelected) stage.accent.copy(alpha = 0.75f) else Color(0xFF1F2230),
              animationSpec = tween(180),
              label = "stage_border_$index"
            )
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = bg,
              border = BorderStroke(1.dp, borderCol),
              modifier = Modifier
                .weight(1f)
                .clickable { selectedStageIndex = index }
            ) {
              Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Text(
                  text = stage.code,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 9.sp,
                  color = if (isSelected) stage.accent else AmoledTextMuted
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = stage.title,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                  fontSize = 8.5.sp,
                  color = if (isSelected) Color.White else AmoledTextSecondary,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }

        // Active Stage Detail Box
        val activeStage = stages[selectedStageIndex.coerceIn(stages.indices)]
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color(0xFF040407),
          border = BorderStroke(1.dp, activeStage.accent.copy(alpha = 0.32f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = activeStage.accent.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, activeStage.accent.copy(alpha = 0.5f))
              ) {
                Text(
                  text = "STAGE ${activeStage.code} • ${activeStage.title}",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 9.sp,
                  color = activeStage.accent,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
              Text(
                text = activeStage.subtitle,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = AmoledTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = activeStage.detail,
              fontFamily = InterFontFamily,
              fontSize = 11.sp,
              lineHeight = 16.sp,
              color = AmoledTextSecondary
            )
          }
        }
      }
    }

    // 3. CORE ARCHITECTURAL PILLARS (Interactive Expandable Modules)
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = Color(0xFF07070B),
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(
              imageVector = Icons.Default.AutoAwesome,
              contentDescription = null,
              tint = Color(0xFF10B981),
              modifier = Modifier.size(14.dp)
            )
            Text(
              text = "CORE ARCHITECTURAL PILLARS",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              color = AmoledActionPrimary,
              fontSize = 10.sp,
              letterSpacing = 0.6.sp
            )
          }
          Text(
            text = "${modules.size} SUBSYSTEMS",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = AmoledStatusSuccess,
            fontSize = 8.5.sp
          )
        }

        modules.forEach { module ->
          ArchitectureModuleCard(
            spec = module,
            expanded = expandedModuleTag == module.tag,
            onToggle = {
              expandedModuleTag = if (expandedModuleTag == module.tag) null else module.tag
            }
          )
        }
      }
    }

    // 4. LIVE RUNTIME DIAGNOSTICS & TOPOLOGY CONSOLE
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = Color(0xFF050508),
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Dns,
              contentDescription = null,
              tint = Color(0xFF38BDF8),
              modifier = Modifier.size(14.dp)
            )
            Text(
              text = "RUNTIME DIAGNOSTICS & TOPOLOGY",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              color = AmoledActionPrimary,
              fontSize = 10.sp,
              letterSpacing = 0.6.sp
            )
          }

          LittleCopyButton(
            textToCopy = diagnosticsSummaryText,
            label = "COPY",
            tint = AmoledIconGreyLight,
            testTag = "about_copy_diagnostics_btn"
          )
        }

        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color(0xFF020204),
          border = BorderStroke(1.dp, Color(0xFF141722)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
          ) {
            DiagnosticRow(
              label = "Active Intelligence Engine",
              value = currentProviderName,
              valueColor = Color(0xFF38BDF8)
            )
            DiagnosticDivider()
            DiagnosticRow(
              label = "Execution Boundary",
              value = sandboxBoundary,
              valueColor = AmoledStatusSuccess
            )
            DiagnosticDivider()
            DiagnosticRow(
              label = "Workspace Root",
              value = workspacePath
            )
            DiagnosticDivider()
            DiagnosticRow(
              label = "Scratchpad Isolation",
              value = "$workspacePath/tmp"
            )
            DiagnosticDivider()
            DiagnosticRow(
              label = "Artifact Deliverable Filter",
              value = "Strict Root Only (tmp/ Excluded)"
            )
            DiagnosticDivider()
            DiagnosticRow(
              label = "Attachment Scope Policy",
              value = "Single-Prompt Scoped (Auto-Cleared on Send)",
              valueColor = AmoledStatusSuccess
            )
            DiagnosticDivider()
            DiagnosticRow(
              label = "UI Design System",
              value = "Jetpack Compose M3 • Pure AMOLED True Black"
            )
          }
        }
      }
    }

    // 5. QUICK ACTIONS ROW
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      AragonActionButton(
        text = "SETTINGS & KEYS",
        onClick = onOpenSettings,
        modifier = Modifier.weight(1f),
        variant = AragonButtonVariant.PRIMARY,
        height = 38.dp,
        testTag = "about_open_settings_direct_btn"
      )

      AragonActionButton(
        text = "REPLAY INTRO",
        onClick = onReplaySplash,
        modifier = Modifier.weight(1f),
        variant = AragonButtonVariant.MUTED,
        height = 38.dp,
        testTag = "replay_splash_intro_btn"
      )
    }

    // 6. INTERACTIVE BUILD NUMBER CARD WITH 10-TAP DEVELOPER UNLOCK
    val buildCardInteraction = remember { MutableInteractionSource() }
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = if (isDevUnlocked) Color(0xFF07150E) else Color(0xFF07070B),
      border = BorderStroke(
        1.dp,
        if (isDevUnlocked) AmoledStatusSuccess.copy(alpha = 0.55f) else Color(0xFF191C26)
      ),
      modifier = Modifier
        .fillMaxWidth()
        .tactilePress(buildCardInteraction, pressedScale = 0.98f)
        .clickable(
          interactionSource = buildCardInteraction,
          indication = ripple(color = AmoledStatusSuccess.copy(alpha = 0.25f)),
          role = Role.Button,
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
                unlockBannerMessage =
                  "You are $remaining tap${if (remaining == 1) "" else "s"} away from Developer Mode"
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
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = if (isDevUnlocked) Icons.Default.Verified else Icons.Default.Code,
            contentDescription = null,
            tint = if (isDevUnlocked) AmoledStatusSuccess else Color(0xFF38BDF8),
            modifier = Modifier.size(13.dp)
          )
          Text(
            text = if (isDevUnlocked) "[DEVELOPER MODE ACTIVE]" else "[BUILD & KERNEL SIGNATURE]",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            color = if (isDevUnlocked) AmoledStatusSuccess else AmoledActionPrimary
          )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text = "Version $APP_VERSION ($BUILD_NUMBER)",
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
          color = AmoledTextPrimary,
          fontWeight = FontWeight.SemiBold,
          fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
          text = if (isDevUnlocked) {
            "Tap to open system settings & runtime configurations"
          } else {
            "Tap 10 times to unlock direct developer configuration shortcut"
          },
          style = MaterialTheme.typography.labelSmall,
          color = AmoledTextMuted,
          fontSize = 9.5.sp,
          textAlign = TextAlign.Center
        )

        // Subtle progress bar when user is actively tapping to unlock
        if (!isDevUnlocked && buildTapCount > 0) {
          Spacer(modifier = Modifier.height(8.dp))
          val progress = (buildTapCount.toFloat() / REQUIRED_TAPS_TO_UNLOCK.toFloat()).coerceIn(0f, 1f)
          Box(
            modifier = Modifier
              .fillMaxWidth(0.65f)
              .height(3.dp)
              .clip(CircleShape)
              .background(Color(0xFF1A1D28))
          ) {
            Box(
              modifier = Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .clip(CircleShape)
                .background(AmoledStatusWarning)
            )
          }
        }

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
private fun HeroMetricItem(
  value: String,
  label: String,
  valueColor: Color
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = value,
      fontFamily = JetBrainsMonoFontFamily,
      fontWeight = FontWeight.ExtraBold,
      fontSize = 11.sp,
      color = valueColor
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = label,
      fontFamily = JetBrainsMonoFontFamily,
      fontWeight = FontWeight.Medium,
      fontSize = 7.5.sp,
      color = AmoledTextMuted
    )
  }
}

@Composable
private fun HeroMetricDivider() {
  Box(
    modifier = Modifier
      .width(1.dp)
      .height(24.dp)
      .background(Color(0xFF1A1E2B))
  )
}

@Composable
private fun SpecTag(
  text: String,
  accent: Color = AmoledIconGreyLight
) {
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = accent.copy(alpha = 0.08f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
  ) {
    Text(
      text = text,
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 8.5.sp,
      fontWeight = FontWeight.SemiBold,
      color = accent,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
    )
  }
}

@Composable
private fun ArchitectureModuleCard(
  spec: ArchitectureModuleSpec,
  expanded: Boolean,
  onToggle: () -> Unit
) {
  val borderCol by animateColorAsState(
    targetValue = if (expanded) spec.accent.copy(alpha = 0.45f) else Color(0xFF151822),
    animationSpec = tween(180),
    label = "module_border_${spec.tag}"
  )
  val bgCol by animateColorAsState(
    targetValue = if (expanded) Color(0xFF0A0C12) else Color(0xFF040407),
    animationSpec = tween(180),
    label = "module_bg_${spec.tag}"
  )

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = bgCol,
    border = BorderStroke(1.dp, borderCol),
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onToggle() }
  ) {
    Column(modifier = Modifier.padding(11.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Icon + Accent Badge
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(spec.accent.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, spec.accent.copy(alpha = 0.35f)), RoundedCornerShape(8.dp))
        ) {
          Icon(
            imageVector = spec.icon,
            contentDescription = null,
            tint = spec.accent,
            modifier = Modifier.size(16.dp)
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = spec.tag,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 8.sp,
              color = spec.accent
            )
            Text(
              text = "•",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.sp,
              color = AmoledTextMuted
            )
            Text(
              text = spec.metrics,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.sp,
              color = AmoledTextMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = spec.title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = AmoledTextPrimary
          )
          Text(
            text = spec.summary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            color = AmoledTextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 14.sp
          )
        }

        Icon(
          imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (expanded) "Collapse" else "Expand",
          tint = AmoledTextMuted,
          modifier = Modifier.size(16.dp)
        )
      }

      AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
      ) {
        Column {
          Spacer(modifier = Modifier.height(8.dp))
          HorizontalDivider(color = Color(0xFF151822), thickness = 1.dp)
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = spec.detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            color = AmoledIconGreyLight,
            fontSize = 10.5.sp,
            lineHeight = 15.5.sp
          )
        }
      }
    }
  }
}

@Composable
private fun DiagnosticDivider() {
  HorizontalDivider(color = Color(0xFF0E1018), thickness = 1.dp)
}

@Composable
private fun DiagnosticRow(
  label: String,
  value: String,
  valueColor: Color = AmoledTextPrimary
) {
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
      modifier = Modifier.padding(end = 10.dp)
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
      fontWeight = FontWeight.Medium,
      color = valueColor,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.End,
      modifier = Modifier.weight(1f, fill = false)
    )
  }
}
