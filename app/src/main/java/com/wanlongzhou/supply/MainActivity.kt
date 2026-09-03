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
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject

/**
 *                    ?                ? *
 *                                                                                                     
 *     /        100%                                ? *
 *   1.     WebView           ?                ?/         ? *   2.     localStorage       ?                   ? *   3.           ?+     ?   ?                       ? *   4.     /        /     ?/        /           ?          ? *
 *                               ?PageCache                     
 *                4KB    ?SDK                     
 *            +       540KB         token              
 *                                   ? */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    /**                       ?JS                      
     *  SwipeRefreshLayout                    */
    private var pageAtTop = true
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var splash: View
    private lateinit var errView: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**                rev        ?WebView                    */
    @Volatile
    private var bizUrl: String? = null

    /**             ?APK       checkUpdateAsync                                */
    private var latestAppUpdate: JSONObject? = null
    private var apkDownloadId: Long = -1L
    private var apkFileName: String = ""
    private var downloadManager: DownloadManager? = null
    /**                          */
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

    /**                   ?App                 */
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
        errView = findViewById(R.id.errorView)

        applySystemBars()
        setupWebView()
        setupSwipe()

        //                          ?try-catch          ?                     ?        //                                         ebView         ?        try {
            downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            registerReceiver(apkDownloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        } catch (e: Exception) {
            android.util.Log.w("WlzApp", "涓嬭浇绠＄悊鍣ㄥ垵濮嬪寲澶辫触锛屽簲鐢ㄥ唴鏇存柊鏆備笉鍙敤: ${e.message}")
        }

        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            errView.visibility = View.GONE
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

    /**       /                         */
    private fun applySystemBars() {
        window.statusBarColor = getColorCompat(R.color.colorPrimaryDark)
        window.navigationBarColor = getColorCompat(R.color.colorPrimaryDark)
    }

    @Suppress("DEPRECATION")
    private fun getColorCompat(id: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id) else resources.getColor(id)

    /**                                      ?scroll     ?     *      ?window.scrollY                ?scrollTop             ?     *                           SwipeRefreshLayout              ?     *       ebView.scrollY                    ?div        ?0 ?     *                    ?                                */
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

    /**     WorkBuddy               WorkBuddy              v1.4   ?                       HTML                 ?chrome          ?JS     ?       v1.3           class     'workbuddy'                    ?body/          ? ?    ?                        WorkBuddy /                            class                  */
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
                if(txt && (txt.indexOf('鍥濿orkBuddy')>=0 || txt.indexOf('缁х画鑱?)>=0)){ hideEl(el); }
                else if(clsRe.test(cls)){ hideEl(el); }
              }
              var links = document.querySelectorAll('a[href*="workbuddy"]');
              for(var j=0;j<links.length;j++){
                var l = links[j], lt = (l.innerText||'').trim();
                if(lt.indexOf('鍥濿orkBuddy')>=0 || lt.indexOf('缁х画鑱?)>=0){ hideEl(l); }
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

    /** JS                          */
    inner class WbScrollBridge {
        @JavascriptInterface
        fun atTop(v: Int) {
            pageAtTop = v == 1
        }
    }

    /**                                                         */
    private fun loadHome() {
        val url = homeUrl
        val sep = if (url.contains("?")) "&" else "?"
        errView.visibility = View.GONE
        splash.visibility = View.VISIBLE
        webView.loadUrl(url + sep + "_t=" + System.currentTimeMillis())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings

        s.javaScriptEnabled = true

        // =====     1    ?WebView        =====
        //     WebView                                       ?        //                                              ?        //     Chrome  ?iOS Safari                              ?        s.cacheMode = WebSettings.LOAD_NO_CACHE

        // =====     2       ?localStorage =====
        //                                     ?        s.domStorageEnabled = true
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
            //     https        http              NAS     http ?            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        s.userAgentString = s.userAgentString + " WLZSupply/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        // JS                                            ?        webView.addJavascriptInterface(WbScrollBridge(), "__wbScroll")
        webView.addJavascriptInterface(WlzAppBridge(), "__wlzApp")

        webView.webViewClient = object : WebViewClient() {

            /**
             *                                       
             *                                         ?             *
             *                hotel_requisition.html   ?             *                                       ?             */
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
                return null // 鏃犵紦瀛?鈫?璧扮綉缁滐紝鍚庡彴浼氱珛鍒昏ˉ瀛?            }

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
                //                                   ?                splash.postDelayed({ splash.visibility = View.GONE }, 250)
                //                                ?                checkUpdateAsync(notify = false)
                //                                        
                if (isBizPage(url)) {
                    webView.evaluateJavascript(SCROLL_JS, null)
                }
                // v1.3           WorkBuddy                                 ?                webView.evaluateJavascript(HIDE_FAB_JS, null)
            }

            //                                              
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

            //        alert / confirm / prompt ?            //                    ?WebView          
            // confirm                            ?            override fun onJsAlert(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("鎻愮ず")
                    .setMessage(message)
                    .setPositiveButton("纭畾") { _, _ -> result.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("纭")
                    .setMessage(message)
                    .setPositiveButton("纭畾") { _, _ -> result.confirm() }
                    .setNegativeButton("鍙栨秷") { _, _ -> result.cancel() }
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
                    .setTitle("璇疯緭鍏?)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("纭畾") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("鍙栨秷") { _, _ -> result.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }

            //                              ?            override fun onShowFileChooser(
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

        //              ?Excel    
        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, "鏃犳硶鎵撳紑涓嬭浇閾炬帴", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**                                      rev     */
    private fun isBizPage(url: String): Boolean =
        url.contains("/page/") && url.substringBefore("?").endsWith("hotel_requisition.html")

    // =====        =====
    private fun setupSwipe() {
        swipe.setColorSchemeColors(getColorCompat(R.color.colorPrimary))
        swipe.setOnRefreshListener { trySoftRefresh(0) }
        //                                        ?        //  ?JS           pageAtTop              ebView.scrollY     ?        //           ?div        ?0                          ?        //     true  ?                      ?             ?        swipe.isEnabled = true
        swipe.setOnChildScrollUpCallback { _, _ -> !pageAtTop }
        //                                                   64dp ?        val triggerPx = (resources.displayMetrics.density * 140).toInt()
        swipe.setDistanceToTriggerSync(triggerPx)
    }

    /**
     *             v1.6       ?     *
     *      1.2        webView.reload()           PA                   ?     *  ?        ?        1.3              window.__wlzSoftRefresh         
     *     location.reload()  pp                                       
     *     ? ?                                   ?     *
     *          
     *  -              false /            ?400ms        ?5     ?2     ?     *  -     ?reload                                            ?     */
    private fun trySoftRefresh(retry: Int) {
        val js = "(function(){try{return (window.__wlzSoftRefresh && window.__wlzSoftRefresh()===true)?'ok':'no';}catch(e){return 'no';}})()"
        webView.evaluateJavascript(js) { res ->
            val ok = res != null && res.contains("ok")
            when {
                ok -> {
                    //                                        ?                    mainHandler.postDelayed({ swipe.isRefreshing = false }, 600)
                }
                retry < 5 -> {
                    //                 ?/                 ?                    mainHandler.postDelayed({ trySoftRefresh(retry + 1) }, 400)
                }
                else -> {
                    swipe.isRefreshing = false
                    Toast.makeText(this, "椤甸潰杩樺湪鍔犺浇锛岃绋嶅€欏啀鎷?, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // =====                    ?APP_VERSION             ?=====
    /**
     * @param notify                         ?true               
     *
     *                                 ?     */
    private fun checkUpdateAsync(notify: Boolean) {
        val url = bizUrl ?: run {
            if (notify) toast("灏氭湭鑾峰彇鍒伴〉闈㈠湴鍧€锛岃绋嶅€欏啀璇?)
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
                    if (notify) mainHandler.post { toast("妫€鏌ュけ璐ワ紙HTTP $code锛?) }
                    return@Thread
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                val head = String(bytes, 0, minOf(bytes.size, 200_000), Charsets.UTF_8)
                // v1.7          window.WLZ_APP_UPDATE                                  ?                val appUp = extractAppUpdate(head)
                if (appUp != null) {
                    latestAppUpdate = appUp
                    maybePromptAppUpdate()
                }
                val ver = PageCache.extractVersion(head)
                if (ver == null) {
                    if (notify) mainHandler.post { toast("鏈兘璇嗗埆椤甸潰鐗堟湰") }
                    return@Thread
                }
                val updated = PageCache.save(this@MainActivity, bytes, ver)
                mainHandler.post {
                    when {
                        updated && notify -> toast("宸叉洿鏂板埌 $ver锛屼笅娆″惎鍔ㄨ嚜鍔ㄥ簲鐢?)
                        updated -> toast("宸蹭笅杞芥柊鐗?$ver锛屼笅娆″惎鍔ㄨ嚜鍔ㄥ簲鐢?)
                        notify -> toast("宸叉槸鏈€鏂帮紙$ver锛?)
                    }
                    //                          ?shouldInterceptRequest              ?         "   
                    //           App    ?         Activity              ?         "       ?                }
            } catch (e: Exception) {
                if (notify) mainHandler.post { toast("妫€鏌ユ洿鏂板け璐ワ細${e.message ?: "缃戠粶寮傚父"}") }
            }
        }.start()
    }

    // =====             v1.7 ?====
    /**              ?window.WLZ_APP_UPDATE JSON    versionCode/versionName/url/note */
    private fun extractAppUpdate(head: String): JSONObject? {
        val m = Regex("""window\.WLZ_APP_UPDATE\s*=\s*(\{[^\n]*?\});""").find(head) ?: return null
        return try { JSONObject(m.groupValues[1]) } catch (_: Exception) { null }
    }

    /**                    APK                                          */
    private fun maybePromptAppUpdate() {
        val up = latestAppUpdate ?: return
        val remoteCode = try { up.getInt("versionCode") } catch (_: Exception) { return }
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
                .setTitle("鍙戠幇鏂扮増鏈?v$name")
                .setMessage(if (note.isBlank()) "鏈夋柊鐗堝彲鐢紝鐐瑰嚮涓嬭浇鏇存柊銆? else note)
                .setPositiveButton("绔嬪嵆涓嬭浇") { _, _ -> downloadApk(url, name) }
                .setNegativeButton("绋嶅悗", null)
                .setCancelable(false)
                .show()
        }
    }

    /**     ?DownloadManager     APK                                   */
    private fun downloadApk(url: String, versionName: String) {
        try {
            val dm = downloadManager
                ?: (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
            apkFileName = "wanlongzhou-supply-v$versionName.apk"
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("涓囬緳娲蹭緵搴旈摼 App v$versionName")
                setDescription("姝ｅ湪涓嬭浇鏇存柊鍖呪€?)
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, apkFileName)
            }
            apkDownloadId = dm.enqueue(req)
            Toast.makeText(this, "寮€濮嬩笅杞芥洿鏂板寘锛屽畬鎴愬悗鑷姩鎻愮ず瀹夎", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "涓嬭浇澶辫触锛?{e.message ?: "鏈煡閿欒"}", Toast.LENGTH_LONG).show()
        }
    }

    /**                     ileProvider                    ?Android 7+ */
    private fun promptInstall() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            val file = File(dir, apkFileName)
            if (!file.exists()) {
                Toast.makeText(this, "瀹夎鍖呮湭鎵惧埌", Toast.LENGTH_SHORT).show()
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
                "鏃犳硶瀹夎锛?{e.message ?: "鏈煡閿欒"}锛堣鍒拌缃腑鍏佽銆屽畨瑁呮湭鐭ュ簲鐢ㄣ€嶏級",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** JS                 window.__wlzApp.checkUpdate()          */
    inner class WlzAppBridge {
        @JavascriptInterface
        fun checkUpdate() {
            mainHandler.post { maybePromptAppUpdate() }
        }
    }

    private fun showNetworkError(desc: String?) {
        val v = errView
        v.findViewById<TextView>(R.id.errorText).text =
            if (isOnline()) (desc ?: "椤甸潰鍔犺浇澶辫触") else getString(R.string.net_error_msg)
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

    // =====         4 ?====
    private fun printCurrentPage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            toast("绯荤粺鐗堟湰杩囦綆锛屼笉鏀寔鎵撳嵃")
            return
        }
        val pm = getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (pm == null) {
            toast("褰撳墠璁惧涓嶆敮鎸佹墦鍗?)
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

    // =====     =====
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
                toast("宸叉仮澶嶉粯璁ゅ湴鍧€")
                loadHome()
                true
            }
            MENU_CLEAR_CACHE -> {
                PageCache.clear(this)
                toast("鏈湴椤甸潰缂撳瓨宸叉竻闄わ紝涓嬫鍚姩閲嶆柊涓嬭浇")
                loadHome()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     *             ?     *                ?localStorage                     ?     */
    private fun clearCacheAndReload() {
        webView.clearCache(true)
        webView.clearFormData()
        loadHome()
        toast("宸叉竻缂撳瓨骞堕噸鏂板姞杞?)
    }

    /**                   NAS                    ?App */
    private fun showUrlDialog() {
        val input = EditText(this)
        input.setText(homeUrl)
        input.setSingleLine(true)

        AlertDialog.Builder(this)
            .setTitle("鏈嶅姟鍣ㄥ湴鍧€")
            .setMessage("鍚庢湡杩佸埌 NAS 鍚庯紝鎶婅繖閲屾敼鎴?NAS 涓婄殑璁块棶鍦板潃鍗冲彲锛孉pp 鏃犻渶閲嶆柊鎵撳寘銆?)
            .setView(input)
            .setPositiveButton("淇濆瓨骞舵墦寮€") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isBlank()) {
                    toast("鍦板潃涓嶈兘涓虹┖")
                    return@setPositiveButton
                }
                val finalUrl =
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
                prefs.edit().putString(KEY_URL, finalUrl).apply()
                webView.clearCache(true)
                loadHome()
            }
            .setNegativeButton("鍙栨秷", null)
            .show()
    }

    // =====                                 ?=====
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
                toast("鍐嶆寜涓€娆￠€€鍑?)
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
        try { unregisterReceiver(apkDownloadReceiver) } catch (_: Exception) {}
    }

    companion object {
        private val DEFAULT_URL: String by lazy {
            val s = "https://www.workbuddy.link/p/1n4Jb5OioGpFwab2YHFXTs"
            s
        }
        private val PREFS: String by lazy { "wlz_supply" }
        private val KEY_URL: String by lazy { "server_url" }
        private val REQ_FILE: Int = 1001

        private val MENU_REFRESH: Int = 1
        private val MENU_SET_URL: Int = 2
        private val MENU_RESET_URL: Int = 3
        private val MENU_PRINT: Int = 4
        private val MENU_CHECK_UPDATE: Int = 5
        private val MENU_CLEAR_CACHE: Int = 6
    }
}