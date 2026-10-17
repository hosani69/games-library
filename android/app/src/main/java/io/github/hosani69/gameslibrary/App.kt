@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.hosani69.gameslibrary

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Currency

private fun money(v: Double, code: String): String = runCatching {
    NumberFormat.getCurrencyInstance().apply { currency = Currency.getInstance(code) }.format(v)
}.getOrElse { "$code %.2f".format(v) }

private fun parseMoney(s: String): Double? = s.replace(',', '.').trim().toDoubleOrNull()
private fun moneyText(v: Double?): String = v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.2f".format(java.util.Locale.US, it) } ?: ""

private enum class Tab(val label: String) { LIBRARY("Library"), ANALYTICS("Analytics"), SETTINGS("Settings") }

@Composable
fun App(vm: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val settings by vm.settings.collectAsState()
    val capture by vm.capture.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf<Kind?>(null) }
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) vm.process(vm.newCaptureFile(), filter ?: Kind.GAME)
    }
    val takePhoto = {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", vm.newCaptureFile())
        camera.launch(uri)
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) vm.backup(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.restore(uri)
    }
    val reportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) vm.exportReport(uri)
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    val open = items.firstOrNull { it.id == openId }
    when (val c = capture) {
        is Capture.Working -> WorkingScreen(c.message)
        is Capture.Review -> ReviewScreen(vm, c, settings, onRetake = { vm.discardCapture(); takePhoto() })
        is Capture.Failed -> FailedScreen(c.message, onRetry = { vm.discardCapture(); takePhoto() }, onClose = vm::discardCapture)
        Capture.Idle -> if (open != null) {
            DetailScreen(vm, open, settings, onBack = { openId = null })
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(tab.label, fontWeight = FontWeight.SemiBold)
                                if (tab == Tab.LIBRARY && items.isNotEmpty()) {
                                    val shown = items.filter { filter == null || it.kind == filter }
                                    Text(
                                        "${shown.size} items · ${money(shown.sumOf { settings.convert(it.total, it.currency) }, settings.currency)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = {
                                    Icon(
                                        when (t) {
                                            Tab.LIBRARY -> Icons.Filled.Home
                                            Tab.ANALYTICS -> Icons.AutoMirrored.Filled.List
                                            Tab.SETTINGS -> Icons.Filled.Settings
                                        },
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                            )
                        }
                    }
                },
                floatingActionButton = {
                    if (tab == Tab.LIBRARY) {
                        LargeFloatingActionButton(
                            onClick = takePhoto,
                            shape = RoundedCornerShape(50),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) { Icon(Icons.Filled.Add, "Take a photo", modifier = Modifier.size(36.dp)) }
                    }
                },
            ) { pad ->
                val m = Modifier.padding(pad).fillMaxSize()
                when (tab) {
                    Tab.LIBRARY -> LibraryTab(vm, items, filter, m, onFilter = { filter = it }, onOpen = { openId = it.id })
                    Tab.ANALYTICS -> AnalyticsTab(items, settings, m, onExport = { reportLauncher.launch("games-library-report-${LocalDate.now()}.csv") })
                    Tab.SETTINGS -> SettingsTab(
                        vm, settings, m,
                        onBackup = { backupLauncher.launch("games-library-backup-${LocalDate.now()}.zip") },
                        onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    )
                }
            }
        }
    }
}

/* ---------------- Library ---------------- */

@Composable
private fun LibraryTab(
    vm: LibraryViewModel,
    items: List<Item>,
    filter: Kind?,
    modifier: Modifier,
    onFilter: (Kind?) -> Unit,
    onOpen: (Item) -> Unit,
) {
    val shown = items.filter { filter == null || it.kind == filter }.sortedByDescending { it.logDate }
    Column(modifier) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("All", filter == null) { onFilter(null) } }
            items(Kind.entries) { k -> Chip(k.plural, filter == k) { onFilter(k) } }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (items.isEmpty()) "Tap + and take a photo.\nLay several items side by side to add them all at once."
                    else "Nothing here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(shown, key = { it.id }) { item -> ItemCard(vm, item) { onOpen(item) } }
            }
        }
    }
}

@Composable
private fun ItemCard(vm: LibraryViewModel, item: Item, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = vm.photoOf(item),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(6.dp))
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        val sub = buildList {
            add(item.condition.label)
            if (item.kind == Kind.GAME && item.progress != Progress.NOT_STARTED) add(item.progress.label)
        }.joinToString(" · ")
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/* ---------------- Analytics ---------------- */

