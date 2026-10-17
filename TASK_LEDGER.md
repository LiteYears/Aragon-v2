TASK ID: TASK-2026-002-e2b-integration-audit-repair
TYPE: fix / hardening
STATUS: done
PRIORITY: 🔴 critical
CREATED: 2026-10-13
LAST UPDATED: 2026-10-13
SESSION COUNT: 1

GOAL: Comprehensive source-level investigation, debugging, and end-to-end architectural repair of Aragon's E2B Sandbox integration. Ensure verifiable microVM execution, non-destructive health & identity diagnostics, zero silent fallbacks, task-scoped microVM reuse, disk state persistence, and full regression coverage.

SCOPE IN:
  - Trace and repair complete execution pipeline (LLM tool-call -> parsing -> approval -> dispatcher -> E2B adapter -> remote envd -> structured observation -> artifact sync).
  - Eliminate silent local-shell fallbacks in E2B_CLOUD mode when API keys are missing/invalid.
  - Implement task-scoped Firecracker microVM reuse and disk session checkpointing across app restarts.
  - Fix Connect RPC framing (5-byte envelopes: flags + 4-byte big-endian length + JSON payload).
  - Support envd X-Access-Token / X-API-Key authorization and 401 re-authentication flows.
  - Implement explicit non-destructive E2B health and identity diagnostics (host, user, os-release, diagnostic marker read/write/persist).
  - Add comprehensive regression suite covering sandbox creation, reuse, recovery, diagnostics, exit codes, timeouts, refusal, and artifact synchronization.

SCOPE OUT:
  - Replacing or altering unrelated local EmbeddedSandbox toybox capabilities.
  - Hardcoding dummy/mock responses inside production code paths.

SUCCESS CRITERIA:
  - [x] E2B Connect protocol stream envelope encoding and parsing verified (flags + 4-byte big-endian length + JSON).
  - [x] Zero silent fallback: unauthenticated E2B requests return typed E2BAuthenticationError with exit code 1 and refuse local shell execution.
  - [x] Task-scoped microVM reuse verified: subsequent calls in the same session reuse the existing E2B instance without duplicate provisioning.
  - [x] Persistent session state verified: session metadata persisted to disk and restored across app restarts.
  - [x] Non-destructive health and identity diagnostic verified: writes, reads back, and verifies diagnostic marker in microVM with full report.
  - [x] Artifact discovery and bidirectional sync verified: deliverable files discovered and decoded to workspace.
  - [x] 13/13 unit and Robolectric tests passing cleanly in testDebugUnitTest (BUILD SUCCESSFUL).
  - [x] Full applet compilation verified (compile_applet: BUILD SUCCESSFUL).

CONSTRAINTS: Strictly adhere to official E2B JS/REST SDK v2.6.2 and Connect protocol specifications; never claim microVM execution without cryptographic/identity verification.
RISK: low
ROLLBACK POINT: git commit HEAD~1

CURRENT STEP: Complete
COMPLETED STEPS:
  - [x] Step 1 — Source-level audit of E2B adapter, ToolDispatcher, ToolExecutor, TerminalTool, and SandboxTools — verified 2026-10-13
  - [x] Step 2 — Fix Connect RPC envelope framing and token authentication logic — verified 2026-10-13
  - [x] Step 3 — Implement non-destructive health and identity diagnostic with marker persistence — verified 2026-10-13
  - [x] Step 4 — Implement task-scoped sandbox reuse, disk checkpointing, and unauthenticated refusal — verified 2026-10-13
  - [x] Step 5 — Expand E2BSandboxProviderTest suite with 13 focused regression tests covering all CUJs — verified 2026-10-13
  - [x] Step 6 — Execute full test suite and verify clean compilation — verified 2026-10-13

REMAINING STEPS: None.
OPEN QUESTIONS: None.
BLOCKERS: None.
DISCOVERED ISSUES: None.
NEXT ACTION: Ready for review and user verification.

