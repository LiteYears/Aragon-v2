# Changelog

All notable changes to the Agent Kernel execution reliability sprint are documented below.

## [1.3.0] - 2026-10-14
- **FIX-01**: Repetition guard hashes arguments and observation text across sliding window (N=12), tripping only if identical call_sig appears >=3 times and obs_sig appears >=2 times without progress. Never blocks on tool name alone.
- **FIX-02**: Task-aware turn budget derivation (base 20 + 5 per deliverable + 10 recon + 5 per phase, clamped [30, 200], header override), visible observation prefixes, 50% soft warning, 70% hard warning, and graceful 100% final turn.
- **FIX-03**: Target Unavailable Policy (`NO_TARGET_UNAVAILABLE_POLICY`) tracking consecutive host failures, fast-failing on unreachable targets with typed `TARGET_UNREACHABLE` errors and offline analysis guidance.
- **FIX-04**: Unambiguous turn and step accounting (`turn=T/BUDGET step=S deliverables=X/Y`) with `advance_turn()`.
- **FIX-05**: Deliverable plan tracker with byte size and SHA-256 assertions, periodic 5-turn checklist injection, and `_plan_status.json` persistence.
- **PARTIAL HARDENING**: Expanded mirror resolution with port preservation (BUG-15, M-04), verified observation spilling (BUG-07), content-addressed fetch cache (BUG-11), and keyword link filtering (M-01).

## [1.2.0] - 2026-10-14
- **BUG-01**: Repetition guard updated to evaluate canonical argument hashes across sliding window and never hard-block terminal until 5 consecutive identical calls with zero progress.
- **BUG-02**: Unified workspace path resolution via `resolve_workspace_root`, allowing in-workspace `/home/user` and `/tmp` scratch paths without false lint errors.
- **BUG-03**: Honor `maxChars` up to configurable ceiling, spill oversized bodies to disk cache, support `offset` pagination, and return verbatim text for code/data content types.
- **BUG-04**: Enforced byte-exact `write_file` without regex sanitization, asserting roundtrip SHA-256 match and opt-in unified diff verification.
- **BUG-05**: Unified active environment tracking in topology and observations, mirroring files transparently across local and cloud virtual workspaces.
- **BUG-06**: Injected tool execution parameters into command linting to recognize bounded curl pipes via `timeoutSeconds` and generate machine-applicable auto-fixes.
- **BUG-07**: Implemented observation spilling to `.agent_cache/obs/` for outputs exceeding 16 KB, returning head/tail previews and pagination hints.
- **BUG-08**: Introduced `browser_open`, `browser_eval`, and `browser_network` tools for JavaScript-rendered SPAs with automated client-side rendering detection.
- **BUG-09**: Added structured `http_request` tool with exponential backoff on 5xx errors, jitter, and Retry-After header extraction.
- **BUG-10**: Added deliverable plan tracking from task intent, providing periodic reminders and discovery-to-delivery redirection at 70% budget.
- **BUG-11**: Introduced content-addressed fetch cache keyed by URL, HTTP method, and body SHA with TTL tracking and deduplication.
- **BUG-12**: Optimized observation payloads to delta format containing status, structured attributes, and stdout previews rather than raw full stdout dumps.
- **BUG-13**: Standardized machine-readable remediation in tool errors with structured auto-fix actions (`shell_rewrite`, tool retry parameters).
- **BUG-14**: Enforced written byte count assertion in `write_file` and refused direct writes of truncated observation content (`SOURCE_TRUNCATED`).
- **BUG-15**: Added automated retry policy with backoff on network/5xx errors and fallback domain resolution on HTTP 503 for known mirrors.
- **BUG-16**: Automated virtual workspace mirroring between local and cloud environments, eliminating manual file transfer requirement errors.
- **BUG-17**: Added `append_file` tool across environments, injected running log standing instructions, and added session overwrite warnings.
- **BUG-18**: Integrated step and token budget tracking with automatic 60% budget constraints directing agents toward deliverable finalization.
- **M-01**: Filtered link extraction to same-host and recon-relevant keywords, capped at 50 links with deduplication.
- **M-02**: Injected real runtime ISO date (`today_iso`) into all system prompt contexts.
- **M-03**: Emitted warning pointing to `browser_open` when zero links are discovered on pages containing script bundles.
- **M-04**: Added `resolve_mirror` helper tool backed by production and staging mirror configuration mapping.
- **M-05**: Documented tool precedence order (`browser_open > http_request > web_open > terminal+curl`) in selection prompt.
- **M-06**: Emitted `FILE_TRUNCATION_SUSPECTED` hint when grep returns empty results on files smaller than fetch cache size.
- **M-07**: Hashed observations and skipped duplicate consecutive reinjections into reasoning context.
