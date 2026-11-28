package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chimerateam.offsetextractor.data.export.InternalIndex
import com.chimerateam.offsetextractor.data.export.OffsetCategory
import com.chimerateam.offsetextractor.data.export.OffsetRow
import com.chimerateam.offsetextractor.data.export.OffsetRows
import com.chimerateam.offsetextractor.data.native.AddressInspection
import com.chimerateam.offsetextractor.data.native.Disassembly
import com.chimerateam.offsetextractor.data.native.InspectorState
import com.chimerateam.offsetextractor.ui.log.LogEntry
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.Graphite700
import com.chimerateam.offsetextractor.ui.theme.MonoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tag of the workspace data grid. */
internal const val WORKSPACE_GRID_TAG = "workspace-grid"

/** Test tag of the inspector pane. */
internal const val WORKSPACE_INSPECTOR_TAG = "workspace-inspector"

/** Dense monospace row style used by the grid and the inspector. */
private val Dense = MonoText.label.copy(fontSize = 12.sp, lineHeight = 16.sp)

/**
 * Desktop-style reverse-engineering workspace.
 *
 * Three docked panes: a category tree, a sortable high-density grid and a
 * contextual inspector. It is shown from 840dp, where the extra width can carry
 * the sidebar and the inspector without squeezing the data.
 *
 * @param result the completed scan summary.
 * @param query current search text.
 * @param onQueryChange invoked when the search text changes.
 * @param inspector state of the right-hand pane.
 * @param onSelectAddress invoked with the virtual address of the selected row.
 * @param entries live log entries, shown in the console tab.
 * @param onCopyJson copies the exported JSON.
 * @param onShareJson shares the exported JSON.
 * @param onSaveJson saves the exported JSON.
 * @param onDownloadZip builds and saves the archive.
 * @param onStartNewScan resets the scanner.
 * @param onRetryInternal re-runs the internal pass.
 * @param internalBusy whether the internal pass is running.
 * @param internalAvailable whether the internal table is still on disk.
 * @param internalRetryable whether a retry can succeed.
 * @param modifier layout modifier.
 */
@Composable
fun IdeWorkspace(
    result: ScanResultSummary,
    query: String,
    onQueryChange: (String) -> Unit,
    inspector: InspectorState,
    onSelectAddress: (Long) -> Unit,
    entries: List<LogEntry>,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onDownloadZip: () -> Unit,
    onStartNewScan: () -> Unit,
    onRetryInternal: () -> Unit,
    internalBusy: Boolean,
    internalAvailable: Boolean,
    internalRetryable: Boolean,
    modifier: Modifier = Modifier
) {
    val rows = remember(result) { OffsetRows.from(result) }
    val index = remember(result.internal) { InternalIndex(result.internal) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(SortColumn.Address) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var internalRows by remember { mutableStateOf<List<OffsetRow>>(emptyList()) }
    var internalOffset by remember { mutableStateOf(0) }
    var internalHasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, index) {
        internalRows = emptyList()
        internalOffset = 0
        internalHasMore = false
        if (!index.isAvailable()) return@LaunchedEffect
        // A blank query loads the first page so the internal C++ offsets are
        // browsable without typing; only typed queries are debounced.
        if (query.isNotBlank()) delay(200)
        val page = withContext(Dispatchers.IO) { index.searchPage(query, 0) }
        internalRows = page.rows
        internalOffset = page.rows.size
        internalHasMore = page.hasMore
    }

    val filtered = remember(rows, internalRows, query, category, sort, ascending) {
        val combined = rows.filter { it.matches(query) } + internalRows
        val grouped = if (category == null) {
            combined
        } else {
            combined.filter { categoryOf(it) == category }
        }
        grouped.sortedWith(comparatorFor(sort, ascending))
    }

    Row(modifier = modifier.fillMaxSize()) {
        CategorySidebar(
            result = result,
            rows = rows,
            selected = category,
            onSelect = { category = it },
            modifier = Modifier.width(248.dp).fillMaxHeight()
        )
        PaneDivider()
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            GridHeader(sort = sort, ascending = ascending, onSort = { column ->
                if (sort == column) {
                    ascending = !ascending
                } else {
                    sort = column
                    ascending = true
                }
            })
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(WORKSPACE_GRID_TAG),
                state = rememberLazyListState()
            ) {
                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (query.isBlank()) {
                                "No offsets in this category."
                            } else {
                                "No offset matches \"$query\"."
                            },
                            style = Dense,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                // Duplicate (category, name, address) rows are possible, so the
                // index is part of the list key. The selection key stays the
                // row's own identity so it is independent of list position.
                itemsIndexed(
                    filtered,
                    key = { index, row ->
                        "$index:${row.category}:${row.name}:${row.addressHex}"
                    }
                ) { _, row ->
                    val key = "${row.category}:${row.name}:${row.addressHex}"
                    GridRow(
                        row = row,
                        selected = selectedKey == key,
                        imageBase = result.imageBase,
                        onSelect = {
                            selectedKey = key
                            parseAddress(row.addressHex)?.let(onSelectAddress)
                        }
                    )
                }
                if (internalHasMore) {
                    item(key = "more") {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    loadingMore = true
                                    val page = withContext(Dispatchers.IO) {
                                        index.searchPage(query, internalOffset)
                                    }
                                    internalRows = internalRows + page.rows
                                    internalOffset += page.rows.size
                                    internalHasMore = page.hasMore
                                    loadingMore = false
                                }
                            },
                            enabled = !loadingMore,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text(if (loadingMore) "Loading..." else "Load more matches")
                        }
                    }
                }
            }
            WorkspaceActions(
                result = result,
                query = query,
                onQueryChange = onQueryChange,
                onCopyJson = onCopyJson,
                onShareJson = onShareJson,
                onSaveJson = onSaveJson,
                onDownloadZip = onDownloadZip,
                onStartNewScan = onStartNewScan,
                onRetryInternal = onRetryInternal,
                internalBusy = internalBusy,
                internalAvailable = internalAvailable,
                internalRetryable = internalRetryable
            )
        }
        PaneDivider()
        InspectorPane(
            state = inspector,
            entries = entries,
            onSelectAddress = onSelectAddress,
            modifier = Modifier.width(360.dp).fillMaxHeight()
        )
    }
}

