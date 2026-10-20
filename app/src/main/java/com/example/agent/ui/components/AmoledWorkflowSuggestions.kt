package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
 * Floating Modern Flat Workflow Suggestions Bar.
 *
 * Implements:
 * 1. Clean White floating flat container with NO borders and subtle soft elevation, matching the user prompt field.
 * 2. High-contrast typography: Dark slate (#0F172A) headers, clear readable descriptions, and refined badges.
 * 3. Categorized interactive workflows with practical, high-value tasks.
 * 4. Dual action: one-tap to populate prompt or immediate launch.
 */
@Composable
fun AmoledWorkflowSuggestions(
  visible: Boolean,
  onDismiss: () -> Unit,
  onSelectSuggestion: (prompt: String, expectedArtifact: String?) -> Unit,
  onExecuteSuggestion: ((prompt: String, expectedArtifact: String?) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var selectedCategory by remember { mutableStateOf("ALL") }

  val allSuggestions = remember {
    listOf(
      WorkflowSuggestion(
        id = "deep_research",
        category = "RESEARCH",
        categoryTag = "WEB RESEARCH",
        title = "Deep Technical Research & Synthesis",
        description = "Conducts multi-query web research, cross-references sources, and synthesizes an authoritative technical brief.",
        prompt = "Perform deep web research on production best practices for autonomous AI agent tool execution, error recovery, and context engineering. Cross-reference academic preprints and authoritative sources, and synthesize an executive research brief.",
        expectedArtifact = "agent_architecture_brief.md"
      ),
      WorkflowSuggestion(
        id = "code_interpreter",
        category = "CODE",
        categoryTag = "DATA & PYTHON",
        title = "Statistical Data Analysis & Metrics",
        description = "Executes Python in sandbox to compute distribution statistics, calculate percentiles, and output clean markdown.",
        prompt = "Write and execute a Python script using code_interpreter to generate statistical metrics (mean, standard deviation, percentiles) for a simulated benchmark dataset and produce a formatted markdown report.",
        expectedArtifact = "benchmark_metrics.md"
      ),
      WorkflowSuggestion(
        id = "browser_js",
        category = "BROWSER",
        categoryTag = "PLAYWRIGHT JS",
        title = "Live DOM Extraction & Data Scraper",
        description = "Navigates target web page, executes DOM extraction, and saves tabular JSON dataset to workspace.",
        prompt = "Use Playwright MCP to navigate to https://news.ycombinator.com, evaluate JavaScript DOM queries to extract the top 10 articles (title, points, URL), and save the structured result into hn_top_posts.json.",
        expectedArtifact = "hn_top_posts.json"
      ),
      WorkflowSuggestion(
        id = "doc_synthesis",
        category = "DOCS",
        categoryTag = "PDF COMPILER",
        title = "Compile Executive PDF Specification",
        description = "Gathers requirements, designs clean structured tables, and compiles an executive deliverable PDF document.",
        prompt = "Compile a comprehensive technical specification for an autonomous agent engine with deterministic tool orchestration, state recovery, and error taxonomy. Export as an executive document.",
        expectedArtifact = "executive_spec.pdf"
      ),
      WorkflowSuggestion(
        id = "system_audit",
        category = "SYSTEMS",
        categoryTag = "WORKSPACE AUDIT",
        title = "Filesystem & Tool Environment Audit",
        description = "Inspects sandbox topology, checks file boundaries, tests tool discovery, and outputs an audit log.",
        prompt = "Inspect the sandbox environment topology, list available tools in the registry, verify workspace filesystem permissions, and output an audit status log.",
        expectedArtifact = "environment_audit.log"
      )
    )
  }

  val categories = remember { listOf("ALL", "RESEARCH", "CODE", "BROWSER", "DOCS", "SYSTEMS") }

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
      shape = RoundedCornerShape(22.dp),
      color = Color.White,
      border = null,
      shadowElevation = 8.dp,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 2.dp, vertical = 6.dp)
        .testTag("amoled_workflow_suggestions_panel")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(14.dp)
      ) {
        // Modern Header: Title on left, dismiss pill on right
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "SUGGESTED WORKFLOWS",
              fontFamily = InterFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 11.5.sp,
              letterSpacing = 1.sp,
              color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              shape = CircleShape,
              color = Color(0xFF0F172A),
              border = null
            ) {
              Text(
                text = "${filteredSuggestions.size}",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          // Modern flat dismiss pill button
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFF1F5F9),
            border = null,
            modifier = Modifier
              .clickable(
                role = Role.Button,
                onClickLabel = "Dismiss suggestions",
                onClick = onDismiss
              )
              .testTag("dismiss_suggestions_button")
          ) {
            Text(
              text = "DISMISS",
              fontFamily = InterFontFamily,
              fontWeight = FontWeight.SemiBold,
              fontSize = 10.sp,
              letterSpacing = 0.5.sp,
              color = Color(0xFF475569),
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
          }
        }

        // Modern Category Filter Pills
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
              shape = RoundedCornerShape(14.dp),
              color = if (isSelected) Color(0xFF0F172A) else Color(0xFFF1F5F9),
              border = null,
              modifier = Modifier
                .clickable(role = Role.Tab, onClickLabel = "Filter category $cat") {
                  selectedCategory = cat
                }
                .testTag("category_pill_$cat")
            ) {
              Text(
                text = cat,
                fontFamily = InterFontFamily,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 10.5.sp,
                letterSpacing = 0.4.sp,
                color = if (isSelected) Color.White else Color(0xFF475569),
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
              )
            }
          }
        }

        // Workflows List Cards
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          filteredSuggestions.take(3).forEach { suggestion ->
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = Color(0xFFF8FAFC),
              border = null,
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                  onSelectSuggestion(suggestion.prompt, suggestion.expectedArtifact)
                }
                .testTag("workflow_suggestion_${suggestion.id}")
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp)
              ) {
                // Top row: Category tag + Deliverable indicator
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFE2E8F0),
                    border = null
                  ) {
                    Text(
                      text = suggestion.categoryTag,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontWeight = FontWeight.Bold,
                      fontSize = 9.sp,
                      color = Color(0xFF0369A1),
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                  }
                  if (!suggestion.expectedArtifact.isNullOrBlank()) {
                    Text(
                      text = "→ ${suggestion.expectedArtifact}",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontWeight = FontWeight.SemiBold,
                      fontSize = 10.sp,
                      color = Color(0xFF059669)
                    )
                  }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Suggestion Title
                Text(
                  text = suggestion.title,
                  fontFamily = InterFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.5.sp,
                  color = Color(0xFF0F172A)
                )

                Spacer(modifier = Modifier.height(3.dp))

                // Suggestion Description
                Text(
                  text = suggestion.description,
                  fontFamily = InterFontFamily,
                  fontWeight = FontWeight.Normal,
                  fontSize = 11.5.sp,
                  lineHeight = 16.sp,
                  color = Color(0xFF475569)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Action Bar: "USE PROMPT" and "RUN NOW ➔"
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.End,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFE2E8F0),
                    border = null,
                    modifier = Modifier.clickable {
                      onSelectSuggestion(suggestion.prompt, suggestion.expectedArtifact)
                    }
                  ) {
                    Text(
                      text = "USE PROMPT",
                      fontFamily = InterFontFamily,
                      fontWeight = FontWeight.SemiBold,
                      fontSize = 9.5.sp,
                      color = Color(0xFF334155),
                      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                  }

                  Spacer(modifier = Modifier.width(6.dp))

                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0F172A),
                    border = null,
                    modifier = Modifier.clickable {
                      if (onExecuteSuggestion != null) {
                        onExecuteSuggestion(suggestion.prompt, suggestion.expectedArtifact)
                      } else {
                        onSelectSuggestion(suggestion.prompt, suggestion.expectedArtifact)
                      }
                    }
                  ) {
                    Text(
                      text = "RUN ➔",
                      fontFamily = InterFontFamily,
                      fontWeight = FontWeight.Bold,
                      fontSize = 9.5.sp,
                      color = Color.White,
                      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Floating Modern Flat Trigger Pill to re-open templates when hidden.
 */
@Composable
fun AmoledWorkflowReopenTrigger(
  visible: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  if (visible) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color.White,
      border = null,
      shadowElevation = 3.dp,
      modifier = modifier
        .clickable(
          role = Role.Button,
          onClickLabel = "Show suggested workflows",
          onClick = onClick
        )
        .testTag("reopen_workflow_suggestions")
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "+ WORKFLOWS",
          fontFamily = InterFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 10.5.sp,
          letterSpacing = 0.5.sp,
          color = Color(0xFF0F172A)
        )
      }
    }
  }
}
