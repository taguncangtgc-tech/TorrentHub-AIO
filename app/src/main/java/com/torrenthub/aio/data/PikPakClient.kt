package com.torrenthub.aio.data

import com.torrenthub.aio.model.PikPakCredentials
import com.torrenthub.aio.model.PikPakDriveItem
import com.torrenthub.aio.model.PikPakFolder
import com.torrenthub.aio.model.PikPakTaskInfo
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.network.HttpStatusException
import com.torrenthub.aio.util.Formatters
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

object PikPakClient {
    private const val DEFAULT_CLIENT_ID = "YUMx5nI8ZU8Ap8pm"

    private data class CaptchaProfile(
        val clientId: String,
        val clientVersion: String,
        val packageName: String,
        val salts: List<String>
    )

    private val WEB_V2_PROFILE = CaptchaProfile(
        clientId = "YUMx5nI8ZU8Ap8pm",
        clientVersion = "2.0.0",
        packageName = "mypikpak.com",
        salts = listOf(
            "C9qPpZLN8ucRTaTiUMWYS9cQvWOE", "+r6CQVxjzJV6LCV", "F", "pFJRC",
            "9WXYIDGrwTCz2OiVlgZa90qpECPD6olt", "/750aCr4lm/Sly/c", "RB+DT/gZCrbV", "",
            "CyLsf7hdkIRxRm215hl", "7xHvLi2tOYP0Y92b", "ZGTXXxu8E/MIWaEDB+Sm/", "1UI3",
            "E7fP5Pfijd+7K+t6Tg/NhuLq0eEUVChpJSkrKxpO", "ihtqpG6FMt65+Xk+tWUH2", "NhXXU9rg4XXdzo7u5o"
        )
    )

    private val WEB_V1_PROFILE = CaptchaProfile(
        clientId = "YUMx5nI8ZU8Ap8pm",
        clientVersion = "1.0.0",
        packageName = "mypikpak.com",
        salts = listOf(
            "mg3UtlOJ5/6WjxHsGXtAthe",
            "kRG2RIlL/eScz3oDbzeF1",
            "uOIOBDcR5QALlRUUK4JVoreEI0i3RG8ZiUf2hMOH",
            "wa+0OkzHAzpyZ0S/JAnHmF2BlMR9Y",
            "ZWV2OkSLoNkmbr58v0f6U3udtqUNP7XON",
            "Jg4cDxtvbmlakZIOpQN0oY1P0eYkA4xquMY9/xqwZE5sjrcHwufR",
            "XHfs",
            "S4/mRgYpWyNGEUxVsYBw8n//zlywe5Ga1R8ffWJSOPZnMqWb4w"
        )
    )

