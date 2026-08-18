package com.piperostool

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PiperBrowserActivity : AppCompatActivity() {
    private data class BrowserTab(
        val id: Long,
        var title: String,
        var url: String,
        val webView: WebView,
        val incognito: Boolean,
        val profileName: String?,
        var thumbnail: Bitmap? = null,
        val thirdPartyHosts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val trackerHosts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val requestCount: AtomicInteger = AtomicInteger()
    )

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String?,
        val mimeType: String?,
        val suggestedFileName: String?,
        val referrer: String?
    )

    private data class BrowserMediaCandidate(
        val url: String,
        val mimeType: String?,
        val title: String
    )

    private lateinit var browserRoot: View
    private lateinit var browserContent: FrameLayout
    private lateinit var startPage: View
    private lateinit var homeSearchInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var pageTitle: TextView
    private lateinit var tabCount: TextView
    private lateinit var pageProgress: ProgressBar
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var reloadButton: ImageButton
    private lateinit var mediaDownloadButton: ImageButton
    private lateinit var addressActionButton: ImageButton
    private lateinit var bottomPanel: LinearLayout
    private lateinit var addressRow: LinearLayout
    private lateinit var browserToolbar: LinearLayout
    private lateinit var sessionStore: BrowserSessionStore
    private lateinit var extensionStore: BrowserExtensionStore
    private lateinit var privateBadge: TextView

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabId = -1L
    private var pendingDownload: PendingDownload? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var systemUserAgent = ""
    private val suggestedDownloadNames = ConcurrentHashMap<String, String>()
    private val mediaCandidates =
        ConcurrentHashMap<Long, ConcurrentHashMap<String, BrowserMediaCandidate>>()
    private var browserCustomView: View? = null
    private var browserCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    private var orientationBeforeCustomView = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var browserChromeCompact = false
    private var chromeAnimator: ValueAnimator? = null
    private var chromeScrollDirection = 0
    private var chromeScrollDistance = 0
    private var chromeLastScrollAt = 0L
    private var chromeScrollSuppressedUntil = 0L
    private var lastCredentialPromptKey: String? = null
    private var lastCredentialPromptAt = 0L

    private inner class DownloadMetadataBridge(private val tabId: Long) {
        @JavascriptInterface
        fun remember(url: String?, fileName: String?) {
            val safeUrl = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return
            val safeName = fileName?.trim()?.takeIf { it.isNotBlank() }?.take(240) ?: return
            if (suggestedDownloadNames.size >= MAX_SUGGESTED_DOWNLOADS) {
                suggestedDownloadNames.keys.firstOrNull()?.let(suggestedDownloadNames::remove)
            }
            suggestedDownloadNames[safeUrl] = safeName
        }

        @JavascriptInterface
        fun discover(url: String?, mimeType: String?, title: String?) {
            registerMediaCandidate(tabId, url, mimeType, title)
        }

        @JavascriptInterface
        fun choose(url: String?, mimeType: String?, title: String?) {
            registerMediaCandidate(tabId, url, mimeType, title)
            runOnUiThread {
                showMediaDownloadOptions(tabId, url)
            }
        }
    }

    private inner class CredentialCaptureBridge(private val tabId: Long) {
        @JavascriptInterface
        fun propose(origin: String?, pageTitle: String?, username: String?, password: String?) {
            val secret = password?.takeIf { it.isNotEmpty() }?.take(4096) ?: return
            val safeOrigin = origin?.take(500) ?: return
            runOnUiThread {
                val tab = tabs.firstOrNull { it.id == tabId } ?: return@runOnUiThread
                if (tab.incognito || FirebaseAuth.getInstance().currentUser == null) return@runOnUiThread
                val current = runCatching { Uri.parse(tab.url) }.getOrNull() ?: return@runOnUiThread
                val submitted = runCatching { Uri.parse(safeOrigin) }.getOrNull() ?: return@runOnUiThread
                if (current.scheme != "https" || submitted.scheme != "https" ||
                    current.host.isNullOrBlank() || current.host != submitted.host
                ) return@runOnUiThread

                val account = username.orEmpty().trim().take(320)
                val key = "${current.host}|$account|${secret.hashCode()}"
                val now = SystemClock.uptimeMillis()
                if (lastCredentialPromptKey == key && now - lastCredentialPromptAt < 15_000L) return@runOnUiThread
                lastCredentialPromptKey = key
                lastCredentialPromptAt = now
                showSaveCredentialPrompt(
                    PendingBrowserCredential(
                        site = pageTitle?.trim()?.takeIf { it.isNotBlank() }?.take(160)
                            ?: current.host.orEmpty(),
                        origin = safeOrigin,
                        username = account,
                        password = secret
                    )
                )
            }
        }
    }

    private val extensionPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            showExtensionImportConfirmation(uri)
        }

    private val downloadPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val storageReady =
                Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED

            if (storageReady) {
                pendingDownload?.let(::startDownloadService)
            } else {
                Toast.makeText(
                    this,
                    R.string.browser_storage_permission_required,
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingDownload = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openBrowserNotificationSettings()
        }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileChooserCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_piper_browser)

        sessionStore = BrowserSessionStore(this)
        extensionStore = BrowserExtensionStore(this)
        clearStaleIncognitoProfiles()
        systemUserAgent = WebSettings.getDefaultUserAgent(applicationContext)
        bindViews()
        applyWindowInsets()
        applyResponsiveLayout(resources.configuration)
        setupActions()
        setupBackHandling()
        restoreSession()
        NetworkAccess.observe(this, this) { online ->
            if (!online) {
                tabs.forEach { it.webView.stopLoading() }
                NetworkAccess.showOffline(browserRoot)
            }
        }
    }

    override fun onPause() {
        saveSession()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout(newConfig)
        activeTab()?.webView?.invalidate()
        browserCustomView?.invalidate()
    }

    override fun onDestroy() {
        hideBrowserCustomView()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        val privateProfiles = tabs.mapNotNull { it.profileName }
        tabs.forEach { tab ->
            tab.thumbnail?.recycle()
            tab.thumbnail = null
            tab.webView.stopLoading()
            tab.webView.webChromeClient = null
            tab.webView.webViewClient = WebViewClient()
            tab.webView.destroy()
        }
        tabs.clear()
        mediaCandidates.clear()
        privateProfiles.forEach(::deleteIncognitoProfile)
        super.onDestroy()
    }

    private fun bindViews() {
        browserRoot = findViewById(R.id.browserRoot)
        browserContent = findViewById(R.id.browserContent)
        startPage = findViewById(R.id.browserStartPage)
        homeSearchInput = findViewById(R.id.homeSearchInput)
        addressInput = findViewById(R.id.browserAddressInput)
        pageTitle = findViewById(R.id.browserPageTitle)
        tabCount = findViewById(R.id.browserTabCount)
        pageProgress = findViewById(R.id.browserProgress)
        backButton = findViewById(R.id.btnBrowserBack)
        forwardButton = findViewById(R.id.btnBrowserForward)
        reloadButton = findViewById(R.id.btnBrowserReload)
        mediaDownloadButton = findViewById(R.id.browserMediaDownload)
        privateBadge = findViewById(R.id.browserPrivateBadge)
        addressActionButton = findViewById(R.id.btnAddressGo)
        bottomPanel = findViewById(R.id.browserBottomPanel)
        addressRow = findViewById(R.id.browserAddressRow)
        browserToolbar = findViewById(R.id.browserToolbar)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(browserRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                max(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun setupActions() {
        findViewById<View>(R.id.btnExitBrowser).setOnClickListener { finish() }
        findViewById<View>(R.id.btnHomeSearch).setOnClickListener {
            navigateFromInput(homeSearchInput)
        }
        addressActionButton.setOnClickListener {
            if (activeTab()?.url == BrowserSessionStore.HOME_URL) {
                setBrowserChromeCompact(false)
                addressInput.requestFocus()
                showKeyboard(addressInput)
            } else {
                showBrowserMenu()
            }
        }
        addressInput.setOnFocusChangeListener { _, focused ->
            val tab = activeTab() ?: return@setOnFocusChangeListener
            if (focused) {
                setBrowserChromeCompact(false)
                addressInput.setText(if (tab.url == BrowserSessionStore.HOME_URL) "" else tab.url)
                addressInput.setSelection(addressInput.text?.length ?: 0)
            } else {
                updateAddressDisplay(tab)
            }
        }
        homeSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                navigateFromInput(homeSearchInput)
                true
            } else {
                false
            }
        }
        addressInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                navigateFromInput(addressInput)
                true
            } else {
                false
            }
        }

        backButton.setOnClickListener { navigateBack() }
        forwardButton.setOnClickListener {
            activeTab()?.webView?.takeIf { it.canGoForward() }?.goForward()
        }
        findViewById<View>(R.id.btnBrowserHome).setOnClickListener { showHome() }
        reloadButton.setOnClickListener {
            activeTab()?.let { tab ->
                if (tab.url == BrowserSessionStore.HOME_URL) {
                    homeSearchInput.requestFocus()
                    showKeyboard(homeSearchInput)
                } else {
                    tab.webView.reload()
                }
            }
        }
        findViewById<View>(R.id.btnBrowserTabs).setOnClickListener { showTabsSheet() }
        mediaDownloadButton.setOnClickListener {
            showMediaDownloadOptions(activeTabId)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (browserCustomView != null) {
                        hideBrowserCustomView()
                    } else {
                        navigateBack()
                    }
                }
            }
        )
    }

    private fun restoreSession() {
        val savedTabs = sessionStore.loadTabs()
        if (savedTabs.isEmpty()) {
            addTab(BrowserSessionStore.HOME_URL, switchToTab = true)
            return
        }

        savedTabs.forEach { saved ->
            addTab(
                url = saved.url,
                switchToTab = false,
                id = saved.id,
                savedTitle = saved.title
            )
        }
        val savedActiveId = sessionStore.loadActiveTabId()
        switchToTab(tabs.firstOrNull { it.id == savedActiveId }?.id ?: tabs.first().id)
    }

    private fun addTab(
        url: String,
        switchToTab: Boolean,
        id: Long = System.nanoTime(),
        savedTitle: String = BrowserSessionStore.DEFAULT_TAB_TITLE,
        incognito: Boolean = false
    ): BrowserTab {
        val profileName = if (incognito) {
            "$INCOGNITO_PROFILE_PREFIX${UUID.randomUUID()}"
        } else {
            null
        }
        val webView = createWebView(id, profileName)
        val tab = BrowserTab(id, savedTitle, url, webView, incognito, profileName)
        tabs += tab
        browserContent.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView.visibility = View.GONE

        if (url != BrowserSessionStore.HOME_URL && URLUtil.isNetworkUrl(url)) {
            webView.loadUrl(url)
        }
        if (switchToTab) switchToTab(id)
        updateTabCount()
        return tab
    }

    private fun createWebView(tabId: Long, profileName: String?): WebView {
        return WebView(this).apply {
            tag = tabId
            if (profileName != null) {
                WebViewCompat.setProfile(this, profileName)
            }
            setBackgroundColor(Color.WHITE)
            addJavascriptInterface(DownloadMetadataBridge(tabId), DOWNLOAD_BRIDGE_NAME)
            addJavascriptInterface(CredentialCaptureBridge(tabId), CREDENTIAL_BRIDGE_NAME)
            configureWebSettings(this)
            webViewClient = createWebViewClient()
            webChromeClient = createWebChromeClient()
            setDownloadListener(createDownloadListener(this))
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                handleBrowserScroll(this, scrollY, oldScrollY)
            }
        }
    }

    private fun handleBrowserScroll(webView: WebView, scrollY: Int, oldScrollY: Int) {
        if ((webView.tag as? Long) != activeTabId ||
            activeTab()?.url == BrowserSessionStore.HOME_URL
        ) {
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now < chromeScrollSuppressedUntil) return

        val delta = scrollY - oldScrollY
        if (abs(delta) < dp(2)) return

        val direction = if (delta > 0) SCROLL_DOWN else SCROLL_UP
        if (direction != chromeScrollDirection || now - chromeLastScrollAt > SCROLL_SEQUENCE_TIMEOUT_MS) {
            chromeScrollDirection = direction
            chromeScrollDistance = 0
        }
        chromeLastScrollAt = now
        chromeScrollDistance += abs(delta)

        when {
            direction == SCROLL_DOWN &&
                !browserChromeCompact &&
                scrollY >= dp(48) &&
                chromeScrollDistance >= dp(40) -> {
                resetBrowserScrollTracking()
                setBrowserChromeCompact(true)
            }

            direction == SCROLL_UP &&
                browserChromeCompact &&
                chromeScrollDistance >= dp(56) -> {
                resetBrowserScrollTracking()
                setBrowserChromeCompact(false)
            }
        }
    }

    private fun resetBrowserScrollTracking() {
        chromeScrollDirection = 0
        chromeScrollDistance = 0
        chromeLastScrollAt = 0L
    }

    private fun configureWebSettings(webView: WebView) {
        val selected = selectedUserAgent()
        val desktopMode = sessionStore.isDesktopMode()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
            userAgentString = selected.value
                ?: if (desktopMode) DESKTOP_DEFAULT_USER_AGENT else systemUserAgent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") {
                    if (!NetworkAccess.isOnline(this@PiperBrowserActivity)) {
                        NetworkAccess.showOffline(browserRoot)
                        return true
                    }
                    return false
                }
                return openExternalUri(uri)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                val tab = tabFor(view) ?: return
                val currentUrl = url.orEmpty()
                if (currentUrl == "about:blank" && tab.url == BrowserSessionStore.HOME_URL) return
                tab.requestCount.set(0)
                tab.thirdPartyHosts.clear()
                tab.trackerHosts.clear()
                mediaCandidates.remove(tab.id)
                if (tab.id == activeTabId) updateMediaDownloadButton()
                tab.url = currentUrl
                if (tab.id == activeTabId) {
                    startPage.visibility = View.GONE
                    view.visibility = View.VISIBLE
                    updateAddressDisplay(tab)
                    updateSecurityIcon(currentUrl)
                    updateAddressAction(tab)
                }
                saveSession()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val tab = tabFor(view) ?: return
                val currentUrl = url.orEmpty()
                if (currentUrl == "about:blank" && tab.url == BrowserSessionStore.HOME_URL) return

                tab.url = currentUrl
                tab.title = view.title?.takeIf { it.isNotBlank() } ?: currentUrl
                if (!tab.incognito) {
                    sessionStore.addHistory(tab.title, currentUrl)
                }
                CookieManager.getInstance().flush()
                if (!tab.incognito) {
                    injectExtensions(view, currentUrl)
                }
                installDownloadMetadataCapture(view)
                installMediaDiscovery(view)
                if (!tab.incognito && currentUrl.startsWith("https://")) {
                    installCredentialCapture(view)
                }
                captureTabThumbnail(tab)
                if (tab.id == activeTabId) updateActiveTabUi()
                saveSession()
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val tabId = (view.tag as? Long) ?: return super.shouldInterceptRequest(view, request)
                val url = request.url.toString()
                tabs.firstOrNull { it.id == tabId }?.let { recordPrivacyRequest(it, request.url) }
                mediaMimeTypeForUrl(url)?.let { mimeType ->
                    registerMediaCandidate(tabId, url, mimeType, null)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tabFor(view)?.id != activeTabId) return
                pageProgress.progress = newProgress
                pageProgress.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
                updateNavigationButtons()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val tab = tabFor(view) ?: return
                if (!title.isNullOrBlank()) tab.title = title
                if (tab.id == activeTabId) pageTitle.text = tab.title
            }

            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                showBrowserCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideBrowserCustomView()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                if (!isUserGesture) return false
                val sourceTab = view?.let(::tabFor)
                if (sourceTab?.incognito == true && !supportsIncognitoProfiles()) return false
                val tab = addTab(
                    BrowserSessionStore.HOME_URL,
                    switchToTab = true,
                    incognito = sourceTab?.incognito == true
                )
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = tab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val chooserIntent = runCatching {
                    fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                }.getOrNull() ?: return false

                return runCatching {
                    filePickerLauncher.launch(chooserIntent)
                    true
                }.getOrElse {
                    fileChooserCallback = null
                    false
                }
            }
        }
    }

    private fun showBrowserCustomView(
        view: View,
        callback: WebChromeClient.CustomViewCallback?
    ) {
        if (browserCustomView != null) {
            callback?.onCustomViewHidden()
            return
        }
        orientationBeforeCustomView = requestedOrientation
        browserCustomView = view
        browserCustomViewCallback = callback
        browserRoot.visibility = View.GONE
        (window.decorView as ViewGroup).addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        view.requestFocus()
    }

    private fun hideBrowserCustomView() {
        val customView = browserCustomView ?: return
        (customView.parent as? ViewGroup)?.removeView(customView)
        browserCustomView = null
        browserRoot.visibility = View.VISIBLE
        requestedOrientation = orientationBeforeCustomView
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(browserRoot)
        browserCustomViewCallback?.onCustomViewHidden()
        browserCustomViewCallback = null
    }

    private fun createDownloadListener(webView: WebView): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val download = PendingDownload(
                url = url,
                userAgent = userAgent.orEmpty(),
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                suggestedFileName = takeSuggestedDownloadName(url),
                referrer = webView.url
            )
            requestDownload(download)
        }
    }

    private fun requestDownload(download: PendingDownload) {
        pendingDownload = download
        val missingPermissions = buildList {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@PiperBrowserActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    this@PiperBrowserActivity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (missingPermissions.isEmpty()) {
            startDownloadService(download)
            pendingDownload = null
        } else {
            downloadPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startDownloadService(download: PendingDownload) {
        val cookies = if (activeTab()?.incognito == true) {
            null
        } else {
            CookieManager.getInstance().getCookie(download.url)
        }
        val intent = Intent(this, BrowserDownloadService::class.java)
            .setAction(BrowserDownloadService.ACTION_DOWNLOAD)
            .putExtra(BrowserDownloadService.EXTRA_URL, download.url)
            .putExtra(BrowserDownloadService.EXTRA_USER_AGENT, download.userAgent)
            .putExtra(
                BrowserDownloadService.EXTRA_CONTENT_DISPOSITION,
                download.contentDisposition
            )
            .putExtra(BrowserDownloadService.EXTRA_MIME_TYPE, download.mimeType)
            .putExtra(BrowserDownloadService.EXTRA_COOKIES, cookies)
            .putExtra(
                BrowserDownloadService.EXTRA_SUGGESTED_FILE_NAME,
                download.suggestedFileName
            )
            .putExtra(BrowserDownloadService.EXTRA_REFERRER, download.referrer)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.browser_download_started, Toast.LENGTH_SHORT).show()
    }

    private fun navigateFromInput(input: EditText) {
        val value = input.text?.toString().orEmpty().trim()
        if (value.isBlank()) return
        hideKeyboard(input)
        navigateTo(normalizeAddress(value))
    }

    private fun normalizeAddress(value: String): String {
        val lower = value.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) return value
        if (!value.contains(" ") && (value.contains(".") || value.startsWith("localhost"))) {
            return "https://$value"
        }
        return GOOGLE_SEARCH_URL + URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun navigateTo(url: String) {
        if (!NetworkAccess.isOnline(this)) {
            NetworkAccess.showOffline(browserRoot)
            return
        }
        val tab = activeTab() ?: addTab(BrowserSessionStore.HOME_URL, true)
        tab.url = url
        startPage.visibility = View.GONE
        tab.webView.visibility = View.VISIBLE
        tab.webView.loadUrl(url)
    }

    private fun showHome() {
        val tab = activeTab() ?: return
        captureTabThumbnail(tab)
        mediaCandidates.remove(tab.id)
        tab.url = BrowserSessionStore.HOME_URL
        tab.title = BrowserSessionStore.DEFAULT_TAB_TITLE
        tab.webView.visibility = View.GONE
        startPage.visibility = View.VISIBLE
        homeSearchInput.text?.clear()
        updateActiveTabUi()
        saveSession()
    }

    private fun navigateBack() {
        val tab = activeTab()
        when {
            tab == null -> finish()
            tab.url == BrowserSessionStore.HOME_URL && tab.webView.canGoBack() -> {
                startPage.visibility = View.GONE
                tab.webView.visibility = View.VISIBLE
                tab.webView.goBack()
            }
            tab.url != BrowserSessionStore.HOME_URL && tab.webView.canGoBack() -> {
                tab.webView.goBack()
            }
            tabs.size > 1 -> closeTab(tab.id)
            else -> finish()
        }
    }

    private fun switchToTab(tabId: Long) {
        val selected = tabs.firstOrNull { it.id == tabId } ?: return
        activeTab()?.takeUnless { it.id == selected.id }?.let(::captureTabThumbnail)
        activeTabId = selected.id
        tabs.forEach { it.webView.visibility = View.GONE }
        if (selected.url == BrowserSessionStore.HOME_URL) {
            startPage.visibility = View.VISIBLE
        } else {
            startPage.visibility = View.GONE
            selected.webView.visibility = View.VISIBLE
        }
        updateActiveTabUi()
        saveSession()
    }

    private fun closeTab(tabId: Long) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return

        val removed = tabs.removeAt(index)
        removed.thumbnail?.recycle()
        removed.thumbnail = null
        mediaCandidates.remove(tabId)
        browserContent.removeView(removed.webView)
        removed.webView.destroy()
        removed.profileName?.let { profile ->
            browserRoot.postDelayed({ deleteIncognitoProfile(profile) }, 250)
        }

        if (tabs.isEmpty()) {
            addTab(BrowserSessionStore.HOME_URL, switchToTab = true)
        } else if (activeTabId == tabId) {
            switchToTab(tabs[index.coerceAtMost(tabs.lastIndex)].id)
        }
        updateTabCount()
        saveSession()
    }

    private fun updateActiveTabUi() {
        val tab = activeTab() ?: return
        setBrowserChromeCompact(false, animated = false)
        pageTitle.text = tab.title
        privateBadge.visibility = if (tab.incognito) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.browserStorageNote).setText(
            if (tab.incognito) {
                R.string.browser_incognito_storage_note
            } else {
                R.string.browser_private_storage_note
            }
        )
        updateAddressDisplay(tab)
        updateAddressAction(tab)
        updateSecurityIcon(tab.url)
        updateNavigationButtons()
        updateTabCount()
        updateMediaDownloadButton()
    }

    private fun updateNavigationButtons() {
        val tab = activeTab()
        backButton.alpha =
            if (tab?.webView?.canGoBack() == true || tabs.size > 1) 1f else 0.38f
        forwardButton.alpha = if (tab?.webView?.canGoForward() == true) 1f else 0.38f
        reloadButton.alpha = if (tab?.url == BrowserSessionStore.HOME_URL) 0.65f else 1f
    }

    private fun updateSecurityIcon(url: String) {
        findViewById<View>(R.id.browserSecurityIcon).alpha =
            if (url.startsWith("https://")) 1f else 0.38f
    }

    private fun updateTabCount() {
        tabCount.text = tabs.size.coerceAtMost(99).toString()
    }

    private fun updateAddressDisplay(tab: BrowserTab) {
        val value = when {
            tab.url == BrowserSessionStore.HOME_URL -> ""
            addressInput.hasFocus() -> tab.url
            else -> conciseAddress(tab.url)
        }
        if (addressInput.text?.toString() != value) addressInput.setText(value)
    }

    private fun updateAddressAction(tab: BrowserTab) {
        val atHome = tab.url == BrowserSessionStore.HOME_URL
        addressActionButton.setImageResource(
            if (atHome) R.drawable.ic_browser_search else R.drawable.ic_browser_more
        )
        addressActionButton.contentDescription = getString(
            if (atHome) R.string.search else R.string.more_options
        )
    }

    private fun conciseAddress(url: String): String {
        return runCatching {
            Uri.parse(url).host
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
                ?: url
        }.getOrDefault(url)
    }

    private fun setBrowserChromeCompact(compact: Boolean, animated: Boolean = true) {
        if (browserChromeCompact == compact && animated) return
        browserChromeCompact = compact
        resetBrowserScrollTracking()
        chromeScrollSuppressedUntil = SystemClock.uptimeMillis() +
            if (animated) CHROME_SCROLL_SUPPRESSION_MS else 100L
        chromeAnimator?.cancel()

        val toolbarParams = browserToolbar.layoutParams
        val addressParams = addressRow.layoutParams
        val currentToolbarHeight = when {
            toolbarParams.height > 0 -> toolbarParams.height
            browserToolbar.height > 0 -> browserToolbar.height
            else -> 0
        }
        val currentAddressHeight = when {
            addressParams.height > 0 -> addressParams.height
            else -> dp(48)
        }
        val targetToolbarHeight = if (compact) 0 else dp(54)
        val targetAddressHeight = if (compact) dp(40) else dp(48)

        if (!compact) browserToolbar.visibility = View.VISIBLE
        val applyProgress: (Float) -> Unit = { fraction ->
            toolbarParams.height =
                (currentToolbarHeight + (targetToolbarHeight - currentToolbarHeight) * fraction).toInt()
            addressParams.height =
                (currentAddressHeight + (targetAddressHeight - currentAddressHeight) * fraction).toInt()
            browserToolbar.layoutParams = toolbarParams
            addressRow.layoutParams = addressParams
            browserToolbar.alpha = if (compact) 1f - fraction else fraction
            bottomPanel.setPadding(
                dp(10),
                if (compact) dp(4) else dp(6),
                dp(10),
                if (compact) dp(4) else dp(6)
            )
        }

        if (!animated) {
            applyProgress(1f)
            browserToolbar.visibility = if (compact) View.GONE else View.VISIBLE
            activeTab()?.let(::updateAddressDisplay)
            return
        }

        chromeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 190L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled || chromeAnimator !== animation) return
                    browserToolbar.visibility = if (compact) View.GONE else View.VISIBLE
                    activeTab()?.let(::updateAddressDisplay)
                    chromeAnimator = null
                }
            })
            start()
        }
    }

    private fun captureTabThumbnail(tab: BrowserTab) {
        if (tab.url == BrowserSessionStore.HOME_URL) return
        val sourceWidth = tab.webView.width
        val sourceHeight = tab.webView.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val targetWidth = min(dp(190), sourceWidth)
        val targetHeight = min(dp(260), (sourceHeight * targetWidth.toFloat() / sourceWidth).toInt())
        if (targetWidth <= 0 || targetHeight <= 0) return
        val bitmap = runCatching {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565).also { output ->
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                canvas.scale(
                    targetWidth.toFloat() / sourceWidth,
                    targetHeight.toFloat() / sourceHeight
                )
                tab.webView.draw(canvas)
            }
        }.getOrNull() ?: return
        tab.thumbnail?.takeUnless { it.isRecycled }?.recycle()
        tab.thumbnail = bitmap
    }

    private fun recordPrivacyRequest(tab: BrowserTab, requestUri: Uri) {
        val requestHost = requestUri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return
        tab.requestCount.incrementAndGet()
        val pageHost = runCatching { Uri.parse(tab.url).host }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("www.")
        if (!pageHost.isNullOrBlank() && !sameSite(pageHost, requestHost)) {
            tab.thirdPartyHosts += requestHost
        }
        if (TRACKER_HOST_HINTS.any { hint -> requestHost == hint || requestHost.endsWith(".$hint") }) {
            tab.trackerHosts += requestHost
        }
    }

    private fun sameSite(first: String, second: String): Boolean {
        if (first == second || first.endsWith(".$second") || second.endsWith(".$first")) return true
        fun root(host: String): String = host.split('.').takeLast(2).joinToString(".")
        return root(first) == root(second)
    }

    private fun activeTab(): BrowserTab? = tabs.firstOrNull { it.id == activeTabId }

    private fun tabFor(webView: WebView): BrowserTab? =
        tabs.firstOrNull { it.id == webView.tag }

    private fun saveSession() {
        if (!::sessionStore.isInitialized || tabs.isEmpty()) return
        val regularTabs = tabs.filterNot { it.incognito }
        if (regularTabs.isEmpty()) {
            sessionStore.saveTabs(emptyList(), -1L)
            return
        }
        val savedActiveId = activeTab()
            ?.takeUnless { it.incognito }
            ?.id
            ?: regularTabs.first().id
        sessionStore.saveTabs(
            regularTabs.map { BrowserSavedTab(it.id, it.title, it.url) },
            savedActiveId
        )
    }

    private fun selectedUserAgent(): BrowserUserAgent {
        val selectedId = sessionStore.selectedUserAgentId()
        return BrowserSessionStore.userAgents()
            .firstOrNull { it.id == selectedId }
            ?: BrowserSessionStore.userAgents().first()
    }

    private fun showBrowserMenu() {
        val tab = activeTab() ?: return
        if (tab.url == BrowserSessionStore.HOME_URL) {
            addressInput.requestFocus()
            showKeyboard(addressInput)
            return
        }
        setBrowserChromeCompact(false)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(10))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = popupSurfaceDrawable()
            addView(createSheetHeader(getString(R.string.browser_options), null))
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }.also(PiperAutoFont::watch)
        val popupWidth = min(dp(350), resources.displayMetrics.widthPixels - dp(24))
        val popupHeight = min(dp(570), (resources.displayMetrics.heightPixels * 0.68f).toInt())
        val popup = PopupWindow(root, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            elevation = dp(12).toFloat()
            animationStyle = android.R.style.Animation_Dialog
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        val desktopSwitch = SwitchMaterial(this).apply {
            isChecked = sessionStore.isDesktopMode()
            buttonTintList = null
            setOnCheckedChangeListener { _, enabled -> setDesktopMode(enabled) }
        }
        content.addView(
            createMenuRow(
                R.drawable.ic_browser_privacy,
                getString(R.string.browser_privacy_report),
                if (tab.trackerHosts.isEmpty()) {
                    getString(R.string.browser_privacy_summary_safe)
                } else {
                    getString(R.string.browser_privacy_summary_warning, tab.trackerHosts.size)
                }
            ) {
                popup.dismiss()
                showPrivacyReport(tab)
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.browser,
                getString(R.string.desktop_site),
                null,
                desktopSwitch
            ) {
                desktopSwitch.isChecked = !desktopSwitch.isChecked
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.ic_browser_reload,
                getString(R.string.browser_history)
            ) {
                popup.dismiss()
                showHistorySheet()
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.ic_browser_download,
                getString(R.string.browser_downloads)
            ) {
                popup.dismiss()
                openDownloads()
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.ic_browser_lock,
                getString(R.string.browser_accounts_title),
                if (FirebaseAuth.getInstance().currentUser == null) {
                    getString(R.string.browser_accounts_sign_in_required)
                } else {
                    getString(R.string.browser_accounts_locked)
                }
            ) {
                popup.dismiss()
                openAccountManager()
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.ic_browser_lock,
                getString(R.string.browser_vpn),
                selectedVpnLabel()
            ) {
                popup.dismiss()
                showVpnDialog()
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.ic_browser_add,
                getString(R.string.browser_extensions),
                resources.getQuantityString(
                    R.plurals.browser_extension_count,
                    extensionStore.load().size,
                    extensionStore.load().size
                )
            ) {
                popup.dismiss()
                showExtensionsSheet()
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.browser,
                getString(R.string.browser_user_agent),
                selectedUserAgent().label
            ) {
                popup.dismiss()
                showUserAgentDialog()
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.notification,
                getString(R.string.browser_notifications)
            ) {
                popup.dismiss()
                requestAndOpenNotificationSettings()
            }
        )
        content.addView(
            createMenuRow(
                R.drawable.browser,
                getString(R.string.about_piperos_browser)
            ) {
                popup.dismiss()
                showAboutDialog()
            }
        )
        PiperModernUi.apply(root)
        popup.showAtLocation(
            browserRoot,
            Gravity.BOTTOM or Gravity.END,
            dp(12),
            bottomPanel.height + dp(10)
        )
    }

    private fun popupSurfaceDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(PiperModernUi.surfaceColor(this@PiperBrowserActivity))
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), PiperModernUi.borderColor(this@PiperBrowserActivity))
    }

    private fun showPrivacyReport(tab: BrowserTab) {
        val dialog = BottomSheetDialog(this)
        val root = createSheetRoot()
        root.addView(createSheetHeader(getString(R.string.browser_privacy_report), null))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(24))
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = popupSurfaceDrawable()
            addView(ImageView(this@PiperBrowserActivity).apply {
                setImageResource(R.drawable.ic_browser_privacy)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (tab.trackerHosts.isEmpty()) {
                        PiperModernUi.accentColor(this@PiperBrowserActivity)
                    } else {
                        Color.rgb(235, 161, 65)
                    }
                )
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@PiperBrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(createSheetText(conciseAddress(tab.url), 17f, Color.WHITE).apply {
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(createSheetText(
                    if (tab.trackerHosts.isEmpty()) {
                        getString(R.string.browser_privacy_summary_safe)
                    } else {
                        getString(R.string.browser_privacy_summary_warning, tab.trackerHosts.size)
                    },
                    12f,
                    Color.parseColor("#AAFFFFFF")
                ))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                privacyMetric(
                    if (tab.url.startsWith("https://")) "HTTPS" else "HTTP",
                    getString(R.string.browser_privacy_https)
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                privacyMetric(
                    tab.requestCount.get().toString(),
                    getString(R.string.browser_privacy_requests, tab.requestCount.get())
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                privacyMetric(
                    tab.thirdPartyHosts.size.toString(),
                    getString(R.string.browser_privacy_third_party, tab.thirdPartyHosts.size)
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        content.addView(metrics)

        content.addView(createSheetText(
            getString(R.string.browser_privacy_trackers),
            14f,
            Color.WHITE
        ).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(10))
        })
        if (tab.trackerHosts.isEmpty()) {
            content.addView(createSheetText(
                getString(R.string.browser_privacy_no_trackers),
                13f,
                Color.parseColor("#AAFFFFFF")
            ).apply {
                maxLines = 3
            })
        } else {
            tab.trackerHosts.sorted().forEach { host ->
                content.addView(createSheetText(host, 13f, Color.WHITE).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = popupSurfaceDrawable()
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
        }
        content.addView(createSheetText(
            getString(R.string.browser_privacy_note),
            11f,
            Color.parseColor("#86FFFFFF")
        ).apply {
            maxLines = 5
            setPadding(0, dp(20), 0, 0)
        })

        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        PiperModernUi.apply(root)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun privacyMetric(value: String, label: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(10))
            addView(createSheetText(value, 16f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(createSheetText(label, 9f, Color.parseColor("#9AFFFFFF")).apply {
                gravity = Gravity.CENTER
                maxLines = 2
            })
        }
    }

    private fun createMenuRow(
        icon: Int,
        title: String,
        subtitle: String? = null,
        trailingView: View? = null,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(dp(12), dp(6), dp(6), dp(6))
            isClickable = true
            isFocusable = true
            background = getDrawable(android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            addView(
                ImageView(this@PiperBrowserActivity).apply {
                    setImageResource(icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    imageTintList =
                        if (icon == R.drawable.browser) {
                            null
                        } else {
                            android.content.res.ColorStateList.valueOf(
                                if (PiperUiPreferences.isModern(this@PiperBrowserActivity)) {
                                    PiperModernUi.accentColor(this@PiperBrowserActivity)
                                } else {
                                    ContextCompat.getColor(
                                        this@PiperBrowserActivity,
                                        R.color.green_neon
                                    )
                                }
                            )
                        }
                },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(14)
                }
            )

            val labels = LinearLayout(this@PiperBrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(createSheetText(title, 15f, Color.WHITE))
                if (!subtitle.isNullOrBlank()) {
                    addView(
                        createSheetText(
                            subtitle,
                            11f,
                            Color.parseColor("#99FFFFFF")
                        )
                    )
                }
            }
            addView(
                labels,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (trailingView != null) {
                addView(
                    trailingView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setDesktopMode(enabled: Boolean) {
        sessionStore.setDesktopMode(enabled)
        tabs.forEach { configureWebSettings(it.webView) }
        activeTab()?.takeIf { it.url != BrowserSessionStore.HOME_URL }?.webView?.reload()
    }

    private fun showUserAgentDialog() {
        val options = BrowserSessionStore.userAgents()
        val selected = sessionStore.selectedUserAgentId()
        PiperActionSheet.showSingleSelect(
            context = this,
            title = getString(R.string.browser_user_agent),
            choices = options.map { option ->
                PiperSheetChoice(option.id, option.label, option.id == selected)
            },
            onSelect = { key ->
                sessionStore.setSelectedUserAgent(key)
                tabs.forEach { configureWebSettings(it.webView) }
                activeTab()
                    ?.takeIf { it.url != BrowserSessionStore.HOME_URL }
                    ?.webView
                    ?.reload()
            },
            onRemove = {},
            onAdd = {}
        )
    }

    private fun showTabsSheet() {
        activeTab()?.let(::captureTabThumbnail)
        val dialog = BottomSheetDialog(this)
        val root = createSheetRoot()
        root.addView(createSheetHeader(getString(R.string.open_tabs), null))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(12))
            addView(
                createSheetAction(getString(R.string.new_tab), false) {
                    dialog.dismiss()
                    addTab(BrowserSessionStore.HOME_URL, switchToTab = true)
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(6)
                }
            )
            addView(
                createSheetAction(getString(R.string.new_incognito_tab), true) {
                    dialog.dismiss()
                    addIncognitoTab()
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(6)
                }
            )
        }
        root.addView(actions)

        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply {
            columnCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(dp(12), 0, dp(12), dp(20))
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
            }
        }
        populateTabsGrid(grid, dialog)
        scroll.addView(grid)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        PiperModernUi.apply(root)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun populateTabsGrid(grid: GridLayout, dialog: BottomSheetDialog) {
        grid.removeAllViews()
        val columns = grid.columnCount
        val available = resources.displayMetrics.widthPixels - dp(24) - dp(12 * (columns - 1))
        val cardWidth = available / columns
        tabs.toList().forEachIndexed { index, tab ->
            val card = createTabCard(tab, dialog, grid)
            grid.addView(card, GridLayout.LayoutParams().apply {
                width = cardWidth
                height = dp(if (columns == 2) 255 else 220)
                setMargins(dp(4), dp(4), dp(4), dp(8))
            })
            card.alpha = 0f
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 35L).coerceAtMost(175L))
                .setDuration(180L)
                .start()
        }
    }

    private fun createTabCard(
        tab: BrowserTab,
        dialog: BottomSheetDialog,
        grid: GridLayout
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(PiperModernUi.surfaceColor(this@PiperBrowserActivity))
                cornerRadius = dp(8).toFloat()
                setStroke(
                    dp(if (tab.id == activeTabId) 2 else 1),
                    if (tab.id == activeTabId) {
                        PiperModernUi.accentColor(this@PiperBrowserActivity)
                    } else {
                        PiperModernUi.borderColor(this@PiperBrowserActivity)
                    }
                )
            }

            addView(LinearLayout(this@PiperBrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(createSheetText(
                    if (tab.incognito) getString(R.string.incognito_tab_title, tab.title) else tab.title,
                    12f,
                    Color.WHITE
                ).apply {
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, dp(38), 1f))
                addView(createIconButton(R.drawable.ic_browser_close, R.string.close_tab).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener {
                        closeTab(tab.id)
                        populateTabsGrid(grid, dialog)
                    }
                })
            })

            addView(FrameLayout(this@PiperBrowserActivity).apply {
                background = GradientDrawable().apply {
                    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                    setColor(if (dark) Color.rgb(14, 18, 22) else Color.WHITE)
                    cornerRadius = dp(6).toFloat()
                }
                if (tab.thumbnail != null && tab.thumbnail?.isRecycled == false) {
                    addView(ImageView(this@PiperBrowserActivity).apply {
                        setImageBitmap(tab.thumbnail)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                } else {
                    addView(ImageView(this@PiperBrowserActivity).apply {
                        setImageResource(if (tab.url == BrowserSessionStore.HOME_URL) R.drawable.a3tn else R.drawable.browser)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(28), dp(28), dp(28), dp(28))
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }
                if (tab.id == activeTabId) {
                    addView(TextView(this@PiperBrowserActivity).apply {
                        text = getString(R.string.browser_current_tab)
                        textSize = 9f
                        setTextColor(Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(PiperModernUi.accentColor(this@PiperBrowserActivity))
                            cornerRadius = dp(5).toFloat()
                        }
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(24),
                        Gravity.TOP or Gravity.START
                    ).apply { setMargins(dp(7), dp(7), 0, 0) })
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))

            addView(createSheetText(
                if (tab.url == BrowserSessionStore.HOME_URL) {
                    getString(R.string.browser_home_tab)
                } else {
                    conciseAddress(tab.url)
                },
                10f,
                Color.parseColor("#99FFFFFF")
            ).apply {
                setPadding(dp(2), dp(7), dp(2), 0)
            })
            setOnClickListener {
                dialog.dismiss()
                switchToTab(tab.id)
            }
        }
    }

    private fun showHistorySheet() {
        val dialog = BottomSheetDialog(this)
        val root = createSheetRoot()
        val header = createSheetHeader(getString(R.string.browser_history), null)
        val clear = TextView(this).apply {
            text = getString(R.string.clear_history)
            setTextColor(ContextCompat.getColor(this@PiperBrowserActivity, R.color.green_neon))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                sessionStore.clearHistory()
                dialog.dismiss()
            }
        }
        header.addView(clear)
        root.addView(header)

        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(20))
        }
        val history = sessionStore.loadHistory()
        if (history.isEmpty()) {
            list.addView(
                createSheetText(
                    getString(R.string.no_browser_history),
                    14f,
                    Color.parseColor("#AAFFFFFF")
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(36), 0, dp(36))
                }
            )
        } else {
            var previousDay = ""
            history.forEach { entry ->
                val day = historyDayLabel(entry.visitedAt)
                if (day != previousDay) {
                    list.addView(createHistoryDayHeader(day))
                    previousDay = day
                }
                list.addView(createHistoryRow(entry, dialog))
            }
        }
        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        PiperModernUi.apply(root)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun createHistoryRow(
        entry: BrowserHistoryEntry,
        dialog: BottomSheetDialog
    ): View {
        val date = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.visitedAt))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(
                this@PiperBrowserActivity,
                R.drawable.bg_browser_surface
            )
            addView(createSheetText(entry.title, 14f, Color.WHITE))
            addView(createSheetText(entry.url, 11f, Color.parseColor("#99FFFFFF")))
            addView(createSheetText(date, 10f, Color.parseColor("#70FFFFFF")))
            setOnClickListener {
                dialog.dismiss()
                navigateTo(entry.url)
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(8)
            layoutParams = params
        }
    }

    private fun createSheetRoot(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF11161B"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.78f).toInt()
            )
        }.also(PiperAutoFont::watch)
    }

    private fun createSheetHeader(
        title: String,
        onAdd: (() -> Unit)?
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(10), dp(12))
            addView(
                createSheetText(title, 20f, Color.WHITE).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, dp(48), 1f)
            )
            if (onAdd != null) {
                addView(
                    createIconButton(R.drawable.ic_browser_add, R.string.new_tab).apply {
                        setOnClickListener { onAdd() }
                    }
                )
            }
        }
    }

    private fun createSheetText(textValue: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    private fun createIconButton(icon: Int, description: Int): ImageButton {
        return ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = getDrawable(android.R.drawable.list_selector_background)
            contentDescription = getString(description)
            setPadding(dp(13), dp(13), dp(13), dp(13))
        }
    }

    private fun createSheetAction(
        label: String,
        privateMode: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(
                if (privateMode) {
                    ContextCompat.getColor(this@PiperBrowserActivity, R.color.green_neon)
                } else {
                    Color.WHITE
                }
            )
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(
                this@PiperBrowserActivity,
                R.drawable.bg_browser_surface
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun addIncognitoTab() {
        if (!supportsIncognitoProfiles()) {
            PiperDialog.showMessage(
                this,
                getString(R.string.incognito_unavailable_title),
                getString(R.string.incognito_unavailable_message)
            )
            return
        }
        addTab(
            BrowserSessionStore.HOME_URL,
            switchToTab = true,
            savedTitle = getString(R.string.incognito),
            incognito = true
        )
    }

    private fun supportsIncognitoProfiles(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun clearStaleIncognitoProfiles() {
        if (!supportsIncognitoProfiles()) return
        runCatching {
            ProfileStore.getInstance().allProfileNames
                .filter { it.startsWith(INCOGNITO_PROFILE_PREFIX) }
                .forEach { ProfileStore.getInstance().deleteProfile(it) }
        }
    }

    private fun deleteIncognitoProfile(profileName: String) {
        if (!supportsIncognitoProfiles()) return
        runCatching { ProfileStore.getInstance().deleteProfile(profileName) }
    }

    private fun injectExtensions(webView: WebView, url: String) {
        extensionStore.scriptsFor(url).forEach { script ->
            webView.evaluateJavascript(
                "(function(){try{\n$script\n}catch(e){console.error('PiperOS Extension',e);}})();",
                null
            )
        }
    }

    private fun installDownloadMetadataCapture(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
              if (window.__piperDownloadCaptureInstalled) return;
              window.__piperDownloadCaptureInstalled = true;
              document.addEventListener('click', function(event) {
                var node = event.target;
                var anchor = node && node.closest ? node.closest('a[download]') : null;
                if (!anchor) return;
                var name = anchor.getAttribute('download');
                if (name && anchor.href && window.$DOWNLOAD_BRIDGE_NAME) {
                  window.$DOWNLOAD_BRIDGE_NAME.remember(anchor.href, name);
                }
              }, true);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun installMediaDiscovery(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
              document.querySelectorAll('.piper-media-download').forEach(function(button) {
                button.remove();
              });
              if (window.__piperMediaDiscoveryInstalled) return;
              window.__piperMediaDiscoveryInstalled = true;

              function absoluteUrl(value) {
                if (!value) return '';
                try { return new URL(value, document.baseURI).href; } catch (_) { return ''; }
              }

              function report(url, type, title) {
                var resolved = absoluteUrl(url);
                if (!/^https?:\/\//i.test(resolved) || !window.$DOWNLOAD_BRIDGE_NAME) return;
                window.$DOWNLOAD_BRIDGE_NAME.discover(
                  resolved,
                  type || '',
                  title || document.title || 'PiperOS media'
                );
              }

              function attach(media) {
                if (!media || media.dataset.piperMediaReady === '1') return;
                media.dataset.piperMediaReady = '1';

                function sources() {
                  var values = [];
                  if (media.currentSrc) values.push([media.currentSrc, media.getAttribute('type')]);
                  if (media.src) values.push([media.src, media.getAttribute('type')]);
                  media.querySelectorAll('source[src]').forEach(function(source) {
                    values.push([source.src, source.type]);
                  });
                  values.forEach(function(value) {
                    report(value[0], value[1], media.getAttribute('title'));
                  });
                  return values;
                }

                sources();
                ['loadedmetadata', 'canplay', 'playing'].forEach(function(name) {
                  media.addEventListener(name, sources, {passive: true});
                });
              }

              function scan(root) {
                (root || document).querySelectorAll('video,audio').forEach(attach);
              }
              scan(document);
              new MutationObserver(function() { scan(document); }).observe(
                document.documentElement,
                {childList: true, subtree: true}
              );
            })();
            """.trimIndent(),
            null
        )
    }

    private fun registerMediaCandidate(
        tabId: Long,
        rawUrl: String?,
        rawMimeType: String?,
        rawTitle: String?
    ) {
        val url = rawUrl
            ?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return
        val mimeType = rawMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
            ?: mediaMimeTypeForUrl(url)
            ?: return
        val title = rawTitle
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "PiperOS media"
        val candidates = mediaCandidates.getOrPut(tabId) { ConcurrentHashMap() }
        if (candidates.size >= MAX_MEDIA_CANDIDATES && !candidates.containsKey(url)) {
            candidates.keys.firstOrNull()?.let(candidates::remove)
        }
        candidates[url] = BrowserMediaCandidate(url, mimeType, title.take(160))
        if (tabId == activeTabId) {
            runOnUiThread(::updateMediaDownloadButton)
        }
    }

    private fun mediaMimeTypeForUrl(url: String): String? {
        val extension = Uri.parse(url)
            .lastPathSegment
            ?.substringBefore('?')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp", "3gpp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "oga", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> null
        }
    }

    private fun updateMediaDownloadButton() {
        val available = mediaCandidates[activeTabId]?.isNotEmpty() == true
        mediaDownloadButton.visibility = if (available) View.VISIBLE else View.GONE
    }

    private fun showMediaDownloadOptions(tabId: Long, preferredUrl: String? = null) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        val candidates = mediaCandidates[tabId]
            ?.values
            ?.sortedWith(
                compareByDescending<BrowserMediaCandidate> { it.url == preferredUrl }
                    .thenByDescending { it.mimeType?.startsWith("video/") == true }
                    .thenBy { it.title.lowercase() }
            )
            .orEmpty()
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.browser_no_downloadable_media, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { candidate ->
            val format = candidate.url
                .substringBefore('?')
                .substringAfterLast('.', "")
                .takeIf(String::isNotBlank)
                ?.uppercase()
                ?: candidate.mimeType?.substringAfter('/')?.uppercase()
                ?: getString(R.string.browser_media_original_format)
            getString(R.string.browser_media_download_item, format, candidate.title)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.browser_download_media)
            .setMessage(R.string.browser_media_download_note)
            .setItems(labels) { _, which ->
                val candidate = candidates[which]
                requestDownload(
                    PendingDownload(
                        url = candidate.url,
                        userAgent = tab.webView.settings.userAgentString.orEmpty(),
                        contentDisposition = null,
                        mimeType = candidate.mimeType,
                        suggestedFileName = null,
                        referrer = tab.url
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun takeSuggestedDownloadName(url: String): String? {
        suggestedDownloadNames.remove(url)?.let { return it }
        val comparableUrl = url.substringBefore('#')
        val matchingKey = suggestedDownloadNames.keys.firstOrNull {
            it.substringBefore('#') == comparableUrl
        } ?: return null
        return suggestedDownloadNames.remove(matchingKey)
    }

    private fun showExtensionImportConfirmation(uri: Uri) {
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.import_browser_extension),
            message = getString(R.string.extension_security_warning),
            positiveLabel = getString(R.string.import_action)
        ) {
                runCatching { extensionStore.import(uri) }
                    .onSuccess {
                        Toast.makeText(
                            this,
                            getString(R.string.extension_imported, it.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        showExtensionsSheet()
                    }
                    .onFailure {
                        PiperDialog.showMessage(
                            this,
                            getString(R.string.extension_import_failed),
                            it.message ?: getString(R.string.invalid_extension_file)
                        )
                    }
        }
    }

    private fun showExtensionsSheet() {
        val dialog = BottomSheetDialog(this)
        val root = createSheetRoot()
        root.addView(createSheetHeader(getString(R.string.browser_extensions)) {
            dialog.dismiss()
            extensionPickerLauncher.launch(
                arrayOf(
                    "application/json",
                    "application/zip",
                    "application/x-xpinstall",
                    "application/octet-stream",
                    "text/plain"
                )
            )
        })

        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(20))
        }
        val extensions = extensionStore.load()
        if (extensions.isEmpty()) {
            list.addView(
                createSheetText(
                    getString(R.string.no_browser_extensions),
                    14f,
                    Color.parseColor("#AAFFFFFF")
                ).apply {
                    gravity = Gravity.CENTER
                    maxLines = 3
                    setPadding(dp(12), dp(36), dp(12), dp(36))
                }
            )
        } else {
            extensions.forEach { extension ->
                list.addView(createExtensionRow(extension, dialog))
            }
        }
        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        PiperModernUi.apply(root)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun createExtensionRow(
        extension: BrowserExtension,
        dialog: BottomSheetDialog
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            background = ContextCompat.getDrawable(
                this@PiperBrowserActivity,
                R.drawable.bg_browser_surface
            )

            val labels = LinearLayout(this@PiperBrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(createSheetText(extension.name, 15f, Color.WHITE))
                addView(
                    createSheetText(
                        getString(R.string.extension_version, extension.version),
                        11f,
                        Color.parseColor("#99FFFFFF")
                    )
                )
            }
            addView(
                labels,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                SwitchMaterial(this@PiperBrowserActivity).apply {
                    isChecked = extension.enabled
                    setOnCheckedChangeListener { _, enabled ->
                        extensionStore.setEnabled(extension.id, enabled)
                    }
                }
            )
            addView(
                createIconButton(R.drawable.ic_browser_close, R.string.delete_extension).apply {
                    setOnClickListener {
                        extensionStore.delete(extension.id)
                        dialog.dismiss()
                        showExtensionsSheet()
                    }
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun installedVpnApps(): List<Pair<String, String>> {
        val query = Intent(VpnService.SERVICE_INTERFACE)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                query,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(query, PackageManager.MATCH_ALL)
        }
        return services
            .mapNotNull { info ->
                val packageName = info.serviceInfo?.packageName ?: return@mapNotNull null
                if (packageName == this.packageName) return@mapNotNull null
                packageName to info.loadLabel(packageManager).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun selectedVpnLabel(): String {
        val selected = sessionStore.preferredVpnPackage()
            ?: return getString(R.string.system_vpn)
        return installedVpnApps().firstOrNull { it.first == selected }?.second
            ?: getString(R.string.automatic_vpn)
    }

    private fun showVpnDialog() {
        val apps = installedVpnApps()
        val labels = buildList {
            add(getString(R.string.system_vpn_settings))
            add(getString(R.string.automatic_vpn))
            addAll(apps.map { it.second })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_vpn)
            .setMessage(R.string.browser_vpn_explanation)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        sessionStore.setPreferredVpnPackage(null)
                        openSystemVpnSettings()
                    }
                    1 -> openAutomaticVpn(apps)
                    else -> {
                        val selected = apps[which - 2]
                        sessionStore.setPreferredVpnPackage(selected.first)
                        openVpnApp(selected.first)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAutomaticVpn(apps: List<Pair<String, String>>) {
        val preferred = sessionStore.preferredVpnPackage()
            ?.let { packageName -> apps.firstOrNull { it.first == packageName } }
        val selected = preferred ?: apps.firstOrNull()
        if (selected == null) {
            openSystemVpnSettings()
            return
        }
        sessionStore.setPreferredVpnPackage(selected.first)
        openVpnApp(selected.first)
    }

    private fun openVpnApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, R.string.vpn_app_unavailable, Toast.LENGTH_SHORT).show()
            openSystemVpnSettings()
        } else {
            startActivity(launchIntent)
        }
    }

    private fun openSystemVpnSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
    }

    private fun historyDayLabel(timestamp: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        if (sameDay(target, today)) return getString(R.string.today)
        today.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(target, today)) return getString(R.string.yesterday)
        return SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.getDefault())
            .format(Date(timestamp))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun sameDay(first: Calendar, second: Calendar): Boolean =
        first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
            first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)

    private fun createHistoryDayHeader(label: String): TextView =
        createSheetText(label, 13f, ContextCompat.getColor(this, R.color.green_neon)).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(14), dp(4), dp(10))
        }

    private fun applyResponsiveLayout(configuration: Configuration) {
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bottomPanel = findViewById<LinearLayout>(R.id.browserBottomPanel)
        val addressRow = findViewById<LinearLayout>(R.id.browserAddressRow)
        val toolbar = findViewById<LinearLayout>(R.id.browserToolbar)
        bottomPanel.orientation =
            if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        bottomPanel.gravity = Gravity.CENTER_VERTICAL
        addressRow.layoutParams = if (landscape) {
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
        toolbar.layoutParams = if (landscape) {
            LinearLayout.LayoutParams(dp(378), dp(48))
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        }

        findViewById<ImageView>(R.id.browserStartLogo).layoutParams =
            findViewById<ImageView>(R.id.browserStartLogo).layoutParams.apply {
                width = dp(if (landscape) 52 else 72)
                height = dp(if (landscape) 52 else 72)
            }
        findViewById<TextView>(R.id.browserStartTitle).textSize =
            if (landscape) 23f else 30f
        findViewById<View>(R.id.browserStorageNote).visibility =
            if (landscape) View.GONE else View.VISIBLE
    }

    private fun openDownloads() {
        runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            .onFailure {
                Toast.makeText(this, R.string.browser_downloads_unavailable, Toast.LENGTH_SHORT)
                    .show()
            }
    }

    private fun installCredentialCapture(webView: WebView) {
        val script = """
            (function() {
              if (window.__piperosCredentialCaptureInstalled) return;
              window.__piperosCredentialCaptureInstalled = true;
              document.addEventListener('submit', function(event) {
                try {
                  var form = event.target;
                  if (!form || !form.querySelector) return;
                  var passwords = Array.prototype.slice.call(
                    form.querySelectorAll('input[type="password"]')
                  ).filter(function(input) { return input.value && !input.disabled; });
                  if (!passwords.length) return;
                  var password = passwords[passwords.length - 1].value;
                  var username = form.querySelector(
                    'input[autocomplete="username"],input[type="email"],input[name*="user" i],input[name*="email" i],input[type="text"]'
                  );
                  window.$CREDENTIAL_BRIDGE_NAME.propose(
                    location.origin,
                    document.title || location.hostname,
                    username && username.value ? username.value : '',
                    password
                  );
                } catch (_) {}
              }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun showSaveCredentialPrompt(pending: PendingBrowserCredential) {
        val userLabel = pending.username.ifBlank { getString(R.string.browser_account_no_username) }
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.browser_save_credential_title),
            message = getString(
                R.string.browser_save_credential_message,
                userLabel,
                runCatching { Uri.parse(pending.origin).host }.getOrNull() ?: pending.site
            ),
            positiveLabel = getString(R.string.browser_save_credential_action)
        ) {
            BrowserCredentialCaptureSession.pending = pending
            openAccountManager()
        }
    }

    private fun openAccountManager() {
        if (FirebaseAuth.getInstance().currentUser == null) {
            PiperDialog.showMessage(
                this,
                getString(R.string.browser_accounts_title),
                getString(R.string.browser_accounts_sign_in_required),
                R.drawable.ic_browser_lock
            )
            return
        }
        startActivity(Intent(this, BrowserAccountsActivity::class.java))
    }

    private fun requestAndOpenNotificationSettings() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openBrowserNotificationSettings()
        }
    }

    private fun openBrowserNotificationSettings() {
        val promotedIntent = if (Build.VERSION.SDK_INT >= 36) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            null
        }
        val fallbackIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .setData(Uri.parse("package:$packageName"))

        if (promotedIntent == null) {
            startActivity(fallbackIntent)
            return
        }
        runCatching { startActivity(promotedIntent) }
            .onFailure { startActivity(fallbackIntent) }
    }

    private fun showAboutDialog() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val liveUpdateStatus = if (Build.VERSION.SDK_INT >= 36) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.canPostPromotedNotifications()) {
                getString(R.string.live_update_available)
            } else {
                getString(R.string.live_update_disabled)
            }
        } else {
            getString(R.string.normal_notifications_available)
        }

        val message = getString(
            R.string.browser_about_message,
            packageInfo.versionName ?: "-",
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            liveUpdateStatus,
            selectedUserAgent().label
        )
        PiperDialog.showMessage(
            context = this,
            title = getString(R.string.about_piperos_browser),
            message = message,
            icon = R.drawable.a3tn
        )
    }

    private fun openExternalUri(uri: Uri): Boolean {
        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrElse {
            Toast.makeText(this, R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun showKeyboard(view: View) {
        view.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q="
        private const val INCOGNITO_PROFILE_PREFIX = "piperos_incognito_"
        private const val DOWNLOAD_BRIDGE_NAME = "PiperDownloadMetadata"
        private const val CREDENTIAL_BRIDGE_NAME = "PiperCredentialVault"
        private const val MAX_SUGGESTED_DOWNLOADS = 64
        private const val MAX_MEDIA_CANDIDATES = 24
        private const val SCROLL_UP = -1
        private const val SCROLL_DOWN = 1
        private const val SCROLL_SEQUENCE_TIMEOUT_MS = 180L
        private const val CHROME_SCROLL_SUPPRESSION_MS = 420L
        private const val DESKTOP_DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        private val TRACKER_HOST_HINTS = setOf(
            "doubleclick.net",
            "google-analytics.com",
            "googletagmanager.com",
            "googlesyndication.com",
            "adservice.google.com",
            "connect.facebook.net",
            "analytics.facebook.com",
            "facebook.net",
            "hotjar.com",
            "clarity.ms",
            "segment.io",
            "segment.com",
            "mixpanel.com",
            "amplitude.com",
            "appsflyer.com",
            "branch.io",
            "scorecardresearch.com",
            "quantserve.com",
            "taboola.com",
            "outbrain.com"
        )

    }
}
