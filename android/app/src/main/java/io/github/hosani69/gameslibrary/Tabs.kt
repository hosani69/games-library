package io.github.hosani69.gameslibrary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/* ---------------- Analytics ---------------- */

@Composable
fun AnalyticsTab(items: List<Item>, s: Settings, modifier: Modifier, onExport: () -> Unit) {
    if (items.isEmpty()) {
        Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Add a few items to see your numbers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val cur = s.currency
    fun cost(i: Item) = s.convert(i.total, i.currency)
    fun value(i: Item) = i.market ?: cost(i)
    val totalCost = items.sumOf { cost(it) }
    val shipping = items.sumOf { s.convert(it.shipping ?: 0.0, it.currency) }
    val market = items.sumOf { value(it) }
    val unvalued = items.count { it.market == null }
    val gain = market - totalCost
    val games = items.filter { it.kind == Kind.GAME }
    val finished = games.count { it.progress == Progress.FINISHED }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Total cost", money(totalCost, cur), Modifier.weight(1f))
            Stat("Market value", money(market, cur), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val pct = if (totalCost > 0) " (${"%+.0f".format(gain / totalCost * 100)}%)" else ""
            Stat(if (gain >= 0) "Gain" else "Loss", money(kotlin.math.abs(gain), cur) + pct, Modifier.weight(1f))
            Stat("Items", items.size.toString(), Modifier.weight(1f))
        }
        Text(
            buildString {
                append("Includes ${money(shipping, cur)} shipping. ")
                if (unvalued > 0) append("$unvalued of ${items.size} items have no market value yet and count at what you paid. Set it on each item.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("By type") {
            TableRow("", "Items", "Cost", "Value", header = true)
            Kind.entries.forEach { k ->
                val list = items.filter { it.kind == k }
                if (list.isNotEmpty()) TableRow(k.plural, list.size.toString(), money(list.sumOf { cost(it) }, cur), money(list.sumOf { value(it) }, cur))
            }
        }
        val platforms = items.filter { it.platform.isNotBlank() }.groupBy { it.platform }.entries.sortedByDescending { it.value.size }.take(8)
        if (platforms.isNotEmpty()) {
            Section("By platform") {
                platforms.forEach { (p, list) -> Bar(p, list.size, items.size) }
            }
        }
        Section("Condition") {
            Condition.entries.forEach { c ->
                val n = items.count { it.condition == c }
                if (n > 0) Bar(c.label, n, items.size)
            }
        }
        if (games.isNotEmpty()) {
            Section("Games finished: $finished of ${games.size}") {
                Progress.entries.forEach { p -> Bar(p.label, games.count { it.progress == p }, games.size) }
            }
        }
        Section("Most valuable") {
            items.sortedByDescending { value(it) }.take(5).forEach { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(i.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(money(value(i), cur), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export report (spreadsheet)") }
        Spacer(Modifier.height(24.dp))
    }
}




/* ---------------- Settings ---------------- */

@Composable
fun SettingsTab(vm: LibraryViewModel, s: Settings, modifier: Modifier, onBackup: () -> Unit, onRestore: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
        item {
            Section("Currency") {
                Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 10.dp)) {
                    Text("Main currency", Modifier.weight(1f))
                    Text(s.currency, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Exchange rates", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (s.ratesDate.isBlank()) "Built-in rates. Update when online." else "Updated ${s.ratesDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.updateRates() }) { Text("Update now") }
            }
            Text("Tap a rate to set your own.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(Currencies.codes.filter { it != s.currency }) { code ->
            Row(Modifier.fillMaxWidth().clickable { editing = code }.padding(vertical = 10.dp)) {
                Text(code, Modifier.weight(1f))
                Text(
                    "1 ${s.currency} = ${"%.4f".format(s.convert(1.0, s.currency, code))} $code",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Spacer(Modifier.height(24.dp))
            Section("Backup") {
                Text(
                    "Your library lives only on this phone. Save a backup file (with photos) somewhere safe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onBackup, modifier = Modifier.weight(1f)) { Text("Back up") }
                    OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) { Text("Restore") }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Free and private. Pictures are read on the phone (Google ML Kit). Details from Wikipedia, Wikidata, " +
                    "Open Library and MusicBrainz. Rates by ExchangeRate-API (open.er-api.com).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (picking) CurrencyDialog(s.currency, onDismiss = { picking = false }) { vm.setCurrency(it); picking = false }
    editing?.let { code ->
        var text by remember(code) { mutableStateOf("%.4f".format(java.util.Locale.US, s.convert(1.0, s.currency, code))) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("1 ${s.currency} = ? $code") },
            text = {
                OutlinedTextField(
                    text, { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' } },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    parseMoney(text)?.takeIf { it > 0 }?.let { vm.setRate(code, it) }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}




