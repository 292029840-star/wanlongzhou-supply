package com.wanlongzhou.supply

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 万龙洲供应链申购系统 —— 安卓壳（准原生增强版）
 *
 * ── 设计原则 ────────────────────────────────────────────
 * 界面 / 业务逻辑 100% 由网页端承载，本壳不碰任何业务代码，只做：
 *
 *   1. 关掉 WebView 网络缓存 —— 根治「新单据闪现即消失 / 状态回退」
 *   2. 打开 localStorage    —— 草稿机制依赖，关掉会丢草稿
 *   3. 本地缓存业务页 + 热更新 —— 秒开，且改网页【不用重新打包】
 *   4. 闪屏 / 下拉刷新 / 返回键 / 原生打印 / 断网提示 —— 抹掉「网页感」
 *
 * ── 为什么缓存是安全的（改动前必读，见 PageCache 注释）────────
 * 线上是两层：外壳页（4KB，注入 SDK 与登录态，【必须走网络】）
 *            + 业务页（540KB，纯静态无 token，【可安全缓存】）
 * 这里只缓存后者，因此不会影响登录态与数据访问。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var splash: View
    private lateinit var errorView: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 业务页真实地址（含 rev 段，首次由 WebView 请求时捕获，供后台更新用） */
    @Volatile
    private var bizUrl: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var lastBackAt = 0L

    /** 当前生效的业务地址（可在 App 内改，默认取线上地址） */
    private val homeUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)
        splash = findViewById(R.id.splash)
        errorView = findViewById(R.id.errorView)

        applySystemBars()
        setupWebView()
        setupSwipe()

        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            errorView.visibility = View.GONE
            splash.visibility = View.VISIBLE
            loadHome()
        }

        if (savedInstanceState == null) {
            loadHome()
        } else {
            webView.restoreState(savedInstanceState)
            splash.visibility = View.GONE
        }
    }

    /** 状态栏 / 导航栏统一为品牌色，去掉视觉割裂 */
    private fun applySystemBars() {
        window.statusBarColor = getColorCompat(R.color.colorPrimaryDark)
        window.navigationBarColor = getColorCompat(R.color.colorPrimaryDark)
    }

    @Suppress("DEPRECATION")
    private fun getColorCompat(id: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id) else resources.getColor(id)

    /** 主地址带时间戳加载，确保每次拿到最新外壳页（业务页走本地缓存，见拦截逻辑） */
    private fun loadHome() {
        val url = homeUrl
        val sep = if (url.contains("?")) "&" else "?"
        errorView.visibility = View.GONE
        splash.visibility = View.VISIBLE
        webView.loadUrl(url + sep + "_t=" + System.currentTimeMillis())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings

        s.javaScriptEnabled = true

        // ===== 关键 1：禁用 WebView 网络缓存 =====
        // 安卓 WebView 默认会缓存请求，把「写库之前」的旧数据返回给页面，
        // 表现为：新单据闪现一下就消失、点发货后状态又变回「待发货」。
        // 桌面 Chrome 与 iOS Safari 缓存策略没这么激进，所以只有安卓复现。
        s.cacheMode = WebSettings.LOAD_NO_CACHE

        // ===== 关键 2：必须开启 localStorage =====
        // 系统的草稿机制、本地缓存都依赖它，关掉会丢草稿。
        s.domStorageEnabled = true
        s.databaseEnabled = true

        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadsImagesAutomatically = true
        s.defaultTextEncodingName = "UTF-8"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 允许 https 页面里的 http 子资源（后期内网 NAS 多为 http）
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        s.userAgentString = s.userAgentString + " WLZSupply/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            /**
             * 拦截业务页请求：命中则用本地缓存直接返回（秒开），
             * 否则放行走网络。同时记下真实地址，供后台热更新使用。
             *
             * 注意：只拦截业务页（hotel_requisition.html）。
             * 外壳页与所有数据接口一律放行，否则会拿不到登录态。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (!isBizPage(url)) return null

                bizUrl = url

                val cached = PageCache.bytes(this@MainActivity)
                if (cached != null) {
                    return WebResourceResponse(
                        "text/html", "utf-8",
                        ByteArrayInputStream(cached)
                    )
                }
                return null // 无缓存 → 走网络，后台会立刻补存
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return when {
                    url.startsWith("http://") || url.startsWith("https://") ||
                            url.startsWith("about:") -> false
                    else -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {
                        }
                        true
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                // 稍作延迟再收闪屏，避免页面刚渲染完就闪一下白底
                splash.postDelayed({ splash.visibility = View.GONE }, 250)
                // 每次加载完顺带检查一次更新（异步，不阻塞）
                checkUpdateAsync(notify = false)
            }

            // 主框架加载失败才弹错误页；子资源（图片等）失败忽略，避免误伤
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError?
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    request.isForMainFrame && !isBizPage(request.url.toString())
                ) {
                    showNetworkError(error?.description?.toString())
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            // 必须处理 alert / confirm / prompt：
            // 不接管的话，网页里的弹窗在 WebView 中不会显示，
            // confirm 会一直拿不到结果，业务流程直接卡死。
            override fun onJsAlert(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("提示")
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("确认")
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView, url: String, message: String,
                defaultValue: String, result: JsPromptResult
            ): Boolean {
                val input = EditText(this@MainActivity)
                input.setText(defaultValue ?: "")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("请输入")
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }

            // 支持网页里的文件上传（选图片、导入等）
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (_: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        // 下载交给系统（导出 Excel 等）
        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开下载链接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 是否业务页（可缓存的那一层），按路径尾匹配，忽略 rev 段变化 */
    private fun isBizPage(url: String): Boolean =
        url.contains("/page/") && url.substringBefore("?").endsWith("hotel_requisition.html")

    // ===== 下拉刷新 =====
    private fun setupSwipe() {
        swipe.setColorSchemeColors(getColorCompat(R.color.colorPrimary))
        swipe.setOnRefreshListener {
            webView.reload()
            // 兜底：若页面已完成回调未触发（极少见），4 秒后强制收起
            mainHandler.postDelayed({ swipe.isRefreshing = false }, 4000)
        }
        // 只有页面滚到顶部时才允许下拉，避免与页面内滚动冲突
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipe.isEnabled = scrollY == 0
        }
    }

    // ===== 热更新：后台拉取业务页，按 APP_VERSION 判断是否需要更新 =====
    /**
     * @param notify 是否提示结果（菜单里手动检查时用 true，自动检查静默进行）
     *
     * 更新只在【下次启动】生效，不打断当前操作。
     */
    private fun checkUpdateAsync(notify: Boolean) {
        val url = bizUrl ?: run {
            if (notify) toast("尚未获取到页面地址，请稍候再试")
            return
        }
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", webView.settings.userAgentString)
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    if (notify) mainHandler.post { toast("检查失败（HTTP $code）") }
                    return@Thread
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                val head = String(bytes, 0, minOf(bytes.size, 200_000), Charsets.UTF_8)
                val ver = PageCache.extractVersion(head)
                if (ver == null) {
                    if (notify) mainHandler.post { toast("未能识别页面版本") }
                    return@Thread
                }
                val updated = PageCache.save(this@MainActivity, bytes, ver)
                mainHandler.post {
                    when {
                        updated && notify -> toast("已更新到 $ver，重启 App 生效")
                        updated -> toast("已下载新版 $ver，下次启动生效")
                        notify -> toast("已是最新（$ver）")
                    }
                }
            } catch (e: Exception) {
                if (notify) mainHandler.post { toast("检查更新失败：${e.message ?: "网络异常"}") }
            }
        }.start()
    }

    private fun showNetworkError(desc: String?) {
        val v = errorView
        v.findViewById<TextView>(R.id.errorText).text =
            if (isOnline()) (desc ?: "页面加载失败") else getString(R.string.net_error_msg)
        v.visibility = View.VISIBLE
        splash.visibility = View.GONE
        progress.visibility = View.GONE
        swipe.isRefreshing = false
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.activeNetwork?.let { n ->
                cm.getNetworkCapabilities(n)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } ?: false
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected ?: false
        }
    }

    // ===== 原生打印（A4）=====
    private fun printCurrentPage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            toast("系统版本过低，不支持打印")
            return
        }
        val pm = getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (pm == null) {
            toast("当前设备不支持打印")
            return
        }
        @Suppress("DEPRECATION")
        val adapter = webView.createPrintDocumentAdapter(getString(R.string.app_name))
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("wlz", "A4", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        pm.print(getString(R.string.print_job_name), adapter, attrs)
    }

    // ===== 菜单 =====
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REFRESH, 0, R.string.menu_refresh)
        menu.add(0, MENU_CHECK_UPDATE, 0, R.string.menu_check_update)
        menu.add(0, MENU_PRINT, 0, R.string.menu_print)
        menu.add(0, MENU_SET_URL, 0, R.string.menu_set_url)
        menu.add(0, MENU_RESET_URL, 0, R.string.menu_reset_url)
        menu.add(0, MENU_CLEAR_CACHE, 0, R.string.menu_clear_cache)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_REFRESH -> {
                clearCacheAndReload()
                true
            }
            MENU_CHECK_UPDATE -> {
                checkUpdateAsync(notify = true)
                true
            }
            MENU_PRINT -> {
                printCurrentPage()
                true
            }
            MENU_SET_URL -> {
                showUrlDialog()
                true
            }
            MENU_RESET_URL -> {
                prefs.edit().putString(KEY_URL, DEFAULT_URL).apply()
                toast("已恢复默认地址")
                loadHome()
                true
            }
            MENU_CLEAR_CACHE -> {
                PageCache.clear(this)
                toast("本地页面缓存已清除，下次启动重新下载")
                loadHome()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 清缓存重新加载。
     * 注意：这里刻意【不清 localStorage】，否则会连同草稿一起清掉。
     */
    private fun clearCacheAndReload() {
        webView.clearCache(true)
        webView.clearFormData()
        loadHome()
        toast("已清缓存并重新加载")
    }

    /** 服务器地址配置：后期迁 NAS 时改这里即可，不用重新打包 App */
    private fun showUrlDialog() {
        val input = EditText(this)
        input.setText(homeUrl)
        input.setSingleLine(true)

        AlertDialog.Builder(this)
            .setTitle("服务器地址")
            .setMessage("后期迁到 NAS 后，把这里改成 NAS 上的访问地址即可，App 无需重新打包。")
            .setView(input)
            .setPositiveButton("保存并打开") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isBlank()) {
                    toast("地址不能为空")
                    return@setPositiveButton
                }
                val finalUrl =
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
                prefs.edit().putString(KEY_URL, finalUrl).apply()
                webView.clearCache(true)
                loadHome()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 返回键：优先网页内回退；已到首页则双击退出 =====
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000) {
                finish()
            } else {
                lastBackAt = now
                toast("再按一次退出")
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Deprecated("deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = filePathCallback ?: return
            filePathCallback = null
            val results: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                when {
                    clip != null && clip.itemCount > 0 ->
                        Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            cb.onReceiveValue(results)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    companion object {
        /**
         * 外壳页地址（线上系统的入口）。
         * 注意：这里必须是 workbuddy.link 的页面地址，不能改成静态业务页地址
         * —— 登录态与数据库 SDK 由外壳页注入，直接用静态页会导致系统不可用。
         */
        private const val DEFAULT_URL = "https://www.workbuddy.link/p/1n4Jb5OioGpFwab2YHFXTs"
        private const val PREFS = "wlz_supply"
        private const val KEY_URL = "server_url"
        private const val REQ_FILE = 1001

        private const val MENU_REFRESH = 1
        private const val MENU_SET_URL = 2
        private const val MENU_RESET_URL = 3
        private const val MENU_PRINT = 4
        private const val MENU_CHECK_UPDATE = 5
        private const val MENU_CLEAR_CACHE = 6
    }
}
