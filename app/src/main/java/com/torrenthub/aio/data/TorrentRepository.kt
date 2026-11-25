package com.torrenthub.aio.data

import com.torrenthub.aio.model.SourceDef
import com.torrenthub.aio.model.TorrentItem
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.network.HttpStatusException
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URI
import java.util.Locale

object TorrentRepository {

    /** Android JSONObject.optString returns literal "null" for JSON null values! */
    private fun JSONObject.optCleanString(key: String): String {
        if (isNull(key)) return ""
        val v = optString(key, "").trim()
        return if (v.equals("null", ignoreCase = true)) "" else v
    }

    private fun isValidTorrentLink(link: String): Boolean =
        link.startsWith("magnet:", ignoreCase = true) || link.startsWith("ed2k:", ignoreCase = true)

    val SOURCES = listOf(
        SourceDef("javdb", "JavDB", 0xFFE11D48, defaultOn = true),
        SourceDef("rargb", "RARBG", 0xFF1D4ED8, defaultOn = true, supportsWebViewBridge = true),
        SourceDef("bt4g", "BT4G", 0xFF0891B2, defaultOn = true, supportsWebViewBridge = true),
        SourceDef("knaben", "Knaben", 0xFF2563EB, defaultOn = true),
        SourceDef("tpb", "TPB", 0xFFD97706, defaultOn = true),
        SourceDef("torrentscsv", "Torrents-CSV", 0xFF059669, defaultOn = true),
        SourceDef("yts", "YTS (Film)", 0xFF16A34A, defaultOn = true),
        SourceDef("nyaa", "Nyaa.si", 0xFF0284C7, defaultOn = true),
        SourceDef("sukebei", "Sukebei", 0xFFDB2777, defaultOn = true),
        SourceDef("u3c3", "U3C3", 0xFFEA580C, defaultOn = true),
        SourceDef("btsearch", "BTSearch", 0xFF65A30D, defaultOn = true),
        SourceDef("u9a9", "U9A9", 0xFF0D9488, defaultOn = true),
        SourceDef("sokitty", "SoKitty", 0xFFBE185D, defaultOn = true),
        SourceDef("omag", "ØMagnet", 0xFF4338CA, defaultOn = false),
        SourceDef("bitsearch", "BitSearch", 0xFF7C3AED, defaultOn = false),
        SourceDef("btdig", "BTDig", 0xFF475569, defaultOn = false)
    )

    private const val JAVDB_API_BASE = "https://jdforrepam.com/api"
    private const val JAVDB_SECRET =
        "71cf27bb3c0bcdf207b64abecddc970098c7421ee7203b9cdae54478478a199e7d5a6e1a57691123c1a931c057842fb73ba3b3c83bcd69c17ccf174081e3d8aa"

    private fun buildJavdbSignature(): String {
        val ts = System.currentTimeMillis() / 1000L
        return "$ts.lpw6vgqzsp.${Formatters.md5Hex("$ts$JAVDB_SECRET")}"
    }

    private fun javdbCoverUrl(movieId: String): String {
        val id = movieId.trim()
        return if (id.length >= 2) {
            "https://c0.jdbstatic.com/covers/${id.take(2).lowercase(Locale.ROOT)}/${Formatters.urlEncode(id)}.jpg"
        } else ""
    }

    fun isCloudflareDoc(doc: Document): Boolean {
        val title = doc.title().lowercase(Locale.ROOT)
        if (Regex("just a moment|attention required|checking your browser|security check").containsMatchIn(title)) {
            return true
        }
        return doc.selectFirst("#challenge-running, #challenge-stage, #cf-wrapper, .cf-browser-verification, iframe[src*=\"challenges.cloudflare.com\"]") != null
    }

    private fun resolveUrl(rel: String, base: String): String =
        try {
            URI(base).resolve(rel.trim()).toString()
        } catch (_: Exception) {
            ""
        }

    fun buildBypassSearchUrl(sourceId: String, query: String, sortMode: String): String {
        val q = Formatters.urlEncode(query)
        return when (sourceId) {
            "rargb" -> {
                val suffix = when (sortMode) {
                    "seeders-desc" -> "&order=seeders&by=DESC"
                    "leechers-desc" -> "&order=leechers&by=DESC"
                    "size-desc" -> "&order=size&by=DESC"
                    "size-asc" -> "&order=size&by=ASC"
                    "date-desc" -> "&order=data&by=DESC"
                    "date-asc" -> "&order=data&by=ASC"
                    else -> ""
                }
                "https://rargb.to/search/?search=$q$suffix"
            }
            "bt4g" -> {
                val orderby = when {
                    sortMode.startsWith("size") -> "size"
                    sortMode.startsWith("date") -> "time"
                    else -> "seeders"
                }
                "https://bt4gprx.com/search?q=$q&orderby=$orderby"
            }
            else -> ""
        }
    }

    suspend fun searchSource(sourceId: String, query: String, sortMode: String): List<TorrentItem> =
        when (sourceId) {
            "javdb" -> searchJavdb(query)
            "rargb" -> searchRargb(query, sortMode)
            "bt4g" -> searchBt4g(query, sortMode)
            "knaben" -> searchKnaben(query, sortMode)
            "tpb" -> searchTpb(query)
            "torrentscsv" -> searchTorrentsCsv(query)
            "yts" -> searchYts(query, sortMode)
            "nyaa" -> searchNyaa(query, sortMode, isSukebei = false)
            "sukebei" -> searchNyaa(query, sortMode, isSukebei = true)
            "u3c3" -> searchU3c3(query)
            "btsearch" -> searchBtSearch(query, sortMode)
            "u9a9" -> searchU9a9(query)
            "sokitty" -> searchSoKitty(query)
            "omag" -> searchOmag(query)
            "bitsearch" -> searchBitSearch(query, sortMode)
            "btdig" -> searchBtDig(query, sortMode)
            else -> emptyList()
        }

