package com.torrenthub.aio.util

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.roundToLong

enum class SubtitleCharset(val id: String, val label: String, val charsetName: String) {
    AUTO("auto", "Auto (UTF-8)", "UTF-8"),
    UTF8("utf8", "UTF-8", "UTF-8"),
    UTF8_BOM("utf8-bom", "UTF-8 + BOM", "UTF-8"),
    GB18030("gb18030", "GB18030 / GBK", "GB18030"),
    BIG5("big5", "Big5", "Big5"),
    SHIFT_JIS("shift_jis", "Shift_JIS", "Shift_JIS"),
    EUC_KR("euc_kr", "EUC-KR", "EUC-KR"),
    WIN1252("windows-1252", "Windows-1252", "windows-1252"),
    UTF16LE("utf-16le", "UTF-16LE", "UTF-16LE"),
    UTF16BE("utf-16be", "UTF-16BE", "UTF-16BE")
}

object SubtitleTools {

    private fun isStrictValidCharset(bytes: ByteArray, charset: Charset): Boolean {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun detectAndDecode(bytes: ByteArray): Pair<String, SubtitleCharset> {
        if (bytes.isEmpty()) return "" to SubtitleCharset.UTF8

        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            val str = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            return str to SubtitleCharset.UTF8_BOM
        }

        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to SubtitleCharset.UTF16LE
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to SubtitleCharset.UTF16BE
            }
        }

        if (isStrictValidCharset(bytes, Charsets.UTF_8)) {
            return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF") to SubtitleCharset.UTF8
        }

        // Legacy non-UTF8 subtitle fallback candidates
        val candidates = listOf(
            SubtitleCharset.GB18030,
            SubtitleCharset.SHIFT_JIS,
            SubtitleCharset.EUC_KR,
            SubtitleCharset.BIG5,
            SubtitleCharset.WIN1252
        )
        for (cand in candidates) {
            try {
                val cs = Charset.forName(cand.charsetName)
                if (isStrictValidCharset(bytes, cs)) {
                    val decoded = String(bytes, cs)
                    if (decoded.count { it == '\uFFFD' || it == '\u0000' } == 0) {
                        return decoded to cand
                    }
                }
            } catch (_: Exception) {}
        }

        val fallback = try {
            String(bytes, Charset.forName("GB18030"))
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
        return fallback.removePrefix("\uFEFF") to SubtitleCharset.GB18030
    }

    fun decodeWithCharset(bytes: ByteArray, mode: SubtitleCharset): String {
        if (bytes.isEmpty()) return ""
        if (mode == SubtitleCharset.AUTO) {
            return detectAndDecode(bytes).first
        }
        return try {
            val raw = when (mode) {
                SubtitleCharset.UTF8, SubtitleCharset.UTF8_BOM -> String(bytes, Charsets.UTF_8)
                SubtitleCharset.UTF16LE -> String(bytes, Charsets.UTF_16LE)
                SubtitleCharset.UTF16BE -> String(bytes, Charsets.UTF_16BE)
                else -> String(bytes, Charset.forName(mode.charsetName))
            }
            val clean = raw.removePrefix("\uFEFF").replace("\u0000", "")
            if (mode == SubtitleCharset.UTF8_BOM) "\uFEFF$clean" else clean
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        }
    }

    fun repairMojibakeUtf8(text: String): String {
        val clean = text.removePrefix("\uFEFF")
        return try {
            val win1252 = Charset.forName("windows-1252")
            val bytes = clean.toByteArray(win1252)
            if (isStrictValidCharset(bytes, Charsets.UTF_8)) {
                String(bytes, Charsets.UTF_8)
            } else {
                clean
            }
        } catch (_: Exception) {
            clean
        }
    }

    fun normalizeToUtf8Bytes(content: String, includeBom: Boolean = false): ByteArray {
        val clean = content.removePrefix("\uFEFF").replace("\u0000", "")
        val finalStr = if (includeBom) "\uFEFF$clean" else clean
        return finalStr.toByteArray(Charsets.UTF_8)
    }

    private fun parseCueTimeToMs(str: String): Long? {
        val m = Regex("^(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})$").find(str.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val ms = m.groupValues[4].padEnd(3, '0').toLong()
        return ((h * 3600L + min * 60L + sec) * 1000L) + ms
    }

    private fun formatMsToCueTime(totalMs: Long, sep: Char, includeHours: Boolean): String {
        val clamped = totalMs.coerceAtLeast(0L)
        val ms = clamped % 1000L
        val totalSec = clamped / 1000L
        val sec = totalSec % 60L
        val totalMin = totalSec / 60L
        val min = totalMin % 60L
        val h = totalMin / 60L
        return if (!includeHours && h == 0L) {
            "%02d:%02d%c%03d".format(Locale.US, min, sec, sep, ms)
        } else {
            "%02d:%02d:%02d%c%03d".format(Locale.US, h, min, sec, sep, ms)
        }
    }

    private val CUE_TIME_LINE_RE = Regex("^(\\s*)((?:\\d+:)?\\d{1,2}:\\d{1,2}[.,]\\d{1,3})(\\s*-->\\s*)((?:\\d+:)?\\d{1,2}:\\d{1,2}[.,]\\d{1,3})(.*)$")
    private val ASS_DIALOGUE_LINE_RE = Regex("^((?:Dialogue|Comment):\\s*[^,]*,)(\\d+:\\d{2}:\\d{2}\\.\\d{2})(,)(\\d+:\\d{2}:\\d{2}\\.\\d{2})(,.*)$")

    private fun parseAssTimeMs(s: String): Long? {
        val p = Regex("^(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})$").find(s.trim()) ?: return null
        return ((p.groupValues[1].toLong() * 3600L + p.groupValues[2].toLong() * 60L + p.groupValues[3].toLong()) * 1000L) +
            (p.groupValues[4].toLong() * 10L)
    }

    private fun formatAssTimeMs(msTotal: Long): String {
        val c = msTotal.coerceAtLeast(0L)
        val cs = (c % 1000L) / 10L
        val ts = c / 1000L
        return "%d:%02d:%02d.%02d".format(Locale.US, ts / 3600L, (ts / 60L) % 60L, ts % 60L, cs)
    }

    fun shiftTimestamps(content: String, shiftSec: Double): String {
        val shiftMs = (shiftSec * 1000.0).roundToLong()
        if (shiftMs == 0L || content.isBlank()) return content

        val hasBom = content.startsWith("\uFEFF")
        val eol = if (content.contains("\r\n")) "\r\n" else "\n"
        val lines = content.removePrefix("\uFEFF").split(Regex("\\r?\\n"))

        val shifted = lines.map { line ->
            val cueMatch = CUE_TIME_LINE_RE.find(line)
            if (cueMatch != null) {
                val lead = cueMatch.groupValues[1]
                val t1 = cueMatch.groupValues[2]
                val arrow = cueMatch.groupValues[3]
                val t2 = cueMatch.groupValues[4]
                val tail = cueMatch.groupValues[5]
                val sMs = parseCueTimeToMs(t1)
                val eMs = parseCueTimeToMs(t2)
                if (sMs != null && eMs != null) {
                    val sep = if (t1.contains('.')) '.' else ','
                    val hasHours = t1.split(':').size >= 3
                    val dur = (eMs - sMs).coerceAtLeast(100L)
                    val ns = (sMs + shiftMs).coerceAtLeast(0L)
                    val ne = if (sMs + shiftMs < 0L) (eMs + shiftMs).coerceAtLeast(minOf(dur, 500L)) else (eMs + shiftMs).coerceAtLeast(ns + 80L)
                    lead + formatMsToCueTime(ns, sep, hasHours) + arrow + formatMsToCueTime(ne, sep, hasHours) + tail
                } else {
                    line
                }
            } else {
                val assMatch = ASS_DIALOGUE_LINE_RE.find(line)
                if (assMatch != null) {
                    val sMs = parseAssTimeMs(assMatch.groupValues[2])
                    val eMs = parseAssTimeMs(assMatch.groupValues[4])
                    if (sMs != null && eMs != null) {
                        val ns = (sMs + shiftMs).coerceAtLeast(0L)
                        val ne = (eMs + shiftMs).coerceAtLeast(ns + 80L)
                        assMatch.groupValues[1] + formatAssTimeMs(ns) + assMatch.groupValues[3] + formatAssTimeMs(ne) + assMatch.groupValues[5]
                    } else {
                        line
                    }
                } else {
                    line
                }
            }
        }.joinToString(eol)

        return if (hasBom) "\uFEFF$shifted" else shifted
    }

    fun isTimestampLine(line: String): Boolean =
        line.contains("-->") || line.startsWith("Dialogue:") || line.startsWith("Comment:")
}
