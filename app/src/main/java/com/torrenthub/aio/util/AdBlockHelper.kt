package com.torrenthub.aio.util

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object AdBlockHelper {

    private val BLOCKED_HOST_SUFFIXES = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adnxs.com",
        "exoclick.com",
        "exosrv.com",
        "realsrv.com",
        "syndication.realsrv.com",
        "juicyads.com",
        "jads.co",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "adsterratools.com",
        "highperformanceformat.com",
        "profitablecpmrate.com",
        "tsyndicate.com",
        "tsyndicate.com",
        "pxlcntl.com",
        "clickadu.com",
        "clickaine.com",
        "hilltopads.net",
        "trafficjunky.net",
        "trafficjunky.com",
        "ero-advertising.com",
        "adxpansion.com",
        "plugrush.com",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "revcontent.com",
        "onclkds.com",
        "onclickmax.com",
        "acscdn.com",
        "tsyndicate.com",
        "a-ads.com",
        "sunnyads.com",
        "magsrv.com",
        "bidgear.com",
        "galaksion.com",
        "bidvertiser.com",
        "zeroredirect.com",
        "histats.com",
        "dtscout.com"
    )

    private val BLOCKED_PATH_KEYWORDS = listOf(
        "/popunder",
        "/popup.js",
        "/adserver",
        "/pop.js",
        "/vast.xml",
        "/preroll"
    )

    fun isAdUrl(rawUrl: String, currentPageHost: String = ""): Boolean {
        if (rawUrl.isBlank()) return false
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host.isBlank()) return false

        // Never block main-domain assets of the site currently visited
        val cleanPageHost = currentPageHost.lowercase(Locale.ROOT).removePrefix("www.")
        if (cleanPageHost.isNotEmpty() && (host == cleanPageHost || host.endsWith(".$cleanPageHost"))) {
            val pathLow = uri.path?.lowercase(Locale.ROOT).orEmpty()
            return BLOCKED_PATH_KEYWORDS.any { pathLow.contains(it) }
        }

        if (BLOCKED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) {
            return true
        }

        val fullLow = rawUrl.lowercase(Locale.ROOT)
        return BLOCKED_PATH_KEYWORDS.any { fullLow.contains(it) }
    }

    fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        200,
        "OK",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0))
    )

    const val ANTI_POPUP_AND_COSMETIC_JS = """
        (function() {
            if (window.__thShieldInstalled) return;
            window.__thShieldInstalled = true;
            const origOpen = window.open;
            window.open = function(url) {
                const s = String(url || '');
                if (!s || s === 'about:blank') return null;
                try {
                    const target = new URL(s, location.href);
                    if (target.hostname === location.hostname) {
                        return origOpen ? origOpen.apply(this, arguments) : null;
                    }
                } catch (_) {}
                if (window.__thGmBridge && window.__thGmBridge.onPopupBlocked) {
                    window.__thGmBridge.onPopupBlocked(s);
                }
                return null;
            };
            const hideCss = `
                iframe[src*="exoclick"], iframe[src*="juicyads"], iframe[src*="realsrv"],
                iframe[src*="adsterra"], iframe[src*="doubleclick"], iframe[src*="magsrv"],
                div[id^="exo_"], ins.eas6a97888e2, .exo-native-widget, [class*="popunder"] {
                    display: none !important;
                    visibility: hidden !important;
                    pointer-events: none !important;
                }
            `;
            const applyHide = () => {
                if (document.getElementById('th-adshield-css')) return;
                const st = document.createElement('style');
                st.id = 'th-adshield-css';
                st.textContent = hideCss;
                (document.head || document.documentElement).appendChild(st);
            };
            if (document.documentElement) applyHide();
            else document.addEventListener('DOMContentLoaded', applyHide, { once: true });
        })();
    """
}
