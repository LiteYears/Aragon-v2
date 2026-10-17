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
package com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem

import java.time.OffsetDateTime

/**
 * Metadata information for a file or directory entry in OpenSandbox.
 *
 * @property path Absolute or relative path of the file or directory
 * @property mode Unix file mode/permissions as integer (e.g., 644 for rw-r--r--)
 * @property owner Owner username of the file or directory
 * @property group Group name of the file or directory
 * @property size Size of the file in bytes (0 for directories)
 * @property modifiedAt Timestamp when the entry was last modified
 * @property createdAt Timestamp when the entry was created
 * @property type Entry type (file, directory, symlink)
 */
data class EntryInfo(
  val path: String,
  val mode: Int = 644,
  val owner: String = "sandbox",
  val group: String = "sandbox",
  val size: Long = 0L,
  val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
  val createdAt: OffsetDateTime = OffsetDateTime.now(),
  val type: String? = "file",
) {
  val isDirectory: Boolean get() = type == "directory" || size == 0L && mode and 0x4000 != 0
}

/**
 * Request to write content to a file.
 */
class WriteEntry private constructor(
  val path: String,
  val data: Any?,
  val mode: Int,
  val owner: String?,
  val group: String?,
  val encoding: String,
) {
  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var path: String? = null
    private var data: Any? = null
    private var mode: Int = 644
    private var owner: String? = null
    private var group: String? = null
    private var encoding: String = "UTF-8"

    fun path(path: String): Builder {
      require(path.isNotBlank()) { "Path cannot be blank" }
      this.path = path
      return this
    }

    fun data(data: Any?): Builder {
      this.data = data
      return this
    }

    fun mode(mode: Int): Builder {
      this.mode = mode
      return this
    }

    fun owner(owner: String?): Builder {
      this.owner = owner
      return this
    }

    fun group(group: String?): Builder {
      this.group = group
      return this
    }

    fun encoding(encoding: String): Builder {
      this.encoding = encoding
      return this
    }

    fun build(): WriteEntry {
      val pathValue = path ?: throw IllegalArgumentException("Path must be specified")
      return WriteEntry(
        path = pathValue,
        data = data,
        mode = mode,
        owner = owner,
        group = group,
        encoding = encoding,
      )
    }
  }
}

/**
 * Request to move or rename a file or directory.
 */
class MoveEntry private constructor(
  val sourcePath: String,
  val destinationPath: String,
) {
  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var sourcePath: String? = null
    private var destinationPath: String? = null

    fun sourcePath(sourcePath: String): Builder {
      require(sourcePath.isNotBlank()) { "Source path cannot be blank" }
      this.sourcePath = sourcePath
      return this
    }

    fun destinationPath(destinationPath: String): Builder {
      require(destinationPath.isNotBlank()) { "Destination path cannot be blank" }
      this.destinationPath = destinationPath
      return this
    }

    fun build(): MoveEntry {
      val src = sourcePath ?: throw IllegalArgumentException("Source path must be specified")
      val dst = destinationPath ?: throw IllegalArgumentException("Destination path must be specified")
      return MoveEntry(sourcePath = src, destinationPath = dst)
    }
  }
}

/**
 * Request to set permissions on a file.
 */
class SetPermissionEntry private constructor(
  val path: String,
  val owner: String?,
  val group: String?,
  val mode: Int,
) {
  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var path: String? = null
    private var owner: String? = null
    private var group: String? = null
    private var mode: Int = 644

    fun path(path: String): Builder {
      require(path.isNotBlank()) { "Path cannot be blank" }
      this.path = path
      return this
    }

    fun owner(owner: String?): Builder {
      this.owner = owner
      return this
    }

    fun group(group: String?): Builder {
      this.group = group
      return this
    }

    fun mode(mode: Int): Builder {
      this.mode = mode
      return this
    }

    fun build(): SetPermissionEntry {
      val pathValue = path ?: throw IllegalArgumentException("Path must be specified")
      return SetPermissionEntry(path = pathValue, owner = owner, group = group, mode = mode)
    }
  }
}

/**
 * Request to search for files matching a pattern.
 */
class SearchEntry private constructor(
  val path: String,
  val pattern: String,
) {
  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var path: String? = null
    private var pattern: String? = null

    fun path(path: String): Builder {
      require(path.isNotBlank()) { "Path cannot be blank" }
      this.path = path
      return this
    }

    fun pattern(pattern: String): Builder {
      require(pattern.isNotBlank()) { "Pattern cannot be blank" }
      this.pattern = pattern
      return this
    }

    fun build(): SearchEntry {
      val pathValue = path ?: throw IllegalArgumentException("Path must be specified")
      val patternValue = pattern ?: throw IllegalArgumentException("Pattern must be specified")
      return SearchEntry(path = pathValue, pattern = patternValue)
    }
  }
}

/**
 * Request to replace content within a file.
 */
class ContentReplaceEntry private constructor(
  val path: String,
  val oldContent: String,
  val newContent: String,
) {
  companion object {
    @JvmStatic
    fun builder(): Builder = Builder()
  }

  class Builder {
    private var path: String? = null
    private var oldContent: String? = null
    private var newContent: String? = null

    fun path(path: String): Builder {
      require(path.isNotBlank()) { "Path cannot be blank" }
      this.path = path
      return this
    }

    fun oldContent(oldContent: String): Builder {
      this.oldContent = oldContent
      return this
    }

    fun newContent(newContent: String): Builder {
      this.newContent = newContent
      return this
    }

    fun build(): ContentReplaceEntry {
      val pathValue = path ?: throw IllegalArgumentException("Path must be specified")
      val oldContentValue = oldContent ?: throw IllegalArgumentException("Old content must be specified")
      val newContentValue = newContent ?: throw IllegalArgumentException("New content must be specified")
      return ContentReplaceEntry(path = pathValue, oldContent = oldContentValue, newContent = newContentValue)
    }
  }
}

/**
 * Result of a content replacement operation on a single file.
 */
data class ContentReplaceResult(
  val path: String,
  val replacedCount: Int,
)
