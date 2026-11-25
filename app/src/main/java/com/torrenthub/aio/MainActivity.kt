package com.torrenthub.aio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Coil.setImageLoader(HttpClient.createImageLoader(applicationContext))
        enableEdgeToEdge()
        setContent {
            TorrentHubTheme {
                MainAppShell()
            }
        }
    }
}

private val AppDarkColorScheme = darkColorScheme(
    primary = Color(0xFF14B8A6),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59),
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF0B1120),
    surface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFF1E293B),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC)
)

@Composable
fun TorrentHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppDarkColorScheme,
        content = content
    )
}

// Visual left-to-right order matching Bottom Navigation Bar:
// 0 = Torrent, 4 = Browser, 1 = Subtitle, 2 = Gemini, 3 = PikPak
private val TAB_ORDER = intArrayOf(0, 4, 1, 2, 3)

private fun tabToPage(tabId: Int): Int = TAB_ORDER.indexOf(tabId).coerceAtLeast(0)
private fun pageToTab(page: Int): Int = TAB_ORDER.getOrElse(page) { 0 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageWithHeader(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF0F172A),
                titleContentColor = Color(0xFFF8FAFC)
            )
        )
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppShell(vm: AppViewModel = viewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    val browserHolder = remember { BrowserWebViewHolder() }
    val focusManager = LocalFocusManager.current
    val pagerState = rememberPagerState(
        initialPage = tabToPage(vm.currentTab),
        pageCount = { TAB_ORDER.size }
    )

    var lastSyncedPage by remember { mutableIntStateOf(pagerState.currentPage) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { browserHolder.destroy() }
    }

    LaunchedEffect(Unit) {
        vm.toastFlow.collect { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }

    // Two-way sync: programmatic tab switches (bottom bar tap, cross-tab actions, Back button) -> Pager
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

    // Fallback Back handler (inner BackHandlers in BrowserScreen & PikPakScreen take higher priority first)
    BackHandler(enabled = vm.currentTab != 0) {
        vm.currentTab = 0
    }

    Scaffold(
        containerColor = Color(0xFF0B1120),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0F172A)) {
                NavigationBarItem(
                    selected = vm.currentTab == 0,
                    onClick = { vm.currentTab = 0 },
                    icon = { Icon(Icons.Default.TravelExplore, contentDescription = "Torrent") },
                    label = { Text("Torrent", fontSize = 10.5.sp, maxLines = 1) }
                )
                NavigationBarItem(
                    selected = vm.currentTab == 4,
                    onClick = { vm.currentTab = 4 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (vm.activePageScriptNames.isNotEmpty()) {
                                    Badge(containerColor = Color(0xFF7C3AED)) {
                                        Text(vm.activePageScriptNames.size.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Public, contentDescription = "Browser")
                        }
                    },
                    label = { Text("Browser", fontSize = 10.5.sp, maxLines = 1) }
                )
                NavigationBarItem(
                    selected = vm.currentTab == 1,
                    onClick = { vm.currentTab = 1 },
                    icon = { Icon(Icons.Default.Subtitles, contentDescription = "Subtitle") },
                    label = { Text("Subtitle", fontSize = 10.5.sp, maxLines = 1) }
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
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Gemini")
                        }
                    },
                    label = { Text("Gemini", fontSize = 10.5.sp, maxLines = 1) }
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
                            Icon(Icons.Default.CloudUpload, contentDescription = "PikPak")
                        }
                    },
                    label = { Text("PikPak", fontSize = 10.5.sp, maxLines = 1) }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = TAB_ORDER.size - 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) { page ->
            when (pageToTab(page)) {
                0 -> PageWithHeader("🧲 TorrentHub · 16 Situs") {
                    TorrentScreen(vm)
                }
                4 -> Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(Color(0xFF1E293B))
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        BrowserScreen(vm = vm, holder = browserHolder, isActive = vm.currentTab == 4)
                    }
                }
                1 -> PageWithHeader("💬 Subtitle Search (Xunlei)") {
                    SubtitleScreen(vm)
                }
                2 -> PageWithHeader("✨ Gemini Subtitle Translator") {
                    GeminiScreen(vm)
                }
                3 -> PageWithHeader("☁️ PikPak Cloud & File Manager") {
                    PikPakScreen(vm = vm, isActive = vm.currentTab == 3)
                }
            }
        }
    }
}
