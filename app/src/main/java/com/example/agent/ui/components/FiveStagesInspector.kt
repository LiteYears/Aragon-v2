package com.example.agent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.FiveStageRecord
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

private val StageObsidianBg = Color(0xFF050508)
private val StageCardSurface = Color(0xFF07070B)
private val StageCardElevated = Color(0xFF0C0E14)
private val StageCardBorder = Color(0xFF191C26)

private val StageCyan = Color(0xFF38BDF8)
private val StageIndigo = Color(0xFF6366F1)
private val StageAmber = Color(0xFFF59E0B)
private val StageEmerald = Color(0xFF10B981)
private val StagePurple = Color(0xFFA855F7)

@Composable
fun FiveStagesInspector(
  record: FiveStageRecord,
  modifier: Modifier = Modifier
) {
  val completedStages = remember(record) {
    listOf(
      record.intent.isNotBlank(),
      record.currentPlan.isNotEmpty(),
      record.actions.isNotEmpty(),
      record.observations.isNotEmpty(),
      !record.conclusion.isNullOrBlank()
    ).count { it }
  }

  val fullStagesSummary = remember(record) {
    buildString {
      appendLine("# 5-Stage Agentic Architecture Record")
      appendLine("1. Intent: ${record.intent.ifBlank { "Pending" }}")
      appendLine("2. Plan:\n${if (record.currentPlan.isEmpty()) "Pending" else record.currentPlan.joinToString("\n") { "- $it" }}")
      appendLine("3. Execution:\n${if (record.actions.isEmpty()) "Pending" else record.actions.joinToString("\n")}")
      appendLine("4. Observations:\n${if (record.observations.isEmpty()) "Pending" else record.observations.joinToString("\n")}")
      appendLine("5. Termination: ${record.conclusion ?: "Pending"}")
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
    contentAlignment = Alignment.TopCenter
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 760.dp)
        .padding(horizontal = 14.dp)
        .testTag("five_stages_inspector"),
      contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // Hero Architecture Banner (Matching AboutAragonScreen)
      item(key = "five_stages_hero") {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          color = StageObsidianBg,
          border = BorderStroke(1.dp, StageCardBorder)
        ) {
          Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(modifier = Modifier.matchParentSize()) {
              drawCircle(
                brush = Brush.radialGradient(
                  colors = listOf(
                    StageCyan.copy(alpha = 0.11f),
                    StageIndigo.copy(alpha = 0.04f),
                    Color.Transparent
                  ),
                  center = Offset(size.width * 0.25f, size.height * 0.3f),
                  radius = size.maxDimension * 0.7f
                )
              )
            }

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Surface(
                  shape = RoundedCornerShape(999.dp),
                  color = StageCyan.copy(alpha = 0.12f),
                  border = BorderStroke(1.dp, StageCyan.copy(alpha = 0.35f))
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
                        .background(if (completedStages == 5) StageEmerald else StageCyan)
                    )
                    Text(
                      text = "PIPELINE SNAPSHOT • $completedStages/5 STAGES",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontWeight = FontWeight.Bold,
                      fontSize = 8.5.sp,
                      letterSpacing = 0.8.sp,
                      color = if (completedStages == 5) StageEmerald else StageCyan
                    )
                  }
                }

                LittleCopyButton(
                  textToCopy = fullStagesSummary,
                  testTag = "copy_full_5stages_report"
                )
              }

              Spacer(modifier = Modifier.height(10.dp))

              Text(
                text = "DETERMINISTIC 5-STAGE COGNITIVE LOOP",
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp,
                color = Color.White
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = "Real-time inspection of Intent parsing, DAG Task Planning, Sandboxed Tool Execution, Observation synthesis, and Deliverable Verification.",
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Color(0xFF94A3B8)
              )

              Spacer(modifier = Modifier.height(12.dp))

              // 5-Stage Visual Progress Bar
              val stageBadges = listOf(
                Triple("01 INTENT", record.intent.isNotBlank(), StageCyan),
                Triple("02 PLAN", record.currentPlan.isNotEmpty(), StageIndigo),
                Triple("03 EXEC", record.actions.isNotEmpty(), StageAmber),
                Triple("04 OBSV", record.observations.isNotEmpty(), StageEmerald),
                Triple("05 END", !record.conclusion.isNullOrBlank(), StagePurple)
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                stageBadges.forEach { (label, active, color) ->
                  Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) color.copy(alpha = 0.14f) else StageCardElevated,
                    border = BorderStroke(1.dp, if (active) color.copy(alpha = 0.45f) else StageCardBorder)
                  ) {
                    Column(
                      modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                      horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                      Text(
                        text = label,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 7.5.sp,
                        color = if (active) color else Color(0xFF64748B),
                        maxLines = 1
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      // Stage 1: Intent
      item(key = "stage_1_intent") {
        StageCard(
          stageNumber = 1,
          code = "INTENT",
          title = "Goal & Deliverable Extraction",
          accentColor = StageCyan,
          isCompleted = record.intent.isNotBlank(),
          content = if (record.intent.isNotBlank()) {
            record.intent
          } else {
            "Awaiting user mission prompt to extract primary objective and target deliverables..."
          }
        )
      }

      // Stage 2: Planning
      item(key = "stage_2_plan") {
        StageCard(
          stageNumber = 2,
          code = "PLAN",
          title = "DAG Task Decomposition & Subgoals",
          accentColor = StageIndigo,
          isCompleted = record.currentPlan.isNotEmpty(),
          badgeCount = record.currentPlan.size,
          content = if (record.currentPlan.isNotEmpty()) {
            record.currentPlan.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
          } else {
            "Awaiting structured task decomposition..."
          }
        )
      }

      // Stage 3: Tool Execution
      item(key = "stage_3_exec") {
        StageCard(
          stageNumber = 3,
          code = "EXEC",
          title = "Sandboxed Tool & Command Dispatch",
          accentColor = StageAmber,
          isCompleted = record.actions.isNotEmpty(),
          badgeCount = record.actions.size,
          content = if (record.actions.isNotEmpty()) {
            record.actions.takeLast(40).joinToString("\n")
          } else {
            "No tool invocations dispatched yet..."
          }
        )
      }

      // Stage 4: Observation & State Update
      item(key = "stage_4_obsv") {
        StageCard(
          stageNumber = 4,
          code = "OBSV",
          title = "Observation Synthesis & Self-Healing",
          accentColor = StageEmerald,
          isCompleted = record.observations.isNotEmpty(),
          badgeCount = record.observations.size,
          content = if (record.observations.isNotEmpty()) {
            record.observations.takeLast(30).joinToString("\n\n")
          } else {
            "Awaiting stdout/stderr telemetry and observation synthesis..."
          }
        )
      }

      // Stage 5: Termination Condition
      item(key = "stage_5_end") {
        StageCard(
          stageNumber = 5,
          code = "END",
          title = "Deliverable Verification & Termination",
          accentColor = StagePurple,
          isCompleted = !record.conclusion.isNullOrBlank(),
          content = if (!record.conclusion.isNullOrBlank()) {
            record.conclusion
          } else {
            "Loop active or idle — awaiting final deliverable verification..."
          }
        )
      }
    }
  }
}

