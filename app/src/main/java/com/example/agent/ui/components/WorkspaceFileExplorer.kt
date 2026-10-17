package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.DeliverableStatus
import com.example.agent.core.FileExplorerItem
import com.example.agent.core.TrackedDeliverable
import com.example.ui.theme.AmoledAccentAmber
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledActionPrimaryOn
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusError
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExplorerViewMode {
  LIST,
  GRID
}

enum class ExplorerSortBy(val label: String) {
  NAME_ASC("Name A-Z"),
  NAME_DESC("Name Z-A"),
  SIZE_DESC("Size Large-Small"),
  SIZE_ASC("Size Small-Large"),
  DATE_DESC("Date Newest"),
  DATE_ASC("Date Oldest")
}

enum class FileCategoryFilter(val label: String) {
  ALL("ALL"),
  DELIVERABLES("DELIVERABLES"),
  DOCS("DOCS"),
  CODE("CODE"),
  DATA("DATA")
}

/**
 * Minimal, high-density, beautifully themed Workspace File Explorer.
 * Features unified compact command bar, directory breadcrumbs, smart category tabs,
 * instant search, responsive list/grid modes, deliverable badges, and rich file actions.
 */
@Composable
fun WorkspaceFileExplorer(
  currentPath: String,
  searchQuery: String,
  items: List<FileExplorerItem>,
  deliverables: List<TrackedDeliverable>,
  onNavigateToFolder: (String) -> Unit,
  onNavigateUp: () -> Unit,
  onBreadcrumbClick: (String) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onCreateFile: (String, String) -> Unit,
  onCreateFolder: (String) -> Unit,
  onDeleteItem: (FileExplorerItem) -> Unit,
  onPreviewItem: (FileExplorerItem) -> Unit,
  onSaveItem: (FileExplorerItem) -> Unit,
  onShareItem: (FileExplorerItem) -> Unit,
  onRefresh: () -> Unit,
  onRenameItem: ((FileExplorerItem, String) -> Unit)? = null,
  onDuplicateItem: ((FileExplorerItem) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var showNewFileDialog by remember { mutableStateOf(false) }
  var showNewFolderDialog by remember { mutableStateOf(false) }
  var itemToDelete by remember { mutableStateOf<FileExplorerItem?>(null) }
  var itemToRename by remember { mutableStateOf<FileExplorerItem?>(null) }
  var itemToInspect by remember { mutableStateOf<FileExplorerItem?>(null) }

  var viewMode by remember { mutableStateOf(ExplorerViewMode.LIST) }
  var activeCategory by remember { mutableStateOf(FileCategoryFilter.ALL) }
  var sortBy by remember { mutableStateOf(ExplorerSortBy.NAME_ASC) }

  val docsExtensions = remember { listOf("md", "txt", "docx", "doc", "pdf", "rtf", "odt", "html", "log") }
  val codeExtensions = remember { listOf("py", "sh", "js", "ts", "kt", "java", "cpp", "c", "h", "rs", "go", "rb", "css") }
  val dataExtensions = remember { listOf("json", "jsonl", "csv", "xlsx", "xls", "tsv", "xml", "yaml", "yml", "sql", "db") }

  // Filter items by category, search query, and sort order
  val filteredItems = remember(items, searchQuery, activeCategory, sortBy) {
    var list = items

    // 1. Category Filter (Directories are only included in ALL or if they match name)
    list = when (activeCategory) {
      FileCategoryFilter.ALL -> list
      FileCategoryFilter.DELIVERABLES -> list.filter { it.isDeliverable }
      FileCategoryFilter.DOCS -> list.filter { !it.isDirectory && it.extension in docsExtensions }
      FileCategoryFilter.CODE -> list.filter { !it.isDirectory && it.extension in codeExtensions }
      FileCategoryFilter.DATA -> list.filter { !it.isDirectory && it.extension in dataExtensions }
    }

    // 2. Search Query Filter
    if (searchQuery.isNotBlank()) {
      val q = searchQuery.trim()
      list = list.filter { it.name.contains(q, ignoreCase = true) }
    }

    // 3. Sorting (Directories always grouped at top in ALL view)
    list.sortedWith { a, b ->
      if (a.isDirectory != b.isDirectory) {
        if (a.isDirectory) -1 else 1
      } else {
        when (sortBy) {
          ExplorerSortBy.NAME_ASC -> a.name.compareTo(b.name, ignoreCase = true)
          ExplorerSortBy.NAME_DESC -> b.name.compareTo(a.name, ignoreCase = true)
          ExplorerSortBy.SIZE_DESC -> b.size.compareTo(a.size)
          ExplorerSortBy.SIZE_ASC -> a.size.compareTo(b.size)
          ExplorerSortBy.DATE_DESC -> b.lastModified.compareTo(a.lastModified)
          ExplorerSortBy.DATE_ASC -> a.lastModified.compareTo(b.lastModified)
        }
      }
    }
  }

  val totalDirCount = items.count { it.isDirectory }
  val totalFileCount = items.count { !it.isDirectory }
  val totalSizeBytes = items.filter { !it.isDirectory }.sumOf { it.size }
  val deliverablesCount = items.count { it.isDeliverable }
  val docsCount = items.count { !it.isDirectory && it.extension in docsExtensions }
  val codeCount = items.count { !it.isDirectory && it.extension in codeExtensions }
  val dataCount = items.count { !it.isDirectory && it.extension in dataExtensions }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(AmoledBackground)
  ) {
    // 1. Unified Minimal Header: Breadcrumb Trail & Quick Actions
    WorkspaceMinimalCommandHeader(
      currentPath = currentPath,
      totalFiles = totalFileCount,
      totalDirs = totalDirCount,
      totalSize = totalSizeBytes,
      viewMode = viewMode,
      sortBy = sortBy,
      onNavigateUp = onNavigateUp,
      onBreadcrumbClick = onBreadcrumbClick,
      onRefresh = onRefresh,
      onToggleViewMode = {
        viewMode = if (viewMode == ExplorerViewMode.LIST) ExplorerViewMode.GRID else ExplorerViewMode.LIST
      },
      onSelectSortBy = { sortBy = it },
      onNewFile = { showNewFileDialog = true },
      onNewFolder = { showNewFolderDialog = true }
    )

    // 2. Integrated Search Bar and Category Pills
    WorkspaceSearchAndCategoryBar(
      searchQuery = searchQuery,
      onSearchQueryChange = onSearchQueryChange,
      activeCategory = activeCategory,
      totalCount = items.size,
      deliverablesCount = deliverablesCount,
      docsCount = docsCount,
      codeCount = codeCount,
      dataCount = dataCount,
      onSelectCategory = { activeCategory = it }
    )

    // 3. File List or Grid Content
    if (filteredItems.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .padding(32.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = if (searchQuery.isNotBlank()) "[NO MATCHING ITEMS]" else "[DIRECTORY EMPTY]",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = AmoledIconGreyLight,
            fontSize = 11.sp
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = if (searchQuery.isNotBlank()) {
              "No files or folders match '$searchQuery'"
            } else {
              "This workspace directory is empty."
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            color = AmoledTextMuted,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(14.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AragonActionButton(
              text = "+ NEW FILE",
              onClick = { showNewFileDialog = true },
              variant = AragonButtonVariant.PRIMARY,
              height = 32.dp,
              testTag = "empty_create_file_btn"
            )
            AragonActionButton(
              text = "+ NEW FOLDER",
              onClick = { showNewFolderDialog = true },
              variant = AragonButtonVariant.DEFAULT,
              height = 32.dp,
              testTag = "empty_create_folder_btn"
            )
          }
        }
      }
    } else {
      if (viewMode == ExplorerViewMode.LIST) {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .testTag("workspace_explorer_list"),
          contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
          itemsIndexed(
            items = filteredItems,
            key = { _, item -> item.relativePath }
          ) { _, item ->
            if (item.isDirectory) {
              DirectoryItemRow(
                item = item,
                onClick = { onNavigateToFolder(item.name) },
                onRename = { itemToRename = item },
                onDelete = { itemToDelete = item }
              )
            } else {
              FileItemRow(
                item = item,
                onClick = { onPreviewItem(item) },
                onSave = { onSaveItem(item) },
                onShare = { onShareItem(item) },
                onRename = { itemToRename = item },
                onDuplicate = { onDuplicateItem?.invoke(item) },
                onInspect = { itemToInspect = item },
                onDelete = { itemToDelete = item }
              )
            }
          }
        }
      } else {
        // High density Grid mode
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 145.dp),
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .testTag("workspace_explorer_grid"),
          contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          items(
            items = filteredItems,
            key = { it.relativePath }
          ) { item ->
            if (item.isDirectory) {
              DirectoryGridCard(
                item = item,
                onClick = { onNavigateToFolder(item.name) },
                onRename = { itemToRename = item },
                onDelete = { itemToDelete = item }
              )
            } else {
              FileGridCard(
                item = item,
                onClick = { onPreviewItem(item) },
                onSave = { onSaveItem(item) },
                onInspect = { itemToInspect = item },
                onDelete = { itemToDelete = item }
              )
            }
          }
        }
      }
    }
  }

  // Dialog: Create File (With fast templates)
  if (showNewFileDialog) {
    CreateFileDialog(
      currentDirectory = if (currentPath.isBlank()) "workspace" else "workspace/$currentPath",
      onDismiss = { showNewFileDialog = false },
      onCreate = { name, content ->
        showNewFileDialog = false
        onCreateFile(name, content)
      }
    )
  }

  // Dialog: Create Folder
  if (showNewFolderDialog) {
    CreateFolderDialog(
      currentDirectory = if (currentPath.isBlank()) "workspace" else "workspace/$currentPath",
      onDismiss = { showNewFolderDialog = false },
      onCreate = { name ->
        showNewFolderDialog = false
        onCreateFolder(name)
      }
    )
  }

  // Dialog: Rename Item
  itemToRename?.let { item ->
    RenameItemDialog(
      item = item,
      onDismiss = { itemToRename = null },
      onRename = { newName ->
        itemToRename = null
        onRenameItem?.invoke(item, newName)
      }
    )
  }

  // Dialog: Deep Metadata Inspector
  itemToInspect?.let { item ->
    ItemMetadataDialog(
      item = item,
      onDismiss = { itemToInspect = null },
      onPreview = {
        itemToInspect = null
        onPreviewItem(item)
      }
    )
  }

  // Dialog: Delete Confirmation
  itemToDelete?.let { item ->
    AlertDialog(
      onDismissRequest = { itemToDelete = null },
      containerColor = AmoledSurfaceElevated,
      titleContentColor = AmoledTextPrimary,
      textContentColor = AmoledTextSecondary,
      title = {
        Text(
          text = if (item.isDirectory) "Delete Directory?" else "Delete File?",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp
        )
      },
      text = {
        Text(
          text = if (item.isDirectory) {
            "Are you sure you want to delete directory '${item.name}' and all its contents? This cannot be undone."
          } else {
            "Are you sure you want to delete file '${item.name}' (${formatFileSize(item.size)})? This cannot be undone."
          },
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 12.sp,
          color = AmoledTextSecondary
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteItem(item)
            itemToDelete = null
          },
          modifier = Modifier.testTag("confirm_delete_btn")
        ) {
          Text("DELETE", color = AmoledStatusError, fontWeight = FontWeight.Bold, fontFamily = JetBrainsMonoFontFamily)
        }
      },
      dismissButton = {
        TextButton(
          onClick = { itemToDelete = null },
          modifier = Modifier.testTag("cancel_delete_btn")
        ) {
          Text("CANCEL", color = AmoledTextMuted, fontFamily = JetBrainsMonoFontFamily)
        }
      }
    )
  }
}

