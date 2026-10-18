@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.hosani69.gameslibrary

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate

private enum class Tab(val label: String) { LIBRARY("Library"), ANALYTICS("Analytics"), SETTINGS("Settings") }

@Composable
fun App(vm: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val settings by vm.settings.collectAsState()
    val capture by vm.capture.collectAsState()
    val editor by vm.editor.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    var query by rememberSaveable { mutableStateOf("") }
    var filters by remember { mutableStateOf(Filters()) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) vm.process(vm.newCaptureFile())
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.processPicked(uri)
    }
    val takePhoto = {
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", vm.newCaptureFile()))
    }
    val pickPhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
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

    val e = editor
    when (val c = capture) {
        is Capture.Working -> WorkingScreen(c.message)
        is Capture.Failed -> FailedScreen(c.message, onRetry = { vm.dismissFailure(); takePhoto() }, onClose = vm::dismissFailure)
        Capture.Idle -> if (e != null) {
            Box {
                EditorScreen(vm, e, settings)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(tab.label, fontWeight = FontWeight.SemiBold)
                                if (tab == Tab.LIBRARY && items.isNotEmpty()) {
                                    Text(
                                        "${items.size} items · ${money(items.sumOf { settings.convert(it.total, it.currency) }, settings.currency)}",
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
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ExtendedFloatingActionButton(
                                onClick = pickPhoto,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                text = { Text("Gallery") },
                                icon = { Icon(Icons.Filled.Add, null) },
                            )
                            ExtendedFloatingActionButton(
                                onClick = takePhoto,
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                text = { Text("Camera") },
                                icon = { Icon(Icons.Filled.Add, null) },
                            )
                        }
                    }
                },
            ) { pad ->
                val m = Modifier.padding(pad).fillMaxSize()
                when (tab) {
                    Tab.LIBRARY -> LibraryTab(
                        vm, items, settings, query, filters, m,
                        onQuery = { query = it }, onFilters = { filters = it }, onOpen = vm::open,
                    )
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
