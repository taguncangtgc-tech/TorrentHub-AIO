package com.torrenthub.aio.data

import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

object GeminiEngine {
    private const val GOOGLE_API = "https://generativelanguage.googleapis.com/v1beta"
    private const val OR_API = "https://openrouter.ai/api/v1"
    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    private const val CHUNK_SIZE = 50
    private const val SLIDING_CONTEXT_SIZE = 4

    val DEFAULT_MODELS = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash-lite",
        "gemini-3.6-flash",
        "gemini-2.5-flash",
        "deepseek/deepseek-chat",
        "qwen/qwen-2.5-72b-instruct",
        "x-ai/grok-3-mini"
    )

    private val FALLBACK_GOOGLE_CHAIN = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash"
    )

    const val DEFAULT_NOTES_TRANSLATE =
        "Terjemahkan dialog ke bahasa Indonesia percakapan sehari-hari yang natural, ekspresif, dan sesuai konteks adegan—hindari terjemahan kaku kata per kata. Sesuaikan tingkat keakraban dan kata ganti (aku/kamu atau panggilan hormat/keluarga) berdasarkan hubungan antarkarakter. Pertahankan nuansa emosi, ekspresi singkat, serta nama panggilan khas tanpa menyensor atau memotong makna dialog."

    const val DEFAULT_NOTES_POLISH =
        "Perbaiki kalimat bahasa Indonesia hasil terjemahan mesin ini agar terdengar seperti percakapan sehari-hari yang alami, luwes, dan emosional. Ubah struktur kalimat yang kaku atau terlalu harfiah menjadi bahasa lisan yang enak dibaca di layar. Sesuaikan kata ganti orang (aku/kamu, saya/Anda, atau panggilan nama/kakak/adik/pak/bu) secara konsisten berdasarkan konteks dan hubungan antarkarakter. Jangan meringkas, menghilangkan, atau menyensor dialog, serta pertahankan tanda baca, jeda baris, dan format asli subtitle."

    private val CJK_RE = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uAC00-\\uD7AF]")
    private val LETTER_OR_DIGIT_RE = Regex("[\\p{L}\\p{N}]")
    private val ID_WORDS_RE = Regex(
        "\\b(yang|dan|tidak|nggak|gak|aku|kamu|saya|anda|ini|itu|dengan|untuk|sudah|belum|jangan|bisa|kenapa|apa|kalau|tapi|mereka|kita|kami|dia|mau|lagi|saja|aja|banget|dong|sih|kok|akan|dari|dalam|harus|ingin|tolong)\\b",
        RegexOption.IGNORE_CASE
    )
    private val WATERMARK_AD_RE = Regex(
        "(https?://|www\\.|\\.[a-z]{2,4}/[a-z0-9]|opensubtitles|subscene|yify|addic7ed|subtitles?\\s+by|synced\\s*(?:&|and)?\\s*corrected|translated\\s+by|diterjemahkan\\s+oleh|subtitle\\s+indonesia\\s+oleh|t\\.me/|telegram|discord\\.gg|download\\s+subtitle|support\\s+us\\s+at|advertise\\s+your\\s+product)",
        RegexOption.IGNORE_CASE
    )

    fun isOpenRouterKey(key: String): Boolean =
        key.trim().startsWith("sk-or-", ignoreCase = true)

    fun parseKeys(raw: String): List<String> {
        val all = raw.split(Regex("[\\n,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val freeKeys = all.filterNot { isOpenRouterKey(it) }
        val orKeys = all.filter { isOpenRouterKey(it) }
        return freeKeys + orKeys
    }

    fun resolveModelForProvider(modelName: String, useOpenRouter: Boolean): String {
        val clean = modelName.ifBlank { DEFAULT_MODEL }.trim()
        return if (useOpenRouter) {
            if (clean.contains("/")) clean else "google/$clean"
        } else {
            if (clean.contains("/") && !clean.startsWith("google/", ignoreCase = true)) {
                DEFAULT_MODEL
            } else {
                clean.replace(Regex("^google/", RegexOption.IGNORE_CASE), "").substringBefore(":")
            }
        }
    }

    fun detectLanguageMode(texts: List<String>): String {
        val nonEmpty = texts.filter { it.isNotBlank() && !isWatermarkLine(it) }
        if (nonEmpty.isEmpty()) return "translate"
        val step = maxOf(1, nonEmpty.size / 40)
        val sample = mutableListOf<String>()
        var i = 0
        while (i < nonEmpty.size && sample.size < 40) {
            sample.add(nonEmpty[i])
            i += step
        }
        var cjkLines = 0
        var idLines = 0
        var totalIdHits = 0
        for (line in sample) {
            if (CJK_RE.containsMatchIn(line)) {
                cjkLines++
            } else {
                val hits = ID_WORDS_RE.findAll(line).count()
                if (hits > 0) {
                    idLines++
                    totalIdHits += hits
                }
            }
        }
        if (cjkLines >= 3) return "translate"
        if (idLines >= maxOf(3, (sample.size * 0.28).toInt()) && totalIdHits >= 5) return "polish"
        return "translate"
    }

    fun isWatermarkLine(text: String): Boolean = WATERMARK_AD_RE.containsMatchIn(text)

    fun formatOutputFileName(origName: String, nameMode: String): String {
        val ext = origName.substringAfterLast('.', "srt").lowercase(Locale.ROOT)
        val base = origName.replace(Regex("\\.[^.]+$"), "")
        return when (nameMode) {
            "code-upper" -> {
                val code = Formatters.extractUpperCodeFromFileName(origName)
                if (code.isNotBlank()) "$code.$ext" else "${base.uppercase(Locale.ROOT)}.$ext"
            }
            "upper-full" -> "${base.uppercase(Locale.ROOT)}.$ext"
            ".id" -> "$base.id.$ext"
            else -> origName
        }
    }

    /* ================= SUBTITLE PARSER & BUILDER ================= */
    data class AssSlot(val lineIdx: Int, val head: String, val prefixTags: String, val orig: String, val text: String)
    data class CueBlock(val raw: String? = null, val head: List<String> = emptyList(), val timeIdx: Int = -1, val body: String = "")

    data class ParsedSubtitle(
        val kind: String, // "ass" or "cue"
        val ext: String,
        val eol: String,
        val rawLines: List<String> = emptyList(),
        val assSlots: List<AssSlot> = emptyList(),
        val cues: List<CueBlock> = emptyList(),
        val texts: List<String>
    )

    data class BuildOptions(
        val shiftSec: Double = 0.0,
        val cleanAds: Boolean = true,
        val bilingual: Boolean = false
    )

    fun parseSubtitle(content: String, extRaw: String): ParsedSubtitle {
        val ext = extRaw.lowercase(Locale.ROOT)
        val eol = if (content.contains("\r\n")) "\r\n" else "\n"
        val src = content.removePrefix("\uFEFF")

        if (ext == "ass" || ext == "ssa") {
            val lines = src.split(Regex("\\r?\\n"))
            val slots = mutableListOf<AssSlot>()
            lines.forEachIndexed { idx, line ->
                val m = Regex("^(Dialogue:(?:[^,]*,){9})(.*)$").find(line)
                if (m != null) {
                    val rawDialogue = m.groupValues[2]
                    val leadMatch = Regex("^((?:\\{[^}]*\\})+)(.*)$").find(rawDialogue)
                    val prefixTags = leadMatch?.groupValues?.get(1).orEmpty()
                    val bodyWithInline = leadMatch?.groupValues?.get(2) ?: rawDialogue
                    val cleanText = bodyWithInline.replace(Regex("\\{[^}]*\\}"), "").trim()
                    slots.add(AssSlot(idx, m.groupValues[1], prefixTags, rawDialogue, cleanText))
                }
            }
            return ParsedSubtitle(
                kind = "ass",
                ext = ext,
                eol = eol,
                rawLines = lines,
                assSlots = slots,
                texts = slots.map { it.text }
            )
        }

        val blocks = src.split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
        val cues = blocks.map { b ->
            val ls = b.split(Regex("\\r?\\n"))
            val tIdx = ls.indexOfFirst { it.contains("-->") }
            if (tIdx < 0) {
                CueBlock(raw = b)
            } else {
                val body = ls.drop(tIdx + 1).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                CueBlock(head = ls.take(tIdx + 1), timeIdx = tIdx, body = body)
            }
        }
        return ParsedSubtitle(
            kind = "cue",
            ext = ext,
            eol = eol,
            cues = cues,
            texts = cues.map { if (it.raw != null) "" else it.body }
        )
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

    private fun shiftAssHeadLine(headStr: String, shiftMs: Long): String {
        if (shiftMs == 0L) return headStr
        val m = Regex("^(Dialogue:\\s*[^,]*,)(\\d+:\\d{2}:\\d{2}\\.\\d{2})(,)(\\d+:\\d{2}:\\d{2}\\.\\d{2})(,.*)$").find(headStr)
            ?: return headStr
        fun parseAss(s: String): Long? {
            val p = Regex("^(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})$").find(s) ?: return null
            return ((p.groupValues[1].toLong() * 3600L + p.groupValues[2].toLong() * 60L + p.groupValues[3].toLong()) * 1000L) +
                p.groupValues[4].toLong() * 10L
        }
        fun fmtAss(msTotal: Long): String {
            val c = msTotal.coerceAtLeast(0L)
            val cs = (c % 1000L) / 10L
            val ts = c / 1000L
            return "%d:%02d:%02d.%02d".format(Locale.US, ts / 3600L, (ts / 60L) % 60L, ts % 60L, cs)
        }
        val s = parseAss(m.groupValues[2]) ?: return headStr
        val e = parseAss(m.groupValues[4]) ?: return headStr
        val ns = (s + shiftMs).coerceAtLeast(0L)
        val ne = (e + shiftMs).coerceAtLeast(ns + 100L)
        return m.groupValues[1] + fmtAss(ns) + m.groupValues[3] + fmtAss(ne) + m.groupValues[5]
    }

    fun buildSubtitle(sub: ParsedSubtitle, trArray: List<String>, opts: BuildOptions): String {
        val shiftMs = (opts.shiftSec * 1000.0).roundToLong()

        if (sub.kind == "ass") {
            val lines = sub.rawLines.toMutableList()
            sub.assSlots.forEachIndexed { k, s ->
                val origClean = s.text
                if (opts.cleanAds && isWatermarkLine(origClean)) {
                    lines[s.lineIdx] = ""
                    return@forEachIndexed
                }
                val tr = trArray.getOrNull(k)?.trim().orEmpty()
                val finalBody = if (tr.isNotEmpty()) {
                    val normTr = tr.replace(Regex("\\r?\\n"), "\\\\N")
                    if (opts.bilingual && origClean.isNotEmpty() && !tr.equals(origClean, ignoreCase = true)) {
                        s.prefixTags + normTr + "\\N" + origClean.replace(Regex("\\r?\\n"), "\\\\N")
                    } else {
                        s.prefixTags + normTr
                    }
                } else {
                    s.orig
                }
                lines[s.lineIdx] = shiftAssHeadLine(s.head, shiftMs) + finalBody
            }
            return lines.filter { it.isNotEmpty() }.joinToString(sub.eol)
        }

        data class OutCue(
            val raw: String? = null,
            val head: MutableList<String> = mutableListOf(),
            val timeIdx: Int = 0,
            var startMs: Long? = null,
            var endMs: Long? = null,
            val sep: Char = ',',
            val suffix: String = "",
            val hasHours: Boolean = true,
            val bodyText: String = ""
        )

        val isVtt = sub.ext == "vtt"
        val outCues = mutableListOf<OutCue>()

        sub.cues.forEachIndexed { idx, p ->
            if (p.raw != null) {
                outCues.add(OutCue(raw = p.raw))
                return@forEachIndexed
            }
            val origBody = p.body
            if (opts.cleanAds && isWatermarkLine(origBody)) return@forEachIndexed

            val trText = trArray.getOrNull(idx)?.trim().orEmpty()
            var bodyText = trText.ifEmpty { origBody }
            if (opts.bilingual && trText.isNotEmpty() && origBody.isNotEmpty() && !trText.equals(origBody, ignoreCase = true)) {
                bodyText = "$trText\n$origBody"
            }

            val headCopy = p.head.toMutableList()
            val timeLine = headCopy.getOrNull(p.timeIdx).orEmpty()
            val tm = Regex("^([^\\s]+)\\s*-->\\s*([^\\s]+)(.*)$").find(timeLine)
            var startMs: Long? = null
            var endMs: Long? = null
            var sep = if (isVtt) '.' else ','
            var suffix = ""
            var hasHours = true

            if (tm != null) {
                sep = if (tm.groupValues[1].contains('.')) '.' else ','
                hasHours = tm.groupValues[1].split(':').size >= 3
                startMs = parseCueTimeToMs(tm.groupValues[1])
                endMs = parseCueTimeToMs(tm.groupValues[2])
                suffix = tm.groupValues[3]
                if (startMs != null && endMs != null && shiftMs != 0L) {
                    startMs = (startMs + shiftMs).coerceAtLeast(0L)
                    endMs = (endMs + shiftMs).coerceAtLeast(startMs + 100L)
                }
            }

            outCues.add(OutCue(null, headCopy, p.timeIdx, startMs, endMs, sep, suffix, hasHours, bodyText))
        }

        if (opts.cleanAds) {
            var prev: OutCue? = null
            for (c in outCues) {
                if (c.raw != null || c.startMs == null || c.endMs == null) continue
                val p = prev
                if (p != null && p.endMs != null && p.startMs != null && p.endMs!! > c.startMs!!) {
                    p.endMs = maxOf(p.startMs!! + 100L, c.startMs!! - 10L)
                }
                prev = c
            }
        }

        var seq = 1
        return outCues.joinToString(sub.eol + sub.eol) { c ->
            if (c.raw != null) return@joinToString c.raw
            val head = c.head.toMutableList()
            if (c.startMs != null && c.endMs != null) {
                head[c.timeIdx] = "${formatMsToCueTime(c.startMs!!, c.sep, c.hasHours)} --> ${formatMsToCueTime(c.endMs!!, c.sep, c.hasHours)}${c.suffix}"
            }
            if (!isVtt && opts.cleanAds && head.size >= 2 && Regex("^\\d+$").matches(head[0].trim())) {
                head[0] = (seq++).toString()
            }
            if (c.bodyText.isEmpty()) head.joinToString(sub.eol)
            else (head + c.bodyText.split(Regex("\\r?\\n"))).joinToString(sub.eol)
        }
    }

    /* ================= API TRANSLATION ENGINE ================= */
    data class TranslateConfig(
        val keys: List<String>,
        val model: String,
        val taskMode: String, // "translate" or "polish"
        val notes: String,
        val glossary: String,
        val relaxSafety: Boolean = true,
        val includeAllLines: Boolean = false,
        val useSlidingContext: Boolean = true,
        val cleanAds: Boolean = true
    )

    private data class ContextPair(val src: String, val dst: String)

    private class KeySwitchException(val httpCode: Int, message: String) : Exception(message)
    private class BlockedException(message: String) : Exception(message)
    private class RetryableException(message: String) : Exception(message)

    suspend fun pingKeys(keys: List<String>, rawModel: String): List<String> {
        val logs = mutableListOf<String>()
        keys.forEachIndexed { idx, k ->
            val isOR = isOpenRouterKey(k)
            val provider = if (isOR) "OpenRouter" else "Google Free"
            val model = resolveModelForProvider(rawModel, isOR)
            val t0 = System.currentTimeMillis()
            try {
                val (code, text) = if (isOR) {
                    val body = JSONObject()
                        .put("model", model)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with only: OK")))
                    HttpClient.postJson(
                        "$OR_API/chat/completions",
                        body.toString(),
                        headers = mapOf("Authorization" to "Bearer $k")
                    )
                } else {
                    val body = JSONObject().put(
                        "contents",
                        JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Reply with only: OK"))))
                    )
                    HttpClient.postJson(
                        "$GOOGLE_API/models/${Formatters.urlEncode(model)}:generateContent",
                        body.toString(),
                        headers = mapOf("x-goog-api-key" to k)
                    )
                }
                val ms = System.currentTimeMillis() - t0
                val masked = k.take(10) + "…"
                if (code == 200) {
                    logs.add("✅ Key #${idx + 1} [$provider · $model] ($masked): BERHASIL ($ms ms)")
                } else {
                    logs.add("❌ Key #${idx + 1} [$provider · $model] ($masked): GAGAL — ${HttpClient.buildErrorDetail(code, text)}")
                }
            } catch (e: Exception) {
                logs.add("⚠️ Key #${idx + 1} [$provider] (${k.take(10)}…): ERROR — ${e.message}")
            }
        }
        return logs
    }

    suspend fun fetchAvailableModels(keys: List<String>): List<String> {
        val orKey = keys.firstOrNull { isOpenRouterKey(it) }
        val gKey = keys.firstOrNull { !isOpenRouterKey(it) }
        val result = mutableListOf<String>()

        if (gKey != null) {
            try {
                val raw = HttpClient.getString(
                    "$GOOGLE_API/models?pageSize=200",
                    headers = mapOf("x-goog-api-key" to gKey)
                )
                val arr = JSONObject(raw).optJSONArray("models")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                        if (!methods.contains("generateContent")) continue
                        val name = m.optString("name").removePrefix("models/")
                        if (Regex("^gemini-.*(flash|pro|hydrogen|neon)", RegexOption.IGNORE_CASE).containsMatchIn(name) &&
                            !Regex("vision|image|audio|tts|live", RegexOption.IGNORE_CASE).containsMatchIn(name)
                        ) {
                            result.add(name)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (orKey != null) {
            try {
                val raw = HttpClient.getString(
                    "$OR_API/models",
                    headers = mapOf("Authorization" to "Bearer $orKey")
                )
                val arr = JSONObject(raw).optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                        if (Regex("^(google/gemini-.*(flash|pro)|deepseek/deepseek-(chat|v3)|qwen/qwen.*(72b|max)|x-ai/grok)", RegexOption.IGNORE_CASE).containsMatchIn(id) &&
                            !Regex("vision|image|audio|tts", RegexOption.IGNORE_CASE).containsMatchIn(id)
                        ) {
                            result.add(id)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return (DEFAULT_MODELS + result).distinct()
    }

    private fun buildSystemPrompt(cfg: TranslateConfig, prevCtx: List<ContextPair>): String {
        val lines = if (cfg.taskMode == "polish") {
            mutableListOf(
                "You are an expert Indonesian subtitle editor specializing in fixing machine-translated (MTL) Indonesian subtitles.",
                "Rewrite stiff, literal, or awkward machine-translated lines into natural, idiomatic, spoken Indonesian that flows smoothly on screen.",
                "Infer character relationships and emotional tone from surrounding dialogue to keep pronouns (aku/kamu, saya/Anda, honorifics, or names) consistent across lines.",
                "Keep dialogue concise and easy to read while preserving the exact original meaning, intensity, and nuance. Never censor, sanitize, summarize, omit, or split/merge subtitle entries.",
                "Preserve any formatting tags (such as <i>, </i>, <b>, </b>, <u>, </u>) and explicit line breaks (\\n or \\N) in their natural positions.",
                "Return ONLY a valid JSON array of strings with the exact same length and order as the input array. Never merge or split entries."
            )
        } else {
            mutableListOf(
                "Translate each subtitle entry into natural, fluent, context-aware Indonesian.",
                "Preserve the original meaning, emotions, tone, and character relationships. Use natural everyday Indonesian, not literal translation.",
                "Do not censor, summarize, omit, or invent dialogue.",
                "Preserve any formatting tags (such as <i>, </i>, <b>, </b>, <u>, </u>) and explicit line breaks (\\n or \\N) in their natural positions.",
                "Return ONLY a valid JSON array of strings with the exact same length and order as the input array. Never merge or split entries."
            )
        }
        if (cfg.notes.isNotBlank()) {
            lines.add("Additional Context / Style Notes: ${cfg.notes}")
        }
        if (cfg.glossary.isNotBlank()) {
            lines.add("Mandatory Glossary / Term Mapping (always translate/keep these consistently):\n${cfg.glossary}")
        }
        if (prevCtx.isNotEmpty()) {
            val ctxStr = prevCtx.mapIndexed { i, c ->
                "${i + 1}. \"${c.src.replace("\n", " / ")}\" => \"${c.dst.replace("\n", " / ")}\""
            }.joinToString("\n")
            lines.add("Recent preceding lines (FOR CONTINUITY CONTEXT ONLY — DO NOT include these lines in your output JSON array):\n$ctxStr")
        }
        return lines.joinToString("\n")
    }

    private fun parseJsonStringList(rawText: String): List<String> {
        val cleaned = rawText
            .replace(Regex("^\\s*```(?:json)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*$"), "")
            .trim()
        val first = cleaned.indexOf('[')
        val last = cleaned.lastIndexOf(']')
        val slice = if (first >= 0 && last > first) cleaned.substring(first, last + 1) else cleaned
        val arr = JSONArray(slice)
        return List(arr.length()) { idx -> arr.optString(idx, "") }
    }

    private suspend fun callGoogleFree(
        cfg: TranslateConfig,
        modelOverride: String,
        apiKey: String,
        items: List<String>,
        prevCtx: List<ContextPair>
    ): List<String> {
        val model = resolveModelForProvider(modelOverride, useOpenRouter = false)
        val genCfg = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")))
        // Only pass temperature for older 2.x models; Gemini 3.x+ deprecates sampling parameters
        if (model.startsWith("gemini-2", ignoreCase = true) || model.startsWith("gemini-1", ignoreCase = true)) {
            genCfg.put("temperature", if (cfg.taskMode == "polish") 0.3 else 0.35)
        }

        val inputArr = JSONArray().apply { items.forEach { put(it) } }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", buildSystemPrompt(cfg, prevCtx)))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", inputArr.toString())))))
            .put("generationConfig", genCfg)

        if (cfg.relaxSafety) {
            val safety = JSONArray()
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { cat ->
                safety.put(JSONObject().put("category", cat).put("threshold", "BLOCK_NONE"))
            }
            body.put("safetySettings", safety)
        }

        val (code, respText) = try {
            HttpClient.postJson(
                "$GOOGLE_API/models/${Formatters.urlEncode(model)}:generateContent",
                body.toString(),
                headers = mapOf("x-goog-api-key" to apiKey),
                useLongTimeout = true
            )
        } catch (e: Exception) {
            throw RetryableException("Jaringan terganggu (${e.message})")
        }

        if (code == 429) {
            val detail = HttpClient.buildErrorDetail(code, respText)
            throw KeySwitchException(429, if (detail.contains("limit: 0")) "Kuota 0 pada model $model" else "Limit 429 Kuota/RPM")
        }
        if (code == 400 || code == 403 || code == 404) {
            val msg = HttpClient.buildErrorDetail(code, respText)
            throw KeySwitchException(code, msg)
        }
        if (code >= 500) {
            throw KeySwitchException(code, "Server Google penuh ($code) pada $model")
        }
        if (code != 200) throw Exception(HttpClient.buildErrorDetail(code, respText))

        val root = JSONObject(respText)
        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (blockReason.isNotBlank()) throw BlockedException("Diblokir Google: $blockReason")

        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw BlockedException("Respons kosong dari Google")
        val contentObj = cand.optJSONObject("content")
            ?: throw BlockedException("Diblokir Google (${cand.optString("finishReason")})")
        val parts = contentObj.optJSONArray("parts") ?: JSONArray()
        val combined = buildString {
            for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        return try {
            parseJsonStringList(combined)
        } catch (_: Exception) {
            throw Exception("mismatch")
        }
    }

    private suspend fun callOpenRouter(
        cfg: TranslateConfig,
        modelOverride: String,
        apiKey: String,
        items: List<String>,
        prevCtx: List<ContextPair>
    ): List<String> {
        val model = resolveModelForProvider(modelOverride, useOpenRouter = true)
        val inputArr = JSONArray().apply { items.forEach { put(it) } }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", buildSystemPrompt(cfg, prevCtx)))
            .put(JSONObject().put("role", "user").put("content", inputArr.toString()))

        val body = JSONObject()
            .put("model", model)
            .put("temperature", if (cfg.taskMode == "polish") 0.3 else 0.35)
            .put("messages", messages)
        if (cfg.relaxSafety) {
            body.put("provider", JSONObject().put("allow_fallbacks", true))
        }

        val (code, respText) = try {
            HttpClient.postJson(
                "$OR_API/chat/completions",
                body.toString(),
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "HTTP-Referer" to "https://mypikpak.com",
                    "X-Title" to "TorrentHub AIO"
                ),
                useLongTimeout = true
            )
        } catch (e: Exception) {
            throw RetryableException("Jaringan OpenRouter terganggu (${e.message})")
        }

        if (code == 429 || code == 401 || code == 402 || code == 403) {
            throw KeySwitchException(code, HttpClient.buildErrorDetail(code, respText))
        }
        if (code >= 500) throw RetryableException("Server OpenRouter sibuk ($code)")
        if (code != 200) throw Exception(HttpClient.buildErrorDetail(code, respText))

        val root = JSONObject(respText)
        root.optJSONObject("error")?.let {
            throw RetryableException(it.optString("message", "Error OpenRouter"))
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
        if (content.isBlank()) {
            val reason = choice?.optString("finish_reason").orEmpty()
            if (Regex("content_filter|safety", RegexOption.IGNORE_CASE).containsMatchIn(reason)) {
                throw BlockedException("Diblokir filter: $reason")
            }
            throw RetryableException("Respons kosong ($reason)")
        }
        return try {
            parseJsonStringList(content)
        } catch (_: Exception) {
            throw Exception("mismatch")
        }
    }

    suspend fun processSubtitleJob(
        sub: ParsedSubtitle,
        existingProgress: MutableList<String>?,
        cfg: TranslateConfig,
        limitLines: Int = 0,
        isCancelled: () -> Boolean,
        onProgress: (pct: Int, status: String, currentTr: List<String>, previewPair: String?) -> Unit
    ): List<String> {
        val includeAll = cfg.includeAllLines || cfg.taskMode == "polish"
        val indexes = sub.texts.indices.filter { i ->
            val t = sub.texts[i]
            t.isNotBlank() &&
                !(cfg.cleanAds && isWatermarkLine(t)) &&
                (if (includeAll) LETTER_OR_DIGIT_RE.containsMatchIn(t) else CJK_RE.containsMatchIn(t))
        }

        val tr = existingProgress?.takeIf { it.size == sub.texts.size }?.toMutableList()
            ?: MutableList(sub.texts.size) { "" }

        if (indexes.isEmpty()) {
            onProgress(100, "Tidak ada baris dialog yang perlu diproses.", tr, null)
            return tr
        }

        val remaining = if (limitLines > 0) indexes.take(limitLines) else indexes.filter { tr[it].isBlank() }
        if (remaining.isEmpty()) {
            onProgress(100, "Selesai (${indexes.size}/${indexes.size} baris).", tr, null)
            return tr
        }

        var keyIdx = 0
        var activeModel = cfg.model.ifBlank { DEFAULT_MODEL }
        var failedLines = 0
        var blockStreak = 0
        var doneCount = if (limitLines > 0) 0 else (indexes.size - remaining.size)
        val targetTotal = if (limitLines > 0) remaining.size else indexes.size

        suspend fun translateWithRotation(items: List<String>, prevCtx: List<ContextPair>): List<String> {
            var keySwitches = 0
            var netRetry = 0
            val totalKeys = cfg.keys.size

            while (true) {
                if (isCancelled()) throw Exception("Dibatalkan")
                val key = cfg.keys[keyIdx % totalKeys]
                val useOR = isOpenRouterKey(key)

                try {
                    return if (useOR) {
                        callOpenRouter(cfg, activeModel, key, items, prevCtx)
                    } else {
                        callGoogleFree(cfg, activeModel, key, items, prevCtx)
                    }
                } catch (e: BlockedException) {
                    val firstOr = cfg.keys.indexOfFirst { isOpenRouterKey(it) }
                    if (!useOR && firstOr >= 0) {
                        onProgress(
                            ((doneCount * 100) / targetTotal).coerceIn(0, 99),
                            "Sensor Google -> alihkan batch ke OpenRouter…",
                            tr,
                            null
                        )
                        return callOpenRouter(cfg, activeModel, cfg.keys[firstOr], items, prevCtx)
                    }
                    throw e
                } catch (e: KeySwitchException) {
                    keySwitches++
                    val shortReason = e.message?.take(65) ?: "HTTP ${e.httpCode}"
                    if (totalKeys > 1 && keySwitches < totalKeys) {
                        keyIdx = (keyIdx + 1) % totalKeys
                        onProgress(
                            ((doneCount * 100) / targetTotal).coerceIn(0, 99),
                            "$shortReason -> pindah ke Key #${keyIdx + 1}…",
                            tr,
                            null
                        )
                        delay(450)
                        continue
                    }

                    // All keys failed in a full cycle: if model-level issue (503 busy / 404 / limit: 0), auto-fallback to next free Gemini model!
                    if (!useOR && (e.httpCode == 503 || e.httpCode == 404 || shortReason.contains("Kuota 0"))) {
                        val nextModel = FALLBACK_GOOGLE_CHAIN.firstOrNull {
                            !it.equals(activeModel, ignoreCase = true) &&
                                FALLBACK_GOOGLE_CHAIN.indexOf(it) > FALLBACK_GOOGLE_CHAIN.indexOf(activeModel)
                        }
                        if (nextModel != null) {
                            val oldModel = activeModel
                            activeModel = nextModel
                            keySwitches = 0
                            keyIdx = 0
                            onProgress(
                                ((doneCount * 100) / targetTotal).coerceIn(0, 99),
                                "$oldModel bermasalah ($shortReason) -> otomatis beralih ke $activeModel…",
                                tr,
                                null
                            )
                            delay(600)
                            continue
                        }
                    }

                    if ((e.httpCode == 401 || e.httpCode == 402 || e.httpCode == 403) && keySwitches >= totalKeys) {
                        throw Exception("Key ditolak ($shortReason)")
                    }

                    keySwitches = 0
                    keyIdx = (keyIdx + 1) % totalKeys
                    onProgress(
                        ((doneCount * 100) / targetTotal).coerceIn(0, 99),
                        "Semua key tertahan ($shortReason). Menunggu 10 dtk…",
                        tr,
                        null
                    )
                    delay(10_000)
                } catch (e: RetryableException) {
                    if (netRetry >= 6) throw e
                    netRetry++
                    if (totalKeys > 1) keyIdx = (keyIdx + 1) % totalKeys
                    onProgress(
                        ((doneCount * 100) / targetTotal).coerceIn(0, 99),
                        "${e.message} — mencoba ulang (#$netRetry)…",
                        tr,
                        null
                    )
                    delay(netRetry * 1500L)
                }
            }
        }

        suspend fun translateGroupRecursive(items: List<String>, prevCtx: List<ContextPair>): List<String> {
            if (isCancelled()) throw Exception("Dibatalkan")
            return try {
                val out = translateWithRotation(items, prevCtx)
                if (out.size != items.size) throw Exception("mismatch")
                blockStreak = 0
                out
            } catch (e: Exception) {
                if (e is BlockedException || e.message == "mismatch") {
                    if (items.size == 1) {
                        failedLines++
                        if (e is BlockedException) {
                            blockStreak++
                            if (blockStreak >= 8) throw Exception("Model memblokir berturut-turut: ${e.message}")
                        }
                        listOf("")
                    } else {
                        val mid = items.size / 2
                        val left = translateGroupRecursive(items.subList(0, mid), prevCtx)
                        val right = translateGroupRecursive(items.subList(mid, items.size), emptyList())
                        left + right
                    }
                } else {
                    throw e
                }
            }
        }

        val verb = if (cfg.taskMode == "polish") "Memperbaiki ID" else "Menerjemahkan"
        var pos = 0
        while (pos < remaining.size) {
            if (isCancelled()) throw Exception("Dibatalkan")
            if (pos > 0) {
                val usingOR = isOpenRouterKey(cfg.keys[keyIdx % cfg.keys.size])
                delay(if (usingOR) 400L else 1100L)
            }
            val group = remaining.subList(pos, minOf(pos + CHUNK_SIZE, remaining.size))

            val prevCtx = mutableListOf<ContextPair>()
            if (cfg.useSlidingContext) {
                var back = group.first() - 1
                while (back >= 0 && prevCtx.size < SLIDING_CONTEXT_SIZE) {
                    if (tr[back].isNotBlank() && sub.texts[back].isNotBlank()) {
                        prevCtx.add(0, ContextPair(sub.texts[back], tr[back]))
                    }
                    back--
                }
            }

            val outputs = translateGroupRecursive(group.map { sub.texts[it] }, prevCtx)
            outputs.forEachIndexed { k, text ->
                if (text.isNotBlank()) tr[group[k]] = text.trim()
            }
            doneCount += group.size
            val pct = ((doneCount * 100) / targetTotal).coerceIn(1, 100)
            val lastIdx = group.last()
            val samplePair = "[#${lastIdx + 1}] ${sub.texts[lastIdx].replace("\n", " / ")}\n→ ${tr[lastIdx].ifBlank { "(dilewati)" }.replace("\n", " / ")}"
            val keyLabel = if (isOpenRouterKey(cfg.keys[keyIdx % cfg.keys.size])) "OpenRouter" else "Key #${(keyIdx % cfg.keys.size) + 1} · $activeModel"
            onProgress(
                pct,
                "[$keyLabel] $verb… $doneCount/$targetTotal ($pct%)" + (if (failedLines > 0) " | gagal: $failedLines" else ""),
                tr,
                samplePair
            )
            pos += CHUNK_SIZE
        }

        return tr
    }
}