/**
 * Minimal Command Header integrating Breadcrumbs and Action Controls in a single streamlined card.
 */
@Composable
fun WorkspaceMinimalCommandHeader(
  currentPath: String,
  totalFiles: Int,
  totalDirs: Int,
  totalSize: Long,
  viewMode: ExplorerViewMode,
  sortBy: ExplorerSortBy,
  onNavigateUp: () -> Unit,
  onBreadcrumbClick: (String) -> Unit,
  onRefresh: () -> Unit,
  onToggleViewMode: () -> Unit,
  onSelectSortBy: (ExplorerSortBy) -> Unit,
  onNewFile: () -> Unit,
  onNewFolder: () -> Unit
) {
  var showSortDropdown by remember { mutableStateOf(false) }
  val cleanPath = currentPath.trim().removePrefix("/").removeSuffix("/")
  val segments = if (cleanPath.isBlank()) emptyList() else cleanPath.split("/")

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 4.dp),
    shape = RoundedCornerShape(8.dp),
    color = AmoledSurfaceElevated,
    border = BorderStroke(1.dp, AmoledBorderSubtle)
  ) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
      // Row 1: Breadcrumb path & Stats
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Root tag
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (segments.isEmpty()) AmoledActionPrimary.copy(alpha = 0.2f) else Color.Transparent,
            modifier = Modifier
              .clickable(role = Role.Button, onClickLabel = "Jump to workspace root") { onBreadcrumbClick("") }
              .testTag("breadcrumb_root")
          ) {
            Text(
              text = "workspace",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              color = if (segments.isEmpty()) AmoledTextPrimary else AmoledActionPrimary,
              modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
          }

          var accumulated = ""
          segments.forEachIndexed { index, seg ->
            accumulated = if (accumulated.isEmpty()) seg else "$accumulated/$seg"
            val targetPath = accumulated
            val isLast = index == segments.size - 1

            Text(
              text = "/",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              color = AmoledTextMuted,
              modifier = Modifier.padding(horizontal = 2.dp)
            )

            Surface(
              shape = RoundedCornerShape(4.dp),
              color = if (isLast) AmoledActionPrimary.copy(alpha = 0.2f) else Color.Transparent,
              modifier = Modifier
                .clickable(enabled = !isLast, role = Role.Button, onClickLabel = "Jump to $seg") {
                  onBreadcrumbClick(targetPath)
                }
                .testTag("breadcrumb_$seg")
            ) {
              Text(
                text = seg,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.sp,
                color = if (isLast) AmoledTextPrimary else AmoledTextSecondary,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
              )
            }
          }

          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "(${totalDirs}d, ${totalFiles}f • ${formatFileSize(totalSize)})",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextMuted
          )
        }

        // Quick UP & Refresh
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
          if (segments.isNotEmpty()) {
            AragonActionButton(
              text = "UP",
              onClick = onNavigateUp,
              variant = AragonButtonVariant.MUTED,
              height = 24.dp,
              testTag = "navigate_up_btn"
            )
          }
          AragonActionButton(
            text = "REFRESH",
            onClick = onRefresh,
            variant = AragonButtonVariant.MUTED,
            height = 24.dp,
            testTag = "refresh_explorer_btn"
          )
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Row 2: Action Buttons (Minimalist Compact Bar)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        AragonActionButton(
          text = "+ FILE",
          onClick = onNewFile,
          variant = AragonButtonVariant.PRIMARY,
          height = 26.dp,
          testTag = "toolbar_new_file_btn"
        )

        AragonActionButton(
          text = "+ FOLDER",
          onClick = onNewFolder,
          variant = AragonButtonVariant.DEFAULT,
          height = 26.dp,
          testTag = "toolbar_new_folder_btn"
        )

        AragonActionButton(
          text = if (viewMode == ExplorerViewMode.LIST) "GRID" else "LIST",
          onClick = onToggleViewMode,
          variant = AragonButtonVariant.MUTED,
          height = 26.dp,
          testTag = "toggle_view_mode_btn"
        )

        Box {
          AragonActionButton(
            text = "SORT: ${sortBy.label.substringBefore(' ')}",
            onClick = { showSortDropdown = true },
            variant = AragonButtonVariant.MUTED,
            height = 26.dp,
            testTag = "sort_by_btn"
          )

          DropdownMenu(
            expanded = showSortDropdown,
            onDismissRequest = { showSortDropdown = false },
            modifier = Modifier
              .background(AmoledSurfaceElevated)
              .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(6.dp))
          ) {
            ExplorerSortBy.values().forEach { option ->
              DropdownMenuItem(
                text = {
                  Text(
                    text = option.label,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = if (option == sortBy) AmoledActionPrimary else AmoledTextPrimary
                  )
                },
                onClick = {
                  onSelectSortBy(option)
                  showSortDropdown = false
                }
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Integrated Search and Category Filter Bar.
 */
@Composable
fun WorkspaceSearchAndCategoryBar(
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  activeCategory: FileCategoryFilter,
  totalCount: Int,
  deliverablesCount: Int,
  docsCount: Int,
  codeCount: Int,
  dataCount: Int,
  onSelectCategory: (FileCategoryFilter) -> Unit
) {
  val focusManager = LocalFocusManager.current

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 2.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    // Search input
    Surface(
      shape = RoundedCornerShape(6.dp),
      color = AmoledSurfaceElevated,
      border = BorderStroke(1.dp, if (searchQuery.isNotBlank()) AmoledActionPrimary else AmoledBorderSubtle),
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
      ) {
        Text(
          text = "FIND",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = AmoledActionPrimary,
          modifier = Modifier.padding(end = 6.dp)
        )
        BasicTextField(
          value = searchQuery,
          onValueChange = onSearchQueryChange,
          modifier = Modifier
            .weight(1f)
            .padding(vertical = 5.dp)
            .testTag("explorer_search_input"),
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
          textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            color = AmoledTextPrimary
          ),
          cursorBrush = SolidColor(AmoledActionPrimary),
          decorationBox = { innerTextField ->
            if (searchQuery.isEmpty()) {
              Text(
                text = "Search workspace...",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = AmoledTextMuted
              )
            }
            innerTextField()
          }
        )
        if (searchQuery.isNotBlank()) {
          Box(
            modifier = Modifier
              .size(28.dp)
              .clip(CircleShape)
              .clickable(role = Role.Button, onClickLabel = "Clear search") {
                onSearchQueryChange("")
              }
              .testTag("clear_explorer_search_btn"),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "✕",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = AmoledIconGreyLight
            )
          }
        }
      }
    }

    // Category pills row
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      val filters = listOf(
        FileCategoryFilter.ALL to totalCount,
        FileCategoryFilter.DELIVERABLES to deliverablesCount,
        FileCategoryFilter.DOCS to docsCount,
        FileCategoryFilter.CODE to codeCount,
        FileCategoryFilter.DATA to dataCount
      )

      filters.forEach { (cat, count) ->
        val isSelected = activeCategory == cat
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = if (isSelected) AmoledActionPrimary.copy(alpha = 0.2f) else AmoledSurfaceElevated,
          border = BorderStroke(1.dp, if (isSelected) AmoledActionPrimary else AmoledBorderSubtle),
          modifier = Modifier
            .clickable(role = Role.Tab, onClickLabel = "Filter ${cat.label}") { onSelectCategory(cat) }
            .testTag("category_filter_${cat.name}")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
          ) {
            Text(
              text = cat.label,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              fontSize = 9.sp,
              color = if (isSelected) AmoledActionPrimary else AmoledTextSecondary
            )
            Text(
              text = "($count)",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.sp,
              color = if (isSelected) AmoledActionPrimary else AmoledTextMuted
            )
          }
        }
      }
    }
  }
}