/** Columns the grid can be sorted by. */
private enum class SortColumn(val label: String) {
    Address("Address"),
    Name("Name"),
    Category("Source"),
    Status("Confidence")
}

private fun comparatorFor(sort: SortColumn, ascending: Boolean): Comparator<OffsetRow> {
    val base: Comparator<OffsetRow> = when (sort) {
        SortColumn.Address -> compareBy<OffsetRow> {
            parseAddress(it.addressHex) ?: Long.MAX_VALUE
        }
        SortColumn.Name -> compareBy<OffsetRow> { it.name.lowercase() }
        SortColumn.Category -> compareBy<OffsetRow> { it.category.name }
        SortColumn.Status -> compareBy<OffsetRow> { it.status.ordinal }
    }
    return if (ascending) base else base.reversed()
}

/** Category identifier a row belongs to, for the sidebar filter. */
private fun categoryOf(row: OffsetRow): String = when (row.category) {
    OffsetCategory.VTable -> "classes"
    OffsetCategory.Pattern -> "signatures"
    OffsetCategory.Symbol -> "symbols"
    OffsetCategory.InternalCpp -> "functions"
    OffsetCategory.VirtualSlot -> "slots"
}

private fun parseAddress(value: String): Long? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("0x")) return null
    return trimmed.removePrefix("0x").toLongOrNull(16)
}

@Composable
private fun PaneDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(Graphite700)
    )
}

