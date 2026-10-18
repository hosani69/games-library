@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hosani69.gameslibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** What the library list is narrowed down to. Empty sets mean "any". */
data class Filters(
    val kinds: Set<Kind> = emptySet(),
    val conditions: Set<Condition> = emptySet(),
    val progress: Set<Progress> = emptySet(),
    val platforms: Set<String> = emptySet(),
    val noValue: Boolean = false,
) {
    val count get() = kinds.size + conditions.size + progress.size + platforms.size + (if (noValue) 1 else 0)
}

private fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v

/** Filters, sorts and groups the library. Returns (group title, items) pairs; title is null when not grouped. */
fun browse(items: List<Item>, query: String, f: Filters, s: Settings): List<Pair<String?, List<Item>>> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val shown = items.filter { i ->
        (f.kinds.isEmpty() || i.kind in f.kinds) &&
            (f.conditions.isEmpty() || i.condition in f.conditions) &&
            (f.progress.isEmpty() || (i.kind == Kind.GAME && i.progress in f.progress)) &&
            (f.platforms.isEmpty() || i.platform in f.platforms) &&
            (!f.noValue || i.market == null) &&
            (words.isEmpty() || listOf(i.title, i.platform, i.creator, i.publisher, i.genre, i.info, i.kind.label, i.released)
                .joinToString(" ").lowercase().let { hay -> words.all { it in hay } })
    }
    fun paid(i: Item) = s.convert(i.total, i.currency)
    fun value(i: Item) = i.market ?: paid(i)
    fun year(i: Item) = Regex("\\d{4}").find(i.released)?.value ?: ""
    val sorted = when (s.sort) {
        Sort.NEWEST -> shown.sortedWith(compareByDescending<Item> { it.logDate }.thenBy { it.title.lowercase() })
        Sort.OLDEST -> shown.sortedWith(compareBy<Item> { it.logDate }.thenBy { it.title.lowercase() })
        Sort.TITLE_AZ -> shown.sortedBy { it.title.lowercase() }
        Sort.TITLE_ZA -> shown.sortedByDescending { it.title.lowercase() }
        Sort.PAID_HIGH -> shown.sortedByDescending { paid(it) }
        Sort.PAID_LOW -> shown.sortedBy { paid(it) }
        Sort.VALUE_HIGH -> shown.sortedByDescending { value(it) }
        Sort.VALUE_LOW -> shown.sortedBy { value(it) }
        Sort.RELEASE_NEW -> shown.sortedByDescending { it.released.ifBlank { "0" } }
        Sort.RELEASE_OLD -> shown.sortedBy { it.released.ifBlank { "9999" } }
    }
    if (s.group == Group.NONE) return listOf(null to sorted)
    val unknown = "Unknown"
    val key: (Item) -> String = when (s.group) {
        Group.TYPE -> { i -> i.kind.plural }
        Group.PLATFORM -> { i -> i.platform.ifBlank { unknown } }
        Group.CONDITION -> { i -> i.condition.label }
        Group.PROGRESS -> { i -> if (i.kind == Kind.GAME) i.progress.label else "Not a game" }
        Group.GENRE -> { i -> i.genre.substringBefore(',').trim().ifBlank { unknown }.replaceFirstChar { it.uppercase() } }
        Group.CREATOR -> { i -> i.creator.substringBefore(',').trim().ifBlank { unknown } }
        Group.RELEASE_YEAR -> { i -> year(i).ifBlank { unknown } }
        Group.ADDED_MONTH -> { i ->
            runCatching { YearMonth.parse(i.logDate.take(7)).format(DateTimeFormatter.ofPattern("MMMM yyyy")) }.getOrDefault(unknown)
        }
        Group.NONE -> { _ -> "" }
    }
    val groups = sorted.groupBy(key)
    val order: List<String> = when (s.group) {
        Group.TYPE -> Kind.entries.map { it.plural }
        Group.CONDITION -> Condition.entries.map { it.label }
        Group.PROGRESS -> Progress.entries.map { it.label } + "Not a game"
        Group.RELEASE_YEAR -> groups.keys.filter { it != unknown }.sortedDescending() + unknown
        Group.ADDED_MONTH -> groups.keys.sortedByDescending { k -> sorted.first { key(it) == k }.logDate }
        else -> groups.keys.filter { it != unknown }.sortedBy { it.lowercase() } + unknown
    }
    return order.mapNotNull { k -> groups[k]?.let { k to it } }
}