/**
 * Minimalist directory row with clear touch targets.
 */
@Composable
fun DirectoryItemRow(
  item: FileExplorerItem,
  onClick: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit
) {
  val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  val timeStr = dateFormat.format(Date(item.lastModified))
  var showDirMenu by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(6.dp))
      .clickable(role = Role.Button, onClickLabel = "Open directory ${item.name}", onClick = onClick)
      .testTag("dir_row_${item.name}"),
    color = AmoledSurfaceElevated
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Folder badge [DIR]
      Surface(
        shape = RoundedCornerShape(4.dp),
        color = AmoledAccentAmber.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, AmoledAccentAmber.copy(alpha = 0.45f)),
        modifier = Modifier.size(30.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            text = "DIR",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = AmoledAccentAmber
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = item.name,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = AmoledTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.width(3.dp))
          Text(text = "/", fontFamily = JetBrainsMonoFontFamily, color = AmoledTextMuted, fontSize = 12.sp)
        }

        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = if (item.childCount == 1) "1 item" else "${item.childCount} items",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextSecondary
          )
          Text(text = "•", style = MaterialTheme.typography.labelSmall, color = AmoledTextMuted)
          Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextMuted
          )
        }
      }

      // Actions: OPEN + More Menu (•••)
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        AragonActionButton(
          text = "OPEN",
          onClick = onClick,
          variant = AragonButtonVariant.PRIMARY,
          height = 26.dp,
          testTag = "open_dir_btn_${item.name}"
        )

        Box {
          AragonActionButton(
            text = "•••",
            onClick = { showDirMenu = true },
            variant = AragonButtonVariant.MUTED,
            height = 26.dp,
            testTag = "more_dir_btn_${item.name}"
          )

          DropdownMenu(
            expanded = showDirMenu,
            onDismissRequest = { showDirMenu = false },
            modifier = Modifier
              .background(AmoledSurfaceElevated)
              .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(6.dp))
          ) {
            DropdownMenuItem(
              text = {
                Text("Rename Directory", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledTextPrimary)
              },
              onClick = {
                showDirMenu = false
                onRename()
              }
            )
            DropdownMenuItem(
              text = {
                Text("Delete Directory", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledStatusError)
              },
              onClick = {
                showDirMenu = false
                onDelete()
              }
            )
          }
        }
      }
    }
  }
}

