package io.github.hosani69.gameslibrary

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** An item found in a photo, waiting for the user to confirm it. */
data class Draft(
    val id: String,
    val photo: String,
    val title: String,
    val kind: Kind,
    val condition: Condition = Condition.NEW,
    val keep: Boolean = true,
    val info: String = "",
)

/** How the photo is split into items: automatic, or an even grid the user picks. */
data class Layout(val cols: Int, val rows: Int) {
    val label get() = when {
        cols == 0 -> "Auto"
        rows == 1 -> "$cols"
        else -> "$cols×$rows"
    }

    companion object {
        val AUTO = Layout(0, 0)
        val choices = listOf(AUTO, Layout(1, 1), Layout(2, 1), Layout(3, 1), Layout(4, 1), Layout(5, 1), Layout(6, 1),
            Layout(1, 2), Layout(2, 2), Layout(3, 2), Layout(4, 2), Layout(3, 3))
    }
}

sealed interface Capture {
    data object Idle : Capture
    data class Working(val message: String) : Capture
    data class Review(
        val photo: Bitmap,
        val boxes: List<Rect>,
        val layout: Layout,
        val drafts: List<Draft>,
        val reading: Boolean = false,
    ) : Capture
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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var segmenter: Pair<Segmenter, Double>? = null
    private var defaultKind = Kind.GAME
    private var readJob: Job? = null

    init {
        // Refresh exchange rates once a day when online.
        if (_settings.value.ratesDate != Rates.today()) updateRates(quiet = true)
    }

    fun photoOf(item: Item) = library.photoFile(item)
    fun photoOf(draft: Draft) = File(library.photos, draft.photo)

    fun newCaptureFile(): File =
        File(getApplication<Application>().cacheDir, "captures").apply { mkdirs() }.let { File(it, "capture.jpg") }

    fun messageShown() { _message.value = null }

    /* ---------- Capture ---------- */

    /** Finds every item in the photo, reads its title and looks it up online. */
    fun process(photoFile: File, kind: Kind) {
        defaultKind = kind
        _capture.value = Capture.Working("Finding items…")
        viewModelScope.launch {
            try {
                val (photo, boxes) = withContext(Dispatchers.Default) {
                    val photo = Recognizer.loadUpright(photoFile)
                    val seg = Recognizer.segmenter(photo)
                    segmenter = seg
                    photo to Recognizer.findItems(photo, seg.first, seg.second)
                }
                photoFile.delete()
                read(photo, boxes, Layout.AUTO)
            } catch (e: Exception) {
                _capture.value = Capture.Failed("Couldn't read that photo. Try again with more light.")
            }
        }
    }

    /** Re-splits the same photo with a grid the user picked, e.g. 3 items in a row. */
    fun relayout(layout: Layout) {
        val c = _capture.value as? Capture.Review ?: return
        val seg = segmenter ?: return
        viewModelScope.launch {
            val boxes = withContext(Dispatchers.Default) {
                if (layout == Layout.AUTO) Recognizer.findItems(c.photo, seg.first, seg.second)
                else seg.first.grid(layout.cols, layout.rows).map { it.scaled(seg.second).toRect() }
            }
            deleteDraftPhotos(c.drafts)
            read(c.photo, boxes, layout)
        }
    }

    private fun read(photo: Bitmap, boxes: List<Rect>, layout: Layout) {
        readJob?.cancel()
        val placeholders = boxes.map { Draft(UUID.randomUUID().toString(), "", "", defaultKind) }
        _capture.value = Capture.Review(photo, boxes, layout, placeholders, reading = true)
        readJob = viewModelScope.launch {
            val drafts = withContext(Dispatchers.Default) {
                boxes.zip(placeholders).map { (box, d) ->
                    async {
                        val cover = Recognizer.crop(photo, box)
                        val name = "${d.id}.jpg"
                        savePhoto(cover, File(library.photos, name))
                        val text = runCatching { Recognizer.readTitle(cover) }.getOrDefault("")
                        val match = runCatching { Lookup.find(text, defaultKind) }.getOrNull()
                        d.copy(photo = name, title = match?.title ?: text, kind = match?.kind ?: defaultKind, info = match?.info ?: "")
                    }
                }.awaitAll()
            }
            _capture.update { c -> if (c is Capture.Review && c.boxes === boxes) c.copy(drafts = drafts, reading = false) else c }
        }
    }

    fun editDraft(id: String, change: (Draft) -> Draft) {
        _capture.update { c -> if (c is Capture.Review) c.copy(drafts = c.drafts.map { if (it.id == id) change(it) else it }) else c }
    }

    /** Adds the kept drafts. The total paid and shipping are split evenly between them. */
    fun addDrafts(totalCost: Double?, totalShipping: Double?, currency: String) {
        val c = _capture.value as? Capture.Review ?: return
        if (c.reading) return
        val kept = c.drafts.filter { it.keep }
        deleteDraftPhotos(c.drafts.filterNot { it.keep })
        val n = kept.size.coerceAtLeast(1)
        val today = LocalDate.now().toString()
        val newItems = kept.map { d ->
            Item(
                id = d.id, photo = d.photo, title = d.title.ifBlank { "Untitled" }, kind = d.kind,
                condition = d.condition, cost = totalCost?.div(n), shipping = totalShipping?.div(n),
                currency = currency, market = null, logDate = today, progress = Progress.NOT_STARTED, info = d.info,
            )
        }
        save(_items.value + newItems)
        closeCapture()
        _message.value = if (newItems.size == 1) "Added 1 item" else "Added ${newItems.size} items"
    }

    fun discardCapture() {
        (_capture.value as? Capture.Review)?.let { deleteDraftPhotos(it.drafts) }
        closeCapture()
    }

    private fun closeCapture() {
        readJob?.cancel()
        segmenter = null
        _capture.value = Capture.Idle
    }

    private fun deleteDraftPhotos(drafts: List<Draft>) =
        drafts.filter { it.photo.isNotEmpty() }.forEach { File(library.photos, it.photo).delete() }

    /* ---------- Library ---------- */

    fun update(item: Item) = save(_items.value.map { if (it.id == item.id) item else it })

    fun delete(item: Item) {
        library.photoFile(item).delete()
        save(_items.value.filterNot { it.id == item.id })
    }

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
                fun cell(v: Any?) = v?.toString()?.let { if (it.contains(',') || it.contains('"')) "\"" + it.replace("\"", "\"\"") + "\"" else it } ?: ""
                fun amt(v: Double?) = v?.let { "%.2f".format(java.util.Locale.US, it) }
                val header = listOf("Title", "Type", "Condition", "Progress", "Logged", "Paid currency", "Paid", "Shipping",
                    "Total (${s.currency})", "Market value (${s.currency})", "Info")
                val rows = _items.value.sortedBy { it.title.lowercase() }.map { i ->
                    listOf(i.title, i.kind.label, i.condition.label, if (i.kind == Kind.GAME) i.progress.label else "", i.logDate,
                        i.currency, amt(i.cost), amt(i.shipping), amt(s.convert(i.total, i.currency)), amt(i.market), i.info)
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
        val scale = minOf(1f, 900f / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }
}
