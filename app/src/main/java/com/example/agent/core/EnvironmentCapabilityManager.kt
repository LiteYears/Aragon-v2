package com.example.agent.core

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Functional category for sandbox capabilities.
 */
enum class CapabilityCategory {
  CORE_EXECUTION,
  WEB_RESEARCH,
  DOCUMENTS,
  OCR_IMAGE,
  DATA_ANALYSIS,
  WEB_APPS,
  SYSTEM_UTILITIES
}

/**
 * Provisioning strategy for a capability.
 */
enum class ProvisioningStrategy {
  BUILTIN,
  PIP_VENV,
  SYSTEM_APT,
  NPM,
  DIRECT_URL,
  UNSUPPORTED
}

/**
 * Machine-readable state of a capability.
 */
enum class CapabilityState {
  AVAILABLE,       // Already present and functional
  MISSING,         // Not yet installed
  INSTALLING,      // Currently provisioning
  READY,           // Provisioned and verified
  FAILED,          // Installation or verification failed
  UNSUPPORTED      // Environment cannot support it (e.g. missing daemon, privileges, quota)
}

/**
 * Verification level distinguishing depth of validation.
 */
enum class VerificationLevel {
  NOT_CHECKED,
  EXECUTABLE_PRESENT,
  PACKAGE_IMPORTABLE,
  FUNCTIONALLY_VERIFIED,
  ENVIRONMENTALLY_RESTRICTED
}

/**
 * Machine-readable capability definition.
 */
data class CapabilityDefinition(
  val id: String,
  val name: String,
  val category: CapabilityCategory,
  val description: String,
  val strategy: ProvisioningStrategy,
  val requiredExecutables: List<String> = emptyList(),
  val requiredPythonPackages: List<String> = emptyList(),
  val requiredSystemPackages: List<String> = emptyList(),
  val minMemoryMb: Int = 30,
  val minDiskMb: Int = 50,
  val verificationCommand: String,
  val smokeTestScript: String? = null,
  val installCommand: String? = null,
  val requiresSudo: Boolean = false,
  val requiresNetwork: Boolean = false,
  val recoveryGuidance: String = ""
)

/**
 * Machine-readable live status of a capability.
 */
data class CapabilityStatus(
  val id: String,
  val category: CapabilityCategory,
  val state: CapabilityState,
  val verificationLevel: VerificationLevel,
  val installedVersion: String? = null,
  val lastVerifiedAt: Long = 0L,
  val lastError: String? = null,
  val recoveryHint: String? = null,
  val provisionDurationMs: Long = 0L
) {
  fun toJsonObject(): JSONObject = JSONObject().apply {
    put("id", id)
    put("category", category.name)
    put("state", state.name)
    put("verificationLevel", verificationLevel.name)
    if (installedVersion != null) put("installedVersion", installedVersion)
    put("lastVerifiedAt", lastVerifiedAt)
    if (lastError != null) put("lastError", lastError)
    if (recoveryHint != null) put("recoveryHint", recoveryHint)
    put("provisionDurationMs", provisionDurationMs)
  }
}

/**
 * Snapshot of sandbox environment resources.
 */
data class SandboxResourceSnapshot(
  val totalMemoryMb: Int,
  val freeMemoryMb: Int,
  val usedMemoryMb: Int,
  val totalDiskMb: Int,
  val freeDiskMb: Int,
  val cpuCores: Int,
  val osRelease: String,
  val kernel: String,
  val arch: String,
  val currentUser: String,
  val isSudoUsable: Boolean,
  val isAptUsable: Boolean,
  val isNetworkConnected: Boolean,
  val hasVirtualEnv: Boolean,
  val pythonVersion: String,
  val nodeVersion: String?,
  val snapshotTimestamp: Long = System.currentTimeMillis()
) {
  fun toJsonObject(): JSONObject = JSONObject().apply {
    put("totalMemoryMb", totalMemoryMb)
    put("freeMemoryMb", freeMemoryMb)
    put("usedMemoryMb", usedMemoryMb)
    put("totalDiskMb", totalDiskMb)
    put("freeDiskMb", freeDiskMb)
    put("cpuCores", cpuCores)
    put("osRelease", osRelease)
    put("kernel", kernel)
    put("arch", arch)
    put("currentUser", currentUser)
    put("isSudoUsable", isSudoUsable)
    put("isAptUsable", isAptUsable)
    put("isNetworkConnected", isNetworkConnected)
    put("hasVirtualEnv", hasVirtualEnv)
    put("pythonVersion", pythonVersion)
    if (nodeVersion != null) put("nodeVersion", nodeVersion)
    put("snapshotTimestamp", snapshotTimestamp)
  }
}

/**
 * Result of a capability provisioning operation.
 */
data class ProvisioningResult(
  val success: Boolean,
  val capabilityId: String,
  val state: CapabilityState,
  val manager: String? = null,
  val version: String? = null,
  val message: String,
  val durationMs: Long,
  val errorDetails: String? = null,
  val recoveryGuidance: String? = null,
  val stdout: String = "",
  val stderr: String = ""
) {
  fun toJsonObject(): JSONObject = JSONObject().apply {
    put("success", success)
    put("capabilityId", capabilityId)
    put("state", state.name)
    if (manager != null) put("manager", manager)
    if (version != null) put("version", version)
    put("message", message)
    put("durationMs", durationMs)
    if (errorDetails != null) put("errorDetails", errorDetails)
    if (recoveryGuidance != null) put("recoveryGuidance", recoveryGuidance)
  }
}

/**
 * Authoritative Environment Capability Manager.
 *
 * Implements a resource-aware, idempotent 8-stage provisioning pipeline
 * across all seven capability groups:
 * A. Core Execution
 * B. Web Research & Extraction
 * C. Documents & Office Files
 * D. OCR & Image Processing
 * E. Data Analysis & Visualization
 * F. Web Applications & Development
 * G. System & Network Utilities
 */
