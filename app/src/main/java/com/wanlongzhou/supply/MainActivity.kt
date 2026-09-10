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
import android.webkit.ConsoleMessage
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

    /** v1.8.5：最近若干条控制台日志，白屏时展示出来便于定位（只留最近 30 条） */
    private val consoleLog = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** v1.8.6：看门狗状态 —— 业务页与外壳页是跨域 iframe，原生读不到 iframe 正文，
     *  必须靠「业务页主动报到(booted) + 控制台活性」判断页面是否真的活着，否则必然误报白屏。 */
    @Volatile private var bootReported = false      // 业务页已调用 __wlzApp.booted() 报到
    @Volatile private var lastConsoleAt = 0L        // 最近一次控制台输出时间（任何页面）
    @Volatile private var blankErrorShowing = false // 当前错误页是否为白屏看门狗弹出的
    private var blankChecks = 0                     // 看门狗已检查轮数

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
        // v1.8.6：重置看门狗状态（新一轮加载）
        bootReported = false
        blankChecks = 0
        blankErrorShowing = false
        mainHandler.removeCallbacks(blankCheckRunnable)
        mainHandler.removeCallbacks(splashFallbackRunnable)
        webView.loadUrl(url + sep + "_t=" + System.currentTimeMillis())
    }

    /** v1.8.6：业务页迟迟不报到时的兜底收 loading（外壳页 onPageFinished 后 20 秒）。
     *  之前 250ms 就收 loading，而 iframe 在手机网络上还要再拉几秒数据，用户看到的就是白屏。 */
    private val splashFallbackRunnable = Runnable {
        if (!bootReported && errorView.visibility != View.VISIBLE) splash.visibility = View.GONE
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
                // v1.8.7：恢复 v1.8.3 的「秒开」拦截（用户实测 1.8.3 好用、1.8.4+ 空白）。
                // 背景：1.8.4 曾因「外壳页被 HIDE_FAB 误隐藏导致白屏」把拦截一并砍掉，
                // 结果业务页每次启动都要手机现场下载 540KB + 全量拉云数据，弱网下就是转圈→空白。
                // 真正的白屏根因（HIDE_FAB 误伤外壳页、看门狗误报）已在 1.8.4/1.8.6 分别修掉，
                // 缓存本身是无辜的（业务页纯静态、按 APP_VERSION 只增不改），恢复拦截：
                val url = request.url.toString()
                if (!isBizPage(url)) return null
                bizUrl = url // 记录真实地址，供热更新/更新检测用

                // 有本地缓存 → 直接秒开（后台 checkUpdateAsync 会拉新版，下次启动生效）
                val cached = PageCache.bytes(this@MainActivity)
                if (cached != null) {
                    return WebResourceResponse(
                        "text/html", "utf-8",
                        ByteArrayInputStream(cached)
                    )
                }
                // 无缓存（首次/刚清缓存）→ 走网络下载，后台随即写入 PageCache 供下次秒开
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
                // v1.8.6：不再立刻收 loading —— 业务页(iframe)在外壳页 load 完后还要拉数据，
                // 提前收掉就是一段白屏。改为：业务页 __wlzApp.booted() 报到时收，或 20s 兜底收。
                mainHandler.removeCallbacks(splashFallbackRunnable)
                mainHandler.postDelayed(splashFallbackRunnable, 20000)
                scheduleBlankCheck()
                checkUpdateAsync(notify = false)
                // 隐藏平台「回WorkBuddy继续聊」浮窗：外壳页 + 业务页都注入（v1.8.2 起改安全版，可作用外壳页；
                // 旧版只敢注入业务页，外壳页注入会误隐藏 React 挂载点导致整页白屏）
                webView.evaluateJavascript(HIDE_FAB_JS, null)
                // 滚动探测只对业务页有意义（外壳页无 __wbScroll 桥）
                if (isBizPage(url)) {
                    webView.evaluateJavascript(SCROLL_JS, null)
                }
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
            // v1.8.5：记录控制台输出，白屏时把最近的错误显示给用户（截图即可定位）
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                try {
                    val lvl = when (m.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        ConsoleMessage.MessageLevel.WARNING -> "WARN"
                        else -> "LOG"
                    }
                    consoleLog.add("$lvl ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                    while (consoleLog.size > 30) consoleLog.removeAt(0)
                    // v1.8.6：记录控制台活性 —— 业务页还在输出日志就说明页面活着，
                    // 不能因为跨域 iframe 读不到正文就判白屏
                    lastConsoleAt = System.currentTimeMillis()
                    if (m.message().contains("AppLog")) {
                        // 业务页真的跑起来了：即便错误页已经弹出，也立刻撤掉（属于误报）
                        if (blankErrorShowing) mainHandler.post { hideBlankError() }
                    }
                } catch (_: Exception) {
                }
                return false
            }

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

    /** 隐藏平台「回WorkBuddy继续聊」浮窗。
     *  v1.8.2 起外壳页 + 业务页都注入，因此必须是"安全版"：
     *   ① 文本命中加长度守卫 t.length<=80 —— v1.3 白屏根因：FAB 文案的祖先容器 textContent 也含该子串，
     *      命中祖先会把整个 #root 藏成白屏。
     *   ② 直接子元素数守卫 children.length<=5 —— React 挂载点 / 路由容器直接子元素极多，FAB 本身很小；O(1) 判断。
     *   ③ 显式跳过 body/html 与 id 命中 root|app|main|container|shell|viewport 的容器。
     *   ④ MutationObserver 只做有界增量扫描（仅新增节点子树，上限 400 节点）—— 外壳页 SPA + 业务页都高频重渲染，全页重扫会卡。
     */
    private val HIDE_FAB_JS = """
        (function(){
          function hideEl(el){ if(el && el.style){ el.style.display='none'; el.style.visibility='hidden'; el.style.pointerEvents='none'; } }
          var clsRe = /wk-fab|chat-float|float-chat|back-to|continue-chat|floating-btn|fab-btn/i;
          var skipIdRe = /(^|[-_])(root|app|main|container|shell|viewport)([-_]|$)/i;
          var hidden = (typeof WeakSet==='function') ? new WeakSet() : null;
          function isHidden(el){ return hidden ? hidden.has(el) : false; }
          function mark(el){ if(hidden){ try{ hidden.add(el); }catch(e){} } }
          function txt(el){ try{ return (el.innerText||el.textContent||'').trim(); }catch(e){ return ''; } }
          function safe(el){
            if(!el || el.nodeType!==1) return false;
            if(el===document.body || el===document.documentElement) return false;
            var id = el.id || '';
            if(id && skipIdRe.test(id)) return false;
            try{ if(el.children && el.children.length>5) return false; }catch(e){ return false; }
            return true;
          }
          function hit(el){
            if(!safe(el) || isHidden(el)) return false;
            var cls = (el.className||'').toString();
            if(clsRe.test(cls)) return true;
            var t = txt(el);
            return t.length>0 && t.length<=80 && (t.indexOf('回WorkBuddy')>=0 || t.indexOf('继续聊')>=0);
          }
          function walk(node){
            if(!node || node.nodeType!==1) return;
            if(hit(node)){ hideEl(node); mark(node); return; }
            try{
              var d = node.querySelectorAll('a,button,div,span,img,svg');
              for(var i=0;i<d.length && i<400;i++){ if(hit(d[i])){ hideEl(d[i]); mark(d[i]); } }
              var ls = node.querySelectorAll('a[href*="workbuddy"]');
              for(var j=0;j<ls.length;j++){
                var l = ls[j], lt = txt(l);
                if(safe(l) && (lt.indexOf('回WorkBuddy')>=0 || lt.indexOf('继续聊')>=0)){ hideEl(l); mark(l); }
              }
            }catch(e){}
          }
          function hideWbFab(){ if(document.body){ walk(document.body); } }
          hideWbFab();
          [500,1200,2500,5000,10000,20000,40000].forEach(function(t){ setTimeout(hideWbFab, t); });
          if(window.MutationObserver){
            try{
              var timer = null;
              new MutationObserver(function(muts){
                if(timer) return;
                timer = setTimeout(function(){
                  timer = null;
                  for(var i=0;i<muts.length;i++){
                    var an = muts[i].addedNodes;
                    for(var k=0;k<an.length;k++){ walk(an[k]); }
                  }
                }, 150);
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

    // ===== 下拉刷新（v1.8：用户要求禁用下拉刷新手势——下拉只滚动页面，不再触发刷新；
    //       trySoftRefresh 保留供后续菜单入口复用，isEnabled=false 后手势不会再触发） =====
    private fun setupSwipe() {
        swipe.setColorSchemeColors(getColorCompat(R.color.colorPrimary))
        swipe.setOnRefreshListener { trySoftRefresh(0) }
        swipe.isEnabled = false
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
    /** [force]=true 用于菜单主动「检查更新」：忽略静默/冷却状态强制弹出，并给出 App 版本结论 */
    private fun checkUpdateAsync(notify: Boolean, force: Boolean = false) {
        val url = bizUrl ?: run {
            if (notify) toast("尚未获取到页面地址，请稍候再试")
            return
        }
        // WebView 方法必须在 UI 线程调用：后台线程启动前读取 UA，避免 wrong thread 异常
        val userAgent = webView.settings.userAgentString
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", userAgent)
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
                    maybePromptAppUpdate(force)
                } else if (force) {
                    // 业务页未声明更新元信息（非本壳打包的页面），主动检查时给个明确结论
                    mainHandler.post { toast("该页面未声明 App 版本信息") }
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

    /** 业务页声明更高 APK 版本时，弹「发现新版本」对话框。
     *  「稍后」= 冷却 72 小时内不再提醒，超过后下次启动重新弹出；
     *  「以后不再提示」= 该版本永久静默；
     *  [force]=true（菜单主动检查）忽略上述状态强制弹出。 */
    private fun maybePromptAppUpdate(force: Boolean = false) {
        val up = latestAppUpdate ?: return
        val remoteCode = try { up.getInt("versionCode") } catch (e: Exception) { return }
        if (remoteCode <= BuildConfig.VERSION_CODE) {
            if (force) mainHandler.post { toast("App 已是最新版本 v${BuildConfig.VERSION_NAME}") }
            return
        }
        val silentKey = "apk_update_silenced_$remoteCode"
        if (!force && prefs.getBoolean(silentKey, false)) return
        val coolKey = "apk_update_dismissed_$remoteCode"
        if (!force && prefs.getLong(coolKey, 0L).let { d ->
            d > 0L && System.currentTimeMillis() - d < UPDATE_COOLDOWN_MS
        }) return
        val name = up.optString("versionName", remoteCode.toString())
        val note = up.optString("note", "")
        val url = up.optString("url", "")
        if (url.isBlank()) return
        mainHandler.post {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("发现新版本 v$name")
                .setMessage(if (note.isBlank()) "有新版可用，点击下载更新。" else note)
                .setCancelable(false)
                .setPositiveButton("立即下载") { _, _ -> downloadApk(url, name) }
                .setNeutralButton("以后不再提示") { _, _ ->
                    prefs.edit().putBoolean(silentKey, true).remove(coolKey).apply()
                }
                .setNegativeButton("稍后") { _, _ ->
                    prefs.edit().remove(silentKey).putLong(coolKey, System.currentTimeMillis()).apply()
                }
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

        /**
         * v1.8.6 首屏报到：业务页首屏渲染完成（登录页显示或已进入应用）后调用，
         * 原生据此收掉 loading 并撤销白屏看门狗。跨域 iframe 的正文原生读不到，
         * 这个报到信号是判断「页面真的好了」最可靠的依据。
         */
        @JavascriptInterface
        fun booted() {
            bootReported = true
            mainHandler.post {
                splash.visibility = View.GONE
                mainHandler.removeCallbacks(blankCheckRunnable)
                mainHandler.removeCallbacks(splashFallbackRunnable)
                if (blankErrorShowing) hideBlankError()
            }
        }

        /**
         * v1.8.4 导出落盘：WebView 里 blob:/a.click() 下载会被静默丢弃（DownloadListener 收不到 blob:），
         * 网页把导出内容转 base64 调本方法，由原生写入系统「下载」目录并 toast 路径。
         * API 29+ 走 MediaStore.Downloads（用户在文件管理/下载里直接可见）；26-28 落 App 私有 Download 目录。
         */
        @JavascriptInterface
        fun saveFile(name: String, base64: String) {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val safeName = name.replace('/', '_').replace('\\', '_')
                    .ifBlank { "export_${System.currentTimeMillis()}" }
                val where: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeFor(safeName))
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = applicationContext.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("系统拒绝了写入请求")
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("无法打开输出流")
                    "下载/" + safeName
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: throw IllegalStateException("存储不可用")
                    val f = java.io.File(dir, safeName)
                    java.io.FileOutputStream(f).use { it.write(bytes) }
                    f.absolutePath
                }
                mainHandler.post { toast("已保存：$where") }
            } catch (e: Exception) {
                mainHandler.post { toast("保存失败：${e.message ?: "未知错误"}") }
            }
        }

        private fun mimeFor(name: String): String = when {
            name.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            name.endsWith(".xls", true) -> "application/vnd.ms-excel"
            name.endsWith(".csv", true) -> "text/csv"
            name.endsWith(".json", true) -> "application/json"
            else -> "application/octet-stream"
        }
    }

    // ===== v1.8.5 白屏看门狗 / v1.8.6 修正误报 =====
    // 背景：外壳页(4KB)加载成功后由 React 去装载 540KB 的业务页；若业务页拉取失败或 JS 报错，
    // 老版本只留下「有 Logo 然后白屏」，没有任何提示。
    // v1.8.6 教训（实测截图）：业务页与外壳页是跨域 iframe，原生 evaluateJavascript 只作用
    // 外壳页主框架 —— document.body.innerText 永远读不到 iframe 里的登录页/首页内容，
    // 旧逻辑 8 秒后必然误报白屏，还用全屏错误页把正常页面盖住（业务页日志显示 loadAll 早已完成）。
    // 修正为三重信号：① 业务页 __wlzApp.booted() 报到 → 立即收工；
    //                 ② 控制台仍在输出（AppLog）→ 页面活着，继续等，绝不弹错误页；
    //                 ③ 外壳页本身有可见正文/可见容器 → ok。
    // 同时错误页支持「迟到自动撤销」：弹错之后业务页日志又来了 → 自动撤掉错误页。
    private val blankCheckRunnable = Runnable { checkBlank() }

    private fun scheduleBlankCheck() {
        blankChecks = 0
        mainHandler.removeCallbacks(blankCheckRunnable)
        mainHandler.postDelayed(blankCheckRunnable, 12000)
    }

    /** 判断页面是否真的渲染出内容（在【外壳页】主框架里执行）：
     *  外壳页自身有可见正文/容器 → ok；有可见且足够大的 iframe → iframe（正文读不到，需结合控制台活性） */
    private val BLANK_CHECK_JS = """
        (function(){
          try{
            var b=document.body; if(!b) return 'nobody';
            var txt=(b.innerText||b.textContent||'').replace(/\s/g,'');
            var vis=0;
            try{
              var ids=['loginScreen','appScreen','appBody','tabBody'];
              for(var i=0;i<ids.length;i++){
                var e=document.getElementById(ids[i]);
                if(e){ var r=e.getBoundingClientRect(); if(r.width>10&&r.height>10) vis++; }
              }
            }catch(e){}
            if(vis>0 || txt.length>=20) return 'ok';
            try{
              var f=document.querySelector('iframe');
              if(f){ var g=f.getBoundingClientRect(); if(g.width>50&&g.height>50) return 'iframe'; }
            }catch(e){}
            return 'blank';
          }catch(e){ return 'err:'+((e&&e.message)?e.message:e); }
        })()
    """.trimIndent()

    private fun checkBlank() {
        blankChecks++
        if (bootReported) { hideBlankError(); return }
        val consoleFresh = System.currentTimeMillis() - lastConsoleAt < 15000
        try {
            webView.evaluateJavascript(BLANK_CHECK_JS) { res ->
                val r = (res ?: "").trim().trim('"')
                val ok = r == "ok" || (r == "iframe" && consoleFresh)
                if (ok) {
                    if (blankErrorShowing) hideBlankError()
                    return@evaluateJavascript
                }
                // 还在输出日志 / 前几轮宽限期 → 页面可能只是慢，继续等，最多约 1 分钟
                if (consoleFresh && blankChecks < 8) { mainHandler.postDelayed(blankCheckRunnable, 6000); return@evaluateJavascript }
                if (blankChecks < 3) { mainHandler.postDelayed(blankCheckRunnable, 6000); return@evaluateJavascript }
                showBlankScreen(r.ifBlank { "blank" })
            }
        } catch (_: Exception) {
        }
    }

    private fun hideBlankError() {
        if (!blankErrorShowing) return
        blankErrorShowing = false
        errorView.visibility = View.GONE
    }

    private fun showBlankScreen(reason: String) {
        blankErrorShowing = true
        val v = errorView
        v.findViewById<TextView>(R.id.errorText).text =
            "页面加载完成但没有内容（$reason）。下面是浏览器控制台最近的输出，截图发给技术即可定位。"
        val log = consoleLog.toList().takeLast(8).joinToString("\n")
        v.findViewById<TextView>(R.id.errorLog).text =
            if (log.isBlank()) "（未捕获到控制台输出：常见于业务页拉取失败，或 WebView 版本过低）" else log
        v.findViewById<View>(R.id.errorLogScroll).visibility = View.VISIBLE
        v.visibility = View.VISIBLE
        splash.visibility = View.GONE
        progress.visibility = View.GONE
        swipe.isRefreshing = false
    }

    private fun showNetworkError(desc: String?) {
        val v = errorView
        v.findViewById<TextView>(R.id.errorText).text =
            if (isOnline()) (desc ?: "页面加载失败") else getString(R.string.net_error_msg)
        v.findViewById<View>(R.id.errorLogScroll).visibility = View.GONE
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
                checkUpdateAsync(notify = true, force = true)
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

        /** 「稍后」后再提醒的冷却窗口：3 天 */
        private const val UPDATE_COOLDOWN_MS = 3L * 24 * 3600 * 1000
    }
}