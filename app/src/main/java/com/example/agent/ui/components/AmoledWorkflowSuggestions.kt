package com.example.agent.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class WorkflowSuggestion(
    val id: String,
    val category: String,
    val title: String,
    val subtitle: String,
    val prompt: String,
    val badgeText: String,
    val accentColor: Color,
    val icon: ImageVector
)

val DEFAULT_WORKFLOW_SUGGESTIONS = listOf(
    // 1. RESEARCH & WEB
    WorkflowSuggestion(
        id = "deep_web_research",
        category = "RESEARCH",
        title = "Deep Multi-Source Synthesis",
        subtitle = "Cross-verify live web data & compile an executive Markdown brief",
        prompt = "Perform a comprehensive multi-source web research investigation on the latest advancements in edge AI inference and autonomous agent architectures. Cross-verify claims across multiple sources, extract key benchmark tables, and save a structured executive report to `research_report.md`.",
        badgeText = "WEB + MD",
        accentColor = Color(0xFF38BDF8),
        icon = Icons.Default.Public
    ),
    WorkflowSuggestion(
        id = "competitor_matrix",
        category = "RESEARCH",
        title = "Technical Architecture Audit",
        subtitle = "Compare frameworks, latency profiles & trade-offs with citations",
        prompt = "Research and compare the top open-source local LLM inference engines (vLLM, llama.cpp, TensorRT-LLM, Ollama). Create a detailed Markdown comparison table covering throughput, memory footprint, quantization support, and deployment trade-offs, and save it to `inference_comparison.md`.",
        badgeText = "MATRIX",
        accentColor = Color(0xFF38BDF8),
        icon = Icons.Default.Science
    ),

    // 2. CODE & SANDBOX
    WorkflowSuggestion(
        id = "python_data_pipeline",
        category = "CODE & DATA",
        title = "Python Analytics & Charting",
        subtitle = "Synthesize dataset, run statistical analysis & export high-res plots",
        prompt = "Write and execute a Python script in the sandbox that generates a realistic 12-month SaaS telemetry dataset (MRR, churn, CAC, LTV), performs statistical trend and cohort analysis, generates a clean high-resolution matplotlib summary chart saved to `saas_metrics.png`, and writes an executive summary to `analytics_summary.md`.",
        badgeText = "PYTHON",
        accentColor = Color(0xFF10B981),
        icon = Icons.Default.Terminal
    ),
    WorkflowSuggestion(
        id = "fullstack_prototype",
        category = "CODE & DATA",
        title = "Interactive HTML5 Dashboard",
        subtitle = "Build a self-contained single-file interactive analytics web app",
        prompt = "Create a complete, self-contained, responsive HTML5/CSS/JS interactive real-time system telemetry dashboard in `dashboard.html` with dark AMOLED styling, interactive charts, sortable data tables, and simulated live metric streams. Verify its syntax and structure.",
        badgeText = "HTML5/JS",
        accentColor = Color(0xFF10B981),
        icon = Icons.Default.Code
    ),

    // 3. DOCUMENTS & OFFICE
    WorkflowSuggestion(
        id = "executive_pdf_brief",
        category = "DELIVERABLES",
        title = "Executive PDF & DOCX Suite",
        subtitle = "Generate a formatted technical whitepaper with tables & metrics",
        prompt = "Generate a polished, publication-ready technical whitepaper on 'Autonomous Cloud Sandbox Security & Zero-Trust Execution' as both a formatted PDF (`security_whitepaper.pdf`) and Word document (`security_whitepaper.docx`), complete with an executive summary, threat model table, and architecture recommendations.",
        badgeText = "PDF + DOCX",
        accentColor = Color(0xFFA855F7),
        icon = Icons.Default.Description
    ),
    WorkflowSuggestion(
        id = "financial_xlsx_model",
        category = "DELIVERABLES",
        title = "Financial Model Spreadsheet",
        subtitle = "Build a multi-sheet Excel workbook with formulas & projections",
        prompt = "Use Python (`openpyxl` or `pandas`) to build a structured multi-sheet Excel financial model saved to `financial_projection.xlsx` containing a 3-year monthly revenue forecast, unit economics breakdown, and sensitivity analysis table, plus a companion `model_notes.md`.",
        badgeText = "XLSX",
        accentColor = Color(0xFFF59E0B),
        icon = Icons.Default.Build
    ),

    // 4. WORKSPACE & DOCS RAG
    WorkflowSuggestion(
        id = "workspace_audit",
        category = "WORKSPACE",
        title = "Workspace & Document Synthesis",
        subtitle = "Inspect attached files or workspace artifacts & extract insights",
        prompt = "Inspect the current workspace files and any indexed documents, summarize their structure, key findings, and actionable takeaways, and compile a consolidated `workspace_digest.md` report.",
        badgeText = "RAG + FS",
        accentColor = Color(0xFFEC4899),
        icon = Icons.Default.AutoAwesome
    )
)

