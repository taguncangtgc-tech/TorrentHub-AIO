package com.torrenthub.aio.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.torrenthub.aio.R
import com.torrenthub.aio.data.UserscriptManager
import com.torrenthub.aio.model.UserscriptItem
import com.torrenthub.aio.model.UserscriptMenuCmd
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.AdBlockHelper
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val SWIPE_SCROLL_HINT_JS = """
(function(){
  if (window.__thSwipeHintInstalled) return;
  window.__thSwipeHintInstalled = true;
  document.addEventListener('touchstart', function(e) {
    try {
      var canL = false, canR = false, interactive = false;
      var el = e.target;
      while (el && el !== document.body && el !== document.documentElement) {
        var tag = el.tagName || '';
        if (tag === 'INPUT' && el.type === 'range') { interactive = true; break; }
        var role = el.getAttribute && el.getAttribute('role');
        if (role === 'slider' || role === 'scrollbar') { interactive = true; break; }
        var sw = el.scrollWidth || 0, cw = el.clientWidth || 0;
        if (sw > cw + 24) {
          var ox = window.getComputedStyle(el).overflowX;
          if (ox === 'auto' || ox === 'scroll') {
            if (el.scrollLeft > 4) canL = true;
            if (el.scrollLeft + cw < sw - 4) canR = true;
            if (canL || canR) break;
          }
        }
        el = el.parentElement;
      }
      if (window.__thGmBridge && window.__thGmBridge.onTouchScrollHint) {
        window.__thGmBridge.onTouchScrollHint(canL, canR, interactive);
      }
    } catch (_) {}
  }, { passive: true, capture: true });
})();
"""

class SmartBrowserWebView(context: Context) : WebView(context) {
    private val minScrollableOverflowPx = (24f * resources.displayMetrics.density).toInt()
    private val edgeThresholdPx = (8f * resources.displayMetrics.density).toInt()

    fun canPanHorizontally(dx: Float): Boolean {
        val range = computeHorizontalScrollRange()
        val extent = computeHorizontalScrollExtent()
        if (range - extent <= minScrollableOverflowPx) return false
        val offset = computeHorizontalScrollOffset()
        return if (dx < 0f) {
            (range - extent - offset) > edgeThresholdPx
        } else {
            offset > edgeThresholdPx
        }
    }
}

