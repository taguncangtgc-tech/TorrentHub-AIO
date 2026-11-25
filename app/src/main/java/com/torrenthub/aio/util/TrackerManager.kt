package com.torrenthub.aio.util

import com.torrenthub.aio.network.HttpClient
import java.net.URLDecoder
import java.util.Locale

object TrackerManager {

    const val DEFAULT_TRACKER_URL =
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

    val PRESET_TRACKER_URLS: List<Pair<String, String>> = listOf(
        "ngosang Best" to "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt",
        "ngosang All UDP" to "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_all_udp.txt",
        "ngosang HTTPS" to "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_all_https.txt",
        "XIU2 Best" to "https://raw.githubusercontent.com/XIU2/TrackersListCollection/master/best.txt"
    )

    val BUILTIN_PUBLIC_TRACKERS: List<String> = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://public.popcorn-tracker.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://explodie.org:6969/announce",
        "udp://uploads.gamecoast.net:6969/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
        "https://tracker.gbitt.info:443/announce",
        "http://tracker.bt4g.com:2095/announce"
    )

    private val VALID_SCHEME_REGEX = Regex("^(?:udp|http|https|ws|wss)://[^\\s\"'<>]+$", RegexOption.IGNORE_CASE)

    fun parseTrackers(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        rawText.lineSequence()
            .flatMap { it.split(Regex("[\\s,;]+")).asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && VALID_SCHEME_REGEX.matches(it) }
            .forEach { tr ->
                val norm = tr.lowercase(Locale.ROOT)
                if (seen.add(norm)) {
                    out.add(tr)
                }
            }
        return out
    }

    fun getActiveTrackers(fetchedRaw: String, customRaw: String): List<String> {
        val baseList = parseTrackers(fetchedRaw).ifEmpty { BUILTIN_PUBLIC_TRACKERS }
        val customList = parseTrackers(customRaw)
        val seen = HashSet<String>()
        val merged = mutableListOf<String>()
        // Custom user trackers take priority first, followed by public list
        for (tr in (customList + baseList)) {
            if (seen.add(tr.lowercase(Locale.ROOT))) {
                merged.add(tr)
            }
        }
        return merged
    }

    suspend fun fetchTrackersFromUrl(rawUrl: String): List<String> {
        val cleanUrl = rawUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            throw IllegalArgumentException("Invalid URL: $cleanUrl")
        }

        val body = try {
            HttpClient.getString(cleanUrl, timeoutSec = 20L)
        } catch (primaryErr: Exception) {
            // Automatic jsDelivr CDN fallback when raw.githubusercontent.com is throttled
            val mirrorUrl = buildJsDelivrMirror(cleanUrl)
            if (mirrorUrl != null) {
                HttpClient.getString(mirrorUrl, timeoutSec = 20L)
            } else {
                throw primaryErr
            }
        }

        val parsed = parseTrackers(body)
        if (parsed.isEmpty()) {
            throw IllegalStateException("No valid trackers found at URL")
        }
        return parsed
    }

    private fun buildJsDelivrMirror(url: String): String? {
        val m = Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$", RegexOption.IGNORE_CASE)
            .find(url) ?: return null
        val user = m.groupValues[1]
        val repo = m.groupValues[2]
        val branch = m.groupValues[3]
        val path = m.groupValues[4]
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path"
    }

    fun applyTrackersToMagnet(
        rawLink: String,
        enabled: Boolean,
        trackers: List<String>
    ): String {
        val trimmed = rawLink.trim()
        if (!enabled || trackers.isEmpty() || !trimmed.startsWith("magnet:?", ignoreCase = true)) {
            return trimmed
        }

        val existing = HashSet<String>()
        val queryPart = trimmed.substringAfter("magnet:?", "")
        for (param in queryPart.split('&')) {
            if (param.startsWith("tr=", ignoreCase = true)) {
                val rawVal = param.substring(3)
                val decoded = try {
                    URLDecoder.decode(rawVal, "UTF-8")
                } catch (_: Exception) {
                    rawVal
                }
                if (decoded.isNotBlank()) {
                    existing.add(decoded.trim().lowercase(Locale.ROOT))
                }
            }
        }

        val sb = StringBuilder(trimmed)
        for (tr in trackers) {
            val key = tr.trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty() && existing.add(key)) {
                sb.append("&tr=").append(Formatters.urlEncode(tr.trim()))
            }
        }
        return sb.toString()
    }

    fun buildMagnetLink(
        hashOrMagnetInput: String,
        displayName: String = "",
        appendTrackers: Boolean = true,
        trackers: List<String> = BUILTIN_PUBLIC_TRACKERS
    ): String {
        val raw = hashOrMagnetInput.trim()
        if (raw.isEmpty()) return ""

        if (raw.startsWith("ed2k://", ignoreCase = true)) {
            return raw
        }

        if (raw.startsWith("magnet:?", ignoreCase = true)) {
            var base = raw
            if (displayName.isNotBlank() && !Regex("(?:\\?|&)dn=", RegexOption.IGNORE_CASE).containsMatchIn(base)) {
                base += "&dn=${Formatters.urlEncode(displayName.trim())}"
            }
            return applyTrackersToMagnet(base, appendTrackers, trackers)
        }

        // Accept 40-char SHA1 hex, 32-char Base32 BTIH, orurn:btih:...
        val extracted = Regex("(?:urn:btih:)?([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})").find(raw)
            ?.groupValues?.get(1)
            ?.let { if (it.length == 40) it.lowercase(Locale.ROOT) else it.uppercase(Locale.ROOT) }
            ?: return ""

        val dnPart = if (displayName.isNotBlank()) {
            "&dn=${Formatters.urlEncode(displayName.trim())}"
        } else {
            "&dn=${Formatters.urlEncode(extracted)}"
        }
        val baseMagnet = "magnet:?xt=urn:btih:$extracted$dnPart"
        return applyTrackersToMagnet(baseMagnet, appendTrackers, trackers)
    }
}