/**
 * Minimalist file row with deliverable highlights and rich quick actions.
 */
@Composable
fun FileItemRow(
  item: FileExplorerItem,
  onClick: () -> Unit,
  onSave: () -> Unit,
  onShare: () -> Unit,
  onRename: () -> Unit,
  onDuplicate: (() -> Unit)?,
  onInspect: () -> Unit,
  onDelete: () -> Unit
) {
  val extBadge = getArtifactExtensionBadge(item.name)
  val extColor = getArtifactExtensionColor(item.name)
  val formattedSize = formatFileSize(item.size)
  val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  val timeStr = dateFormat.format(Date(item.lastModified))
  var showFileMenu by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(
        1.dp,
        if (item.isDeliverable) AmoledStatusSuccess.copy(alpha = 0.5f) else AmoledBorderSubtle,
        RoundedCornerShape(6.dp)
      )
      .clickable(role = Role.Button, onClickLabel = "Inspect file ${item.name}", onClick = onClick)
      .testTag("file_row_${item.name}"),
    color = AmoledSurfaceElevated
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Extension badge
      Surface(
        shape = RoundedCornerShape(4.dp),
        color = extColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, extColor.copy(alpha = 0.4f)),
        modifier = Modifier.size(30.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            text = extBadge,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = extColor
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = item.name,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = AmoledTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )

          if (item.isDeliverable) {
            Spacer(modifier = Modifier.width(5.dp))
            Surface(
              shape = RoundedCornerShape(3.dp),
              color = AmoledStatusSuccess.copy(alpha = 0.18f),
              border = BorderStroke(1.dp, AmoledStatusSuccess.copy(alpha = 0.5f))
            ) {
              Text(
                text = if (item.qualityScore > 0) "DELIVERABLE ✓ (${item.qualityScore}%)" else "DELIVERABLE ✓",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                color = AmoledStatusSuccess,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(1.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = formattedSize,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextSecondary
          )
          if (item.lineCount > 0) {
            Text(text = "•", style = MaterialTheme.typography.labelSmall, color = AmoledTextMuted)
            Text(
              text = "${item.lineCount}L",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 9.sp,
              color = AmoledTextSecondary
            )
          }
          Text(text = "•", style = MaterialTheme.typography.labelSmall, color = AmoledTextMuted)
          Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextMuted
          )
        }
      }

      // Compact Actions: SAVE + More (•••)
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        AragonActionButton(
          text = "SAVE",
          onClick = onSave,
          variant = AragonButtonVariant.MUTED,
          height = 26.dp,
          testTag = "save_file_btn_${item.name}"
        )

        Box {
          AragonActionButton(
            text = "•••",
            onClick = { showFileMenu = true },
            variant = AragonButtonVariant.MUTED,
            height = 26.dp,
            testTag = "more_file_btn_${item.name}"
          )

          DropdownMenu(
            expanded = showFileMenu,
            onDismissRequest = { showFileMenu = false },
            modifier = Modifier
              .background(AmoledSurfaceElevated)
              .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(6.dp))
          ) {
            DropdownMenuItem(
              text = { Text("View Content", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledTextPrimary) },
              onClick = {
                showFileMenu = false
                onClick()
              }
            )
            DropdownMenuItem(
              text = { Text("File Info & Metadata", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledTextPrimary) },
              onClick = {
                showFileMenu = false
                onInspect()
              }
            )
            DropdownMenuItem(
              text = { Text("Rename File", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledTextPrimary) },
              onClick = {
                showFileMenu = false
                onRename()
              }
            )
            if (onDuplicate != null) {
              DropdownMenuItem(
                text = { Text("Duplicate File", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledTextPrimary) },
                onClick = {
                  showFileMenu = false
                  onDuplicate()
                }
              )
            }
            DropdownMenuItem(
              text = { Text("Share File", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledTextPrimary) },
              onClick = {
                showFileMenu = false
                onShare()
              }
            )
            DropdownMenuItem(
              text = { Text("Delete File", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledStatusError) },
              onClick = {
                showFileMenu = false
                onDelete()
              }
            )
          }
        }
      }
    }
  }
}