@Composable
private fun StageCard(
  stageNumber: Int,
  code: String,
  title: String,
  accentColor: Color,
  isCompleted: Boolean,
  badgeCount: Int = 0,
  content: String
) {
  val borderColor = if (isCompleted) accentColor.copy(alpha = 0.45f) else StageCardBorder

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = StageCardSurface,
    border = BorderStroke(1.dp, borderColor)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          Surface(
            shape = RoundedCornerShape(7.dp),
            color = accentColor.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
          ) {
            Text(
              text = "0$stageNumber // $code",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              color = accentColor,
              fontSize = 8.5.sp,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
            )
          }

          Spacer(modifier = Modifier.width(8.dp))

          Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = Color.White
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          if (badgeCount > 0) {
            Surface(
              shape = RoundedCornerShape(5.dp),
              color = accentColor.copy(alpha = 0.12f),
              border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
            ) {
              Text(
                text = "$badgeCount ITEMS",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
              )
            }
          }

          Surface(
            shape = RoundedCornerShape(5.dp),
            color = if (isCompleted) StageEmerald.copy(alpha = 0.14f) else StageCardElevated,
            border = BorderStroke(
              1.dp,
              if (isCompleted) StageEmerald.copy(alpha = 0.4f) else StageCardBorder
            )
          ) {
            Text(
              text = if (isCompleted) "VERIFIED" else "IDLE",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              color = if (isCompleted) StageEmerald else Color(0xFF64748B),
              fontSize = 7.5.sp,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }

          LittleCopyButton(
            textToCopy = "[$code] $title\n$content",
            testTag = "copy_stage_$stageNumber"
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))
      HorizontalDivider(color = StageCardBorder)
      Spacer(modifier = Modifier.height(10.dp))

      Surface(
        shape = RoundedCornerShape(10.dp),
        color = StageObsidianBg,
        border = BorderStroke(1.dp, StageCardBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = content,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = JetBrainsMonoFontFamily,
          color = if (isCompleted) Color(0xFFE2E8F0) else Color(0xFF64748B),
          fontSize = 10.5.sp,
          lineHeight = 16.sp,
          modifier = Modifier.padding(10.dp)
        )
      }
    }
  }
}
