package io.github.hosani69.gameslibrary

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val DEFAULT_CURRENCY = "AED"

enum class Kind(val label: String, val plural: String) {
    GAME("Game", "Games"), CONSOLE("Console", "Consoles"), OST("Soundtrack", "Soundtracks"), BOOK("Book", "Books"),
    ACCESSORY("Accessory", "Accessories"), MISC("Misc", "Misc")
}

enum class Condition(val label: String) { NEW("New"), USED("Used"), DAMAGED("Damaged") }

enum class Sort(val label: String) {
    NEWEST("Newest added"), OLDEST("Oldest added"), TITLE_AZ("Title A–Z"), TITLE_ZA("Title Z–A"),
    PAID_HIGH("Paid: high to low"), PAID_LOW("Paid: low to high"), VALUE_HIGH("Value: high to low"),
    VALUE_LOW("Value: low to high"), RELEASE_NEW("Release: newest"), RELEASE_OLD("Release: oldest"),
    MY_RATING("My rating"), CRITIC("Critic score"),
}

enum class Group(val label: String) {
    NONE("No grouping"), TYPE("Type"), PLATFORM("Platform"), CONDITION("Condition"), PROGRESS("Progress"),
    GENRE("Genre"), CREATOR("Developer / author"), MY_RATING("My rating"), RELEASE_YEAR("Release year"), ADDED_MONTH("Month added"),
}

enum class AppTheme(val label: String) { MONO("Mono"), OCEAN("Ocean"), FOREST("Forest"), SUNSET("Sunset") }

/** The platforms offered in the drop-down. "Misc" is the default for anything else. */
const val MISC_PLATFORM = "Misc"
val PLATFORMS = listOf(
    "Nintendo Switch 2", "Nintendo Switch", "Wii U", "Wii", "GameCube", "Nintendo 64", "SNES", "NES",
    "Nintendo 3DS", "Nintendo DS", "Game Boy Advance", "Game Boy Color", "Game Boy",
    "PlayStation 5", "PlayStation 4", "PlayStation 3", "PlayStation 2", "PlayStation", "PS Vita", "PSP",
    "Xbox Series X|S", "Xbox One", "Xbox 360", "Xbox",
    "Dreamcast", "Sega Saturn", "Mega Drive / Genesis", "Master System", "Game Gear",
    "PC", "Steam Deck", "Mobile", "Retro / other console", MISC_PLATFORM,
)

