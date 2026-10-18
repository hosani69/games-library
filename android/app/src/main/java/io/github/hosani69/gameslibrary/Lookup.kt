package io.github.hosani69.gameslibrary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A possible match found on the internet. */
data class Match(
    val source: String,
    val title: String,
    val kind: Kind? = null,
    val info: String = "",
    val creator: String = "",
    val publisher: String = "",
    val released: String = "",
    val genre: String = "",
    val platforms: List<String> = emptyList(),
    val wikidata: String = "",
    val criticScore: String = "",
    val criticSource: String = "",
    val ageRating: String = "",
)

/**
 * Searches free public sources (no keys, no accounts) for what the picture shows:
 * Wikipedia + Wikidata for games, consoles and anything else, Open Library for books,
 * MusicBrainz for soundtracks.
 */
object Lookup {
    private const val AGENT = "GamesLibrary/1.2 (https://github.com/hosani69/games-library)"

    suspend fun search(query: String, kind: Kind?): List<Match> = coroutineScope {
        if (query.length < 2) return@coroutineScope emptyList()
        val wiki = async { runCatching { wikipedia(query, kind) }.getOrDefault(emptyList()) }
        val extra = async {
            runCatching {
                when (kind) {
                    Kind.BOOK -> openLibrary(query)
                    Kind.OST -> musicBrainz(query)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }
        val words = significant(query)
        (extra.await() + wiki.await())
            .filter { m -> words.isEmpty() || significant(m.title).any { it in words } }
            .distinctBy { it.title.lowercase() to it.source }
            .take(6)
    }

    /** Adds developer, publisher, genre, platforms and release date from Wikidata. */
    suspend fun details(m: Match): Match = withContext(Dispatchers.IO) {
        if (m.wikidata.isBlank()) return@withContext m
        runCatching {
            val q = m.wikidata
            val claims = get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims&ids=$q")
                .getJSONObject("entities").getJSONObject(q).optJSONObject("claims") ?: return@runCatching m
            fun ids(p: String): List<String> {
                val arr = claims.optJSONArray(p) ?: return emptyList()
                return (0 until arr.length()).mapNotNull {
                    arr.getJSONObject(it).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")
                }.filter { it.isNotBlank() }.take(5)
            }
            fun time(p: String): String {
                val arr = claims.optJSONArray(p) ?: return ""
                val t = arr.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time") ?: return ""
                return t.removePrefix("+").take(10).replace("-00", "")
            }
            val props = mapOf(
                "dev" to "P178", "pub" to "P123", "genre" to "P136", "platform" to "P400", "maker" to "P176",
                "author" to "P50", "composer" to "P86", "pegi" to "P908", "esrb" to "P852",
            )
            val wanted = props.mapValues { ids(it.value) }
            // Review scores (P444), each with the reviewer (P447) as a qualifier, e.g. "97/100" by Metacritic.
            val scores = claims.optJSONArray("P444")?.let { arr ->
                (0 until arr.length()).mapNotNull { k ->
                    val c = arr.getJSONObject(k)
                    val v = c.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value") ?: return@mapNotNull null
                    val by = c.optJSONObject("qualifiers")?.optJSONArray("P447")?.optJSONObject(0)
                        ?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id").orEmpty()
                    v to by
                }
            }.orEmpty()
            val all = (wanted.values.flatten() + scores.map { it.second }.filter { it.isNotBlank() }).distinct().take(50)
            val labels = if (all.isEmpty()) emptyMap() else {
                val e = get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels&languages=en&ids=${all.joinToString("|")}")
                    .getJSONObject("entities")
                all.associateWith { e.optJSONObject(it)?.optJSONObject("labels")?.optJSONObject("en")?.optString("value") ?: "" }
            }
            fun names(k: String) = wanted[k].orEmpty().mapNotNull { labels[it]?.takeIf { s -> s.isNotBlank() } }
            val creator = listOf("dev", "author", "composer", "maker").firstNotNullOfOrNull { k -> names(k).takeIf { it.isNotEmpty() } }.orEmpty()
            // Prefer the aggregate sites, then any score out of 100, then whatever is there.
            val best = scores.firstOrNull { labels[it.second] == "Metacritic" }
                ?: scores.firstOrNull { labels[it.second] == "OpenCritic" }
                ?: scores.firstOrNull { it.first.endsWith("/100") }
                ?: scores.firstOrNull()
            val pegi = names("pegi").firstOrNull()?.let { if (it.startsWith("PEGI", true)) it else "PEGI $it" }
            val esrb = names("esrb").firstOrNull()?.let { if (it.startsWith("ESRB", true)) it else "ESRB $it" }
            m.copy(
                creator = m.creator.ifBlank { creator.take(2).joinToString(", ") },
                publisher = m.publisher.ifBlank { names("pub").take(2).joinToString(", ") },
                genre = m.genre.ifBlank { names("genre").take(2).joinToString(", ") },
                platforms = m.platforms.ifEmpty { names("platform") },
                released = m.released.ifBlank { time("P577").ifBlank { time("P571") } },
                criticScore = best?.first.orEmpty(),
                criticSource = best?.second?.let { labels[it] }.orEmpty(),
                ageRating = pegi ?: esrb ?: "",
            )
        }.getOrDefault(m)
    }

    private suspend fun wikipedia(query: String, kind: Kind?): List<Match> = withContext(Dispatchers.IO) {
        val hint = when (kind) {
            Kind.GAME -> " video game"
            Kind.CONSOLE -> " console"
            Kind.OST -> " soundtrack"
            Kind.BOOK -> " book"
            Kind.ACCESSORY -> " controller"
            else -> ""
        }
        val q = URLEncoder.encode(query + hint, "UTF-8")
        val json = get(
            "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=6" +
                "&prop=description|pageprops&ppprop=wikibase_item&gsrsearch=$q"
        )
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext emptyList()
        pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }.map { p ->
            val d = p.optString("description")
            Match(
                source = "Wikipedia",
                title = p.optString("title").replace(Regex("\\s*\\([^)]*\\)$"), ""),
                kind = kindFrom(d),
                info = d.replaceFirstChar { it.uppercase() },
                wikidata = p.optJSONObject("pageprops")?.optString("wikibase_item").orEmpty(),
            )
        }.filterNot { it.info.contains("disambiguation", true) }.toList()
    }

    private suspend fun openLibrary(query: String): List<Match> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val docs = get("https://openlibrary.org/search.json?limit=5&fields=title,author_name,first_publish_year,publisher,subject&q=$q")
            .optJSONArray("docs") ?: return@withContext emptyList()
        (0 until docs.length()).map { docs.getJSONObject(it) }.map { d ->
            fun first(k: String, n: Int = 1) = d.optJSONArray(k)?.let { a -> (0 until minOf(n, a.length())).joinToString(", ") { a.getString(it) } }.orEmpty()
            Match(
                source = "Open Library", title = d.optString("title"), kind = Kind.BOOK,
                info = "Book", creator = first("author_name", 2), publisher = first("publisher"),
                released = d.optInt("first_publish_year").takeIf { it > 0 }?.toString().orEmpty(), genre = first("subject", 2),
            )
        }
    }

