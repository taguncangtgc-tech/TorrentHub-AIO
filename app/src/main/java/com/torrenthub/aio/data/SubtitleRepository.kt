package com.torrenthub.aio.data

import com.torrenthub.aio.model.SubtitleItem
import com.torrenthub.aio.model.SubtitleProvider
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import com.torrenthub.aio.util.SubtitleCharset
import com.torrenthub.aio.util.SubtitleTools
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object SubtitleRepository {
    private const val XUNLEI_API = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle"
    private const val SUBTITLECAT_BASE = "https://www.subtitlecat.com"
    private const val OPENSUBTITLES_REST = "https://rest.opensubtitles.org/search"
    private const val OPENSUBTITLES_WEB = "https://www.opensubtitles.org"
    private const val SUBDL_WEB = "https://subdl.com"
    private const val SUBDL_DL = "https://dl.subdl.com/subtitle"

    val SUPPORTED_TEXT_EXTS = setOf("srt", "ass", "ssa", "vtt")

    private val textCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(48, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 40
        }
    )

    private val bytesCache: MutableMap<String, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(36, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 28
        }
    )

    private fun unpackArchiveIfNeeded(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size < 4) return rawBytes
        val b0 = rawBytes[0].toInt() and 0xFF
        val b1 = rawBytes[1].toInt() and 0xFF
        val b2 = rawBytes[2].toInt() and 0xFF
        val b3 = rawBytes[3].toInt() and 0xFF

        // GZIP magic: 1F 8B
        if (b0 == 0x1F && b1 == 0x8B) {
            return try {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gis ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var readTotal = 0
                    while (true) {
                        val n = gis.read(buf)
                        if (n <= 0) break
                        readTotal += n
                        if (readTotal > 12 * 1024 * 1024) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray().takeIf { it.isNotEmpty() } ?: rawBytes
                }
            } catch (_: Exception) {
                rawBytes
            }
        }

        // ZIP magic: 50 4B 03 04
        if (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) {
            return try {
                var bestBytes: ByteArray? = null
                var bestScore = -1L
                ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory || entry.name.contains("__MACOSX")) continue
                        val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        if (ext in SUPPORTED_TEXT_EXTS) {
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                total += n
                                if (total > 10 * 1024 * 1024) break
                                out.write(buf, 0, n)
                            }
                            val entryBytes = out.toByteArray()
                            val score = entryBytes.size.toLong() + (if (ext == "srt") 50_000L else 0L)
                            if (entryBytes.isNotEmpty() && score > bestScore) {
                                bestScore = score
                                bestBytes = entryBytes
                            }
                        }
                    }
                }
                bestBytes ?: rawBytes
            } catch (_: Exception) {
                rawBytes
            }
        }

        return rawBytes
    }

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        val unpacked = unpackArchiveIfNeeded(bytes)
        return SubtitleTools.decodeWithCharset(unpacked, SubtitleCharset.AUTO)
    }

    private fun sanitizeFileName(rawName: String, fallbackBase: String, ext: String): String {
        val cleanExt = (if (ext in SUPPORTED_TEXT_EXTS) ext else "srt").lowercase(Locale.ROOT)
        var safe = rawName.ifBlank { "$fallbackBase.$cleanExt" }
            .replace(Regex("\\.(zip|gz)$", RegexOption.IGNORE_CASE), "")
        if (!safe.endsWith(".$cleanExt", ignoreCase = true)) {
            safe += ".$cleanExt"
        }
        return safe.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_").trim()
    }

    suspend fun searchSubtitles(
        query: String,
        provider: SubtitleProvider = SubtitleProvider.ALL,
        targetLang: String = "id"
    ): List<SubtitleItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        return when (provider) {
            SubtitleProvider.XUNLEI -> searchXunlei(q)
            SubtitleProvider.SUBTITLECAT -> searchSubtitleCat(q, targetLang)
            SubtitleProvider.OPENSUBTITLES -> searchOpenSubtitles(q, targetLang)
            SubtitleProvider.SUBDL -> searchSubdl(q, targetLang)
            SubtitleProvider.ALL -> supervisorScope {
                val tasks = listOf(
                    async { runCatching { searchXunlei(q) }.getOrDefault(emptyList()) },
                    async { runCatching { searchSubtitleCat(q, targetLang) }.getOrDefault(emptyList()) },
                    async { runCatching { searchOpenSubtitles(q, targetLang) }.getOrDefault(emptyList()) },
                    async { runCatching { searchSubdl(q, targetLang) }.getOrDefault(emptyList()) }
                )
                val combined = tasks.awaitAll().flatten()
                val seenUrls = HashSet<String>()
                combined.filter { seenUrls.add("${it.sourceId}|${it.url}|${it.name}") }
            }
        }
    }

    private suspend fun searchXunlei(q: String): List<SubtitleItem> {
        val url = "$XUNLEI_API?gcid=&cid=&name=${Formatters.urlEncode(q)}"
        val raw = HttpClient.getString(
            url,
            headers = mapOf("Accept" to "application/json, text/plain, */*"),
            timeoutSec = 20L
        )
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
            val safeName = sanitizeFileName(rawName, q, ext)
            val dedupKey = "xunlei|$dlUrl|$safeName"
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
                    ext = if (ext in SUPPORTED_TEXT_EXTS) ext else "srt",
                    url = dlUrl,
                    sizeBytes = initSize,
                    language = langs,
                    durationMs = durationMs,
                    sourceId = SubtitleProvider.XUNLEI.id,
                    sourceName = SubtitleProvider.XUNLEI.label
                )
            )
        }
        return list
    }

    private suspend fun searchSubtitleCat(q: String, targetLang: String): List<SubtitleItem> {
        val searchUrl = "$SUBTITLECAT_BASE/index.php?search=${Formatters.urlEncode(q)}"
        val html = HttpClient.getString(searchUrl, headers = HttpClient.BROWSER_HTML_HEADERS, timeoutSec = 18L)
        val doc = Jsoup.parse(html, SUBTITLECAT_BASE)

        val rows = doc.select("table.sub-table tbody tr, table tbody tr")
        val list = mutableListOf<SubtitleItem>()
        val seen = HashSet<String>()

        for (tr in rows) {
            val linkEl = tr.selectFirst("a[href*='subs/']") ?: continue
            val href = linkEl.absUrl("href").ifBlank {
                val rawHref = linkEl.attr("href").trim()
                if (rawHref.startsWith("/")) "$SUBTITLECAT_BASE$rawHref" else "$SUBTITLECAT_BASE/$rawHref"
            }
            if (href.isBlank() || !seen.add(href)) continue

            val title = linkEl.text().trim().ifBlank { q }
            val tds = tr.select("td")
            val langInfo = tds.getOrNull(1)?.text()?.replace("translated from", "")?.trim().orEmpty()
                .ifBlank { targetLang.uppercase(Locale.ROOT) }
            val safeName = sanitizeFileName(title, q, "srt")

            list.add(
                SubtitleItem(
                    idKey = "subtitlecat|$href",
                    name = safeName,
                    ext = "srt",
                    url = href,
                    sizeBytes = 0L,
                    language = langInfo.take(18),
                    sourceId = SubtitleProvider.SUBTITLECAT.id,
                    sourceName = SubtitleProvider.SUBTITLECAT.label
                )
            )
            if (list.size >= 25) break
        }
        return list
    }

    private fun openSubLangId(targetLang: String): String = when (targetLang.lowercase(Locale.ROOT)) {
        "id" -> "ind"
        "en" -> "eng"
        "zh" -> "chi"
        "ja" -> "jpn"
        "ko" -> "kor"
        else -> "all"
    }

    private suspend fun searchOpenSubtitles(q: String, targetLang: String): List<SubtitleItem> {
        val list = mutableListOf<SubtitleItem>()
        val seen = HashSet<String>()

        // 1) Official OpenSubtitles REST JSON API
        try {
            val restUrl = "$OPENSUBTITLES_REST/query-${Formatters.urlEncode(q.lowercase(Locale.ROOT))}"
            val raw = HttpClient.getString(
                restUrl,
                headers = mapOf(
                    "X-User-Agent" to "TemporaryUserAgent",
                    "Accept" to "application/json"
                ),
                timeoutSec = 18L
            )
            if (raw.trimStart().startsWith("[")) {
                val arr = JSONArray(raw)
                val preferredIso = openSubLangId(targetLang)
                val parsed = mutableListOf<Pair<Int, SubtitleItem>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val dlUrl = obj.optString("SubDownloadLink").ifBlank { obj.optString("ZipDownloadLink") }.trim()
                    if (dlUrl.isBlank() || !seen.add(dlUrl)) continue
                    val rawName = obj.optString("SubFileName").ifBlank { obj.optString("MovieReleaseName") }.ifBlank { q }
                    val fmt = obj.optString("SubFormat", "srt").lowercase(Locale.ROOT)
                    val ext = if (fmt in SUPPORTED_TEXT_EXTS) fmt else "srt"
                    val size = obj.optString("SubSize").toLongOrNull() ?: 0L
                    val langName = obj.optString("LanguageName").ifBlank { obj.optString("SubLanguageID") }
                    val subLangId = obj.optString("SubLanguageID").lowercase(Locale.ROOT)
                    val priority = when {
                        subLangId == preferredIso -> 0
                        subLangId == "eng" -> 1
                        else -> 2
                    }
                    val safeName = sanitizeFileName(rawName, q, ext)
                    parsed.add(
                        priority to SubtitleItem(
                            idKey = "opensub|$dlUrl",
                            name = safeName,
                            ext = ext,
                            url = dlUrl,
                            sizeBytes = size,
                            language = langName.take(16),
                            sourceId = SubtitleProvider.OPENSUBTITLES.id,
                            sourceName = SubtitleProvider.OPENSUBTITLES.label
                        )
                    )
                }
                parsed.sortedBy { it.first }.take(30).forEach { list.add(it.second) }
            }
        } catch (_: Exception) {}

        if (list.isNotEmpty()) return list

        // 2) Fallback HTML scraping on opensubtitles.org
        try {
            val webUrl = "$OPENSUBTITLES_WEB/en/search2/sublanguageid-all/moviename-${Formatters.urlEncode(q)}"
            val html = HttpClient.getString(webUrl, headers = HttpClient.BROWSER_HTML_HEADERS, timeoutSec = 18L)
            val doc = Jsoup.parse(html, OPENSUBTITLES_WEB)
            for (tr in doc.select("table#search_results tr.change, tr[id^='name']")) {
                val mainLink = tr.selectFirst("a.bnone[href*='/subtitles/']") ?: continue
                val subHref = mainLink.attr("href")
                val subId = Regex("/subtitles/(\\d+)/").find(subHref)?.groupValues?.get(1) ?: continue
                val dlUrl = "$OPENSUBTITLES_WEB/en/subtitleserve/sub/$subId"
                if (!seen.add(dlUrl)) continue

                val releaseText = tr.selectFirst("td[id^='main']")?.ownText()?.trim().orEmpty()
                val rawTitle = releaseText.ifBlank { mainLink.text().trim() }.ifBlank { q }
                val lang = tr.selectFirst("a[href*='/sublanguageid-']")?.attr("title").orEmpty()
                val safeName = sanitizeFileName(rawTitle, q, "srt")
                list.add(
                    SubtitleItem(
                        idKey = "opensub|$dlUrl",
                        name = safeName,
                        ext = "srt",
                        url = dlUrl,
                        sizeBytes = 0L,
                        language = lang.take(16),
                        sourceId = SubtitleProvider.OPENSUBTITLES.id,
                        sourceName = SubtitleProvider.OPENSUBTITLES.label
                    )
                )
                if (list.size >= 25) break
            }
        } catch (_: Exception) {}

        return list
    }

    private suspend fun searchSubdl(q: String, targetLang: String): List<SubtitleItem> {
        val list = mutableListOf<SubtitleItem>()
        val seen = HashSet<String>()

        // 1) Try SubDL public web search & Next.js JSON payload
        try {
            val searchUrl = "$SUBDL_WEB/search/${Formatters.urlEncode(q)}"
            val html = HttpClient.getString(searchUrl, headers = HttpClient.BROWSER_HTML_HEADERS, timeoutSec = 18L)
            val doc = Jsoup.parse(html, SUBDL_WEB)

            // Parse subtitle page links
            val pageLinks = mutableListOf<Pair<String, String>>()
            val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data().orEmpty()
            if (nextData.isNotBlank()) {
                Regex("\"(?:slug|link|url)\"\\s*:\\s*\"(/subtitle/sd\\d+[^\"]*)\"").findAll(nextData).forEach { m ->
                    val path = m.groupValues[1]
                    pageLinks.add("$SUBDL_WEB$path" to path.substringAfterLast('/'))
                }
            }
            doc.select("a[href*='/subtitle/sd']").forEach { a ->
                val abs = a.absUrl("href")
                if (abs.isNotBlank()) pageLinks.add(abs to a.text().trim())
            }

            val distinctPages = pageLinks.distinctBy { it.first }.take(4)
            if (distinctPages.isNotEmpty()) {
                val sem = Semaphore(3)
                coroutineScope {
                    distinctPages.map { (pageUrl, pageTitle) ->
                        async {
                            sem.withPermit {
                                runCatching {
                                    val subHtml = HttpClient.getString(pageUrl, headers = HttpClient.BROWSER_HTML_HEADERS, timeoutSec = 15L)
                                    extractSubdlDownloadsFromPage(subHtml, pageUrl, pageTitle.ifBlank { q }, targetLang)
                                }.getOrDefault(emptyList())
                            }
                        }
                    }.awaitAll().flatten().forEach { item ->
                        if (seen.add(item.url)) list.add(item)
                    }
                }
            }
        } catch (_: Exception) {}

        return list.take(30)
    }

    private fun extractSubdlDownloadsFromPage(
        html: String,
        baseUrl: String,
        fallbackTitle: String,
        targetLang: String
    ): List<SubtitleItem> {
        val items = mutableListOf<SubtitleItem>()
        val doc = Jsoup.parse(html, baseUrl)

        // Direct dl.subdl.com zip links in anchors or Next.js data
        doc.select("a[href*='dl.subdl.com/subtitle/']").forEach { a ->
            val dlUrl = a.absUrl("href").ifBlank { a.attr("href") }.trim()
            if (dlUrl.isBlank()) return@forEach
            val rowText = a.parent()?.parent()?.text()?.trim().orEmpty()
            val nameCand = a.text().trim().takeIf { it.length > 4 && !it.equals("Download", ignoreCase = true) }
                ?: rowText.take(80).ifBlank { fallbackTitle }
            items.add(
                SubtitleItem(
                    idKey = "subdl|$dlUrl",
                    name = sanitizeFileName(nameCand, fallbackTitle, "srt"),
                    ext = "srt",
                    url = dlUrl,
                    sizeBytes = 0L,
                    language = targetLang.uppercase(Locale.ROOT),
                    sourceId = SubtitleProvider.SUBDL.id,
                    sourceName = SubtitleProvider.SUBDL.label
                )
            )
        }

        if (items.isEmpty()) {
            val nextJson = doc.selectFirst("script#__NEXT_DATA__")?.data().orEmpty()
            if (nextJson.isNotBlank()) {
                Regex("\"url\"\\s*:\\s*\"([^\"]*\\d+\\.zip)\"[^}]*?\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(nextJson).forEach { m ->
                    val rawPath = m.groupValues[1]
                    val dlUrl = if (rawPath.startsWith("http")) rawPath else "$SUBDL_DL/${rawPath.trimStart('/')}"
                    val rName = m.groupValues[2]
                    items.add(
                        SubtitleItem(
                            idKey = "subdl|$dlUrl",
                            name = sanitizeFileName(rName, fallbackTitle, "srt"),
                            ext = "srt",
                            url = dlUrl,
                            sizeBytes = 0L,
                            language = "",
                            sourceId = SubtitleProvider.SUBDL.id,
                            sourceName = SubtitleProvider.SUBDL.label
                        )
                    )
                }
            }
        }
        return items
    }

    private suspend fun resolveActualDownloadUrl(url: String, targetLang: String): String {
        if (url.contains("subtitlecat.com", ignoreCase = true) &&
            (url.endsWith(".html", ignoreCase = true) || !url.endsWith(".srt", ignoreCase = true))
        ) {
            val html = HttpClient.getString(url, headers = HttpClient.BROWSER_HTML_HEADERS, timeoutSec = 18L)
            val doc = Jsoup.parse(html, SUBTITLECAT_BASE)
            val langSuffix = when (targetLang.lowercase(Locale.ROOT)) {
                "zh" -> "-zh-CN.srt"
                else -> "-${targetLang.lowercase(Locale.ROOT)}.srt"
            }
            val links = doc.select("a[href$='.srt']")
            val preferred = links.firstOrNull { it.attr("href").endsWith(langSuffix, ignoreCase = true) }
                ?: links.firstOrNull { it.attr("href").endsWith("-orig.srt", ignoreCase = true) }
                ?: links.firstOrNull { it.attr("href").endsWith("-en.srt", ignoreCase = true) }
                ?: links.firstOrNull()
            val resolved = preferred?.absUrl("href")?.takeIf { it.isNotBlank() }
                ?: preferred?.attr("href")?.let { if (it.startsWith("/")) "$SUBTITLECAT_BASE$it" else "$SUBTITLECAT_BASE/$it" }
            if (!resolved.isNullOrBlank()) return resolved
        }
        return url
    }

    suspend fun probeSizes(
        items: List<SubtitleItem>,
        onProgress: ((done: Int, total: Int, updatedItem: SubtitleItem) -> Unit)? = null
    ): List<SubtitleItem> = coroutineScope {
        val sem = Semaphore(5)
        val completed = AtomicInteger(0)
        items.mapIndexed { index, item ->
            async {
                val isDetailPage = item.url.contains("subtitlecat.com") && item.url.endsWith(".html", ignoreCase = true)
                val updated = if (item.sizeBytes > 0L || isDetailPage || index >= 25) {
                    item
                } else {
                    sem.withPermit {
                        val probe = HttpClient.probeSizeAndOptionalBytes(item.url)
                        if (probe.fullBytes != null && item.ext in SUPPORTED_TEXT_EXTS) {
                            val unpacked = unpackArchiveIfNeeded(probe.fullBytes)
                            bytesCache[item.url] = unpacked
                            textCache[item.url] = SubtitleTools.decodeWithCharset(unpacked, SubtitleCharset.AUTO)
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

    suspend fun fetchSubtitleBytes(url: String, targetLang: String = "id"): ByteArray {
        bytesCache[url]?.let { if (it.isNotEmpty()) return it }
        val resolvedUrl = resolveActualDownloadUrl(url, targetLang)
        bytesCache[resolvedUrl]?.let {
            if (it.isNotEmpty()) {
                bytesCache[url] = it
                return it
            }
        }
        val downloaded = HttpClient.getBytes(resolvedUrl, headers = HttpClient.BROWSER_HTML_HEADERS, timeoutSec = 25L)
        val unpacked = unpackArchiveIfNeeded(downloaded)
        if (unpacked.isNotEmpty()) {
            bytesCache[url] = unpacked
            bytesCache[resolvedUrl] = unpacked
            textCache[url] = SubtitleTools.decodeWithCharset(unpacked, SubtitleCharset.AUTO)
        }
        return unpacked
    }

    suspend fun fetchSubtitleText(url: String, targetLang: String = "id"): String {
        textCache[url]?.let { if (it.isNotBlank()) return it }
        val bytes = fetchSubtitleBytes(url, targetLang)
        if (bytes.isNotEmpty()) {
            val decoded = decodeSubtitleBytes(bytes)
            if (decoded.isNotBlank()) {
                textCache[url] = decoded
                return decoded
            }
        }
        val resolved = resolveActualDownloadUrl(url, targetLang)
        val fetched = HttpClient.getString(resolved, timeoutSec = 25L)
        if (fetched.isNotBlank()) textCache[url] = fetched
        return fetched
    }
}
