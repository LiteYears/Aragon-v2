# Aragon.app — Architectural Blueprint

## Project Overview
**Aragon.app** is a production-grade **autonomous AI agent Android application** that executes tasks in **E2B Cloud Firecracker MicroVMs** with comprehensive verification, anti-stagnation, and ground-truth artifact validation.

---

## 1. Directory Structure (Excluding `.git`)

```
Aragon.app/
├── app/                          # Main Android module
│   ├── build.gradle.kts          # App-level Gradle config
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/
│       │   ├── com/example/
│       │   │   ├── MainActivity.kt           # Entry point
│       │   │   ├── agent/
│       │   │   │   ├── core/                 # 66-file agent kernel
│       │   │   │   ├── ui/                   # Compose UI (MVVM)
│       │   │   │   └── service/              # Foreground service
│       │   │   └── ui/theme/                 # Material3 theme
│       │   └── com/alibaba/opensandbox/      # Embedded E2B SDK
│       └── res/                              # Android resources
├── gradle/                       # Gradle wrapper & version catalog
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Project settings
├── gradle.properties             # JVM/performance tuning
├── .env / .env.example           # API keys (NVIDIA, Gemini, E2B)
├── CHANGELOG.md                  # Versioned changes
├── HISTORY_LOG.md                # Session history log
├── TASK_LEDGER.md                # Task tracking
├── MIGRATION_REPORT.md           # Migration docs
├── metadata.json                 # Build metadata
├── apk/                          # Output APKs
└── checkpoints/                  # Session checkpoints
```

---

## 2. Technology Stack

| Layer | Technology |
|-------|------------|
| **Language** | Kotlin 1.9+ (JVM 11) |
| **Build** | Gradle 8.x (Kotlin DSL) |
| **UI** | Jetpack Compose + Material3 |
| **Architecture** | MVVM + ViewModel + StateFlow |
| **Concurrency** | Coroutines + Flow |
| **Networking** | OkHttp 4 + Moshi (KSP codegen) |
| **Persistence** | Room + SharedPreferences + File checkpoints |
| **Sandbox** | **E2B Cloud Firecracker MicroVMs** (Connect RPC) |
| **LLM Providers** | NVIDIA NIM (15-key pool), Gemini REST, Autonomous fallback |
| **Doc Generation** | Native `.docx`/`.xlsx` via OpenXML builders |
| **Min/Target SDK** | API 24 / API 36 |

---

## 3. Core Agent Kernel (`com.example.agent.core` — 66 files)

### Central Orchestrator
- **`AgentEngine.kt`** (86 KB) — Authoritative orchestrator enforcing strict tool pipeline: LLM Call → Schema Validation → Registry → Permissions → Normalize → Execute → Real Result → Observation → Next Turn

### Tool System (Deterministic 8-Step Pipeline)
- **`Tool.kt`** — Canonical interfaces, error taxonomy, execution context
- **`ToolRegistry.kt`** — Single registry with 100+ aliases, schema validation, OpenAI/Gemini function declarations
- **`ToolExecutor.kt`** — 8-step pipeline with idempotency, timeout, cancellation, permission checks
- **`ToolDispatcher.kt`** — Anti-stagnation guards (repetition detection, failure repeat guard), single-dispatch guarantee

### Planning & Reasoning
- **`TaskPlanner.kt`** — Deterministic planner with capability cache, reflection, replanning triggers
- **`ScratchpadMemoryManager.kt`** — Persistent session memory (checkpoints, plans, findings)
- **`ReasoningDedupPipeline.kt`** — Cross-turn LLM reasoning deduplication

### Sandbox & Execution (E2B Firecracker MicroVMs)
- **`SandboxProvider.kt`** (1500+ lines) — `E2BSandboxProvider`: Connect RPC envelope framing (5-byte), envd token auth, session reuse, disk persistence, health diagnostics, artifact sync
- **`SandboxManager.kt`** — Active provider coordination
- **`TerminalTool.kt`** — Shell execution with command linting, CWD tracking
- **`CommandResolver.kt` / `CommandLinter.kt`** — Binary resolution & safety analysis

### Filesystem & Artifacts (Ground-Truth Only)
- **`ArtifactManager.kt`** — Only registers files verified on disk with valid size/checksum
- **`ArtifactValidatorRegistry.kt` / `DeliverableTracker.kt`** — Byte-exact SHA-256 assertions, periodic checklist injection
- **`FilesystemTools.kt`** (33 KB) — 12 native tools: read/write/edit/patch/diff/list/delete/move/copy/create/delete/exists/metadata

