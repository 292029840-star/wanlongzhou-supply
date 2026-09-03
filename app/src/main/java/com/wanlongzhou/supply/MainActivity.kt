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
 * 涓囬緳娲蹭緵搴旈摼鐢宠喘绯荤粺 鈥斺€?瀹夊崜澹筹紙鍑嗗師鐢熷寮虹増锛? *
 * 鈹€鈹€ 璁捐鍘熷垯 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
 * 鐣岄潰 / 涓氬姟閫昏緫 100% 鐢辩綉椤电鎵胯浇锛屾湰澹充笉纰颁换浣曚笟鍔′唬鐮侊紝鍙仛锛? *
 *   1. 鍏虫帀 WebView 缃戠粶缂撳瓨 鈥斺€?鏍规不銆屾柊鍗曟嵁闂幇鍗虫秷澶?/ 鐘舵€佸洖閫€銆? *   2. 鎵撳紑 localStorage    鈥斺€?鑽夌鏈哄埗渚濊禆锛屽叧鎺変細涓㈣崏绋? *   3. 鏈湴缂撳瓨涓氬姟椤?+ 鐑洿鏂?鈥斺€?绉掑紑锛屼笖鏀圭綉椤点€愪笉鐢ㄩ噸鏂版墦鍖呫€? *   4. 闂睆 / 涓嬫媺鍒锋柊 / 杩斿洖閿?/ 鍘熺敓鎵撳嵃 / 鏂綉鎻愮ず 鈥斺€?鎶规帀銆岀綉椤垫劅銆? *
 * 鈹€鈹€ 涓轰粈涔堢紦瀛樻槸瀹夊叏鐨勶紙鏀瑰姩鍓嶅繀璇伙紝瑙?PageCache 娉ㄩ噴锛夆攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€
 * 绾夸笂鏄袱灞傦細澶栧３椤碉紙4KB锛屾敞鍏?SDK 涓庣櫥褰曟€侊紝銆愬繀椤昏蛋缃戠粶銆戯級
 *            + 涓氬姟椤碉紙540KB锛岀函闈欐€佹棤 token锛屻€愬彲瀹夊叏缂撳瓨銆戯級
 * 杩欓噷鍙紦瀛樺悗鑰咃紝鍥犳涓嶄細褰卞搷鐧诲綍鎬佷笌鏁版嵁璁块棶銆? */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    /** 涓氬姟椤靛綋鍓嶆槸鍚﹀浜庨《閮紙鐢遍〉闈?JS 瀹炴椂涓婃姤锛屽惈鍐呭眰婊氬姩瀹瑰櫒锛夛紱
     *  SwipeRefreshLayout 鎹鍒ゆ柇鏄惁鍏佽涓嬫媺鍒锋柊銆?*/
    private var pageAtTop = true
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var splash: View
    private lateinit var errorView: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 涓氬姟椤电湡瀹炲湴鍧€锛堝惈 rev 娈碉紝棣栨鐢?WebView 璇锋眰鏃舵崟鑾凤紝渚涘悗鍙版洿鏂扮敤锛?*/
    @Volatile
    private var bizUrl: String? = null

    /** 涓氬姟椤靛０鏄庣殑鏈€鏂?APK 鍏冧俊鎭紙checkUpdateAsync 浠庢渶鏂扮綉椤靛瓧鑺傝В鏋愶級锛岀敤浜庡簲鐢ㄥ唴鏇存柊鎻愮ず */
    private var latestAppUpdate: JSONObject? = null
    private var apkDownloadId: Long = -1L
    private var apkFileName: String = ""
    private var downloadManager: DownloadManager? = null
    /** 涓嬭浇瀹屾垚骞挎挱鎺ユ敹鍣細鑷姩鎷夎捣瀹夎 */
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

    /** 褰撳墠鐢熸晥鐨勪笟鍔″湴鍧€锛堝彲鍦?App 鍐呮敼锛岄粯璁ゅ彇绾夸笂鍦板潃锛?*/
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

        // 搴旂敤鍐呮洿鏂颁緷璧栫殑涓嬭浇绠＄悊鍣細鍖呬竴灞?try-catch锛岄槻姝釜鍒満鍨?绯荤粺鏈嶅姟寮傚父瀵艰嚧鍐峰惎鍔ㄩ棯閫€銆?        // 鍗充究杩欓噷澶辫触锛屽彧鏄€屽簲鐢ㄥ唴鏇存柊銆嶅姛鑳戒笉鍙敤锛屼富涓氬姟锛圵ebView锛変笉鍙楀奖鍝嶃€?        try {
            downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            registerReceiver(apkDownloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        } catch (e: Exception) {
            android.util.Log.w("WlzApp", "涓嬭浇绠＄悊鍣ㄥ垵濮嬪寲澶辫触锛屽簲鐢ㄥ唴鏇存柊鏆備笉鍙敤: ${e.message}")
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

    /** 鐘舵€佹爮 / 瀵艰埅鏍忕粺涓€涓哄搧鐗岃壊锛屽幓鎺夎瑙夊壊瑁?*/
    private fun applySystemBars() {
        window.statusBarColor = getColorCompat(R.color.colorPrimaryDark)
        window.navigationBarColor = getColorCompat(R.color.colorPrimaryDark)
    }

    @Suppress("DEPRECATION")
    private fun getColorCompat(id: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id) else resources.getColor(id)

    /** 娉ㄥ叆鍒颁笟鍔￠〉鐨勬粴鍔ㄦ帰娴嬭剼鏈細鐩戝惉锛堝惈鎹曡幏闃舵锛夋墍鏈?scroll 浜嬩欢锛?     *  鍚屾椂璇?window.scrollY锛堟枃妗ｆ粴鍔級鍜屼簨浠剁洰鏍?scrollTop锛堝唴灞傚鍣ㄦ粴鍔級锛?     *  瀹炴椂涓婃姤銆屾槸鍚﹀湪椤堕儴銆嶇粰鍘熺敓锛屼緵 SwipeRefreshLayout 鍒ゆ柇鏄惁鍏佽鍒锋柊銆?     *  鍏抽敭锛歸ebView.scrollY 鍙弽鏄犳枃妗ｆ粴鍔紝椤甸潰鍦ㄥ唴灞?div 婊氬姩鏃舵亽涓?0锛?     *  浼氳鍒ゃ€屽湪椤堕儴銆嶅鑷寸炕椤?婊氬姩涓瑙﹀埛鏂扳€斺€旇繖閲岀敤鐪熷疄婊氬姩浣嶇疆鏍规不銆?*/
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

    /** 闅愯棌 WorkBuddy 骞冲彴澶栧３娓叉煋鐨勩€屽洖WorkBuddy缁х画鑱娿€嶆诞绐楁寜閽紙v1.4锛夈€?       璇ユ诞绐椾笉鍦ㄦ垜浠笟鍔￠〉 HTML 鍐咃紝鏄钩鍙板鎴风娉ㄥ叆鐨?chrome锛屽彧鑳藉師鐢熸敞鍏?JS 闅愯棌銆?       v1.3 鏁欒锛氫笉鑳界敤 class 閲屽惈 'workbuddy' 鐨勫娉涙鍒欙紝浼氳鎶婃暣涓钩鍙?body/涓诲鍣ㄤ竴璧烽殣钘?鈫?鐧藉睆銆?       淇绛栫暐锛氬彧鎸夋枃鏈€屽洖WorkBuddy / 缁х画鑱娿€嶅懡涓紝澶栧姞鏋佸皯鏁版槑纭殑娴獥 class锛屽苟鍔犻槻鎶栭伩鍏嶉樆濉為灞忋€?*/
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

    /** JS 妗ユ帴锛氭帴鏀堕〉闈笂鎶ョ殑銆屾槸鍚﹀湪椤堕儴銆?*/
    inner class WbScrollBridge {
        @JavascriptInterface
        fun atTop(v: Int) {
            pageAtTop = v == 1
        }
    }

    /** 涓诲湴鍧€甯︽椂闂存埑鍔犺浇锛岀‘淇濇瘡娆℃嬁鍒版渶鏂板澹抽〉锛堜笟鍔￠〉璧版湰鍦扮紦瀛橈紝瑙佹嫤鎴€昏緫锛?*/
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

        // ===== 鍏抽敭 1锛氱鐢?WebView 缃戠粶缂撳瓨 =====
        // 瀹夊崜 WebView 榛樿浼氱紦瀛樿姹傦紝鎶娿€屽啓搴撲箣鍓嶃€嶇殑鏃ф暟鎹繑鍥炵粰椤甸潰锛?        // 琛ㄧ幇涓猴細鏂板崟鎹棯鐜颁竴涓嬪氨娑堝け銆佺偣鍙戣揣鍚庣姸鎬佸張鍙樺洖銆屽緟鍙戣揣銆嶃€?        // 妗岄潰 Chrome 涓?iOS Safari 缂撳瓨绛栫暐娌¤繖涔堟縺杩涳紝鎵€浠ュ彧鏈夊畨鍗撳鐜般€?        s.cacheMode = WebSettings.LOAD_NO_CACHE

        // ===== 鍏抽敭 2锛氬繀椤诲紑鍚?localStorage =====
        // 绯荤粺鐨勮崏绋挎満鍒躲€佹湰鍦扮紦瀛橀兘渚濊禆瀹冿紝鍏虫帀浼氫涪鑽夌銆?        s.domStorageEnabled = true
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
            // 鍏佽 https 椤甸潰閲岀殑 http 瀛愯祫婧愶紙鍚庢湡鍐呯綉 NAS 澶氫负 http锛?            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        s.userAgentString = s.userAgentString + " WLZSupply/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        // JS 妗ユ帴锛氶〉闈㈡嵁姝や笂鎶ョ湡瀹炴粴鍔ㄤ綅缃紝渚涗笅鎷夊埛鏂板垽鏂槸鍚﹀浜庨《閮?        webView.addJavascriptInterface(WbScrollBridge(), "__wbScroll")
        webView.addJavascriptInterface(WlzAppBridge(), "__wlzApp")

        webView.webViewClient = object : WebViewClient() {

            /**
             * 鎷︽埅涓氬姟椤佃姹傦細鍛戒腑鍒欑敤鏈湴缂撳瓨鐩存帴杩斿洖锛堢寮€锛夛紝
             * 鍚﹀垯鏀捐璧扮綉缁溿€傚悓鏃惰涓嬬湡瀹炲湴鍧€锛屼緵鍚庡彴鐑洿鏂颁娇鐢ㄣ€?             *
             * 娉ㄦ剰锛氬彧鎷︽埅涓氬姟椤碉紙hotel_requisition.html锛夈€?             * 澶栧３椤典笌鎵€鏈夋暟鎹帴鍙ｄ竴寰嬫斁琛岋紝鍚﹀垯浼氭嬁涓嶅埌鐧诲綍鎬併€?             */
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
                // 绋嶄綔寤惰繜鍐嶆敹闂睆锛岄伩鍏嶉〉闈㈠垰娓叉煋瀹屽氨闂竴涓嬬櫧搴?                splash.postDelayed({ splash.visibility = View.GONE }, 250)
                // 姣忔鍔犺浇瀹岄『甯︽鏌ヤ竴娆℃洿鏂帮紙寮傛锛屼笉闃诲锛?                checkUpdateAsync(notify = false)
                // 涓氬姟椤垫敞鍏ユ粴鍔ㄦ帰娴嬭剼鏈紙浠呭湪涓氬姟椤碉紝閬垮厤姹℃煋澶栧３椤碉級
                if (isBizPage(url)) {
                    webView.evaluateJavascript(SCROLL_JS, null)
                }
                // v1.3锛氶殣钘忓钩鍙般€屽洖WorkBuddy缁х画鑱娿€嶆诞绐楋紙澶栧３椤典笌涓氬姟椤甸兘娉ㄥ叆锛屽弻淇濋櫓锛?                webView.evaluateJavascript(HIDE_FAB_JS, null)
            }

            // 涓绘鏋跺姞杞藉け璐ユ墠寮归敊璇〉锛涘瓙璧勬簮锛堝浘鐗囩瓑锛夊け璐ュ拷鐣ワ紝閬垮厤璇激
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

            // 蹇呴』澶勭悊 alert / confirm / prompt锛?            // 涓嶆帴绠＄殑璇濓紝缃戦〉閲岀殑寮圭獥鍦?WebView 涓笉浼氭樉绀猴紝
            // confirm 浼氫竴鐩存嬁涓嶅埌缁撴灉锛屼笟鍔℃祦绋嬬洿鎺ュ崱姝汇€?            override fun onJsAlert(
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

            // 鏀寔缃戦〉閲岀殑鏂囦欢涓婁紶锛堥€夊浘鐗囥€佸鍏ョ瓑锛?            override fun onShowFileChooser(
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

        // 涓嬭浇浜ょ粰绯荤粺锛堝鍑?Excel 绛夛級
        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, "鏃犳硶鎵撳紑涓嬭浇閾炬帴", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 鏄惁涓氬姟椤碉紙鍙紦瀛樼殑閭ｄ竴灞傦級锛屾寜璺緞灏惧尮閰嶏紝蹇界暐 rev 娈靛彉鍖?*/
    private fun isBizPage(url: String): Boolean =
        url.contains("/page/") && url.substringBefore("?").endsWith("hotel_requisition.html")

    // ===== 涓嬫媺鍒锋柊 =====
    private fun setupSwipe() {
        swipe.setColorSchemeColors(getColorCompat(R.color.colorPrimary))
        swipe.setOnRefreshListener { trySoftRefresh(0) }
        // 鍏抽敭淇锛氬彧鏈変笟鍔￠〉澶勪簬銆屾渶椤堕儴銆嶆椂鎵嶅厑璁镐笅鎷夊埛鏂般€?        // 鐢?JS 妗ユ帴瀹炴椂涓婃姤 pageAtTop锛堝惈鍐呭眰婊氬姩瀹瑰櫒锛泈ebView.scrollY 鍙弽鏄?        // 鏂囨。婊氬姩銆佸唴灞?div 婊氬姩鏃舵亽涓?0 浼氳鍒ゅ湪椤堕儴锛夛紝鍙椤甸潰娌℃粴鍒伴《灏?        // 杩斿洖 true 鈫?鍒锋柊鎵嬪娍琚姂鍒讹紝椤甸潰鍐呭悜涓嬫粴鍔?缈婚〉涓嶅啀璇Е鍒锋柊銆?        swipe.isEnabled = true
        swipe.setOnChildScrollUpCallback { _, _ -> !pageAtTop }
        // 鍔犲ぇ瑙﹀彂璺濈锛氬繀椤汇€岄暱鎷夈€嶆墠鍒锋柊锛岄伩鍏嶈交寰笅鎷夊氨璺冲睆鍒锋柊锛堥粯璁ょ害 64dp锛?        val triggerPx = (resources.displayMetrics.density * 140).toInt()
        swipe.setDistanceToTriggerSync(triggerPx)
    }

    /**
     * 杞埛鏂板綋鍓嶈鍥撅紙v1.6 鍔犲浐锛夈€?     *
     * 鑳屾櫙锛歷1.2 鍙婃洿鏃╃敤 webView.reload()锛屼細杩炲澹抽〉锛圫PA 澶栧３鎸佹湁鐧诲綍鎬侊級涓€璧烽噸杞?     * 鈫?閲嶆柊閴存潈 鈫?璺崇櫥褰曢〉銆倂1.3 鏀逛负璋冪敤涓氬姟椤电殑 window.__wlzSoftRefresh锛屼絾闄嶇骇鍒嗘敮
     * 浠嶆槸 location.reload()锛欰pp 鍒氬惎鍔ㄣ€佷笟鍔￠〉鑴氭湰杩樻病鎵ц瀹屾椂涓嬫媺锛岄挬瀛愪笉瀛樺湪灏变細
     * 璧伴檷绾?鈫?渚濈劧璺崇櫥褰曘€傝繖鏄€屼慨浜嗕絾杩樻槸浼氳烦鐧诲綍銆嶇殑鏍瑰洜銆?     *
     * 鐜板湪鐨勮涓猴細
     *  - 閽╁瓙鏈氨缁紙杩斿洖 false / 鍥炶皟涓虹┖锛夆啋 姣?400ms 閲嶈瘯锛屾渶澶?5 娆★紙绾?2 绉掞級锛?     *  - 濮嬬粓涓?reload锛氶噸璇曡€楀敖涔熷彧鏄敹璧峰埛鏂板姩鐢诲苟杞绘彁绀猴紝缁濅笉瑙︾澶栧３椤靛鑸€?     */
    private fun trySoftRefresh(retry: Int) {
        val js = "(function(){try{return (window.__wlzSoftRefresh && window.__wlzSoftRefresh()===true)?'ok':'no';}catch(e){return 'no';}})()"
        webView.evaluateJavascript(js) { res ->
            val ok = res != null && res.contains("ok")
            when {
                ok -> {
                    // 鏁版嵁鎷夊彇鏄紓姝ョ殑锛岀◢绛夊啀鏀跺姩鐢伙紝閬垮厤銆岃浆涓€涓嬪氨娌′簡銆?                    mainHandler.postDelayed({ swipe.isRefreshing = false }, 600)
                }
                retry < 5 -> {
                    // 涓氬姟椤靛皻鏈氨缁紙鍒氬惎鍔?/ 姝ｅ湪鐑洿鏂帮級锛屽欢杩熼噸璇?                    mainHandler.postDelayed({ trySoftRefresh(retry + 1) }, 400)
                }
                else -> {
                    swipe.isRefreshing = false
                    Toast.makeText(this, "椤甸潰杩樺湪鍔犺浇锛岃绋嶅€欏啀鎷?, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===== 鐑洿鏂帮細鍚庡彴鎷夊彇涓氬姟椤碉紝鎸?APP_VERSION 鍒ゆ柇鏄惁闇€瑕佹洿鏂?=====
    /**
     * @param notify 鏄惁鎻愮ず缁撴灉锛堣彍鍗曢噷鎵嬪姩妫€鏌ユ椂鐢?true锛岃嚜鍔ㄦ鏌ラ潤榛樿繘琛岋級
     *
     * 鏇存柊鍙湪銆愪笅娆″惎鍔ㄣ€戠敓鏁堬紝涓嶆墦鏂綋鍓嶆搷浣溿€?     */
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
                // v1.7锛氫笟鍔￠〉鍐呭祵 window.WLZ_APP_UPDATE锛岃В鏋愬悗鐢ㄤ簬搴旂敤鍐呮洿鏂版彁绀猴紙鐙珛浜庨〉闈㈢儹鏇存柊锛?                val appUp = extractAppUpdate(head)
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
                    // 鏇存柊宸插啓鍏ユ湰鍦扮紦瀛橈紱涓嬫椤甸潰璇锋眰鏃?shouldInterceptRequest 鑷姩杩斿洖鏂扮増锛堢瓑鏁?涓嬫鍚姩鐢熸晥"锛夛紝
                    // 涓嶅湪姝ゅ閲嶅惎 App 鈥斺€?鍐烽噸鍚細鏉€ Activity 閫犳垚椤甸潰闂儊锛屼笖涓?涓嬫鍚姩鐢熸晥"璁捐鐭涚浘銆?                }
            } catch (e: Exception) {
                if (notify) mainHandler.post { toast("妫€鏌ユ洿鏂板け璐ワ細${e.message ?: "缃戠粶寮傚父"}") }
            }
        }.start()
    }

    // ===== 搴旂敤鍐呮洿鏂版彁绀猴紙v1.7锛?====
    /** 浠庢渶鏂扮綉椤靛瓧鑺傝В鏋?window.WLZ_APP_UPDATE JSON锛堝惈 versionCode/versionName/url/note锛?*/
    private fun extractAppUpdate(head: String): JSONObject? {
        val m = Regex("""window\.WLZ_APP_UPDATE\s*=\s*(\{[^\n]*?\});""").find(head) ?: return null
        return try { JSONObject(m.groupValues[1]) } catch (_: Exception) { null }
    }

    /** 涓氬姟椤靛０鏄庝簡姣斿綋鍓嶆洿楂樼殑 APK 鐗堟湰鏃讹紝寮广€屽彂鐜版柊鐗堟湰銆嶅璇濇锛堟瘡涓増鏈粎鎻愮ず涓€娆★級 */
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

    /** 鐢ㄧ郴缁?DownloadManager 涓嬭浇 APK 鍒板簲鐢ㄧ鏈変笅杞界洰褰曪紝瀹屾垚鍚庣敱骞挎挱鎺ユ敹鍣ㄦ媺璧峰畨瑁?*/
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

    /** 涓嬭浇瀹屾垚鍚庢媺璧风郴缁熷畨瑁呭櫒锛團ileProvider 鏆撮湶搴旂敤绉佹湁涓嬭浇鐩綍锛屽吋瀹?Android 7+锛?*/
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

    /** JS 妗ワ細缃戦〉鐧诲綍鍚庡彲璋冪敤 window.__wlzApp.checkUpdate() 涓诲姩瑙﹀彂妫€娴?*/
    inner class WlzAppBridge {
        @JavascriptInterface
        fun checkUpdate() {
            mainHandler.post { maybePromptAppUpdate() }
        }
    }

    private fun showNetworkError(desc: String?) {
        val v = errorView
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

    // ===== 鍘熺敓鎵撳嵃锛圓4锛?====
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

    // ===== 鑿滃崟 =====
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
     * 娓呯紦瀛橀噸鏂板姞杞姐€?     * 娉ㄦ剰锛氳繖閲屽埢鎰忋€愪笉娓?localStorage銆戯紝鍚﹀垯浼氳繛鍚岃崏绋夸竴璧锋竻鎺夈€?     */
    private fun clearCacheAndReload() {
        webView.clearCache(true)
        webView.clearFormData()
        loadHome()
        toast("宸叉竻缂撳瓨骞堕噸鏂板姞杞?)
    }

    /** 鏈嶅姟鍣ㄥ湴鍧€閰嶇疆锛氬悗鏈熻縼 NAS 鏃舵敼杩欓噷鍗冲彲锛屼笉鐢ㄩ噸鏂版墦鍖?App */
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

    // ===== 杩斿洖閿細浼樺厛缃戦〉鍐呭洖閫€锛涘凡鍒伴椤靛垯鍙屽嚮閫€鍑?=====
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
        /**
         * 澶栧３椤靛湴鍧€锛堢嚎涓婄郴缁熺殑鍏ュ彛锛夈€?         * 娉ㄦ剰锛氳繖閲屽繀椤绘槸 workbuddy.link 鐨勯〉闈㈠湴鍧€锛屼笉鑳芥敼鎴愰潤鎬佷笟鍔￠〉鍦板潃
         * 鈥斺€?鐧诲綍鎬佷笌鏁版嵁搴?SDK 鐢卞澹抽〉娉ㄥ叆锛岀洿鎺ョ敤闈欐€侀〉浼氬鑷寸郴缁熶笉鍙敤銆?         */
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
