/*
 * Copyright 2025 The OpenSandbox Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.opensandbox.sandbox.domain.models.execd.executions

/**
 * Output message chunk with text and timestamp.
 */
data class OutputMessage(
  val text: String,
  val timestamp: Long = System.currentTimeMillis()
)

/**
 * Container for execution output logs.
 */
class ExecutionLogs(
  val stdout: MutableList<OutputMessage> = mutableListOf(),
  val stderr: MutableList<OutputMessage> = mutableListOf()
) {
  fun stdoutText(): String = stdout.joinToString("") { it.text }
  fun stderrText(): String = stderr.joinToString("") { it.text }
}

/**
 * Result data produced by code or command execution.
 */
data class ExecutionResult(
  val name: String,
  val result: Any?
)

/**
 * Error data if the execution failed.
 */
data class ExecutionError(
  val name: String? = null,
  val value: String? = null,
  val traceback: List<String> = emptyList()
)

/**
 * Completion metadata for the execution.
 */
data class ExecutionComplete(
  val executionTimeInMillis: Long? = null
)

/**
 * Event handlers for streamed execution events.
 */
interface ExecutionHandlers {
  fun onStdout(message: OutputMessage) {}
  fun onStderr(message: OutputMessage) {}
  fun onError(error: ExecutionError) {}
  fun onResult(result: ExecutionResult) {}
  fun onComplete(complete: ExecutionComplete) {}
}

/**
 * Represents a complete code or command execution session.
 */
class Execution(
  var id: String? = null,
  var executionCount: Long? = null,
  val result: MutableList<ExecutionResult> = mutableListOf(),
  var error: ExecutionError? = null,
  var complete: ExecutionComplete? = null,
  var exitCode: Int? = null,
  val logs: ExecutionLogs = ExecutionLogs(),
) {
  fun addResult(result: ExecutionResult) {
    this.result.add(result)
  }

  fun stdoutText(): String = logs.stdoutText()
  fun stderrText(): String = logs.stderrText()
}