@Composable
fun AmoledWorkflowSuggestions(
    onSelectPrompt: (String) -> Unit,
    onRunPromptImmediately: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = remember { listOf("ALL", "RESEARCH", "CODE & DATA", "DELIVERABLES", "WORKSPACE") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var showAllCards by remember { mutableStateOf(false) }
    var shuffleSeed by remember { mutableIntStateOf(0) }

    val filteredSuggestions = remember(selectedCategory, shuffleSeed) {
        val base = if (selectedCategory == "ALL") {
            DEFAULT_WORKFLOW_SUGGESTIONS
        } else {
            DEFAULT_WORKFLOW_SUGGESTIONS.filter { it.category == selectedCategory }
        }
        if (shuffleSeed == 0) base else base.shuffled(kotlin.random.Random(shuffleSeed))
    }

    val visibleSuggestions = remember(filteredSuggestions, showAllCards) {
        if (showAllCards) filteredSuggestions else filteredSuggestions.take(3)
    }

    Surface(
        color = Color(0xFF050508),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF191C26)),
        shadowElevation = 10.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("amoled_workflow_suggestions_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0D1E36).copy(alpha = 0.40f),
                            Color(0xFF050508)
                        ),
                        center = Offset(220f, 40f),
                        radius = 680f
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.14f))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f), RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "AUTONOMOUS WORKFLOW BLUEPRINTS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.1.sp
                                ),
                                color = Color(0xFFF8FAFC)
                            )
                            Surface(
                                color = Color(0xFF10B981).copy(alpha = 0.14f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.5.dp, Color(0xFF10B981).copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "${filteredSuggestions.size} READY",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "Tap a blueprint to populate the command dock or launch immediately",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Shuffle / cycle button
                    Surface(
                        color = Color(0xFF0A0D14),
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { shuffleSeed++ }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Shuffle blueprints",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    // Expand / Collapse count toggle
                    if (filteredSuggestions.size > 3) {
                        Surface(
                            color = Color(0xFF0A0D14),
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, Color(0xFF1E293B)),
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { showAllCards = !showAllCards }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (showAllCards) "LESS" else "ALL (${filteredSuggestions.size})",
                                    fontSize = 8.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFCBD5E1)
                                )
                                Icon(
                                    imageVector = if (showAllCards) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (showAllCards) "Show fewer" else "Show all",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    // Dismiss button
                    Surface(
                        color = Color(0xFF0A0D14),
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { onDismiss() }
                            .testTag("dismiss_suggestions_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss workflow suggestions",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            // Category Filter Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    val catAccent = when (cat) {
                        "RESEARCH" -> Color(0xFF38BDF8)
                        "CODE & DATA" -> Color(0xFF10B981)
                        "DELIVERABLES" -> Color(0xFFA855F7)
                        "WORKSPACE" -> Color(0xFFEC4899)
                        else -> Color(0xFF38BDF8)
                    }
                    Surface(
                        color = if (isSelected) catAccent.copy(alpha = 0.16f) else Color(0xFF07070B),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) catAccent.copy(alpha = 0.55f) else Color(0xFF191C26)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedCategory = cat }
                            .testTag("category_pill_$cat")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(catAccent)
                                )
                            }
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 9.5.sp,
                                    letterSpacing = 0.6.sp
                                ),
                                color = if (isSelected) Color(0xFFF8FAFC) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // Blueprint Cards List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                visibleSuggestions.forEach { suggestion ->
                    AmoledWorkflowCard(
                        suggestion = suggestion,
                        onSelect = { onSelectPrompt(suggestion.prompt) },
                        onRunNow = onRunPromptImmediately?.let { runner ->
                            { runner(suggestion.prompt) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AmoledWorkflowCard(
    suggestion: WorkflowSuggestion,
    onSelect: () -> Unit,
    onRunNow: (() -> Unit)?
) {
    val accent = suggestion.accentColor

    Surface(
        color = Color(0xFF07070B),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF191C26)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .testTag("workflow_suggestion_${suggestion.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.08f),
                            Color(0xFF07070B),
                            Color(0xFF07070B)
                        )
                    )
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = suggestion.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = suggestion.title,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = Color(0xFFF8FAFC),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            color = accent.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(0.5.dp, accent.copy(alpha = 0.40f))
                        ) {
                            Text(
                                text = suggestion.badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 8.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = accent,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Text(
                        text = suggestion.subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp
                        ),
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Populate editor pill
                Surface(
                    color = Color(0xFF0D111A),
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onSelect() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Insert into prompt",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "EDIT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.5.sp
                            ),
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                // Direct execute button if provided
                if (onRunNow != null) {
                    Surface(
                        color = accent.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { onRunNow() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "RUN",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 8.5.sp
                                ),
                                color = accent
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Run blueprint now",
                                tint = accent,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AmoledWorkflowReopenTrigger(
    onReopen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF07070B),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color(0xFF191C26)),
        shadowElevation = 4.dp,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onReopen() }
            .testTag("reopen_workflow_suggestions")
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF38BDF8).copy(alpha = 0.12f),
                            Color(0xFF07070B)
                        )
                    )
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8))
            )
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "WORKFLOW BLUEPRINTS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.7.sp
                ),
                color = Color(0xFFE2E8F0)
            )
        }
    }
}
