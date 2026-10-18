@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hosani69.gameslibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.net.URLEncoder

/** One screen for adding a new item (after a picture) and for viewing or editing an existing one. */
@Composable
fun EditorScreen(vm: LibraryViewModel, e: Editor, s: Settings) {
    val item = e.item
    val uri = LocalUriHandler.current
    var cost by remember(item.id) { mutableStateOf(moneyText(item.cost)) }
    var shipping by remember(item.id) { mutableStateOf(moneyText(item.shipping)) }
    var market by remember(item.id) { mutableStateOf(moneyText(item.market)) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showMatches by remember(item.id) { mutableStateOf(false) }
    BackHandler(onBack = vm::closeEditor)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (e.isNew) "New item" else "") },
                navigationIcon = { IconButton(onClick = vm::closeEditor) { Icon(Icons.Filled.Close, "Close without saving") } },
                actions = {
                    if (!e.isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    Button(onClick = vm::saveEditor, modifier = Modifier.padding(end = 8.dp)) { Text(if (e.isNew) "Add" else "Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = vm.photoOf(item),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }

            // Title with search: the picture's text is a starting point, the internet fills the rest.
            OutlinedTextField(
                value = item.title,
                onValueChange = { t -> vm.edit { it.copy(title = t) } },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.searchAgain(); showMatches = true }, enabled = !e.searching && item.title.isNotBlank()) {
                    Text("Search online")
                }
                if (e.matches.size > 1) {
                    TextButton(onClick = { showMatches = !showMatches }) { Text(if (showMatches) "Hide matches" else "Not right? ${e.matches.size} matches") }
                }
            }
            if (e.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (showMatches && e.matches.isNotEmpty() && !e.searching) {
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    e.matches.forEachIndexed { i, m ->
                        Column(Modifier.fillMaxWidth().clickable { vm.pick(m); showMatches = false }.padding(14.dp)) {
                            Text(m.title, fontWeight = FontWeight.Medium)
                            Text(
                                listOf(m.info, m.creator, m.released.take(4), m.source).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (i < e.matches.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            if (item.info.isNotBlank()) {
                Text(item.info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Type") { Choices(Kind.entries, { it == item.kind }, { it.label }) { k -> vm.edit { it.copy(kind = k) } } }
            Section("Condition") { Choices(Condition.entries, { it == item.condition }, { it.label }) { c -> vm.edit { it.copy(condition = c) } } }
            if (item.kind == Kind.GAME) {
                Section("Progress") { Choices(Progress.entries, { it == item.progress }, { it.label }) { p -> vm.edit { it.copy(progress = p) } } }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MoneyField("Paid", cost, Modifier.weight(1f)) { v -> cost = v; vm.edit { it.copy(cost = parseMoney(v)) } }
                MoneyField("Shipping", shipping, Modifier.weight(1f)) { v -> shipping = v; vm.edit { it.copy(shipping = parseMoney(v)) } }
                CurrencyButton(item.currency) { c -> vm.edit { it.copy(currency = c) } }
            }
            if (item.total > 0) {
                val conv = if (item.currency != s.currency) " (${money(s.convert(item.total, item.currency), s.currency)})" else ""
                Text("Total ${money(item.total, item.currency)}$conv", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoneyField("Market value (${s.currency})", market, Modifier.fillMaxWidth()) { v -> market = v; vm.edit { it.copy(market = parseMoney(v)) } }
            TextButton(onClick = {
                val q = URLEncoder.encode(listOf(item.title, item.platform).filter { it.isNotBlank() }.joinToString(" "), "UTF-8")
                uri.openUri(
                    if (item.kind == Kind.GAME || item.kind == Kind.CONSOLE || item.kind == Kind.ACCESSORY) "https://www.pricecharting.com/search-products?type=prices&q=$q"
                    else "https://www.ebay.com/sch/i.html?LH_Sold=1&LH_Complete=1&_nkw=$q"
                )
            }) { Text("Check the price online ↗") }

            Section("Details (filled from the internet, editable)") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Detail(if (item.kind == Kind.BOOK || item.kind == Kind.OST) "Format" else "Platform", item.platform) { v -> vm.edit { it.copy(platform = v) } }
                    Detail(creatorLabel(item.kind), item.creator) { v -> vm.edit { it.copy(creator = v) } }
                    Detail("Publisher", item.publisher) { v -> vm.edit { it.copy(publisher = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Detail("Released", item.released, Modifier.weight(1f)) { v -> vm.edit { it.copy(released = v) } }
                        Detail("Genre", item.genre, Modifier.weight(1f)) { v -> vm.edit { it.copy(genre = v) } }
                    }
                }
            }
            Text("Logged ${item.logDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${item.title}”?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteEditing() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun creatorLabel(k: Kind) = when (k) {
    Kind.GAME -> "Developer"
    Kind.BOOK -> "Author"
    Kind.OST -> "Composer / artist"
    else -> "Maker"
}

@Composable
private fun Detail(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier)
}
