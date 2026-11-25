package com.torrenthub.aio.data

import com.torrenthub.aio.model.RssFeedItem
import com.torrenthub.aio.model.RssFeedSource
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.Locale
import java.util.UUID

object RssRepository {

    val PRESET_FEEDS: List<Pair<String, String>> = listOf(
        "Nyaa.si (Latest)" to "https://nyaa.si/?page=rss",
        "Sukebei Nyaa" to "https://sukebei.nyaa.si/?page=rss",
        "YTS Movies" to "https://yts.mx/rss",
        "EZTV Releases" to "https://eztv.re/ezrss.xml",
        "ShowRSS (Latest)" to "https://showrss.info/other/all.rss"
    )

    fun defaultFeeds(): List<RssFeedSource> = listOf(
        RssFeedSource(
            id = "preset-nyaa",
            title = "Nyaa.si",
            url = "https://nyaa.si/?page=rss",
            enabled = true
        ),
        RssFeedSource(
            id = "preset-yts",
            title = "YTS Movies",
            url = "https://yts.mx/rss",
            enabled = true
        )
    )

    private val RSS_HEADERS = mapOf(
        "Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8"
    )

    suspend fun fetchFeedItems(
        feed: RssFeedSource,
        watchKeywords: List<String>
    ): List<RssFeedItem> {
        val xmlRaw = HttpClient.getString(feed.url, headers = RSS_HEADERS, timeoutSec = 20L)
        if (xmlRaw.isBlank()) return emptyList()

        val doc = Jsoup.parse(xmlRaw, feed.url, Parser.xmlParser())
        val entries = doc.select("item").ifEmpty { doc.select("entry") }
        val results = mutableListOf<RssFeedItem>()

        for (el in entries) {
            val parsed = parseFeedEntry(el, feed, watchKeywords)
            if (parsed != null) {
                results.add(parsed)
            }
        }
        return results
    }

    private fun Element.firstTagText(vararg tagNames: String): String {
        for (name in tagNames) {
            val found = children().firstOrNull {
                it.tagName().equals(name, ignoreCase = true) ||
                    it.tagName().substringAfter(':').equals(name.substringAfter(':'), ignoreCase = true) &&
                    (!name.contains(':') || it.tagName().equals(name, ignoreCase = true))
            }
            if (found != null) {
                val t = found.text().trim()
                if (t.isNotEmpty()) return t
            }
        }
        return ""
    }