    private suspend fun <T, R> mapConcurrent(items: List<T>, concurrency: Int = 4, block: suspend (T) -> R?): List<R> =
        coroutineScope {
            val sem = Semaphore(concurrency)
            items.map { item ->
                async {
                    sem.withPermit {
                        try { block(item) } catch (_: Exception) { null }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    /* ================= 1. JAVDB ================= */
    private data class JavMovie(val id: String, val number: String, val title: String)

    private suspend fun searchJavdb(query: String): List<TorrentItem> {
        val fc2 = Regex("^(?:FC2[-_\\s]?(?:PPV[-_\\s]?)?|PPV[-_\\s]?)(\\d{6,9})$", RegexOption.IGNORE_CASE).find(query.trim())
        val searchQ = fc2?.groupValues?.get(1) ?: query.trim()
        val movies = mutableListOf<JavMovie>()

        try {
            val url = "$JAVDB_API_BASE/v2/search?q=${Formatters.urlEncode(searchQ)}&page=1&type=movie&limit=5&movie_type=all&from_recent=false&movie_filter_by=all&movie_sort_by=relevance"
            val raw = HttpClient.getString(
                url,
                headers = mapOf("Accept" to "application/json", "jdSignature" to buildJavdbSignature())
            )
            val arr = JSONObject(raw).optJSONObject("data")?.optJSONArray("movies")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val id = m.optString("id")
                    if (id.isNotBlank()) {
                        movies.add(JavMovie(id, m.optString("number"), m.optString("title")))
                    }
                }
            }
        } catch (_: Exception) {}

        if (movies.isEmpty()) {
            try {
                val html = HttpClient.getString(
                    "https://javdb.com/search?q=${Formatters.urlEncode(searchQ)}&f=all",
                    headers = HttpClient.BROWSER_HTML_HEADERS
                )
                val doc = Jsoup.parse(html, "https://javdb.com")
                for (el in doc.select(".movie-list .item")) {
                    val a = el.selectFirst("a[href^=\"/v/\"]") ?: continue
                    val href = a.attr("href")
                    val id = Regex("^/v/([^/?#]+)").find(href)?.groupValues?.get(1) ?: continue
                    val num = el.selectFirst(".video-title strong")?.text()?.trim().orEmpty()
                    movies.add(JavMovie(id, num, a.attr("title").ifBlank { num }))
                }
            } catch (_: Exception) {}
        }

        if (movies.isEmpty()) return emptyList()

        val normQ = searchQ.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        val exact = movies.firstOrNull {
            it.number.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "") == normQ
        }
        val targets = if (exact != null) listOf(exact) else movies.take(3)

        return mapConcurrent(targets, 3) { movie ->
            val out = mutableListOf<TorrentItem>()
            val detailUrl = "https://javdb.com/v/${Formatters.urlEncode(movie.id)}"
            val cover = javdbCoverUrl(movie.id)

            try {
                val mRaw = HttpClient.getString(
                    "$JAVDB_API_BASE/v2/movies/${Formatters.urlEncode(movie.id)}/magnets",
                    headers = mapOf("Accept" to "application/json", "jdSignature" to buildJavdbSignature())
                )
                val mags = JSONObject(mRaw).optJSONObject("data")?.optJSONArray("magnets")
                if (mags != null && mags.length() > 0) {
                    for (i in 0 until mags.length()) {
                        val mag = mags.optJSONObject(i) ?: continue
                        val baseName = mag.optString("name").ifBlank { movie.number.ifBlank { movie.title } }.trim()
                        val rawUrl = mag.optString("url").trim()
                        val link = rawUrl.ifBlank { Formatters.buildMagnet(mag.optString("hash"), baseName) }
                        if (!link.startsWith("magnet:", true) && !link.startsWith("ed2k:", true)) continue
                        val tags = buildList {
                            if (mag.optBoolean("cnsub") && !Regex("中字|字幕|-C\\b", RegexOption.IGNORE_CASE).containsMatchIn(baseName)) add("[中字]")
                            if (mag.optBoolean("hd") && !Regex("\\bHD\\b|4K|1080", RegexOption.IGNORE_CASE).containsMatchIn(baseName)) add("[HD]")
                        }.joinToString(" ")
                        val title = listOf(baseName, tags).filter { it.isNotBlank() }.joinToString(" ")
                        val sizeMb = mag.optDouble("size", 0.0)
                        out.add(
                            TorrentItem(
                                title = title,
                                magnet = link,
                                hash = Formatters.extractInfoHash(link),
                                size = if (sizeMb > 0) (sizeMb * 1024 * 1024).toLong() else 0L,
                                files = mag.optInt("files_count", 0).takeIf { it > 0 }?.toString().orEmpty(),
                                dateMs = Formatters.parseDateMs(mag.optString("created_at")),
                                pageUrl = detailUrl,
                                images = if (cover.isNotBlank()) listOf(cover) else emptyList(),
                                desc = movie.title,
                                source = "JavDB",
                                sourceId = "javdb"
                            )
                        )
                    }
                }
            } catch (_: Exception) {}

            if (out.isEmpty()) {
                try {
                    val dHtml = HttpClient.getString(detailUrl, headers = HttpClient.BROWSER_HTML_HEADERS)
                    val dDoc = Jsoup.parse(dHtml, detailUrl)
                    for (row in dDoc.select("#magnets-content .item")) {
                        val a = row.selectFirst(".magnet-name a[href^=\"magnet:\"], .magnet-name a[href^=\"ed2k:\"]") ?: continue
                        val link = a.attr("href").trim()
                        val name = row.selectFirst(".magnet-name .name")?.text()?.trim().orEmpty().ifBlank { movie.number }
                        val meta = row.selectFirst(".magnet-name .meta")?.text()?.trim().orEmpty()
                        val parts = meta.split(Regex("[,，]"))
                        val szStr = parts.getOrNull(0).orEmpty()
                        val fileStr = Regex("\\d+").find(parts.getOrNull(1).orEmpty())?.value.orEmpty()
                        val dateTxt = row.selectFirst(".date .time, .date")?.text()?.trim().orEmpty()
                        out.add(
                            TorrentItem(
                                title = name,
                                magnet = link,
                                hash = Formatters.extractInfoHash(link),
                                size = Formatters.parseSizeToBytes(szStr),
                                files = fileStr,
                                dateMs = Formatters.parseDateMs(dateTxt),
                                pageUrl = detailUrl,
                                images = if (cover.isNotBlank()) listOf(cover) else emptyList(),
                                desc = movie.title,
                                source = "JavDB",
                                sourceId = "javdb"
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
            out
        }.flatten()
    }

    /* ================= 2. RARBG (rargb.to) ================= */
    data class RawRargbRow(
        val title: String,
        val magnet: String,
        val detailUrl: String,
        val size: Long,
        val dateMs: Long,
        val seeders: Int?,
        val leechers: Int?
    )

    fun parseRargbDoc(doc: Document, base: String = "https://rargb.to"): List<RawRargbRow> {
        var rows = doc.select("table.lista2t tr.lista2, tr.lista2").toList()
        if (rows.isEmpty()) {
            rows = doc.select("table tr").filter { tr ->
                val tds = tr.select("td")
                val a = tds.getOrNull(1)?.selectFirst("a[href*=\"/torrent/\"]")
                tds.size >= 6 && a != null && a.selectFirst("img") == null
            }
        }
        return rows.mapNotNull { tr ->
            val tds = tr.select("td")
            if (tds.size < 5) return@mapNotNull null
            val titleA = tds.getOrNull(1)?.selectFirst("a[href*=\"/torrent/\"]")
                ?: tr.select("a[href*=\"/torrent/\"]").firstOrNull { it.selectFirst("img") == null }
                ?: return@mapNotNull null
            val title = titleA.attr("title").ifBlank { titleA.text() }.replace(Regex("\\s+"), " ").trim()
            val detailUrl = resolveUrl(titleA.attr("href"), base)
            val magDirect = tr.selectFirst("a[href^=\"magnet:\"]")?.attr("href")?.trim().orEmpty()
            val dateStr = tds.getOrNull(3)?.text()?.trim().orEmpty()
            val sizeStr = tds.getOrNull(4)?.text()?.trim().orEmpty()
            val seedStr = tds.getOrNull(5)?.text().orEmpty().replace(Regex("[^\\d]"), "")
            val leechStr = tds.getOrNull(6)?.text().orEmpty().replace(Regex("[^\\d]"), "")
            if (title.isBlank() || (magDirect.isBlank() && detailUrl.isBlank())) return@mapNotNull null
            RawRargbRow(
                title = title,
                magnet = magDirect,
                detailUrl = detailUrl,
                size = Formatters.parseSizeToBytes(sizeStr),
                dateMs = Formatters.parseDateMs(dateStr),
                seeders = seedStr.toIntOrNull(),
                leechers = leechStr.toIntOrNull()
            )
        }
    }

    suspend fun resolveRargbRows(
        rows: List<RawRargbRow>,
        searchUrl: String,
        cookieHeader: String = ""
    ): List<TorrentItem> {
        val sorted = rows.sortedWith(
            compareByDescending<RawRargbRow> { it.seeders ?: -1 }.thenByDescending { it.size }
        )
        val direct = sorted.filter { it.magnet.isNotBlank() }.map {
            TorrentItem(
                title = it.title,
                magnet = it.magnet,
                hash = Formatters.extractInfoHash(it.magnet),
                size = it.size,
                seeders = it.seeders,
                leechers = it.leechers,
                dateMs = it.dateMs,
                pageUrl = it.detailUrl.ifBlank { searchUrl },
                source = "RARBG",
                sourceId = "rargb"
            )
        }
        val needDetail = sorted.filter { it.magnet.isBlank() && it.detailUrl.isNotBlank() }.take(14)
        val hdrs = buildMap {
            putAll(HttpClient.BROWSER_HTML_HEADERS)
            put("Referer", searchUrl)
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }
        val fromDetail = mapConcurrent(needDetail, 4) { item ->
            val dHtml = HttpClient.getString(item.detailUrl, headers = hdrs, timeoutSec = 13L)
            val dDoc = Jsoup.parse(dHtml, item.detailUrl)
            var mag = dDoc.selectFirst("a[href^=\"magnet:\"]")?.attr("href").orEmpty()
            if (mag.isBlank()) {
                mag = Regex("magnet:\\?xt=urn:btih:[a-zA-Z0-9]{32,40}[^\"'<\\s]*", RegexOption.IGNORE_CASE)
                    .find(dHtml)?.value?.replace("&amp;", "&").orEmpty()
            }
            if (mag.isBlank()) return@mapConcurrent null
            val posterRaw = dDoc.selectFirst("td.lista img[src*=\"/static/movie/\"], td.lista img[src*=\"poster\"], td.lista img[src*=\"cover\"], #description img[src]")
                ?.attr("src").orEmpty()
            val poster = if (posterRaw.isNotBlank()) resolveUrl(posterRaw, item.detailUrl) else ""
            TorrentItem(
                title = item.title,
                magnet = mag.trim(),
                hash = Formatters.extractInfoHash(mag),
                size = item.size,
                seeders = item.seeders,
                leechers = item.leechers,
                dateMs = item.dateMs,
                pageUrl = item.detailUrl,
                images = if (poster.startsWith("http")) listOf(poster) else emptyList(),
                source = "RARBG",
                sourceId = "rargb"
            )
        }
        return direct + fromDetail
    }

    private suspend fun searchRargb(query: String, sortMode: String): List<TorrentItem> {
        // 1. Primary: TheRARBG Official JSON API (fast, 1 request, full infohash + seeders + size, no 403)
        val orderParam = when (sortMode) {
            "leechers-desc" -> "-l"
            "size-desc" -> "-s"
            "size-asc" -> "s"
            "date-desc" -> "-a"
            "date-asc" -> "a"
            else -> "-se"
        }
        try {
            val jsonUrl = "https://therarbg.to/get-posts/order:$orderParam:keywords:${Formatters.urlEncode(query)}:paginate_by:50:format:json/"
            val raw = HttpClient.getString(jsonUrl, headers = mapOf("Accept" to "application/json"), timeoutSec = 14L)
            val results = JSONObject(raw).optJSONArray("results")
            if (results != null && results.length() > 0) {
                val out = mutableListOf<TorrentItem>()
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val title = r.optString("n").trim()
                    val hash = r.optString("h").trim()
                    val magnet = Formatters.buildMagnet(hash, title)
                    if (title.isBlank() || magnet.isBlank()) continue
                    val pk = r.optString("pk")
                    val thumb = r.optString("t").takeIf { it != "null" && it.startsWith("http") }
                    out.add(
                        TorrentItem(
                            title = title,
                            magnet = magnet,
                            hash = Formatters.extractInfoHash(hash),
                            size = r.optLong("s", 0L),
                            seeders = r.optInt("se", 0),
                            leechers = r.optInt("le", 0),
                            dateMs = Formatters.parseDateMs(r.optLong("a", 0L)),
                            pageUrl = if (pk.isNotBlank()) "https://therarbg.to/post-detail/$pk/" else "",
                            images = if (thumb != null) listOf(thumb) else emptyList(),
                            source = "RARBG",
                            sourceId = "rargb"
                        )
                    )
                }
                if (out.isNotEmpty()) return out
            }
        } catch (_: Exception) {}

        // 2. Secondary: TheRARBG Official Torznab XML API
        try {
            val tzUrl = "https://therarbg.com/api/torznab?t=search&q=${Formatters.urlEncode(query)}&apikey=torrenthub"
            val xml = HttpClient.getString(tzUrl, timeoutSec = 14L)
            val xmlDoc = Jsoup.parse(xml, "", Parser.xmlParser())
            val items = xmlDoc.select("item").mapNotNull { it ->
                val title = it.selectFirst("title")?.text()?.trim().orEmpty()
                val link = it.selectFirst("link")?.text()?.trim().orEmpty()
                val magnet = if (link.startsWith("magnet:")) link else ""
                if (title.isBlank() || magnet.isBlank()) return@mapNotNull null
                val attrs = it.select("torznab|attr, attr").associate { a ->
                    a.attr("name").lowercase(Locale.ROOT) to a.attr("value")
                }
                val sizeVal = attrs["size"]?.toLongOrNull()
                    ?: it.selectFirst("size")?.text()?.toLongOrNull() ?: 0L
                TorrentItem(
                    title = title,
                    magnet = magnet,
                    hash = Formatters.extractInfoHash(attrs["infohash"].orEmpty().ifBlank { magnet }),
                    size = sizeVal,
                    seeders = attrs["seeders"]?.toIntOrNull(),
                    leechers = attrs["peers"]?.toIntOrNull(),
                    dateMs = Formatters.parseDateMs(it.selectFirst("pubDate")?.text()),
                    pageUrl = it.selectFirst("guid")?.text().orEmpty(),
                    source = "RARBG",
                    sourceId = "rargb"
                )
            }
            if (items.isNotEmpty()) return items
        } catch (_: Exception) {}

        // 3. Fallback: rargb.to HTML (+ WebView bypass option if 403)
        val searchUrl = buildBypassSearchUrl("rargb", query, sortMode)
        val html = HttpClient.getString(
            searchUrl,
            headers = HttpClient.BROWSER_HTML_HEADERS + ("Referer" to "https://rargb.to/")
        )
        val doc = Jsoup.parse(html, "https://rargb.to")
        if (isCloudflareDoc(doc)) throw HttpStatusException(403, "HTTP 403 (Cloudflare)")
        return resolveRargbRows(parseRargbDoc(doc), searchUrl)
    }

    /* ================= 3. BT4G (bt4gprx.com) ================= */
    data class RawBt4gCard(
        val title: String,
        val detailUrl: String,
        val size: Long,
        val files: String,
        val seeders: Int?,
        val leechers: Int?,
        val dateMs: Long
    )

    fun parseBt4gDoc(doc: Document, base: String = "https://bt4gprx.com"): List<RawBt4gCard> =
        doc.select(".notion-list-item").mapNotNull { card ->
            val titleA = card.selectFirst(".notion-list-item-title a[href]") ?: return@mapNotNull null
            val title = titleA.attr("title").ifBlank { titleA.text() }.replace(Regex("\\s+"), " ").trim()
            val detailUrl = resolveUrl(titleA.attr("href"), base)
            if (title.isBlank() || detailUrl.isBlank()) return@mapNotNull null

            val metaEl = card.selectFirst(".notion-list-item-meta")
            val metaText = metaEl?.text().orEmpty().replace('\u00A0', ' ').replace(Regex("\\s+"), " ")
            val sizeStr = metaEl?.selectFirst("b.cpill")?.text()?.trim().orEmpty().ifBlank {
                Regex("Total Size:\\s*([\\d.]+\\s*[KMGT]?i?B)", RegexOption.IGNORE_CASE)
                    .find(metaText)?.groupValues?.get(1).orEmpty()
            }
            val filesStr = Regex("Files:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(metaText)?.groupValues?.get(1).orEmpty()
            val dateStr = Regex("(\\d{4}-\\d{2}-\\d{2})").find(metaText)?.groupValues?.get(1).orEmpty()
            val seedStr = metaEl?.selectFirst(".notion-seeders, #seeders")?.text().orEmpty().replace(Regex("[^\\d]"), "")
            val leechStr = metaEl?.selectFirst(".notion-leechers, #leechers")?.text().orEmpty().replace(Regex("[^\\d]"), "")

            RawBt4gCard(
                title = title,
                detailUrl = detailUrl,
                size = Formatters.parseSizeToBytes(sizeStr),
                files = filesStr,
                seeders = seedStr.toIntOrNull(),
                leechers = leechStr.toIntOrNull(),
                dateMs = Formatters.parseDateMs(dateStr)
            )
        }

    suspend fun resolveBt4gCards(
        cards: List<RawBt4gCard>,
        searchUrl: String,
        cookieHeader: String = ""
    ): List<TorrentItem> {
        val targets = cards.sortedWith(
            compareByDescending<RawBt4gCard> { it.seeders ?: -1 }.thenByDescending { it.size }
        ).take(14)
        val hdrs = buildMap {
            putAll(HttpClient.BROWSER_HTML_HEADERS)
            put("Referer", searchUrl)
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }
        return mapConcurrent(targets, 4) { item ->
            val dHtml = HttpClient.getString(item.detailUrl, headers = hdrs, timeoutSec = 13L)
            var magnet = Regex("magnet:\\?xt=urn:btih:[a-zA-Z0-9]{32,40}[^\"'<\\s]*", RegexOption.IGNORE_CASE)
                .find(dHtml)?.value?.replace("&amp;", "&").orEmpty()
            if (magnet.isBlank()) {
                val hash = Regex("btih:([a-f0-9]{40})", RegexOption.IGNORE_CASE).find(dHtml)?.groupValues?.get(1)
                    ?: Regex("/hash/([a-f0-9]{40})", RegexOption.IGNORE_CASE).find(dHtml)?.groupValues?.get(1)
                    ?: Regex("\\b([a-f0-9]{40})\\b", RegexOption.IGNORE_CASE).find(dHtml)?.groupValues?.get(1)
                if (!hash.isNullOrBlank()) magnet = Formatters.buildMagnet(hash, item.title)
            }
            if (magnet.isBlank()) return@mapConcurrent null
            TorrentItem(
                title = item.title,
                magnet = magnet,
                hash = Formatters.extractInfoHash(magnet),
                size = item.size,
                files = item.files,
                seeders = item.seeders,
                leechers = item.leechers,
                dateMs = item.dateMs,
                pageUrl = item.detailUrl,
                source = "BT4G",
                sourceId = "bt4g"
            )
        }
    }

    private suspend fun searchBt4g(query: String, sortMode: String): List<TorrentItem> {
        // 1. Primary: Official BT4G RSS XML feed (returns full magnet + size + pubDate in 1 request, no 403!)
        val orderby = when {
            sortMode.startsWith("size") -> "size"
            sortMode.startsWith("date") -> "time"
            else -> "seeders"
        }
        try {
            val rssUrl = "https://bt4gprx.com/search?q=${Formatters.urlEncode(query)}&orderby=$orderby&page=rss"
            val rssXml = HttpClient.getString(rssUrl, timeoutSec = 14L)
            if (rssXml.contains("<rss", ignoreCase = true)) {
                val xmlDoc = Jsoup.parse(rssXml, "", Parser.xmlParser())
                return xmlDoc.select("item").mapNotNull { it ->
                    val title = it.selectFirst("title")?.text()?.trim().orEmpty()
                    val magnet = it.selectFirst("link")?.text()?.trim()?.replace("&amp;", "&").orEmpty()
                    if (title.isBlank() || !magnet.startsWith("magnet:", ignoreCase = true)) return@mapNotNull null
                    val descHtml = it.selectFirst("description")?.text().orEmpty()
                    val descParts = descHtml.split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)).map { p -> p.trim() }
                    val sizeBytes = descParts.firstNotNullOfOrNull { p ->
                        Formatters.parseSizeToBytes(p).takeIf { b -> b > 0L }
                    } ?: 0L
                    TorrentItem(
                        title = title,
                        magnet = magnet,
                        hash = Formatters.extractInfoHash(magnet),
                        size = sizeBytes,
                        dateMs = Formatters.parseDateMs(it.selectFirst("pubDate")?.text()?.replace(",", ", ")),
                        pageUrl = it.selectFirst("guid")?.text()?.trim().orEmpty(),
                        source = "BT4G",
                        sourceId = "bt4g"
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback: HTML search (+ WebView bypass option if 403)
        val searchUrl = buildBypassSearchUrl("bt4g", query, sortMode)
        val html = HttpClient.getString(
            searchUrl,
            headers = HttpClient.BROWSER_HTML_HEADERS + ("Referer" to "https://bt4gprx.com/")
        )
        val doc = Jsoup.parse(html, "https://bt4gprx.com")
        if (isCloudflareDoc(doc)) throw HttpStatusException(403, "HTTP 403 (Cloudflare)")
        return resolveBt4gCards(parseBt4gDoc(doc), searchUrl)
    }

    /* ================= 4. KNABEN ================= */
    private suspend fun searchKnaben(query: String, sortMode: String): List<TorrentItem> {
        val (orderBy, orderDir) = when (sortMode) {
            "leechers-desc" -> "peers" to "desc"
            "size-desc" -> "bytes" to "desc"
            "size-asc" -> "bytes" to "asc"
            "date-desc" -> "date" to "desc"
            "date-asc" -> "date" to "asc"
            else -> "seeders" to "desc"
        }
        val payload = JSONObject()
            .put("query", query)
            .put("search_type", "100%")
            .put("order_by", orderBy)
            .put("order_direction", orderDir)
            .put("from", 0)
            .put("size", 60)
            .put("hide_unsafe", false)
            .put("hide_xxx", false)

        val (code, raw) = HttpClient.postJson(
            "https://api.knaben.org/v1",
            payload.toString(),
            headers = mapOf("Accept" to "application/json")
        )
        if (code !in 200..299) throw HttpStatusException(code, "HTTP $code")
        val hits = JSONObject(raw).optJSONArray("hits") ?: return emptyList()
        val out = mutableListOf<TorrentItem>()
        for (i in 0 until hits.length()) {
            val h = hits.optJSONObject(i) ?: continue
            val title = h.optCleanString("title")
            val hash = h.optCleanString("hash")
            val rawMagnet = h.optCleanString("magnetUrl")
            val magnet = when {
                isValidTorrentLink(rawMagnet) -> rawMagnet
                hash.isNotBlank() -> Formatters.buildMagnet(hash, title)
                else -> ""
            }
            if (title.isBlank() || !isValidTorrentLink(magnet)) continue
            val origin = h.optCleanString("cachedOrigin")
            val pageUrl = h.optCleanString("details").ifBlank { h.optCleanString("link") }
            out.add(
                TorrentItem(
                    title = title,
                    magnet = magnet,
                    hash = Formatters.extractInfoHash(hash.ifBlank { magnet }),
                    size = h.optLong("bytes", 0L),
                    seeders = h.optInt("seeders", 0),
                    leechers = h.optInt("peers", 0),
                    dateMs = Formatters.parseDateMs(h.optCleanString("date")),
                    pageUrl = pageUrl,
                    source = if (origin.isNotBlank()) "Knaben:$origin" else "Knaben",
                    sourceId = "knaben"
                )
            )
        }
        return out
    }

    /* ================= 5. THE PIRATE BAY ================= */
    private suspend fun searchTpb(query: String): List<TorrentItem> {
        val raw = HttpClient.getString(
            "https://apibay.org/q.php?q=${Formatters.urlEncode(query)}",
            headers = mapOf("Accept" to "application/json")
        )
        val arr = JSONArray(raw)
        val out = mutableListOf<TorrentItem>()
        for (i in 0 until minOf(arr.length(), 60)) {
            val x = arr.optJSONObject(i) ?: continue
            val id = x.optString("id")
            val ih = x.optString("info_hash")
            if (id == "0" || ih.isBlank() || Regex("^0+$").matches(ih)) continue
            val name = x.optString("name").trim()
            out.add(
                TorrentItem(
                    title = name,
                    magnet = Formatters.buildMagnet(ih, name),
                    hash = Formatters.extractInfoHash(ih),
                    size = x.optString("size").toLongOrNull() ?: 0L,
                    files = x.optString("num_files").takeIf { it != "0" }.orEmpty(),
                    seeders = x.optString("seeders").toIntOrNull() ?: 0,
                    leechers = x.optString("leechers").toIntOrNull() ?: 0,
                    dateMs = Formatters.parseDateMs(x.optString("added").toLongOrNull()),
                    pageUrl = "https://thepiratebay.org/description.php?id=$id",
                    tpbId = id,
                    source = "TPB",
                    sourceId = "tpb"
                )
            )
        }
        return out
    }

    /* ================= 6. TORRENTS-CSV ================= */
    private suspend fun searchTorrentsCsv(query: String): List<TorrentItem> {
        val raw = HttpClient.getString(
            "https://torrents-csv.com/service/search?q=${Formatters.urlEncode(query)}&size=50",
            headers = mapOf("Accept" to "application/json")
        )
        val arr = if (raw.trimStart().startsWith("[")) {
            JSONArray(raw)
        } else {
            JSONObject(raw).optJSONArray("torrents") ?: JSONArray()
        }
        val out = mutableListOf<TorrentItem>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val title = x.optString("name").trim()
            val ih = x.optString("infohash")
            val magnet = Formatters.buildMagnet(ih, title)
            if (title.isBlank() || magnet.isBlank()) continue
            out.add(
                TorrentItem(
                    title = title,
                    magnet = magnet,
                    hash = Formatters.extractInfoHash(ih),
                    size = x.optLong("size_bytes", 0L),
                    seeders = x.optInt("seeders", 0),
                    leechers = x.optInt("leechers", 0),
                    dateMs = Formatters.parseDateMs(x.optLong("created_unix", 0L)),
                    source = "Torrents-CSV",
                    sourceId = "torrentscsv"
                )
            )
        }
        return out
    }

    /* ================= 7. YTS ================= */
    private suspend fun searchYts(query: String, sortMode: String): List<TorrentItem> {
        val ytsSort = when {
            sortMode.startsWith("date") -> "date_added"
            sortMode.startsWith("name") -> "title"
            else -> "seeds"
        }
        val path = "/api/v2/list_movies.json?query_term=${Formatters.urlEncode(query)}&limit=25&sort_by=$ytsSort"
        val raw = try {
            HttpClient.getString("https://yts.mx$path", headers = mapOf("Accept" to "application/json"), timeoutSec = 12L)
        } catch (_: Exception) {
            HttpClient.getString("https://yts.lt$path", headers = mapOf("Accept" to "application/json"), timeoutSec = 12L)
        }
        val movies = JSONObject(raw).optJSONObject("data")?.optJSONArray("movies") ?: return emptyList()
        val out = mutableListOf<TorrentItem>()
        for (i in 0 until movies.length()) {
            val m = movies.optJSONObject(i) ?: continue
            val cover = m.optString("large_cover_image").ifBlank { m.optString("medium_cover_image") }
            val bg = m.optString("background_image_original").ifBlank { m.optString("background_image") }
            val imgs = listOf(cover, bg).filter { it.isNotBlank() }
            val rating = m.optDouble("rating", 0.0)
            val summary = m.optString("summary")
            val desc = listOfNotNull(
                if (rating > 0) "IMDb $rating/10" else null,
                summary.takeIf { it.isNotBlank() }
            ).joinToString(" · ").take(220)

            val torrents = m.optJSONArray("torrents") ?: continue
            for (j in 0 until torrents.length()) {
                val t = torrents.optJSONObject(j) ?: continue
                val baseTitle = m.optString("title_long").ifBlank { m.optString("title") }
                val title = "$baseTitle [${t.optString("quality")} ${t.optString("type")}]".trim()
                val hash = t.optString("hash")
                val magnet = Formatters.buildMagnet(hash, title)
                if (magnet.isBlank()) continue
                out.add(
                    TorrentItem(
                        title = title,
                        magnet = magnet,
                        hash = Formatters.extractInfoHash(hash),
                        size = t.optLong("size_bytes", 0L).takeIf { it > 0L } ?: Formatters.parseSizeToBytes(t.optString("size")),
                        seeders = t.optInt("seeds", 0),
                        leechers = t.optInt("peers", 0),
                        dateMs = Formatters.parseDateMs(t.optLong("date_uploaded_unix", 0L)),
                        pageUrl = m.optString("url"),
                        images = imgs,
                        desc = desc,
                        source = "YTS",
                        sourceId = "yts"
                    )
                )
            }
        }
        return out
    }

    /* ================= 8 & 9. NYAA & SUKEBEI ================= */
    private suspend fun searchNyaa(query: String, sortMode: String, isSukebei: Boolean): List<TorrentItem> {
        val (s, o) = when (sortMode) {
            "leechers-desc" -> "leechers" to "desc"
            "size-desc" -> "size" to "desc"
            "size-asc" -> "size" to "asc"
            "date-desc" -> "id" to "desc"
            "date-asc" -> "id" to "asc"
            else -> "seeders" to "desc"
        }
        val host = if (isSukebei) "https://sukebei.nyaa.si" else "https://nyaa.si"
        val label = if (isSukebei) "Sukebei" else "Nyaa"
        val id = if (isSukebei) "sukebei" else "nyaa"
        val raw = HttpClient.getString("$host/?page=rss&q=${Formatters.urlEncode(query)}&s=$s&o=$o")
        val doc = Jsoup.parse(raw, "", Parser.xmlParser())
        return doc.select("item").take(60).mapNotNull { it ->
            val title = it.selectFirst("title")?.text()?.trim().orEmpty()
            val hash = it.selectFirst("nyaa|infoHash, infoHash")?.text()?.trim().orEmpty()
            val guid = it.selectFirst("guid")?.text()?.trim().orEmpty()
            val magnet = Formatters.buildMagnet(hash, title)
            if (title.isBlank() || magnet.isBlank()) return@mapNotNull null
            TorrentItem(
                title = title,
                magnet = magnet,
                hash = Formatters.extractInfoHash(hash),
                size = Formatters.parseSizeToBytes(it.selectFirst("nyaa|size, size")?.text()),
                seeders = it.selectFirst("nyaa|seeders, seeders")?.text()?.trim()?.toIntOrNull() ?: 0,
                leechers = it.selectFirst("nyaa|leechers, leechers")?.text()?.trim()?.toIntOrNull() ?: 0,
                dateMs = Formatters.parseDateMs(it.selectFirst("pubDate")?.text()),
                pageUrl = if (guid.contains("/view/")) guid else "",
                source = label,
                sourceId = id
            )
        }
    }

    /* ================= 10. U3C3 ================= */
    private suspend fun searchU3c3(query: String): List<TorrentItem> {
        val base = "https://www.u3c3.com"
        val homeHtml = HttpClient.getString("$base/", headers = mapOf("Referer" to "$base/"))
        val token = Regex("var\\s+nmefafej\\s*=\\s*[\"']([^\"']+)[\"']").find(homeHtml)?.groupValues?.get(1).orEmpty()
        if (token.isBlank()) return emptyList()
        val searchUrl = "$base/?search2=${Formatters.urlEncode(token)}&search=${Formatters.urlEncode(query)}"
        val html = HttpClient.getString(searchUrl, headers = mapOf("Referer" to "$base/"))
        val doc = Jsoup.parse(html, base)
        val compactKw = query.uppercase(Locale.ROOT).replace(Regex("[-_\\s]"), "")
        return doc.select("table.torrent-list tbody tr.default, table.torrent-list tbody tr.success").mapNotNull { row ->
            val titleA = row.selectFirst("td:nth-child(2) a[href*=\"/view?id=\"]")
            val magA = row.selectFirst("td:nth-child(3) a[href^=\"magnet:\"]")
            val title = (titleA?.attr("title")?.ifBlank { titleA.text() }).orEmpty().trim()
            val magnet = magA?.attr("href").orEmpty().trim()
            if (title.isBlank() || magnet.isBlank()) return@mapNotNull null
            if (compactKw.isNotBlank() && !title.uppercase(Locale.ROOT).replace(Regex("[-_\\s]"), "").contains(compactKw)) {
                return@mapNotNull null
            }
            TorrentItem(
                title = title,
                magnet = magnet,
                hash = Formatters.extractInfoHash(magnet),
                size = Formatters.parseSizeToBytes(row.selectFirst("td:nth-child(4)")?.text()),
                dateMs = Formatters.parseDateMs(row.selectFirst("td:nth-child(5)")?.text()),
                pageUrl = titleA?.attr("href")?.let { resolveUrl(it, base) }.orEmpty().ifBlank { searchUrl },
                source = "U3C3",
                sourceId = "u3c3"
            )
        }.take(50)
    }

    /* ================= 11. BTSEARCH ================= */
    private suspend fun searchBtSearch(query: String, sortMode: String): List<TorrentItem> {
        val base = "https://btsearch.love"
        val referer = "$base/search?keyword=${Formatters.urlEncode(query)}"
        val sort = if (sortMode.startsWith("date")) "time" else "size"
        val sortType = if (sortMode.endsWith("-asc")) "asc" else "desc"
        val params = linkedMapOf(
            "keyword" to query,
            "limit" to "20",
            "offset" to "0",
            "mode" to "",
            "time" to "",
            "sort" to sort,
            "sort_type" to sortType,
            "size" to ""
        )
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val nonce = Formatters.randomNonce(8)
        val signParts = mutableListOf("timestamp=$ts", "nonce=$nonce")
        params.forEach { (k, v) -> signParts.add("$k=$v") }
        val sign = Formatters.md5Hex("${signParts.sorted().joinToString("&")}&key=long2ice").uppercase(Locale.ROOT)
        val qs = params.entries.joinToString("&") { "${it.key}=${Formatters.urlEncode(it.value)}" }

        val raw = HttpClient.getString(
            "$base/api/search?$qs",
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Referer" to referer,
                "x-timestamp" to ts,
                "x-nonce" to nonce,
                "x-sign" to sign
            )
        )
        val root = JSONObject(raw)
        val list = root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("data")
            ?: return emptyList()
        val out = mutableListOf<TorrentItem>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val cleanHash = item.optString("hash")
                .replace(Regex("^magnet:\\?xt=urn:btih:", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[^a-zA-Z0-9]"), "")
            if (!Regex("^[a-fA-F0-9]{32,40}$").matches(cleanHash)) continue
            val rawName = item.optString("name").replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
            val files = listOf("files_count", "file_count", "files_num", "count")
                .firstNotNullOfOrNull { k -> item.optInt(k, 0).takeIf { it > 0 }?.toString() }.orEmpty()
            val dateVal = item.optString("created_at").ifBlank { item.optString("created_time") }
                .ifBlank { item.optString("create_time") }.ifBlank { item.optString("date") }
            val itemId = item.optString("id")
            out.add(
                TorrentItem(
                    title = rawName.ifBlank { cleanHash },
                    magnet = Formatters.buildMagnet(cleanHash, rawName),
                    hash = cleanHash.lowercase(Locale.ROOT),
                    size = Formatters.parseSizeToBytes(item.optString("size")),
                    files = files,
                    dateMs = Formatters.parseDateMs(dateVal),
                    pageUrl = if (itemId.isNotBlank()) "$base/torrent/${Formatters.urlEncode(itemId)}?keyword=${Formatters.urlEncode(query)}" else referer,
                    source = "BTSearch",
                    sourceId = "btsearch"
                )
            )
        }
        return out
    }

    /* ================= 12. U9A9 ================= */
    private suspend fun searchU9a9(query: String): List<TorrentItem> {
        val base = "https://u9a9.com"
        val url = "$base/?type=2&search=${Formatters.urlEncode(query)}"
        val html = HttpClient.getString(url, headers = HttpClient.BROWSER_HTML_HEADERS + ("Referer" to "$base/"))
        val doc = Jsoup.parse(html, base)
        return doc.select("table.torrent-list tbody tr.default, table.torrent-list tbody tr.success").mapNotNull { row ->
            val a = row.selectFirst("td:nth-child(2) > a:nth-child(1)")
            val mag = row.selectFirst("td:nth-child(3) > a[href^=\"magnet:\"]")
            val title = (a?.attr("title")?.ifBlank { a.text() }).orEmpty().trim()
            val magnet = mag?.attr("href").orEmpty().trim()
            if (title.isBlank() || magnet.isBlank() || !Formatters.matchesKeywordTitle(title, query)) return@mapNotNull null
            TorrentItem(
                title = title,
                magnet = magnet,
                hash = Formatters.extractInfoHash(magnet),
                size = Formatters.parseSizeToBytes(row.selectFirst("td:nth-child(4)")?.text()),
                dateMs = Formatters.parseDateMs(row.selectFirst("td:nth-child(5)")?.text()),
                pageUrl = a?.attr("href")?.let { resolveUrl(it, base) }.orEmpty().ifBlank { url },
                source = "U9A9",
                sourceId = "u9a9"
            )
        }.take(50)
    }

    /* ================= 13. SOKITTY ================= */
    private suspend fun searchSoKitty(query: String): List<TorrentItem> {
        val base = "https://w1.sokitty.me"
        val url = "$base/search?key=${Formatters.urlEncode(query)}"
        val html = HttpClient.getString(url, headers = mapOf("Referer" to "$base/"))
        val doc = Jsoup.parse(html, base)
        val compactKw = query.uppercase(Locale.ROOT).replace(Regex("[-_\\s]"), "")
        return doc.select(".panel.search-panel").mapNotNull { panel ->
            val a = panel.selectFirst("h3.panel-title > a.list-title") ?: return@mapNotNull null
            val href = a.attr("href")
            if (!href.startsWith("/bt/")) return@mapNotNull null
            val hash = href.removePrefix("/bt/").trim()
            val title = a.text().trim()
            if (hash.isBlank() || title.isBlank()) return@mapNotNull null
            if (compactKw.isNotBlank() && !title.uppercase(Locale.ROOT).replace(Regex("[-_\\s]"), "").contains(compactKw)) {
                return@mapNotNull null
            }
            val magnet = Formatters.buildMagnet(hash, title)
            if (magnet.isBlank()) return@mapNotNull null
            val infoItems = panel.select(".panel-footer .info-item")
            TorrentItem(
                title = title,
                magnet = magnet,
                hash = Formatters.extractInfoHash(hash).ifBlank { hash.lowercase(Locale.ROOT) },
                size = Formatters.parseSizeToBytes(infoItems.getOrNull(0)?.text()),
                dateMs = Formatters.parseDateMs(infoItems.getOrNull(2)?.text()),
                pageUrl = "$base$href",
                source = "SoKitty",
                sourceId = "sokitty"
            )
        }.take(50)
    }

    /* ================= 14. ØMAGNET ================= */
    private suspend fun searchOmag(query: String): List<TorrentItem> {
        val base = "https://xn--mag-zna.net"
        val searchUrl = "$base/search?q=${Formatters.urlEncode(query)}"
        val html = HttpClient.getString(searchUrl, headers = mapOf("Referer" to "$base/"))
        val doc = Jsoup.parse(html, base)
        data class Cand(val title: String, val size: Long, val dateMs: Long, val src: String)
        val candidates = doc.select("tr").mapNotNull { tr ->
            val a = tr.selectFirst("td.result-title a[href^=\"/!\"]") ?: return@mapNotNull null
            val metas = tr.select(".result-meta > div").map { it.text().trim() }
            Cand(
                title = a.text().trim(),
                size = Formatters.parseSizeToBytes(metas.getOrNull(0)),
                dateMs = Formatters.parseDateMs(metas.getOrNull(1)),
                src = resolveUrl(a.attr("href"), base)
            )
        }.take(12)

        return mapConcurrent(candidates, 4) { c ->
            val dHtml = HttpClient.getString(c.src, headers = mapOf("Referer" to searchUrl), timeoutSec = 12L)
            val dDoc = Jsoup.parse(dHtml, c.src)
            val mag = dDoc.selectFirst("#input-magnet")?.attr("value").orEmpty()
                .ifBlank { dDoc.selectFirst("a[href^=\"magnet:\"]")?.attr("href").orEmpty() }
            if (!mag.startsWith("magnet:?xt=urn:btih:", ignoreCase = true)) return@mapConcurrent null
            val thText = dDoc.selectFirst("table.table-hover.file-list thead th:first-child")?.text().orEmpty()
            val fCount = Regex("Files\\s*\\(\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE).find(thText)?.groupValues?.get(1).orEmpty()
            TorrentItem(
                title = c.title,
                magnet = mag.trim(),
                hash = Formatters.extractInfoHash(mag),
                size = c.size,
                files = fCount,
                dateMs = c.dateMs,
                pageUrl = c.src,
                source = "ØMagnet",
                sourceId = "omag"
            )
        }
    }

    /* ================= 15. BITSEARCH ================= */
    private suspend fun searchBitSearch(query: String, sortMode: String): List<TorrentItem> {
        val bsSort = when {
            sortMode.startsWith("size") -> "size"
            sortMode.startsWith("date") -> "date"
            else -> "seeders"
        }
        val raw = HttpClient.getString("https://bitsearch.to/search?q=${Formatters.urlEncode(query)}&sort=$bsSort")
        val doc = Jsoup.parse(raw, "https://bitsearch.to")
        return doc.select("li.search-result, .search-result, article").mapNotNull { card ->
            val magnet = card.selectFirst("a[href^=\"magnet:\"]")?.attr("href").orEmpty().trim()
            if (magnet.isBlank()) return@mapNotNull null
            val titleEl = card.selectFirst("h5 a, h3 a, .title a, a[href*=\"/torrents/\"]")
            val title = titleEl?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val statsText = card.text()
            val sizeStr = Regex("\\b(\\d+(?:\\.\\d+)?\\s*(?:KB|MB|GB|TB|KiB|MiB|GiB|TiB))\\b", RegexOption.IGNORE_CASE)
                .find(statsText)?.groupValues?.get(1).orEmpty()
            val statSpans = card.select(".stats div, .stats span").map { it.text().trim() }
            TorrentItem(
                title = title,
                magnet = magnet,
                hash = Formatters.extractInfoHash(magnet),
                size = Formatters.parseSizeToBytes(sizeStr),
                seeders = if (statSpans.size >= 4) statSpans[2].replace(Regex("[^\\d]"), "").toIntOrNull() else null,
                leechers = if (statSpans.size >= 4) statSpans[3].replace(Regex("[^\\d]"), "").toIntOrNull() else null,
                pageUrl = titleEl?.attr("href")?.let { resolveUrl(it, "https://bitsearch.to") }.orEmpty(),
                source = "BitSearch",
                sourceId = "bitsearch"
            )
        }.take(50)
    }

    /* ================= 16. BTDIG ================= */
    private suspend fun searchBtDig(query: String, sortMode: String): List<TorrentItem> {
        val order = when {
            sortMode.startsWith("size") -> "3"
            sortMode.startsWith("date") -> "2"
            else -> "0"
        }
        val raw = HttpClient.getString("https://btdig.com/search?q=${Formatters.urlEncode(query)}&order=$order")
        val doc = Jsoup.parse(raw, "https://btdig.com")
        return doc.select(".one_result").mapNotNull { r ->
            val magnet = r.selectFirst("a[href^=\"magnet:\"]")?.attr("href").orEmpty().trim()
            val titleEl = r.selectFirst(".torrent_name a")
            val title = titleEl?.text()?.trim().orEmpty()
            if (title.isBlank() || magnet.isBlank()) return@mapNotNull null
            TorrentItem(
                title = title,
                magnet = magnet,
                hash = Formatters.extractInfoHash(magnet),
                size = Formatters.parseSizeToBytes(r.selectFirst(".torrent_size")?.text()),
                pageUrl = titleEl?.attr("href")?.let { resolveUrl(it, "https://btdig.com") }.orEmpty(),
                source = "BTDig",
                sourceId = "btdig"
            )
        }.take(40)
    }

    /* ================= DEDUPLICATE & ENRICH ================= */
    fun mergeAndDeduplicate(items: List<TorrentItem>): List<TorrentItem> {
        val byHash = LinkedHashMap<String, TorrentItem>()
        val noHashByKey = LinkedHashMap<String, TorrentItem>()

        for (item in items) {
            if (!isValidTorrentLink(item.magnet)) continue
            val h = item.hash.trim().lowercase(Locale.ROOT).ifBlank { Formatters.extractInfoHash(item.magnet) }
            val qFlags = Formatters.classifyQuality(item.title, item.magnet)
            val vCode = Formatters.extractVideoCode(item.title)
            val enriched = item.copy(
                hash = h,
                qFlags = qFlags,
                videoCode = vCode,
                sources = listOf(item.source),
                sourceIds = listOf(item.sourceId)
            )
            if (h.isBlank()) {
                val fallbackKey = item.magnet.trim().ifBlank { "${item.sourceId}:${item.title}" }
                if (!noHashByKey.containsKey(fallbackKey)) {
                    noHashByKey[fallbackKey] = enriched
                }
                continue
            }
            val cur = byHash[h]
            if (cur == null) {
                byHash[h] = enriched
            } else {
                val mergedSources = if (item.source in cur.sources) cur.sources else cur.sources + item.source
                val mergedIds = if (item.sourceId in cur.sourceIds) cur.sourceIds else cur.sourceIds + item.sourceId
                val bestSeeders = when {
                    item.seeders != null && (cur.seeders == null || item.seeders > cur.seeders) -> item.seeders
                    else -> cur.seeders
                }
                val bestLeechers = when {
                    item.leechers != null && (cur.leechers == null || item.leechers > cur.leechers) -> item.leechers
                    else -> cur.leechers
                }
                byHash[h] = cur.copy(
                    title = if (item.title.length > cur.title.length) item.title else cur.title,
                    size = maxOf(cur.size, item.size),
                    files = cur.files.ifBlank { item.files },
                    seeders = bestSeeders,
                    leechers = bestLeechers,
                    dateMs = maxOf(cur.dateMs, item.dateMs),
                    pageUrl = cur.pageUrl.ifBlank { item.pageUrl },
                    tpbId = cur.tpbId.ifBlank { item.tpbId },
                    desc = cur.desc.ifBlank { item.desc },
                    videoCode = cur.videoCode.ifBlank { vCode },
                    images = (cur.images + item.images).distinct(),
                    sources = mergedSources,
                    sourceIds = mergedIds,
                    qFlags = cur.qFlags.copy(
                        is4K = cur.qFlags.is4K || qFlags.is4K,
                        isSub = cur.qFlags.isSub || qFlags.isSub,
                        isCracked = cur.qFlags.isCracked || qFlags.isCracked,
                        isEd2k = cur.qFlags.isEd2k || qFlags.isEd2k
                    )
                )
            }
        }
        return byHash.values + noHashByKey.values
    }
}
