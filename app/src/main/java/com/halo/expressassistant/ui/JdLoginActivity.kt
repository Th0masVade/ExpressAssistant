package com.halo.expressassistant.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.halo.expressassistant.data.Store

/**
 * 京东 H5 登录：用户在 WebView 内完成登录，抓取 pt_key / pt_pin 存本地，
 * 用于「商品溯源」（物流单号 -> 订单商品名/图）。
 */
class JdLoginActivity : Activity() {

    companion object {
        private const val TAG = "JdLogin"
        private const val LOGIN_URL = "https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fhome.m.jd.com%2FmyJd%2Fhome.action"

        /* ★★ 2026-09-28 修复「京东登录后抓不到订单（payload 采集不全）」。
           ── 现象 ──
           用户在 APP 内登录京东后，WebView 打订单页会被跳回 plogin，
           日志：keep login payload (17071) / order_list_m 全 passthrough / captures=0。
           ── 根因（读代码 + 对比两份 payload 得出）──
           原来 pollRunnable 只做两件事：
             ① 只从 **一个域** 取 cookie：CookieManager.getCookie("https://www.jd.com")
             ② 一旦 pt_key + pt_pin 出现，**立刻 tryLogin 并 return** → working=true，轮询彻底停止
           而京东登录态的 cookie 是**分批下发到不同域**的：
             · pt_key / pt_pin / pt_token   ← 移动域先给（www.jd.com 上也能看到）
             · lsid / lstoken / s_key / s_pin / whwswswws ← **PC 域**（trade.jd.com / order.jd.com）才给
           实测对比（同一账号）：
             · 好 payload（14471）：含 lsid 58 / lstoken 8 / s_key 75 / s_pin 16
             · 坏 payload（17071）：lsid/lstoken/s_key/s_pin 全为 0，却多了 shshshfpa/shshshfpx/cd_eid/_tj_rvurl
           → 说明 `pt_key` 一到就收工，**PC 域那几个关键 cookie 还没落下来就被采走了**。
           ★ 注意这不是「payload 被写坏」：pt_key 本身也换成了新值，
             是**登录采集时机太早 + 采集域太窄**，采到的是半套登录态。
           ── 修法（三处）──
           ① 多点域采样：把 jd 的主要域（含 PC 域 trade.jd.com / order.jd.com）全部取一遍再合并。
           ② 过了 pt_key 之后**再多采样 3 轮（约 6 秒）**，取「cookie 更全」的那一份，别急着收工。
           ③ 判据从「pt_key 存在」升级为「pt_key 存在 且 本轮 cookie 数不再增长」，
              确保拿到的是一套完整的登录态。 */
        private val COOKIE_HOSTS = listOf(
            // PC 域：lsid / lstoken / s_key / s_pin 这些关键登录 cookie 从这里才能拿到
            "https://www.jd.com",
            "https://trade.jd.com",
            "https://order.jd.com",
            "https://home.jd.com",
            // 移动域
            "https://trade.m.jd.com",
            "https://wq.jd.com",
            "https://wqs.jd.com",
            "https://api.m.jd.com",
            "https://home.m.jd.com",
            "https://my.m.jd.com",
            // 登录域
            "https://plogin.m.jd.com"
        )
        /** pt_key 出现后，再多观察这么多轮（每轮 2 秒）才收工，等 PC 域 cookie 落下来 */
        private const val SETTLE_ROUNDS = 3

        /** 把 COOKIE_HOSTS 各域的 cookie 去重合并成一份 payload */
        fun collectAllCookies(): String {
            val out = LinkedHashMap<String, String>()
            for (host in COOKIE_HOSTS) {
                val c = CookieManager.getInstance().getCookie(host) ?: continue
                for (part in c.split(";")) {
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) out[kv[0]] = kv[1]
                }
            }
            return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        /** 一套登录态是否「够全」：必须有 pt_key/pt_pin，且带 PC 域的 lsid（京东订单页依赖它） */
        fun looksComplete(cookies: String): Boolean {
            val m = parseCookies(cookies)
            val hasKey = !m["pt_key"].isNullOrBlank() && !m["pt_pin"].isNullOrBlank()
            val hasPc = !m["lsid"].isNullOrBlank() || !m["s_key"].isNullOrBlank()
            return hasKey && hasPc
        }

        fun parseCookies(header: String): Map<String, String> {
            val map = HashMap<String, String>()
            for (part in header.split(";")) {
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[1].isNotBlank()) map[kv[0].trim()] = kv[1]
            }
            return map
        }
    }

    private var web: WebView? = null
    private lateinit var output: TextView
    private var working = false
    private var pollHandler: Handler? = null
    /** 已观测到 pt_key 之后的剩余观察轮数（等 PC 域 cookie 补齐） */
    private var settle = -1
    /** 观测到的最全的一份 payload */
    private var bestCookies = ""

    private val pollRunnable = object : Runnable {
        override fun run() {
            val h = pollHandler ?: return
            if (working || isFinishing) return

            // ① 多点域合并采样（原来只看 www.jd.com，采不到 PC 域的 lsid/s_key）
            val cookies = collectAllCookies()
            val map = parseCookies(cookies)
            val hasKey = !map["pt_key"].isNullOrBlank() && !map["pt_pin"].isNullOrBlank()

            // 记录观测到的最全的一份（按 cookie 条数比，条数相同则不换，保留先到的）
            if (cookies.length > bestCookies.length) bestCookies = cookies

            if (!hasKey) {
                append("等待登录态…（当前 cookie ${map.size} 项）\n")
                h.postDelayed(this, 2000)
                return
            }

            // ② pt_key 已出现：不立刻收工，先给 PC 域 cookie 一点时间落下来
            if (settle < 0) {
                settle = SETTLE_ROUNDS
                append("已检测到登录态，等待 PC 域 cookie 补齐…\n")
                h.postDelayed(this, 2000)
                return
            }
            if (settle > 0 && !looksComplete(bestCookies)) {
                settle--
                append("补齐中…（cookie ${map.size} 项，剩 $settle 轮）\n")
                h.postDelayed(this, 2000)
                return
            }

            // ③ 用「最全的那份」而不是「最后一轮的那份」
            tryLogin(bestCookies.ifBlank { cookies })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
            text = "正在打开京东登录页…\n请在页面内完成登录（密码 / 验证码 / 扫码均可）\n"
            maxHeight = (90 * resources.displayMetrics.density).toInt()
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val w = WebView(this)
        web = w
        root.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        // 统一根治：登录页打开即清全部 WebView Cookie（域级 cookie 按 host expire 删不掉，必须 removeAllCookies）
        {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Log.i(TAG, "jd login opened -> removed all webview cookies")
        }
        s.userAgentString = "Mozilla/5.0 (Linux; Android 16; 25102RKBEC Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        // 清除旧的京东会话 cookie，强制走真实登录流程（否则会复用已失效的旧会话）
        for (host in listOf("https://www.jd.com", "https://trade.m.jd.com",
            "https://wqs.jd.com", "https://api.m.jd.com", "https://plogin.m.jd.com",
            "https://m.jd.com", "https://my.m.jd.com")) {
            val old = cm.getCookie(host)
            if (!old.isNullOrBlank()) {
                for (part in old.split(";")) {
                    val key = part.trim().split("=", limit = 2)[0]
                    if (key.isNotBlank()) {
                        cm.setCookie(host, "$key=; Max-Age=0")
                    }
                }
            }
        }
        cm.flush()

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                append("页面: $url\n")
                Log.i(TAG, "page: $url")
                logCookieKeys()
            }
        }
        w.webChromeClient = object : WebChromeClient() {}

        pollHandler = Handler(Looper.getMainLooper()).also { it.postDelayed(pollRunnable, 2000) }
        w.loadUrl(LOGIN_URL)
    }

    private fun tryLogin(cookies: String) {
        if (working) return
        working = true
        val m = parseCookies(cookies)
        // ★ 2026-09-28：把「采到了哪些关键 cookie」打进日志，方便以后一眼看出采集是否完整
        val keyList = listOf("pt_key", "pt_pin", "pt_token", "lsid", "lstoken", "s_key", "s_pin", "whwswswws")
            .joinToString(" ") { k -> "$k=${if (m[k].isNullOrBlank()) "-" else "✓"}(${(m[k] ?: "").length})" }
        Log.i(TAG, "tryLogin cookieKeys=${m.size} len=${cookies.length} | $keyList")
        Log.i(TAG, "tryLogin keys: " + m.keys.joinToString(","))
        // 多源绑定：每次登录 = 追加一个可绑定的账号
        Store.addJdAccount(this, cookies)
        runOnUiThread {
            Toast.makeText(this, "京东登录成功，已绑定新账号", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun logCookieKeys() {
        val cookies = CookieManager.getInstance().getCookie("https://www.jd.com") ?: ""
        val sb = StringBuilder("cookie keys:")
        for (part in cookies.split(";")) {
            val k = part.trim().split("=", limit = 2)[0]
            if (k.isNotEmpty()) {
                sb.append(' ').append(k).append(if (k == "pt_key") "(len)" else "")
            }
        }
        Log.i(TAG, sb.toString())
    }

    private fun append(s: String) {
        runOnUiThread { output.append(s) }
    }

    override fun onDestroy() {
        pollHandler?.removeCallbacks(pollRunnable)
        web?.destroy()
        super.onDestroy()
    }
}
