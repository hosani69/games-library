package io.github.hosani69.gameslibrary

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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

sealed interface Capture {
    data object Idle : Capture
    data class Working(val message: String) : Capture
    data class Review(val drafts: List<Draft>) : Capture
    data class Failed(val message: String) : Capture
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val library = Library(app)
    private val saveLock = Mutex()

    private val _items = MutableStateFlow(library.load())
    val items: StateFlow<List<Item>> = _items

    private val _capture = MutableStateFlow<Capture>(Capture.Idle)
    val capture: StateFlow<Capture> = _capture

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val photosDir: File get() = library.photos
    fun photoOf(item: Item) = library.photoFile(item)
    fun photoOf(draft: Draft) = File(library.photos, draft.photo)

    fun newCaptureFile(): File =
        File(getApplication<Application>().cacheDir, "captures").apply { mkdirs() }.let { File(it, "capture.jpg") }

    fun messageShown() { _message.value = null }

    /** Finds every item in the photo, reads its title and looks it up online. */
    fun process(photoFile: File, defaultKind: Kind) {
        _capture.value = Capture.Working("Finding items…")
        viewModelScope.launch {
            try {
                val drafts = withContext(Dispatchers.Default) {
                    val photo = Recognizer.loadUpright(photoFile)
                    val boxes = Recognizer.findItems(photo)
                    _capture.value = Capture.Working(if (boxes.size == 1) "Reading the cover…" else "Found ${boxes.size} items. Reading covers…")
                    boxes.map { box ->
                        async {
                            val cover = Recognizer.crop(photo, box)
                            val id = UUID.randomUUID().toString()
                            val name = "$id.jpg"
                            savePhoto(cover, File(library.photos, name))
                            val text = runCatching { Recognizer.readTitle(cover) }.getOrDefault("")
                            val match = runCatching { Lookup.find(text, defaultKind) }.getOrNull()
                            Draft(
                                id = id, photo = name,
                                title = match?.title ?: text,
                                kind = match?.kind ?: defaultKind,
                                info = match?.info ?: "",
                            )
                        }
                    }.awaitAll()
                }
                photoFile.delete()
                _capture.value = Capture.Review(drafts)
            } catch (e: Exception) {
                _capture.value = Capture.Failed("Couldn't read that photo. Try again with more light.")
            }
        }
    }

    fun editDraft(id: String, change: (Draft) -> Draft) {
        _capture.update { c -> if (c is Capture.Review) c.copy(drafts = c.drafts.map { if (it.id == id) change(it) else it }) else c }
    }

    /** Adds the kept drafts. The total paid and shipping are split evenly between them. */
    fun addDrafts(totalCost: Double?, totalShipping: Double?) {
        val c = _capture.value as? Capture.Review ?: return
        val kept = c.drafts.filter { it.keep }
        c.drafts.filterNot { it.keep }.forEach { File(library.photos, it.photo).delete() }
        val n = kept.size.coerceAtLeast(1)
        val today = LocalDate.now().toString()
        val newItems = kept.map { d ->
            Item(
                id = d.id, photo = d.photo, title = d.title.ifBlank { "Untitled" }, kind = d.kind,
                condition = d.condition, cost = totalCost?.div(n), shipping = totalShipping?.div(n),
                logDate = today, progress = Progress.NOT_STARTED, info = d.info,
            )
        }
        save(_items.value + newItems)
        _capture.value = Capture.Idle
        _message.value = if (newItems.size == 1) "Added 1 item" else "Added ${newItems.size} items"
    }

    fun discardCapture() {
        (_capture.value as? Capture.Review)?.drafts?.forEach { File(library.photos, it.photo).delete() }
        _capture.value = Capture.Idle
    }

    fun update(item: Item) = save(_items.value.map { if (it.id == item.id) item else it })

    fun delete(item: Item) {
        library.photoFile(item).delete()
        save(_items.value.filterNot { it.id == item.id })
    }

    fun backup(target: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { runCatching { library.backup(_items.value, target) }.isSuccess }
        _message.value = if (ok) "Backup saved" else "Backup failed"
    }

    fun restore(source: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { library.restore(source, _items.value) } }
        result.onSuccess { save(it) }
        _message.value = result.fold({ "Restored. ${it.size} items in your library" }, { "That file isn't a Games Library backup" })
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