@Composable
private fun AnalyticsTab(items: List<Item>, s: Settings, modifier: Modifier, onExport: () -> Unit) {
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

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TableRow(a: String, b: String, c: String, d: String, header: Boolean = false) {
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val style = if (header) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(a, Modifier.weight(1.2f), color = color, style = style)
        Text(b, Modifier.weight(0.6f), color = color, style = style, textAlign = TextAlign.End)
        Text(c, Modifier.weight(1.3f), color = color, style = style, textAlign = TextAlign.End)
        Text(d, Modifier.weight(1.3f), color = color, style = style, textAlign = TextAlign.End)
    }
}

@Composable
private fun Bar(label: String, n: Int, of: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { if (of == 0) 0f else n.toFloat() / of },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Text(n.toString(), Modifier.width(40.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------------- Settings ---------------- */

@Composable
private fun SettingsTab(vm: LibraryViewModel, s: Settings, modifier: Modifier, onBackup: () -> Unit, onRestore: () -> Unit) {
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
                "Free and private. Items are recognised on the phone (Google ML Kit). Titles from Wikipedia. " +
                    "Rates by ExchangeRate-API (open.er-api.com).",
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

@Composable
private fun CurrencyDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Currency") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(Currencies.codes) { code ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 12.dp)) {
                        Text(code, Modifier.weight(1f), fontWeight = if (code == current) FontWeight.Bold else FontWeight.Normal)
                        Text(runCatching { Currency.getInstance(code).displayName }.getOrDefault(""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CurrencyButton(code: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(56.dp).padding(top = 6.dp)) {
        Text(code)
    }
    if (open) CurrencyDialog(code, onDismiss = { open = false }) { onPick(it); open = false }
}

/* ---------------- Capture ---------------- */

@Composable
private fun WorkingScreen(message: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(Modifier.width(180.dp))
        }
    }
}

@Composable
private fun FailedScreen(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Take another photo") }
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    }
}

@Composable
private fun ReviewScreen(vm: LibraryViewModel, c: Capture.Review, settings: Settings, onRetake: () -> Unit) {
    BackHandler(onBack = vm::discardCapture)
    var cost by rememberSaveable { mutableStateOf("") }
    var shipping by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf(settings.currency) }
    var renaming by remember { mutableStateOf<Draft?>(null) }
    val kept = c.drafts.count { it.keep }
    val photo = remember(c.photo) { c.photo.asImageBitmap() }
    val boxColor = MaterialTheme.colorScheme.primary
    val labelBg = MaterialTheme.colorScheme.primary.toArgb()
    val labelFg = MaterialTheme.colorScheme.onPrimary.toArgb()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (c.drafts.size == 1) "Found 1 item" else "Found ${c.drafts.size} items") },
                navigationIcon = { IconButton(onClick = vm::discardCapture) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = { TextButton(onClick = onRetake) { Text("Retake") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().imePadding().padding(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MoneyField("Paid", cost, Modifier.weight(1f)) { cost = it }
                    MoneyField("Shipping", shipping, Modifier.weight(1f)) { shipping = it }
                    CurrencyButton(currency) { currency = it }
                }
                if (kept > 1 && (cost.isNotBlank() || shipping.isNotBlank())) {
                    Text(
                        "Totals for the whole photo, split evenly across $kept items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.addDrafts(parseMoney(cost), parseMoney(shipping), currency) },
                    enabled = kept > 0 && !c.reading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (c.reading) "Reading covers…" else if (kept == 1) "Add 1 item" else "Add $kept items", fontSize = 16.sp) }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.heightIn(max = 280.dp)
                            .aspectRatio(c.photo.width.toFloat() / c.photo.height, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(10.dp)),
                    ) {
                        Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            val f = size.width / c.photo.width
                            val paint = android.graphics.Paint().apply { textSize = 14.dp.toPx(); isAntiAlias = true; isFakeBoldText = true }
                            c.boxes.forEachIndexed { i, r ->
                                drawRect(boxColor, Offset(r.left * f, r.top * f), Size(r.width() * f, r.height() * f), style = Stroke(2.5.dp.toPx()))
                                val label = "${i + 1}"
                                val pad2 = 4.dp.toPx()
                                val tw = paint.measureText(label)
                                paint.color = labelBg
                                drawContext.canvas.nativeCanvas.drawRect(r.left * f, r.top * f, r.left * f + tw + pad2 * 2, r.top * f + paint.textSize + pad2 * 2, paint)
                                paint.color = labelFg
                                drawContext.canvas.nativeCanvas.drawText(label, r.left * f + pad2, r.top * f + paint.textSize + pad2 / 2, paint)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Items laid out wrong? Pick how they sit in the photo:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Layout.choices) { l -> Chip(l.label, l == c.layout) { vm.relayout(l) } }
                }
            }
            items(c.drafts.size) { index ->
                val d = c.drafts[index]
                Row {
                    Box {
                        if (d.photo.isNotEmpty()) {
                            AsyncImage(
                                model = vm.photoOf(d),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(84.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.editDraft(d.id) { it.copy(keep = !it.keep) } },
                            )
                        } else {
                            Box(Modifier.width(84.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                        }
                        Checkbox(checked = d.keep, onCheckedChange = { k -> vm.editDraft(d.id) { it.copy(keep = k) } })
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                d.photo.isEmpty() -> "${index + 1}. Reading…"
                                d.title.isBlank() -> "${index + 1}. Tap to name"
                                else -> "${index + 1}. ${d.title}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (d.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable(enabled = d.photo.isNotEmpty()) { renaming = d },
                        )
                        if (d.info.isNotBlank()) {
                            Text(d.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Choices(Kind.entries, d.kind, { it.label }) { k -> vm.editDraft(d.id) { it.copy(kind = k) } }
                        Choices(Condition.entries, d.condition, { it.label }) { cc -> vm.editDraft(d.id) { it.copy(condition = cc) } }
                    }
                }
            }
        }
    }

    renaming?.let { d ->
        RenameDialog(d.title, onDismiss = { renaming = null }) { name ->
            vm.editDraft(d.id) { it.copy(title = name) }
            renaming = null
        }
    }
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> Chip(label(o), o == selected) { onPick(o) } }
    }
}