/** Maps a platform name from the box or the internet onto the drop-down list. */
fun normalizePlatform(name: String): String {
    if (name.isBlank()) return MISC_PLATFORM
    PLATFORMS.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
    if (Regex("windows|linux|mac ?os|steam", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "PC"
    if (Regex("android|ios|iphone", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "Mobile"
    if (Regex("series x|series s", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "Xbox Series X|S"
    return Extractor.platforms.firstOrNull { (re, _) -> re.containsMatchIn(name) }?.second
        ?.let { found -> PLATFORMS.firstOrNull { it == found } }
        ?: MISC_PLATFORM
}

enum class ViewMode(val label: String) { GRID("Large grid"), SMALL_GRID("Small grid"), LIST("List"), COMPACT("Compact list") }

enum class Progress(val label: String) { NOT_STARTED("Not started"), PLAYING("Playing"), FINISHED("Finished") }

data class Item(
    val id: String,
    val photo: String,          // file name inside the photos folder
    val title: String,
    val kind: Kind,
    val condition: Condition,
    val cost: Double?,
    val shipping: Double?,
    val currency: String,       // currency the item was paid in
    val market: Double?,        // current market value, in the library's main currency
    val logDate: String,        // yyyy-MM-dd, set automatically
    val progress: Progress,
    val info: String,           // filled from the internet, e.g. "2017 video game"
    val platform: String = "",  // e.g. "PlayStation 5"; for books and soundtracks, the format
    val creator: String = "",   // developer, author, composer or maker
    val publisher: String = "",
    val released: String = "",  // year or full date
    val genre: String = "",
    val original: String = "",  // the full picture before cropping, so it can be cropped again
    val criticScore: String = "", // e.g. "97/100", from Wikidata
    val criticSource: String = "", // e.g. "Metacritic"
    val ageRating: String = "",  // e.g. "PEGI 12"
    val myRating: Int = 0,       // personal rating, 0 (none) to 5 stars
) {
    /** What was paid including shipping, in the currency it was paid in. */
    val total: Double get() = (cost ?: 0.0) + (shipping ?: 0.0)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("photo", photo).put("title", title).put("kind", kind.name)
        .put("condition", condition.name).put("cost", cost ?: JSONObject.NULL)
        .put("shipping", shipping ?: JSONObject.NULL).put("currency", currency)
        .put("market", market ?: JSONObject.NULL).put("logDate", logDate)
        .put("progress", progress.name).put("info", info).put("platform", platform)
        .put("creator", creator).put("publisher", publisher).put("released", released).put("genre", genre)
        .put("original", original).put("criticScore", criticScore).put("criticSource", criticSource)
        .put("ageRating", ageRating).put("myRating", myRating)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            id = o.getString("id"),
            photo = o.optString("photo"),
            title = o.optString("title"),
            kind = enumOr(o.optString("kind"), Kind.GAME),
            condition = enumOr(o.optString("condition"), Condition.NEW),
            cost = o.optDouble("cost").takeUnless { it.isNaN() },
            shipping = o.optDouble("shipping").takeUnless { it.isNaN() },
            currency = o.optString("currency").ifBlank { DEFAULT_CURRENCY },
            market = o.optDouble("market").takeUnless { it.isNaN() },
            logDate = o.optString("logDate"),
            progress = enumOr(o.optString("progress"), Progress.NOT_STARTED),
            info = o.optString("info"),
            platform = normalizePlatform(o.optString("platform")),
            creator = o.optString("creator"),
            publisher = o.optString("publisher"),
            released = o.optString("released"),
            genre = o.optString("genre"),
            original = o.optString("original"),
            criticScore = o.optString("criticScore"),
            criticSource = o.optString("criticSource"),
            ageRating = o.optString("ageRating"),
            myRating = o.optInt("myRating").coerceIn(0, 5),
        )
    }
}

private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

/** Everything is stored in the app's private folder on the phone: one JSON file plus the photos. */
class Library(private val context: Context) {
    val photos: File = File(context.filesDir, "photos").apply { mkdirs() }
    private val file = File(context.filesDir, "library.json")
    private val settingsFile = File(context.filesDir, "settings.json")

    fun loadSettings(): Settings =
        runCatching { Settings.fromJson(JSONObject(settingsFile.readText())) }.getOrDefault(Settings())

    fun saveSettings(s: Settings) = settingsFile.writeText(s.toJson().toString())

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
    fun backup(items: List<Item>, settings: Settings, target: Uri) {
        context.contentResolver.openOutputStream(target)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("library.json"))
                zip.write(serialize(items).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(settings.toJson().toString().toByteArray())
                zip.closeEntry()
                for (name in items.flatMap { listOf(it.photo, it.original) }.filter { it.isNotBlank() }) {
                    val photo = File(photos, name)
                    if (!photo.exists()) continue
                    zip.putNextEntry(ZipEntry("photos/$name"))
                    photo.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Reads a backup zip. Items in the backup replace items with the same id; others are added. */
    fun restore(source: Uri, current: List<Item>): Pair<List<Item>, Settings?> {
        var restored: List<Item>? = null
        var settings: Settings? = null
        context.contentResolver.openInputStream(source)!!.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == "library.json" -> restored = parse(zip.readBytes().decodeToString())
                        name == "settings.json" -> settings = runCatching { Settings.fromJson(JSONObject(zip.readBytes().decodeToString())) }.getOrNull()
                        name.startsWith("photos/") && !name.contains("..") ->
                            File(photos, name.removePrefix("photos/")).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val incoming = restored ?: throw IllegalArgumentException("Not a Games Library backup")
        val ids = incoming.map { it.id }.toSet()
        return (current.filterNot { it.id in ids } + incoming) to settings
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

/**
 * Main currency and exchange rates. Rates are stored as "units of that currency for 1 AED"
 * so any currency can be converted to any other, and changing the main currency needs no download.
 */
data class Settings(
    val currency: String = DEFAULT_CURRENCY,
    val rates: Map<String, Double> = Currencies.fallbackRates,
    val ratesDate: String = "",
    val sort: Sort = Sort.NEWEST,
    val group: Group = Group.NONE,
    val view: ViewMode = ViewMode.GRID,
    val theme: AppTheme = AppTheme.MONO,
) {
    fun rate(code: String): Double = rates[code] ?: 1.0

    /** Converts an amount from one currency into another. */
    fun convert(amount: Double, from: String, to: String = currency): Double =
        if (from == to) amount else amount / rate(from) * rate(to)

    fun toJson(): JSONObject = JSONObject().put("currency", currency).put("ratesDate", ratesDate)
        .put("sort", sort.name).put("group", group.name).put("view", view.name).put("theme", theme.name)
        .put("rates", JSONObject().apply { rates.forEach { (k, v) -> put(k, v) } })

    companion object {
        fun fromJson(o: JSONObject): Settings {
            val r = o.optJSONObject("rates")
            val rates = Currencies.fallbackRates.toMutableMap()
            r?.keys()?.forEach { k -> r.optDouble(k).takeIf { !it.isNaN() && it > 0 }?.let { rates[k] = it } }
            return Settings(
                o.optString("currency").ifBlank { DEFAULT_CURRENCY }, rates, o.optString("ratesDate"),
                enumOr(o.optString("sort"), Sort.NEWEST), enumOr(o.optString("group"), Group.NONE),
                enumOr(o.optString("view"), ViewMode.GRID), enumOr(o.optString("theme"), AppTheme.MONO),
            )
        }
    }
}

object Currencies {
    val codes = listOf(
        "AED", "USD", "EUR", "GBP", "SAR", "KWD", "QAR", "BHD", "OMR", "EGP", "JOD", "TRY",
        "INR", "PKR", "JPY", "CNY", "KRW", "HKD", "SGD", "AUD", "CAD", "CHF",
    )

    /** Rough rates per 1 AED, used until the first online update. Gulf pegs are exact. */
    val fallbackRates = mapOf(
        "AED" to 1.0, "USD" to 0.2723, "EUR" to 0.234, "GBP" to 0.204, "SAR" to 1.0211, "KWD" to 0.0834,
        "QAR" to 0.9912, "BHD" to 0.1026, "OMR" to 0.1047, "EGP" to 13.2, "JOD" to 0.1931, "TRY" to 11.2,
        "INR" to 23.6, "PKR" to 76.0, "JPY" to 41.0, "CNY" to 1.95, "KRW" to 380.0, "HKD" to 2.12,
        "SGD" to 0.355, "AUD" to 0.415, "CAD" to 0.376, "CHF" to 0.219,
    )
}
