package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

/**
 * Data specification for an advanced autonomous workflow template.
 */
data class WorkflowSuggestion(
  val id: String,
  val category: String,
  val categoryTag: String,
  val title: String,
  val description: String,
  val prompt: String,
  val expectedArtifact: String? = null
)

/**
 * Advanced Pure-Typography AMOLED Workflow Suggestions.
 *
 * Implements:
 * 1. ZERO icons - 100% typography-driven visual hierarchy.
 * 2. Unified AMOLED styling (deep obsidian surfaces, subtle borders, high-contrast typography).
 * 3. InterFontFamily for readable descriptions and titles; JetBrainsMonoFontFamily for badges, tags, and controls.
 * 4. Multi-category filtering for advanced autonomous operations (Browser JS, Code, Research, Systems).
 * 5. Clean text-only dismissal and expand triggers.
 */
@Composable
fun AmoledWorkflowSuggestions(
  visible: Boolean,
  onDismiss: () -> Unit,
  onSelectSuggestion: (prompt: String, expectedArtifact: String?) -> Unit,
  modifier: Modifier = Modifier
) {
  var selectedCategory by remember { mutableStateOf("ALL") }

  val allSuggestions = remember {
    listOf(
      WorkflowSuggestion(
        id = "browser_js",
        category = "BROWSER",
        categoryTag = "PLAYWRIGHT JS",
        title = "Execute Playwright JS DOM Extraction",
        description = "Navigates target URL, evaluates JavaScript DOM queries, and extracts tabular dataset into workspace.",
        prompt = "Use Playwright MCP to navigate to https://news.ycombinator.com, execute JavaScript with browser_evaluate to extract the top 10 post titles and link URLs, and save the structured result to hn_top_posts.json.",
        expectedArtifact = "hn_top_posts.json"
      ),
      WorkflowSuggestion(
        id = "deep_research",
        category = "RESEARCH",
        categoryTag = "DEEP RESEARCH",
        title = "Autonomous Web & Preprints Investigation",
        description = "Conducts multi-query web and arXiv research, cross-references sources, and synthesizes technical brief.",
        prompt = "Perform deep web research on recent breakthroughs in autonomous AI agent tool execution and context engineering. Cross-reference academic preprints and synthesize an authoritative technical summary report.",
        expectedArtifact = "agent_architecture_brief.md"
      ),
      WorkflowSuggestion(
        id = "code_interpreter",
        category = "CODE",
        categoryTag = "CODE INTERPRETER",
        title = "Data Processing & Statistical Analysis",
        description = "Generates and executes numerical data pipeline, verifies calculation invariants, and outputs clean matrix.",
        prompt = "Write and execute a Python script using code_interpreter that generates statistical metrics (mean, standard deviation, percentiles) for a simulated benchmark dataset and produces a formatted markdown summary.",
        expectedArtifact = "benchmark_metrics.md"
      ),
      WorkflowSuggestion(
        id = "sqlite_analytics",
        category = "SYSTEMS",
        categoryTag = "SQLITE MCP",
        title = "Workspace Database Schema & Query",
        description = "Creates relational SQLite tables, populates structured records, and executes analytical queries.",
        prompt = "Use the SQLite MCP server to create a project tracking schema with tasks, durations, and priorities. Insert sample workload records and run an aggregated performance query.",
        expectedArtifact = "workspace_queries.sql"
      ),
      WorkflowSuggestion(
        id = "doc_synthesis",
        category = "RESEARCH",
        categoryTag = "PDF SYNTHESIS",
        title = "Compile Executive Technical Specification",
        description = "Gathers architectural requirements, formats clean tables, and compiles deliverable PDF document.",
        prompt = "Compile a comprehensive technical specification for an autonomous agent kernel with deterministic tool orchestration, state recovery, and error taxonomy. Export as an executive document.",
        expectedArtifact = "executive_spec.pdf"
      ),
      WorkflowSuggestion(
        id = "system_audit",
        category = "CODE",
        categoryTag = "SYSTEM AUDIT",
        title = "Terminal & Filesystem Verification",
        description = "Inspects environment topology, verifies file boundaries, and benchmarks tool execution pipeline.",
        prompt = "Inspect the sandbox environment topology, list available tools in the registry, verify workspace filesystem permissions, and output an audit status log.",
        expectedArtifact = "environment_audit.log"
      )
    )
  }

  val categories = remember { listOf("ALL", "BROWSER", "CODE", "RESEARCH", "SYSTEMS") }

  val filteredSuggestions = remember(selectedCategory) {
    if (selectedCategory == "ALL") allSuggestions
    else allSuggestions.filter { it.category == selectedCategory }
  }

  AnimatedVisibility(
    visible = visible,
    enter = fadeIn() + expandVertically(),
    exit = fadeOut() + shrinkVertically(),
    modifier = modifier
  ) {
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = Color(0xFF09090D),
      border = BorderStroke(0.85.dp, Color(0xFF1E1E28)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 4.dp)
        .testTag("amoled_workflow_suggestions_panel")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp)
      ) {
        // Pure typography header: Title on left, pure text dismiss on right (NO ICONS)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "SUGGESTED WORKFLOWS",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              letterSpacing = 1.2.sp,
              color = Color(0xFF8E8E98)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
              modifier = Modifier
                .background(Color(0xFF14141E), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text(
                text = "${filteredSuggestions.size}",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                color = Color(0xFF38BDF8)
              )
            }
          }

          // Pure typography dismiss button (NO ICONS)
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF14141E),
            border = BorderStroke(0.75.dp, Color(0xFF262634)),
            modifier = Modifier
              .clickable(role = Role.Button, onClickLabel = "Dismiss suggestions") {
                onDismiss()
              }
              .testTag("dismiss_suggestions_button")
          ) {
            Text(
              text = "DISMISS",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 10.sp,
              letterSpacing = 0.8.sp,
              color = Color(0xFFA1A1AA),
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
          }
        }

        // Pure typography category filter pills (NO ICONS)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          categories.forEach { cat ->
            val isSelected = cat == selectedCategory
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = if (isSelected) Color(0xFF161622) else Color(0xFF0E0E14),
              border = BorderStroke(
                width = if (isSelected) 1.dp else 0.75.dp,
                color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF20202C)
              ),
              modifier = Modifier
                .clickable(role = Role.Tab, onClickLabel = "Filter category $cat") {
                  selectedCategory = cat
                }
                .testTag("category_pill_$cat")
            ) {
              Text(
                text = cat,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF71717A),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
              )
            }
          }
        }

        // Advanced suggestion list cards
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          filteredSuggestions.take(3).forEachIndexed { index, suggestion ->
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = Color(0xFF0F0F16),
              border = BorderStroke(0.75.dp, Color(0xFF1B1B26)),
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                  onSelectSuggestion(suggestion.prompt, suggestion.expectedArtifact)
                }
                .testTag("workflow_suggestion_${suggestion.id}")
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 8.dp)
              ) {
                // Top row: Category tag + deliverable indicator (pure typography)
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "[ ${suggestion.categoryTag} ]",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    color = Color(0xFF38BDF8)
                  )
                  if (!suggestion.expectedArtifact.isNullOrBlank()) {
                    Text(
                      text = "→ ${suggestion.expectedArtifact}",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontWeight = FontWeight.Medium,
                      fontSize = 9.5.sp,
                      color = Color(0xFF10B981)
                    )
                  }
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Suggestion Title
                Text(
                  text = suggestion.title,
                  fontFamily = InterFontFamily,
                  fontWeight = FontWeight.SemiBold,
                  fontSize = 13.sp,
                  color = Color(0xFFF4F4F5)
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Suggestion Description
                Text(
                  text = suggestion.description,
                  fontFamily = InterFontFamily,
                  fontWeight = FontWeight.Normal,
                  fontSize = 11.5.sp,
                  lineHeight = 15.sp,
                  color = Color(0xFF94A3B8)
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Sleek Pure-Typography Trigger Pill to re-open templates when hidden.
 */
@Composable
fun AmoledWorkflowReopenTrigger(
  visible: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  if (visible) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = Color(0xFF0C0C12),
      border = BorderStroke(0.75.dp, Color(0xFF222230)),
      modifier = modifier
        .clickable(role = Role.Button, onClickLabel = "Show suggested workflows") {
          onClick()
        }
        .testTag("reopen_workflow_suggestions")
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "+ WORKFLOW TEMPLATES",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 10.sp,
          letterSpacing = 0.8.sp,
          color = Color(0xFF71717A)
        )
      }
    }
  }
}
