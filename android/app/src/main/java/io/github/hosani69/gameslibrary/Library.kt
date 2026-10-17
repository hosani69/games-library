package io.github.hosani69.gameslibrary

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class Kind(val label: String, val plural: String) {
    GAME("Game", "Games"), CONSOLE("Console", "Consoles"), OST("Soundtrack", "Soundtracks"), BOOK("Book", "Books")
}

enum class Condition(val label: String) { NEW("New"), USED("Used"), DAMAGED("Damaged") }

enum class Progress(val label: String) { NOT_STARTED("Not started"), PLAYING("Playing"), FINISHED("Finished") }

data class Item(
    val id: String,
    val photo: String,          // file name inside the photos folder
    val title: String,
    val kind: Kind,
    val condition: Condition,
    val cost: Double?,
    val shipping: Double?,
    val logDate: String,        // yyyy-MM-dd, set automatically
    val progress: Progress,
    val info: String,           // filled from the internet, e.g. "2017 video game"
) {
    val total: Double get() = (cost ?: 0.0) + (shipping ?: 0.0)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("photo", photo).put("title", title).put("kind", kind.name)
        .put("condition", condition.name).put("cost", cost ?: JSONObject.NULL)
        .put("shipping", shipping ?: JSONObject.NULL).put("logDate", logDate)
        .put("progress", progress.name).put("info", info)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            id = o.getString("id"),
            photo = o.optString("photo"),
            title = o.optString("title"),
            kind = enumOr(o.optString("kind"), Kind.GAME),
            condition = enumOr(o.optString("condition"), Condition.NEW),
            cost = o.optDouble("cost").takeUnless { it.isNaN() },
            shipping = o.optDouble("shipping").takeUnless { it.isNaN() },
            logDate = o.optString("logDate"),
            progress = enumOr(o.optString("progress"), Progress.NOT_STARTED),
            info = o.optString("info"),
        )
    }
}

private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

/** Everything is stored in the app's private folder on the phone: one JSON file plus the photos. */
class Library(private val context: Context) {
    val photos: File = File(context.filesDir, "photos").apply { mkdirs() }
    private val file = File(context.filesDir, "library.json")

    fun load(): List<Item> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(items: List<Item>) {
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(serialize(items))
        tmp.renameTo(file)
    }

    fun photoFile(item: Item) = File(photos, item.photo)

    /** Writes a zip with the library and all photos to a file the user picked. */
    fun backup(items: List<Item>, target: Uri) {
        context.contentResolver.openOutputStream(target)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("library.json"))
                zip.write(serialize(items).toByteArray())
                zip.closeEntry()
                for (item in items) {
                    val photo = photoFile(item)
                    if (!photo.exists()) continue
                    zip.putNextEntry(ZipEntry("photos/${item.photo}"))
                    photo.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Reads a backup zip. Items in the backup replace items with the same id; others are added. */
    fun restore(source: Uri, current: List<Item>): List<Item> {
        var restored: List<Item>? = null
        context.contentResolver.openInputStream(source)!!.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == "library.json" -> restored = parse(zip.readBytes().decodeToString())
                        name.startsWith("photos/") && !name.contains("..") ->
                            File(photos, name.removePrefix("photos/")).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val incoming = restored ?: throw IllegalArgumentException("Not a Games Library backup")
        val ids = incoming.map { it.id }.toSet()
        return current.filterNot { it.id in ids } + incoming
    }

    private fun serialize(items: List<Item>) =
        JSONObject().put("app", "games-library").put("version", 2)
            .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
            .toString()

    private fun parse(text: String): List<Item> {
        val arr = JSONObject(text).getJSONArray("items")
        return (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
    }
}
