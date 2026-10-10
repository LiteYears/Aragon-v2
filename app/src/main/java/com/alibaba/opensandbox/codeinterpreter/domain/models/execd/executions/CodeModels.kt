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
package com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionHandlers

/**
 * Supported programming languages for OpenSandbox code execution.
 */
object SupportedLanguage {
  const val PYTHON = "python"
  const val JAVA = "java"
  const val GO = "go"
  const val TYPESCRIPT = "typescript"
  const val BASH = "bash"
  const val JAVASCRIPT = "javascript"
}

/**
 * Represents an execution context for code interpretation in OpenSandbox.
 */
class CodeContext private constructor(
  val id: String?,
  val language: String,
) {
  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var language: String = SupportedLanguage.PYTHON
    private var id: String? = null

    fun id(id: String?): Builder {
      this.id = id
      return this
    }

    fun language(language: String): Builder {
      this.language = language
      return this
    }

    fun build(): CodeContext {
      return CodeContext(id = id, language = language)
    }
  }
}

/**
 * Request model for executing code within a specific OpenSandbox context.
 */
class RunCodeRequest private constructor(
  val code: String,
  val context: CodeContext,
  val handlers: ExecutionHandlers?,
) {
  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var code: String? = null
    private var context: CodeContext = CodeContext.builder().build()
    private var handlers: ExecutionHandlers? = null

    fun code(code: String): Builder {
      require(code.isNotBlank()) { "Code cannot be blank" }
      this.code = code
      return this
    }

    fun context(context: CodeContext): Builder {
      this.context = context
      return this
    }

    fun handlers(handlers: ExecutionHandlers?): Builder {
      this.handlers = handlers
      return this
    }

    fun build(): RunCodeRequest {
      val codeValue = code ?: throw IllegalArgumentException("Code must be specified")
      return RunCodeRequest(code = codeValue, context = context, handlers = handlers)
    }
  }
}
