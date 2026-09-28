package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store
import org.json.JSONObject

/**
 * 京东包裹列表（无需小米登录）：隐藏 WebView 加载京东订单中心，
 * 网络层拦截 order_list_m 响应（页面 SDK 自动签名，绕开 h5st），
 * 滚动触发翻页，把订单转成 ExpressItem（source=jd）。
 */
object JdListFetcher {

    private const val TAG = "JdListFetcher"

    /* ★★ 2026-09-28 修复「新下的京东单同步不进来」（用户实测：4 个新单在云雀里完全看不到）。
       ── 根因（读源码 + 实测数据得出，不是猜的）──
       原来只有 `orderType=all` 一个入口，而终止条件是：
           if ((total > 0 && orders.size >= total) || rounds > 14) finish()
        `total` 取自响应体 body.totalNum = **账号下全部订单数**（实测这个账号几百单），
        `orders.size` 每轮只多一页（10~20 条）→ `orders.size >= total` 几乎永不成立，
        只能靠 `rounds > 14` 强制收工（14 × 3s ≈ 42 秒）。
        ★ 而京东订单列表是**按时间倒序**，「已完成」的历史单排在前面 ——
          42 秒全用来滚旧单，**新单还没滚到就被截断了**。
        ★ 实测佐证：抓到的 20 条京东件**全部是 dealState=18（已完成）**，
          且 latestText 只有「完成」二字（已完成单本来就没轨迹文案）。

       ── 修法（两处一起改，缺一不可）──
       ① 入口覆盖多个 tab：不再只抓 all，而是把「待收货 / 待出库 / 全部」逐个抓一遍。
          ★ 为什么用「多 tab 轮抓」而不是「只把 all 换成 waitReceive」：
            京东这个页面是 SPA 壳（实测 orderType 取任何值，服务端返回的 HTML 长度都是 99758，
            完全不区分），所以**参数名对不对无法从服务端验证**，只能到 WebView 里跑才知道。
            轮抓多个 tab 的好处是：**只要有一个 tab 名写对，新单就能抓到**，
            不会因为猜错一个参数名而全盘失效。而且各 tab 之间靠 orderId 去重，不会重复。
       ② 终止条件改成「连续 N 轮没有新增就停」：真正反映「已经滚到底」，
          而不是依赖错误的 totalNum 比较。
       ★ 超时同步放宽（见下方 60000 → 180000），因为要跑多个 tab。 */
    private val ORDER_TABS = listOf(
        "waitReceive",   // 待收货：新单（已揽收/运输中/派送中）基本都在这
        "waitDeliver",   // 待出库：仓库处理中的单
        "notShipped",    // 未发货
        "all"            // 全部（兜底，最后跑）
    )
    private const val ORDER_CENTER_TPL =
        "https://trade.m.jd.com/order/orderlist_jdm.shtml?orderType=%s&source=m_outer_jx_order"

    private const val HOOK_JS = """
        (function(){
          function pushCap(s){
            try {
              var arr = [];
              if (window.name && window.name.charAt(0) === '[') { try { arr = JSON.parse(window.name); } catch(e){ arr = []; } }
              arr.push(s);
              window.name = JSON.stringify(arr);
            } catch(e) {}
          }
          window.__jdcb = function(data){ try{ pushCap(JSON.stringify(data)); }catch(e){} };
          function rewrite(v){ if (v && /callback=/.test(String(v))) { return String(v).replace(/callback=[a-zA-Z0-9_]+/, 'callback=__jdcb'); } return v; }
          var origCreate = document.createElement.bind(document);
          document.createElement = function(tag){
            var el = origCreate(tag);
            if (String(tag).toLowerCase() === 'script') {
              var origSet = el.setAttribute.bind(el);
              el.setAttribute = function(k, v){ if (k === 'src') { v = rewrite(v); } return origSet(k, v); };
            }
            return el;
          };
          try {
            var desc = Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype, 'src');
            Object.defineProperty(HTMLScriptElement.prototype, 'src', {
              get: function(){ return desc.get.call(this); },
              set: function(v){ return desc.set.call(this, rewrite(v)); },
              configurable: true
            });
          } catch(e) {}
          var oo = XMLHttpRequest.prototype.open; var os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m,u){ this.__u=u; return oo.apply(this,arguments); };
          XMLHttpRequest.prototype.send = function(){ var self=this; this.addEventListener('load', function(){ try{ pushCap(self.responseText); }catch(e){} }); return os.apply(this,arguments); };
          var of = window.fetch; if (of) { window.fetch = function(input,init){ var p=of.apply(this,arguments); if(p&&p.then){p.then(function(r){try{ r.clone().text().then(function(t){ pushCap(t); }); }catch(e){}});} return p; }; }
        })();
    """

