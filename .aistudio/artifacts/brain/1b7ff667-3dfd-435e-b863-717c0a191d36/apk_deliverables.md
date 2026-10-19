# Built APK Package

The Android application debug build has been compiled and is ready in your workspace:

### Deliverable File
- **Path**: `apk/app-debug.apk`
- **Size**: ~29 MB
- **Package**: `com.aragonbysamoguesmia.agentkernel`
- **Status**: Compiled successfully with all latest features.

### Features Included in This Build
1. **AMOLED Unified Prompt Input & Templates**:
   - Replaced old suggestions above the input field with advanced, clean AMOLED-themed workflow presets with unified typography (JetBrains Mono & Inter).
   - Removed distracting icons and excessive borders in favor of high-contrast micro badges, monochrome styling, and subtle hover/press states.
   - Clean hide/show toggle with smooth transitions.

2. **Upgraded Agent Tools**:
   - Enhanced file tools (`ReadFileTool`, `EditFileTool`, `WriteFileTool`) with automatic directory creation, tolerant fuzzy line matching, and intelligent path resolution.
   - Resilient search fallbacks across DuckDuckGo HTML, DuckDuckGo Lite, and Wikipedia.
   - Built-in local workspace execution for core shell commands (`ls`, `cat`, `grep`, `wc`, `tail`) when an external sandbox is unavailable.

3. **Playwright MCP JavaScript Engine**:
   - In-process JavaScript and DOM evaluation engine (`PlaywrightJsEngine`) enabling arbitrary JS execution, DOM queries (`document.querySelector`, `querySelectorAll`), element attribute extraction, and script execution in Playwright MCP tools (`browser_evaluate`).

### Download / Install
In the AI Studio file explorer on the left, navigate to the `apk/` directory to download `app-debug.apk`.
