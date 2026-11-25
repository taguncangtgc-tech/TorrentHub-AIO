package com.torrenthub.aio.util

import com.torrenthub.aio.model.QualityFlags
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

object Formatters {
    private val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://public.popcorn-tracker.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce"
    )

    val TRACKER_PARAMS: String = PUBLIC_TRACKERS.joinToString("") {
        "&tr=" + urlEncode(it)
    }

    @Volatile
    var dynamicAutoAppendTrackers: Boolean = true

    @Volatile
    var dynamicTrackersList: List<String> = PUBLIC_TRACKERS

    fun urlEncode(value: String): String =
        try {
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            value
        }

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun randomNonce(len: Int = 8): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(len) {
            repeat(len) { append(chars[Random.nextInt(chars.length)]) }
        }
    }

    fun isEd2kLink(link: String): Boolean =
        link.trim().startsWith("ed2k://", ignoreCase = true)

    fun extractInfoHash(str: String): String {
        val raw = str.trim()
        Regex("^ed2k://\\|file\\|[^|]*\\|\\d+\\|([a-fA-F0-9]{32})").find(raw)?.let {
            return "ed2k:${it.groupValues[1].lowercase(Locale.ROOT)}"
        }
        Regex("btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})", RegexOption.IGNORE_CASE).find(raw)?.let {
            return it.groupValues[1].lowercase(Locale.ROOT)
        }
        if (Regex("^[a-fA-F0-9]{40}$").matches(raw)) {
            return raw.lowercase(Locale.ROOT)
        }
        return ""
    }

    fun buildMagnet(hashOrLink: String, title: String = ""): String {
        val raw = hashOrLink.trim()
        if (raw.startsWith("ed2k:", ignoreCase = true)) {
            return raw
        }
        if (raw.startsWith("magnet:", ignoreCase = true)) {
            return TrackerManager.applyTrackersToMagnet(
                rawLink = raw,
                enabled = dynamicAutoAppendTrackers,
                trackers = dynamicTrackersList
            )
        }
        val cleanHash = extractInfoHash(raw)
        if (cleanHash.isBlank() || cleanHash.startsWith("ed2k:")) return ""
        val dn = urlEncode(title.ifBlank { cleanHash })
        val base = "magnet:?xt=urn:btih:$cleanHash&dn=$dn"
        return if (dynamicAutoAppendTrackers) {
            TrackerManager.applyTrackersToMagnet(base, enabled = true, trackers = dynamicTrackersList)
        } else {
            base
        }
    }

    fun parseSizeToBytes(rawVal: Any?): Long {
        if (rawVal == null) return 0L
        if (rawVal is Number) return rawVal.toLong().coerceAtLeast(0L)
        val s = rawVal.toString().trim().replace(",", "")
        if (s.isEmpty()) return 0L
        if (Regex("^\\d+(?:\\.\\d+)?$").matches(s)) {
            return s.toDoubleOrNull()?.roundToLong()?.coerceAtLeast(0L) ?: 0L
        }
        val m = Regex("([\\d.]+)\\s*(B|KB|KIB|MB|MIB|GB|GIB|TB|TIB)\\b", RegexOption.IGNORE_CASE).find(s)
            ?: return 0L
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val mult = when (m.groupValues[2].uppercase(Locale.ROOT)) {
            "B" -> 1.0
            "KB", "KIB" -> 1024.0
            "MB", "MIB" -> 1024.0.pow(2)
            "GB", "GIB" -> 1024.0.pow(3)
            "TB", "TIB" -> 1024.0.pow(4)
            else -> 1.0
        }
        return (n * mult).roundToLong().coerceAtLeast(0L)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        val pattern = when {
            i >= 3 -> "%.2f %s"
            i >= 1 -> "%.1f %s"
            else -> "%.0f %s"
        }
        return String.format(Locale.US, pattern, v, units[i])
    }

    fun parseDateMs(rawVal: Any?): Long {
        if (rawVal == null) return 0L
        if (rawVal is Number) {
            val n = rawVal.toLong()
            if (n <= 0L) return 0L
            return if (n < 100_000_000_000L) n * 1000L else n
        }
        val s = rawVal.toString().trim()
        if (s.isEmpty()) return 0L
        if (Regex("^\\d{10,13}$").matches(s)) {
            val n = s.toLongOrNull() ?: return 0L
            return if (s.length == 10) n * 1000L else n
        }
        Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(s)?.let { m ->
            return try {
                val sdf = SimpleDateFormat("yyyy-M-d", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse("${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}")?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
        val rfcFormats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss",
            "dd MMM yyyy"
        )
        for (fmt in rfcFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                val parsed = sdf.parse(s)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    fun formatDate(tsMs: Long): String {
        if (tsMs <= 0L) return "—"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.format(Date(tsMs))
        } catch (_: Exception) {
            "—"
        }
    }

    private val EXCLUDED_VIDEO_PREFIXES = setOf(
        "FULLHD", "H264", "H265", "X264", "X265", "1080P", "720P",
        "2160P", "PART", "DISC", "HEVC", "MPEG", "MP4", "MKV"
    )

    fun extractVideoCode(text: String): String {
        val s = text.replace(Regex("\\b(?:19|20)\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b"), " ")
        Regex("\\b(?:FC2[-_\\s]?(?:PPV[-_\\s]?)?|PPV[-_\\s]?)(\\d{6,9})\\b", RegexOption.IGNORE_CASE)
            .find(s)?.let { return "FC2-PPV-${it.groupValues[1]}" }
        Regex("\\b(\\d{6})[-_](\\d{2,3})\\b")
            .find(s)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}" }
        Regex("\\b([A-Za-z]{2,8})[-_\\s]?(\\d{2,6})\\b")
            .find(s)?.let { m ->
                val prefix = m.groupValues[1].uppercase(Locale.ROOT)
                if (prefix !in EXCLUDED_VIDEO_PREFIXES) {
                    return "$prefix-${m.groupValues[2]}"
                }
            }
        return ""
    }

    fun extractUpperCodeFromFileName(origName: String): String {
        val base = origName.replace(Regex("\\.[^.]+$"), "").trim()
        if (base.isEmpty()) return ""
        Regex("FC2[-_ ]?PPV[-_ ]?(\\d{5,9})", RegexOption.IGNORE_CASE).find(base)?.let {
            return "FC2-PPV-${it.groupValues[1]}"
        }
        Regex("(?:^|[^a-zA-Z0-9])([a-zA-Z]{2,8})[-_ ]?(\\d{2,6})(?:[^0-9]|$)").find(base)?.let {
            return "${it.groupValues[1].uppercase(Locale.ROOT)}-${it.groupValues[2]}"
        }
        return base.uppercase(Locale.ROOT)
    }

    private fun hasCrackedCode(text: String): Boolean {
        val re = Regex("\\b(?:UN|UC)\\b")
        for (m in re.findAll(text)) {
            val before = text.substring(0, m.range.first)
            val after = text.substring(m.range.last + 1)
            val adjDigit = before.lastOrNull()?.isDigit() == true || after.firstOrNull()?.isDigit() == true
            val adjSub = before.endsWith("字幕") || after.startsWith("字幕")
            if (!adjDigit && !adjSub) return true
        }
        return Regex("\\d[-_\\s]U(?:$|[\\s._-])", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    fun classifyQuality(title: String, magnet: String): QualityFlags {
        val clean = title.replace(
            Regex("【[^】]*(?:APP|夸克|UC搜|(?:[a-z0-9-]+\\.)+(?:com|net|org|cc|sbs|top))[^】]*】", RegexOption.IGNORE_CASE),
            " "
        )
        val is4K = Regex("(?:[^A-Za-z0-9]|^)(?:4K(?:UHD)?|2160P)(?:[^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(clean)
        val isSub =
            Regex("(?:[^A-Za-z]|^)FHDC(?:[^A-Za-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(clean) ||
                Regex("[-_](?:UC|CH?)(?:[^A-Za-z]|$)").containsMatchIn(clean) ||
                Regex("(?:中字|中文|字幕|繁體|繁体|漢化|汉化|內嵌|内嵌|內封|内封|雙語|双语)").containsMatchIn(clean) ||
                Regex("\\b(?:sub(?:bed|title)?|indosub|engsub)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)
        val isCracked =
            Regex("(?:破解|破坏|破壞|无码|無碼)").containsMatchIn(clean) ||
                Regex("\\b(?:uncensored|mosaic|leak(?:ed)?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ||
                hasCrackedCode(clean)
        val isEd2k = isEd2kLink(magnet)
        return QualityFlags(is4K = is4K, isSub = isSub, isCracked = isCracked, isEd2k = isEd2k)
    }

    fun matchesKeywordTitle(title: String, keyword: String): Boolean {
        val upperTitle = title.uppercase(Locale.ROOT)
        val compactKw = keyword.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        if (upperTitle.isEmpty() || compactKw.isEmpty()) return true
        val codeM = Regex("^([A-Z]{2,15})0*(\\d{2,10})$").find(compactKw)
        if (codeM != null) {
            val num = codeM.groupValues[2].replace(Regex("^0+(?=\\d)"), "")
            return Regex("(?:^|[^A-Z0-9])${codeM.groupValues[1]}[\\s._-]*0*$num(?!\\d)", RegexOption.IGNORE_CASE)
                .containsMatchIn(upperTitle)
        }
        return upperTitle.replace(Regex("[^A-Z0-9]"), "").contains(compactKw)
    }
}