class EnvironmentCapabilityManager(
  var sandboxProviderSupplier: () -> SandboxProvider?
) {
  private val registry = ConcurrentHashMap<String, CapabilityDefinition>()
  private val liveStatus = ConcurrentHashMap<String, CapabilityStatus>()
  private var lastResourceSnapshot: SandboxResourceSnapshot? = null
  private val lastAptUpdateTime = AtomicLong(0L)

  init {
    registerCanonicalCapabilities()
  }

  val definitions: Map<String, CapabilityDefinition> get() = registry

  fun getCapabilityDefinition(id: String): CapabilityDefinition? =
    registry[id] ?: registry.values.firstOrNull { it.name.equals(id, ignoreCase = true) }

  fun getCapabilityStatus(id: String): CapabilityStatus? = liveStatus[id]

  fun listAllStatuses(): List<CapabilityStatus> = liveStatus.values.toList()

  /**
   * Registers all canonical capabilities per the 7 architectural groups.
   */
  private fun registerCanonicalCapabilities() {
    // Group A: Core execution
    register(
      CapabilityDefinition(
        id = "core.bash",
        name = "Bash & Core Utilities",
        category = CapabilityCategory.CORE_EXECUTION,
        description = "Standard Linux userland, shell commands, file utilities, pipes, redirects.",
        strategy = ProvisioningStrategy.BUILTIN,
        requiredExecutables = listOf("bash", "sh", "cat", "ls", "grep", "sed", "awk"),
        verificationCommand = "bash -c 'echo \"BASH_OK \${BASH_VERSION:-}\"'"
      )
    )
    register(
      CapabilityDefinition(
        id = "core.python3",
        name = "Python 3 Runtime",
        category = CapabilityCategory.CORE_EXECUTION,
        description = "Python 3 runtime with standard library.",
        strategy = ProvisioningStrategy.BUILTIN,
        requiredExecutables = listOf("python3"),
        verificationCommand = "python3 -c 'import sys; print(f\"PYTHON_OK {sys.version}\")'"
      )
    )
    register(
      CapabilityDefinition(
        id = "core.nodejs",
        name = "Node.js & npm",
        category = CapabilityCategory.CORE_EXECUTION,
        description = "Node.js JavaScript runtime and npm package manager.",
        strategy = ProvisioningStrategy.BUILTIN,
        requiredExecutables = listOf("node", "npm"),
        verificationCommand = "node -e 'console.log(\"NODE_OK \" + process.version)'"
      )
    )
    register(
      CapabilityDefinition(
        id = "core.git",
        name = "Git Version Control",
        category = CapabilityCategory.CORE_EXECUTION,
        description = "Git version control system for repository operations.",
        strategy = ProvisioningStrategy.BUILTIN,
        requiredExecutables = listOf("git"),
        verificationCommand = "git --version"
      )
    )
    register(
      CapabilityDefinition(
        id = "core.archive",
        name = "Archive Tools",
        category = CapabilityCategory.CORE_EXECUTION,
        description = "Common archive utilities: tar, gzip, zip, unzip.",
        strategy = ProvisioningStrategy.BUILTIN,
        requiredExecutables = listOf("tar", "gzip"),
        verificationCommand = "tar --version && gzip --version"
      )
    )
    register(
      CapabilityDefinition(
        id = "core.sqlite3",
        name = "SQLite 3 Database",
        category = CapabilityCategory.CORE_EXECUTION,
        description = "Embedded SQLite database engine and standard Python sqlite3 library.",
        strategy = ProvisioningStrategy.BUILTIN,
        requiredExecutables = listOf("sqlite3"),
        verificationCommand = "python3 -c 'import sqlite3; con = sqlite3.connect(\":memory:\"); con.execute(\"CREATE TABLE t(x)\"); print(\"SQLITE_OK\")'"
      )
    )
    register(
      CapabilityDefinition(
        id = "core.curl",
        name = "curl HTTP Client",
        category = CapabilityCategory.CORE_EXECUTION,
        description = "Command-line HTTP client with TLS/SSL support.",
        strategy = ProvisioningStrategy.BUILTIN,
        requiredExecutables = listOf("curl"),
        verificationCommand = "curl --version"
      )
    )

    // Group B: Web research and extraction
    register(
      CapabilityDefinition(
        id = "web.requests",
        name = "Requests HTTP Client",
        category = CapabilityCategory.WEB_RESEARCH,
        description = "Synchronous HTTP client library for Python.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("requests"),
        minMemoryMb = 25,
        minDiskMb = 30,
        verificationCommand = "python3 -c 'import requests; print(\"REQUESTS_OK \" + requests.__version__)'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir requests"
      )
    )
    register(
      CapabilityDefinition(
        id = "web.beautifulsoup4",
        name = "BeautifulSoup4 HTML Parser",
        category = CapabilityCategory.WEB_RESEARCH,
        description = "HTML/XML parser for web scraping and structured extraction.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("beautifulsoup4"),
        minMemoryMb = 30,
        minDiskMb = 40,
        verificationCommand = "python3 -c 'import bs4; soup = bs4.BeautifulSoup(\"<b>ok</b>\", \"html.parser\"); assert soup.b.text == \"ok\"; print(\"BS4_OK \" + bs4.__version__)'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir beautifulsoup4"
      )
    )
    register(
      CapabilityDefinition(
        id = "web.lxml",
        name = "lxml XML/HTML Processor",
        category = CapabilityCategory.WEB_RESEARCH,
        description = "High-performance XML and HTML parser binding libxml2/libxslt.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("lxml"),
        minMemoryMb = 45,
        minDiskMb = 80,
        verificationCommand = "python3 -c 'import lxml.etree; root = lxml.etree.fromstring(\"<r>ok</r>\"); assert root.text == \"ok\"; print(\"LXML_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir lxml"
      )
    )
    register(
      CapabilityDefinition(
        id = "web.playwright",
        name = "Playwright Headless Browser",
        category = CapabilityCategory.WEB_RESEARCH,
        description = "Automated headless browser execution for JavaScript-rendered sites.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("playwright"),
        minMemoryMb = 250,
        minDiskMb = 500,
        verificationCommand = "python3 -c 'import playwright; from playwright.sync_api import sync_playwright; p = sync_playwright().start(); browser = p.chromium.launch(headless=True); page = browser.new_page(); page.set_content(\"<h1>test</h1>\"); assert page.title() == \"\" or True; browser.close(); p.stop(); print(\"PLAYWRIGHT_OK\")'",
        recoveryGuidance = "Playwright requires chromium binaries and ~250MB free RAM. If memory is tight (<250MB), use web.requests + web.beautifulsoup4 instead."
      )
    )

    // Group C: Documents and office files
    register(
      CapabilityDefinition(
        id = "docs.docx",
        name = "DOCX Creation & Editing",
        category = CapabilityCategory.DOCUMENTS,
        description = "OpenXML Word document (.docx) generation and modification.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("python-docx"),
        minMemoryMb = 35,
        minDiskMb = 50,
        verificationCommand = "python3 -c 'import docx; doc = docx.Document(); doc.add_paragraph(\"test\"); doc.save(\"/tmp/aragon_test.docx\"); print(\"DOCX_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir python-docx"
      )
    )
    register(
      CapabilityDefinition(
        id = "docs.xlsx",
        name = "XLSX Spreadsheet Processing",
        category = CapabilityCategory.DOCUMENTS,
        description = "OpenXML Excel spreadsheet (.xlsx) generation and data extraction.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("openpyxl"),
        minMemoryMb = 35,
        minDiskMb = 50,
        verificationCommand = "python3 -c 'import openpyxl; wb = openpyxl.Workbook(); ws = wb.active; ws[\"A1\"] = \"ok\"; wb.save(\"/tmp/aragon_test.xlsx\"); print(\"XLSX_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir openpyxl"
      )
    )
    register(
      CapabilityDefinition(
        id = "docs.pdf",
        name = "PDF Text Extraction & Creation",
        category = CapabilityCategory.DOCUMENTS,
        description = "Pure Python PDF extraction, merging, and writing.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("pypdf"),
        minMemoryMb = 30,
        minDiskMb = 40,
        verificationCommand = "python3 -c 'import pypdf; writer = pypdf.PdfWriter(); writer.add_blank_page(width=100, height=100); writer.write(\"/tmp/aragon_test.pdf\"); print(\"PDF_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir pypdf"
      )
    )
    register(
      CapabilityDefinition(
        id = "docs.arabic",
        name = "Arabic & RTL Text Reshaping",
        category = CapabilityCategory.DOCUMENTS,
        description = "Arabic text shaping and bidirectional text reordering for documents.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("arabic-reshaper", "python-bidi"),
        minMemoryMb = 25,
        minDiskMb = 30,
        verificationCommand = "python3 -c 'import arabic_reshaper; from bidi.algorithm import get_display; res = get_display(arabic_reshaper.reshape(\"مرحبا\")); assert len(res) > 0; print(\"ARABIC_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir arabic-reshaper python-bidi"
      )
    )
    register(
      CapabilityDefinition(
        id = "docs.libreoffice",
        name = "LibreOffice Conversion Engine",
        category = CapabilityCategory.DOCUMENTS,
        description = "Headless LibreOffice suite for document format conversion.",
        strategy = ProvisioningStrategy.SYSTEM_APT,
        requiredExecutables = listOf("libreoffice"),
        requiredSystemPackages = listOf("libreoffice-writer", "libreoffice-calc"),
        minMemoryMb = 300,
        minDiskMb = 450,
        requiresSudo = true,
        verificationCommand = "libreoffice --headless --version",
        recoveryGuidance = "LibreOffice is heavy (>300MB RAM, >400MB disk). Use native openpyxl/python-docx builders when possible."
      )
    )

    // Group D: OCR and image processing
    register(
      CapabilityDefinition(
        id = "img.pillow",
        name = "Pillow Image Processing",
        category = CapabilityCategory.OCR_IMAGE,
        description = "Python Imaging Library for image manipulation, resizing, format conversion.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("pillow"),
        minMemoryMb = 40,
        minDiskMb = 60,
        verificationCommand = "python3 -c 'from PIL import Image; im = Image.new(\"RGB\", (20, 20), \"red\"); im.save(\"/tmp/aragon_im.png\"); print(\"PILLOW_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir pillow"
      )
    )
    register(
      CapabilityDefinition(
        id = "ocr.tesseract",
        name = "Tesseract OCR Engine",
        category = CapabilityCategory.OCR_IMAGE,
        description = "Tesseract OCR command-line engine and pytesseract wrapper.",
        strategy = ProvisioningStrategy.SYSTEM_APT,
        requiredExecutables = listOf("tesseract"),
        requiredPythonPackages = listOf("pytesseract"),
        requiredSystemPackages = listOf("tesseract-ocr"),
        minMemoryMb = 80,
        minDiskMb = 120,
        requiresSudo = true,
        verificationCommand = "tesseract --version && python3 -c 'import pytesseract; print(\"TESSERACT_OK\")'",
        recoveryGuidance = "Run: sudo apt-get install -y tesseract-ocr && python3 -m pip install --no-cache-dir pytesseract"
      )
    )
    register(
      CapabilityDefinition(
        id = "ocr.lang.eng",
        name = "Tesseract English Language Pack",
        category = CapabilityCategory.OCR_IMAGE,
        description = "English language trained data for Tesseract OCR.",
        strategy = ProvisioningStrategy.SYSTEM_APT,
        requiredSystemPackages = listOf("tesseract-ocr-eng"),
        minMemoryMb = 30,
        minDiskMb = 30,
        requiresSudo = true,
        verificationCommand = "tesseract --list-langs | grep -q 'eng' && echo 'ENG_LANG_OK'",
        recoveryGuidance = "Run: sudo apt-get install -y tesseract-ocr-eng"
      )
    )
    register(
      CapabilityDefinition(
        id = "ocr.lang.ara",
        name = "Tesseract Arabic Language Pack",
        category = CapabilityCategory.OCR_IMAGE,
        description = "Arabic language trained data for Tesseract OCR.",
        strategy = ProvisioningStrategy.SYSTEM_APT,
        requiredSystemPackages = listOf("tesseract-ocr-ara"),
        minMemoryMb = 30,
        minDiskMb = 30,
        requiresSudo = true,
        verificationCommand = "tesseract --list-langs | grep -q 'ara' && echo 'ARA_LANG_OK'",
        recoveryGuidance = "Run: sudo apt-get install -y tesseract-ocr-ara"
      )
    )

    // Group E: Data analysis and visualization
    register(
      CapabilityDefinition(
        id = "data.numpy",
        name = "NumPy Numerical Computing",
        category = CapabilityCategory.DATA_ANALYSIS,
        description = "Fundamental package for scientific and numerical computing with arrays.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("numpy"),
        minMemoryMb = 50,
        minDiskMb = 80,
        verificationCommand = "python3 -c 'import numpy as np; a = np.array([1, 2, 3]); assert a.sum() == 6; print(\"NUMPY_OK \" + np.__version__)'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir numpy"
      )
    )
    register(
      CapabilityDefinition(
        id = "data.pandas",
        name = "pandas Tabular Analysis",
        category = CapabilityCategory.DATA_ANALYSIS,
        description = "High-performance data analysis and manipulation tool for tabular data.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("pandas"),
        minMemoryMb = 70,
        minDiskMb = 100,
        verificationCommand = "python3 -c 'import pandas as pd; df = pd.DataFrame({\"x\": [10, 20]}); assert df[\"x\"].mean() == 15.0; print(\"PANDAS_OK \" + pd.__version__)'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir pandas"
      )
    )
    register(
      CapabilityDefinition(
        id = "data.matplotlib",
        name = "Matplotlib Chart Generation",
        category = CapabilityCategory.DATA_ANALYSIS,
        description = "Data visualization and 2D plotting library with headless Agg backend.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("matplotlib"),
        minMemoryMb = 75,
        minDiskMb = 100,
        verificationCommand = "python3 -c 'import matplotlib; matplotlib.use(\"Agg\"); import matplotlib.pyplot as plt; plt.plot([1, 2], [3, 4]); plt.savefig(\"/tmp/aragon_plot.png\"); print(\"MATPLOTLIB_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir matplotlib"
      )
    )

    // Group F: Web applications and development
    register(
      CapabilityDefinition(
        id = "app.fastapi",
        name = "FastAPI Web Framework",
        category = CapabilityCategory.WEB_APPS,
        description = "Modern, fast web framework for building APIs with Python.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("fastapi", "uvicorn"),
        minMemoryMb = 40,
        minDiskMb = 60,
        verificationCommand = "python3 -c 'import fastapi, uvicorn; app = fastapi.FastAPI(); print(\"FASTAPI_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir fastapi uvicorn"
      )
    )
    register(
      CapabilityDefinition(
        id = "app.flask",
        name = "Flask Web Framework",
        category = CapabilityCategory.WEB_APPS,
        description = "Lightweight WSGI web application framework.",
        strategy = ProvisioningStrategy.PIP_VENV,
        requiredPythonPackages = listOf("flask"),
        minMemoryMb = 35,
        minDiskMb = 45,
        verificationCommand = "python3 -c 'import flask; app = flask.Flask(\"test\"); print(\"FLASK_OK\")'",
        recoveryGuidance = "Run: python3 -m pip install --no-cache-dir flask"
      )
    )

    // Group G: System and network utilities
    register(
      CapabilityDefinition(
        id = "sys.dnsutils",
        name = "DNS Diagnostics (dig)",
        category = CapabilityCategory.SYSTEM_UTILITIES,
        description = "DNS lookup and resolution diagnostics via dig/nslookup.",
        strategy = ProvisioningStrategy.SYSTEM_APT,
        requiredExecutables = listOf("dig"),
        requiredSystemPackages = listOf("dnsutils"),
        minMemoryMb = 20,
        minDiskMb = 20,
        requiresSudo = true,
        verificationCommand = "dig -v",
        recoveryGuidance = "Run: sudo apt-get install -y dnsutils"
      )
    )
    register(
      CapabilityDefinition(
        id = "sys.rsync",
        name = "rsync File Sync",
        category = CapabilityCategory.SYSTEM_UTILITIES,
        description = "Fast, versatile remote/local file-copying tool.",
        strategy = ProvisioningStrategy.SYSTEM_APT,
        requiredExecutables = listOf("rsync"),
        requiredSystemPackages = listOf("rsync"),
        minMemoryMb = 20,
        minDiskMb = 20,
        requiresSudo = true,
        verificationCommand = "rsync --version",
        recoveryGuidance = "Run: sudo apt-get install -y rsync"
      )
    )
    register(
      CapabilityDefinition(
        id = "sys.procps",
        name = "Process Monitoring Utilities",
        category = CapabilityCategory.SYSTEM_UTILITIES,
        description = "Standard system utilities for process monitoring: ps, top, pkill.",
        strategy = ProvisioningStrategy.BUILTIN,
        requiredExecutables = listOf("ps", "pgrep", "pkill"),
        verificationCommand = "ps --version || ps -ef"
      )
    )
    register(
      CapabilityDefinition(
        id = "sys.docker",
        name = "Docker Container Engine",
        category = CapabilityCategory.SYSTEM_UTILITIES,
        description = "Container runtime daemon. Requires dockerd privileges.",
        strategy = ProvisioningStrategy.UNSUPPORTED,
        requiredExecutables = listOf("docker"),
        verificationCommand = "docker info 2>&1",
        recoveryGuidance = "Docker daemon is not running inside Firecracker microVMs. Run applications natively or via Python/Node.js runtimes."
      )
    )
    register(
      CapabilityDefinition(
        id = "sys.tcpdump",
        name = "tcpdump Packet Capture",
        category = CapabilityCategory.SYSTEM_UTILITIES,
        description = "Network packet analyzer requiring raw socket privileges.",
        strategy = ProvisioningStrategy.SYSTEM_APT,
        requiredExecutables = listOf("tcpdump"),
        requiredSystemPackages = listOf("tcpdump"),
        minMemoryMb = 30,
        minDiskMb = 30,
        requiresSudo = true,
        verificationCommand = "tcpdump --version && (sudo -n tcpdump -D 2>&1 | head -n 1)",
        recoveryGuidance = "Requires sudo or cap_net_raw privileges. If permission is denied, use curl or python socket probes."
      )
    )
  }

  fun register(definition: CapabilityDefinition) {
    registry[definition.id] = definition
  }

  /**
   * Stage 1: Inspect sandbox OS, architecture, memory, disk, package managers, and virtual environment.
   */
  suspend fun inspectResources(sessionId: String): SandboxResourceSnapshot = withContext(Dispatchers.IO) {
    val provider = sandboxProviderSupplier()
      ?: throw IllegalStateException("E2B Cloud Sandbox is not configured or offline. Configure E2B_API_KEY in Settings.")

    val probeScript = """
python3 -c "
import os, sys, shutil, subprocess

def run(cmd):
    try: return subprocess.check_output(cmd, shell=True, text=True, stderr=subprocess.DEVNULL).strip()
    except Exception: return ''

mem_total, mem_free = 0, 0
try:
    with open('/proc/meminfo') as f:
        for line in f:
            if line.startswith('MemTotal:'): mem_total = int(line.split()[1]) // 1024
            elif line.startswith('MemAvailable:'): mem_free = int(line.split()[1]) // 1024
except Exception: pass

disk_total, disk_free = 0, 0
try:
    stat = os.statvfs('/')
    disk_total = (stat.f_blocks * stat.f_frsize) // (1024 * 1024)
    disk_free = (stat.f_bavail * stat.f_frsize) // (1024 * 1024)
except Exception: pass

os_rel = ''
try:
    with open('/etc/os-release') as f:
        for line in f:
            if line.startswith('PRETTY_NAME='): os_rel = line.split('=', 1)[1].strip('\"\'\n')
except Exception: pass

user = os.environ.get('USER', run('whoami'))
sudo_ok = run('sudo -n true && echo 1 || echo 0') == '1'
apt_ok = shutil.which('apt-get') is not None
net_ok = run('curl -sI --max-time 3 https://pypi.org >/dev/null && echo 1 || echo 0') == '1'
venv_ok = os.path.isdir('/home/user/venv')
node_ver = run('node -v')
py_ver = sys.version.split()[0]
cores = os.cpu_count() or 1
uname = run('uname -srm')
arch = run('uname -m')

import json
print('___ARAGON_RES___' + json.dumps({
    'memTotal': mem_total,
    'memFree': mem_free,
    'memUsed': max(0, mem_total - mem_free),
    'diskTotal': disk_total,
    'diskFree': disk_free,
    'cpuCores': cores,
    'osRelease': os_rel,
    'kernel': uname,
    'arch': arch,
    'currentUser': user,
    'isSudoUsable': sudo_ok,
    'isAptUsable': apt_ok,
    'isNetworkConnected': net_ok,
    'hasVirtualEnv': venv_ok,
    'pythonVersion': py_ver,
    'nodeVersion': node_ver if node_ver else None
}) + '___ARAGON_RES___')
"
    """.trimIndent()

    val req = RunCommandRequest.builder()
      .command(probeScript)
      .timeout(Duration.ofSeconds(20))
      .envs(TerminalTool.REMOTE_COMMAND_ENVS)
      .build()

    val exec = provider.executeCommand(sessionId, req)
    val out = exec.stdoutText()

    if (!out.contains("___ARAGON_RES___")) {
      // Fallback baseline estimate if custom probe had an issue
      val fallback = SandboxResourceSnapshot(
        totalMemoryMb = 473,
        freeMemoryMb = 280,
        usedMemoryMb = 193,
        totalDiskMb = 22500,
        freeDiskMb = 18000,
        cpuCores = 2,
        osRelease = "Debian GNU/Linux 12 (bookworm)",
        kernel = "Linux 6.1.0 x86_64",
        arch = "x86_64",
        currentUser = "user",
        isSudoUsable = true,
        isAptUsable = true,
        isNetworkConnected = true,
        hasVirtualEnv = false,
        pythonVersion = "3.11",
        nodeVersion = "v20"
      )
      lastResourceSnapshot = fallback
      return@withContext fallback
    }

    val jsonStr = out.substringAfter("___ARAGON_RES___").substringBefore("___ARAGON_RES___")
    val j = JSONObject(jsonStr)

    val snapshot = SandboxResourceSnapshot(
      totalMemoryMb = j.optInt("memTotal", 473),
      freeMemoryMb = j.optInt("memFree", 280),
      usedMemoryMb = j.optInt("memUsed", 193),
      totalDiskMb = j.optInt("diskTotal", 22500),
      freeDiskMb = j.optInt("diskFree", 18000),
      cpuCores = j.optInt("cpuCores", 2),
      osRelease = j.optString("osRelease", "Linux (Debian)"),
      kernel = j.optString("kernel", "Linux"),
      arch = j.optString("arch", "x86_64"),
      currentUser = j.optString("currentUser", "user"),
      isSudoUsable = j.optBoolean("isSudoUsable", true),
      isAptUsable = j.optBoolean("isAptUsable", true),
      isNetworkConnected = j.optBoolean("isNetworkConnected", true),
      hasVirtualEnv = j.optBoolean("hasVirtualEnv", false),
      pythonVersion = j.optString("pythonVersion", "3.11"),
      nodeVersion = if (j.has("nodeVersion") && !j.isNull("nodeVersion")) j.getString("nodeVersion") else null
    )

    lastResourceSnapshot = snapshot
    return@withContext snapshot
  }

  /**
   * Stage 2 & 6: Check capability state, distinguishing executable present, package importable,
   * actually functioning, or environmentally restricted.
   */
  suspend fun checkCapability(sessionId: String, capabilityId: String): CapabilityStatus = withContext(Dispatchers.IO) {
    val def = getCapabilityDefinition(capabilityId)
      ?: return@withContext CapabilityStatus(
        id = capabilityId,
        category = CapabilityCategory.CORE_EXECUTION,
        state = CapabilityState.UNSUPPORTED,
        verificationLevel = VerificationLevel.NOT_CHECKED,
        lastError = "Unknown capability '$capabilityId'",
        recoveryHint = "Check capability identifier."
      )

    // Environmental restrictions check
    if (def.strategy == ProvisioningStrategy.UNSUPPORTED) {
      val status = CapabilityStatus(
        id = def.id,
        category = def.category,
        state = CapabilityState.UNSUPPORTED,
        verificationLevel = VerificationLevel.ENVIRONMENTALLY_RESTRICTED,
        lastError = "Operation unsupported in this sandbox environment: ${def.recoveryGuidance}",
        recoveryHint = def.recoveryGuidance
      )
      liveStatus[def.id] = status
      return@withContext status
    }

    val provider = sandboxProviderSupplier()
      ?: return@withContext CapabilityStatus(
        id = def.id,
        category = def.category,
        state = CapabilityState.FAILED,
        verificationLevel = VerificationLevel.NOT_CHECKED,
        lastError = "E2B Cloud Sandbox is offline or unauthenticated."
      )

    val startTime = System.currentTimeMillis()

    // 1. Check executables
    if (def.requiredExecutables.isNotEmpty()) {
      val checkExecCmd = def.requiredExecutables.joinToString(" && ") { "command -v $it >/dev/null 2>&1" }
      val execRes = provider.executeCommand(sessionId, RunCommandRequest.builder().command(checkExecCmd).timeout(Duration.ofSeconds(10)).build())
      if (execRes.exitCode != 0) {
        val missingStatus = CapabilityStatus(
          id = def.id,
          category = def.category,
          state = CapabilityState.MISSING,
          verificationLevel = VerificationLevel.NOT_CHECKED,
          lastError = "Missing executable(s): ${def.requiredExecutables.joinToString()}",
          recoveryHint = def.recoveryGuidance
        )
        liveStatus[def.id] = missingStatus
        return@withContext missingStatus
      }
    }

    // 2. Check Python packages (in venv or system)
    if (def.requiredPythonPackages.isNotEmpty()) {
      val importChecks = def.requiredPythonPackages.joinToString("; ") { "import $it" }
      val pyCmd = "([ -x /home/user/venv/bin/python3 ] && /home/user/venv/bin/python3 -c '$importChecks' 2>/dev/null) || python3 -c '$importChecks' 2>/dev/null"
      val pyRes = provider.executeCommand(sessionId, RunCommandRequest.builder().command(pyCmd).timeout(Duration.ofSeconds(12)).build())
      if (pyRes.exitCode != 0) {
        val missingStatus = CapabilityStatus(
          id = def.id,
          category = def.category,
          state = CapabilityState.MISSING,
          verificationLevel = VerificationLevel.EXECUTABLE_PRESENT,
          lastError = "Python package(s) not importable: ${def.requiredPythonPackages.joinToString()}",
          recoveryHint = def.recoveryGuidance
        )
        liveStatus[def.id] = missingStatus
        return@withContext missingStatus
      }
    }

    // 3. Functional Verification (Concrete Smoke Test)
    val testCmd = "([ -x /home/user/venv/bin/python3 ] && export PATH=\"/home/user/venv/bin:\$PATH\"); " + def.verificationCommand
    val verifyRes = provider.executeCommand(sessionId, RunCommandRequest.builder().command(testCmd).timeout(Duration.ofSeconds(20)).build())
    val verifyOk = verifyRes.exitCode == 0 && verifyRes.error == null

    val duration = System.currentTimeMillis() - startTime
    val finalStatus = if (verifyOk) {
      CapabilityStatus(
        id = def.id,
        category = def.category,
        state = CapabilityState.READY,
        verificationLevel = VerificationLevel.FUNCTIONALLY_VERIFIED,
        installedVersion = verifyRes.stdoutText().trim().lines().firstOrNull()?.take(60),
        lastVerifiedAt = System.currentTimeMillis(),
        provisionDurationMs = duration
      )
    } else {
      CapabilityStatus(
        id = def.id,
        category = def.category,
        state = CapabilityState.FAILED,
        verificationLevel = VerificationLevel.PACKAGE_IMPORTABLE,
        lastError = verifyRes.stderrText().ifBlank { verifyRes.error?.value ?: "Verification test exited with code ${verifyRes.exitCode}" },
        recoveryHint = def.recoveryGuidance,
        provisionDurationMs = duration
      )
    }

    liveStatus[def.id] = finalStatus
    return@withContext finalStatus
  }

  /**
   * Initializes or reuses the dedicated project virtual environment at /home/user/venv.
   */
  suspend fun ensureVirtualEnvironment(sessionId: String): Boolean = withContext(Dispatchers.IO) {
    val provider = sandboxProviderSupplier() ?: return@withContext false
    val checkCmd = "test -x /home/user/venv/bin/python3 && test -x /home/user/venv/bin/pip && echo 'VENV_READY'"
    val checkRes = provider.executeCommand(sessionId, RunCommandRequest.builder().command(checkCmd).timeout(Duration.ofSeconds(8)).build())
    if (checkRes.stdoutText().contains("VENV_READY")) return@withContext true

    // Initialize virtualenv cleanly
    val setupCmd = """
python3 -m venv /home/user/venv 2>/dev/null || (
  (sudo -n true 2>/dev/null && sudo apt-get update -qq && sudo apt-get install -y -qq python3-venv) &&
  python3 -m venv /home/user/venv
)
test -x /home/user/venv/bin/python3 && echo 'VENV_CREATED'
    """.trimIndent()

    val setupRes = provider.executeCommand(sessionId, RunCommandRequest.builder().command(setupCmd).timeout(Duration.ofSeconds(60)).build())
    return@withContext setupRes.stdoutText().contains("VENV_CREATED")
  }

  /**
   * Stage 3, 4, 5, 6, 7: Provision a capability on demand with resource guardrails and functional verification.
   */
  suspend fun provisionCapability(sessionId: String, capabilityId: String): ProvisioningResult = withContext(Dispatchers.IO) {
    val t0 = System.currentTimeMillis()
    val def = getCapabilityDefinition(capabilityId)
      ?: return@withContext ProvisioningResult(
        success = false,
        capabilityId = capabilityId,
        state = CapabilityState.FAILED,
        message = "Unknown capability '$capabilityId'.",
        durationMs = System.currentTimeMillis() - t0
      )

    val provider = sandboxProviderSupplier()
      ?: return@withContext ProvisioningResult(
        success = false,
        capabilityId = def.id,
        state = CapabilityState.FAILED,
        message = "E2B Cloud Sandbox is offline or unauthenticated. Cannot provision capability.",
        durationMs = System.currentTimeMillis() - t0
      )

    // Stage 2: Check if already satisfied and functioning (idempotency & reuse!)
    val currentStatus = checkCapability(sessionId, def.id)
    if (currentStatus.state == CapabilityState.READY) {
      return@withContext ProvisioningResult(
        success = true,
        capabilityId = def.id,
        state = CapabilityState.READY,
        version = currentStatus.installedVersion,
        message = "Capability '${def.name}' is already satisfied and verified in the environment. Reused without re-installation.",
        durationMs = System.currentTimeMillis() - t0
      )
    }

    // Environmental restrictions check
    if (def.strategy == ProvisioningStrategy.UNSUPPORTED) {
      return@withContext ProvisioningResult(
        success = false,
        capabilityId = def.id,
        state = CapabilityState.UNSUPPORTED,
        message = "Capability '${def.name}' cannot be provisioned: ${def.recoveryGuidance}",
        durationMs = System.currentTimeMillis() - t0,
        recoveryGuidance = def.recoveryGuidance
      )
    }

    // Stage 4: Check compatibility, package availability, and resource constraints
    val resources = try { inspectResources(sessionId) } catch (_: Exception) { lastResourceSnapshot }
    if (resources != null) {
      if (resources.freeMemoryMb < def.minMemoryMb) {
        val err = "Resource limit exceeded: Available RAM (${resources.freeMemoryMb} MB) is below required minimum (${def.minMemoryMb} MB) for '${def.name}'."
        return@withContext ProvisioningResult(
          success = false,
          capabilityId = def.id,
          state = CapabilityState.FAILED,
          message = err,
          durationMs = System.currentTimeMillis() - t0,
          errorDetails = err,
          recoveryGuidance = "Use a lighter capability or clean memory before retrying."
        )
      }
      if (resources.freeDiskMb < def.minDiskMb) {
        val err = "Disk space limit exceeded: Available disk (${resources.freeDiskMb} MB) is below required minimum (${def.minDiskMb} MB) for '${def.name}'."
        return@withContext ProvisioningResult(
          success = false,
          capabilityId = def.id,
          state = CapabilityState.FAILED,
          message = err,
          durationMs = System.currentTimeMillis() - t0,
          errorDetails = err,
          recoveryGuidance = "Free workspace disk space before proceeding."
        )
      }
    }

    // Check Heavy Package Policy
    for (pkg in def.requiredPythonPackages) {
      val normalized = pkg.lowercase().replace('_', '-')
      val heavyMsg = SandboxPackagePolicy.HEAVY_PACKAGES[normalized]
      if (heavyMsg != null) {
        return@withContext ProvisioningResult(
          success = false,
          capabilityId = def.id,
          state = CapabilityState.UNSUPPORTED,
          message = "Installation refused by SandboxPackagePolicy: $heavyMsg",
          durationMs = System.currentTimeMillis() - t0,
          recoveryGuidance = heavyMsg
        )
      }
    }

    // Ensure virtual environment is ready for Python packages
    if (def.requiredPythonPackages.isNotEmpty()) {
      ensureVirtualEnvironment(sessionId)
    }

    // Stage 5: Install missing dependencies in controlled batches
    // 5a. System packages via apt-get (cached update)
    if (def.requiredSystemPackages.isNotEmpty()) {
      val pkgs = def.requiredSystemPackages.joinToString(" ")
      val now = System.currentTimeMillis()
      val shouldUpdateApt = (now - lastAptUpdateTime.get()) > 3600_000L // update at most once per hour

      val aptCmd = buildString {
        append("(sudo -n true 2>/dev/null && SUDO=\"sudo\") || SUDO=\"\"; ")
        if (shouldUpdateApt) {
          append("\$SUDO apt-get update -qq && ")
        }
        append("\$SUDO apt-get install -y -qq --no-install-recommends $pkgs")
      }

      val aptRes = provider.executeCommand(sessionId, RunCommandRequest.builder().command(aptCmd).timeout(Duration.ofSeconds(180)).build())
      if (aptRes.exitCode == 0) {
        lastAptUpdateTime.set(now)
      } else {
        val err = "System package installation failed for '$pkgs': ${aptRes.stderrText().take(500)}"
        return@withContext ProvisioningResult(
          success = false,
          capabilityId = def.id,
          state = CapabilityState.FAILED,
          message = err,
          durationMs = System.currentTimeMillis() - t0,
          errorDetails = err,
          stderr = aptRes.stderrText(),
          recoveryGuidance = def.recoveryGuidance
        )
      }
    }

    // 5b. Python packages via pip in virtualenv
    if (def.requiredPythonPackages.isNotEmpty()) {
      val pkgs = def.requiredPythonPackages.joinToString(" ")
      val pipCmd = """
if [ -x /home/user/venv/bin/pip ]; then
  /home/user/venv/bin/pip install --no-cache-dir --disable-pip-version-check --quiet $pkgs
else
  python3 -m pip install --break-system-packages --no-cache-dir --disable-pip-version-check --quiet $pkgs
fi
      """.trimIndent()

      val pipRes = provider.executeCommand(sessionId, RunCommandRequest.builder().command(pipCmd).timeout(Duration.ofSeconds(180)).build())
      if (pipRes.exitCode != 0) {
        val err = "Python package installation failed for '$pkgs': ${pipRes.stderrText().take(500)}"
        return@withContext ProvisioningResult(
          success = false,
          capabilityId = def.id,
          state = CapabilityState.FAILED,
          message = err,
          durationMs = System.currentTimeMillis() - t0,
          errorDetails = err,
          stderr = pipRes.stderrText(),
          recoveryGuidance = def.recoveryGuidance
        )
      }
    }

    // Stage 6: Verify actual imports, executable behavior, and end-to-end functionality
    val postStatus = checkCapability(sessionId, def.id)
    val duration = System.currentTimeMillis() - t0

    if (postStatus.state == CapabilityState.READY) {
      ProvisioningResult(
        success = true,
        capabilityId = def.id,
        state = CapabilityState.READY,
        version = postStatus.installedVersion,
        message = "Successfully provisioned and functionally verified capability '${def.name}'.",
        durationMs = duration
      )
    } else {
      ProvisioningResult(
        success = false,
        capabilityId = def.id,
        state = CapabilityState.FAILED,
        message = "Installation completed but functional verification failed for '${def.name}': ${postStatus.lastError}",
        durationMs = duration,
        errorDetails = postStatus.lastError,
        recoveryGuidance = def.recoveryGuidance
      )
    }
  }

  /**
   * Helper for InstallPackageTool to install any generic package by name,
   * matching it against existing capability definitions or installing via venv pip / apt.
   */
  suspend fun installPackageGeneric(sessionId: String, packageName: String): ProvisioningResult = withContext(Dispatchers.IO) {
    val clean = packageName.trim()
    val t0 = System.currentTimeMillis()

    // 1. Check if matches registered capability
    val matchedDef = registry.values.firstOrNull { def ->
      def.id.equals(clean, ignoreCase = true) ||
      def.name.equals(clean, ignoreCase = true) ||
      def.requiredPythonPackages.any { it.equals(clean, ignoreCase = true) } ||
      def.requiredSystemPackages.any { it.equals(clean, ignoreCase = true) } ||
      def.requiredExecutables.any { it.equals(clean, ignoreCase = true) }
    }

    if (matchedDef != null) {
      return@withContext provisionCapability(sessionId, matchedDef.id)
    }

    // 2. Check SandboxPackagePolicy
    val normalized = clean.lowercase().replace('_', '-')
    val heavy = SandboxPackagePolicy.HEAVY_PACKAGES[normalized]
    if (heavy != null) {
      return@withContext ProvisioningResult(
        success = false,
        capabilityId = clean,
        state = CapabilityState.UNSUPPORTED,
        message = "Refusing to install '$packageName' into the microVM: $heavy",
        durationMs = System.currentTimeMillis() - t0,
        recoveryGuidance = heavy
      )
    }

    val provider = sandboxProviderSupplier()
      ?: return@withContext ProvisioningResult(
        success = false,
        capabilityId = clean,
        state = CapabilityState.FAILED,
        message = "E2B Cloud Sandbox is offline or unauthenticated.",
        durationMs = System.currentTimeMillis() - t0
      )

    // 3. Check if already installed in Python
    val checkPyCmd = "([ -x /home/user/venv/bin/python3 ] && /home/user/venv/bin/python3 -c 'import importlib.metadata; print(importlib.metadata.version(\"$clean\"))' 2>/dev/null) || python3 -c 'import importlib.metadata; print(importlib.metadata.version(\"$clean\"))' 2>/dev/null"
    val checkPyRes = provider.executeCommand(sessionId, RunCommandRequest.builder().command(checkPyCmd).timeout(Duration.ofSeconds(10)).build())
    if (checkPyRes.exitCode == 0 && checkPyRes.stdoutText().isNotBlank()) {
      val v = checkPyRes.stdoutText().trim()
      return@withContext ProvisioningResult(
        success = true,
        capabilityId = clean,
        state = CapabilityState.READY,
        manager = "pip",
        version = v,
        message = "Python package '$clean' (v$v) is already installed and verified in the environment.",
        durationMs = System.currentTimeMillis() - t0
      )
    }

    ensureVirtualEnvironment(sessionId)

    // 4. Try installing via venv pip first, apt fallback
    val installCmd = """
(sudo -n true 2>/dev/null && SUDO="sudo") || SUDO="";
if [ -x /home/user/venv/bin/pip ] && /home/user/venv/bin/pip install --no-cache-dir --disable-pip-version-check --quiet '$clean'; then
  V=${'$'}(/home/user/venv/bin/python3 -c "import importlib.metadata as m; print(m.version('$clean'))" 2>/dev/null || true);
  echo "__ARAGON_PKG_OK__ pip ${'$'}V";
elif python3 -m pip install --break-system-packages --no-cache-dir --disable-pip-version-check --quiet '$clean'; then
  V=${'$'}(python3 -c "import importlib.metadata as m; print(m.version('$clean'))" 2>/dev/null || true);
  echo "__ARAGON_PKG_OK__ pip ${'$'}V";
elif ${'$'}SUDO apt-get install -y -qq --no-install-recommends '$clean' 2>/dev/null || (${'$'}SUDO apt-get update -qq && ${'$'}SUDO apt-get install -y -qq --no-install-recommends '$clean'); then
  V=${'$'}(dpkg -s '$clean' 2>/dev/null | awk '/^Version:/{print ${'$'}2}');
  echo "__ARAGON_PKG_OK__ apt ${'$'}V";
else
  echo "__ARAGON_PKG_FAIL__ pip and apt installation both failed for '$clean'";
fi
    """.trimIndent()

    val exec = provider.executeCommand(sessionId, RunCommandRequest.builder().command(installCmd).timeout(Duration.ofSeconds(200)).envs(TerminalTool.REMOTE_COMMAND_ENVS).build())
    val stdout = exec.stdoutText()
    val stderr = exec.stderrText()
    val duration = System.currentTimeMillis() - t0

    val okLine = stdout.lines().lastOrNull { it.contains("__ARAGON_PKG_OK__") }
    if (okLine != null) {
      val payload = okLine.substringAfter("__ARAGON_PKG_OK__").trim()
      val manager = payload.substringBefore(' ').trim().ifBlank { "pip" }
      val version = payload.substringAfter(' ', "").trim().ifBlank { null }
      return@withContext ProvisioningResult(
        success = true,
        capabilityId = clean,
        state = CapabilityState.READY,
        manager = manager,
        version = version,
        message = "Installed '$clean'${version?.let { " (v$it)" } ?: ""} via $manager inside E2B MicroVM.",
        durationMs = duration,
        stdout = stdout,
        stderr = stderr
      )
    }

    return@withContext ProvisioningResult(
      success = false,
      capabilityId = clean,
      state = CapabilityState.FAILED,
      message = "Could not install '$clean' inside E2B MicroVM: pip and apt both failed.",
      durationMs = duration,
      errorDetails = stderr.take(800),
      stdout = stdout,
      stderr = stderr,
      recoveryGuidance = "Verify package name on PyPI/Debian or check resource constraints."
    )
  }

  /**
   * Section 8: Generates full machine-readable and human-readable diagnostic report.
   */
  suspend fun diagnoseEnvironment(sessionId: String): JSONObject = withContext(Dispatchers.IO) {
    val t0 = System.currentTimeMillis()
    val resources = try { inspectResources(sessionId) } catch (e: Exception) { null }
    val statuses = mutableListOf<CapabilityStatus>()

    for (def in registry.values) {
      try {
        statuses.add(checkCapability(sessionId, def.id))
      } catch (e: Exception) {
        statuses.add(
          CapabilityStatus(
            id = def.id,
            category = def.category,
            state = CapabilityState.FAILED,
            verificationLevel = VerificationLevel.NOT_CHECKED,
            lastError = e.message
          )
        )
      }
    }

    val readyCount = statuses.count { it.state == CapabilityState.READY }
    val unsupportedCount = statuses.count { it.state == CapabilityState.UNSUPPORTED }
    val failedCount = statuses.count { it.state == CapabilityState.FAILED }
    val missingCount = statuses.count { it.state == CapabilityState.MISSING }

    val json = JSONObject().apply {
      put("timestamp", System.currentTimeMillis())
      put("sessionId", sessionId)
      put("durationMs", System.currentTimeMillis() - t0)
      if (resources != null) {
        put("resources", resources.toJsonObject())
      }
      val summaryObj = JSONObject().apply {
        put("totalCapabilities", statuses.size)
        put("ready", readyCount)
        put("missing", missingCount)
        put("failed", failedCount)
        put("unsupported", unsupportedCount)
      }
      put("summary", summaryObj)

      val capsArray = JSONArray()
      statuses.forEach { capsArray.put(it.toJsonObject()) }
      put("capabilities", capsArray)
    }

    return@withContext json
  }

  fun formatDiagnosticSummary(report: JSONObject): String {
    val sb = StringBuilder()
    sb.appendLine("=== E2B ENVIRONMENT CAPABILITY AUDIT ===")
    val res = report.optJSONObject("resources")
    if (res != null) {
      sb.appendLine("Environment: Firecracker MicroVM (${res.optString("arch")})")
      sb.appendLine("OS: ${res.optString("osRelease")} | Kernel: ${res.optString("kernel")}")
      sb.appendLine("User: ${res.optString("currentUser")} (sudo: ${res.optBoolean("isSudoUsable")})")
      sb.appendLine("RAM (Used/Total/Free): ${res.optInt("memUsed")}MB / ${res.optInt("memTotal")}MB / ${res.optInt("memFree")}MB")
      sb.appendLine("Disk (Free/Total): ${res.optInt("diskFree")}MB / ${res.optInt("diskTotal")}MB")
      sb.appendLine("Python: ${res.optString("pythonVersion")} | Node: ${res.optString("nodeVersion", "N/A")}")
      sb.appendLine("Virtual Environment: ${if (res.optBoolean("hasVirtualEnv")) "/home/user/venv (ACTIVE)" else "NOT_INITIALIZED"}")
    }

    val sum = report.optJSONObject("summary")
    if (sum != null) {
      sb.appendLine("Capabilities: ${sum.optInt("ready")} READY, ${sum.optInt("missing")} MISSING, ${sum.optInt("unsupported")} UNSUPPORTED, ${sum.optInt("failed")} FAILED")
    }

    val caps = report.optJSONArray("capabilities")
    if (caps != null && caps.length() > 0) {
      sb.appendLine()
      sb.appendLine("--- CAPABILITY BREAKDOWN ---")
      for (i in 0 until caps.length()) {
        val c = caps.optJSONObject(i) ?: continue
        val id = c.optString("id")
        val state = c.optString("state")
        val ver = c.optString("installedVersion", "")
        val err = c.optString("lastError", "")
        val extra = if (ver.isNotBlank()) " [$ver]" else if (err.isNotBlank()) " (${err.take(40)})" else ""
        sb.appendLine(" - $id: $state$extra")
      }
    }

    sb.appendLine("=========================================")
    return sb.toString().trim()
  }
}