    private suspend fun musicBrainz(query: String): List<Match> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("release:($query)", "UTF-8")
        val rel = get("https://musicbrainz.org/ws/2/release/?fmt=json&limit=5&query=$q").optJSONArray("releases")
            ?: return@withContext emptyList()
        (0 until rel.length()).map { rel.getJSONObject(it) }.filter { it.optInt("score") >= 60 }.map { r ->
            val artist = r.optJSONArray("artist-credit")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty()
            val label = r.optJSONArray("label-info")?.optJSONObject(0)?.optJSONObject("label")?.optString("name").orEmpty()
            val format = r.optJSONArray("media")?.optJSONObject(0)?.optString("format").orEmpty()
            Match(
                source = "MusicBrainz", title = r.optString("title"), kind = Kind.OST, info = "Soundtrack",
                creator = artist, publisher = label, released = r.optString("date"), genre = "Soundtrack",
                platforms = listOfNotNull(format.takeIf { it.isNotBlank() }),
            )
        }
    }

    fun kindFrom(d: String): Kind? {
        val s = d.lowercase()
        return when {
            "controller" in s || "accessory" in s || "peripheral" in s || "headset" in s -> Kind.ACCESSORY
            "console" in s || "handheld" in s -> Kind.CONSOLE
            "soundtrack" in s || "album" in s -> Kind.OST
            "video game" in s || "game" in s -> Kind.GAME
            "novel" in s || "book" in s || "manga" in s || "comic" in s -> Kind.BOOK
            "toy" in s || "figure" in s || "film" in s -> Kind.MISC
            else -> null
        }
    }

    private fun significant(s: String) =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", AGENT)
        c.setRequestProperty("Accept", "application/json")
        try {
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}
