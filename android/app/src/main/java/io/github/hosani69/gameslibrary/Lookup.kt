package io.github.hosani69.gameslibrary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Match(val title: String, val kind: Kind?, val info: String)

/** Corrects the title read from the cover and fills in a short description from Wikipedia (free, no key). */
object Lookup {
    private const val AGENT = "GamesLibrary/1.0 (https://github.com/hosani69/games-library)"

    suspend fun find(coverText: String, hint: Kind? = null): Match? = withContext(Dispatchers.IO) {
        if (coverText.length < 3) return@withContext null
        val suffix = when (hint) {
            Kind.CONSOLE -> " console"
            Kind.OST -> " soundtrack"
            Kind.BOOK -> " book"
            else -> ""
        }
        val q = URLEncoder.encode(coverText + suffix, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=5" +
            "&prop=description&gsrsearch=$q"
        val json = runCatching { get(url) }.getOrNull() ?: return@withContext null
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null
        val words = significant(coverText)
        pages.keys().asSequence().map { pages.getJSONObject(it) }
            .sortedBy { it.optInt("index") }
            .firstOrNull { p -> significant(p.optString("title")).any { it in words } }
            ?.let { p ->
                val description = p.optString("description")
                Match(
                    title = p.optString("title").replace(Regex("\\s*\\([^)]*\\)$"), ""),
                    kind = kindFrom(description),
                    info = description.replaceFirstChar { it.uppercase() },
                )
            }
    }

    private fun kindFrom(d: String): Kind? {
        val s = d.lowercase()
        return when {
            "console" in s || "handheld" in s -> Kind.CONSOLE
            "soundtrack" in s || "album" in s -> Kind.OST
            "video game" in s || "game" in s -> Kind.GAME
            "novel" in s || "book" in s || "manga" in s || "comic" in s -> Kind.BOOK
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
        try {
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}