    private fun jwtSub(token: String): String = try {
        val seg = token.removePrefix("Bearer ").trim().split(".")[1]
        val bytes = android.util.Base64.decode(seg, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        JSONObject(String(bytes)).optString("sub")
    } catch (e: Exception) { "" }

    private fun normalizeDeviceId(raw: String, fallbackSeed: String): String {
        val cleaned = raw.trim().trim('"')
        if (cleaned.isBlank()) return Formatters.md5Hex("pikpak_dev_$fallbackSeed")
        // WebView localStorage stores deviceid like "wdi10.<64hex>"; strip prefix if present
        val withoutPrefix = if (cleaned.startsWith("wdi10.", ignoreCase = true)) {
            cleaned.substringAfter('.').take(32)
        } else {
            cleaned
        }
        return withoutPrefix.ifBlank { Formatters.md5Hex("pikpak_dev_$fallbackSeed") }
    }

    fun isCaptchaError(code: Int, body: String): Boolean =
        (code == 400 || code == 403 || code == 412) &&
            (body.contains("captcha", true) ||
                body.contains("Verification Code", true) ||
                body.contains("\"error_code\":9") ||
                body.contains("\"error_code\": 9") ||
                body.contains("4002") ||
                body.contains("invalid_request", true))

    /** Ambil x-captcha-token baru dari PikPak shield dengan fallback profil & deviceId. */
    suspend fun ensureCaptcha(creds: PikPakCredentials, action: String, force: Boolean = false): PikPakCredentials {
        val now = System.currentTimeMillis()
        if (!force && creds.captchaToken.isNotBlank() && creds.captchaExpiresAtMs > now) return creds
        val clientId = creds.clientId.ifBlank { DEFAULT_CLIENT_ID }
        val userId = jwtSub(creds.accessToken)
        val seed = userId.ifBlank { creds.accessToken.takeLast(24) }
        val normalizedDevId = normalizeDeviceId(creds.deviceId, seed)
        val rawDevId = creds.deviceId.trim().trim('"')
        val candidateDeviceIds = buildList {
            if (normalizedDevId.isNotBlank()) add(normalizedDevId)
            if (rawDevId.isNotBlank() && rawDevId != normalizedDevId) add(rawDevId)
            val md5Dev = Formatters.md5Hex("pikpak_dev_$seed")
            if (md5Dev !in this) add(md5Dev)
        }
        val profiles = listOf(WEB_V2_PROFILE, WEB_V1_PROFILE)

        for (devId in candidateDeviceIds) {
            for (profile in profiles) {
                val ts = System.currentTimeMillis().toString()
                var sign = clientId + profile.clientVersion + profile.packageName + devId + ts
                for (salt in profile.salts) sign = Formatters.md5Hex(sign + salt)
                val payload = JSONObject()
                    .put("client_id", clientId)
                    .put("action", action)
                    .put("device_id", devId)
                    .put("captcha_token", if (force) "" else creds.captchaToken)
                    .put("meta", JSONObject()
                        .put("captcha_sign", "1.$sign")
                        .put("client_version", profile.clientVersion)
                        .put("package_name", profile.packageName)
                        .put("user_id", userId)
                        .put("timestamp", ts))
                try {
                    val (code, text) = HttpClient.postJson(
                        "https://user.mypikpak.com/v1/shield/captcha/init",
                        payload.toString(),
                        headers = mapOf(
                            "Accept" to "application/json",
                            "Origin" to "https://mypikpak.com",
                            "Referer" to "https://mypikpak.com/",
                            "x-client-id" to clientId,
                            "x-device-id" to devId
                        )
                    )
                    if (code !in 200..299) continue
                    val obj = JSONObject(text)
                    val tok = obj.optString("captcha_token").trim()
                    if (tok.isNotBlank()) {
                        val exp = obj.optLong("expires_in", 300L).coerceAtLeast(30L)
                        return creds.copy(
                            clientId = clientId,
                            deviceId = devId,
                            captchaToken = tok,
                            captchaExpiresAtMs = System.currentTimeMillis() + (exp - 15) * 1000L
                        )
                    }
                } catch (_: Exception) {
                    // Try next profile / deviceId candidate
                }
            }
        }
        return creds.copy(clientId = clientId, deviceId = normalizedDevId)
    }

    private fun buildHeaders(creds: PikPakCredentials): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("Origin", "https://mypikpak.com")
        put("Referer", "https://mypikpak.com/")
        if (creds.accessToken.isNotBlank()) {
            val auth = if (creds.accessToken.startsWith("Bearer ", ignoreCase = true)) {
                creds.accessToken
            } else {
                "Bearer ${creds.accessToken}"
            }
            put("Authorization", auth)
        }
        put("x-client-id", creds.clientId.ifBlank { DEFAULT_CLIENT_ID })
        if (creds.deviceId.isNotBlank()) put("x-device-id", creds.deviceId)
        if (creds.captchaToken.isNotBlank()) put("x-captcha-token", creds.captchaToken)
    }

