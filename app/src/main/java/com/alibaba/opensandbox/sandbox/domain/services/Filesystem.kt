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

import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.ContentReplaceEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.ContentReplaceResult
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.MoveEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.SearchEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.SetPermissionEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry
import java.io.InputStream
import java.util.Collections

/**
 * Filesystem operations for OpenSandbox environments.
 */
interface Filesystem {
  /**
   * Reads the content of a file as a string.
   */
  fun readFile(
    path: String,
    encoding: String = "UTF-8",
    range: String? = null,
    offset: Int? = null,
    limit: Int? = null,
  ): String

  /**
   * Reads the content of a file as a byte array.
   */
  fun readByteArray(
    path: String,
    range: String? = null,
    offset: Int? = null,
    limit: Int? = null,
  ): ByteArray

  /**
   * Opens a file for reading as an InputStream.
   */
  fun readStream(
    path: String,
    range: String? = null,
    offset: Int? = null,
    limit: Int? = null,
  ): InputStream

  /**
   * Writes content to files based on the provided write entries.
   */
  fun write(entries: List<WriteEntry>)

  /**
   * Writes a single file based on the provided [WriteEntry].
   */
  fun writeFile(entry: WriteEntry) {
    write(Collections.singletonList(entry))
  }

  /**
   * Convenience overload for writing a single file.
   */
  fun writeFile(path: String, data: Any) {
    writeFile(WriteEntry.builder().path(path).data(data).build())
  }

  /**
   * Creates directories based on the provided entries.
   */
  fun createDirectories(entries: List<WriteEntry>)

  /**
   * Deletes the specified files.
   */
  fun deleteFiles(paths: List<String>)

  /**
   * Deletes the specified directories.
   */
  fun deleteDirectories(paths: List<String>)

  /**
   * Lists directory contents with optional depth control.
   */
  fun listDirectory(path: String, depth: Int? = null): List<EntryInfo>

  /**
   * Moves files from source to destination paths.
   */
  fun moveFiles(entries: List<MoveEntry>)

  /**
   * Sets file system permissions for the specified entries.
   */
  fun setPermissions(entries: List<SetPermissionEntry>)

  /**
   * Replaces content in files based on search and replace patterns.
   */
  fun replaceContents(entries: List<ContentReplaceEntry>)

  /**
   * Replaces content in files and returns per-file replacement counts.
   */
  fun replaceContentsDetailed(entries: List<ContentReplaceEntry>): List<ContentReplaceResult>

  /**
   * Searches for files and directories based on the specified criteria.
   */
  fun search(entry: SearchEntry): List<EntryInfo>

  /**
   * Retrieves file information for the specified paths.
   */
  fun readFileInfo(paths: List<String>): Map<String, EntryInfo>
}
