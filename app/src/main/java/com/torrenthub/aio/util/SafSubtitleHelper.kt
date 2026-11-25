package com.torrenthub.aio.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.torrenthub.aio.model.SafVideoFile
import java.util.Locale

object SafSubtitleHelper {

    private val VIDEO_EXT_RE = Regex(
        "\\.(mp4|mkv|avi|mov|wmv|flv|webm|m4v|ts|m2ts|mpg|mpeg|rmvb)$",
        RegexOption.IGNORE_CASE
    )

    fun persistTreePermission(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: Exception) {}
    }

    fun getTreeDisplayName(context: Context, treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else null
            } ?: docId.substringAfterLast(':').substringAfterLast('/').ifBlank { treeUri.lastPathSegment ?: "Folder" }
        } catch (_: Exception) {
            treeUri.lastPathSegment?.substringAfterLast(':') ?: "Folder"
        }
    }

    fun listVideosInTree(context: Context, treeUriString: String): List<SafVideoFile> {
        if (treeUriString.isBlank()) return emptyList()
        val treeUri = Uri.parse(treeUriString)
        val resolver = context.contentResolver
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return emptyList()
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        val videos = mutableListOf<SafVideoFile>()
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L

                    if (docId.isBlank() || name.isBlank()) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue

                    val isVideo = mime.startsWith("video/", ignoreCase = true) || VIDEO_EXT_RE.containsMatchIn(name)
                    if (isVideo) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        videos.add(
                            SafVideoFile(
                                uriString = fileUri.toString(),
                                documentId = docId,
                                name = name,
                                sizeBytes = size
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return videos.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun findBestMatchingVideo(videos: List<SafVideoFile>, hint: String): SafVideoFile? {
        if (videos.isEmpty()) return null
        if (videos.size == 1 && hint.isBlank()) return videos.first()

        val hintCode = Formatters.extractVideoCode(hint).uppercase(Locale.ROOT)
        if (hintCode.isNotBlank()) {
            videos.firstOrNull { Formatters.extractVideoCode(it.name).equals(hintCode, ignoreCase = true) }
                ?.let { return it }
        }

        val hintBase = hint.substringBeforeLast('.', hint)
            .replace(Regex("\\.(id|ind|en|eng|zh|chs|cht|ja|jpn|ko|kor)$", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase(Locale.ROOT)

        if (hintBase.isNotBlank()) {
            videos.firstOrNull {
                val vb = it.name.substringBeforeLast('.', it.name).trim().lowercase(Locale.ROOT)
                vb == hintBase || vb.contains(hintBase) || hintBase.contains(vb)
            }?.let { return it }

            val tokens = hintBase.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
            if (tokens.isNotEmpty()) {
                val scored = videos.map { v ->
                    val vn = v.name.lowercase(Locale.ROOT)
                    v to tokens.count { vn.contains(it) }
                }.maxByOrNull { it.second }
                if (scored != null && scored.second >= maxOf(1, tokens.size / 2)) {
                    return scored.first
                }
            }
        }
        return null
    }

    fun buildPairedSubtitleFileName(videoFileName: String, subtitleFileNameOrExt: String): String {
        val cleanVideoBase = videoFileName.trim()
            .replace(VIDEO_EXT_RE, "")
            .ifBlank { videoFileName.substringBeforeLast('.', videoFileName) }
            .trim()
        val extCandidate = if (subtitleFileNameOrExt.contains('.')) {
            subtitleFileNameOrExt.substringAfterLast('.', "srt").lowercase(Locale.ROOT)
        } else {
            subtitleFileNameOrExt.removePrefix(".").lowercase(Locale.ROOT)
        }
        val safeExt = if (extCandidate in setOf("srt", "ass", "ssa", "vtt")) extCandidate else "srt"
        return "$cleanVideoBase.$safeExt"
    }

    private fun subtitleMimeType(fileName: String): String =
        when (fileName.substringAfterLast('.', "srt").lowercase(Locale.ROOT)) {
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> "text/plain"
        }

    fun saveSubtitleToTreeFolder(
        context: Context,
        treeUriString: String,
        targetFileName: String,
        contentUtf8: String
    ): String {
        val treeUri = Uri.parse(treeUriString)
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

        var existingDocUri: Uri? = null
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0).orEmpty()
                    val name = cursor.getString(1).orEmpty()
                    if (name.equals(targetFileName, ignoreCase = true) && docId.isNotBlank()) {
                        existingDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        // Delete existing file first if present so SAF doesn't append "(1)" suffix
        existingDocUri?.let { oldUri ->
            try {
                DocumentsContract.deleteDocument(resolver, oldUri)
                existingDocUri = null
            } catch (_: Exception) {}
        }

        val destUri = existingDocUri ?: DocumentsContract.createDocument(
            resolver,
            parentDocUri,
            subtitleMimeType(targetFileName),
            targetFileName
        ) ?: throw IllegalStateException("Cannot create document in target folder")

        val outBytes = SubtitleTools.normalizeToUtf8Bytes(contentUtf8, includeBom = false)
        resolver.openOutputStream(destUri, "wt")?.use { out ->
            out.write(outBytes)
            out.flush()
        } ?: resolver.openOutputStream(destUri)?.use { out ->
            out.write(outBytes)
            out.flush()
        } ?: throw IllegalStateException("Cannot open output stream for $targetFileName")

        return targetFileName
    }
}