@SuppressLint("ViewConstructor")
class SmartWebViewContainer(
    context: Context,
    private val holder: BrowserWebViewHolder
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val decisionThreshold = (touchSlop * 0.45f).coerceAtLeast(6f)
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var gestureDecided = false

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Controlled strictly by SmartWebViewContainer gesture arbitration & allowTabSwipe setting.
    }

    private fun setPagerDisallowIntercept(disallow: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!holder.allowTabSwipe) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                    setPagerDisallowIntercept(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setPagerDisallowIntercept(false)
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        val wv = holder.webView
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                gestureDecided = false
                holder.resetTouchScrollHints()
                setPagerDisallowIntercept(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureDecided = true
                setPagerDisallowIntercept(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) {
                    gestureDecided = true
                    setPagerDisallowIntercept(true)
                } else if (!gestureDecided) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)

                    if (absDx <= touchSlop && absDy <= touchSlop && (ev.eventTime - downTime) > 300L) {
                        gestureDecided = true
                        setPagerDisallowIntercept(true)
                    } else if (absDy > decisionThreshold && absDy >= absDx) {
                        gestureDecided = true
                        setPagerDisallowIntercept(true)
                    } else if (absDx > decisionThreshold && absDx > absDy) {
                        val domScrolls = if (dx < 0f) holder.domCanScrollRight else holder.domCanScrollLeft
                        val keepInWebView = (wv?.canPanHorizontally(dx) == true) ||
                            domScrolls ||
                            holder.domIsInteractiveHorizontal
                        gestureDecided = true
                        setPagerDisallowIntercept(keepInWebView)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureDecided = false
                setPagerDisallowIntercept(false)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

class BrowserWebViewHolder {
    var webView: SmartBrowserWebView? = null
    private var containerView: SmartWebViewContainer? = null
    private var docStartScriptHandler: ScriptHandler? = null
    private var adShieldScriptHandler: ScriptHandler? = null
    private var swipeHintScriptHandler: ScriptHandler? = null
    val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var lastAppliedRevision: Int = -1
    var lastAppliedShield: Boolean? = null

    @Volatile var allowTabSwipe: Boolean = true
    @Volatile var domCanScrollLeft: Boolean = false
    @Volatile var domCanScrollRight: Boolean = false
    @Volatile var domIsInteractiveHorizontal: Boolean = false

    var onInterceptMagnet: ((String) -> Unit)? = null
    var onInterceptUserscriptUrl: ((String) -> Unit)? = null

    fun resetTouchScrollHints() {
        domCanScrollLeft = false
        domCanScrollRight = false
        domIsInteractiveHorizontal = false
    }

    fun syncDocumentStartScripts(context: Context, vm: AppViewModel) {
        val wv = webView ?: return
        val supportsDocStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!supportsDocStart) return

        if (swipeHintScriptHandler == null) {
            swipeHintScriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, SWIPE_SCROLL_HINT_JS, setOf("*"))
        }

        if (lastAppliedRevision != vm.userscriptRevision) {
            docStartScriptHandler?.remove()
            val bootstrap = UserscriptManager.buildDocumentBootstrapScript(context.applicationContext, vm.installedUserscripts)
            docStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, bootstrap, setOf("*"))
            lastAppliedRevision = vm.userscriptRevision
        }

        if (lastAppliedShield != vm.browserAdShieldEnabled) {
            adShieldScriptHandler?.remove()
            adShieldScriptHandler = if (vm.browserAdShieldEnabled) {
                WebViewCompat.addDocumentStartJavaScript(wv, AdBlockHelper.ANTI_POPUP_AND_COSMETIC_JS, setOf("*"))
            } else {
                null
            }
            lastAppliedShield = vm.browserAdShieldEnabled
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun getOrCreateContainer(activityContext: Context, vm: AppViewModel): SmartWebViewContainer {
        allowTabSwipe = vm.browserTabSwipeEnabled
        containerView?.let { existingContainer ->
            (existingContainer.parent as? ViewGroup)?.removeView(existingContainer)
            syncDocumentStartScripts(activityContext, vm)
            return existingContainer
        }

        val appCtx = activityContext.applicationContext
        val wv = SmartBrowserWebView(activityContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.userAgentString = HttpClient.DEFAULT_UA

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onTouchScrollHint(canLeft: Boolean, canRight: Boolean, interactive: Boolean) {
                        domCanScrollLeft = canLeft
                        domCanScrollRight = canRight
                        domIsInteractiveHorizontal = interactive
                    }

                    @JavascriptInterface
                    fun onTextSelected(text: String, isCopy: Boolean) {
                        val clean = text.trim()
                        if (clean.length in 2..180) {
                            post {
                                vm.browserSelectedQuery = clean
                                if (isCopy) {
                                    vm.emitToast(vm.str(R.string.toast_text_selected_hint))
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun setValue(scriptId: String, key: String, rawJsonValue: String) {
                        UserscriptManager.setScriptValue(appCtx, scriptId, key, rawJsonValue)
                    }

                    @JavascriptInterface
                    fun deleteValue(scriptId: String, key: String) {
                        UserscriptManager.deleteScriptValue(appCtx, scriptId, key)
                    }

                    @JavascriptInterface
                    fun setClipboard(text: String) {
                        post {
                            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Userscript", text))
                        }
                    }

                    @JavascriptInterface
                    fun openUrlInBrowser(targetUrl: String) {
                        if (targetUrl.startsWith("http://", ignoreCase = true) ||
                            targetUrl.startsWith("https://", ignoreCase = true)
                        ) {
                            post { loadUrl(targetUrl) }
                        }
                    }

                    @JavascriptInterface
                    fun onScriptActivated(scriptId: String, scriptName: String) {
                        post {
                            if (scriptName !in vm.activePageScriptNames) {
                                vm.activePageScriptNames.add(scriptName)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun registerMenuCommand(scriptId: String, scriptName: String, cmdId: String, caption: String) {
                        post {
                            if (vm.activePageMenuCommands.none { it.cmdId == cmdId }) {
                                vm.activePageMenuCommands.add(
                                    UserscriptMenuCmd(scriptId, scriptName, cmdId, caption)
                                )
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onPopupBlocked(blockedUrl: String) {
                        post { vm.browserBlockedAdsCount++ }
                    }

                    @JavascriptInterface
                    fun xmlHttpRequest(reqId: String, optionsJson: String, pageUrl: String) {
                        bridgeScope.launch {
                            val resJson = UserscriptManager.executeGmXhr(optionsJson, pageUrl)
                            withContext(Dispatchers.Main) {
                                val js = "window.__thGmOnXhrDone && window.__thGmOnXhrDone(${JSONObject.quote(reqId)}, ${JSONObject.quote(resJson)});"
                                evaluateJavascript(js, null)
                            }
                        }
                    }
                },
                "__thGmBridge"
            )

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    vm.browserProgress = newProgress
                    vm.browserIsLoading = newProgress in 1..99
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    vm.browserPageTitle = title.orEmpty()
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    if (view == null) return false
                    val hit = view.hitTestResult
                    val targetUrl = hit.extra.orEmpty()
                    if (vm.browserAdShieldEnabled) {
                        val pageHost = try { Uri.parse(view.url.orEmpty()).host.orEmpty() } catch (_: Exception) { "" }
                        if (!isUserGesture || targetUrl.isBlank() || AdBlockHelper.isAdUrl(targetUrl, pageHost)) {
                            vm.browserBlockedAdsCount++
                            return false
                        }
                    }
                    if (targetUrl.startsWith("http://", ignoreCase = true) ||
                        targetUrl.startsWith("https://", ignoreCase = true)
                    ) {
                        view.loadUrl(targetUrl)
                    }
                    return false
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request != null && vm.browserAdShieldEnabled) {
                        val reqUrl = request.url?.toString().orEmpty()
                        val pageHost = try { Uri.parse(vm.browserCurrentUrl).host.orEmpty() } catch (_: Exception) { "" }
                        if (AdBlockHelper.isAdUrl(reqUrl, pageHost)) {
                            view?.post { vm.browserBlockedAdsCount++ }
                            return AdBlockHelper.emptyResponse()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (url.isBlank()) return false

                    if (url.startsWith("magnet:", ignoreCase = true) || url.startsWith("ed2k://", ignoreCase = true)) {
                        onInterceptMagnet?.invoke(url)
                        return true
                    }

                    val cleanPath = url.substringBefore('?').substringBefore('#')
                    if (cleanPath.endsWith(".user.js", ignoreCase = true)) {
                        onInterceptUserscriptUrl?.invoke(url)
                        return true
                    }

                    if (!url.startsWith("http://", ignoreCase = true) &&
                        !url.startsWith("https://", ignoreCase = true)
                    ) {
                        if (vm.browserAdShieldEnabled) {
                            vm.browserBlockedAdsCount++
                        }
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    vm.activePageScriptNames.clear()
                    vm.activePageMenuCommands.clear()
                    if (!url.isNullOrBlank()) {
                        vm.saveBrowserLastUrl(url)
                    }
                    vm.browserCanGoBack = view?.canGoBack() == true
                    vm.browserCanGoForward = view?.canGoForward() == true

                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && view != null) {
                        view.evaluateJavascript(SWIPE_SCROLL_HINT_JS, null)
                        if (vm.browserAdShieldEnabled) {
                            view.evaluateJavascript(AdBlockHelper.ANTI_POPUP_AND_COSMETIC_JS, null)
                        }
                        val bootstrap = UserscriptManager.buildDocumentBootstrapScript(appCtx, vm.installedUserscripts)
                        view.evaluateJavascript(bootstrap, null)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        vm.saveBrowserLastUrl(url)
                    }
                    vm.browserIsLoading = false
                    vm.browserCanGoBack = view?.canGoBack() == true
                    vm.browserCanGoForward = view?.canGoForward() == true

                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && view != null) {
                        view.evaluateJavascript(SWIPE_SCROLL_HINT_JS, null)
                        val bootstrap = UserscriptManager.buildDocumentBootstrapScript(appCtx, vm.installedUserscripts)
                        view.evaluateJavascript(bootstrap, null)
                    }
                }
            }
        }

        val container = SmartWebViewContainer(activityContext, this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(wv)
        }

        webView = wv
        containerView = container
        syncDocumentStartScripts(activityContext, vm)
        wv.loadUrl(vm.browserCurrentUrl)
        return container
    }

    fun destroy() {
        bridgeScope.cancel()
        containerView?.removeAllViews()
        containerView = null
        webView?.destroy()
        webView = null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(vm: AppViewModel, holder: BrowserWebViewHolder, isActive: Boolean = true) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val activityContext = LocalView.current.context
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var addressInput by remember(vm.browserCurrentUrl) { mutableStateOf(vm.browserCurrentUrl) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showUserscriptsDialog by remember { mutableStateOf(false) }
    var showDetectDialog by remember { mutableStateOf(false) }
    var detectedCandidates by remember { mutableStateOf<List<String>>(emptyList()) }

    var interceptedMagnet by remember { mutableStateOf<String?>(null) }
    var pendingInstallScriptCode by remember { mutableStateOf<String?>(null) }
    var pendingInstallScriptMeta by remember { mutableStateOf<UserscriptItem?>(null) }
    var pendingInstallSourceUrl by remember { mutableStateOf("") }

    SideEffect {
        holder.allowTabSwipe = vm.browserTabSwipeEnabled
    }

    DisposableEffect(holder) {
        holder.onInterceptMagnet = { mag -> interceptedMagnet = mag }
        holder.onInterceptUserscriptUrl = { scriptUrl ->
            scope.launch {
                vm.emitToast(vm.str(R.string.toast_checking_script))
                try {
                    val code = HttpClient.getString(scriptUrl, timeoutSec = 25L)
                    if (code.isNotBlank()) {
                        pendingInstallSourceUrl = scriptUrl
                        pendingInstallScriptCode = code
                        pendingInstallScriptMeta = UserscriptManager.parseMetadata(code, scriptUrl)
                    }
                } catch (e: Exception) {
                    vm.emitToast(vm.str(R.string.toast_download_failed, e.message ?: "Error"))
                }
            }
        }
        onDispose {}
    }

    DisposableEffect(context, isActive) {
        if (!isActive) {
            return@DisposableEffect onDispose {}
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            if (text.length in 2..180 &&
                !text.startsWith("http://", ignoreCase = true) &&
                !text.startsWith("https://", ignoreCase = true) &&
                !text.startsWith("magnet:", ignoreCase = true)
            ) {
                vm.browserSelectedQuery = text
            }
        }
        cm.addPrimaryClipChangedListener(listener)
        onDispose {
            cm.removePrimaryClipChangedListener(listener)
        }
    }

    LaunchedEffect(vm.userscriptRevision, vm.browserAdShieldEnabled) {
        holder.syncDocumentStartScripts(activityContext, vm)
    }

    BackHandler(enabled = isActive && vm.browserCanGoBack) {
        holder.webView?.goBack()
    }

    fun navigateInput(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        focusManager.clearFocus()
        val finalUrl = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            !trimmed.contains(' ') && trimmed.contains('.') -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Formatters.urlEncode(trimmed)}"
        }
        holder.webView?.loadUrl(finalUrl)
    }

    fun runOneClickPageDetector() {
        val wv = holder.webView
        val fallbackList = mutableListOf<String>()
        Formatters.extractVideoCode(vm.browserCurrentUrl).takeIf { it.isNotBlank() }?.let { fallbackList.add(it) }
        Formatters.extractVideoCode(vm.browserPageTitle).takeIf { it.isNotBlank() && it !in fallbackList }?.let { fallbackList.add(it) }
        vm.browserPageTitle
            .replace(Regex("\\s*[-|–—·•]\\s*(Watch|Free|Online|IMDb|JavDB|MissAV|MyDramaList|YouTube|Google|Wikipedia).*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.length in 2..120 && it !in fallbackList }
            ?.let { fallbackList.add(it) }

        if (wv == null) {
            detectedCandidates = fallbackList
            showDetectDialog = true
            return
        }

        wv.evaluateJavascript("window.__thDetectPageCandidates ? window.__thDetectPageCandidates() : '[]'") { raw ->
            val list = mutableListOf<String>()
            try {
                val jsonStr = org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotBlank() && s !in list) list.add(s)
                }
            } catch (_: Exception) {}
            fallbackList.forEach { if (it !in list) list.add(it) }
            detectedCandidates = list
            showDetectDialog = true
        }
    }

    val importScriptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                if (content.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        vm.installOrUpdateUserscript(content)
                        holder.webView?.reload()
                    }
                }
            } catch (e: Exception) {
                vm.emitToast(vm.str(R.string.toast_read_file_failed, e.message ?: "Error"))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Compact Address & Toolbar
            Surface(color = p.barSurface) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = { holder.webView?.goBack() },
                            enabled = vm.browserCanGoBack,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), modifier = Modifier.size(19.dp))
                        }

                        OutlinedTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.browser_url_placeholder), fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigateInput(addressInput) }),
                            trailingIcon = {
                                if (addressInput.isNotEmpty()) {
                                    IconButton(onClick = { addressInput = "" }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear), modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp)
                        )

                        IconButton(
                            onClick = {
                                if (vm.browserIsLoading) holder.webView?.stopLoading()
                                else holder.webView?.reload()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                if (vm.browserIsLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh),
                                modifier = Modifier.size(19.dp),
                                tint = p.textPrimary
                            )
                        }

                        IconButton(
                            onClick = { vm.showSettingsDialog = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                                modifier = Modifier.size(19.dp),
                                tint = p.textPrimary
                            )
                        }
                    }

                    // Second compact action strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF0F766E),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { runOneClickPageDetector() }
                            ) {
                                Text(
                                    stringResource(R.string.browser_btn_detect),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }

                            Surface(
                                color = p.subCardSurface,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, p.borderColor),
                                modifier = Modifier.clickable { showBookmarksDialog = true }
                            ) {
                                Text(
                                    stringResource(R.string.browser_btn_bookmark),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.accentAmber,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val activeCount = vm.activePageScriptNames.size
                            Surface(
                                color = if (activeCount > 0) Color(0xFF4C1D95) else p.subCardSurface,
                                shape = RoundedCornerShape(8.dp),
                                border = if (activeCount > 0) null else BorderStroke(1.dp, p.borderColor),
                                modifier = Modifier.clickable { showUserscriptsDialog = true }
                            ) {
                                Text(
                                    stringResource(R.string.browser_btn_script) + (if (activeCount > 0) " ($activeCount)" else ""),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeCount > 0) Color(0xFFE9D5FF) else p.textPrimary,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }

                            Surface(
                                color = if (vm.browserAdShieldEnabled) Color(0xFF064E3B) else p.chipBg,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    vm.toggleBrowserAdShield(!vm.browserAdShieldEnabled)
                                    holder.webView?.reload()
                                }
                            ) {
                                Text(
                                    if (vm.browserAdShieldEnabled) "🛡️ ${vm.browserBlockedAdsCount}" else "🛡️ OFF",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (vm.browserAdShieldEnabled) Color(0xFF6EE7B7) else p.textMuted,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    if (vm.browserIsLoading) {
                        LinearProgressIndicator(
                            progress = { (vm.browserProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = p.accentBlue,
                            trackColor = Color.Transparent
                        )
                    } else {
                        HorizontalDivider(color = p.borderColor, thickness = 1.dp)
                    }
                }
            }

            // Persistent WebView wrapped in SmartWebViewContainer for Pager swipe arbitration
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { holder.getOrCreateContainer(activityContext, vm) }
            )
        }

        // Floating Action Card when user selects or copies movie title / code!
        val selText = vm.browserSelectedQuery.trim()
        if (selText.isNotEmpty()) {
            val extractedCode = remember(selText) { Formatters.extractVideoCode(selText) }
            Surface(
                color = p.cardSurface.copy(alpha = 0.98f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, p.borderColor),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.browser_selected_label),
                                fontSize = 10.5.sp,
                                color = p.accentBlue,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                selText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = p.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { vm.browserSelectedQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textMuted, modifier = Modifier.size(18.dp))
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { vm.searchTorrentFromBrowser(selText) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.browser_btn_search_torrent), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        if (extractedCode.isNotBlank() && !extractedCode.equals(selText, ignoreCase = true)) {
                            Button(
                                onClick = { vm.searchTorrentFromBrowser(extractedCode) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(R.string.browser_btn_search_code, extractedCode), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        OutlinedButton(
                            onClick = { vm.searchSubtitleFromBrowser(extractedCode.ifBlank { selText }) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.browser_btn_search_sub), fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }
    }

    // 1-Click Detect Code / Title Dialog
    if (showDetectDialog) {
        Dialog(onDismissRequest = { showDetectDialog = false }) {
            Surface(color = p.barSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, p.borderColor)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.browser_detect_title), fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = p.textPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showDetectDialog = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                        }
                    }

                    if (detectedCandidates.isEmpty()) {
                        Text(
                            stringResource(R.string.browser_detect_empty),
                            fontSize = 12.sp,
                            color = p.textMuted
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(detectedCandidates) { cand ->
                                Surface(color = p.cardSurface, shape = RoundedCornerShape(10.dp), border = p.cardBorder) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(cand, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = p.textPrimary)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    showDetectDialog = false
                                                    vm.searchTorrentFromBrowser(cand)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(stringResource(R.string.browser_btn_search_torrent_short), fontSize = 11.5.sp, color = Color.White)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    showDetectDialog = false
                                                    vm.searchSubtitleFromBrowser(cand)
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(stringResource(R.string.browser_btn_search_sub), fontSize = 11.5.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Bookmarks Dialog
    if (showBookmarksDialog) {
        Dialog(onDismissRequest = { showBookmarksDialog = false }) {
            Surface(color = p.barSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, p.borderColor)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.browser_bookmarks_title), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = p.textPrimary)
                        IconButton(onClick = { showBookmarksDialog = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                vm.addBrowserBookmark(
                                    title = vm.browserPageTitle.ifBlank { vm.browserCurrentUrl },
                                    url = vm.browserCurrentUrl
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.browser_btn_save_page), fontSize = 12.sp, color = Color.White)
                        }
                        if (vm.browserCanGoForward) {
                            OutlinedButton(onClick = {
                                holder.webView?.goForward()
                                showBookmarksDialog = false
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.cd_forward), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(vm.browserBookmarks, key = { it.url }) { bm ->
                            Surface(
                                color = p.cardSurface,
                                shape = RoundedCornerShape(8.dp),
                                border = p.cardBorder,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBookmarksDialog = false
                                        holder.webView?.loadUrl(bm.url)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(bm.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(bm.url, fontSize = 11.sp, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(
                                        onClick = { vm.removeBrowserBookmark(bm.url) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = p.accentRed, modifier = Modifier.size(17.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Tampermonkey / Userscripts Manager Dialog
    if (showUserscriptsDialog) {
        UserscriptManagerDialog(
            vm = vm,
            onDismiss = { showUserscriptsDialog = false },
            onImportFile = { importScriptLauncher.launch(arrayOf("*/*")) },
            onInvokeMenuCmd = { cmdId ->
                showUserscriptsDialog = false
                holder.webView?.evaluateJavascript(
                    "window.__thGmInvokeMenu && window.__thGmInvokeMenu(${JSONObject.quote(cmdId)});",
                    null
                )
            },
            onReloadWebView = { holder.webView?.reload() }
        )
    }

    // Auto-Intercepted .user.js Install Confirmation Dialog
    if (pendingInstallScriptMeta != null && pendingInstallScriptCode != null) {
        val meta = pendingInstallScriptMeta!!
        AlertDialog(
            onDismissRequest = {
                pendingInstallScriptMeta = null
                pendingInstallScriptCode = null
            },
            title = { Text(stringResource(R.string.browser_install_script_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${meta.name} (v${meta.version})", fontWeight = FontWeight.Bold, color = p.accentBlue)
                    if (meta.description.isNotBlank()) {
                        Text(meta.description, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(R.string.browser_install_script_target, meta.matches.take(3).joinToString(", ")), fontSize = 11.sp, color = p.textMuted)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val code = pendingInstallScriptCode.orEmpty()
                        val src = pendingInstallSourceUrl
                        pendingInstallScriptMeta = null
                        pendingInstallScriptCode = null
                        vm.installOrUpdateUserscript(code, src)
                        holder.webView?.reload()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text(stringResource(R.string.browser_btn_install_now), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingInstallScriptMeta = null
                    pendingInstallScriptCode = null
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Intercepted Magnet / ED2K Link Dialog inside Browser
    if (interceptedMagnet != null) {
        val mag = interceptedMagnet!!
        AlertDialog(
            onDismissRequest = { interceptedMagnet = null },
            title = { Text(stringResource(R.string.browser_magnet_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(mag, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            interceptedMagnet = null
                            vm.sendMagnetsToPikPak(listOf(mag))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text(stringResource(R.string.browser_btn_to_pikpak), fontSize = 12.sp, color = Color.White)
                    }
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Magnet", mag))
                            interceptedMagnet = null
                            vm.emitToast(vm.str(R.string.toast_link_copied, mag.take(40)))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text("📋 ${stringResource(R.string.common_copy)}", fontSize = 12.sp, color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    interceptedMagnet = null
                    try {
                        val extIntent = Intent(Intent.ACTION_VIEW, Uri.parse(mag)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(extIntent)
                    } catch (_: Exception) {
                        vm.emitToast(vm.str(R.string.toast_no_torrent_app))
                    }
                }) {
                    Text(stringResource(R.string.browser_btn_external_app))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserscriptManagerDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onImportFile: () -> Unit,
    onInvokeMenuCmd: (String) -> Unit,
    onReloadWebView: () -> Unit
) {
    val p = AppTheme.palette
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var showPasteEditor by remember { mutableStateOf(false) }
    var pasteCodeInput by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.userscript_title), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = p.textPrimary)
                        Text(
                            stringResource(R.string.userscript_subtitle),
                            fontSize = 11.sp,
                            color = p.textMuted
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onImportFile,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.userscript_btn_import), fontSize = 11.5.sp, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = { showUrlInput = !showUrlInput; showPasteEditor = false },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.userscript_btn_from_url), fontSize = 11.5.sp)
                    }
                    OutlinedButton(
                        onClick = { showPasteEditor = !showPasteEditor; showUrlInput = false },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.userscript_btn_paste), fontSize = 11.5.sp)
                    }
                }

                if (showUrlInput) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            placeholder = { Text("https://.../script.user.js", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )
                        Button(
                            onClick = {
                                vm.installUserscriptFromUrl(urlInput) {
                                    urlInput = ""
                                    showUrlInput = false
                                    onReloadWebView()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text(stringResource(R.string.userscript_btn_install), fontSize = 12.sp, color = Color.White)
                        }
                    }
                }

                if (showPasteEditor) {
                    OutlinedTextField(
                        value = pasteCodeInput,
                        onValueChange = { pasteCodeInput = it },
                        minLines = 4,
                        maxLines = 7,
                        placeholder = { Text("// ==UserScript==\n// @name My Script\n...", fontSize = 11.5.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                    )
                    Button(
                        onClick = {
                            vm.installOrUpdateUserscript(pasteCodeInput)
                            pasteCodeInput = ""
                            showPasteEditor = false
                            onReloadWebView()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text(stringResource(R.string.userscript_btn_save_install), color = Color.White)
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (vm.activePageMenuCommands.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.userscript_menu_cmds, vm.activePageMenuCommands.size),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = p.accentBlue
                            )
                        }
                        items(vm.activePageMenuCommands, key = { it.cmdId }) { cmd ->
                            Surface(
                                color = p.cardSurface,
                                shape = RoundedCornerShape(8.dp),
                                border = p.cardBorder,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onInvokeMenuCmd(cmd.cmdId) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(cmd.caption, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = p.textPrimary)
                                        Text(cmd.scriptName, fontSize = 10.5.sp, color = p.textMuted)
                                    }
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = p.accentGreen)
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            stringResource(R.string.userscript_installed_header, vm.installedUserscripts.size),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.textPrimary
                        )
                    }

                    if (vm.installedUserscripts.isEmpty()) {
                        item {
                            Surface(color = p.cardSurface, shape = RoundedCornerShape(10.dp), border = p.cardBorder) {
                                Text(
                                    stringResource(R.string.userscript_empty_tips),
                                    fontSize = 12.sp,
                                    color = p.textMuted,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    } else {
                        items(vm.installedUserscripts, key = { it.id }) { sc ->
                            val isActiveHere = sc.name in vm.activePageScriptNames
                            Surface(color = p.cardSurface, shape = RoundedCornerShape(10.dp), border = p.cardBorder) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (isActiveHere) {
                                                    Box(Modifier.size(8.dp).background(p.accentGreen, CircleShape))
                                                }
                                                Text(
                                                    "${sc.name} (v${sc.version})",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = p.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                "Run-at: ${sc.runAt} · ${Formatters.formatBytes(sc.codeSize.toLong())}",
                                                fontSize = 10.5.sp,
                                                color = p.textMuted
                                            )
                                        }
                                        Switch(
                                            checked = sc.enabled,
                                            onCheckedChange = {
                                                vm.toggleUserscript(sc.id, it)
                                                onReloadWebView()
                                            }
                                        )
                                    }
                                    if (sc.description.isNotBlank()) {
                                        Text(sc.description, fontSize = 11.5.sp, color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            sc.matches.take(2).joinToString(", "),
                                            fontSize = 10.5.sp,
                                            color = p.textMuted,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        TextButton(
                                            onClick = {
                                                vm.deleteUserscript(sc.id)
                                                onReloadWebView()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(stringResource(R.string.common_delete), fontSize = 11.5.sp, color = p.accentRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
