package com.torrenthub.aio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import com.torrenthub.aio.data.SettingsRepository
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.ui.*
import com.torrenthub.aio.util.AppSecurityHelper
import com.torrenthub.aio.util.ExternalPlayerHelper
import com.torrenthub.aio.util.LocaleHelper
import com.torrenthub.aio.util.NotificationHelper
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {

    private var activeBrowserHolder: BrowserWebViewHolder? = null
    private var backgroundedAtMs: Long = 0L

    override fun attachBaseContext(newBase: Context) {
        val repo = SettingsRepository.getInstance(newBase)
        HttpClient.applyDohProvider(repo.dohProvider)
        val langCode = repo.appLanguage.code
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, langCode))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_DOWNLOADS, false)) {
            val vm = ViewModelProvider(this)[AppViewModel::class.java]
            vm.showDownloadManagerDialog = true
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_DOWNLOADS)
        }
    }

    override fun onStart() {
        super.onStart()
        val stoppedAt = backgroundedAtMs
        backgroundedAtMs = 0L
        if (stoppedAt > 0L && (System.currentTimeMillis() - stoppedAt) >= 60_000L) {
            val vm = ViewModelProvider(this)[AppViewModel::class.java]
            if (vm.appLockEnabled) {
                vm.lockSessionNow()
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            backgroundedAtMs = System.currentTimeMillis()
        }
        if (isFinishing) {
            performExitAutoClearIfEnabled()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            performExitAutoClearIfEnabled()
        }
        activeBrowserHolder = null
        super.onDestroy()
    }

    private fun performExitAutoClearIfEnabled() {
        val repo = SettingsRepository.getInstance(applicationContext)
        if (repo.autoClearOnExit) {
            runCatching {
                val vm = ViewModelProvider(this)[AppViewModel::class.java]
                vm.clearSearchAndBrowserHistoryNow(
                    webView = activeBrowserHolder?.webView,
                    silent = true,
                    blockingPrefs = true
                )
            }.onFailure {
                repo.clearPersistedSearchAndBrowserHistory(blocking = true)
                AppSecurityHelper.clearWebViewSessionAndHistory(applicationContext, activeBrowserHolder?.webView)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository.getInstance(applicationContext)
        HttpClient.applyDohProvider(repo.dohProvider)
        if (savedInstanceState == null && repo.autoClearOnExit) {
            repo.clearPersistedSearchAndBrowserHistory(blocking = false)
            AppSecurityHelper.clearWebViewSessionAndHistory(applicationContext, null)
        }
        Coil.setImageLoader(HttpClient.createImageLoader(applicationContext))
        enableEdgeToEdge()
        val openDownloadsInitial = intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_DOWNLOADS, false) == true
        if (openDownloadsInitial) {
            intent?.removeExtra(NotificationHelper.EXTRA_OPEN_DOWNLOADS)
        }
        setContent {
            val activity = this@MainActivity
            val vm: AppViewModel = viewModel(viewModelStoreOwner = activity)
            val useSecureFlag = vm.disguiseModeEnabled || vm.isAppLockedNow
            LaunchedEffect(useSecureFlag) {
                if (useSecureFlag) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            LaunchedEffect(openDownloadsInitial) {
                if (openDownloadsInitial) {
                    vm.showDownloadManagerDialog = true
                }
            }
            val localizedContext = remember(activity, vm.appLanguage) {
                LocaleHelper.wrapActivityContext(activity, vm.appLanguage.code)
            }
            val localizedConfig = remember(localizedContext, vm.appLanguage) {
                Configuration(localizedContext.resources.configuration)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig,
                LocalActivityResultRegistryOwner provides activity,
                LocalOnBackPressedDispatcherOwner provides activity,
                LocalLifecycleOwner provides activity,
                LocalViewModelStoreOwner provides activity
            ) {
                TorrentHubTheme(themeMode = vm.themeMode) {
                    if (vm.isAppLockedNow) {
                        AppLockScreen(
                            vm = vm,
                            onExitApp = { activity.finish() }
                        )
                    } else {
                        MainAppShell(
                            vm = vm,
                            onBrowserHolderBound = { activeBrowserHolder = it }
                        )
                    }
                }
            }
        }
    }
}

// Visual left-to-right order matching Bottom Navigation Bar:
// 0 = Torrent, 4 = Browser, 1 = Subtitle, 2 = Gemini, 3 = PikPak
private val TAB_ORDER = intArrayOf(0, 4, 1, 2, 3)

private fun tabToPage(tabId: Int): Int = TAB_ORDER.indexOf(tabId).coerceAtLeast(0)
private fun pageToTab(page: Int): Int = TAB_ORDER.getOrElse(page) { 0 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageWithHeader(
    title: String,
    activeDownloadsCount: Int,
    showLockAction: Boolean,
    onLockNow: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    val p = AppTheme.palette
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            actions = {
                if (showLockAction) {
                    IconButton(onClick = onLockNow) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.cd_lock_app),
                            tint = p.textPrimary
                        )
                    }
                }
                IconButton(onClick = onOpenDownloads) {
                    BadgedBox(
                        badge = {
                            if (activeDownloadsCount > 0) {
                                Badge(containerColor = p.accentBlue) {
                                    Text(activeDownloadsCount.toString(), color = Color.White)
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(R.string.cd_downloads),
                            tint = if (activeDownloadsCount > 0) p.accentBlue else p.textPrimary
                        )
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = p.textPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = p.barSurface,
                titleContentColor = p.textPrimary,
                actionIconContentColor = p.textPrimary
            )
        )
        if (p.isLight || p.isAmoled) {
            HorizontalDivider(color = p.borderColor, thickness = 1.dp)
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppShell(
    vm: AppViewModel = viewModel(),
    onBrowserHolderBound: (BrowserWebViewHolder?) -> Unit = {}
) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val browserHolder = remember { BrowserWebViewHolder() }
    val focusManager = LocalFocusManager.current
    val pagerState = rememberPagerState(
        initialPage = tabToPage(vm.currentTab),
        pageCount = { TAB_ORDER.size }
    )

    var lastSyncedPage by remember { mutableIntStateOf(pagerState.currentPage) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    DisposableEffect(browserHolder) {
        onBrowserHolderBound(browserHolder)
        onDispose {
            onBrowserHolderBound(null)
            browserHolder.destroy()
        }
    }

    LaunchedEffect(vm.browserTabSwipeEnabled) {
        browserHolder.allowTabSwipe = vm.browserTabSwipeEnabled
    }

    LaunchedEffect(Unit) {
        vm.toastFlow.collect { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(context) {
        vm.videoPlayFlow.collect { ev ->
            ExternalPlayerHelper.openVideoWithChooser(
                context = context,
                streamUrl = ev.streamUrl,
                videoTitle = ev.videoTitle,
                vm = vm
            )
        }
    }

    // Two-way sync: programmatic tab switches -> Pager
    LaunchedEffect(vm.currentTab) {
        val targetPage = tabToPage(vm.currentTab)
        if (targetPage != lastSyncedPage) {
            lastSyncedPage = targetPage
            focusManager.clearFocus()
            isProgrammaticScroll = true
            try {
                pagerState.animateScrollToPage(targetPage)
            } finally {
                isProgrammaticScroll = false
                val actualPage = pagerState.currentPage
                if (tabToPage(vm.currentTab) == targetPage && actualPage != targetPage) {
                    lastSyncedPage = actualPage
                    vm.currentTab = pageToTab(actualPage)
                }
            }
        }
    }

    // Two-way sync: user horizontal swipe on Pager -> vm.currentTab & Bottom Bar indicator
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (!isProgrammaticScroll && lastSyncedPage != page) {
                lastSyncedPage = page
                vm.currentTab = pageToTab(page)
                focusManager.clearFocus()
            }
        }
    }

    // Fallback Back handler
    BackHandler(enabled = vm.currentTab != 0) {
        vm.currentTab = 0
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    val openSettings = { vm.showSettingsDialog = true }
    val openDownloads = { vm.showDownloadManagerDialog = true }
    val activeDlCount = vm.activeDownloadsCount

    Scaffold(
        containerColor = p.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (p.isLight || p.isAmoled) {
                    HorizontalDivider(color = p.borderColor, thickness = 1.dp)
                }
                NavigationBar(containerColor = p.barSurface) {
                    NavigationBarItem(
                        selected = vm.currentTab == 0,
                        onClick = { vm.currentTab = 0 },
                        icon = { Icon(Icons.Default.TravelExplore, contentDescription = stringResource(R.string.nav_torrent)) },
                        label = { Text(stringResource(R.string.nav_torrent), fontSize = 10.5.sp, maxLines = 1) }
                    )
                    NavigationBarItem(
                        selected = vm.currentTab == 4,
                        onClick = { vm.currentTab = 4 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (vm.activePageScriptNames.isNotEmpty()) {
                                        Badge(containerColor = Color(0xFF7C3AED)) {
                                            Text(vm.activePageScriptNames.size.toString(), color = Color.White)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Public, contentDescription = stringResource(R.string.nav_browser))
                            }
                        },
                        label = { Text(stringResource(R.string.nav_browser), fontSize = 10.5.sp, maxLines = 1) }
                    )
                    NavigationBarItem(
                        selected = vm.currentTab == 1,
                        onClick = { vm.currentTab = 1 },
                        icon = { Icon(Icons.Default.Subtitles, contentDescription = stringResource(R.string.nav_subtitle)) },
                        label = { Text(stringResource(R.string.nav_subtitle), fontSize = 10.5.sp, maxLines = 1) }
                    )
                    NavigationBarItem(
                        selected = vm.currentTab == 2,
                        onClick = { vm.currentTab = 2 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (vm.geminiJobs.isNotEmpty()) {
                                        Badge { Text(vm.geminiJobs.size.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.nav_gemini))
                            }
                        },
                        label = { Text(stringResource(R.string.nav_gemini), fontSize = 10.5.sp, maxLines = 1) }
                    )
                    NavigationBarItem(
                        selected = vm.currentTab == 3,
                        onClick = { vm.currentTab = 3 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (vm.pikpakCreds.isLoggedIn) {
                                        Badge(containerColor = Color(0xFF22C55E))
                                    }
                                }
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.nav_pikpak))
                            }
                        },
                        label = { Text(stringResource(R.string.nav_pikpak), fontSize = 10.5.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !(vm.currentTab == 4 && !vm.browserTabSwipeEnabled),
            beyondViewportPageCount = TAB_ORDER.size - 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) { page ->
            val disguisedTitle = stringResource(R.string.disguise_app_title)
            val showLockBtn = vm.appLockEnabled
            when (pageToTab(page)) {
                0 -> PageWithHeader(
                    title = if (vm.disguiseModeEnabled) disguisedTitle else stringResource(R.string.header_torrent),
                    activeDownloadsCount = activeDlCount,
                    showLockAction = showLockBtn,
                    onLockNow = { vm.lockSessionNow() },
                    onOpenDownloads = openDownloads,
                    onOpenSettings = openSettings
                ) {
                    TorrentScreen(vm)
                }
                4 -> Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(p.barSurface)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        BrowserScreen(vm = vm, holder = browserHolder, isActive = vm.currentTab == 4)
                    }
                }
                1 -> PageWithHeader(
                    title = if (vm.disguiseModeEnabled) disguisedTitle else stringResource(R.string.header_subtitle),
                    activeDownloadsCount = activeDlCount,
                    showLockAction = showLockBtn,
                    onLockNow = { vm.lockSessionNow() },
                    onOpenDownloads = openDownloads,
                    onOpenSettings = openSettings
                ) {
                    SubtitleScreen(vm)
                }
                2 -> PageWithHeader(
                    title = if (vm.disguiseModeEnabled) disguisedTitle else stringResource(R.string.header_gemini),
                    activeDownloadsCount = activeDlCount,
                    showLockAction = showLockBtn,
                    onLockNow = { vm.lockSessionNow() },
                    onOpenDownloads = openDownloads,
                    onOpenSettings = openSettings
                ) {
                    GeminiScreen(vm)
                }
                3 -> PageWithHeader(
                    title = if (vm.disguiseModeEnabled) disguisedTitle else stringResource(R.string.header_pikpak),
                    activeDownloadsCount = activeDlCount,
                    showLockAction = showLockBtn,
                    onLockNow = { vm.lockSessionNow() },
                    onOpenDownloads = openDownloads,
                    onOpenSettings = openSettings
                ) {
                    PikPakScreen(vm = vm, isActive = vm.currentTab == 3)
                }
            }
        }
    }

    if (vm.showSettingsDialog) {
        SettingsDialog(
            vm = vm,
            activeWebView = browserHolder.webView,
            onClearHistoryRequested = {
                vm.clearSearchAndBrowserHistoryNow(webView = browserHolder.webView)
            },
            onDismiss = { vm.showSettingsDialog = false }
        )
    }

    if (vm.showDownloadManagerDialog) {
        DownloadManagerDialog(
            vm = vm,
            onDismiss = { vm.showDownloadManagerDialog = false }
        )
    }
}
