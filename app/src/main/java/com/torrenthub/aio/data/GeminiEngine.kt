package com.torrenthub.aio.data

import com.torrenthub.aio.model.TranslationProvider
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

object GeminiEngine {
    const val DEFAULT_GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
    const val DEFAULT_GOOGLE_GTX_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1"
    const val DEFAULT_DEEPL_FREE_ENDPOINT = "https://api-free.deepl.com/v2"
    const val DEFAULT_DEEPL_PRO_ENDPOINT = "https://api.deepl.com/v2"

    private const val GOOGLE_API = DEFAULT_GEMINI_ENDPOINT
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

    val OPENAI_PRESET_MODELS = listOf(
        "gpt-4o-mini",
        "gpt-4.1-mini",
        "gpt-4.1-nano",
        "gpt-4o",
        "deepseek-chat",
        "google/gemini-2.5-flash"
    )

    val OPENAI_PRESET_ENDPOINTS = listOf(
        "OpenAI" to "https://api.openai.com/v1",
        "OpenRouter" to "https://openrouter.ai/api/v1",
        "DeepSeek" to "https://api.deepseek.com/v1"
    )

    val DEEPL_MODELS = listOf(
        "prefer_quality_optimized",
        "latency_optimized"
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

    private val BUILTIN_NOTES_TRANSLATE = mapOf(
        "id" to DEFAULT_NOTES_TRANSLATE,
        "en" to "Translate dialogue into natural, idiomatic, spoken everyday English that fits the scene context—avoid stiff word-for-word translation. Match register, contractions, and tone to character relationships, and preserve emotional nuance, interjections, and nicknames without censoring or truncating meaning.",
        "zh" to "将对白翻译为自然流畅、口语化且符合剧情语境的现代简体中文日常用语，避免生硬的逐字直译。根据人物关系合理处理称谓与语气词，完整保留情感色彩、简短感叹及特色称呼，不要删减、概括或审查原意。",
        "ja" to "セリフを場面の文脈に合った自然で感情豊かな日常会話の日本語に翻訳してください。直訳調を避け、登場人物の関係性に応じて一人称・二人称・敬語やタメ口・語尾のニュアンスを自然に調整し、検閲や省略をせず元の意味と感情を忠実に保ってください。",
        "ko" to "대사를 장면의 맥락에 맞는 자연스럽고 생동감 있는 일상 구어체 한국어로 번역하세요. 딱딱한 직역을 피하고 등장인물 간의 관계에 맞춰 반말/존댓말과 호칭을 일관되게 조절하며, 검열이나 생략 없이 원문의 감정선과 뉘앙스를 그대로 살려주세요."
    )

    private val BUILTIN_NOTES_POLISH = mapOf(
        "id" to DEFAULT_NOTES_POLISH,
        "en" to "Rewrite these machine-translated English subtitle lines so they read like natural, fluent, emotional spoken dialogue on screen. Fix awkward or overly literal phrasing while keeping character voice consistent. Never summarize, omit, or censor dialogue, and preserve original line breaks and tags.",
        "zh" to "润色并修复这些机器翻译的简体中文字幕，使其读起来像自然流畅、富有情感的影视口语对白。消除生硬直译的语病，统一人物称谓与口吻，切勿删减、合并、概括或审查台词，保留原有的换行和格式标签。",
        "ja" to "機械翻訳された不自然な日本語字幕を、画面で読みやすく自然で感情のこもった話し言葉のセリフに推敲・修正してください。登場人物の関係性に合った口調や人称を一貫させ、要約・省略・検閲をせず元の改行とタグを維持してください。",
        "ko" to "기계번역으로 어색해진 한국어 자막을 화면에서 읽기 편하고 자연스러운 일상 구어체 대사로 교정해 주세요. 인물 관계에 맞는 말투(반말/존댓말)와 호칭을 일관성 있게 다듬되, 대사를 요약·생략·검열하지 말고 줄바꿈과 태그를 그대로 유지하세요."
    )

    fun defaultNotesTranslate(targetLang: String): String {
        val key = targetLang.lowercase(Locale.ROOT)
        BUILTIN_NOTES_TRANSLATE[key]?.let { return it }
        val langName = SubtitleTargetLanguage.fromCode(targetLang).englishPromptName
        return "Translate dialogue into natural, idiomatic, spoken everyday $langName that fits the scene context—avoid stiff word-for-word translation. Match register and tone to character relationships, and preserve emotional nuance, interjections, and nicknames without censoring or truncating meaning."
    }

    fun defaultNotesPolish(targetLang: String): String {
        val key = targetLang.lowercase(Locale.ROOT)
        BUILTIN_NOTES_POLISH[key]?.let { return it }
        val langName = SubtitleTargetLanguage.fromCode(targetLang).englishPromptName
        return "Rewrite these machine-translated $langName subtitle lines so they read like natural, fluent, emotional spoken dialogue on screen. Fix awkward or overly literal phrasing while keeping character voice consistent. Never summarize, omit, or censor dialogue, and preserve original line breaks and tags."
    }

    fun isBuiltInDefaultTranslateNotes(notes: String): Boolean {
        val clean = notes.trim()
        return clean.isEmpty() ||
            BUILTIN_NOTES_TRANSLATE.values.any { it.trim() == clean } ||
            SubtitleTargetLanguage.entries.any { defaultNotesTranslate(it.code).trim() == clean }
    }

    fun isBuiltInDefaultPolishNotes(notes: String): Boolean {
        val clean = notes.trim()
        return clean.isEmpty() ||
            BUILTIN_NOTES_POLISH.values.any { it.trim() == clean } ||
            SubtitleTargetLanguage.entries.any { defaultNotesPolish(it.code).trim() == clean }
    }

    fun targetLanguageEnglishName(targetLang: String): String =
        SubtitleTargetLanguage.fromCode(targetLang).englishPromptName

    private val CJK_RE = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uAC00-\\uD7AF]")
    private val JA_KANA_RE = Regex("[\\u3040-\\u30FF]")
    private val KO_HANGUL_RE = Regex("[\\uAC00-\\uD7AF]")
    private val ZH_HAN_RE = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]")
    private val LETTER_OR_DIGIT_RE = Regex("[\\p{L}\\p{N}]")
    private val ID_WORDS_RE = Regex(
        "\\b(yang|dan|tidak|nggak|gak|aku|kamu|saya|anda|ini|itu|dengan|untuk|sudah|belum|jangan|bisa|kenapa|apa|kalau|tapi|mereka|kita|kami|dia|mau|lagi|saja|aja|banget|dong|sih|kok|akan|dari|dalam|harus|ingin|tolong)\\b",
        RegexOption.IGNORE_CASE
    )
    private val EN_WORDS_RE = Regex(
        "\\b(the|and|you|that|this|with|have|what|don't|can't|i'm|it's|they|from|your|about|know|just|want|here|there|would|could|should|please|really)\\b",
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

    fun detectLanguageMode(texts: List<String>, targetLang: String = "id"): String {
        val nonEmpty = texts.filter { it.isNotBlank() && !isWatermarkLine(it) }
        if (nonEmpty.isEmpty()) return "translate"
        val step = maxOf(1, nonEmpty.size / 40)
        val sample = mutableListOf<String>()
        var i = 0
        while (i < nonEmpty.size && sample.size < 40) {
            sample.add(nonEmpty[i])
            i += step
        }

        var jaLines = 0
        var koLines = 0
        var zhLines = 0
        var idLines = 0
        var totalIdHits = 0
        var enLines = 0
        var totalEnHits = 0

        for (line in sample) {
            when {
                JA_KANA_RE.containsMatchIn(line) -> jaLines++
                KO_HANGUL_RE.containsMatchIn(line) -> koLines++
                ZH_HAN_RE.containsMatchIn(line) -> zhLines++
                else -> {
                    val idHits = ID_WORDS_RE.findAll(line).count()
                    val enHits = EN_WORDS_RE.findAll(line).count()
                    if (idHits > 0) {
                        idLines++
                        totalIdHits += idHits
                    }
                    if (enHits > 0) {
                        enLines++
                        totalEnHits += enHits
                    }
                }
            }
        }

        val threshold = maxOf(3, (sample.size * 0.28).toInt())
        val isAlreadyTarget = when (targetLang.lowercase(Locale.ROOT)) {
            "ja" -> jaLines >= threshold
            "ko" -> koLines >= threshold
            "zh" -> zhLines >= threshold && jaLines < 2 && koLines < 2
            "en" -> (jaLines + koLines + zhLines) < 3 && enLines >= threshold && totalEnHits >= 5 && totalEnHits > totalIdHits
            else -> (jaLines + koLines + zhLines) < 3 && idLines >= threshold && totalIdHits >= 5
        }
        return if (isAlreadyTarget) "polish" else "translate"
    }

    fun isWatermarkLine(text: String): Boolean = WATERMARK_AD_RE.containsMatchIn(text)

    fun formatOutputFileName(origName: String, nameMode: String, targetLang: String = "id"): String {
        val ext = origName.substringAfterLast('.', "srt").lowercase(Locale.ROOT)
        val base = origName.replace(Regex("\\.[^.]+$"), "")
        val langSuffix = targetLang.lowercase(Locale.ROOT).ifBlank { "id" }
        return when (nameMode) {
            "code-upper" -> {
                val code = Formatters.extractUpperCodeFromFileName(origName)
                if (code.isNotBlank()) "$code.$ext" else "${base.uppercase(Locale.ROOT)}.$ext"
            }
            "upper-full" -> "${base.uppercase(Locale.ROOT)}.$ext"
            ".id", ".lang" -> "$base.$langSuffix.$ext"
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
        val provider: TranslationProvider = TranslationProvider.GEMINI,
        val keys: List<String> = emptyList(),
        val model: String = DEFAULT_MODEL,
        val customEndpoint: String = "",
        val deeplKey: String = "",
        val deeplModel: String = "prefer_quality_optimized",
        val openaiKey: String = "",
        val openaiModel: String = "gpt-4o-mini",
        val taskMode: String, // "translate" or "polish"
        val targetLang: String = "id",
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

    private fun googleGtxLangCode(targetLang: String): String =
        SubtitleTargetLanguage.fromCode(targetLang).googleGtxCode

    private fun deeplTargetLangCode(targetLang: String): String =
        SubtitleTargetLanguage.fromCode(targetLang).deeplCode

    fun resolveDeepLEndpoint(rawEndpoint: String, apiKey: String): String {
        val clean = rawEndpoint.trim().trimEnd('/')
        if (clean.isNotBlank()) return clean
        return if (apiKey.trim().endsWith(":fx", ignoreCase = true)) {
            DEFAULT_DEEPL_FREE_ENDPOINT
        } else {
            DEFAULT_DEEPL_PRO_ENDPOINT
        }
    }

    suspend fun pingProvider(
        provider: TranslationProvider,
        keys: List<String>,
        rawModel: String,
        customEndpoint: String,
        targetLang: String = "id"
    ): List<String> {
        return when (provider) {
            TranslationProvider.GOOGLE_FREE -> {
                val ep = customEndpoint.trim().ifBlank { DEFAULT_GOOGLE_GTX_ENDPOINT }
                val tl = googleGtxLangCode(targetLang)
                val t0 = System.currentTimeMillis()
                try {
                    val (code, body) = HttpClient.postForm(
                        ep,
                        mapOf(
                            "client" to "gtx",
                            "sl" to "auto",
                            "tl" to tl,
                            "dt" to "t",
                            "q" to "Hello world"
                        )
                    )
                    val ms = System.currentTimeMillis() - t0
                    if (code == 200 && body.trimStart().startsWith("[")) {
                        val sample = JSONArray(body).optJSONArray(0)?.optJSONArray(0)?.optString(0).orEmpty()
                        listOf("✅ Google Translate Free [$tl]: OK ($ms ms) → \"$sample\"")
                    } else {
                        listOf("❌ Google Translate Free: FAIL — ${HttpClient.buildErrorDetail(code, body)}")
                    }
                } catch (e: Exception) {
                    listOf("⚠️ Google Translate Free: ERROR — ${e.message}")
                }
            }
            TranslationProvider.GEMINI -> pingKeys(keys, rawModel, customEndpoint)
            TranslationProvider.DEEPL -> {
                val logs = mutableListOf<String>()
                keys.forEachIndexed { idx, k ->
                    val ep = resolveDeepLEndpoint(customEndpoint, k)
                    val url = if (ep.endsWith("/translate")) ep else "$ep/translate"
                    val t0 = System.currentTimeMillis()
                    try {
                        val payload = JSONObject()
                            .put("text", JSONArray().put("Hello world"))
                            .put("target_lang", deeplTargetLangCode(targetLang))
                        val headers = if (k.isNotBlank()) mapOf("Authorization" to "DeepL-Auth-Key $k") else emptyMap()
                        val (code, text) = HttpClient.postJson(url, payload.toString(), headers = headers)
                        val ms = System.currentTimeMillis() - t0
                        val masked = if (k.isNotBlank()) k.take(8) + "…" else "Custom"
                        if (code == 200) {
                            val out = JSONObject(text).optJSONArray("translations")?.optJSONObject(0)?.optString("text").orEmpty()
                            logs.add("✅ DeepL #${idx + 1} [$ep] ($masked): OK ($ms ms) → \"$out\"")
                        } else {
                            logs.add("❌ DeepL #${idx + 1} [$ep] ($masked): FAIL — ${HttpClient.buildErrorDetail(code, text)}")
                        }
                    } catch (e: Exception) {
                        logs.add("⚠️ DeepL #${idx + 1}: ERROR — ${e.message}")
                    }
                }
                logs
            }
            TranslationProvider.OPENAI -> {
                val logs = mutableListOf<String>()
                val ep = customEndpoint.trim().trimEnd('/').ifBlank { DEFAULT_OPENAI_ENDPOINT }
                val chatUrl = if (ep.endsWith("/chat/completions")) ep else "$ep/chat/completions"
                val model = rawModel.ifBlank { "gpt-4o-mini" }.trim()
                keys.forEachIndexed { idx, k ->
                    val t0 = System.currentTimeMillis()
                    try {
                        val body = JSONObject()
                            .put("model", model)
                            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with only: OK")))
                        val headers = buildMap {
                            if (k.isNotBlank()) put("Authorization", "Bearer $k")
                            if (ep.contains("openrouter.ai", ignoreCase = true)) {
                                put("HTTP-Referer", "https://mypikpak.com")
                                put("X-Title", "TorrentHub AIO")
                            }
                        }
                        val (code, text) = HttpClient.postJson(chatUrl, body.toString(), headers = headers)
                        val ms = System.currentTimeMillis() - t0
                        val masked = k.take(10) + "…"
                        if (code == 200) {
                            logs.add("✅ OpenAI/Compatible #${idx + 1} [$model] ($masked): OK ($ms ms)")
                        } else {
                            logs.add("❌ OpenAI/Compatible #${idx + 1} [$model] ($masked): FAIL — ${HttpClient.buildErrorDetail(code, text)}")
                        }
                    } catch (e: Exception) {
                        logs.add("⚠️ OpenAI/Compatible #${idx + 1}: ERROR — ${e.message}")
                    }
                }
                logs
            }
        }
    }

    suspend fun pingKeys(
        keys: List<String>,
        rawModel: String,
        customGeminiEndpoint: String = ""
    ): List<String> {
        val geminiBase = customGeminiEndpoint.trim().trimEnd('/').ifBlank { GOOGLE_API }
        val logs = mutableListOf<String>()
        keys.forEachIndexed { idx, k ->
            val isOR = isOpenRouterKey(k)
            val provider = if (isOR) "OpenRouter" else "Gemini API"
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
                        "$geminiBase/models/${Formatters.urlEncode(model)}:generateContent",
                        body.toString(),
                        headers = mapOf("x-goog-api-key" to k)
                    )
                }
                val ms = System.currentTimeMillis() - t0
                val masked = k.take(10) + "…"
                if (code == 200) {
                    logs.add("✅ Key #${idx + 1} [$provider · $model] ($masked): OK ($ms ms)")
                } else {
                    logs.add("❌ Key #${idx + 1} [$provider · $model] ($masked): FAIL — ${HttpClient.buildErrorDetail(code, text)}")
                }
            } catch (e: Exception) {
                logs.add("⚠️ Key #${idx + 1} [$provider] (${k.take(10)}…): ERROR — ${e.message}")
            }
        }
        return logs
    }

    suspend fun fetchAvailableModels(
        keys: List<String>,
        provider: TranslationProvider = TranslationProvider.GEMINI,
        customEndpoint: String = ""
    ): List<String> {
        if (provider == TranslationProvider.OPENAI) {
            val ep = customEndpoint.trim().trimEnd('/').ifBlank { DEFAULT_OPENAI_ENDPOINT }
            val modelsUrl = "${ep.removeSuffix("/chat/completions")}/models"
            val key = keys.firstOrNull().orEmpty()
            val list = mutableListOf<String>()
            if (key.isNotBlank()) {
                try {
                    val raw = HttpClient.getString(
                        modelsUrl,
                        headers = mapOf("Authorization" to "Bearer $key")
                    )
                    val arr = JSONObject(raw).optJSONArray("data")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                            if (id.isNotBlank() && !Regex("embedding|whisper|tts|dall-e|moderation|audio|realtime", RegexOption.IGNORE_CASE).containsMatchIn(id)) {
                                list.add(id)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            return (OPENAI_PRESET_MODELS + list).distinct()
        }

        val geminiBase = customEndpoint.trim().trimEnd('/').ifBlank { GOOGLE_API }
        val orKey = keys.firstOrNull { isOpenRouterKey(it) }
        val gKey = keys.firstOrNull { !isOpenRouterKey(it) }
        val result = mutableListOf<String>()

        if (gKey != null) {
            try {
                val raw = HttpClient.getString(
                    "$geminiBase/models?pageSize=200",
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
        val langName = targetLanguageEnglishName(cfg.targetLang)
        val langRule = when (cfg.targetLang.lowercase(Locale.ROOT)) {
            "en" -> "Use natural, idiomatic everyday spoken English with appropriate tone, contractions, and register matching character relationships."
            "zh" -> "Use natural, idiomatic everyday spoken Simplified Chinese (简体中文) with appropriate tone, particles, and forms of address matching character relationships."
            "ja" -> "Use natural, idiomatic spoken Japanese (日本語) with context-appropriate speech style, politeness levels (keigo/casual), pronouns, and sentence-ending particles."
            "ko" -> "Use natural, idiomatic spoken Korean (한국어) with context-appropriate speech levels (banmal/jondaetmal), pronouns, and honorifics matching character relationships."
            else -> "Infer character relationships and emotional tone to keep Indonesian pronouns (aku/kamu, saya/Anda, honorifics, or names) natural and consistent."
        }

        val lines = if (cfg.taskMode == "polish") {
            mutableListOf(
                "You are an expert $langName subtitle editor specializing in fixing machine-translated (MTL) subtitles.",
                "Rewrite stiff, literal, or awkward machine-translated lines into natural, idiomatic, spoken $langName that flows smoothly on screen.",
                langRule,
                "Keep dialogue concise and easy to read while preserving the exact original meaning, intensity, and nuance. Never censor, sanitize, summarize, omit, or split/merge subtitle entries.",
                "Preserve any formatting tags (such as <i>, </i>, <b>, </b>, <u>, </u>) and explicit line breaks (\\n or \\N) in their natural positions.",
                "Return ONLY a valid JSON array of strings in $langName with the exact same length and order as the input array. Never merge or split entries."
            )
        } else {
            mutableListOf(
                "Translate each subtitle entry into natural, fluent, context-aware $langName.",
                "Preserve the original meaning, emotions, tone, and character relationships. Do not translate stiffly word-for-word.",
                langRule,
                "Do not censor, summarize, omit, or invent dialogue.",
                "Preserve any formatting tags (such as <i>, </i>, <b>, </b>, <u>, </u>) and explicit line breaks (\\n or \\N) in their natural positions.",
                "Return ONLY a valid JSON array of strings in $langName with the exact same length and order as the input array. Never merge or split entries."
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

        val geminiBase = cfg.customEndpoint.trim().trimEnd('/').ifBlank { GOOGLE_API }
        val (code, respText) = try {
            HttpClient.postJson(
                "$geminiBase/models/${Formatters.urlEncode(model)}:generateContent",
                body.toString(),
                headers = mapOf("x-goog-api-key" to apiKey),
                useLongTimeout = true
            )
        } catch (e: Exception) {
            throw RetryableException("Network error (${e.message})")
        }

        if (code == 429) {
            val detail = HttpClient.buildErrorDetail(code, respText)
            throw KeySwitchException(429, if (detail.contains("limit: 0")) "Quota 0 on $model" else "HTTP 429 Quota/RPM")
        }
        if (code == 400 || code == 403 || code == 404) {
            val msg = HttpClient.buildErrorDetail(code, respText)
            throw KeySwitchException(code, msg)
        }
        if (code >= 500) {
            throw KeySwitchException(code, "Google server busy ($code) on $model")
        }
        if (code != 200) throw Exception(HttpClient.buildErrorDetail(code, respText))

        val root = JSONObject(respText)
        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (blockReason.isNotBlank()) throw BlockedException("Blocked by Google: $blockReason")

        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw BlockedException("Empty response from Google")
        val contentObj = cand.optJSONObject("content")
            ?: throw BlockedException("Blocked by Google (${cand.optString("finishReason")})")
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
            throw RetryableException("OpenRouter network error (${e.message})")
        }

        if (code == 429 || code == 401 || code == 402 || code == 403) {
            throw KeySwitchException(code, HttpClient.buildErrorDetail(code, respText))
        }
        if (code >= 500) throw RetryableException("OpenRouter server busy ($code)")
        if (code != 200) throw Exception(HttpClient.buildErrorDetail(code, respText))

        val root = JSONObject(respText)
        root.optJSONObject("error")?.let {
            throw RetryableException(it.optString("message", "OpenRouter error"))
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
        if (content.isBlank()) {
            val reason = choice?.optString("finish_reason").orEmpty()
            if (Regex("content_filter|safety", RegexOption.IGNORE_CASE).containsMatchIn(reason)) {
                throw BlockedException("Blocked by filter: $reason")
            }
            throw RetryableException("Empty response ($reason)")
        }
        return try {
            parseJsonStringList(content)
        } catch (_: Exception) {
            throw Exception("mismatch")
        }
    }

    private suspend fun callGoogleTranslateFreeSingle(endpoint: String, tl: String, text: String): String {
        val (code, respText) = try {
            HttpClient.postForm(
                endpoint,
                mapOf(
                    "client" to "gtx",
                    "sl" to "auto",
                    "tl" to tl,
                    "dt" to "t",
                    "q" to text
                ),
                useLongTimeout = true
            )
        } catch (e: Exception) {
            throw RetryableException("Google Translate network error (${e.message})")
        }
        if (code == 429 || code >= 500) {
            throw RetryableException("Google Translate busy (HTTP $code)")
        }
        if (code != 200) throw Exception(HttpClient.buildErrorDetail(code, respText))
        val outer = JSONArray(respText).optJSONArray(0) ?: return ""
        return buildString {
            for (i in 0 until outer.length()) {
                append(outer.optJSONArray(i)?.optString(0).orEmpty())
            }
        }
    }

    private suspend fun callGoogleTranslateFree(
        cfg: TranslateConfig,
        items: List<String>
    ): List<String> {
        if (items.isEmpty()) return emptyList()
        val endpoint = cfg.customEndpoint.trim().ifBlank { DEFAULT_GOOGLE_GTX_ENDPOINT }
        val tl = googleGtxLangCode(cfg.targetLang)
        val lineBreakToken = " ⏎ "
        val sanitized = items.map { it.replace(Regex("\\r?\\n"), lineBreakToken).trim() }
        val joined = sanitized.joinToString("\n")

        val combinedTranslated = callGoogleTranslateFreeSingle(endpoint, tl, joined)
        val splitLines = combinedTranslated.split("\n")
        if (splitLines.size == items.size) {
            return splitLines.map { it.replace(Regex("\\s*⏎\\s*"), "\n").trim() }
        }

        // Fallback to parallel per-line translation if Google merged a newline
        val sem = Semaphore(4)
        return coroutineScope {
            items.map { item ->
                async {
                    sem.withPermit {
                        callGoogleTranslateFreeSingle(endpoint, tl, item).trim()
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun callDeepLApi(
        cfg: TranslateConfig,
        apiKey: String,
        items: List<String>
    ): List<String> {
        if (items.isEmpty()) return emptyList()
        val ep = resolveDeepLEndpoint(cfg.customEndpoint, apiKey)
        val url = if (ep.endsWith("/translate")) ep else "$ep/translate"
        val targetCode = deeplTargetLangCode(cfg.targetLang)
        val headers = if (apiKey.isNotBlank()) mapOf("Authorization" to "DeepL-Auth-Key $apiKey") else emptyMap()

        suspend fun executeCall(includeModelType: Boolean): Pair<Int, String> {
            val payload = JSONObject()
                .put("text", JSONArray().apply { items.forEach { put(it) } })
                .put("target_lang", targetCode)
            if (includeModelType && cfg.deeplModel.isNotBlank()) {
                payload.put("model_type", cfg.deeplModel)
            }
            return try {
                HttpClient.postJson(url, payload.toString(), headers = headers, useLongTimeout = true)
            } catch (e: Exception) {
                throw RetryableException("DeepL network error (${e.message})")
            }
        }

        var (code, respText) = executeCall(includeModelType = true)
        if (code == 400 && respText.contains("model_type", ignoreCase = true)) {
            val retry = executeCall(includeModelType = false)
            code = retry.first
            respText = retry.second
        }

        if (code == 401 || code == 403 || code == 456 || code == 429) {
            throw KeySwitchException(code, HttpClient.buildErrorDetail(code, respText))
        }
        if (code >= 500) throw RetryableException("DeepL server busy ($code)")
        if (code != 200) throw Exception(HttpClient.buildErrorDetail(code, respText))

        val arr = JSONObject(respText).optJSONArray("translations")
            ?: throw RetryableException("Empty DeepL response")
        if (arr.length() != items.size) throw Exception("mismatch")
        return List(arr.length()) { idx -> arr.optJSONObject(idx)?.optString("text").orEmpty() }
    }

    private suspend fun callOpenAiCompatible(
        cfg: TranslateConfig,
        modelOverride: String,
        apiKey: String,
        items: List<String>,
        prevCtx: List<ContextPair>
    ): List<String> {
        val ep = cfg.customEndpoint.trim().trimEnd('/').ifBlank { DEFAULT_OPENAI_ENDPOINT }
        val url = if (ep.endsWith("/chat/completions")) ep else "$ep/chat/completions"
        val model = modelOverride.ifBlank { cfg.openaiModel }.ifBlank { "gpt-4o-mini" }.trim()
        val inputArr = JSONArray().apply { items.forEach { put(it) } }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", buildSystemPrompt(cfg, prevCtx)))
            .put(JSONObject().put("role", "user").put("content", inputArr.toString()))

        val body = JSONObject()
            .put("model", model)
            .put("temperature", if (cfg.taskMode == "polish") 0.3 else 0.35)
            .put("messages", messages)

        val headers = buildMap {
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            if (ep.contains("openrouter.ai", ignoreCase = true)) {
                put("HTTP-Referer", "https://mypikpak.com")
                put("X-Title", "TorrentHub AIO")
            }
        }

        val (code, respText) = try {
            HttpClient.postJson(url, body.toString(), headers = headers, useLongTimeout = true)
        } catch (e: Exception) {
            throw RetryableException("OpenAI API network error (${e.message})")
        }

        if (code == 401 || code == 402 || code == 403 || code == 429) {
            throw KeySwitchException(code, HttpClient.buildErrorDetail(code, respText))
        }
        if (code >= 500) throw RetryableException("Server busy ($code)")
        if (code != 200) throw Exception(HttpClient.buildErrorDetail(code, respText))

        val root = JSONObject(respText)
        root.optJSONObject("error")?.let {
            throw RetryableException(it.optString("message", "API error"))
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
        if (content.isBlank()) {
            val reason = choice?.optString("finish_reason").orEmpty()
            if (Regex("content_filter|safety", RegexOption.IGNORE_CASE).containsMatchIn(reason)) {
                throw BlockedException("Blocked by filter: $reason")
            }
            throw RetryableException("Empty response ($reason)")
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
        val hasCjkDialogue = sub.texts.count { CJK_RE.containsMatchIn(it) } >= 3
        val includeAll = cfg.includeAllLines || cfg.taskMode == "polish" || !hasCjkDialogue
        val indexes = sub.texts.indices.filter { i ->
            val t = sub.texts[i]
            t.isNotBlank() &&
                !(cfg.cleanAds && isWatermarkLine(t)) &&
                (if (includeAll) LETTER_OR_DIGIT_RE.containsMatchIn(t) else CJK_RE.containsMatchIn(t))
        }

        val tr = existingProgress?.takeIf { it.size == sub.texts.size }?.toMutableList()
            ?: MutableList(sub.texts.size) { "" }

        if (indexes.isEmpty()) {
            onProgress(100, "0/0", tr, null)
            return tr
        }

        val remaining = if (limitLines > 0) indexes.take(limitLines) else indexes.filter { tr[it].isBlank() }
        if (remaining.isEmpty()) {
            onProgress(100, "${indexes.size}/${indexes.size}", tr, null)
            return tr
        }

        var keyIdx = 0
        var activeModel = when (cfg.provider) {
            TranslationProvider.OPENAI -> cfg.openaiModel.ifBlank { cfg.model }.ifBlank { "gpt-4o-mini" }
            TranslationProvider.DEEPL -> cfg.deeplModel.ifBlank { "prefer_quality_optimized" }
            TranslationProvider.GOOGLE_FREE -> "gtx"
            TranslationProvider.GEMINI -> cfg.model.ifBlank { DEFAULT_MODEL }
        }
        var failedLines = 0
        var blockStreak = 0
        var doneCount = if (limitLines > 0) 0 else (indexes.size - remaining.size)
        val targetTotal = if (limitLines > 0) remaining.size else indexes.size
        val effectiveKeys = when (cfg.provider) {
            TranslationProvider.GOOGLE_FREE -> listOf("google-free")
            TranslationProvider.DEEPL -> parseKeys(cfg.deeplKey).ifEmpty { cfg.keys }.ifEmpty { listOf("") }
            TranslationProvider.OPENAI -> parseKeys(cfg.openaiKey).ifEmpty { cfg.keys }.ifEmpty { listOf("") }
            TranslationProvider.GEMINI -> cfg.keys
        }

        suspend fun translateWithRotation(items: List<String>, prevCtx: List<ContextPair>): List<String> {
            var keySwitches = 0
            var netRetry = 0
            val totalKeys = effectiveKeys.size.coerceAtLeast(1)

            while (true) {
                if (isCancelled()) throw Exception("Cancelled")
                val key = effectiveKeys.getOrElse(keyIdx % totalKeys) { "" }
                val useOR = cfg.provider == TranslationProvider.GEMINI && isOpenRouterKey(key)

                try {
                    return when (cfg.provider) {
                        TranslationProvider.GOOGLE_FREE -> callGoogleTranslateFree(cfg, items)
                        TranslationProvider.DEEPL -> callDeepLApi(cfg, key, items)
                        TranslationProvider.OPENAI -> callOpenAiCompatible(cfg, activeModel, key, items, prevCtx)
                        TranslationProvider.GEMINI -> {
                            if (useOR) callOpenRouter(cfg, activeModel, key, items, prevCtx)
                            else callGoogleFree(cfg, activeModel, key, items, prevCtx)
                        }
                    }
                } catch (e: BlockedException) {
                    val firstOr = effectiveKeys.indexOfFirst { isOpenRouterKey(it) }
                    if (cfg.provider == TranslationProvider.GEMINI && !useOR && firstOr >= 0) {
                        onProgress(
                            ((doneCount * 100) / targetTotal).coerceIn(0, 99),
                            "Google safety filter -> OpenRouter…",
                            tr,
                            null
                        )
                        return callOpenRouter(cfg, activeModel, effectiveKeys[firstOr], items, prevCtx)
                    }
                    throw e
                } catch (e: KeySwitchException) {
                    keySwitches++
                    val shortReason = e.message?.take(65) ?: "HTTP ${e.httpCode}"
                    if (totalKeys > 1 && keySwitches < totalKeys) {
                        keyIdx = (keyIdx + 1) % totalKeys
                        onProgress(
                            ((doneCount * 100) / targetTotal).coerceIn(0, 99),
                            "$shortReason -> Key #${keyIdx + 1}…",
                            tr,
                            null
                        )
                        delay(450)
                        continue
                    }

                    if (cfg.provider == TranslationProvider.GEMINI && !useOR &&
                        (e.httpCode == 503 || e.httpCode == 404 || shortReason.contains("Quota 0"))
                    ) {
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
                                "$oldModel ($shortReason) -> $activeModel…",
                                tr,
                                null
                            )
                            delay(600)
                            continue
                        }
                    }

                    if ((e.httpCode == 401 || e.httpCode == 402 || e.httpCode == 403 || e.httpCode == 456) && keySwitches >= totalKeys) {
                        throw Exception("API key rejected ($shortReason)")
                    }

                    keySwitches = 0
                    keyIdx = (keyIdx + 1) % totalKeys
                    onProgress(
                        ((doneCount * 100) / targetTotal).coerceIn(0, 99),
                        "$shortReason — waiting 10s…",
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
                        "${e.message} — retry #$netRetry…",
                        tr,
                        null
                    )
                    delay(netRetry * 1500L)
                }
            }
        }

        suspend fun translateGroupRecursive(items: List<String>, prevCtx: List<ContextPair>): List<String> {
            if (isCancelled()) throw Exception("Cancelled")
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
                            if (blockStreak >= 8) throw Exception("Repeated safety block: ${e.message}")
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

        val batchSize = when (cfg.provider) {
            TranslationProvider.GOOGLE_FREE -> 25
            TranslationProvider.DEEPL -> 40
            TranslationProvider.OPENAI -> 45
            TranslationProvider.GEMINI -> CHUNK_SIZE
        }
        val langTag = cfg.targetLang.uppercase(Locale.ROOT)
        var pos = 0
        while (pos < remaining.size) {
            if (isCancelled()) throw Exception("Cancelled")
            if (pos > 0) {
                val waitMs = when (cfg.provider) {
                    TranslationProvider.GOOGLE_FREE -> 250L
                    TranslationProvider.DEEPL -> 300L
                    TranslationProvider.OPENAI -> 350L
                    TranslationProvider.GEMINI -> {
                        val curK = effectiveKeys.getOrElse(keyIdx % effectiveKeys.size.coerceAtLeast(1)) { "" }
                        if (isOpenRouterKey(curK)) 400L else 1100L
                    }
                }
                delay(waitMs)
            }
            val group = remaining.subList(pos, minOf(pos + batchSize, remaining.size))

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
            val samplePair = "[#${lastIdx + 1}] ${sub.texts[lastIdx].replace("\n", " / ")}\n→ ${tr[lastIdx].ifBlank { "(—)" }.replace("\n", " / ")}"
            val keyLabel = when (cfg.provider) {
                TranslationProvider.GOOGLE_FREE -> "Google Free"
                TranslationProvider.DEEPL -> "DeepL"
                TranslationProvider.OPENAI -> "OpenAI · $activeModel"
                TranslationProvider.GEMINI -> {
                    val curK = effectiveKeys.getOrElse(keyIdx % effectiveKeys.size.coerceAtLeast(1)) { "" }
                    if (isOpenRouterKey(curK)) "OpenRouter" else "Gemini #${(keyIdx % effectiveKeys.size.coerceAtLeast(1)) + 1} · $activeModel"
                }
            }
            onProgress(
                pct,
                "[$keyLabel → $langTag] $doneCount/$targetTotal ($pct%)" + (if (failedLines > 0) " | err: $failedLines" else ""),
                tr,
                samplePair
            )
            pos += batchSize
        }

        return tr
    }
}
