package io.github.hosani69.gameslibrary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Daily exchange rates from ExchangeRate-API's free open endpoint (no key, no account). */
object Rates {
    suspend fun fetch(): Map<String, Double>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://open.er-api.com/v6/latest/AED").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            try {
                val o = JSONObject(c.inputStream.bufferedReader().readText())
                if (o.optString("result") != "success") return@runCatching null
                val r = o.getJSONObject("rates")
                Currencies.codes.mapNotNull { code -> r.optDouble(code).takeIf { !it.isNaN() && it > 0 }?.let { code to it } }.toMap()
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    fun today(): String = LocalDate.now().toString()
}
