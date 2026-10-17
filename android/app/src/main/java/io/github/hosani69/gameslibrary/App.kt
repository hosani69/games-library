@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.hosani69.gameslibrary

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate

private fun money(v: Double): String = NumberFormat.getCurrencyInstance().format(v)
private fun parseMoney(s: String): Double? = s.replace(',', '.').trim().toDoubleOrNull()
private fun moneyText(v: Double?): String = v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.2f".format(it) } ?: ""

@Composable
fun App(vm: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val capture by vm.capture.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf<Kind?>(null) }

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

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    val open = items.firstOrNull { it.id == openId }
    when {
        capture is Capture.Working -> WorkingScreen((capture as Capture.Working).message)
        capture is Capture.Review -> ReviewScreen(vm, (capture as Capture.Review).drafts, onRetake = { vm.discardCapture(); takePhoto() })
        capture is Capture.Failed -> FailedScreen((capture as Capture.Failed).message, onRetry = { vm.discardCapture(); takePhoto() }, onClose = vm::discardCapture)
        open != null -> DetailScreen(vm, open, onBack = { openId = null })
        else -> LibraryScreen(
            vm, items, filter, snackbar,
            onFilter = { filter = it },
            onOpen = { openId = it.id },
            onCapture = takePhoto,
            onBackup = { backupLauncher.launch("games-library-backup-${LocalDate.now()}.zip") },
            onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        )
    }
}

/* ---------------- Library ---------------- */

@Composable
private fun LibraryScreen(
    vm: LibraryViewModel,
    items: List<Item>,
    filter: Kind?,
    snackbar: SnackbarHostState,
    onFilter: (Kind?) -> Unit,
    onOpen: (Item) -> Unit,
    onCapture: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shown = items.filter { filter == null || it.kind == filter }.sortedByDescending { it.logDate }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Library", fontWeight = FontWeight.SemiBold)
                        if (items.isNotEmpty()) {
                            Text(
                                "${shown.size} items · ${money(shown.sumOf { it.total })}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Back up to a file") }, onClick = { menu = false; onBackup() })
                        DropdownMenuItem(text = { Text("Restore from a file") }, onClick = { menu = false; onRestore() })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = onCapture,
                shape = RoundedCornerShape(50),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Filled.Add, "Take a photo", modifier = Modifier.size(36.dp)) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Chip("All", filter == null) { onFilter(null) } }
                items(Kind.entries) { k -> Chip(k.plural, filter == k) { onFilter(k) } }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (items.isEmpty()) "Tap + and take a photo.\nPut several items in one photo to add them all at once."
                        else "Nothing here yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
            Text(message, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Take another photo") }
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    }
}

@Composable
private fun ReviewScreen(vm: LibraryViewModel, drafts: List<Draft>, onRetake: () -> Unit) {
    BackHandler(onBack = vm::discardCapture)
    var cost by rememberSaveable { mutableStateOf("") }
    var shipping by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Draft?>(null) }
    val kept = drafts.count { it.keep }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (drafts.size == 1) "Found 1 item" else "Found ${drafts.size} items") },
                navigationIcon = { IconButton(onClick = vm::discardCapture) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = { TextButton(onClick = onRetake) { Text("Retake") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().imePadding()
                    .padding(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoneyField("Paid", cost, Modifier.weight(1f)) { cost = it }
                    MoneyField("Shipping", shipping, Modifier.weight(1f)) { shipping = it }
                }
                if (kept > 1 && (cost.isNotBlank() || shipping.isNotBlank())) {
                    Text(
                        "Split evenly across $kept items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.addDrafts(parseMoney(cost), parseMoney(shipping)) },
                    enabled = kept > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (kept == 1) "Add 1 item" else "Add $kept items", fontSize = 16.sp) }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(drafts, key = { it.id }) { d ->
                Row {
                    Box {
                        AsyncImage(
                            model = vm.photoOf(d),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(84.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp))
                                .clickable { vm.editDraft(d.id) { it.copy(keep = !it.keep) } },
                        )
                        Checkbox(checked = d.keep, onCheckedChange = { k -> vm.editDraft(d.id) { it.copy(keep = k) } })
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.title.ifBlank { "Tap to name" },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (d.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable { renaming = d },
                        )
                        if (d.info.isNotBlank()) {
                            Text(d.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Choices(Kind.entries, d.kind, { it.label }) { k -> vm.editDraft(d.id) { it.copy(kind = k) } }
                        Choices(Condition.entries, d.condition, { it.label }) { c -> vm.editDraft(d.id) { it.copy(condition = c) } }
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
private fun DetailScreen(vm: LibraryViewModel, item: Item, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var cost by remember(item.id) { mutableStateOf(moneyText(item.cost)) }
    var shipping by remember(item.id) { mutableStateOf(moneyText(item.shipping)) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneyField("Paid", cost, Modifier.weight(1f)) { cost = it; vm.update(item.copy(cost = parseMoney(it))) }
                MoneyField("Shipping", shipping, Modifier.weight(1f)) { shipping = it; vm.update(item.copy(shipping = parseMoney(it))) }
            }
            if (item.total > 0) {
                Text("Total ${money(item.total)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
        Spacer(Modifier.height(4.dp))
        content()
    }
}