@Composable
private fun CategorySidebar(
    result: ScanResultSummary,
    rows: List<OffsetRow>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val registerNatives = result.jni?.offsets?.count { it.source != "symbol" } ?: 0
    val categories = listOf(
        SidebarEntry(null, "All offsets", rows.size),
        SidebarEntry("symbols", "JNI Exports", result.jniSymbolCount),
        SidebarEntry("register", "RegisterNatives", registerNatives),
        SidebarEntry("classes", "RTTI Classes", result.vtables.entries.size),
        SidebarEntry("functions", "Internal Functions", result.internal?.functions ?: 0),
        SidebarEntry("slots", "Virtual Slots", result.internal?.vtableSlots ?: 0),
        SidebarEntry("signatures", "Signatures", result.internal?.signatures ?: 0),
        SidebarEntry("symbols", "Data Symbols", result.internal?.dataSymbols ?: 0)
    ).distinctBy { it.id to it.label }

    Column(modifier = modifier.padding(8.dp)) {
        Text(
            text = "WORKSPACE",
            style = Dense,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp)
        )
        categories.forEach { entry ->
            val isSelected = selected == entry.id
            val rowBackground = if (isSelected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(entry.id) }
                    .background(rowBackground, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = if (isSelected) {
                    Icons.Filled.ExpandMore
                } else {
                    Icons.Filled.ChevronRight
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.label,
                    style = Dense,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "%,d".format(entry.count),
                    style = Dense,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Graphite700)
        Spacer(Modifier.height(8.dp))
        Text(
            text = result.buildId.ifBlank { "unknown build" },
            style = Dense,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class SidebarEntry(val id: String?, val label: String, val count: Int)

@Composable
private fun GridHeader(sort: SortColumn, ascending: Boolean, onSort: (SortColumn) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell(
            text = sortLabel(SortColumn.Address, sort, ascending),
            weight = 1.2f,
            onClick = { onSort(SortColumn.Address) }
        )
        HeaderCell(
            text = sortLabel(SortColumn.Name, sort, ascending),
            weight = 2.4f,
            onClick = { onSort(SortColumn.Name) }
        )
        HeaderCell(
            text = sortLabel(SortColumn.Category, sort, ascending),
            weight = 1.0f,
            onClick = { onSort(SortColumn.Category) }
        )
        HeaderCell(
            text = sortLabel(SortColumn.Status, sort, ascending),
            weight = 0.9f,
            onClick = { onSort(SortColumn.Status) }
        )
        HeaderCell(text = "Detail", weight = 1.6f, onClick = {})
    }
}

private fun sortLabel(column: SortColumn, sort: SortColumn, ascending: Boolean): String =
    if (column == sort) "${column.label} ${if (ascending) "▲" else "▼"}" else column.label

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, onClick: () -> Unit) {
    Text(
        text = text,
        style = Dense,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight).clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridRow(row: OffsetRow, selected: Boolean, imageBase: Long, onSelect: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                )
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = {
                        onSelect()
                        menuOpen = true
                    }
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cell(row.addressHex, 1.2f, accent = true)
            Cell(row.name, 2.4f)
            Cell(row.category.name, 1.0f)
            Cell(row.status.name, 0.9f)
            Cell(row.detail, 1.6f)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val address = parseAddress(row.addressHex)
            val rva = address?.let { it - imageBase }
            DropdownMenuItem(
                text = { Text("Copy address") },
                enabled = address != null,
                onClick = {
                    clipboard.setText(AnnotatedString(row.addressHex))
                    menuOpen = false
                }
            )
            DropdownMenuItem(
                text = { Text("Copy RVA") },
                enabled = rva != null,
                onClick = {
                    clipboard.setText(AnnotatedString(rva?.let { "0x%x".format(it) }.orEmpty()))
                    menuOpen = false
                }
            )
            DropdownMenuItem(
                text = { Text("Copy signature") },
                onClick = {
                    clipboard.setText(AnnotatedString(row.detail))
                    menuOpen = false
                }
            )
            DropdownMenuItem(
                text = { Text("Copy C++ hook") },
                enabled = address != null,
                onClick = {
                    val hook = "// ${row.name}\nauto* target = reinterpret_cast<void*>(" +
                        (address?.let { "0x%x".format(it) } ?: "0") + ");"
                    clipboard.setText(AnnotatedString(hook))
                    menuOpen = false
                }
            )
        }
    }
    HorizontalDivider(color = Graphite700)
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, accent: Boolean = false) {
    Text(
        text = text,
        style = Dense,
        color = if (accent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight)
    )
}

