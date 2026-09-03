package com.wanlongzhou.supply

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 万龙洲供应链申购系统 - 安卓壳（准原生增强版） */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var splash: View
    private lateinit var errorView: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 业务页真实地址（含 rev 段），供后台更新用 */
    @Volatile
    private var bizUrl: String? = null

    /** 业务页声明的最新 APK 元信息（应用内更新用） */
    private var latestAppUpdate: JSONObject? = null
    private var apkDownloadId: Long = -1L
    private var apkFileName: String = ""
    private var downloadManager: DownloadManager? = null

    /** 下载完成广播接收器：自动拉起安装 */
    private val apkDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE &&
                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == apkDownloadId
            ) {
                promptInstall()
            }
        }
    }

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

        // 应用内更新依赖的下载管理器：包 try-catch，避免个别机型异常导致冷启动闪退
        try {
            downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            registerReceiver(apkDownloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        } catch (e: Exception) {
            Log.w("WlzApp", "下载管理器初始化失败，应用内更新暂不可用: ${e.message}")
        }

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

    /** 状态栏 / 导航栏统一为品牌色 */
    private fun applySystemBars() {
        window.statusBarColor = getColorCompat(R.color.colorPrimaryDark)
        window.navigationBarColor = getColorCompat(R.color.colorPrimaryDark)
    }

    @Suppress("DEPRECATION")
    private fun getColorCompat(id: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id) else resources.getColor(id)

    private var pageAtTop = true

    /** 主地址带时间戳加载，确保每次拿到最新外壳页（业务页走本地缓存） */
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

        // 关键 1：禁用网络缓存，根治「新单据闪现即消失 / 状态回退」
        s.cacheMode = WebSettings.LOAD_NO_CACHE

        // 关键 2：启用 localStorage（草稿机制依赖，关掉会丢草稿）
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
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        s.userAgentString = s.userAgentString + " WLZSupply/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(WbScrollBridge(), "__wbScroll")
        webView.addJavascriptInterface(WlzAppBridge(), "__wlzApp")

        webView.webViewClient = object : WebViewClient() {
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
                return null
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
                        } catch (e: Exception) {
                        }
                        true
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                splash.postDelayed({ splash.visibility = View.GONE }, 250)
                checkUpdateAsync(notify = false)
                if (isBizPage(url)) {
                    webView.evaluateJavascript(SCROLL_JS, null)
                }
                webView.evaluateJavascript(HIDE_FAB_JS, null)
            }

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

            // 必须处理 alert / confirm / prompt，否则网页弹窗不显示，confirm 卡死流程
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
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开下载链接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 注入的滚动探测脚本：实时上报「是否在顶部」，供下拉刷新判断 */
    private val SCROLL_JS = """
        (function(){
          function report(e){
            var top = (window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0);
            var inner = 0;
            if (e && e.target && e.target !== window && e.target !== document && e.target.scrollTop != null) inner = e.target.scrollTop;
            var atTop = (top <= 0 && inner <= 0) ? 1 : 0;
            if (window.__wbScroll) window.__wbScroll.atTop(atTop);
          }
          window.addEventListener('scroll', function(e){ report(e); }, true);
          report(null);
        })();
    """.trimIndent()

    /** 隐藏平台「回WorkBuddy继续聊」浮窗（只按文本命中，不宽泛匹配 class） */
    private val HIDE_FAB_JS = """
        (function(){
          function hideEl(el){ if(el && el.style){ el.style.display='none'; el.style.visibility='hidden'; el.style.pointerEvents='none'; } }
          var clsRe = /wk-fab|chat-float|float-chat|back-to|continue-chat|floating-btn|fab-btn/i;
          function hideWbFab(){
            try{
              var nodes = document.querySelectorAll('a,button,div,span,img,svg');
              for(var i=0;i<nodes.length;i++){
                var el = nodes[i];
                var txt = (el.innerText||el.textContent||'').trim();
                var cls = (el.className||'').toString();
                if(txt && (txt.indexOf('回WorkBuddy')>=0 || txt.indexOf('继续聊')>=0)){ hideEl(el); }
                else if(clsRe.test(cls)){ hideEl(el); }
              }
              var links = document.querySelectorAll('a[href*="workbuddy"]');
              for(var j=0;j<links.length;j++){
                var l = links[j], lt = (l.innerText||'').trim();
                if(lt.indexOf('回WorkBuddy')>=0 || lt.indexOf('继续聊')>=0){ hideEl(l); }
              }
            }catch(e){}
          }
          hideWbFab();
          [600,1500,3000,6000].forEach(function(t){ setTimeout(hideWbFab, t); });
          if(window.MutationObserver){
            try{
              var timer = null;
              new MutationObserver(function(){
                if(timer) return;
                timer = setTimeout(function(){ timer=null; hideWbFab(); }, 120);
              }).observe(document.body, {childList:true, subtree:true});
            }catch(e){}
          }
        })();
    """.trimIndent()

    /** JS 桥：页面上报是否在页面顶部 */
    inner class WbScrollBridge {
        @JavascriptInterface
        fun atTop(v: Int) {
            pageAtTop = v == 1
        }
    }

    /** 是否业务页（可缓存的那一层），按路径尾匹配 */
    private fun isBizPage(url: String): Boolean =
        url.contains("/page/") && url.substringBefore("?").endsWith("hotel_requisition.html")

    // ===== 下拉刷新 =====
    private fun setupSwipe() {
        swipe.setColorSchemeColors(getColorCompat(R.color.colorPrimary))
        swipe.setOnRefreshListener { trySoftRefresh(0) }
        swipe.isEnabled = true
        swipe.setOnChildScrollUpCallback { _, _ -> !pageAtTop }
        val triggerPx = (resources.displayMetrics.density * 140).toInt()
        swipe.setDistanceToTriggerSync(triggerPx)
    }

    /** 软刷新当前视图：始终不 reload，避免外壳页重新鉴权跳登录 */
    private fun trySoftRefresh(retry: Int) {
        val js = "(function(){try{return (window.__wlzSoftRefresh && window.__wlzSoftRefresh()===true)?'ok':'no';}catch(e){return 'no';}})()"
        webView.evaluateJavascript(js) { res ->
            val ok = res != null && res.contains("ok")
            when {
                ok -> mainHandler.postDelayed({ swipe.isRefreshing = false }, 600)
                retry < 5 -> mainHandler.postDelayed({ trySoftRefresh(retry + 1) }, 400)
                else -> {
                    swipe.isRefreshing = false
                    Toast.makeText(this, "页面还在加载，请稍候再拉", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===== 热更新：后台拉取业务页，按 APP_VERSION 判断是否更新 =====
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
                val appUp = extractAppUpdate(head)
                if (appUp != null) {
                    latestAppUpdate = appUp
                    maybePromptAppUpdate()
                }
                val ver = PageCache.extractVersion(head)
                if (ver == null) {
                    if (notify) mainHandler.post { toast("未能识别页面版本") }
                    return@Thread
                }
                val updated = PageCache.save(this@MainActivity, bytes, ver)
                mainHandler.post {
                    when {
                        updated && notify -> toast("已更新到 $ver，下次启动自动应用")
                        updated -> toast("已下载新版 $ver，下次启动自动应用")
                        notify -> toast("已是最新（$ver）")
                    }
                    // 更新已写入本地缓存，下次请求时 shouldInterceptRequest 自动返回新版，不在此时重启应用（会闪烁）
                }
            } catch (e: Exception) {
                if (notify) mainHandler.post { toast("检查更新失败：${e.message ?: "网络异常"}") }
            }
        }.start()
    }

    // ===== 应用内更新提示 =====
    private fun extractAppUpdate(head: String): JSONObject? {
        val m = Regex("""window\.WLZ_APP_UPDATE\s*=\s*(\{[^\n]*?\});""").find(head) ?: return null
        return try { JSONObject(m.groupValues[1]) } catch (e: Exception) { null }
    }

    /** 业务页声明更高 APK 版本时，弹「发现新版本」对话框（每版本仅提示一次） */
    private fun maybePromptAppUpdate() {
        val up = latestAppUpdate ?: return
        val remoteCode = try { up.getInt("versionCode") } catch (e: Exception) { return }
        if (remoteCode <= BuildConfig.VERSION_CODE) return
        val key = "apk_update_prompted_$remoteCode"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        val name = up.optString("versionName", remoteCode.toString())
        val note = up.optString("note", "")
        val url = up.optString("url", "")
        if (url.isBlank()) return
        mainHandler.post {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("发现新版本 v$name")
                .setMessage(if (note.isBlank()) "有新版可用，点击下载更新。" else note)
                .setPositiveButton("立即下载") { _, _ -> downloadApk(url, name) }
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show()
        }
    }

    /** 用系统 DownloadManager 下载 APK 到应用私有下载目录 */
    private fun downloadApk(url: String, versionName: String) {
        try {
            val dm = downloadManager
                ?: (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
            apkFileName = "wanlongzhou-supply-v$versionName.apk"
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("万龙洲供应链 App v$versionName")
                setDescription("正在下载更新包…")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, apkFileName)
            }
            apkDownloadId = dm.enqueue(req)
            Toast.makeText(this, "开始下载更新包，完成后自动提示安装", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    /** 下载完成后拉起系统安装器（FileProvider 暴露私有下载目录，兼容 Android 7+） */
    private fun promptInstall() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            val file = File(dir, apkFileName)
            if (!file.exists()) {
                Toast.makeText(this, "安装包未找到", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "无法安装：${e.message ?: "未知错误"}（请到设置中允许「安装未知应用」）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** JS 桥：网页登录后可调用 window.__wlzApp.checkUpdate() 主动触发检测 */
    inner class WlzAppBridge {
        @JavascriptInterface
        fun checkUpdate() {
            mainHandler.post { maybePromptAppUpdate() }
        }
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

    /** 清缓存重新加载（刻意不清 localStorage，否则丢草稿） */
    private fun clearCacheAndReload() {
        webView.clearCache(true)
        webView.clearFormData()
        loadHome()
        toast("已清缓存并重新加载")
    }

    /** 服务器地址配置：后期迁 NAS 时改这里即可 */
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

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(apkDownloadReceiver) } catch (e: Exception) {}
    }

    companion object {
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