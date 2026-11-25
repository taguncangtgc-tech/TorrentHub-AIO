package com.torrenthub.aio.network

import android.content.Context
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class DohProvider(val id: String, val endpointUrl: String, val primaryIp: String) {
    OFF("off", "", ""),
    CLOUDFLARE("cloudflare", "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
    GOOGLE("google", "https://dns.google/dns-query", "8.8.8.8"),
    QUAD9("quad9", "https://dns.quad9.net/dns-query", "9.9.9.9");

    companion object {
        fun fromId(id: String?): DohProvider =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CLOUDFLARE
    }
}

class HttpStatusException(val code: Int, override val message: String, val rawBody: String = "") : IOException(message)

object HttpClient {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val BROWSER_HTML_HEADERS = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
    )

    @Volatile
    var dohProvider: DohProvider = DohProvider.CLOUDFLARE
        private set

    val useDoh: Boolean
        get() = dohProvider != DohProvider.OFF

    private var okHttpInitialized = false

    fun applyDohProvider(provider: DohProvider) {
        val changed = dohProvider != provider
        dohProvider = provider
        if (changed && okHttpInitialized) {
            runCatching { okHttp.connectionPool.evictAll() }
        }
    }

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val cloudflareDoh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(DohProvider.CLOUDFLARE.endpointUrl.toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("2606:4700:4700::1001")
            )
            .build()
    }

    private val googleDoh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(DohProvider.GOOGLE.endpointUrl.toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4"),
                InetAddress.getByName("2001:4860:4860::8888"),
                InetAddress.getByName("2001:4860:4860::8844")
            )
            .build()
    }

    private val quad9Doh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(DohProvider.QUAD9.endpointUrl.toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("9.9.9.9"),
                InetAddress.getByName("149.112.112.112"),
                InetAddress.getByName("2620:fe::fe"),
                InetAddress.getByName("2620:fe::9")
            )
            .build()
    }

    private fun activeDohResolver(provider: DohProvider): DnsOverHttps? = when (provider) {
        DohProvider.OFF -> null
        DohProvider.CLOUDFLARE -> cloudflareDoh
        DohProvider.GOOGLE -> googleDoh
        DohProvider.QUAD9 -> quad9Doh
    }

    // Configurable DNS-over-HTTPS with resilient fallback to System DNS for local/captive hosts
    private val smartDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val active = activeDohResolver(dohProvider)
            if (active != null) {
                try {
                    val res = active.lookup(hostname)
                    if (res.isNotEmpty()) return res
                } catch (_: Exception) {}
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    suspend fun testActiveDns(testHost: String = "one.one.one.one"): Pair<String, Long> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val providerSnap = dohProvider
        val addresses = when (val resolver = activeDohResolver(providerSnap)) {
            null -> Dns.SYSTEM.lookup(testHost)
            else -> resolver.lookup(testHost)
        }
        val elapsed = System.currentTimeMillis() - t0
        val firstIp = addresses.firstOrNull()?.hostAddress ?: "-"
        firstIp to elapsed
    }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(smartDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val orig = chain.request()
                val b = orig.newBuilder()
                if (orig.header("User-Agent") == null) {
                    b.header("User-Agent", DEFAULT_UA)
                }
                if (orig.header("Accept-Language") == null) {
                    b.header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                }
                chain.proceed(b.build())
            }
            .build()
            .also { okHttpInitialized = true }
    }

    val longTimeoutHttp: OkHttpClient by lazy {
        okHttp.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun createImageLoader(context: Context): ImageLoader {
        val imgClient = okHttp.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .build()
        return ImageLoader.Builder(context)
            .okHttpClient(imgClient)
            .crossfade(true)
            .build()
    }

    suspend fun getString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 16L
    ): String = withContext(Dispatchers.IO) {
        val client = if (timeoutSec == 16L) okHttp else okHttp.newBuilder()
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        val reqBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, buildErrorDetail(resp.code, bodyStr), bodyStr)
            }
            bodyStr
        }
    }

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 25L
    ): ByteArray = withContext(Dispatchers.IO) {
        val client = if (timeoutSec == 25L) okHttp else okHttp.newBuilder()
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        val reqBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val preview = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { "" }
                throw HttpStatusException(resp.code, buildErrorDetail(resp.code, preview), preview)
            }
            bytes
        }
    }

    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        useLongTimeout: Boolean = false
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val client = if (useLongTimeout) longTimeoutHttp else okHttp
        val fb = FormBody.Builder(Charsets.UTF_8)
        fields.forEach { (k, v) -> fb.add(k, v) }
        val reqBuilder = Request.Builder().url(url).post(fb.build())
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
    }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        useLongTimeout: Boolean = false
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val client = if (useLongTimeout) longTimeoutHttp else okHttp
        val reqBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val reqBuilder = Request.Builder().url(url).post(reqBody)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
    }

    suspend fun postMultipartForm(
        url: String,
        fields: Map<String, String>,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) ->
            mb.addFormDataPart(k, v)
        }
        mb.addFormDataPart(
            fileFieldName,
            fileName,
            fileBytes.toRequestBody("application/octet-stream".toMediaType())
        )
        val req = Request.Builder()
            .url(url)
            .post(mb.build())
            .build()
        okHttp.newCall(req).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
    }

    data class ProbeResult(val sizeBytes: Long, val fullBytes: ByteArray? = null)

    suspend fun probeSizeAndOptionalBytes(url: String, maxReadBytes: Int = 5 * 1024 * 1024): ProbeResult =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext ProbeResult(0L)

            try {
                val headReq = Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .head()
                    .build()
                okHttp.newCall(headReq).execute().use { r ->
                    val len = r.header("Content-Length")?.toLongOrNull() ?: 0L
                    if (r.isSuccessful && len > 0L) return@withContext ProbeResult(len)
                }
            } catch (_: Exception) {}

            try {
                val rangeReq = Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()
                okHttp.newCall(rangeReq).execute().use { r ->
                    val contentRange = r.header("Content-Range").orEmpty()
                    Regex("/(\\d+)\\s*$").find(contentRange)?.groupValues?.get(1)?.toLongOrNull()?.let { total ->
                        if (total > 0L) return@withContext ProbeResult(total)
                    }
                    if (r.code == 200) {
                        val headerLen = r.header("Content-Length")?.toLongOrNull() ?: 0L
                        val bodyStream = r.body?.byteStream()
                        if (bodyStream != null) {
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            var totalRead = 0L
                            var exceededCap = false
                            while (true) {
                                val n = bodyStream.read(buf)
                                if (n <= 0) break
                                totalRead += n
                                if (totalRead <= maxReadBytes) {
                                    out.write(buf, 0, n)
                                } else {
                                    exceededCap = true
                                    break
                                }
                            }
                            if (!exceededCap && totalRead > 0L) {
                                return@withContext ProbeResult(totalRead, out.toByteArray())
                            }
                            if (headerLen > 0L) return@withContext ProbeResult(headerLen)
                        } else if (headerLen > 0L) {
                            return@withContext ProbeResult(headerLen)
                        }
                    }
                }
            } catch (_: Exception) {}
            ProbeResult(0L)
        }

    suspend fun probeRemoteSize(url: String): Long = probeSizeAndOptionalBytes(url).sizeBytes

    fun buildErrorDetail(code: Int, rawBody: String): String {
        var detail = "HTTP $code"
        try {
            val j = JSONObject(rawBody)
            val errCode = if (!j.isNull("error_code")) j.optString("error_code", "").trim() else ""
            val errKey = if (!j.isNull("error") && j.optJSONObject("error") == null) j.optString("error", "").trim() else ""
            val errDesc = if (!j.isNull("error_description")) j.optString("error_description", "").trim() else ""
            val nestedMsg = j.optJSONObject("error")?.optString("message", "")?.trim().orEmpty()
            val msg = if (!j.isNull("message")) j.optString("message", "").trim() else ""

            val primary = errDesc.ifBlank { nestedMsg }.ifBlank { msg }.ifBlank { errKey }
            val parts = buildList {
                if (errCode.isNotBlank() && errCode != "0") add("[$errCode]")
                if (errKey.isNotBlank() && errKey != primary) add(errKey)
                if (primary.isNotBlank()) add(primary)
            }
            if (parts.isNotEmpty()) {
                detail += ": ${parts.joinToString(" - ")}"
            } else if (rawBody.isNotBlank()) {
                detail += ": ${rawBody.trim().take(140)}"
            }
        } catch (_: Exception) {
            if (rawBody.isNotBlank()) {
                detail += ": ${rawBody.trim().take(140)}"
            }
        }
        return detail
    }
}
