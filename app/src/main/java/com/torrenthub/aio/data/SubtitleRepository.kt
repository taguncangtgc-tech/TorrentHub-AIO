package com.torrenthub.aio.data

import com.torrenthub.aio.model.SubtitleItem
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object SubtitleRepository {
    private const val XUNLEI_API = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle"
    val SUPPORTED_TEXT_EXTS = setOf("srt", "ass", "ssa", "vtt")

    // Bounded cache of subtitle text decoded during size probing so Preview / Save / Gemini is instant
    private val textCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(48, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 40
        }
    )

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        // Fallback to GB18030 if legacy Chinese ANSI subtitle has replacement characters
        if (utf8.count { it == '\uFFFD' } > 4) {
            return try {
                String(bytes, Charset.forName("GB18030"))
            } catch (_: Exception) {
                utf8
            }
        }
        return utf8
    }

    suspend fun searchSubtitles(query: String): List<SubtitleItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$XUNLEI_API?gcid=&cid=&name=${Formatters.urlEncode(q)}"
        val raw = HttpClient.getString(url, headers = mapOf("Accept" to "application/json, text/plain, */*"), timeoutSec = 22L)
        val root = JSONObject(raw)

        val arr: JSONArray = when {
            root.optJSONArray("data") != null -> root.optJSONArray("data")!!
            root.optJSONObject("data")?.optJSONArray("list") != null -> root.optJSONObject("data")!!.optJSONArray("list")!!
            root.optJSONArray("list") != null -> root.optJSONArray("list")!!
            else -> JSONArray()
        }

        val seen = HashSet<String>()
        val list = mutableListOf<SubtitleItem>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val dlUrl = item.optString("url").ifBlank { item.optString("link") }.ifBlank { item.optString("download_url") }.trim()
            if (dlUrl.isBlank()) continue

            val rawName = item.optString("name").ifBlank { item.optString("filename") }.ifBlank { item.optString("title") }.trim()
            val directExt = item.optString("ext").removePrefix(".").lowercase(Locale.ROOT).trim()
            val ext = directExt.ifBlank {
                Regex("\\.([a-z0-9]{2,5})$", RegexOption.IGNORE_CASE).find(rawName)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                    ?: Regex("\\.([a-z0-9]{2,5})(?:[?#]|$)", RegexOption.IGNORE_CASE).find(dlUrl)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                    ?: "srt"
            }
            var safeName = rawName.ifBlank { "$q.$ext" }
            if (ext.isNotBlank() && !safeName.endsWith(".$ext", ignoreCase = true)) {
                safeName += ".$ext"
            }
            safeName = safeName.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_").trim()

            val dedupKey = "$dlUrl|$safeName"
            if (!seen.add(dedupKey)) continue

            val initSize = listOf("size_bytes", "bytes", "file_size", "filesize", "size")
                .firstNotNullOfOrNull { k -> Formatters.parseSizeToBytes(item.opt(k)).takeIf { it > 0L } } ?: 0L

            val langArr = item.optJSONArray("languages")
            val langs = buildList {
                if (langArr != null) {
                    for (j in 0 until langArr.length()) {
                        val l = langArr.optString(j).trim()
                        if (l.isNotEmpty()) add(l)
                    }
                }
            }.joinToString(", ")

            val durationMs = item.optLong("duration", 0L)

            list.add(
                SubtitleItem(
                    idKey = dedupKey,
                    name = safeName,
                    ext = ext,
                    url = dlUrl,
                    sizeBytes = initSize,
                    language = langs,
                    durationMs = durationMs
                )
            )
        }
        return list
    }

    suspend fun probeSizes(
        items: List<SubtitleItem>,
        onProgress: ((done: Int, total: Int, updatedItem: SubtitleItem) -> Unit)? = null
    ): List<SubtitleItem> = coroutineScope {
        val sem = Semaphore(5)
        val completed = AtomicInteger(0)
        items.map { item ->
            async {
                val updated = if (item.sizeBytes > 0L) {
                    item
                } else {
                    sem.withPermit {
                        val probe = HttpClient.probeSizeAndOptionalBytes(item.url)
                        if (probe.fullBytes != null && item.ext in SUPPORTED_TEXT_EXTS) {
                            textCache[item.url] = decodeSubtitleBytes(probe.fullBytes)
                        }
                        item.copy(sizeBytes = probe.sizeBytes)
                    }
                }
                val doneNow = completed.incrementAndGet()
                onProgress?.invoke(doneNow, items.size, updated)
                updated
            }
        }.awaitAll()
    }

    suspend fun fetchSubtitleText(url: String): String {
        textCache[url]?.let { if (it.isNotBlank()) return it }
        val probe = HttpClient.probeSizeAndOptionalBytes(url, maxReadBytes = 10 * 1024 * 1024)
        if (probe.fullBytes != null) {
            val decoded = decodeSubtitleBytes(probe.fullBytes)
            textCache[url] = decoded
            return decoded
        }
        val fetched = HttpClient.getString(url, timeoutSec = 25L)
        if (fetched.isNotBlank()) textCache[url] = fetched
        return fetched
    }
}