/**
 * Grid Card for Directory in dense matrix view.
 */
@Composable
fun DirectoryGridCard(
  item: FileExplorerItem,
  onClick: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .testTag("dir_grid_${item.name}"),
    color = AmoledSurfaceElevated
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = AmoledAccentAmber.copy(alpha = 0.15f),
          border = BorderStroke(1.dp, AmoledAccentAmber.copy(alpha = 0.4f))
        ) {
          Text(
            text = "DIR",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = AmoledAccentAmber,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
          )
        }
        Text(
          text = "${item.childCount} items",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = AmoledTextMuted
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      Text(
        text = item.name,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        color = AmoledTextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        AragonActionButton(
          text = "OPEN",
          onClick = onClick,
          variant = AragonButtonVariant.PRIMARY,
          height = 24.dp
        )
        AragonActionButton(
          text = "DEL",
          onClick = onDelete,
          variant = AragonButtonVariant.MUTED,
          height = 24.dp
        )
      }
    }
  }
}

/**
 * Grid Card for File in dense matrix view.
 */
@Composable
fun FileGridCard(
  item: FileExplorerItem,
  onClick: () -> Unit,
  onSave: () -> Unit,
  onInspect: () -> Unit,
  onDelete: () -> Unit
) {
  val extBadge = getArtifactExtensionBadge(item.name)
  val extColor = getArtifactExtensionColor(item.name)

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(
        1.dp,
        if (item.isDeliverable) AmoledStatusSuccess.copy(alpha = 0.5f) else AmoledBorderSubtle,
        RoundedCornerShape(6.dp)
      )
      .clickable(onClick = onClick)
      .testTag("file_grid_${item.name}"),
    color = AmoledSurfaceElevated
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = extColor.copy(alpha = 0.15f),
          border = BorderStroke(1.dp, extColor.copy(alpha = 0.4f))
        ) {
          Text(
            text = extBadge,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = extColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
          )
        }

        Text(
          text = formatFileSize(item.size),
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = AmoledTextMuted
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      Text(
        text = item.name,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        color = AmoledTextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )

      if (item.isDeliverable) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "DELIVERABLE ✓",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 8.sp,
          color = AmoledStatusSuccess
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        AragonActionButton(
          text = "VIEW",
          onClick = onClick,
          variant = AragonButtonVariant.PRIMARY,
          height = 24.dp
        )
        AragonActionButton(
          text = "SAVE",
          onClick = onSave,
          variant = AragonButtonVariant.MUTED,
          height = 24.dp
        )
        AragonActionButton(
          text = "DEL",
          onClick = onDelete,
          variant = AragonButtonVariant.MUTED,
          height = 24.dp
        )
      }
    }
  }
}

