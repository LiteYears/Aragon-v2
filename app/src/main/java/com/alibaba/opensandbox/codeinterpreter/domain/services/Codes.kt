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
package com.alibaba.opensandbox.codeinterpreter.domain.services

import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.CodeContext
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.RunCodeRequest
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.SupportedLanguage
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionHandlers

/**
 * Code execution operations for multi-language code interpretation in OpenSandbox.
 */
interface Codes {
  fun getContext(id: String): CodeContext
  fun listContexts(language: String): List<CodeContext>
  fun createContext(language: String): CodeContext
  fun deleteContext(id: String)
  fun deleteContexts(language: String)

  fun run(request: RunCodeRequest): Execution

  fun run(code: String, context: CodeContext, handlers: ExecutionHandlers): Execution {
    return run(RunCodeRequest.builder().code(code).context(context).handlers(handlers).build())
  }

  fun run(code: String, context: CodeContext): Execution {
    return run(RunCodeRequest.builder().code(code).context(context).build())
  }

  fun run(code: String, language: String = SupportedLanguage.PYTHON): Execution {
    return run(RunCodeRequest.builder().code(code).context(CodeContext.builder().language(language).build()).build())
  }

  fun interrupt(executionId: String)
  fun ping(): Boolean
}
