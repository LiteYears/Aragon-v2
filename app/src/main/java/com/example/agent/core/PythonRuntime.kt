package com.example.agent.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Authentic Python 3 & Pip Execution Runtime for the Android Agent Sandbox.
 * Executes genuine system or downloaded Python 3 processes with real stdout, stderr,
 * and exit codes. Never fakes command execution or simulates mock packages.
 */
object PythonRuntime {

  data class Result(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
  )

  /**
   * Locates a genuine Python 3 or Python binary in the environment:
   * 1. workspace/bin/python3 or workspace/bin/python (user-installed or downloaded)
   * 2. /system/bin/python3, /system/bin/python, /system/xbin/python3
   * 3. Current system PATH
   */
  fun findPythonBinary(workspaceDir: File): File? {
    val candidates = listOf(
      File(workspaceDir, "bin/python3"),
      File(workspaceDir, "bin/python"),
      File("/system/bin/python3"),
      File("/system/bin/python"),
      File("/system/xbin/python3"),
      File("/system/xbin/python"),
      File("/data/local/tmp/python3"),
      File("/data/local/tmp/python")
    )

    for (c in candidates) {
      if (c.exists() && c.canExecute()) return c
    }

    // Try finding via 'which' or system check
    try {
      val pb = ProcessBuilder("which", "python3")
      val p = pb.start()
      val path = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
      p.waitFor(2, TimeUnit.SECONDS)
      if (!path.isNullOrBlank()) {
        val f = File(path)
        if (f.exists() && f.canExecute()) return f
      }
    } catch (_: Exception) {}

    try {
      val pb = ProcessBuilder("which", "python")
      val p = pb.start()
      val path = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
      p.waitFor(2, TimeUnit.SECONDS)
      if (!path.isNullOrBlank()) {
        val f = File(path)
        if (f.exists() && f.canExecute()) return f
      }
    } catch (_: Exception) {}

    return null
  }

  /**
   * Locates a genuine Pip binary in the environment:
   * 1. workspace/bin/pip3 or workspace/bin/pip
   * 2. /system/bin/pip3, /system/bin/pip
   */
  fun findPipBinary(workspaceDir: File): File? {
    val candidates = listOf(
      File(workspaceDir, "bin/pip3"),
      File(workspaceDir, "bin/pip"),
      File("/system/bin/pip3"),
      File("/system/bin/pip"),
      File("/data/local/tmp/pip3")
    )
    for (c in candidates) {
      if (c.exists() && c.canExecute()) return c
    }
    return null
  }

  /**
   * Determines if a command targets Python or Pip.
   */
  fun matches(command: String): Boolean {
    val trimmed = command.trim()
    return trimmed.startsWith("python3") ||
      trimmed.startsWith("python ") ||
      trimmed == "python" ||
      trimmed.startsWith("pip") ||
      trimmed.startsWith("pip3") ||
      trimmed.contains(" python3 ") ||
      trimmed.contains(" python ") ||
      trimmed.contains(" pip ") ||
      trimmed.contains(" pip3 ")
  }

  /**
   * Executes a Python or Pip command inside the workspace directory.
   * Runs the real binary if available, or returns a genuine exit code 127
   * with diagnostic instructions enforcing installation.
   */
  fun execute(command: String, workspaceDir: File): Result {
    val trimmed = command.trim()

    // Handle compound commands (&&, ;, ||) via real shell process
    if (trimmed.contains("&&") || trimmed.contains(";") || trimmed.contains("||")) {
      return runRealProcess(listOf("sh", "-c", trimmed), workspaceDir, timeoutSeconds = 60)
    }

    if (trimmed.startsWith("pip") || trimmed.startsWith("pip3")) {
      return executePip(trimmed, workspaceDir)
    }

    if (trimmed.startsWith("python3") || trimmed.startsWith("python")) {
      return executePython(trimmed, workspaceDir)
    }

    return runRealProcess(listOf("sh", "-c", trimmed), workspaceDir, timeoutSeconds = 30)
  }

  private fun executePip(command: String, workspaceDir: File): Result {
    val pythonBin = findPythonBinary(workspaceDir)
    val pipBin = findPipBinary(workspaceDir)

    return when {
      pipBin != null -> {
        val args = command.split("\\s+".toRegex()).drop(1)
        runRealProcess(listOf(pipBin.absolutePath) + args, workspaceDir, timeoutSeconds = 120)
      }
      pythonBin != null -> {
        val args = command.split("\\s+".toRegex()).drop(1)
        runRealProcess(listOf(pythonBin.absolutePath, "-m", "pip") + args, workspaceDir, timeoutSeconds = 120)
      }
      else -> {
        Result(
          stdout = "",
          stderr = "sh: pip: command not found (exit code 127). The Pip package manager is not installed in the sandbox PATH.\n" +
            "Please install or download Python/Pip into workspace/bin/ (e.g. using download_file), " +
            "or accomplish your task using native tools: 'write_file', 'edit_file', 'http_request', 'json_processor', 'csv_processor', 'create_docx'.",
          exitCode = 127
        )
      }
    }
  }

