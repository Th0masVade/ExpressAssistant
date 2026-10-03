package com.halo.expressassistant.api

import android.webkit.CookieManager

/**
 * 京东 cookie 的「注入域」清单与注入实现。
 *
 * ★★★ 2026-10-03 新增：**注入域必须与采集域一致**。
 *
 * ── 问题现象 ──
 * 在模拟器/真机上绑定京东后，同步时订单页**必被跳回登录页**：
 * ```
 * JdListFetcher: page: https://plogin.m.jd.com/login/nopasswordcmcc?...
 * JdListFetcher: login challenge (silent) -> wait page continue
 * JdListFetcher: skip anon overwrite: keep login payload (2495)
 * JdListFetcher: round=1..4 orders=0 total=0 captures=0
 * ```
 * 结果：三个渠道 count 全为 0，首页数据永远停在旧快照。
 *
 * ── 根因：不是「采集不全」，是「注入不全」 ──
 * 京东的登录 cookie 是**分批下发到不同域**的：
 *   · `pt_key` / `pt_pin` / `pt_token`      → 移动域（www.jd.com 上也能看到）
 *   · `lsid` / `lstoken` / `s_key` / `s_pin` → **PC 域**（trade.jd.com / order.jd.com）才给
 *
 * 采集端（`JdListFetcher.collectJdCookies`）在 2026-09-28 已经补齐到 11 个域，
 * 所以**存下来的 payload 是完整的**（实测 42 个字段，pc 域那几个都在）。
 * 但**注入端一直没跟上** —— 各处只往 5 个移动域塞：
 * ```
 * cm.setCookie("https://www.jd.com", ...)
 * cm.setCookie("https://wqs.jd.com", ...)
 * cm.setCookie("https://trade.m.jd.com", ...)
 * cm.setCookie("https://api.m.jd.com", ...)
 * cm.setCookie("https://jingfen.jd.com", ...)
 * ```
 * 于是下次加载订单页时，**PC 域那几个关键 cookie 根本没进 WebView**，
 * `getCookie("https://trade.m.jd.com")` 拿不到 `pt_key` →
 * 日志判成「匿名会话」→ `skip anon overwrite`（保护旧 payload 不被匿名集冲掉）→
 * 订单页仍以未登录身份加载 → 被跳 plogin。
 *
 * ── 为什么以前「看起来是好的」 ──
 * 登录当时 WebView 里是完整的（用户刚在页面里登完），所以**当场那次同步能过**。
 * 等 App 重启、WebView 会话重置后再同步，就必然脱线 ——
 * 也就是「重登也没用，隔一会儿又不行」这个体感。
 *
 * ── 修法 ──
 * 把注入清单补成与采集一致，并统一到一个地方，避免以后又只改一边。
 */
object JdCookieInject {

    /**
     * 完整域清单。
     *
     * ★ 必须与 `JdListFetcher.collectJdCookies()` 的列表保持一致 ——
     *   那两处分别是「读」和「写」，不一致就会出现本文件开头描述的问题。
     *   以后改动请**同时**改这两处（或在采集端也引用本常量）。
     */
    val HOSTS = listOf(
        // ── PC 域：lsid / lstoken / s_key / s_pin 只在这些域才有效 ──
        "https://www.jd.com",
        "https://trade.jd.com",
        "https://order.jd.com",
        "https://home.jd.com",
        // ── 移动域 ──
        "https://wqs.jd.com",
        "https://trade.m.jd.com",
        "https://api.m.jd.com",
        "https://wq.jd.com",
        "https://home.m.jd.com",
        "https://jingfen.jd.com",
        // ── 其他会被用到的 ──
        "https://u.jd.com",
        "https://plogin.m.jd.com"
    )

    /**
     * 把一份 cookie 串注入到 [HOSTS] 里的每个域，并 flush。
     *
     * ★ 为什么逐个域 setCookie 而不是写一次 `Domain=.jd.com`：
     *   Android 的 `CookieManager.setCookie(url, cookie)` 对 cookie 串里的
     *   `Domain=` 属性支持并不一致（部分版本会忽略），
     *   逐个域显式写入是**跨版本最稳**的做法 —— 且与现有采集端行为对称。
     *
     * @param cookies 形如 `pt_key=xxx; pt_pin=yyy; lsid=zzz` 的 cookie 串
     */
    fun inject(cookies: String) {
        if (cookies.isBlank()) return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        for (part in cookies.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) {
                for (host in HOSTS) {
                    cm.setCookie(host, "${kv[0]}=${kv[1]}")
                }
            }
        }
        cm.flush()
    }
}