/**
 * Dialog for creating a new file with fast templates (Markdown, Word docx, Excel xlsx, Python, JSON, CSV, Shell).
 */
@Composable
fun CreateFileDialog(
  currentDirectory: String,
  onDismiss: () -> Unit,
  onCreate: (String, String) -> Unit
) {
  var fileName by remember { mutableStateOf("") }
  var initialContent by remember { mutableStateOf("") }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val templates = listOf(
    "Blank" to ("" to ""),
    "Markdown (.md)" to ("report.md" to "# Executive Summary\n\n## Overview\n\n## Findings\n\n## Action Items\n"),
    "Word (.docx)" to ("document.docx" to ""),
    "Excel (.xlsx)" to ("spreadsheet.xlsx" to ""),
    "Python (.py)" to ("script.py" to "#!/usr/bin/env python3\nimport sys\n\ndef main():\n    print('Task completed successfully.')\n\nif __name__ == '__main__':\n    main()\n"),
    "Shell (.sh)" to ("run.sh" to "#!/bin/bash\nset -euo pipefail\necho \"Executing workspace pipeline...\"\n"),
    "JSON (.json)" to ("data.json" to "{\n  \"status\": \"verified\",\n  \"version\": \"1.0.0\",\n  \"items\": []\n}\n"),
    "CSV (.csv)" to ("dataset.csv" to "id,timestamp,category,value,status\n1,2026-10-16,A,100,active\n")
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = AmoledSurfaceElevated,
    titleContentColor = AmoledTextPrimary,
    textContentColor = AmoledTextSecondary,
    title = {
      Text(
        text = "Create Workspace File",
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Directory: $currentDirectory",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 10.sp,
          color = AmoledTextMuted
        )

        // Template pills
        Text(
          text = "Quick Templates:",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 10.sp,
          color = AmoledTextSecondary
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          templates.forEach { (label, pair) ->
            val (tplName, tplContent) = pair
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = AmoledSurfaceVariant,
              border = BorderStroke(1.dp, AmoledBorderSubtle),
              modifier = Modifier.clickable {
                if (tplName.isNotBlank()) fileName = tplName
                if (tplContent.isNotBlank()) initialContent = tplContent
              }
            ) {
              Text(
                text = label,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = AmoledActionPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
              )
            }
          }
        }

        OutlinedTextField(
          value = fileName,
          onValueChange = {
            fileName = it
            errorMessage = null
          },
          label = { Text("File Name (e.g. report.docx, data.csv)", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("new_file_name_input"),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, color = AmoledTextPrimary, fontSize = 11.sp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AmoledActionPrimary,
            unfocusedBorderColor = AmoledBorderSubtle,
            cursorColor = AmoledActionPrimary
          )
        )

        if (!fileName.endsWith(".docx", ignoreCase = true) && !fileName.endsWith(".xlsx", ignoreCase = true)) {
          OutlinedTextField(
            value = initialContent,
            onValueChange = { initialContent = it },
            label = { Text("Initial Text Content (Optional)", fontSize = 11.sp) },
            modifier = Modifier
              .fillMaxWidth()
              .height(110.dp)
              .testTag("new_file_content_input"),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = AmoledTextPrimary),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = AmoledActionPrimary,
              unfocusedBorderColor = AmoledBorderSubtle,
              cursorColor = AmoledActionPrimary
            )
          )
        }

        errorMessage?.let { err ->
          Text(text = err, color = AmoledStatusError, fontSize = 11.sp, fontFamily = JetBrainsMonoFontFamily)
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val clean = fileName.trim()
          if (clean.isBlank()) {
            errorMessage = "File name cannot be empty."
          } else if (clean.contains("/") || clean.contains("\\")) {
            errorMessage = "File name cannot contain path separators."
          } else {
            onCreate(clean, initialContent)
          }
        },
        modifier = Modifier.testTag("confirm_create_file_btn")
      ) {
        Text("CREATE", color = AmoledActionPrimary, fontWeight = FontWeight.Bold, fontFamily = JetBrainsMonoFontFamily)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("CANCEL", color = AmoledTextMuted, fontFamily = JetBrainsMonoFontFamily)
      }
    }
  )
}