@Composable
private fun MoneyField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/* ---------------- Item ---------------- */

@Composable
private fun DetailScreen(vm: LibraryViewModel, item: Item, s: Settings, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val uri = LocalUriHandler.current
    var cost by remember(item.id) { mutableStateOf(moneyText(item.cost)) }
    var shipping by remember(item.id) { mutableStateOf(moneyText(item.shipping)) }
    var market by remember(item.id) { mutableStateOf(moneyText(item.market)) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AsyncImage(
                model = vm.photoOf(item),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.clickable { renaming = true }) {
                Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                val sub = listOf(item.info, "Logged ${item.logDate}").filter { it.isNotBlank() }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Choices(Kind.entries, item.kind, { it.label }) { vm.update(item.copy(kind = it)) }
            Section("Condition") { Choices(Condition.entries, item.condition, { it.label }) { vm.update(item.copy(condition = it)) } }
            if (item.kind == Kind.GAME) {
                Section("Progress") { Choices(Progress.entries, item.progress, { it.label }) { vm.update(item.copy(progress = it)) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MoneyField("Paid", cost, Modifier.weight(1f)) { cost = it; vm.update(item.copy(cost = parseMoney(it))) }
                MoneyField("Shipping", shipping, Modifier.weight(1f)) { shipping = it; vm.update(item.copy(shipping = parseMoney(it))) }
                CurrencyButton(item.currency) { vm.update(item.copy(currency = it)) }
            }
            if (item.total > 0) {
                val conv = if (item.currency != s.currency) " (${money(s.convert(item.total, item.currency), s.currency)})" else ""
                Text("Total ${money(item.total, item.currency)}$conv", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoneyField("Market value (${s.currency})", market, Modifier.fillMaxWidth()) {
                market = it; vm.update(item.copy(market = parseMoney(it)))
            }
            TextButton(onClick = {
                val q = URLEncoder.encode(item.title, "UTF-8")
                uri.openUri(
                    if (item.kind == Kind.GAME || item.kind == Kind.CONSOLE) "https://www.pricecharting.com/search-products?type=prices&q=$q"
                    else "https://www.ebay.com/sch/i.html?LH_Sold=1&LH_Complete=1&_nkw=$q"
                )
            }) { Text("Check the price online ↗") }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (renaming) {
        RenameDialog(item.title, onDismiss = { renaming = false }) {
            if (it.isNotBlank()) vm.update(item.copy(title = it))
            renaming = false
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${item.title}”?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onBack(); vm.delete(item) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        content()
    }
}