    fun parseWebViewLocalStorageJson(rawStorageJson: String, existing: PikPakCredentials): PikPakCredentials? {
        return try {
            val storage = JSONObject(rawStorageJson)
            var accessToken = ""
            var refreshToken = ""
            var clientId = existing.clientId
            var deviceId = existing.deviceId
            var captchaToken = existing.captchaToken
            var email = existing.userEmail

            val keys = storage.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val rawVal = storage.optString(k).orEmpty()
                if (rawVal.isBlank()) continue

                if (k.startsWith("credentials")) {
                    if (k.contains("_")) {
                        val cid = k.substringAfter("_").trim()
                        if (cid.isNotBlank()) clientId = cid
                    }
                    try {
                        val obj = JSONObject(rawVal)
                        val at = obj.optString("access_token")
                        val rt = obj.optString("refresh_token")
                        if (at.isNotBlank()) {
                            val tokenType = obj.optString("token_type", "Bearer")
                            accessToken = "$tokenType $at"
                        }
                        if (rt.isNotBlank()) refreshToken = rt
                    } catch (_: Exception) {}
                } else if (k.startsWith("captcha", ignoreCase = true)) {
                    try {
                        val obj = JSONObject(rawVal)
                        val ct = obj.optString("captcha_token").ifBlank { obj.optString("token") }
                        if (ct.isNotBlank()) captchaToken = ct
                    } catch (_: Exception) {
                        captchaToken = rawVal.trim('"')
                    }
                } else if (Regex("deviceid|device_id", RegexOption.IGNORE_CASE).containsMatchIn(k)) {
                    deviceId = rawVal.trim('"')
                } else if (k.contains("user", ignoreCase = true)) {
                    try {
                        val obj = JSONObject(rawVal)
                        val em = obj.optString("email").ifBlank { obj.optString("name") }
                        if (em.isNotBlank()) email = em
                    } catch (_: Exception) {}
                }
            }

            if (accessToken.isBlank()) null
            else existing.copy(
                accessToken = accessToken,
                refreshToken = refreshToken.ifBlank { existing.refreshToken },
                clientId = clientId.ifBlank { DEFAULT_CLIENT_ID },
                deviceId = deviceId.trim().trim('"'),
                captchaToken = captchaToken,
                captchaExpiresAtMs = 0L,
                userEmail = email
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun refreshAccessToken(creds: PikPakCredentials): PikPakCredentials? {
        if (creds.refreshToken.isBlank()) return null
        val cid = creds.clientId.ifBlank { DEFAULT_CLIENT_ID }
        val payload = JSONObject()
            .put("client_id", cid)
            .put("grant_type", "refresh_token")
            .put("refresh_token", creds.refreshToken)

        val (code, text) = HttpClient.postJson(
            "https://user.mypikpak.com/v1/auth/token",
            payload.toString(),
            headers = buildHeaders(creds)
        )
        if (code !in 200..299) return null
        val obj = JSONObject(text)
        val newAccess = obj.optString("access_token")
        if (newAccess.isBlank()) return null
        val newRefresh = obj.optString("refresh_token").ifBlank { creds.refreshToken }
        val tokenType = obj.optString("token_type", "Bearer")
        return creds.copy(
            accessToken = "$tokenType $newAccess",
            refreshToken = newRefresh
        )
    }

    /* ================= GCID HASH & DIRECT SRT UPLOAD TO PIKPAK DRIVE ================= */
    fun calcGcid(bytes: ByteArray): String {
        val size = bytes.size.toLong()
        var blockSize = 0x40000 // 256 KiB
        while (size.toDouble() / blockSize > 0x200 && blockSize < 0x200000) {
            blockSize = blockSize shl 1
        }
        val totalSha1 = MessageDigest.getInstance("SHA-1")
        val blockSha1 = MessageDigest.getInstance("SHA-1")
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(blockSize, bytes.size - offset)
            blockSha1.reset()
            blockSha1.update(bytes, offset, len)
            totalSha1.update(blockSha1.digest())
            offset += len
        }
        if (bytes.isEmpty()) {
            blockSha1.reset()
            totalSha1.update(blockSha1.digest())
        }
        return totalSha1.digest().joinToString("") { "%02X".format(it) }
    }

    data class UploadFileResult(
        val ok: Boolean,
        val fileName: String,
        val errorMessage: String = "",
        val updatedCreds: PikPakCredentials? = null
    )

    suspend fun uploadSubtitleFile(
        fileName: String,
        content: String,
        creds: PikPakCredentials
    ): UploadFileResult {
        if (!creds.isLoggedIn) {
            return UploadFileResult(false, fileName, "Belum login ke akun PikPak.")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val gcid = calcGcid(bytes)
        var activeCreds = creds
        var refreshed = false
        var captchaRetried = false
        var rootFallbackTried = false

        while (true) {
            activeCreds = ensureCaptcha(activeCreds, "POST:/drive/v1/files")
            val reqJson = JSONObject()
                .put("kind", "drive#file")
                .put("name", fileName)
                .put("size", bytes.size)
                .put("hash", gcid)
                .put("upload_type", "UPLOAD_TYPE_FORM")

            if (activeCreds.targetFolderId.isNotBlank()) {
                reqJson.put("parent_id", activeCreds.targetFolderId)
            } else if (!rootFallbackTried) {
                reqJson.put("folder_type", "DOWNLOAD")
            } else {
                reqJson.put("parent_id", "")
            }

            val (code, respBody) = try {
                HttpClient.postJson(
                    "${activeCreds.apiOrigin}/drive/v1/files",
                    reqJson.toString(),
                    headers = buildHeaders(activeCreds)
                )
            } catch (e: Exception) {
                return UploadFileResult(false, fileName, e.message ?: "Gagal menghubungi PikPak", activeCreds)
            }

            if (code == 401 && !refreshed && activeCreds.refreshToken.isNotBlank()) {
                refreshed = true
                val nextCreds = refreshAccessToken(activeCreds)
                if (nextCreds != null) {
                    activeCreds = nextCreds
                    continue
                }
            }

            if (isCaptchaError(code, respBody) && !captchaRetried) {
                captchaRetried = true
                activeCreds = ensureCaptcha(activeCreds, "POST:/drive/v1/files", force = true)
                continue
            }

            if (code == 400 && activeCreds.targetFolderId.isBlank() && !rootFallbackTried && !isCaptchaError(code, respBody)) {
                rootFallbackTried = true
                continue
            }

            if (code !in 200..299) {
                return UploadFileResult(
                    false,
                    fileName,
                    HttpClient.buildErrorDetail(code, respBody),
                    if (activeCreds != creds) activeCreds else null
                )
            }

            val root = JSONObject(respBody)
            val fileObj = root.optJSONObject("file")
            val phase = fileObj?.optString("phase").orEmpty()
            val upType = root.optString("upload_type")

            // Instant GCID deduplication hit — file already complete on PikPak server!
            if (phase == "PHASE_TYPE_COMPLETE" || upType == "UPLOAD_TYPE_UNKNOWN") {
                return UploadFileResult(
                    true,
                    fileObj?.optString("name") ?: fileName,
                    updatedCreds = if (activeCreds != creds) activeCreds else null
                )
            }

            val formObj = root.optJSONObject("form")
                ?: return UploadFileResult(false, fileName, "Respons form upload PikPak kosong.")
            val formUrl = formObj.optString("url")
            val multiParts = formObj.optJSONObject("multi_parts") ?: JSONObject()
            val fields = LinkedHashMap<String, String>()
            val kIter = multiParts.keys()
            while (kIter.hasNext()) {
                val k = kIter.next()
                fields[k] = multiParts.optString(k)
            }

            val (ossCode, ossBody) = HttpClient.postMultipartForm(
                url = formUrl,
                fields = fields,
                fileFieldName = "file",
                fileName = fileName,
                fileBytes = bytes
            )
            if (ossCode !in 200..299) {
                return UploadFileResult(
                    false,
                    fileName,
                    "OSS Upload HTTP $ossCode: ${ossBody.take(120)}",
                    if (activeCreds != creds) activeCreds else null
                )
            }

            return UploadFileResult(
                true,
                fileObj?.optString("name") ?: fileName,
                updatedCreds = if (activeCreds != creds) activeCreds else null
            )
        }
    }

    /* ================= MAGNET CLOUD DOWNLOAD ================= */
    data class SendMagnetResult(
        val ok: Boolean,
        val addedCount: Int,
        val errorMessage: String = "",
        val updatedCreds: PikPakCredentials? = null
    )

    suspend fun sendMagnetsToCloud(
        magnets: List<String>,
        creds: PikPakCredentials
    ): SendMagnetResult {
        if (!creds.isLoggedIn) {
            return SendMagnetResult(false, 0, "Belum login ke akun PikPak. Buka tab PikPak untuk login dulu.")
        }
        val cleanList = magnets.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanList.isEmpty()) return SendMagnetResult(false, 0, "Tidak ada tautan magnet.")

        var activeCreds = creds
        var refreshedOnce = false
        var captchaRetried = false
        val endpoint = "${activeCreds.apiOrigin}/drive/v1/files"
        var added = 0
        var lastErr = ""

        var idx = 0
        while (idx < cleanList.size) {
            val magnet = cleanList[idx]
            activeCreds = ensureCaptcha(activeCreds, "POST:/drive/v1/files")
            val payload = JSONObject()
                .put("kind", "drive#file")
                .put("name", "")
                .put("upload_type", "UPLOAD_TYPE_URL")
                .put("url", JSONObject().put("url", magnet))
                .put("params", JSONObject().put("from", "file"))

            if (activeCreds.targetFolderId.isNotBlank()) {
                payload.put("parent_id", activeCreds.targetFolderId)
            } else {
                payload.put("folder_type", "DOWNLOAD")
            }

            val (code, body) = try {
                HttpClient.postJson(endpoint, payload.toString(), headers = buildHeaders(activeCreds))
            } catch (e: Exception) {
                return SendMagnetResult(false, added, e.message ?: "Gagal menghubungi server PikPak", activeCreds)
            }

            if (code in 200..299) {
                added++
                idx++
                continue
            }

            if (isCaptchaError(code, body) && !captchaRetried) {
                captchaRetried = true
                activeCreds = ensureCaptcha(activeCreds, "POST:/drive/v1/files", force = true)
                continue
            }
            captchaRetried = false

            if (code == 401 && !refreshedOnce && activeCreds.refreshToken.isNotBlank()) {
                refreshedOnce = true
                val refreshed = refreshAccessToken(activeCreds)
                if (refreshed != null) {
                    activeCreds = refreshed
                    continue
                }
            }

            lastErr = HttpClient.buildErrorDetail(code, body)
            if (lastErr.contains("captcha", ignoreCase = true) || code == 403 || code == 412) {
                lastErr += " (Buka tab PikPak → 'Buka & Sinkron Ulang Sesi Web PikPak')"
            }
            break
        }

        return SendMagnetResult(
            ok = added == cleanList.size && added > 0,
            addedCount = added,
            errorMessage = lastErr,
            updatedCreds = if (activeCreds != creds) activeCreds else null
        )
    }

    /* ================= MINI FILE MANAGER & STREAMING LINKS ================= */
    suspend fun listFolders(parentId: String, creds: PikPakCredentials): List<PikPakFolder> {
        val filters = JSONObject()
            .put("trashed", JSONObject().put("eq", false))
            .put("kind", JSONObject().put("eq", "drive#folder"))
            .toString()
        val url = "${creds.apiOrigin}/drive/v1/files?parent_id=${Formatters.urlEncode(parentId)}&limit=100&filters=${Formatters.urlEncode(filters)}"
        val raw = HttpClient.getString(url, headers = buildHeaders(creds))
        val arr = JSONObject(raw).optJSONArray("files") ?: return emptyList()
        val out = mutableListOf<PikPakFolder>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val kind = f.optString("kind")
            if (kind == "drive#folder") {
                out.add(PikPakFolder(id = f.optString("id"), name = f.optString("name"), kind = kind))
            }
        }
        return out
    }