/**
 * Dialog for creating a new directory.
 */
@Composable
fun CreateFolderDialog(
  currentDirectory: String,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit
) {
  var folderName by remember { mutableStateOf("") }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = AmoledSurfaceElevated,
    titleContentColor = AmoledTextPrimary,
    textContentColor = AmoledTextSecondary,
    title = {
      Text(
        text = "Create New Directory",
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Parent: $currentDirectory",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 10.sp,
          color = AmoledTextMuted
        )

        OutlinedTextField(
          value = folderName,
          onValueChange = {
            folderName = it
            errorMessage = null
          },
          label = { Text("Directory Name (e.g. data, reports, src)", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("new_folder_name_input"),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, color = AmoledTextPrimary, fontSize = 11.sp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AmoledActionPrimary,
            unfocusedBorderColor = AmoledBorderSubtle,
            cursorColor = AmoledActionPrimary
          )
        )

        errorMessage?.let { err ->
          Text(text = err, color = AmoledStatusError, fontSize = 11.sp, fontFamily = JetBrainsMonoFontFamily)
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val clean = folderName.trim()
          if (clean.isBlank()) {
            errorMessage = "Directory name cannot be empty."
          } else if (clean.contains("/") || clean.contains("\\")) {
            errorMessage = "Directory name cannot contain path separators."
          } else {
            onCreate(clean)
          }
        },
        modifier = Modifier.testTag("confirm_create_folder_btn")
      ) {
        Text("CREATE", color = AmoledActionPrimary, fontWeight = FontWeight.Bold, fontFamily = JetBrainsMonoFontFamily)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("CANCEL", color = AmoledTextMuted, fontFamily = JetBrainsMonoFontFamily)
      }
    }
  )
}

