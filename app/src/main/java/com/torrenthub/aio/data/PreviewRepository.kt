package com.torrenthub.aio.data

import com.torrenthub.aio.model.ThreeSourceResult
import com.torrenthub.aio.model.WhatsLinkInfo
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object PreviewRepository {
    private val whatsLinkCache = ConcurrentHashMap<String, WhatsLinkInfo>()
    private val threeSourceCache = ConcurrentHashMap<String, String>()

    suspend fun queryWhatsLink(magnet: String): WhatsLinkInfo {
        val key = Formatters.extractInfoHash(magnet).ifBlank { magnet }
        whatsLinkCache[key]?.let { return it }

        val url = "https://whatslink.info/api/v1/link?url=${Formatters.urlEncode(magnet)}"
        val raw = HttpClient.getString(url, headers = mapOf("Accept" to "application/json"), timeoutSec = 20L)
        val json = JSONObject(raw)
        val shots = mutableListOf<String>()
        val arr = json.optJSONArray("screenshots")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i)?.optString("screenshot").orEmpty()
                if (s.startsWith("http") && s !in shots) shots.add(s)
            }
        }
        val info = WhatsLinkInfo(
            name = json.optString("name"),
            count = json.optInt("count", 0),
            size = json.optLong("size", 0L).takeIf { it > 0L } ?: json.optLong("file_size", 0L),
            fileType = json.optString("file_type"),
            screenshots = shots
        )
        whatsLinkCache[key] = info
        return info
    }

    /* ================= 3-SOURCE PREVIEW (javfree / projectjav / javstore) ================= */
    private fun lookupCode(code: String): String {
        val s = code.trim()
        val fc2 = Regex("FC2[-_\\s]?(?:PPV[-_\\s]?)?(\\d{6,9})", RegexOption.IGNORE_CASE).find(s)
        return fc2?.groupValues?.get(1) ?: s
    }

    private fun normCmp(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    private fun isMatched(haystack: String, code: String): Boolean {
        val c = normCmp(code)
        return c.isNotBlank() && normCmp(haystack).contains(c)
    }

    private fun normUrl(url: String, base: String): String =
        try {
            URI(base).resolve(url.trim()).toString().replaceFirst(Regex("^http:", RegexOption.IGNORE_CASE), "https:")
        } catch (_: Exception) {
            ""
        }

    suspend fun fetchFromSource(code: String, source: String): ThreeSourceResult {
        val url = when (source) {
            "javfree" -> fetchJavfree(code)
            "projectjav" -> fetchProjectJav(code)
            "javstore" -> fetchJavstore(code)
            else -> null
        }
        return ThreeSourceResult(
            code = code,
            activeSource = source,
            imageUrl = url,
            referer = if (source == "projectjav") "https://projectjav.com/" else ""
        )
    }

    suspend fun fetchAnyThreeSource(code: String): ThreeSourceResult {
        for (src in listOf("javfree", "projectjav", "javstore")) {
            val res = fetchFromSource(code, src)
            if (!res.imageUrl.isNullOrBlank()) return res
        }
        return ThreeSourceResult(code = code, activeSource = null, imageUrl = null)
    }

    private suspend fun fetchJavfree(code: String): String? {
        val q = lookupCode(code)
        val ck = "javfree:$q"
        threeSourceCache[ck]?.let { return it.ifBlank { null } }
        return try {
            val html = HttpClient.getString("https://javfree.me/search/${Formatters.urlEncode(q)}", timeoutSec = 14L)
            val doc = Jsoup.parse(html, "https://javfree.me/")
            val link = doc.select(".entry-title > a").firstOrNull { a ->
                isMatched("${a.attr("href")} ${a.text()}", q)
            }
            val href = link?.let { normUrl(it.attr("href"), "https://javfree.me/") }.orEmpty()
            if (href.isBlank()) {
                threeSourceCache[ck] = ""
                return null
            }
            val dHtml = HttpClient.getString(href, timeoutSec = 14L)
            val dDoc = Jsoup.parse(dHtml, href)
            val imgs = dDoc.select("p > img[src]")
                .map { normUrl(it.attr("src"), href) }
                .filter { u ->
                    val baseU = u.substringBefore('?')
                    isMatched(baseU, q) && (
                        Regex("(?:1080p|demosaic)\\.(?:jpe?g|png|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(baseU) ||
                            Regex("_\\d+\\.(?:jpe?g|png|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(baseU)
                        )
                }
            val picked = imgs.firstOrNull { it.contains("-1080p.", true) }
                ?: imgs.firstOrNull { it.contains("-demosaic.", true) }
                ?: imgs.firstOrNull()
            threeSourceCache[ck] = picked.orEmpty()
            picked
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchProjectJav(code: String): String? {
        val q = lookupCode(code)
        val ck = "projectjav:$q"
        threeSourceCache[ck]?.let { return it.ifBlank { null } }
        return try {
            val searchUrl = "https://projectjav.com/?searchTerm=${Formatters.urlEncode(q)}"
            val html = HttpClient.getString(searchUrl, headers = HttpClient.BROWSER_HTML_HEADERS, timeoutSec = 14L)
            val doc = Jsoup.parse(html, "https://projectjav.com/")
            val movieLink = doc.select("a[href*=\"/movie/\"]").firstOrNull { a ->
                isMatched("${a.attr("href")} ${a.text()}", q)
            }
            val detailUrl = movieLink?.let { normUrl(it.attr("href"), "https://projectjav.com/") }.orEmpty()
            val dDoc = if (detailUrl.isNotBlank()) {
                Jsoup.parse(HttpClient.getString(detailUrl, headers = HttpClient.BROWSER_HTML_HEADERS, timeoutSec = 14L), detailUrl)
            } else doc
            val base = detailUrl.ifBlank { searchUrl }
            val shotLink = dDoc.select(".col-md-12.thumbnail a[data-featherlight=\"image\"], .thumbnail a[data-featherlight=\"image\"]")
                .firstOrNull { a -> isMatched("${a.outerHtml()} $base", q) }
            val rawHref = shotLink?.attr("href")?.ifBlank {
                shotLink.selectFirst("img")?.attr("src")?.substringBefore('?').orEmpty()
            }.orEmpty()
            val picked = normUrl(rawHref, base).ifBlank { null }
            threeSourceCache[ck] = picked.orEmpty()
            picked
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchJavstore(code: String): String? {
        val q = lookupCode(code)
        val ck = "javstore:$q"
        threeSourceCache[ck]?.let { return it.ifBlank { null } }
        return try {
            val normQ = q.replace(Regex("^fc2-?", RegexOption.IGNORE_CASE), "").replace("-", "").lowercase(Locale.ROOT)
            val searchUrl = "https://javstore.net/search?q=${Formatters.urlEncode(q)}"
            val html = HttpClient.getString(searchUrl, timeoutSec = 14L)
            val doc = Jsoup.parse(html, searchUrl)
            val candidates = mutableListOf<String>()
            for (a in doc.select("a[href*=\"/\"]")) {
                val full = normUrl(a.attr("href"), searchUrl)
                if (full.isBlank()) continue
                val uri = try { URI(full) } catch (_: Exception) { continue }
                val host = uri.host.orEmpty()
                val path = uri.path.orEmpty()
                if (!host.endsWith("javstore.net", true) || path.startsWith("/search", true)) continue
                val slug = try { URLDecoder.decode(path.substringAfterLast('/'), "UTF-8") } catch (_: Exception) { "" }
                    .lowercase(Locale.ROOT).replace("-", "")
                if ((path.endsWith(".html", true) || Regex("^/\\d+[-/]").containsMatchIn(path)) && slug.contains(normQ) && full !in candidates) {
                    candidates.add(full)
                }
            }
            for (cand in candidates.take(3)) {
                try {
                    val dHtml = HttpClient.getString(cand, timeoutSec = 12L)
                    val dDoc = Jsoup.parse(dHtml, cand)
                    val clickHere = dDoc.select("a").firstOrNull { it.text().contains("CLICK HERE") }
                    if (clickHere != null) {
                        val u = normUrl(clickHere.attr("href"), cand)
                        if (u.isNotBlank()) {
                            threeSourceCache[ck] = u
                            return u
                        }
                    }
                    val thumb = dDoc.selectFirst("img[src*=\"_s.jpg\"]")
                    if (thumb != null) {
                        val u = normUrl(thumb.attr("src"), cand).replace(Regex("_s\\.jpg$", RegexOption.IGNORE_CASE), "_l.jpg")
                        if (u.isNotBlank()) {
                            threeSourceCache[ck] = u
                            return u
                        }
                    }
                } catch (_: Exception) {}
            }
            threeSourceCache[ck] = ""
            null
        } catch (_: Exception) {
            null
        }
    }
}