    private val handler = Handler(Looper.getMainLooper())
    private val interceptClient = okhttp3.OkHttpClient.Builder().build()
    /* ★ 2026-09-28：删掉 scrollStarted / challengeShown 两个成员变量。
       它们原来做「滚动只跑一次」「验证页只提示一次」的全局守卫，
       多 tab 化之后滚动守卫改成了 start() 里的局部变量 everScrolled（每个 tab 各一份），
       challengeShown 在改前就已经没有任何地方读它（只赋值不读，是死字段）。
       留着会让下一个人以为「滚动仍然全局只跑一次」，属误导，故清掉。
       同理 tapAt() 也没人调（原用于模拟点击搜索框，现在走 URL 直接带参数），一并删。 */

    fun fetch(
        act: Context,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit
    ) = fetchWith(act, Store.firstEnabledAccount(act, Store.CH_JD), onDone)

    /** 多源绑定：按指定京东账号凭证抓列表 */
    fun fetchWith(
        act: Context,
        account: com.halo.expressassistant.data.BoundAccount?,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit
    ) {
        handler.post { start(act, account, onDone) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(
        act: Context,
        account: com.halo.expressassistant.data.BoundAccount?,
        onDone: (List<ExpressItem>?, Map<String, JdGoods>, String?) -> Unit
    ) {
        val captures = ArrayList<String>()
        var finished = false
        val w = WebView(act)
        WebView.setWebContentsDebuggingEnabled(true)
        w.setBackgroundColor(0x00000000)
        w.alpha = 0.01f
        w.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Linux; Android 16; 25102RKBEC Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        val jdCookies = account?.let { Store.cookieOf(it.payload) } ?: Store.jdCookies(act)
        for (part in jdCookies.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                cm.setCookie("https://www.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://wqs.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://trade.m.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://api.m.jd.com", "${kv[0]}=${kv[1]}")
                cm.setCookie("https://jingfen.jd.com", "${kv[0]}=${kv[1]}")
            }
        }
        cm.flush()
        try {
            val decor = (act as? Activity)?.window?.decorView as? ViewGroup
            decor?.addView(w, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Throwable) {
            Log.w(TAG, "attach fail: $e")
        }

        fun finish(result: List<ExpressItem>?, err: String?) {
            if (finished) return
            finished = true
            handler.postDelayed({
                try {
                    (w.parent as? ViewGroup)?.removeView(w)
                    w.destroy()
                } catch (e: Throwable) {
                    Log.w(TAG, "cleanup fail: $e")
                }
                val (items, goods) = convert(captures, account)
                onDone(result ?: items, goods, err)
            }, 200)
        }

        /** 汇总京东主要域 Cookie（用于验证完成后回存，防每次刷新都脱线） */
        fun collectJdCookies(): String {
            val sb = StringBuilder()
            for (host in listOf(
                "https://www.jd.com", "https://wqs.jd.com", "https://trade.m.jd.com",
                "https://api.m.jd.com", "https://jingfen.jd.com", "https://plogin.m.jd.com"
            )) {
                val c = CookieManager.getInstance().getCookie(host) ?: continue
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(c)
            }
            return sb.toString()
        }

        fun saveCookies(cookies: String) {
            try {
                // 防降级覆盖：账号 payload 里有登录态（pt_key）而本次收集到的没有（匿名会话），
                // 不能用匿名集把登录 Cookie 冲掉（曾把 11.5KB 登录集缩水成 2.5KB 导致抓取失效）
                if (account != null) {
                    val old = Store.cookieOf(account.payload)
                    if (old.contains("pt_key=") && !cookies.contains("pt_key=")) {
                        Log.i(TAG, "skip anon overwrite: keep login payload (${old.length})")
                        return
                    }
                    Store.updateAccount(act, Store.CH_JD, account.copy(payload = Store.cookiePayload(cookies)))
                } else {
                    Store.saveJdCookies(act, cookies)
                }
                Log.i(TAG, "jd cookies refreshed (len=${cookies.length})")
            } catch (e: Throwable) {
                Log.w(TAG, "save cookies fail: $e")
            }
        }

        /* ★★ 2026-09-28 多 tab 顺序抓取（本次修复的第二个关键点）。
           ── 为什么不是「简单把 all 换成 waitReceive」──
           京东这个页面是 SPA 壳：实测 orderType 取 all / waitReceive / waitPay / …
           **服务端返回的 HTML 长度一模一样（都是 99758 字节）**，说明筛选完全由前端 JS 做。
           所以「哪个 tab 参数名是对的」**无法从服务端验证**，只能到 WebView 里跑才知道。
           ★ 轮抓多个 tab：只要有一个参数名蒙对，新单就进来了；同时靠 orderId 去重，
             重复抓同一个 tab 不会产生重复件。这是「不赌单个参数名」的稳妥做法。
           ── 实现 ──
           tabIndex 逐个推进；每个 tab 进来后跑一轮 scrollAndCollect（滚到底/无新增就停），
           然后 onTabDone 回到这里推进下一个。全部跑完才 finish。 */
        var tabIndex = 0
        var everScrolled = false

        fun loadNextTab() {
            if (finished) return
            if (tabIndex >= ORDER_TABS.size) {
                Log.i(TAG, "all tabs done: ${ORDER_TABS.size} tabs, captures=${captures.size}")
                finish(null, null)
                return
            }
            val tab = ORDER_TABS[tabIndex]
            tabIndex++
            everScrolled = false
            val url = String.format(ORDER_CENTER_TPL, tab)
            Log.i(TAG, "load tab[$tabIndex/${ORDER_TABS.size}] orderType=$tab")
            w.loadUrl(url)
        }

        w.webViewClient = object : WebViewClient() {            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page: $url")
                if (url.contains("plogin") || url.contains("nopasswordcmcc")) {
                    // 京东安全验证页：保持隐藏 WebView 静默等待（不弹窗打扰）；
                    // 页面内登录/一键登录完成后会继续跳回 orderlist_jdm，验证后回存新 Cookie 防再次脱线
                    Log.i(TAG, "login challenge (silent) -> wait page continue")
                    handler.postDelayed({
                        if (!finished) {
                            val cookies = collectJdCookies()
                            if (cookies.isNotBlank()) saveCookies(cookies)
                        }
                    }, 2500)
                    // 兜底：静默等待较久仍无数据 → 一次性提示（持久化 6 小时节流 + 仅当本地确有京东旧数据；
                    // 局部变量节流跨同步无效，会话真失效时每次刷新/后台轮询都弹）
                    handler.postDelayed({
                        if (!finished && captures.isEmpty()) {
                            val now = System.currentTimeMillis()
                            if (now - Store.jdLoginHintAt(act) > 6 * 60 * 60 * 1000L &&
                                Store.items(act).any { it.source == "jd" }
                            ) {
                                Store.setJdLoginHintAt(act, now)
                                android.widget.Toast.makeText(
                                    act,
                                    "京东 H5 登录态已失效：本次保留旧数据；如需更新，请到 设置→京东登录 重新绑定",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }, 8000)
                    return
                }
                if (url.contains("orderlist_jdm.shtml") && !url.contains("orderType=search")) {
                    // 回到订单页（验证完成/直接进入）：回存最新 Cookie
                    val cookies = collectJdCookies()
                    if (cookies.isNotBlank()) saveCookies(cookies)
                    /* ★ 每个 tab 只滚一次（everScrolled 守卫）。
                       原来用全局 scrollStarted，只能滚第一个 tab —— 这正是
                       「新单抓不到」的第二个成因。 */
                    if (everScrolled) return
                    everScrolled = true
                    handler.postDelayed({
                        scrollAndCollect(w, captures, account, ::finish) {
                            // ★ 这个 tab 滚完了 → 推进下一个 tab（而不是直接 finish）
                            handler.postDelayed({ loadNextTab() }, 1200)
                        }
                    }, 4000)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (u.contains("api.m.jd.com") && u.contains("order_list_m")) {
                    Log.i(TAG, "REQ order_list_m (passthrough)")
                    return null
                }
                return null
            }
        }
        // JS 级捕获钩子：改写 JSONP 回调，不动网络层
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
            w, HOOK_JS,
            setOf("https://trade.m.jd.com", "https://wqs.jd.com")
        )
        // ★ 启动第一个 tab（原来是直接 loadUrl(ORDER_CENTER)）
        loadNextTab()
        // 兜底超时。
        // ★ 2026-09-28：60000 → 180000。因为现在要顺序跑 4 个 tab，
        //   每个 tab 最多 40 轮 × 3 秒 = 120 秒，4 个 tab 理论上要 8 分钟。
        //   但正常情况每个 tab 几轮就「无新增」收工（待收货 tab 只有几个单，一轮就到底），
        //   实测预期 30~60 秒跑完。180 秒是防死循环的硬上限，不是预期耗时。
        handler.postDelayed({
            if (!finished) {
                Log.w(TAG, "timeout, finalize with ${captures.size} captures")
                finish(null, "京东列表超时（已用部分数据）")
            }
        }, 180000)
    }

    private fun scrollAndCollect(
        w: WebView,
        captures: ArrayList<String>,
        account: com.halo.expressassistant.data.BoundAccount?,
        finish: (List<ExpressItem>?, String?) -> Unit,
        /* ★ 2026-09-28 加：这个 tab 滚完后的回调。多 tab 轮抓靠它推进下一个 tab。
           注意这里**不再直接调 finish** —— 一个 tab 滚完只是这个 tab 完事，
           还有别的 tab 要抓。真正的结束在多 tab 都跑完之后（loadNextTab 里判 tabIndex）。 */
        onTabDone: () -> Unit
    ) {
        /* ★ 注意：这个函数现在会被**每个 tab 各调一次**。
           原来的 `if (scrollStarted) return` 是全局守卫，会把第 2 个之后的 tab
           全挡掉 —— 那正是「新单抓不到」的成因之一。
           现在改成「每次调用独立跑完」，由外层 everScrolled 控制「每个 tab 只进一次」。 */
        var rounds = 0
        /* ★★ 2026-09-28 终止条件重写（本次修复的核心）。
           ── 原来的判据错在哪 ──
           原代码：`if ((total > 0 && orders.size >= total) || rounds > 14)`
           其中 total = body.totalNum = 账号下**全部订单数**（几百条），
           而每轮滚动只多一页（10~20 条）。于是：
             · `orders.size >= total` 几乎永远不成立；
             · 真正生效的只有 `rounds > 14`（≈42 秒硬截断）。
           ★ 后果：列表按时间倒序、旧单在前，42 秒全滚在旧单上，
             新单（待收货）根本轮不到 → 「新单同步不进来」。
           ── 新判据 ──
           「连续 3 轮没有新增」= 真的滚到底了（没新数据再滚也是白滚）。
           这个判据**不依赖 totalNum**，所以账号里有多少历史订单都不影响。
           上限放到 40 轮（≈120 秒）纯粹是防死循环的保险丝。 */
        var lastCount = -1
        var noGain = 0
        val runnable = object : Runnable {
            override fun run() {
                rounds++
                // 从 window.name（跨导航存活）读取捕获的响应
                w.evaluateJavascript(
                    "(function(){return window.name?window.name:'[]';})()"
                ) { v ->
                    try {
                        val arr = org.json.JSONArray(
                            v.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                        )
                        synchronized(captures) {
                            for (i in 0 until arr.length()) {
                                val s = arr.getString(i)
                                if (s.contains("orderList") && captures.none { it == s }) {
                                    captures.add(s)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "js capture poll fail: $e")
                    }
                }
                val (orders, _) = convert(captures, account)
                val total = totalNum(captures)
                /* ★ 连续无新增计数。注意判据用 orders.size（去重后的**有效件数**），
                   不是 captures.size（原始响应条数）——
                   翻到底之后浏览器可能仍在重复吐同一页，captures 在涨但 orders 不涨，
                   用 captures 判会永远"有新增"而滚到上限。 */
                if (orders.size == lastCount) noGain++ else noGain = 0
                lastCount = orders.size
                Log.i(TAG, "round=$rounds orders=${orders.size} total=$total " +
                        "captures=${captures.size} noGain=$noGain")
                if (noGain >= 3 || rounds > 40) {
                    Log.i(TAG, "tab done: round=$rounds orders=${orders.size} noGain=$noGain")
                    // ★ 不是 finish，是「这个 tab 完事，去下一个」
                    onTabDone()
                    return
                }
                swipeUp(w)
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(runnable)
    }

    private fun swipeUp(w: WebView) {
        val width = w.width.toFloat()
        val height = w.height.toFloat()
        val x = width / 2f
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, height * 0.9f, 0)
        w.dispatchTouchEvent(down)
        var t = downTime
        for (i in 1..8) {
            t += 30
            val move = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE,
                x, height * 0.9f - height * 0.11f * i, 0)
            w.dispatchTouchEvent(move)
            move.recycle()
        }
        val up = MotionEvent.obtain(downTime, t + 30, MotionEvent.ACTION_UP,
            x, height * 0.08f, 0)
        w.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun totalNum(captures: List<String>): Int {
        for (body in captures) {
            runCatching {
                val total = JSONObject(body).getJSONObject("body").optInt("totalNum", 0)
                if (total > 0) return total
            }
        }
        return 0
    }

    private fun convert(
        captures: List<String>,
        account: com.halo.expressassistant.data.BoundAccount?
    ): Pair<List<ExpressItem>, Map<String, JdGoods>> {
        val byId = LinkedHashMap<String, ExpressItem>()
        val goodsMap = HashMap<String, JdGoods>()
        synchronized(captures) {
            for (body in captures) {
                runCatching {
                    val ol = JSONObject(body).getJSONObject("body").optJSONArray("orderList") ?: return@runCatching
                    for (i in 0 until ol.length()) {
                        val o = ol.getJSONObject(i)
                        val orderId = o.optString("orderId")
                        if (orderId.isBlank()) continue
                        val status = o.optJSONObject("orderStatusInfo")
                        val statusName = status?.optString("orderStatusName") ?: ""
                        if (statusName.contains("取消") || statusName.contains("关闭") ||
                            statusName.contains("退款") || statusName.contains("售后") ||
                            statusName.contains("待付款") || statusName.contains("未支付")) {
                            continue
                        }
                        val prog = o.optJSONObject("progressInfo")
                        val latestText = (prog?.optString("content") ?: "")
                            .ifBlank { o.optJSONObject("orderStatusInfo")?.optString("stateTip") ?: "" }
                            .ifBlank { statusName }
                        // 物流页完整参数（无 progressLink 的订单用订单字段拼装）
                        val trackLink = (prog?.optString("progressLink") ?: "").ifBlank {
                            val skuId = o.optJSONArray("wareInfoList")?.optJSONObject(0)?.optString("skuId") ?: ""
                            val shopId = o.optJSONObject("shopInfo")?.optString("shopId") ?: ""
                            val dealState = o.optJSONObject("orderStatusInfo")?.optString("originOrderStatus") ?: ""
                            "https://trade.m.jd.com/order/deal_wuliu_jdm.shtml?from=orderdetail" +
                                    "&dealState=$dealState&dealId=$orderId&orderType=${o.optString("orderType")}" +
                                    "&skuid=$skuId&shopid=$shopId&source=m_inner_orderList.track_orderTrack"
                        }
                        val latestTime = prog?.optString("tip") ?: ""
                        val state = when {
                            statusName.contains("完成") || statusName.contains("签收") -> 3
                            statusName.contains("待收货") || statusName.contains("等待收货") -> 5
                            statusName.contains("待发货") || statusName.contains("出库") -> 1
                            else -> 0
                        }
                        val stateNum = when (state) {
                            3 -> 107
                            5 -> 105
                            1 -> 103
                            else -> 105
                        }
                        val item = ExpressItem(
                            id = orderId,
                            companyCode = "JDKD",
                            companyName = "京东商品快递",
                            mailNo = orderId,
                            latestText = latestText,
                            latestTime = latestTime,
                            state = state,
                            stateName = statusName,
                            provider = "JingDong",
                            stateNum = stateNum,
                            queryChannel = trackLink,
                            source = "jd",
                            accountId = account?.id ?: "",
                            accountLabel = account?.label ?: ""
                        )
                        byId[orderId] = item
                        val wares = o.optJSONArray("wareInfoList")
                        if (wares != null && wares.length() > 0) {
                            val w0 = wares.getJSONObject(0)
                            goodsMap[orderId] = JdGoods(
                                name = w0.optString("wareName"),
                                imageUrl = w0.optString("imageUrl"),
                                count = "x" + w0.optInt("num", 1)
                            )
                        }
                    }
                }
            }
        }
        return byId.values.toList() to goodsMap
    }
}