/**
 * Dialog for renaming a file or directory.
 */
@Composable
fun RenameItemDialog(
  item: FileExplorerItem,
  onDismiss: () -> Unit,
  onRename: (String) -> Unit
) {
  var newName by remember { mutableStateOf(item.name) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = AmoledSurfaceElevated,
    titleContentColor = AmoledTextPrimary,
    textContentColor = AmoledTextSecondary,
    title = {
      Text(
        text = if (item.isDirectory) "Rename Directory" else "Rename File",
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Original: ${item.name}",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 10.sp,
          color = AmoledTextMuted
        )

        OutlinedTextField(
          value = newName,
          onValueChange = {
            newName = it
            errorMessage = null
          },
          label = { Text("New Name", fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("rename_input"),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, color = AmoledTextPrimary, fontSize = 11.sp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AmoledActionPrimary,
            unfocusedBorderColor = AmoledBorderSubtle,
            cursorColor = AmoledActionPrimary
          )
        )

        errorMessage?.let { err ->
          Text(text = err, color = AmoledStatusError, fontSize = 11.sp, fontFamily = JetBrainsMonoFontFamily)
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val clean = newName.trim()
          if (clean.isBlank()) {
            errorMessage = "Name cannot be empty."
          } else if (clean.contains("/") || clean.contains("\\")) {
            errorMessage = "Name cannot contain path separators."
          } else if (clean == item.name) {
            onDismiss()
          } else {
            onRename(clean)
          }
        },
        modifier = Modifier.testTag("confirm_rename_btn")
      ) {
        Text("RENAME", color = AmoledActionPrimary, fontWeight = FontWeight.Bold, fontFamily = JetBrainsMonoFontFamily)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("CANCEL", color = AmoledTextMuted, fontFamily = JetBrainsMonoFontFamily)
      }
    }
  )
}

/**
 * Deep Metadata Inspector dialog displaying size, lines, path, hash, and format metrics.
 */
@Composable
fun ItemMetadataDialog(
  item: FileExplorerItem,
  onDismiss: () -> Unit,
  onPreview: () -> Unit
) {
  val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
  val timeStr = dateFormat.format(Date(item.lastModified))

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = AmoledSurfaceElevated,
    titleContentColor = AmoledTextPrimary,
    textContentColor = AmoledTextSecondary,
    title = {
      Text(
        text = "File Metadata Inspector",
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        MetadataRow("Name", item.name)
        MetadataRow("Relative Path", item.relativePath)
        MetadataRow("Absolute Path", item.absolutePath.ifBlank { "N/A" })
        MetadataRow("Size", "${formatFileSize(item.size)} (${item.size} bytes)")
        if (item.lineCount > 0) {
          MetadataRow("Line Count", "${item.lineCount} lines")
        }
        MetadataRow("Last Modified", timeStr)
        if (item.isDeliverable) {
          MetadataRow("Deliverable Status", item.deliverableStatus?.name ?: "VERIFIED")
          if (item.qualityScore > 0) {
            MetadataRow("Quality Score", "${item.qualityScore}%")
          }
        }
        if (!item.sha256.isNullOrBlank()) {
          MetadataRow("SHA-256 (Prefix)", item.sha256)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onPreview) {
        Text("VIEW CONTENT", color = AmoledActionPrimary, fontWeight = FontWeight.Bold, fontFamily = JetBrainsMonoFontFamily)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("CLOSE", color = AmoledTextMuted, fontFamily = JetBrainsMonoFontFamily)
      }
    }
  )
}

@Composable
private fun MetadataRow(label: String, value: String) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(
      text = label.uppercase(),
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      color = AmoledTextMuted
    )
    Text(
      text = value,
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 11.sp,
      color = AmoledTextPrimary,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis
    )
  }
}
