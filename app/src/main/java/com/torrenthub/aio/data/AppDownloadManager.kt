package com.torrenthub.aio.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.FileProvider
import com.torrenthub.aio.model.DownloadCategory
import com.torrenthub.aio.model.DownloadRecord
import com.torrenthub.aio.model.DownloadState
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import com.torrenthub.aio.util.NotificationHelper
import com.torrenthub.aio.util.SubtitleTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AppDownloadManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsRepository.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val records = mutableStateListOf<DownloadRecord>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val downloadHttpClient by lazy {
        HttpClient.okHttp.newBuilder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    init {
        loadHistory()
    }

    private fun downloadsDir(): File {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        if (!base.exists()) base.mkdirs()
        return base
    }

    private fun allocateUniqueFile(dir: File, desiredName: String): File {
        val clean = sanitizeFileName(desiredName)
        val candidate = File(dir, clean)
        if (!candidate.exists()) return candidate
        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        for (i in 1..999) {
            val f = File(dir, "$stem ($i)$ext")
            if (!f.exists()) return f
        }
        return File(dir, "${System.currentTimeMillis()}_$clean")
    }

    private fun sanitizeFileName(raw: String): String {
        val trimmed = raw.trim().replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
        return trimmed.ifBlank { "download_${System.currentTimeMillis()}.bin" }.take(180)
    }

    fun guessCleanFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        if (!contentDisposition.isNullOrBlank()) {
            // RFC 5987 UTF-8 filename*=UTF-8''...
            Regex("filename\\*\\s*=\\s*UTF-8''([^;\\r\\n]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.get(1)?.let { encoded ->
                    val decoded = try { URLDecoder.decode(encoded.trim().trim('"'), "UTF-8") } catch (_: Exception) { "" }
                    if (decoded.isNotBlank()) return sanitizeFileName(decoded)
                }
            Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.get(1)?.let { quoted ->
                    if (quoted.isNotBlank()) return sanitizeFileName(quoted)
                }
            Regex("filename\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.get(1)?.let { plain ->
                    val clean = plain.trim().trim('"')
                    if (clean.isNotBlank()) return sanitizeFileName(clean)
                }
        }
        val guessed = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (_: Exception) {
            ""
        }
        return sanitizeFileName(guessed.ifBlank { "download_${System.currentTimeMillis()}.bin" })
    }

    fun resolveMimeType(fileName: String, fallbackMime: String = ""): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        when (ext) {
            "srt", "vtt", "ass", "ssa" -> return "text/plain"
            "torrent" -> return "application/x-bittorrent"
            "apk" -> return "application/vnd.android.package-archive"
            "mp4", "m4v" -> return "video/mp4"
            "mkv" -> return "video/x-matroska"
            "zip" -> return "application/zip"
        }
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return fallbackMime.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: "application/octet-stream"
    }

    private fun loadHistory() {
        val raw = settings.downloadHistoryJson ?: return
        try {
            val arr = JSONArray(raw)
            val loaded = mutableListOf<DownloadRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val st = runCatching { DownloadState.valueOf(o.optString("state")) }
                    .getOrDefault(DownloadState.FAILED)
                val normalizedState = if (st == DownloadState.DOWNLOADING || st == DownloadState.QUEUED) {
                    DownloadState.FAILED
                } else st
                val cat = runCatching { DownloadCategory.valueOf(o.optString("category")) }
                    .getOrDefault(DownloadCategory.BROWSER)
                loaded.add(
                    DownloadRecord(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        fileName = o.optString("fileName", "file"),
                        url = o.optString("url"),
                        mimeType = o.optString("mimeType", "application/octet-stream"),
                        category = cat,
                        state = normalizedState,
                        downloadedBytes = o.optLong("downloadedBytes", 0L),
                        totalBytes = o.optLong("totalBytes", 0L),
                        speedBytesPerSec = 0L,
                        localPath = o.optString("localPath"),
                        contentUri = o.optString("contentUri"),
                        referer = o.optString("referer"),
                        subtitleTargetLang = o.optString("subtitleTargetLang"),
                        errorMessage = if (st == DownloadState.DOWNLOADING || st == DownloadState.QUEUED) "Interrupted" else o.optString("errorMessage"),
                        createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis())
                    )
                )
            }
            records.clear()
            records.addAll(loaded)
        } catch (_: Exception) {}
    }

    private fun persistHistory() {
        val snapshot = records.take(120)
        val arr = JSONArray()
        for (r in snapshot) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("fileName", r.fileName)
                    .put("url", r.url)
                    .put("mimeType", r.mimeType)
                    .put("category", r.category.name)
                    .put("state", r.state.name)
                    .put("downloadedBytes", r.downloadedBytes)
                    .put("totalBytes", r.totalBytes)
                    .put("localPath", r.localPath)
                    .put("contentUri", r.contentUri)
                    .put("referer", r.referer)
                    .put("subtitleTargetLang", r.subtitleTargetLang)
                    .put("errorMessage", r.errorMessage)
                    .put("createdAtMs", r.createdAtMs)
            )
        }
        settings.setDownloadHistoryJson(arr.toString())
    }

    private fun updateRecord(id: String, persist: Boolean = false, transform: (DownloadRecord) -> DownloadRecord) {
        mainScope.launch {
            val idx = records.indexOfFirst { it.id == id }
            if (idx >= 0) {
                records[idx] = transform(records[idx])
                if (persist) persistHistory()
            }
        }
    }

    private fun exportToPublicDownloadsIfSupported(sourceFile: File, displayName: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val resolver = appContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TorrentHub")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return ""
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                }
                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                uri.toString()
            } catch (_: Exception) {
                ""
            }
        }
        return ""
    }

    fun enqueueBrowserDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        referer: String = "",
        onComplete: ((DownloadRecord) -> Unit)? = null,
        onError: ((DownloadRecord, String) -> Unit)? = null
    ): DownloadRecord {
        val initialName = guessCleanFileName(url, contentDisposition, mimeType)
        val resolvedMime = resolveMimeType(initialName, mimeType.orEmpty())
        val cat = when {
            initialName.endsWith(".torrent", ignoreCase = true) ||
                resolvedMime == "application/x-bittorrent" -> DownloadCategory.TORRENT
            Regex("\\.(srt|ass|ssa|vtt)$", RegexOption.IGNORE_CASE).containsMatchIn(initialName) -> DownloadCategory.SUBTITLE
            else -> DownloadCategory.BROWSER
        }
        val rec = DownloadRecord(
            id = UUID.randomUUID().toString(),
            fileName = initialName,
            url = url,
            mimeType = resolvedMime,
            category = cat,
            state = DownloadState.QUEUED,
            totalBytes = contentLength.coerceAtLeast(0L),
            referer = referer
        )
        mainScope.launch {
            records.add(0, rec)
            persistHistory()
        }
        startBrowserJob(rec, userAgent, onComplete, onError)
        return rec
    }

    private fun startBrowserJob(
        rec: DownloadRecord,
        userAgent: String? = null,
        onComplete: ((DownloadRecord) -> Unit)? = null,
        onError: ((DownloadRecord, String) -> Unit)? = null
    ) {
        val job = scope.launch {
            updateRecord(rec.id) { it.copy(state = DownloadState.DOWNLOADING, errorMessage = "") }
            NotificationHelper.showDownloadProgress(appContext, rec.id, rec.fileName, 0L, rec.totalBytes, 0L)

            try {
                // Handle data: URI downloads directly
                if (rec.url.startsWith("data:", ignoreCase = true)) {
                    val commaIdx = rec.url.indexOf(',')
                    if (commaIdx < 0) throw IllegalArgumentException("Malformed data URI")
                    val meta = rec.url.substring(5, commaIdx)
                    val payload = rec.url.substring(commaIdx + 1)
                    val bytes = if (meta.contains(";base64", ignoreCase = true)) {
                        Base64.decode(payload, Base64.DEFAULT)
                    } else {
                        URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
                    }
                    val targetFile = allocateUniqueFile(downloadsDir(), rec.fileName)
                    targetFile.writeBytes(bytes)
                    val publicUri = exportToPublicDownloadsIfSupported(targetFile, targetFile.name, rec.mimeType)
                    val doneRec = rec.copy(
                        fileName = targetFile.name,
                        state = DownloadState.COMPLETED,
                        downloadedBytes = bytes.size.toLong(),
                        totalBytes = bytes.size.toLong(),
                        speedBytesPerSec = 0L,
                        localPath = targetFile.absolutePath,
                        contentUri = publicUri
                    )
                    withContext(Dispatchers.Main) {
                        val idx = records.indexOfFirst { it.id == rec.id }
                        if (idx >= 0) records[idx] = doneRec
                        persistHistory()
                    }
                    NotificationHelper.showDownloadFinished(
                        appContext,
                        rec.id,
                        doneRec.fileName,
                        true,
                        Formatters.formatBytes(doneRec.totalBytes)
                    )
                    onComplete?.invoke(doneRec)
                    return@launch
                }

                val reqBuilder = Request.Builder().url(rec.url).get()
                reqBuilder.header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: HttpClient.DEFAULT_UA)
                if (rec.referer.isNotBlank()) {
                    reqBuilder.header("Referer", rec.referer)
                }
                val cookie = try { CookieManager.getInstance().getCookie(rec.url) } catch (_: Exception) { null }
                if (!cookie.isNullOrBlank()) {
                    reqBuilder.header("Cookie", cookie)
                }

                downloadHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val cdHeader = resp.header("Content-Disposition")
                    val ctHeader = resp.body?.contentType()?.toString() ?: resp.header("Content-Type")
                    val finalFileName = if (!cdHeader.isNullOrBlank()) {
                        guessCleanFileName(rec.url, cdHeader, ctHeader)
                    } else {
                        rec.fileName
                    }
                    val finalMime = resolveMimeType(finalFileName, ctHeader ?: rec.mimeType)
                    val totalLen = (resp.body?.contentLength()?.takeIf { it > 0L } ?: rec.totalBytes).coerceAtLeast(0L)
                    val targetFile = allocateUniqueFile(downloadsDir(), finalFileName)

                    val bodyStream = resp.body?.byteStream()
                        ?: throw IllegalStateException("Empty response stream")

                    var downloaded = 0L
                    var lastTickMs = System.currentTimeMillis()
                    var bytesSinceTick = 0L

                    FileOutputStream(targetFile).use { fos ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = bodyStream.read(buffer)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                            downloaded += read
                            bytesSinceTick += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastTickMs
                            if (elapsed >= 320L) {
                                val speed = (bytesSinceTick * 1000L) / elapsed.coerceAtLeast(1L)
                                lastTickMs = now
                                bytesSinceTick = 0L
                                updateRecord(rec.id, persist = false) {
                                    it.copy(
                                        fileName = targetFile.name,
                                        mimeType = finalMime,
                                        state = DownloadState.DOWNLOADING,
                                        downloadedBytes = downloaded,
                                        totalBytes = totalLen,
                                        speedBytesPerSec = speed
                                    )
                                }
                                NotificationHelper.showDownloadProgress(
                                    appContext,
                                    rec.id,
                                    targetFile.name,
                                    downloaded,
                                    totalLen,
                                    speed
                                )
                            }
                        }
                        fos.flush()
                    }

                    val publicUri = exportToPublicDownloadsIfSupported(targetFile, targetFile.name, finalMime)
                    val finalTotal = if (totalLen > 0L) totalLen else downloaded
                    val completedRec = rec.copy(
                        fileName = targetFile.name,
                        mimeType = finalMime,
                        state = DownloadState.COMPLETED,
                        downloadedBytes = downloaded,
                        totalBytes = finalTotal,
                        speedBytesPerSec = 0L,
                        localPath = targetFile.absolutePath,
                        contentUri = publicUri,
                        errorMessage = ""
                    )
                    withContext(Dispatchers.Main) {
                        val idx = records.indexOfFirst { it.id == rec.id }
                        if (idx >= 0) records[idx] = completedRec
                        persistHistory()
                    }
                    NotificationHelper.showDownloadFinished(
                        appContext,
                        rec.id,
                        completedRec.fileName,
                        true,
                        Formatters.formatBytes(downloaded)
                    )
                    onComplete?.invoke(completedRec)
                }
            } catch (ce: CancellationException) {
                updateRecord(rec.id, persist = true) {
                    it.copy(state = DownloadState.CANCELLED, speedBytesPerSec = 0L)
                }
                NotificationHelper.cancelDownloadNotification(appContext, rec.id)
                throw ce
            } catch (e: Exception) {
                val msg = e.message ?: "Download error"
                val failedSnap = rec.copy(state = DownloadState.FAILED, speedBytesPerSec = 0L, errorMessage = msg)
                withContext(Dispatchers.Main) {
                    val idx = records.indexOfFirst { it.id == rec.id }
                    if (idx >= 0) {
                        records[idx] = records[idx].copy(
                            state = DownloadState.FAILED,
                            speedBytesPerSec = 0L,
                            errorMessage = msg
                        )
                    }
                    persistHistory()
                }
                NotificationHelper.showDownloadFinished(appContext, rec.id, rec.fileName, false, msg)
                onError?.invoke(failedSnap, msg)
            } finally {
                activeJobs.remove(rec.id)
            }
        }
        activeJobs[rec.id] = job
    }

    fun enqueueSubtitleDownload(
        fileName: String,
        subtitleUrl: String,
        targetLangCode: String,
        preloadedContentUtf8: String? = null,
        onComplete: ((DownloadRecord, String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): DownloadRecord {
        val cleanName = sanitizeFileName(
            if (Regex("\\.(srt|ass|ssa|vtt)$", RegexOption.IGNORE_CASE).containsMatchIn(fileName)) fileName
            else "$fileName.srt"
        )
        val rec = DownloadRecord(
            id = UUID.randomUUID().toString(),
            fileName = cleanName,
            url = subtitleUrl,
            mimeType = "text/plain",
            category = DownloadCategory.SUBTITLE,
            state = DownloadState.QUEUED,
            subtitleTargetLang = targetLangCode
        )
        mainScope.launch {
            records.add(0, rec)
            persistHistory()
        }
        startSubtitleJob(rec, preloadedContentUtf8, onComplete, onError)
        return rec
    }

    private fun startSubtitleJob(
        rec: DownloadRecord,
        preloadedContentUtf8: String? = null,
        onComplete: ((DownloadRecord, String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val job = scope.launch {
            updateRecord(rec.id) { it.copy(state = DownloadState.DOWNLOADING, errorMessage = "") }
            NotificationHelper.showDownloadProgress(appContext, rec.id, rec.fileName, 0L, 0L, 0L)

            try {
                val utf8Text = if (!preloadedContentUtf8.isNullOrBlank()) {
                    preloadedContentUtf8
                } else {
                    SubtitleRepository.fetchSubtitleText(rec.url, rec.subtitleTargetLang.ifBlank { "id" })
                }
                if (utf8Text.isBlank()) {
                    throw IllegalStateException("Subtitle content is empty")
                }
                val outBytes = SubtitleTools.normalizeToUtf8Bytes(
                    utf8Text,
                    includeBom = utf8Text.startsWith("\uFEFF")
                )
                val targetFile = allocateUniqueFile(downloadsDir(), rec.fileName)
                targetFile.writeBytes(outBytes)
                val publicUri = exportToPublicDownloadsIfSupported(targetFile, targetFile.name, "text/plain")

                val completedRec = rec.copy(
                    fileName = targetFile.name,
                    state = DownloadState.COMPLETED,
                    downloadedBytes = outBytes.size.toLong(),
                    totalBytes = outBytes.size.toLong(),
                    speedBytesPerSec = 0L,
                    localPath = targetFile.absolutePath,
                    contentUri = publicUri,
                    errorMessage = ""
                )
                withContext(Dispatchers.Main) {
                    val idx = records.indexOfFirst { it.id == rec.id }
                    if (idx >= 0) records[idx] = completedRec
                    persistHistory()
                }
                NotificationHelper.showDownloadFinished(
                    appContext,
                    rec.id,
                    completedRec.fileName,
                    true,
                    Formatters.formatBytes(outBytes.size.toLong())
                )
                onComplete?.invoke(completedRec, utf8Text)
            } catch (ce: CancellationException) {
                updateRecord(rec.id, persist = true) {
                    it.copy(state = DownloadState.CANCELLED, speedBytesPerSec = 0L)
                }
                NotificationHelper.cancelDownloadNotification(appContext, rec.id)
                throw ce
            } catch (e: Exception) {
                val msg = e.message ?: "Subtitle download failed"
                withContext(Dispatchers.Main) {
                    val idx = records.indexOfFirst { it.id == rec.id }
                    if (idx >= 0) {
                        records[idx] = records[idx].copy(
                            state = DownloadState.FAILED,
                            speedBytesPerSec = 0L,
                            errorMessage = msg
                        )
                    }
                    persistHistory()
                }
                NotificationHelper.showDownloadFinished(appContext, rec.id, rec.fileName, false, msg)
                onError?.invoke(msg)
            } finally {
                activeJobs.remove(rec.id)
            }
        }
        activeJobs[rec.id] = job
    }

    fun cancelDownload(id: String) {
        activeJobs.remove(id)?.cancel()
        updateRecord(id, persist = true) {
            it.copy(state = DownloadState.CANCELLED, speedBytesPerSec = 0L)
        }
        NotificationHelper.cancelDownloadNotification(appContext, id)
    }

    fun retryDownload(
        id: String,
        onComplete: ((DownloadRecord) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val rec = records.firstOrNull { it.id == id } ?: return
        if (rec.state == DownloadState.DOWNLOADING) return
        if (rec.category == DownloadCategory.SUBTITLE && rec.subtitleTargetLang.isNotBlank()) {
            startSubtitleJob(
                rec = rec,
                onComplete = { done, _ -> onComplete?.invoke(done) },
                onError = onError
            )
        } else {
            startBrowserJob(
                rec = rec,
                onComplete = onComplete,
                onError = { _, err -> onError?.invoke(err) }
            )
        }
    }

    fun deleteDownload(id: String, deleteFromDisk: Boolean = true): DownloadRecord? {
        activeJobs.remove(id)?.cancel()
        NotificationHelper.cancelDownloadNotification(appContext, id)
        val idx = records.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = records.removeAt(idx)
        persistHistory()
        if (deleteFromDisk) {
            scope.launch {
                if (removed.localPath.isNotBlank()) {
                    runCatching { File(removed.localPath).delete() }
                }
                if (removed.contentUri.isNotBlank()) {
                    runCatching { appContext.contentResolver.delete(Uri.parse(removed.contentUri), null, null) }
                }
            }
        }
        return removed
    }

    fun clearFinishedDownloads() {
        val finishedIds = records
            .filter { it.state != DownloadState.DOWNLOADING && it.state != DownloadState.QUEUED }
            .map { it.id }
            .toSet()
        if (finishedIds.isEmpty()) return
        records.removeAll { it.id in finishedIds }
        persistHistory()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    fun openDownloadedFile(
        context: Context,
        record: DownloadRecord,
        chooserTitle: String,
        onMissingFile: () -> Unit,
        onNoAppFound: () -> Unit
    ) {
        val file = record.localPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val targetUri: Uri? = when {
            file != null && file.exists() -> {
                runCatching {
                    FileProvider.getUriForFile(
                        context.applicationContext,
                        "${context.applicationContext.packageName}.fileprovider",
                        file
                    )
                }.getOrNull()
            }
            record.contentUri.isNotBlank() -> runCatching { Uri.parse(record.contentUri) }.getOrNull()
            else -> null
        }

        if (targetUri == null) {
            onMissingFile()
            return
        }

        val mime = resolveMimeType(record.fileName, record.mimeType)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val chooser = Intent.createChooser(viewIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val act = context.findActivity()
            if (act != null) {
                act.startActivity(chooser)
            } else {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        } catch (_: ActivityNotFoundException) {
            onNoAppFound()
        } catch (_: Exception) {
            onNoAppFound()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDownloadManager? = null

        fun getInstance(context: Context): AppDownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
