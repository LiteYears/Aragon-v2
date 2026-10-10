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
package com.alibaba.opensandbox.sandbox.domain.exceptions

/**
 * Base exception for all OpenSandbox operations.
 */
open class SandboxException(
  message: String,
  cause: Throwable? = null
) : RuntimeException(message, cause)

class InvalidArgumentException(
  message: String,
  cause: Throwable? = null
) : SandboxException(message, cause)

class SandboxInternalException(
  message: String,
  cause: Throwable? = null
) : SandboxException(message, cause)

class SandboxNotFoundException(
  message: String,
  cause: Throwable? = null
) : SandboxException(message, cause)

class SandboxReadyTimeoutException(
  message: String,
  cause: Throwable? = null
) : SandboxException(message, cause)
