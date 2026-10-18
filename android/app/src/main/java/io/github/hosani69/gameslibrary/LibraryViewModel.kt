package io.github.hosani69.gameslibrary

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** The item being added or edited, with the internet matches the user can pick from. */
data class Editor(
    val item: Item,
    val isNew: Boolean,
    val matches: List<Match> = emptyList(),
    val searching: Boolean = false,
    val startPhoto: String = item.photo, // the photo when the editor opened, to tidy up after cropping
)

sealed interface Capture {
    data object Idle : Capture
    data class Working(val message: String) : Capture
    data class Failed(val message: String) : Capture
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val library = Library(app)
    private val saveLock = Mutex()

    private val _items = MutableStateFlow(library.load())
    val items: StateFlow<List<Item>> = _items

    private val _settings = MutableStateFlow(library.loadSettings())
    val settings: StateFlow<Settings> = _settings

    private val _capture = MutableStateFlow<Capture>(Capture.Idle)
    val capture: StateFlow<Capture> = _capture

    private val _editor = MutableStateFlow<Editor?>(null)
    val editor: StateFlow<Editor?> = _editor

    /** The picture being cropped, shown on the crop screen. */
    private val _crop = MutableStateFlow<Bitmap?>(null)
    val crop: StateFlow<Bitmap?> = _crop

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var searchJob: Job? = null

    init {
        // Refresh exchange rates once a day when online.
        if (_settings.value.ratesDate != Rates.today()) updateRates(quiet = true)
    }

    fun photoOf(item: Item) = library.photoFile(item)

    fun newCaptureFile(): File =
        File(getApplication<Application>().cacheDir, "captures").apply { mkdirs() }.let { File(it, "capture.jpg") }

    fun messageShown() { _message.value = null }

    /* ---------- Adding from a picture ---------- */