@Composable
fun LibraryTab(
    vm: LibraryViewModel,
    items: List<Item>,
    s: Settings,
    query: String,
    filters: Filters,
    modifier: Modifier,
    onQuery: (String) -> Unit,
    onFilters: (Filters) -> Unit,
    onOpen: (Item) -> Unit,
) {
    var showFilters by remember { mutableStateOf(false) }
    var showView by remember { mutableStateOf(false) }
    val groups = browse(items, query, filters, s)
    val count = groups.sumOf { it.second.size }

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search title, platform, developer…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, "Clear") } },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showFilters = true }) { Text(if (filters.count > 0) "Filter · ${filters.count}" else "Filter") }
            TextButton(onClick = { showView = true }) { Text("Sort & view") }
            Spacer(Modifier.weight(1f))
            if (filters.count > 0) TextButton(onClick = { onFilters(Filters()) }) { Text("Clear") }
        }
        if (count == 0) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (items.isEmpty()) "Add your first item with the camera or a picture from your gallery.\nOne item per picture."
                    else "Nothing matches your search or filters.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val cells = when (s.view) {
                ViewMode.GRID -> GridCells.Adaptive(160.dp)
                ViewMode.SMALL_GRID -> GridCells.Adaptive(104.dp)
                ViewMode.LIST, ViewMode.COMPACT -> GridCells.Fixed(1)
            }
            val grid = s.view == ViewMode.GRID || s.view == ViewMode.SMALL_GRID
            LazyVerticalGrid(
                columns = cells,
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (grid) 16.dp else 0.dp),
            ) {
                groups.forEach { (title, list) ->
                    if (title != null) {
                        item(key = "h:$title", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "$title · ${list.size}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                    items(list, key = { it.id }) { item ->
                        when (s.view) {
                            ViewMode.GRID -> GridCard(vm, item, s, small = false) { onOpen(item) }
                            ViewMode.SMALL_GRID -> GridCard(vm, item, s, small = true) { onOpen(item) }
                            ViewMode.LIST -> ListRow(vm, item, s) { onOpen(item) }
                            ViewMode.COMPACT -> CompactRow(item) { onOpen(item) }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            FilterSheet(items, filters, onFilters)
        }
    }
    if (showView) {
        ModalBottomSheet(onDismissRequest = { showView = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Section("View") { Choices(ViewMode.entries, { it == s.view }, { it.label }) { vm.setView(it) } }
                Section("Sort by") { Choices(Sort.entries, { it == s.sort }, { it.label }) { vm.setSort(it) } }
                Section("Group by") { Choices(Group.entries, { it == s.group }, { it.label }) { vm.setGroup(it) } }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun FilterSheet(items: List<Item>, f: Filters, onChange: (Filters) -> Unit) {
    val platforms = items.map { it.platform }.filter { it.isNotBlank() }.distinct().sorted()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Section("Type") { Choices(Kind.entries, { it in f.kinds }, { it.plural }) { onChange(f.copy(kinds = f.kinds.toggle(it))) } }
        if (platforms.isNotEmpty()) {
            Section("Platform") { Choices(platforms, { it in f.platforms }, { it }) { onChange(f.copy(platforms = f.platforms.toggle(it))) } }
        }
        Section("Condition") { Choices(Condition.entries, { it in f.conditions }, { it.label }) { onChange(f.copy(conditions = f.conditions.toggle(it))) } }
        Section("Game progress") { Choices(Progress.entries, { it in f.progress }, { it.label }) { onChange(f.copy(progress = f.progress.toggle(it))) } }
        Section("Market value") { Choices(listOf("Not set yet"), { f.noValue }, { it }) { onChange(f.copy(noValue = !f.noValue)) } }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onChange(Filters()) }, enabled = f.count > 0) { Text("Clear all") }
        }
    }
}

private fun subtitle(i: Item): String = buildList {
    add(i.platform.ifBlank { i.kind.label })
    add(i.condition.label)
    if (i.kind == Kind.GAME && i.progress != Progress.NOT_STARTED) add(i.progress.label)
}.joinToString(" · ")

@Composable
private fun Photo(vm: LibraryViewModel, item: Item, modifier: Modifier) {
    AsyncImage(
        model = vm.photoOf(item),
        contentDescription = item.title,
        contentScale = ContentScale.Crop,
        modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun GridCard(vm: LibraryViewModel, item: Item, s: Settings, small: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Photo(vm, item, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(
            item.title, maxLines = if (small) 1 else 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        )
        if (!small) {
            Text(subtitle(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListRow(vm: LibraryViewModel, item: Item, s: Settings, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Photo(vm, item, Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(subtitle(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.total > 0 || item.market != null) {
                Column(horizontalAlignment = Alignment.End) {
                    if (item.total > 0) Text(money(s.convert(item.total, item.currency), s.currency), style = MaterialTheme.typography.bodyMedium)
                    item.market?.let { Text("Value ${money(it, s.currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CompactRow(item: Item, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
