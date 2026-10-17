package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Description of tool availability in the capability manifest.
 */
data class ToolCapability(
  val name: String,
  val available: Boolean,
  val category: String,
  val description: String
)

/**
 * Description of shell command executable availability in the capability manifest.
 */
data class CommandCapability(
  val available: Boolean,
  val path: String? = null,
  val version: String? = null
)

/**
 * Authoritative sandbox runtime capability descriptor.
 * Tells the LLM and the app which sandbox is executing code,
 * its hardware boundary, isolation features, and workflow guidance.
 */
data class SandboxCapability(
  val mode: SandboxMode,
  val name: String,
  val isRemote: Boolean,
  val isE2B: Boolean,
  val environment: String,
  val features: List<String>,
  val executionGuidance: String
)

/**
 * Machine-readable runtime capability manifest reflecting authoritative ground truth.
 * Injected at session initialization into LLM prompt so the model never has to
 * guess or repeatedly run 'which' / 'command -v'.
 */
data class CapabilityManifest(
  val tools: List<ToolCapability>,
  val commands: Map<String, CommandCapability>,
  val generatedAt: Long = System.currentTimeMillis(),
  val systemArchitecture: String = "x86_64",
  val sandbox: SandboxCapability = SandboxCapability(
    mode = SandboxMode.E2B_CLOUD,
    name = "E2B Cloud Sandbox (base)",
    isRemote = true,
    isE2B = true,
    environment = "E2B Cloud Firecracker MicroVM (Hardware-Isolated Linux)",
    features = listOf(
      "cloud_firecracker_microvm",
      "full_linux_x86_64",
      "outbound_internet_access",
      "python3_and_pip_runtime",
      "node_runtime",
      "bash_userland",
      "passwordless_sudo_non_root_user",
      "unrestricted_execution"
    ),
    executionGuidance = "Running inside a dedicated E2B Firecracker Cloud MicroVM (/home/user) as non-root user 'user' with passwordless sudo. Full Linux CLI, Python 3, pip, apt-get, and outbound network access are natively available. Python packages install inside the microVM via pip (cache auto-disabled); system packages via 'sudo apt-get install'. Avoid multi-GB ML stacks (torch/tensorflow/easyocr)."
  )
) {
  /**
   * Generates compact JSON string conforming to the specification format:
   * {
   *   "sandbox": { "mode": "E2B_CLOUD", "name": "...", "features": [...] },
   *   "systemArchitecture": "x86_64",
   *   "tools": [ { "name": "terminal", "available": true }, ... ],
   *   "commands": { "ls": { "available": true, "path": "..." }, "python3": { "available": false } }
   * }
   */
  fun toJsonString(): String {
    val root = JSONObject()

    // 1. Authoritative Sandbox Runtime Info
    val sbxObj = JSONObject().apply {
      put("mode", sandbox.mode.name)
      put("name", sandbox.name)
      put("isRemote", sandbox.isRemote)
      put("isE2B", sandbox.isE2B)
      put("environment", sandbox.environment)
      put("features", JSONArray(sandbox.features))
      put("guidance", sandbox.executionGuidance)
    }
    root.put("sandbox", sbxObj)

    val topology = EnvironmentTopology.createDefault()
    val envsArray = JSONArray()
    topology.environments.forEach { envsArray.put(it.toJsonObject()) }
    root.put("environments", envsArray)

    root.put("systemArchitecture", systemArchitecture)

    val toolsArray = JSONArray()
    for (t in tools) {
      val tObj = JSONObject()
      tObj.put("name", t.name)
      tObj.put("available", t.available)
      toolsArray.put(tObj)
    }
    root.put("tools", toolsArray)

    val availableCommandsList = JSONArray()
    val commandsObj = JSONObject()
    var availableCount = 0

    for ((name, cap) in commands) {
      val cObj = JSONObject()
      cObj.put("available", cap.available)
      if (cap.available) {
        availableCount++
        availableCommandsList.put(name)
        if (cap.path != null) {
          cObj.put("path", cap.path)
        }
        if (!cap.version.isNullOrBlank()) {
          cObj.put("version", cap.version)
        }
      }
      commandsObj.put(name, cObj)
    }
    root.put("commands", commandsObj)
    root.put("availableCommands", availableCommandsList)

    val summaryObj = JSONObject().apply {
      put("availableCommandsCount", availableCount)
      put("totalProbedCommands", commands.size)
      put("sandboxMode", sandbox.mode.name)
    }
    root.put("summary", summaryObj)

    val pkgSupport = JSONObject().apply {
      put("supported", true)
      put("tool", "install_package")
      put("environment", "e2b_cloud")
      put("autoResolvable", JSONArray(listOf("pip packages (installed inside microVM)", "apt packages (via sudo)", "tesseract-ocr", "pytesseract", "python-docx", "curl", "python3", "git", "jq", "wget", "ripgrep", "unzip")))
      put("guidance", "In the E2B Cloud MicroVM, installations happen INSIDE the microVM. Python modules: install_package(name: '...') or 'python3 -m pip install --no-cache-dir <pkg>' (no-cache is auto-injected to prevent MemoryError). System packages: 'sudo apt-get update && sudo apt-get install -y <pkg>' — commands run as non-root 'user', so sudo IS available and REQUIRED. Never pip-install multi-GB ML stacks (torch, tensorflow, easyocr, paddleocr); for OCR use 'sudo apt-get install -y tesseract-ocr tesseract-ocr-eng' + 'python3 -m pip install --no-cache-dir pytesseract pillow'.")
    }
    root.put("packageInstallation", pkgSupport)

    return root.toString(2)
  }

  companion object {
    /**
     * Builds the capability manifest directly from the actual runtime state.
     * Evaluates registered tools from [ToolRegistry] and authoritative sandbox
     * backend characteristics from [SandboxProvider]. Commands are declared from
     * the E2B Cloud MicroVM base image (no local device probing).
     */
    fun build(
      registry: ToolRegistry,
      sandboxProvider: SandboxProvider? = null
    ): CapabilityManifest {
      val toolsList = registry.getAllTools().map { tool ->
        ToolCapability(
          name = tool.name,
          available = tool.isAvailable(),
          category = tool.category.name,
          description = tool.description
        )
      }

      // E2B Cloud MicroVM standard Linux userland binaries
      val standardLinuxTools = listOf(
        "python3", "python", "pip", "pip3", "bash", "sh", "curl", "wget", "git",
        "node", "npm", "cat", "ls", "grep", "find", "mkdir", "rm", "cp", "mv",
        "echo", "touch", "head", "tail", "tar", "gzip", "zip", "unzip", "which",
        "env", "sed", "awk", "ps", "df", "free", "uname", "whoami", "uptime", "date", "chmod",
        "apt", "apt-get", "apt-cache", "dpkg", "sudo"
      )
      val commandsMap = standardLinuxTools.associateWith { cmd ->
        CommandCapability(
          available = true,
          path = "/usr/bin/$cmd",
          version = "cloud-microvm"
        )
      }

      val sandboxCap = SandboxCapability(
        mode = SandboxMode.E2B_CLOUD,
        name = sandboxProvider?.name ?: "E2B Cloud Sandbox (base)",
        isRemote = true,
        isE2B = true,
        environment = "E2B Cloud Firecracker MicroVM (Hardware-Isolated Linux)",
        features = listOf(
          "cloud_firecracker_microvm",
          "full_linux_x86_64",
          "outbound_internet_access",
          "python3_and_pip_runtime",
          "node_runtime",
          "bash_userland",
          "passwordless_sudo_non_root_user",
          "unrestricted_execution"
        ),
        executionGuidance = "Running inside a dedicated E2B Firecracker Cloud MicroVM (/home/user) as non-root user 'user' with passwordless sudo. Full Linux CLI, Python 3, pip, apt-get, and outbound network access are natively available. Python packages install inside the microVM via pip (cache auto-disabled); system packages via 'sudo apt-get install'. Avoid multi-GB ML stacks (torch/tensorflow/easyocr)."
      )

      return CapabilityManifest(
        tools = toolsList,
        commands = commandsMap,
        sandbox = sandboxCap
      )
    }
  }
}