    /** Copies a picture chosen from the gallery, then reads it like a camera photo. */
    fun processPicked(uri: Uri) {
        val target = newCaptureFile()
        _capture.value = Capture.Working("Reading the picture…")
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }.isSuccess
            }
            if (ok) process(target) else _capture.value = Capture.Failed("Couldn't open that picture.")
        }
    }

    /**
     * Reads the picture on the phone (text and what it looks like), makes the square image,
     * then searches free public sources and fills in the fields.
     */
    fun process(photoFile: File) {
        _capture.value = Capture.Working("Reading the picture…")
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val name = "$id.jpg"
                val original = "${id}_orig.jpg"
                val clues = withContext(Dispatchers.Default) {
                    val photo = Recognizer.loadUpright(photoFile)
                    savePhoto(Recognizer.square(photo), File(library.photos, name))
                    savePhoto(Recognizer.fit(photo, 1600), File(library.photos, original))
                    val (lines, labels) = Recognizer.read(photo)
                    Extractor.clues(lines, labels)
                }
                photoFile.delete()
                var item = Item(
                    id = id, photo = name, title = clues.title, kind = clues.kind ?: Kind.GAME,
                    condition = Condition.NEW, cost = null, shipping = null, currency = _settings.value.currency,
                    market = null, logDate = LocalDate.now().toString(), progress = Progress.NOT_STARTED, info = "",
                    platform = normalizePlatform(clues.platform), released = clues.year, original = original,
                )
                _capture.value = Capture.Working("Searching the internet…")
                val matches = if (clues.title.isNotBlank()) runCatching { Lookup.search(clues.title, clues.kind) }.getOrDefault(emptyList()) else emptyList()
                matches.firstOrNull()?.let { item = fill(item, Lookup.details(it), keepKind = clues.kind != null) }
                _editor.value = Editor(item, isNew = true, matches = matches)
                _capture.value = Capture.Idle
                if (clues.title.isBlank()) _message.value = "Couldn't read a title. Type it and tap Search."
                else if (matches.isEmpty()) _message.value = "No match online. Check the title and tap Search."
            } catch (e: Exception) {
                _capture.value = Capture.Failed("Couldn't read that picture. Try again with more light.")
            }
        }
    }

    fun dismissFailure() { _capture.value = Capture.Idle }

    /* ---------- Editor ---------- */

    fun open(item: Item) { _editor.value = Editor(item, isNew = false) }

    fun edit(change: (Item) -> Item) = _editor.update { e -> e?.copy(item = change(e.item)) }

    /** Searches again with the title as it is now (after the user fixed it, for example). */
    fun searchAgain() {
        val e = _editor.value ?: return
        searchJob?.cancel()
        _editor.value = e.copy(searching = true)
        searchJob = viewModelScope.launch {
            val matches = runCatching { Lookup.search(e.item.title, e.item.kind) }.getOrDefault(emptyList())
            _editor.update { it?.copy(matches = matches, searching = false) }
            if (matches.isEmpty()) _message.value = "No matches found online"
        }
    }

    /** Uses a match the user picked: replaces the details, keeps what they paid and their notes. */
    fun pick(m: Match) {
        _editor.update { it?.copy(searching = true) }
        viewModelScope.launch {
            val full = Lookup.details(m)
            _editor.update { e -> e?.copy(item = fill(e.item, full, keepKind = false, overwrite = true), searching = false) }
        }
    }

    fun saveEditor() {
        val e = _editor.value ?: return
        val item = e.item.copy(title = e.item.title.trim().ifBlank { "Untitled" })
        if (!e.isNew && item.photo != e.startPhoto) File(library.photos, e.startPhoto).delete()
        if (e.isNew) {
            save(_items.value + item)
            _message.value = "Added “${item.title}”"
        } else {
            update(item)
        }
        _editor.value = null
    }

    fun closeEditor() {
        val e = _editor.value ?: return
        if (e.isNew) {
            deleteFiles(e.item)
        } else if (e.item.photo != e.startPhoto) {
            File(library.photos, e.item.photo).delete() // a crop that wasn't saved
        }
        _editor.value = null
    }

    /* ---------- Cropping ---------- */

    /** Opens the crop screen on the full original picture (or the current square for older items). */
    fun startCrop() {
        val item = _editor.value?.item ?: return
        viewModelScope.launch {
            val src = listOf(item.original, item.photo).map { File(library.photos, it) }.firstOrNull { it.isFile }
            _crop.value = src?.let { f -> withContext(Dispatchers.Default) { runCatching { Recognizer.loadUpright(f, 1600) }.getOrNull() } }
            if (_crop.value == null) _message.value = "Couldn't open the picture"
        }
    }

    fun rotateCrop() {
        val b = _crop.value ?: return
        _crop.value = Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(90f) }, true)
    }

    fun cancelCrop() { _crop.value = null }

    /** Saves the chosen square (in the picture's pixels; it may reach past the edges) as the item's image. */
    fun applyCrop(left: Float, top: Float, side: Float) {
        val src = _crop.value ?: return
        val e = _editor.value ?: return
        _crop.value = null
        viewModelScope.launch {
            val name = "${e.item.id}_${System.currentTimeMillis()}.jpg"
            withContext(Dispatchers.Default) { savePhoto(Recognizer.cropSquare(src, left, top, side), File(library.photos, name)) }
            val previous = e.item.photo
            _editor.update { it?.copy(item = it.item.copy(photo = name)) }
            if (previous != e.startPhoto) File(library.photos, previous).delete()
        }
    }

    fun deleteEditing() {
        val e = _editor.value ?: return
        delete(e.item)
        _editor.value = null
    }

    private fun fill(item: Item, m: Match, keepKind: Boolean, overwrite: Boolean = false): Item {
        fun pick(old: String, new: String) = if (new.isNotBlank() && (overwrite || old.isBlank())) new else old
        // The platform printed on the box wins; otherwise take the internet's when there is only one.
        val found = m.platforms.map { normalizePlatform(it) }.filter { it != MISC_PLATFORM }.distinct()
        val platform = when {
            item.platform.isNotBlank() && item.platform != MISC_PLATFORM && (!overwrite || found.isEmpty() || item.platform in found) -> item.platform
            found.size == 1 -> found[0]
            else -> item.platform.ifBlank { MISC_PLATFORM }
        }
        return item.copy(
            title = m.title.ifBlank { item.title },
            kind = if (keepKind) item.kind else m.kind ?: item.kind,
            info = pick(item.info, m.info),
            creator = pick(item.creator, m.creator),
            publisher = pick(item.publisher, m.publisher),
            released = pick(item.released, m.released),
            genre = pick(item.genre, m.genre),
            platform = platform,
            criticScore = pick(item.criticScore, m.criticScore),
            criticSource = if (m.criticScore.isNotBlank() && (overwrite || item.criticScore.isBlank())) m.criticSource else item.criticSource,
            ageRating = pick(item.ageRating, m.ageRating),
        )
    }

    /* ---------- Library ---------- */

    fun update(item: Item) = save(_items.value.map { if (it.id == item.id) item else it })

    fun delete(item: Item) {
        deleteFiles(item)
        save(_items.value.filterNot { it.id == item.id })
    }

    private fun deleteFiles(item: Item) =
        listOf(item.photo, item.original).filter { it.isNotBlank() }.forEach { File(library.photos, it).delete() }

    fun backup(target: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { runCatching { library.backup(_items.value, _settings.value, target) }.isSuccess }
        _message.value = if (ok) "Backup saved" else "Backup failed"
    }

    fun restore(source: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { library.restore(source, _items.value) } }
        result.onSuccess { (items, settings) ->
            save(items)
            settings?.let { setSettings(it) }
        }
        _message.value = result.fold({ "Restored. ${it.first.size} items in your library" }, { "That file isn't a Games Library backup" })
    }

    /** Writes a spreadsheet of the library, amounts in the main currency. */
    fun exportReport(target: Uri) = viewModelScope.launch {
        val s = _settings.value
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                fun cell(v: Any?) = v?.toString()?.let { if (it.contains(',') || it.contains('"') || it.contains('\n')) "\"" + it.replace("\"", "\"\"") + "\"" else it } ?: ""
                fun amt(v: Double?) = v?.let { "%.2f".format(java.util.Locale.US, it) }
                val header = listOf("Title", "Type", "Platform", "Condition", "Progress", "My rating", "Critic score", "Age rating", "Developer / author", "Publisher",
                    "Released", "Genre", "Logged", "Paid currency", "Paid", "Shipping", "Total (${s.currency})",
                    "Market value (${s.currency})", "Info")
                val rows = _items.value.sortedBy { it.title.lowercase() }.map { i ->
                    listOf(i.title, i.kind.label, i.platform, i.condition.label, if (i.kind == Kind.GAME) i.progress.label else "",
                        if (i.myRating > 0) i.myRating else "", listOf(i.criticScore, i.criticSource).filter { it.isNotBlank() }.joinToString(" "), i.ageRating,
                        i.creator, i.publisher, i.released, i.genre, i.logDate, i.currency, amt(i.cost), amt(i.shipping),
                        amt(s.convert(i.total, i.currency)), amt(i.market), i.info)
                        .joinToString(",") { cell(it) }
                }
                getApplication<Application>().contentResolver.openOutputStream(target)!!.use {
                    it.write(("﻿" + (listOf(header.joinToString(",")) + rows).joinToString("\n")).toByteArray())
                }
            }.isSuccess
        }
        _message.value = if (ok) "Report saved" else "Couldn't save the report"
    }

    /* ---------- Settings ---------- */

    fun setCurrency(code: String) = setSettings(_settings.value.copy(currency = code))
    fun setSort(v: Sort) = setSettings(_settings.value.copy(sort = v))
    fun setGroup(v: Group) = setSettings(_settings.value.copy(group = v))
    fun setView(v: ViewMode) = setSettings(_settings.value.copy(view = v))
    fun setTheme(v: AppTheme) = setSettings(_settings.value.copy(theme = v))

    fun setRate(code: String, perMain: Double) {
        val s = _settings.value
        // The user types "1 main = x code"; stored rates are per 1 AED.
        setSettings(s.copy(rates = s.rates + (code to perMain * s.rate(s.currency))))
    }

    fun updateRates(quiet: Boolean = false) = viewModelScope.launch {
        val fresh = Rates.fetch()
        if (fresh != null) {
            setSettings(_settings.value.copy(rates = _settings.value.rates + fresh, ratesDate = Rates.today()))
            if (!quiet) _message.value = "Exchange rates updated"
        } else if (!quiet) {
            _message.value = "Couldn't update rates. Check your connection."
        }
    }

    private fun setSettings(s: Settings) {
        _settings.value = s
        viewModelScope.launch(Dispatchers.IO) { saveLock.withLock { library.saveSettings(_settings.value) } }
    }

    private fun save(list: List<Item>) {
        _items.value = list
        // Always writes the newest list, one write at a time.
        viewModelScope.launch(Dispatchers.IO) { saveLock.withLock { library.save(_items.value) } }
    }

    private fun savePhoto(bmp: Bitmap, file: File) {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }
}
