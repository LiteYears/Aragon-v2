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
package com.alibaba.opensandbox.sandbox.domain.services

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.CommandLogs
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.CommandStatus
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunInSessionRequest
import java.time.Duration

/**
 * Command execution operations for OpenSandbox environments.
 */
interface Commands {
  /**
   * Executes a shell command in the sandbox environment.
   */
  fun run(request: RunCommandRequest): Execution

  /**
   * Convenience overload for simple command execution.
   */
  fun run(command: String): Execution {
    return run(RunCommandRequest.builder().command(command).build())
  }

  /**
   * Persists an environment variable for future commands and sessions (EXECD_ENVS).
   */
  fun setEnv(key: String, value: String)

  /**
   * Interrupts and terminates a running command execution.
   */
  fun interrupt(executionId: String)

  /**
   * Get the current running status for a command.
   */
  fun getCommandStatus(executionId: String): CommandStatus

  /**
   * Get background command logs (non-streamed).
   */
  fun getBackgroundCommandLogs(executionId: String, cursor: Long? = null): CommandLogs

  /**
   * Creates a new bash session with optional working directory.
   */
  fun createSession(workingDirectory: String? = null): String

  /**
   * Runs a shell command in an existing bash session.
   */
  fun runInSession(sessionId: String, request: RunInSessionRequest): Execution

  /**
   * Convenience overload for running a command in a session.
   */
  fun runInSession(
    sessionId: String,
    command: String,
    workingDirectory: String? = null,
    timeout: Duration? = null,
  ): Execution {
    val builder = RunInSessionRequest.builder()
      .command(command)
      .workingDirectory(workingDirectory)
    if (timeout != null) {
      builder.timeout(timeout)
    }
    return runInSession(sessionId, builder.build())
  }

  /**
   * Deletes a bash session and releases resources.
   */
  fun deleteSession(sessionId: String)
}