    private fun parseFeedEntry(
        el: Element,
        feed: RssFeedSource,
        watchKeywords: List<String>
    ): RssFeedItem? {
        val title = el.selectFirst("title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null

        val descriptionHtml = el.firstTagText("description", "summary", "content", "content:encoded")
        val guidText = el.selectFirst("guid, id")?.text()?.trim().orEmpty()

        // Extract link / enclosure attributes
        var linkText = ""
        for (lnk in el.select("link")) {
            val href = lnk.attr("href").trim()
            val txt = lnk.text().trim()
            val cand = href.ifBlank { txt }
            if (cand.startsWith("magnet:", ignoreCase = true)) {
                linkText = cand
                break
            }
            if (linkText.isBlank() && cand.isNotBlank()) {
                linkText = cand
            }
        }

        val enclosureEl = el.selectFirst("enclosure")
        val enclosureUrl = enclosureEl?.attr("url")?.trim().orEmpty()
        val enclosureLen = enclosureEl?.attr("length")?.toLongOrNull() ?: 0L

        // Torznab / Jackett / Prowlarr attributes
        var torznabMagnet = ""
        var torznabHash = ""
        var torznabSeeders: Int? = null
        var torznabPeers: Int? = null
        var torznabSize = 0L

        for (attrEl in el.select("torznab\\:attr, newznab\\:attr, attr")) {
            val aName = attrEl.attr("name").lowercase(Locale.ROOT)
            val aVal = attrEl.attr("value").trim()
            when (aName) {
                "magneturl" -> if (aVal.startsWith("magnet:", ignoreCase = true)) torznabMagnet = aVal
                "infohash" -> if (aVal.isNotBlank()) torznabHash = aVal
                "seeders" -> torznabSeeders = aVal.toIntOrNull()
                "peers" -> torznabPeers = aVal.toIntOrNull()
                "size" -> torznabSize = aVal.toLongOrNull() ?: torznabSize
            }
        }

        // InfoHash candidates (Nyaa, EZTV, YTS, Torznab, etc.)
        val explicitHash = listOf(
            el.firstTagText("nyaa:infoHash", "torrent:infoHash", "info_hash", "infoHash", "btih"),
            torznabHash,
            Regex("/torrent/download/([A-Fa-f0-9]{40})").find(enclosureUrl)?.groupValues?.get(1).orEmpty(),
            Regex("/torrent/download/([A-Fa-f0-9]{40})").find(linkText)?.groupValues?.get(1).orEmpty(),
            Regex("/torrent/download/([A-Fa-f0-9]{40})").find(descriptionHtml)?.groupValues?.get(1).orEmpty(),
            guidText.takeIf { Regex("^[A-Fa-f0-9]{40}$").matches(it) }.orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        // Direct Magnet candidates
        val rawMagnet = listOf(
            torznabMagnet,
            linkText.takeIf { it.startsWith("magnet:", ignoreCase = true) }.orEmpty(),
            enclosureUrl.takeIf { it.startsWith("magnet:", ignoreCase = true) }.orEmpty(),
            el.firstTagText("torrent:magnetURI", "magnetURI", "magnet"),
            Regex("magnet:\\?xt=urn:btih:[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
                .find(descriptionHtml)?.value?.replace("&amp;", "&").orEmpty()
        ).firstOrNull { it.startsWith("magnet:", ignoreCase = true) }.orEmpty()

        val finalHash = Formatters.extractInfoHash(rawMagnet.ifBlank { explicitHash })
        val finalMagnet = when {
            rawMagnet.isNotBlank() -> Formatters.buildMagnet(rawMagnet, title)
            finalHash.isNotBlank() -> Formatters.buildMagnet(finalHash, title)
            else -> ""
        }

        val torrentDownloadUrl = listOf(enclosureUrl, linkText).firstOrNull { u ->
            u.startsWith("http", ignoreCase = true) && (
                u.contains(".torrent", ignoreCase = true) ||
                    u.contains("page=download", ignoreCase = true) ||
                    u.contains("/torrent/download/", ignoreCase = true)
                )
        }.orEmpty()

        // Skip non-torrent informational entries that have neither magnet nor .torrent download URL
        if (finalMagnet.isBlank() && torrentDownloadUrl.isBlank()) {
            return null
        }

        val pageUrl = listOf(guidText, linkText).firstOrNull {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }.orEmpty()

        // Size extraction
        val sizeFromTags = Formatters.parseSizeToBytes(
            el.firstTagText("nyaa:size", "torrent:contentLength", "contentLength", "size")
        )
        val sizeFromDesc = if (sizeFromTags <= 0L && enclosureLen <= 0L && torznabSize <= 0L) {
            val plainDesc = Jsoup.parse(descriptionHtml).text()
            Regex("([\\d.]+\\s*(?:KB|KiB|MB|MiB|GB|GiB|TB|TiB))", RegexOption.IGNORE_CASE)
                .find(plainDesc)?.value?.let { Formatters.parseSizeToBytes(it) } ?: 0L
        } else 0L
        val finalSize = listOf(sizeFromTags, torznabSize, enclosureLen, sizeFromDesc).firstOrNull { it > 0L } ?: 0L

        // Seeders & Leechers extraction
        val seeders = el.firstTagText("nyaa:seeders", "torrent:seeds", "seeds", "seeders").toIntOrNull()
            ?: torznabSeeders
        val leechers = el.firstTagText("nyaa:leechers", "torrent:peers", "leechers").toIntOrNull()
            ?: (if (torznabPeers != null && seeders != null) (torznabPeers - seeders).coerceAtLeast(0) else null)

        val pubDateRaw = el.firstTagText("pubDate", "published", "updated", "dc:date")
        val pubDateMs = Formatters.parseDateMs(pubDateRaw)

        val videoCode = Formatters.extractVideoCode(title)
        val qFlags = Formatters.classifyQuality(title, finalMagnet)
        val matched = matchWatchKeywords(title, videoCode, watchKeywords)

        val uniqueId = finalHash.ifBlank {
            finalMagnet.ifBlank { torrentDownloadUrl.ifBlank { "${feed.id}:${UUID.randomUUID()}" } }
        }

        return RssFeedItem(
            id = "${feed.id}|$uniqueId",
            feedId = feed.id,
            feedTitle = feed.title,
            title = title,
            magnet = finalMagnet,
            torrentUrl = torrentDownloadUrl,
            pageUrl = pageUrl,
            infoHash = finalHash,
            sizeBytes = finalSize,
            seeders = seeders,
            leechers = leechers,
            pubDateMs = pubDateMs,
            videoCode = videoCode,
            qFlags = qFlags,
            matchedKeywords = matched
        )
    }

    fun matchWatchKeywords(
        title: String,
        videoCode: String,
        watchKeywords: List<String>
    ): List<String> {
        if (watchKeywords.isEmpty()) return emptyList()
        val lowerTitle = title.lowercase(Locale.ROOT)
        val lowerCode = videoCode.lowercase(Locale.ROOT)
        val matched = mutableListOf<String>()

        for (rawKw in watchKeywords) {
            val kw = rawKw.trim()
            if (kw.isEmpty()) continue
            val kwLow = kw.lowercase(Locale.ROOT)

            val directHit = lowerTitle.contains(kwLow) || (lowerCode.isNotEmpty() && lowerCode.contains(kwLow))
            val tokenHit = if (!directHit && kwLow.contains(' ')) {
                val tokens = kwLow.split(Regex("\\s+")).filter { it.isNotEmpty() }
                tokens.isNotEmpty() && tokens.all { lowerTitle.contains(it) }
            } else false
            val codeSmartHit = if (!directHit && !tokenHit && kw.length >= 4) {
                Formatters.matchesKeywordTitle(title, kw)
            } else false

            if (directHit || tokenHit || codeSmartHit) {
                matched.add(kw)
            }
        }
        return matched
    }

    fun cleanTitleForSubtitleSearch(rawTitle: String, videoCode: String): String {
        if (videoCode.isNotBlank()) return videoCode
        var cleaned = rawTitle
            .replace(Regex("^\\[[^\\]]+\\]\\s*"), "")
            .replace(Regex("\\([^)]*(?:1080p|720p|2160p|x264|x265|HEVC|BluRay|WEB-DL|WEBRip|AAC)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\[[^\\]]*(?:1080p|720p|2160p|x264|x265|HEVC|AAC|FLAC|[A-F0-9]{8})[^\\]]*\\]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:2160p|1080p|720p|480p|4K|UHD|BluRay|BDRip|WEBRip|WEB-DL|HDRip|x264|x265|H\\.?264|H\\.?265|HEVC|10bit|AAC|DDP5\\.?1|Atmos|REMUX)\\b.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[._]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length < 2) {
            cleaned = rawTitle.replace(Regex("[._]+"), " ").trim().take(65)
        }
        return cleaned.take(75).trim()
    }
}