### LLM Providers (Hot-Swappable)
- **`LLMProvider.kt`** — Interface + `AutonomousSandboxProvider` / `GeminiLLMProvider`
- **`NvidiaNimProvider.kt`** (37 KB) — Multi-key pool (15 keys), round-robin rotation, model fallback cascade, multi-format tool extraction (native, `<|python_tag|>`, JSON markdown, ReAct)

### Document Builders
- **`DocxBuilder.kt`** — Markdown → `.docx` (OpenXML)
- **`XlsxBuilder.kt`** — Markdown/CSV → `.xlsx`

---

## 4. Key Architectural Patterns

| Pattern | Implementation |
|---------|---------------|
| **Central Orchestrator** | `AgentEngine` — Single source of truth |
| **Deterministic Tool Pipeline** | 8-step: Identity → Registry → Availability → Schema → Permissions → Normalize → Execute → Observation |
| **Reactive State** | `StateFlow<AgentState>` → ViewModel → Compose (unidirectional) |
| **Single-Dispatch Idempotency** | `callId` (`call_<taskId>_<iter>_<random>`) — cached results on duplicate |
| **Anti-Stagnation Guards** | Repetition (N=12, ≥3 arg-hash + ≥2 obs-hash), Failure Repeat Guard, Stagnation (3 turns → replan) |
| **Multi-Provider LLM** | `LLMProvider` interface → runtime hot-swap via `AgentEngine.setProvider()` |
| **Session-Scoped Sandbox Reuse** | E2B session persisted to `.sessions/e2b/session_*.json`, recovered across restarts |
| **Zero Silent Fallback** | E2B_CLOUD throws `IllegalStateException` if API key missing — never local shell |
| **Ground-Truth Artifacts** | Only registers files verified to exist on disk |
| **Five-Stage Cognition** | INTENT → PLAN → ACTION → OBSERVATION → CONCLUSION |
| **Deliverable-Driven Completion** | Byte-size/SHA-256 verification, 5-turn checklist injection |

---

## 5. Entry Points

| Entry Point | Description |
|-------------|-------------|
| `MainActivity` | Launcher activity, hosts `AgentScreen` |
| `AgentViewModel` | Constructs `AgentEngine`, bridges StateFlow to UI |
| `AgentEngine.submitTask()` | Starts autonomous execution coroutine |
| `AgentExecutionService` | Foreground service with WakeLock for background execution |
| `LLMProvider.decideNextAction()` | Called each turn with system prompt, history, tools |

---

## 6. Critical Security Invariants

1. **Zero Silent Fallback** — E2B throws exception if API key missing
2. **Single-Dispatch Idempotency** — Globally unique `callId`, cached results
3. **Ground-Truth Artifacts** — Only registers disk-verified files
4. **Anti-Stagnation Enforcement** — Repetition/failure/stagnation guards
5. **Structured Error Taxonomy** — Every failure classified via `ErrorTaxonomyClassifier`
6. **Observation Payload Policy** — 32KB cap with spillover to `.agent_cache/obs/`
7. **Cross-Environment Path Safety** — `PathSecurity` enforces workspace containment
8. **Deliverable Verification** — Byte-exact SHA-256 on write
9. **Session Persistence** — E2B metadata persisted and restored
10. **Multi-Key Rotation** — 15-key NVIDIA pool with 401/403/429 rotation

---

## 7. Data Flow

```
User Input → AgentViewModel.submitTask()
    ↓
AgentEngine.submitTask() → Task creation → run() coroutine
    ↓
[Per Turn Loop]
  1. DeliverableTracker.scanAndVerify() → AgentState
  2. buildSystemContext() → system prompt
  3. provider.decideNextAction() → LLMDecision
  4. ToolRegistry.normalizeToolCall() → canonical name+args
  5. ToolDispatcher.dispatch() → idempotency, approval, anti-stagnation
  6. ToolExecutor.execute() → 8-step pipeline → ToolResult
  7. createObservation() → structured Observation (32KB capped)
  8. AgentState updated (feed, messages, artifacts, plan, fiveStageRecord)
  9. ScratchpadMemoryManager.saveCheckpoint()
  10. Deliverable verification → COMPLETED
    ↓
AgentExecutionService monitors → foreground notification
    ↓
UI collects StateFlow → renders Feed/Workspace/History/Cognition tabs
```

---

This architecture represents a **verifiable, auditable, resilient autonomous agent kernel** designed for cloud sandbox execution with comprehensive anti-hallucination and ground-truth verification throughout.