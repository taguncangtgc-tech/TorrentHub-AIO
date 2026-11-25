package com.torrenthub.aio.data

import android.content.Context
import android.util.Base64
import com.torrenthub.aio.model.UserscriptItem
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

object UserscriptManager {

    private fun scriptsDir(context: Context): File =
        File(context.filesDir, "userscripts").apply { if (!exists()) mkdirs() }

    private fun indexFile(context: Context): File =
        File(scriptsDir(context), "index.json")

    private fun codeFile(context: Context, id: String): File =
        File(scriptsDir(context), "$id.user.js")

    private fun storageFile(context: Context, id: String): File =
        File(scriptsDir(context), "kv_$id.json")

    fun loadInstalledScripts(context: Context): List<UserscriptItem> {
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = mutableListOf<UserscriptItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val matchesArr = o.optJSONArray("matches")
                val excludesArr = o.optJSONArray("excludes")
                val matches = mutableListOf<String>()
                if (matchesArr != null) {
                    for (j in 0 until matchesArr.length()) matches.add(matchesArr.optString(j))
                }
                if (matches.isEmpty()) matches.add("*://*/*")
                val excludes = mutableListOf<String>()
                if (excludesArr != null) {
                    for (j in 0 until excludesArr.length()) excludes.add(excludesArr.optString(j))
                }
                list.add(
                    UserscriptItem(
                        id = o.optString("id"),
                        name = o.optString("name", "Userscript"),
                        namespace = o.optString("namespace"),
                        version = o.optString("version", "1.0"),
                        description = o.optString("description"),
                        matches = matches,
                        excludes = excludes,
                        runAt = o.optString("runAt", "document-end"),
                        enabled = o.optBoolean("enabled", true),
                        sourceUrl = o.optString("sourceUrl"),
                        codeSize = o.optInt("codeSize", 0)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(context: Context, items: List<UserscriptItem>) {
        val arr = JSONArray()
        items.forEach { it ->
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("namespace", it.namespace)
                    .put("version", it.version)
                    .put("description", it.description)
                    .put("matches", JSONArray(it.matches))
                    .put("excludes", JSONArray(it.excludes))
                    .put("runAt", it.runAt)
                    .put("enabled", it.enabled)
                    .put("sourceUrl", it.sourceUrl)
                    .put("codeSize", it.codeSize)
            )
        }
        indexFile(context).writeText(arr.toString(), Charsets.UTF_8)
    }

    fun parseMetadata(rawCode: String, sourceUrl: String = ""): UserscriptItem {
        val headerMatch = Regex("//\\s*==UserScript==([\\s\\S]*?)//\\s*==/UserScript==").find(rawCode)
        var name = ""
        var namespace = "torrenthub-userscript"
        var version = "1.0"
        var description = ""
        var runAt = "document-end"
        val matches = mutableListOf<String>()
        val excludes = mutableListOf<String>()

        if (headerMatch != null) {
            headerMatch.groupValues[1].lineSequence().forEach { line ->
                val m = Regex("^\\s*//\\s*@([\\w-]+)\\s+(.+?)\\s*$").find(line) ?: return@forEach
                val tag = m.groupValues[1].lowercase(Locale.ROOT)
                val value = m.groupValues[2].trim()
                when (tag) {
                    "name" -> if (name.isEmpty()) name = value
                    "namespace" -> namespace = value
                    "version" -> version = value
                    "description" -> if (description.isEmpty()) description = value
                    "match", "include" -> matches.add(value)
                    "exclude", "exclude-match" -> excludes.add(value)
                    "run-at" -> when (value.lowercase(Locale.ROOT)) {
                        "document-start" -> runAt = "document-start"
                        "document-idle" -> runAt = "document-idle"
                        else -> runAt = "document-end"
                    }
                }
            }
        }

        if (name.isBlank()) {
            name = sourceUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Custom Userscript" }
        }
        if (matches.isEmpty()) matches.add("*://*/*")

        val stableId = "us_" + Formatters.md5Hex("${namespace.lowercase(Locale.ROOT)}::${name.lowercase(Locale.ROOT)}").take(16)
        return UserscriptItem(
            id = stableId,
            name = name,
            namespace = namespace,
            version = version,
            description = description,
            matches = matches,
            excludes = excludes,
            runAt = runAt,
            enabled = true,
            sourceUrl = sourceUrl,
            codeSize = rawCode.toByteArray(Charsets.UTF_8).size
        )
    }

    fun installOrUpdateScript(context: Context, rawCode: String, sourceUrl: String = ""): UserscriptItem {
        val parsed = parseMetadata(rawCode, sourceUrl)
        val current = loadInstalledScripts(context).toMutableList()
        val existingIdx = current.indexOfFirst { it.id == parsed.id }
        val finalItem = if (existingIdx >= 0) {
            val prev = current[existingIdx]
            parsed.copy(enabled = prev.enabled, sourceUrl = sourceUrl.ifBlank { prev.sourceUrl })
        } else {
            parsed
        }
        codeFile(context, finalItem.id).writeText(rawCode, Charsets.UTF_8)
        if (existingIdx >= 0) current[existingIdx] = finalItem else current.add(finalItem)
        saveIndex(context, current)
        return finalItem
    }

    fun setScriptEnabled(context: Context, scriptId: String, enabled: Boolean): List<UserscriptItem> {
        val current = loadInstalledScripts(context).map {
            if (it.id == scriptId) it.copy(enabled = enabled) else it
        }
        saveIndex(context, current)
        return current
    }

    fun deleteScript(context: Context, scriptId: String): List<UserscriptItem> {
        val current = loadInstalledScripts(context).filterNot { it.id == scriptId }
        codeFile(context, scriptId).delete()
        storageFile(context, scriptId).delete()
        saveIndex(context, current)
        return current
    }

    fun readScriptCode(context: Context, scriptId: String): String {
        val f = codeFile(context, scriptId)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    fun loadScriptStorageJson(context: Context, scriptId: String): String {
        val f = storageFile(context, scriptId)
        if (!f.exists()) return "{}"
        return try {
            val txt = f.readText(Charsets.UTF_8)
            JSONObject(txt).toString()
        } catch (_: Exception) {
            "{}"
        }
    }

    @Synchronized
    fun setScriptValue(context: Context, scriptId: String, key: String, rawJsonValue: String) {
        val f = storageFile(context, scriptId)
        val obj = try {
            if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val parsedVal: Any = try {
            org.json.JSONTokener(rawJsonValue).nextValue()
        } catch (_: Exception) {
            rawJsonValue
        }
        obj.put(key, parsedVal)
        f.writeText(obj.toString(), Charsets.UTF_8)
    }

    @Synchronized
    fun deleteScriptValue(context: Context, scriptId: String, key: String) {
        val f = storageFile(context, scriptId)
        if (!f.exists()) return
        try {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            obj.remove(key)
            f.writeText(obj.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun patternMatchesUrl(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p == "*://*/*" || p == "*" || p == "<all_urls>") return true
        if (p.startsWith("/") && p.endsWith("/") && p.length > 2) {
            return try {
                Regex(p.substring(1, p.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(url)
            } catch (_: Exception) {
                false
            }
        }
        val regexStr = buildString {
            append("^")
            var i = 0
            if (p.startsWith("*://")) {
                append("https?://")
                i = 4
            } else if (p.startsWith("http*://")) {
                append("https?://")
                i = 8
            }
            // Handle '*.domain.com' matching both root domain and subdomains in @match
            val rest = p.substring(i)
            val normalizedRest = if (rest.startsWith("*.")) {
                append("(?:[^/]+\\.)?")
                rest.substring(2)
            } else {
                rest
            }
            for (ch in normalizedRest) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    '.', '+', '^', '$', '(', ')', '[', ']', '{', '}', '|', '\\' -> {
                        append('\\')
                        append(ch)
                    }
                    else -> append(ch)
                }
            }
            append("$")
        }
        return try {
            Regex(regexStr, RegexOption.IGNORE_CASE).matches(url)
        } catch (_: Exception) {
            false
        }
    }

    fun isScriptMatchingUrl(script: UserscriptItem, url: String): Boolean {
        if (!script.enabled) return false
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return false
        }
        if (script.excludes.any { patternMatchesUrl(it, url) }) return false
        return script.matches.any { patternMatchesUrl(it, url) }
    }

    /**
     * Executes GM_xmlhttpRequest via OkHttp + DoH without CORS restrictions.
     */
    suspend fun executeGmXhr(optionsJson: String, pageUrl: String): String = withContext(Dispatchers.IO) {
        val opts = JSONObject(optionsJson)
        val method = opts.optString("method", "GET").uppercase(Locale.ROOT)
        val rawUrl = opts.optString("url")
        val resolvedUrl = try {
            java.net.URL(java.net.URL(pageUrl.ifBlank { "https://www.google.com" }), rawUrl).toString()
        } catch (_: Exception) {
            rawUrl
        }
        val responseType = opts.optString("responseType", "").lowercase(Locale.ROOT)
        val timeoutMs = opts.optLong("timeout", 25000L).coerceIn(2000L, 90000L)
        val dataStr = if (opts.has("data") && !opts.isNull("data")) opts.optString("data") else null
        val hdrObj = opts.optJSONObject("headers")

        val client = HttpClient.okHttp.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val reqBuilder = Request.Builder().url(resolvedUrl)
        var contentType: String? = null
        if (hdrObj != null) {
            val keys = hdrObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = hdrObj.optString(k)
                if (k.equals("Content-Type", ignoreCase = true)) contentType = v
                reqBuilder.header(k, v)
            }
        }

        val body = when {
            method == "GET" || method == "HEAD" -> null
            dataStr != null -> dataStr.toRequestBody((contentType ?: "text/plain; charset=utf-8").toMediaTypeOrNull())
            else -> ByteArray(0).toRequestBody(null)
        }
        reqBuilder.method(method, body)

        try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val bytes = if (method == "HEAD") ByteArray(0) else (resp.body?.bytes() ?: ByteArray(0))
                val headersRaw = buildString {
                    for (i in 0 until resp.headers.size) {
                        append(resp.headers.name(i)).append(": ").append(resp.headers.value(i)).append("\r\n")
                    }
                }
                val isBinary = responseType == "arraybuffer" || responseType == "blob"
                val out = JSONObject()
                    .put("ok", true)
                    .put("status", resp.code)
                    .put("statusText", resp.message)
                    .put("finalUrl", resp.request.url.toString())
                    .put("responseHeaders", headersRaw)
                    .put("mimeType", resp.header("Content-Type") ?: "application/octet-stream")
                    .put("responseType", responseType)

                if (isBinary) {
                    out.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    out.put("responseText", "")
                } else {
                    val txt = String(bytes, Charsets.UTF_8)
                    out.put("responseText", txt)
                }
                out.toString()
            }
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: "Network error")
                .toString()
        }
    }

    /**
     * Builds the unified document-start JavaScript bundle containing:
     * 1. Selection & Copy listener + 1-click Page Title/Code detector for TorrentHub
     * 2. Full Tampermonkey GM_* runtime & all enabled userscripts (matched per URL and run-at timing)
     */
    fun buildDocumentBootstrapScript(context: Context, scripts: List<UserscriptItem>): String {
        val enabledScripts = scripts.filter { it.enabled }
        val sb = StringBuilder(16384)

        sb.append(
            """
            (function() {
                if (window !== window.top) return;
                if (window.__thRuntimeBootedUrl === location.href && window.__thRuntimeBooted) return;
                window.__thRuntimeBooted = true;
                window.__thRuntimeBootedUrl = location.href;

                /* === 1. SELECTION, COPY & PAGE CODE/TITLE DETECTOR === */
                if (!window.__thSelectionHooked) {
                    window.__thSelectionHooked = true;
                    let selTimer = null;
                    let lastSentSel = '';

                    const notifySel = (raw, isCopy) => {
                        const clean = String(raw || '').replace(/\s+/g, ' ').trim();
                        if (clean.length < 2 || clean.length > 200) return;
                        if (!isCopy && clean === lastSentSel) return;
                        lastSentSel = clean;
                        try {
                            if (window.__thGmBridge && window.__thGmBridge.onTextSelected) {
                                window.__thGmBridge.onTextSelected(clean, !!isCopy);
                            }
                        } catch (_) {}
                    };

                    document.addEventListener('selectionchange', () => {
                        clearTimeout(selTimer);
                        selTimer = setTimeout(() => {
                            const txt = window.getSelection ? String(window.getSelection()) : '';
                            if (txt && txt.trim().length >= 2) notifySel(txt, false);
                        }, 260);
                    }, { passive: true });

                    document.addEventListener('copy', () => {
                        const txt = window.getSelection ? String(window.getSelection()) : '';
                        if (txt && txt.trim().length >= 2) notifySel(txt, true);
                    }, { passive: true });

                    window.__thDetectPageCandidates = function() {
                        const out = [];
                        const addCand = (val) => {
                            const s = String(val || '').replace(/\s+/g, ' ').trim();
                            if (s.length >= 2 && s.length <= 140 && !out.includes(s)) out.push(s);
                        };
                        const sources = [
                            location.pathname,
                            document.querySelector('h1')?.innerText,
                            document.querySelector('meta[property="og:title"]')?.getAttribute('content'),
                            document.title,
                            document.querySelector('h2')?.innerText
                        ];
                        const codeRe = /\b(?:FC2[-_\s]?PPV[-_\s]?\d{5,9}|[A-Za-z]{2,8}[-_\s]?\d{2,6})\b/gi;
                        const excludedPrefix = /^(FULLHD|H264|H265|X264|X265|1080P|720P|2160P|HTML5|HTTP|HTTPS|UTF8|IMDB|TMDB|PAGE)$/i;

                        for (const src of sources) {
                            if (!src) continue;
                            let m;
                            while ((m = codeRe.exec(src)) !== null) {
                                const raw = m[0].toUpperCase().replace(/[_\s]+/g, '-');
                                const pref = raw.split('-')[0];
                                if (!excludedPrefix.test(pref)) addCand(raw);
                            }
                        }
                        for (const src of [
                            document.querySelector('h1')?.innerText,
                            document.querySelector('meta[property="og:title"]')?.getAttribute('content'),
                            document.title
                        ]) {
                            if (!src) continue;
                            const cleanedTitle = String(src)
                                .replace(/\s*[-|–—·•]\s*(Watch|Free|Online|IMDb|JavDB|MissAV|MyDramaList|YouTube|Google|Wikipedia).*$/i, '')
                                .replace(/^\(\d+\)\s*/, '')
                                .trim();
                            if (cleanedTitle) addCand(cleanedTitle);
                        }
                        return JSON.stringify(out.slice(0, 8));
                    };
                }

                /* === 2. TAMPERMONKEY GM_* RUNTIME === */
                if (!window.__thGmState) {
                    const xhrCallbacks = new Map();
                    const menuCallbacks = new Map();
                    let seq = 1;

                    window.__thGmOnXhrDone = function(reqId, payload) {
                        const cb = xhrCallbacks.get(reqId);
                        if (!cb) return;
                        xhrCallbacks.delete(reqId);
                        try {
                            const data = typeof payload === 'string' ? JSON.parse(payload) : payload;
                            if (!data || !data.ok) {
                                if (typeof cb.onerror === 'function') cb.onerror(new Error((data && data.error) || 'Network error'));
                                return;
                            }
                            let respObj = data.responseText;
                            if ((data.responseType === 'arraybuffer' || data.responseType === 'blob') && data.base64) {
                                const bin = atob(data.base64);
                                const arr = new Uint8Array(bin.length);
                                for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                                respObj = data.responseType === 'blob'
                                    ? new Blob([arr.buffer], { type: data.mimeType || 'application/octet-stream' })
                                    : arr.buffer;
                            } else if (data.responseType === 'json') {
                                try { respObj = JSON.parse(data.responseText); } catch (_) { respObj = null; }
                            }
                            if (typeof cb.onload === 'function') {
                                cb.onload({
                                    status: data.status,
                                    statusText: data.statusText || '',
                                    finalUrl: data.finalUrl || '',
                                    responseHeaders: data.responseHeaders || '',
                                    responseText: data.responseText || '',
                                    response: respObj,
                                    readyState: 4
                                });
                            }
                        } catch (err) {
                            if (typeof cb.onerror === 'function') cb.onerror(err);
                        }
                    };

                    window.__thGmInvokeMenu = function(cmdId) {
                        const fn = menuCallbacks.get(cmdId);
                        if (typeof fn === 'function') fn();
                    };

                    window.__thGmState = {
                        nextId: () => String(seq++),
                        xhrCallbacks,
                        menuCallbacks
                    };
                }

                function __thPatternMatch(pattern, url) {
                    const p = String(pattern || '').trim();
                    if (p === '*://*/*' || p === '*' || p === '<all_urls>') return true;
                    if (p.startsWith('/') && p.endsWith('/') && p.length > 2) {
                        try { return new RegExp(p.slice(1, -1), 'i').test(url); } catch (_) { return false; }
                    }
                    let rest = p;
                    let prefix = '^';
                    if (rest.startsWith('*://')) { prefix += 'https?://'; rest = rest.slice(4); }
                    else if (rest.startsWith('http*://')) { prefix += 'https?://'; rest = rest.slice(8); }
                    if (rest.startsWith('*.')) { prefix += '(?:[^/]+\\.)?'; rest = rest.slice(2); }
                    const escaped = rest.replace(/[.+^${'$'}()|[\]\\]/g, '\\${'$'}&').replace(/\*/g, '.*').replace(/\?/g, '.');
                    try { return new RegExp(prefix + escaped + '${'$'}', 'i').test(url); } catch (_) { return false; }
                }

                function __thCreateGmEnv(meta, initialStore) {
                    const storeObj = Object.assign({}, initialStore || {});
                    const listeners = new Map();
                    let listenerSeq = 1;

                    const GM_info = {
                        scriptHandler: 'TorrentHub Tampermonkey',
                        version: '1.4.0',
                        script: meta
                    };

                    const GM_getValue = (k, defVal) => {
                        return Object.prototype.hasOwnProperty.call(storeObj, k) ? storeObj[k] : defVal;
                    };

                    const GM_setValue = (k, val) => {
                        const oldVal = storeObj[k];
                        storeObj[k] = val;
                        try {
                            if (window.__thGmBridge) {
                                window.__thGmBridge.setValue(meta.id, String(k), JSON.stringify(val));
                            }
                        } catch (_) {}
                        listeners.forEach(item => {
                            if (item.key === k && typeof item.fn === 'function') {
                                try { item.fn(k, oldVal, val, false); } catch (_) {}
                            }
                        });
                    };

                    const GM_deleteValue = (k) => {
                        delete storeObj[k];
                        try {
                            if (window.__thGmBridge) window.__thGmBridge.deleteValue(meta.id, String(k));
                        } catch (_) {}
                    };

                    const GM_listValues = () => Object.keys(storeObj);

                    const GM_addValueChangeListener = (key, fn) => {
                        const id = listenerSeq++;
                        listeners.set(id, { key, fn });
                        return id;
                    };

                    const GM_removeValueChangeListener = (id) => {
                        listeners.delete(id);
                    };

                    const GM_setClipboard = (text) => {
                        try {
                            if (window.__thGmBridge) window.__thGmBridge.setClipboard(String(text || ''));
                        } catch (_) {}
                    };

                    const GM_addStyle = (css) => {
                        const st = document.createElement('style');
                        st.textContent = String(css || '');
                        (document.head || document.documentElement).appendChild(st);
                        return st;
                    };

                    const GM_openInTab = (url) => {
                        try {
                            if (window.__thGmBridge) window.__thGmBridge.openUrlInBrowser(String(url || ''));
                        } catch (_) {}
                    };

                    const GM_download = (arg1, arg2) => {
                        const url = typeof arg1 === 'string' ? arg1 : (arg1 && arg1.url) || '';
                        const name = typeof arg1 === 'string' ? (arg2 || 'download') : (arg1 && arg1.name) || 'download';
                        if (!url) return;
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = name;
                        (document.body || document.documentElement).appendChild(a);
                        a.click();
                        a.remove();
                    };

                    const GM_registerMenuCommand = (caption, fn) => {
                        const cmdId = meta.id + '_' + window.__thGmState.nextId();
                        window.__thGmState.menuCallbacks.set(cmdId, fn);
                        try {
                            if (window.__thGmBridge) {
                                window.__thGmBridge.registerMenuCommand(meta.id, meta.name, cmdId, String(caption || 'Menu'));
                            }
                        } catch (_) {}
                        return cmdId;
                    };

                    const GM_xmlhttpRequest = (details) => {
                        if (!details || !details.url) return { abort: () => {} };
                        const reqId = 'xhr_' + window.__thGmState.nextId();
                        window.__thGmState.xhrCallbacks.set(reqId, details);
                        const payload = JSON.stringify({
                            method: details.method || 'GET',
                            url: details.url,
                            headers: details.headers || {},
                            data: details.data !== undefined ? details.data : null,
                            timeout: details.timeout || 25000,
                            responseType: details.responseType || ''
                        });
                        try {
                            if (window.__thGmBridge) window.__thGmBridge.xmlHttpRequest(reqId, payload, location.href);
                        } catch (err) {
                            window.__thGmState.xhrCallbacks.delete(reqId);
                            if (typeof details.onerror === 'function') details.onerror(err);
                        }
                        return {
                            abort: () => {
                                window.__thGmState.xhrCallbacks.delete(reqId);
                                if (typeof details.onabort === 'function') details.onabort();
                            }
                        };
                    };

                    const GM = {
                        info: GM_info,
                        getValue: async (k, d) => GM_getValue(k, d),
                        setValue: async (k, v) => GM_setValue(k, v),
                        deleteValue: async (k) => GM_deleteValue(k),
                        listValues: async () => GM_listValues(),
                        setClipboard: async (t) => GM_setClipboard(t),
                        addStyle: async (c) => GM_addStyle(c),
                        openInTab: async (u) => GM_openInTab(u),
                        registerMenuCommand: async (c, f) => GM_registerMenuCommand(c, f),
                        xmlHttpRequest: GM_xmlhttpRequest
                    };

                    return {
                        GM_info, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues,
                        GM_addValueChangeListener, GM_removeValueChangeListener,
                        GM_xmlhttpRequest, GM_setClipboard, GM_addStyle, GM_openInTab,
                        GM_download, GM_registerMenuCommand, GM
                    };
                }
            """.trimIndent()
        ).append("\n")

        for (script in enabledScripts) {
            val rawCode = readScriptCode(context, script.id)
            if (rawCode.isBlank()) continue
            val storageJson = loadScriptStorageJson(context, script.id)
            val metaJson = JSONObject()
                .put("id", script.id)
                .put("name", script.name)
                .put("namespace", script.namespace)
                .put("version", script.version)
                .put("description", script.description)
                .put("matches", JSONArray(script.matches))
                .put("excludes", JSONArray(script.excludes))
                .put("runAt", script.runAt)
                .toString()

            sb.append("\n;(function() {\n")
            sb.append("  const __meta = ").append(metaJson).append(";\n")
            sb.append("  const __url = location.href;\n")
            sb.append("  if (!/^https?:/i.test(__url)) return;\n")
            sb.append("  if (__meta.excludes.some(p => __thPatternMatch(p, __url))) return;\n")
            sb.append("  if (!__meta.matches.some(p => __thPatternMatch(p, __url))) return;\n")
            sb.append("  const __run = () => {\n")
            sb.append("    try {\n")
            sb.append("      if (window.__thGmBridge) window.__thGmBridge.onScriptActivated(__meta.id, __meta.name);\n")
            sb.append("      const __env = __thCreateGmEnv(__meta, ").append(storageJson).append(");\n")
            sb.append("      (function(GM_info, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_addValueChangeListener, GM_removeValueChangeListener, GM_xmlhttpRequest, GM_setClipboard, GM_addStyle, GM_openInTab, GM_download, GM_registerMenuCommand, GM, unsafeWindow) {\n")
            sb.append(rawCode).append("\n")
            sb.append("      })(__env.GM_info, __env.GM_getValue, __env.GM_setValue, __env.GM_deleteValue, __env.GM_listValues, __env.GM_addValueChangeListener, __env.GM_removeValueChangeListener, __env.GM_xmlhttpRequest, __env.GM_setClipboard, __env.GM_addStyle, __env.GM_openInTab, __env.GM_download, __env.GM_registerMenuCommand, __env.GM, window);\n")
            sb.append("    } catch (__err) {\n")
            sb.append("      console.error('[Userscript:' + __meta.name + ']', __err);\n")
            sb.append("    }\n")
            sb.append("  };\n")
            when (script.runAt) {
                "document-start" -> sb.append("  __run();\n")
                "document-idle" -> sb.append("  if (document.readyState === 'complete') setTimeout(__run, 100); else window.addEventListener('load', () => setTimeout(__run, 100), { once: true });\n")
                else -> sb.append("  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', __run, { once: true }); else __run();\n")
            }
            sb.append("})();\n")
        }

        sb.append("\n})();")
        return sb.toString()
    }
}