    suspend fun listDirectoryItems(parentId: String, creds: PikPakCredentials): List<PikPakDriveItem> {
        val filters = JSONObject()
            .put("trashed", JSONObject().put("eq", false))
            .toString()
        val url = "${creds.apiOrigin}/drive/v1/files?parent_id=${Formatters.urlEncode(parentId)}&limit=100&filters=${Formatters.urlEncode(filters)}"
        val raw = try {
            HttpClient.getString(url, headers = buildHeaders(creds))
        } catch (e: HttpStatusException) {
            if (e.code == 401 && creds.refreshToken.isNotBlank()) {
                val refreshed = refreshAccessToken(creds) ?: throw e
                HttpClient.getString(url, headers = buildHeaders(refreshed))
            } else throw e
        }
        val arr = JSONObject(raw).optJSONArray("files") ?: return emptyList()
        val out = mutableListOf<PikPakDriveItem>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            out.add(
                PikPakDriveItem(
                    id = f.optString("id"),
                    name = f.optString("name"),
                    kind = f.optString("kind"),
                    size = f.optString("size").toLongOrNull() ?: f.optLong("size", 0L),
                    mimeType = f.optString("mime_type"),
                    modifiedTime = f.optString("modified_time").take(10)
                )
            )
        }
        return out.sortedWith(
            compareByDescending<PikPakDriveItem> { it.isFolder }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    data class FileDirectLinks(
        val streamUrl: String,
        val downloadUrl: String
    )

    suspend fun fetchFileLinks(fileId: String, creds: PikPakCredentials): FileDirectLinks {
        val url = "${creds.apiOrigin}/drive/v1/files/${Formatters.urlEncode(fileId)}?usage=FETCH"
        val raw = HttpClient.getString(url, headers = buildHeaders(creds))
        val root = JSONObject(raw)

        var downloadUrl = root.optString("web_content_link")
        val linksObj = root.optJSONObject("links")
        if (downloadUrl.isBlank() && linksObj != null) {
            downloadUrl = linksObj.optJSONObject("application/octet-stream")?.optString("url").orEmpty()
            if (downloadUrl.isBlank()) {
                val keys = linksObj.keys()
                while (keys.hasNext()) {
                    val cand = linksObj.optJSONObject(keys.next())?.optString("url").orEmpty()
                    if (cand.startsWith("http")) {
                        downloadUrl = cand
                        break
                    }
                }
            }
        }

        // Prefer medias[0].link.url for video streaming (better multi-range support in VLC/MX/MPV!)
        var streamUrl = ""
        val medias = root.optJSONArray("medias")
        if (medias != null) {
            for (i in 0 until medias.length()) {
                val mUrl = medias.optJSONObject(i)?.optJSONObject("link")?.optString("url").orEmpty()
                if (mUrl.startsWith("http")) {
                    streamUrl = mUrl
                    break
                }
            }
        }
        if (streamUrl.isBlank()) streamUrl = downloadUrl

        return FileDirectLinks(streamUrl = streamUrl, downloadUrl = downloadUrl.ifBlank { streamUrl })
    }

    suspend fun listRecentOfflineTasks(creds: PikPakCredentials): List<PikPakTaskInfo> {
        val filters = JSONObject().put("type", JSONObject().put("in", "offline")).toString()
        val url = "${creds.apiOrigin}/drive/v1/tasks?type=offline&limit=20&filters=${Formatters.urlEncode(filters)}"
        val raw = HttpClient.getString(url, headers = buildHeaders(creds))
        val arr = JSONObject(raw).optJSONArray("tasks") ?: return emptyList()
        val out = mutableListOf<PikPakTaskInfo>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            out.add(
                PikPakTaskInfo(
                    id = t.optString("id"),
                    name = t.optString("name").ifBlank { t.optString("file_name", "Tugas Cloud") },
                    phase = t.optString("phase").removePrefix("PHASE_TYPE_"),
                    progress = t.optInt("progress", 0),
                    fileSize = t.optLong("file_size", 0L),
                    message = t.optString("message")
                )
            )
        }
        return out
    }
}