@Composable
private fun WorkspaceActions(
    result: ScanResultSummary,
    query: String,
    onQueryChange: (String) -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onDownloadZip: () -> Unit,
    onStartNewScan: () -> Unit,
    onRetryInternal: () -> Unit,
    internalBusy: Boolean,
    internalAvailable: Boolean,
    internalRetryable: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().testTag(SEARCH_FIELD_TAG),
            singleLine = true,
            label = { Text("Search offsets") }
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopyJson) { Text("Copy JSON") }
            OutlinedButton(onClick = onShareJson) { Text("Share") }
            OutlinedButton(onClick = onSaveJson) { Text("Save") }
            OutlinedButton(onClick = onStartNewScan) { Text("New scan") }
            Spacer(Modifier.weight(1f))
            if (internalBusy) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
            } else if (!result.internalResolved && result.internalError != null) {
                Button(
                    onClick = onRetryInternal,
                    enabled = internalRetryable,
                    modifier = Modifier.testTag(DOWNLOAD_ZIP_TAG)
                ) { Text("Retry internal pass") }
            } else {
                Button(
                    onClick = onDownloadZip,
                    enabled = result.internalResolved && internalAvailable,
                    modifier = Modifier.testTag(DOWNLOAD_ZIP_TAG)
                ) { Text("Download ZIP") }
            }
        }
    }
}

@Composable
private fun InspectorPane(
    state: InspectorState,
    entries: List<LogEntry>,
    onSelectAddress: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Disassembly", "Xrefs", "Vtable", "Console")
    Column(modifier = modifier.testTag(WORKSPACE_INSPECTOR_TAG)) {
        Text(
            text = state.address?.let { "0x%x".format(it) } ?: "INSPECTOR",
            style = Dense,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(8.dp),
            maxLines = 1
        )
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label, style = Dense, maxLines = 1) }
                )
            }
        }
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            state.error != null -> Text(
                text = state.error,
                style = Dense,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(8.dp)
            )
            else -> when (tab) {
                0 -> DisassemblyTab(state.disassembly)
                1 -> XrefsTab(state.inspection, onSelectAddress)
                2 -> VtableTab(state.inspection)
                else -> ConsoleTab(entries)
            }
        }
    }
}

@Composable
private fun DisassemblyTab(disassembly: Disassembly?) {
    if (disassembly == null) {
        Text(
            text = "Select a row to decode its function.",
            style = Dense,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp)
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(disassembly.instructions) { instruction ->
            Row {
                Text(
                    text = "0x%08x".format(instruction.address),
                    style = Dense,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(92.dp)
                )
                Text(
                    text = instruction.mnemonic,
                    style = Dense,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(72.dp)
                )
                Text(
                    text = instruction.operands,
                    style = Dense,
                    modifier = Modifier.weight(1f)
                )
                instruction.annotation?.let { annotation ->
                    Text(
                        text = "; $annotation",
                        style = Dense,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun XrefsTab(inspection: AddressInspection?, onSelectAddress: (Long) -> Unit) {
    if (inspection == null) {
        Text(
            text = "Select a row to resolve its callers.",
            style = Dense,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp)
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        item { SectionLabel("Callers (${inspection.callers.size})") }
        items(inspection.callers) { address -> AddressLine(address, onSelectAddress) }
        item { SectionLabel("Callees (${inspection.callees.size})") }
        items(inspection.callees) { address -> AddressLine(address, onSelectAddress) }
        item { SectionLabel("Data references (${inspection.dataRefs.size})") }
        items(inspection.dataRefs) { address ->
            Text(text = "0x%x".format(address), style = Dense)
        }
        item { SectionLabel("Strings (${inspection.strings.size})") }
        items(inspection.strings) { text -> Text(text = text, style = Dense) }
    }
}

@Composable
private fun VtableTab(inspection: AddressInspection?) {
    if (inspection == null || inspection.vtables.isEmpty()) {
        Text(
            text = "No vtable holds this address.",
            style = Dense,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp)
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(inspection.vtables) { vtable ->
            Text(
                text = vtable.className,
                style = Dense,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "vtable ${"0x%x".format(vtable.vtable)} · slots ${
                    vtable.slots.joinToString(", ")
                }",
                style = Dense,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (vtable.baseClasses.isNotEmpty()) {
                Text(
                    text = "bases: ${vtable.baseClasses.joinToString(", ")}",
                    style = Dense,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConsoleTab(entries: List<LogEntry>) {
    val visible = entries.takeLast(200)
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(visible) { entry -> Text(text = entry.render(), style = Dense) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = Dense,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun AddressLine(address: Long, onSelectAddress: (Long) -> Unit) {
    Text(
        text = "0x%x".format(address),
        style = Dense,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectAddress(address) }
            .padding(vertical = 1.dp)
    )
}
