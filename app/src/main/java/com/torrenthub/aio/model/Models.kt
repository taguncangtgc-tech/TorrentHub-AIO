package com.torrenthub.aio.model

data class QualityFlags(
    val is4K: Boolean = false,
    val isSub: Boolean = false,
    val isCracked: Boolean = false,
    val isEd2k: Boolean = false
)

data class TorrentItem(
    val title: String,
    val magnet: String,
    val hash: String,
    val size: Long = 0L,
    val files: String = "",
    val seeders: Int? = null,
    val leechers: Int? = null,
    val dateMs: Long = 0L,
    val pageUrl: String = "",
    val tpbId: String = "",
    val images: List<String> = emptyList(),
    val desc: String = "",
    val source: String = "",
    val sourceId: String = "",
    val sources: List<String> = listOf(source),
    val sourceIds: List<String> = listOf(sourceId),
    val qFlags: QualityFlags = QualityFlags(),
    val videoCode: String = ""
) {
    val uniqueKey: String
        get() = hash.ifBlank { magnet.ifBlank { "$sourceId:$title" } }
}

data class SourceDef(
    val id: String,
    val label: String,
    val colorHex: Long,
    val defaultOn: Boolean,
    val supportsWebViewBridge: Boolean = false
)

enum class PillState { LOADING, OK, ERROR }

data class SourceStatus(
    val sourceId: String,
    val label: String,
    val state: PillState,
    val count: Int = 0,
    val message: String = "",
    val canBypassCloudflare: Boolean = false,
    val bypassUrl: String = ""
)

data class WhatsLinkInfo(
    val name: String = "",
    val count: Int = 0,
    val size: Long = 0L,
    val fileType: String = "",
    val screenshots: List<String> = emptyList()
)

data class ThreeSourceResult(
    val code: String,
    val activeSource: String?,
    val imageUrl: String?,
    val referer: String = ""
)

enum class SubtitleProvider(val id: String, val label: String, val colorHex: Long) {
    ALL("all", "All", 0xFF0D9488),
    XUNLEI("xunlei", "Xunlei", 0xFF0284C7),
    SUBTITLECAT("subtitlecat", "SubtitleCat", 0xFFD97706),
    OPENSUBTITLES("opensubtitles", "OpenSubtitles", 0xFF059669),
    SUBDL("subdl", "SubDL", 0xFF7C3AED);

    companion object {
        fun fromId(id: String?): SubtitleProvider =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALL
    }
}

enum class TranslationProvider(val id: String) {
    GOOGLE_FREE("google_free"),
    GEMINI("gemini"),
    DEEPL("deepl"),
    OPENAI("openai");

    companion object {
        fun fromId(id: String?, fallbackToGemini: Boolean = false): TranslationProvider {
            if (id.isNullOrBlank()) {
                return if (fallbackToGemini) GEMINI else GOOGLE_FREE
            }
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: if (fallbackToGemini) GEMINI else GOOGLE_FREE
        }
    }
}

data class SafVideoFile(
    val uriString: String,
    val documentId: String,
    val name: String,
    val sizeBytes: Long = 0L
)

data class SubtitleItem(
    val idKey: String,
    val name: String,
    val ext: String,
    val url: String,
    val sizeBytes: Long = 0L,
    val language: String = "",
    val durationMs: Long = 0L,
    val sourceId: String = "xunlei",
    val sourceName: String = "Xunlei"
)

data class GeminiJob(
    val id: String,
    val fileName: String,
    val ext: String,
    val originalContent: String,
    val totalLines: Int,
    val pct: Int = 0,
    val statusText: String = "",
    val resultContent: String? = null,
    val hasSavedProgress: Boolean = false,
    val uploadedToPikPak: Boolean = false
)

data class PikPakCredentials(
    val accessToken: String = "",
    val refreshToken: String = "",
    val clientId: String = "",
    val deviceId: String = "",
    val captchaToken: String = "",
    val captchaExpiresAtMs: Long = 0L,
    val userEmail: String = "",
    val apiOrigin: String = "https://api-drive.mypikpak.com",
    val targetFolderId: String = "",
    val targetFolderName: String = "Cloud Download (Default)"
) {
    val isLoggedIn: Boolean
        get() = accessToken.isNotBlank()
}

data class PikPakFolder(
    val id: String,
    val name: String,
    val kind: String,
    val size: Long = 0L
)

data class PikPakDriveItem(
    val id: String,
    val name: String,
    val kind: String, // "drive#folder" or "drive#file"
    val size: Long = 0L,
    val mimeType: String = "",
    val modifiedTime: String = ""
) {
    val isFolder: Boolean get() = kind == "drive#folder"
    val isVideo: Boolean
        get() = !isFolder && (
            mimeType.startsWith("video/", ignoreCase = true) ||
                Regex("\\.(mp4|mkv|avi|mov|wmv|flv|webm|m4v|ts|rmvb)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
            )
    val isSubtitle: Boolean
        get() = !isFolder && Regex("\\.(srt|vtt|ass|ssa)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
}

data class PikPakTaskInfo(
    val id: String,
    val name: String,
    val phase: String,
    val progress: Int,
    val fileSize: Long,
    val message: String = "",
    val fileId: String = ""
)

enum class QuickStreamStage {
    SENDING_MAGNET,
    CACHING_CLOUD,
    RESOLVING_VIDEO,
    SELECT_VIDEO,
    LAUNCHING_PLAYER,
    TIMEOUT,
    ERROR
}

data class QuickStreamState(
    val torrentTitle: String,
    val magnet: String,
    val stage: QuickStreamStage = QuickStreamStage.SENDING_MAGNET,
    val taskId: String = "",
    val fileId: String = "",
    val taskName: String = "",
    val phaseLabel: String = "PENDING",
    val progress: Int = 0,
    val elapsedSec: Int = 0,
    val candidateVideos: List<PikPakDriveItem> = emptyList(),
    val busyVideoId: String? = null,
    val errorMessage: String = ""
)

data class BrowserBookmark(
    val title: String,
    val url: String
)

data class UserscriptItem(
    val id: String,
    val name: String,
    val namespace: String = "",
    val version: String = "1.0",
    val description: String = "",
    val matches: List<String> = listOf("*://*/*"),
    val excludes: List<String> = emptyList(),
    val runAt: String = "document-end",
    val enabled: Boolean = true,
    val sourceUrl: String = "",
    val codeSize: Int = 0
)

data class UserscriptMenuCmd(
    val scriptId: String,
    val scriptName: String,
    val cmdId: String,
    val caption: String
)
