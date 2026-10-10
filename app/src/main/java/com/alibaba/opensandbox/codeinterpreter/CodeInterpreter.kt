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
package com.alibaba.opensandbox.codeinterpreter

import com.alibaba.opensandbox.codeinterpreter.domain.services.Codes
import com.alibaba.opensandbox.sandbox.Sandbox

/**
 * CodeInterpreter extends an OpenSandbox instance with language runtime code interpretation capabilities.
 */
class CodeInterpreter(
  val sandbox: Sandbox,
  private val codesService: Codes
) {
  fun codes(): Codes = codesService

  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var sandbox: Sandbox? = null

    fun fromSandbox(sandbox: Sandbox): Builder {
      this.sandbox = sandbox
      return this
    }

    fun build(): CodeInterpreter {
      val sb = sandbox ?: throw IllegalArgumentException("Sandbox instance must be specified via fromSandbox()")
      return sb.codeInterpreter()
    }
  }
}