  private fun executePython(command: String, workspaceDir: File): Result {
    val pythonBin = findPythonBinary(workspaceDir)
    if (pythonBin == null) {
      return Result(
        stdout = "",
        stderr = "sh: python3: command not found (exit code 127). The Python 3 interpreter is not installed in the sandbox PATH.\n" +
          "To execute Python scripts, download/install the binary into workspace/bin/python3, " +
          "or accomplish the objective directly using available native tools: 'write_file', 'edit_file', 'json_processor', 'csv_processor', 'create_docx'.",
        exitCode = 127
      )
    }

    // Run real python process
    val parts = command.split("\\s+".toRegex()).drop(1)
    return runRealProcess(listOf(pythonBin.absolutePath) + parts, workspaceDir, timeoutSeconds = 60)
  }

  /**
   * Executes Python code within the workspace by writing a temporary script
   * and running it through the genuine Python 3 interpreter.
   */
  fun runPythonCode(code: String, workspaceDir: File, args: List<String>): Result {
    val pythonBin = findPythonBinary(workspaceDir)
    if (pythonBin == null) {
      return Result(
        stdout = "",
        stderr = "python3: command not found (exit code 127). No Python 3 interpreter found in sandbox PATH.\n" +
          "To run Python code, install or download python3 to workspace/bin/python3, or use native file/data tools: " +
          "'write_file', 'create_docx', 'json_processor', 'csv_processor'.",
        exitCode = 127
      )
    }

    val scriptFile = File(workspaceDir, ".tmp_exec_${System.currentTimeMillis()}.py")
    return try {
      scriptFile.writeText(code)
      val cmdList = listOf(pythonBin.absolutePath, scriptFile.name) + args
      runRealProcess(cmdList, workspaceDir, timeoutSeconds = 60)
    } finally {
      try { scriptFile.delete() } catch (_: Exception) {}
    }
  }

  /**
   * Executes a real OS process via ProcessBuilder with proper sandbox environment configuration.
   */
  private fun runRealProcess(
    command: List<String>,
    workspaceDir: File,
    timeoutSeconds: Long = 30L
  ): Result {
    val stdoutSb = StringBuilder()
    val stderrSb = StringBuilder()
    val lock = Any()

    return try {
      workspaceDir.mkdirs()
      val binDir = File(workspaceDir, "bin").apply { mkdirs() }
      val tmpDir = File(workspaceDir, "tmp").apply { mkdirs() }
      val libDir = File(workspaceDir, "lib/python").apply { mkdirs() }

      val pb = ProcessBuilder(command)
      pb.directory(workspaceDir)
      val env = pb.environment()
      val existingPath = env["PATH"] ?: "/system/bin:/system/xbin"
      env["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin:$existingPath"
      env["HOME"] = workspaceDir.absolutePath
      env["PWD"] = workspaceDir.absolutePath
      env["WORKSPACE"] = workspaceDir.absolutePath
      env["TMPDIR"] = tmpDir.absolutePath
      env["PYTHONPATH"] = "${libDir.absolutePath}:${workspaceDir.absolutePath}"

      val p = pb.start()

      val stdoutThread = Thread {
        try {
          BufferedReader(InputStreamReader(p.inputStream)).use { r ->
            var l: String?
            while (r.readLine().also { l = it } != null) {
              synchronized(lock) {
                if (stdoutSb.length < 32000) stdoutSb.appendLine(l)
              }
            }
          }
        } catch (_: Exception) {}
      }

      val stderrThread = Thread {
        try {
          BufferedReader(InputStreamReader(p.errorStream)).use { r ->
            var l: String?
            while (r.readLine().also { l = it } != null) {
              synchronized(lock) {
                if (stderrSb.length < 32000) stderrSb.appendLine(l)
              }
            }
          }
        } catch (_: Exception) {}
      }

      stdoutThread.start()
      stderrThread.start()

      val completed = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
      stdoutThread.join(800)
      stderrThread.join(800)

      if (!completed) {
        p.destroyForcibly()
        Result(
          stdout = synchronized(lock) { stdoutSb.toString().trim() },
          stderr = "Execution timed out after ${timeoutSeconds}s",
          exitCode = -1
        )
      } else {
        val exitCode = p.exitValue()
        val (out, err) = synchronized(lock) {
          Pair(stdoutSb.toString().trim(), stderrSb.toString().trim())
        }
        Result(stdout = out, stderr = err, exitCode = exitCode)
      }
    } catch (e: Exception) {
      Result(
        stdout = "",
        stderr = "Process execution failed: ${e.message}",
        exitCode = 1
      )
    }
  }
}
