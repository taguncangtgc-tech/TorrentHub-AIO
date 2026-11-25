package com.torrenthub.aio.util

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object AdBlockHelper {

    private val SAFE_ALLOW_SUFFIXES = hashSetOf(
        "cloudflare.com",
        "challenges.cloudflare.com",
        "mypikpak.com",
        "pikpak.me",
        "google.com",
        "gstatic.com",
        "googleapis.com",
        "googleusercontent.com",
        "githubusercontent.com",
        "github.com",
        "jsdelivr.net",
        "cdnjs.cloudflare.com",
        "unpkg.com",
        "greasyfork.org",
        "sleazyfork.org",
        "imdb.com",
        "media-amazon.com",
        "mydramalist.com",
        "whatslink.info",
        "jdbstatic.com",
        "subtitlecat.com",
        "opensubtitles.org",
        "subdl.com"
    )

    private val BLOCKED_DOMAINS = hashSetOf(
        // Major ad networks & trackers
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "2mdn.net",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "indexww.com",
        "smartadserver.com",
        "adform.net",
        "teads.tv",
        "sharethrough.com",
        "spotxchange.com",
        "moatads.com",
        "scorecardresearch.com",
        "quantserve.com",
        "quantcount.com",
        "statcounter.com",
        "histats.com",
        "dtscout.com",
        "hotjar.com",
        "clarity.ms",
        "mc.yandex.ru",
        "an.yandex.ru",
        "cnzz.com",
        "51.la",
        "hm.baidu.com",
        "pos.baidu.com",
        "umeng.com",
        "mmstat.com",
        // Torrent / streaming popup, popunder & malvertising networks
        "exoclick.com",
        "exosrv.com",
        "realsrv.com",
        "syndication.realsrv.com",
        "juicyads.com",
        "jads.co",
        "popads.net",
        "popcash.net",
        "popmyads.com",
        "popunder.net",
        "propellerads.com",
        "monetag.com",
        "adsterra.com",
        "adsterratools.com",
        "highperformanceformat.com",
        "profitablecpmrate.com",
        "effectivegatecpm.com",
        "topcreativeformat.com",
        "tsyndicate.com",
        "pxlcntl.com",
        "clickadu.com",
        "clickaine.com",
        "hilltopads.net",
        "trafficjunky.net",
        "trafficjunky.com",
        "trafficstars.com",
        "ero-advertising.com",
        "adxpansion.com",
        "plugrush.com",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "revcontent.com",
        "onclkds.com",
        "onclickmax.com",
        "onclickalgo.com",
        "acscdn.com",
        "a-ads.com",
        "sunnyads.com",
        "magsrv.com",
        "bidgear.com",
        "galaksion.com",
        "bidvertiser.com",
        "zeroredirect.com",
        "admaven.com",
        "pushground.com",
        "richpush.co",
        "evadav.com",
        "rollerads.com",
        "alfadart.com",
        "rtmark.net",
        "outoctillery.com",
        "Twinrdsyn.com".lowercase(Locale.ROOT),
        "twinrdsyn.com",
        "adsco.re",
        "adinplay.com",
        "adthrive.com",
        "mediavine.com",
        "yllix.com",
        "clksite.com",
        "clkrev.com",
        "adcash.com",
        "ufpcdn.com",
        "cloudfront-ads.com",
        "pushame.com",
        "notix.io"
    )

    private val BLOCKED_PATH_KEYWORDS = listOf(
        "/popunder",
        "/popup.js",
        "/adserver",
        "/pop.js",
        "/vast.xml",
        "/preroll",
        "/adframe",
        "/ads/banner",
        "/pop_under",
        "/clickunder",
        "popunder=",
        "zoneid="
    )

    private fun matchesDomainSet(host: String, domainSet: Set<String>): Boolean {
        var cur = host.lowercase(Locale.ROOT).trimEnd('.')
        while (cur.isNotEmpty()) {
            if (cur in domainSet) return true
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
        }
        return false
    }

    fun extractBaseDomain(rawUrlOrHost: String): String {
        if (rawUrlOrHost.isBlank()) return ""
        val host = if (rawUrlOrHost.contains("://")) {
            try { Uri.parse(rawUrlOrHost).host.orEmpty() } catch (_: Exception) { "" }
        } else {
            rawUrlOrHost
        }.lowercase(Locale.ROOT).removePrefix("www.").trimEnd('.')
        if (host.isBlank()) return ""
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
        val multiTlds = setOf("co.uk", "co.jp", "co.kr", "co.id", "com.cn", "com.tw", "com.hk", "org.uk", "net.au")
        return if (lastTwo in multiTlds && parts.size >= 3) {
            "${parts[parts.size - 3]}.$lastTwo"
        } else {
            lastTwo
        }
    }

    fun isSameBaseDomain(urlA: String, urlB: String): Boolean {
        val domA = extractBaseDomain(urlA)
        val domB = extractBaseDomain(urlB)
        return domA.isNotEmpty() && domA == domB
    }

    fun isAdUrl(rawUrl: String, currentPageHost: String = ""): Boolean {
        if (rawUrl.isBlank()) return false
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host.isBlank()) return false

        if (matchesDomainSet(host, SAFE_ALLOW_SUFFIXES)) {
            return false
        }

        // Never block main-domain assets of the site currently visited unless explicit popup/ad script path
        val cleanPageHost = currentPageHost.lowercase(Locale.ROOT).removePrefix("www.")
        if (cleanPageHost.isNotEmpty() && (host == cleanPageHost || host.endsWith(".$cleanPageHost"))) {
            val pathAndQuery = (uri.path.orEmpty() + "?" + uri.query.orEmpty()).lowercase(Locale.ROOT)
            return BLOCKED_PATH_KEYWORDS.any { pathAndQuery.contains(it) }
        }

        if (matchesDomainSet(host, BLOCKED_DOMAINS)) {
            return true
        }

        val fullLow = rawUrl.lowercase(Locale.ROOT)
        return BLOCKED_PATH_KEYWORDS.any { fullLow.contains(it) }
    }

    fun shouldBlockNavigation(
        targetUrl: String,
        currentPageUrl: String,
        isUserGesture: Boolean,
        isRedirect: Boolean,
        strictAntiPopup: Boolean
    ): Boolean {
        if (targetUrl.isBlank()) return false
        val pageHost = try { Uri.parse(currentPageUrl).host.orEmpty() } catch (_: Exception) { "" }
        if (isAdUrl(targetUrl, pageHost)) return true

        val targetHost = try { Uri.parse(targetUrl).host.orEmpty() } catch (_: Exception) { "" }
        if (targetHost.isBlank() || matchesDomainSet(targetHost, SAFE_ALLOW_SUFFIXES)) return false

        // Block unsolicited background redirects to completely different domains without user gesture
        if (strictAntiPopup && !isUserGesture && isRedirect && currentPageUrl.startsWith("http", ignoreCase = true)) {
            if (!isSameBaseDomain(targetUrl, currentPageUrl)) {
                val low = targetUrl.lowercase(Locale.ROOT)
                if (Regex("(click|track|redir|aff|offer|bet|casino|bonus|pop|adid=|subid=|utm_source=pop)").containsMatchIn(low)) {
                    return true
                }
            }
        }
        return false
    }

    fun shouldBlockPopupWindow(
        targetUrl: String,
        currentPageUrl: String,
        isUserGesture: Boolean,
        strictAntiPopup: Boolean
    ): Boolean {
        if (!isUserGesture) return true
        if (targetUrl.isBlank() || targetUrl.equals("about:blank", ignoreCase = true)) return true
        if (targetUrl.startsWith("javascript:", ignoreCase = true)) return true

        val pageHost = try { Uri.parse(currentPageUrl).host.orEmpty() } catch (_: Exception) { "" }
        if (isAdUrl(targetUrl, pageHost)) return true

        if (strictAntiPopup && currentPageUrl.startsWith("http", ignoreCase = true)) {
            val targetHost = try { Uri.parse(targetUrl).host.orEmpty() } catch (_: Exception) { "" }
            if (targetHost.isNotBlank() &&
                !matchesDomainSet(targetHost, SAFE_ALLOW_SUFFIXES) &&
                !isSameBaseDomain(targetUrl, currentPageUrl)
            ) {
                return true
            }
        }
        return false
    }

    fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        200,
        "OK",
        mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store"
        ),
        ByteArrayInputStream(ByteArray(0))
    )

    const val ANTI_POPUP_AND_COSMETIC_JS = """
        (function() {
            if (window.__thShieldInstalled) return;
            window.__thShieldInstalled = true;
            const notifyBlocked = (u) => {
                try {
                    if (window.__thGmBridge && window.__thGmBridge.onPopupBlocked) {
                        window.__thGmBridge.onPopupBlocked(String(u || 'popup'));
                    }
                } catch (_) {}
            };
            const origOpen = window.open;
            window.open = function(url) {
                const s = String(url || '').trim();
                if (!s || s === 'about:blank' || s.startsWith('javascript:')) {
                    notifyBlocked(s || 'about:blank');
                    return null;
                }
                try {
                    const target = new URL(s, location.href);
                    if (target.hostname === location.hostname) {
                        return origOpen ? origOpen.apply(this, arguments) : null;
                    }
                } catch (_) {}
                notifyBlocked(s);
                return null;
            };
            document.addEventListener('click', function(e) {
                try {
                    let el = e.target;
                    while (el && el !== document.body) {
                        if (el.tagName === 'A') {
                            const href = String(el.getAttribute('href') || '').trim();
                            const tgt = String(el.getAttribute('target') || '').toLowerCase();
                            if (tgt === '_blank' && href && !href.startsWith('magnet:') && !href.startsWith('ed2k:')) {
                                const parsed = new URL(href, location.href);
                                if (parsed.hostname && parsed.hostname !== location.hostname) {
                                    const st = window.getComputedStyle(el);
                                    const isInvisibleOverlay = st.position === 'fixed' && (parseFloat(st.opacity || '1') < 0.08 || parseInt(st.zIndex || '0', 10) > 99999);
                                    if (isInvisibleOverlay || /(exoclick|realsrv|adsterra|popads|popcash|tsyndicate|clickadu|juicyads|monetag|onclick)/i.test(href)) {
                                        e.preventDefault();
                                        e.stopPropagation();
                                        notifyBlocked(href);
                                        return false;
                                    }
                                }
                            }
                            break;
                        }
                        el = el.parentElement;
                    }
                } catch (_) {}
            }, true);
            const hideCss = `
                iframe[src*="exoclick"], iframe[src*="juicyads"], iframe[src*="realsrv"],
                iframe[src*="adsterra"], iframe[src*="doubleclick"], iframe[src*="magsrv"],
                iframe[src*="tsyndicate"], iframe[src*="clickadu"], iframe[src*="a-ads"],
                iframe[src*="trafficjunky"], iframe[src*="popads"], iframe[src*="googlesyndication"],
                div[id^="exo_"], ins.eas6a97888e2, ins.adsbygoogle, .exo-native-widget,
                [class*="popunder"], [id*="popunder"], [class*="ad-banner"], [id*="ad-container"] {
                    display: none !important;
                    visibility: hidden !important;
                    pointer-events: none !important;
                    width: 0 !important;
                    height: 0 !important;
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
