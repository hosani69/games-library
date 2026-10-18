package io.github.hosani69.gameslibrary

/** One line of text read from the picture, with its size and position (pixels). */
data class TextLine(val text: String, val height: Int, val top: Int)

/** What the picture tells us before searching the internet. */
data class Clues(val title: String, val kind: Kind?, val platform: String, val year: String)

/**
 * Turns the text and labels read from a picture into search clues: the title (the biggest words
 * that aren't logos or rating badges), the platform printed on the box, and the kind of item.
 * Plain Kotlin so it can be tested off the phone.
 */
object Extractor {
    val platforms: List<Pair<Regex, String>> = listOf(
        "switch\\s*2" to "Nintendo Switch 2",
        "nintendo\\s*switch|\\bswitch\\b" to "Nintendo Switch",
        "playstation\\s*®?\\s*5|\\bps\\s?5\\b" to "PlayStation 5",
        "playstation\\s*®?\\s*4|\\bps\\s?4\\b" to "PlayStation 4",
        "playstation\\s*®?\\s*3|\\bps\\s?3\\b" to "PlayStation 3",
        "playstation\\s*®?\\s*2|\\bps\\s?2\\b" to "PlayStation 2",
        "ps\\s*vita|playstation\\s*vita" to "PS Vita",
        "\\bpsp\\b" to "PSP",
        "xbox\\s*series" to "Xbox Series X|S",
        "xbox\\s*one" to "Xbox One",
        "xbox\\s*360" to "Xbox 360",
        "\\bwii\\s*u\\b" to "Wii U",
        "\\bwii\\b" to "Wii",
        "gamecube" to "GameCube",
        "nintendo\\s*64|\\bn64\\b" to "Nintendo 64",
        "\\b3ds\\b" to "Nintendo 3DS",
        "nintendo\\s*ds\\b|\\bnds\\b" to "Nintendo DS",
        "game\\s*boy\\s*advance|\\bgba\\b" to "Game Boy Advance",
        "game\\s*boy\\s*color" to "Game Boy Color",
        "game\\s*boy" to "Game Boy",
        "super\\s*nintendo|\\bsnes\\b" to "SNES",
        "dreamcast" to "Dreamcast",
        "mega\\s*drive|genesis" to "Mega Drive / Genesis",
        "sega\\s*saturn" to "Sega Saturn",
        "steam\\s*deck" to "Steam Deck",
        "\\bpc\\b|windows|pc\\s*dvd" to "PC",
        "playstation" to "PlayStation",
        "\\bxbox\\b" to "Xbox",
    ).map { (p, name) -> Regex(p, RegexOption.IGNORE_CASE) to name }

    // Text printed on boxes that is never the title.
    private val noise = Regex(
        "^(?:(?:pegi|esrb|usk|cero|rating|rated|everyone|teen|mature|only on|exclusive|nintendo|sony|microsoft|" +
            "playstation|xbox|sega|bandai namco|ubisoft|ea|capcom|square enix|www|http|online|players?|" +
            "blu-?ray|dvd|disc|cd|includes|video game)\\b|\\d+\\+?$|\\d+\\s?(?:gb|tb)$|\\p{L}$)",
        RegexOption.IGNORE_CASE,
    )

    private val accessoryWords = Regex("controller|joy-?con|dualsense|dualshock|headset|charging|charger|cable|adapter|amiibo|steering|memory card|stand|grip|remote|pro controller", RegexOption.IGNORE_CASE)
    private val consoleWords = Regex("\\bconsole\\b|\\bsystem\\b|\\bbundle\\b|\\b\\d+\\s?(gb|tb)\\b|oled|slim|digital edition|handheld", RegexOption.IGNORE_CASE)
    private val ostWords = Regex("soundtrack|\\bost\\b|original score|music from|vinyl|\\blp\\b|orchestra|symphon", RegexOption.IGNORE_CASE)
    private val bookWords = Regex("isbn|novel|\\bauthor\\b|manga|vol\\.|volume|a novel|art of|artbook|strategy guide|official guide|paperback|hardcover|bestselling", RegexOption.IGNORE_CASE)

    fun clues(lines: List<TextLine>, labels: List<String>): Clues {
        val all = lines.joinToString(" ") { it.text }
        val platform = platforms.firstOrNull { (re, _) -> re.containsMatchIn(all) }?.second ?: ""
        val kind = when {
            ostWords.containsMatchIn(all) -> Kind.OST
            accessoryWords.containsMatchIn(all) -> Kind.ACCESSORY
            platform.isNotEmpty() && consoleWords.containsMatchIn(all) -> Kind.CONSOLE
            platform.isNotEmpty() -> Kind.GAME
            bookWords.containsMatchIn(all) -> Kind.BOOK
            labels.any { it.equals("Joystick", true) || it.equals("Gadget", true) } -> Kind.ACCESSORY
            labels.any { it.equals("Comics", true) || it.equals("Paper", true) } -> Kind.BOOK
            labels.any { it.equals("Toy", true) || it.equals("Figurine", true) } -> Kind.MISC
            else -> null
        }
        val year = Regex("\\b(19[7-9]\\d|20[0-4]\\d)\\b").find(all)?.value ?: ""
        val title = title(lines).ifBlank { if (kind == Kind.CONSOLE || kind == Kind.ACCESSORY) platform else "" }
        return Clues(title, kind, platform, year)
    }

    /** The tallest meaningful line, plus neighbouring lines of similar size (titles often wrap). */
    fun title(lines: List<TextLine>): String {
        val useful = lines.map { it.copy(text = clean(it.text)) }.filter { l ->
            l.text.count { it.isLetter() } >= 2 &&
                !noise.containsMatchIn(l.text) &&
                platforms.none { (re, _) -> re.matches(l.text.trim()) }
        }
        if (useful.isEmpty()) return ""
        val main = useful.maxBy { it.height }
        val group = useful
            .filter { it.height >= main.height * 0.4 && kotlin.math.abs(it.top - main.top) <= main.height * 2.6 }
            .sortedBy { it.top }
            .take(3)
        return tidyCase(group.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim())
    }

    private fun clean(s: String) = s.replace(Regex("[®™©]"), "").replace(Regex("[^\\p{L}\\p{N} :'&.\\-!?]"), " ").trim()

    /** "SUPER MARIO ODYSSEY" -> "Super Mario Odyssey"; mixed case is left alone. */
    fun tidyCase(s: String): String {
        if (s.any { it.isLowerCase() }) return s
        val small = setOf("of", "the", "and", "a", "an", "in", "on", "to", "for", "at", "by")
        return s.lowercase().split(" ").mapIndexed { i, w ->
            if (i > 0 && w in small) w else if (w.length <= 3 && w.all { it in "ivx" }) w.uppercase() else w.replaceFirstChar { it.titlecase() }
        }.joinToString(" ")
    }
}