---

TASK ID: TASK-2026-003-kernel-reliability-hardening
TYPE: bug-fix / reliability sprint
STATUS: done
PRIORITY: 🔴 critical
CREATED: 2026-10-14
LAST UPDATED: 2026-10-14
SESSION COUNT: 1

GOAL: Address root causes BUG-01 through BUG-18 and medium severity issues M-01 to M-07 from the 107-step recon transcript failure. Deliver full Python kernel modules, structured error schema, content cache, budget tracker, observation spill policy, 107-step replay test, and smoke test suite while ensuring Android Kotlin applet compilation and tests pass.

COMPLETED DELIVERABLES:
  - [x] Scoped Git commits per BUG-xx on branch fix/kernel-reliability-sprint.
  - [x] Unit test for each bug in `tests/kernel/test_bug_<01..18>.py`.
  - [x] Integration tests in `tests/kernel/integration/` including 107-step replay (`test_replay_107.py`) and cross-env integration (`test_cross_env_integration.py`).
  - [x] Comprehensive smoke test suite in `tests/kernel/test_smoke.py`.
  - [x] One-line changelog entry in `CHANGELOG.md` for all bugs and enhancements.
  - [x] Structured error module `kernel/tools/errors.py`.
  - [x] Content-addressed fetch cache `kernel/cache/content_cache.py`.
  - [x] Step/token budget and deliverable tracker `kernel/budget.py`.
  - [x] Observation spill manager `kernel/obs/spill.py`.
  - [x] Canonical tool schemas and duplicate tool alias elimination in `kernel/tools/registry.py`.
  - [x] All 61 Python unit/integration tests passing cleanly (100% green).
  - [x] Android Kotlin applet compilation verified (BUILD SUCCESSFUL).

---

TASK ID: TASK-2026-004-kernel-reopen-and-new-failure-fixes
TYPE: bug-fix / reliability sprint
STATUS: done
PRIORITY: 🔴 critical
CREATED: 2026-10-14
LAST UPDATED: 2026-10-14
SESSION COUNT: 1

GOAL: Address reopened FIX-01 (repetition guard argument & observation hashing), FIX-02 (task-aware turn budget & observation visibility), FIX-03 (NO_TARGET_UNAVAILABLE_POLICY), FIX-04 (unambiguous turn accounting), FIX-05 (deliverable tracking & plan status persistence), and complete partial items (BUG-07, BUG-11, BUG-15, M-01, M-04).

COMPLETED DELIVERABLES:
  - [x] FIX-01: RepetitionGuard evaluates argument hashes and observation hashes across sliding window N=12 without tool-name hard-blocking.
  - [x] FIX-01: Replay fixture tests for 6 web_open calls, 5 terminal commands, and 4 identical command trip.
  - [x] FIX-02: Task-aware turn budget derivation (base 20 + deliverables + recon terms + phases clamped [30, 200] with header override).
  - [x] FIX-02: Visible observation prefix `[turn=X/N steps=K deliverables=Y/Z]` and warnings at 50%, 70%, 100%.
  - [x] FIX-03: `TargetUnavailablePolicy` tracking host failures and fast-failing with typed `TARGET_UNREACHABLE` errors.
  - [x] FIX-04: Disambiguated turn vs step accounting with `advance_turn()` and `steps_this_turn`.
  - [x] FIX-05: Deliverable tracking, periodic checklist reminder injection, and `_plan_status.json` write on completion.
  - [x] Partial hardening: BUG-07 observation spilling, BUG-11 content-addressed cache, BUG-15 & M-04 mirror resolution with port preservation, M-01 filtered link extraction.
  - [x] 107-step replay test passes cleanly without repetition guard trips or deliverable failure.
  - [x] All unit and integration tests passing cleanly (17/17 green).
  - [x] Android applet compilation verified (`compile_applet` BUILD SUCCESSFUL).
  - [x] Verified debug APK synced to `apk/app-debug.apk`.


