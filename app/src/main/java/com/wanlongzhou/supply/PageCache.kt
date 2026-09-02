package com.wanlongzhou.supply

import android.content.Context
import java.io.File

/**
 * 业务页面本地缓存 —— 支撑「秒开」与「热更新」
 *
 * ── 为什么可以缓存（关键前提，改动前必读）──────────────────
 * 线上是【两层结构】，必须先分清：
 *
 *   ① 外壳页  https://www.workbuddy.link/p/<nodeId>      （约 4KB，React 运行时）
 *       职责：注入 window.__SMART_PAGE__（含 database SDK 与登录态）
 *       → 必须每次走网络，【绝不能缓存】，否则拿不到登录态、系统不可用
 *
 *   ② 业务页  https://.../page/<nodeId>/<rev>/hotel_requisition.html （约 540KB）
 *       职责：全部界面与业务逻辑，【纯静态】
 *       → 已实测：文件内 __SMART_PAGE__ 赋值出现 0 次，不含任何运行时 token
 *       → 因此【可以安全缓存】，缓存它不会影响登录态与数据访问
 *
 * 本类只缓存 ②。这就是「秒开 + 热更新」能同时成立的原因：
 *   秒开   = 540KB 走本地，不再每次下载
 *   热更新 = 后台拉取 ② 的新版本，比对 APP_VERSION，更新后下次启动生效
 *
 * ── 安全约束 ──────────────────────────────────────────
 * · 只按 APP_VERSION 覆盖，绝不无条件覆盖（防止把新版覆盖回旧版）
 * · 写入用「临时文件 + 重命名」，避免中断产生半截文件导致页面白屏
 * · 版本号取自 HTML 内容本身，不依赖 URL 里的 rev 段（rev 会随发布变化）
 */
object PageCache {

    private const val DIR = "pagecache"
    private const val F_HTML = "biz.html"
    private const val F_VER = "biz.ver"

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 本地缓存的版本号（形如 v20260901-122），无缓存返回 null */
    fun version(ctx: Context): String? = try {
        File(dir(ctx), F_VER).takeIf { it.exists() }
            ?.readText()?.trim()?.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    /** 本地缓存的页面内容，无缓存返回 null */
    fun bytes(ctx: Context): ByteArray? = try {
        File(dir(ctx), F_HTML).takeIf { it.exists() && it.length() > 1024 }?.readBytes()
    } catch (_: Exception) {
        null
    }

    /**
     * 保存页面。仅当 [ver] 比本地版本新时才写入，返回是否真的更新了。
     * 版本相同返回 false（避免无谓的磁盘写入）。
     */
    fun save(ctx: Context, html: ByteArray, ver: String): Boolean {
        if (html.size < 1024) return false
        val cur = version(ctx)
        if (cur != null && !isNewer(ver, cur)) return false
        return try {
            val d = dir(ctx)
            val tmpH = File(d, "$F_HTML.tmp")
            val tmpV = File(d, "$F_VER.tmp")
            tmpH.writeBytes(html)
            tmpV.writeText(ver)
            // 原子替换：先版本后内容，避免「新版本 + 旧内容」的窗口
            if (!tmpV.renameTo(File(d, F_VER))) return false
            if (!tmpH.renameTo(File(d, F_HTML))) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clear(ctx: Context) {
        try {
            dir(ctx).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /** 从 HTML 中提取 var APP_VERSION='v20260901-122' */
    fun extractVersion(html: String): String? = try {
        Regex("""var\s+APP_VERSION\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
    } catch (_: Exception) {
        null
    }

    /**
     * 版本号比较：先看日期段（vYYYYMMDD-nn），日期相同再比序号。
     * 任一端解析失败时退化为字符串比较。
     */
    fun isNewer(a: String, b: String): Boolean {
        if (a == b) return false
        val ra = Regex("""v(\d{8})-(\d+)""").find(a)
        val rb = Regex("""v(\d{8})-(\d+)""").find(b)
        if (ra == null || rb == null) return a > b
        val da = ra.groupValues[1]
        val db = rb.groupValues[1]
        if (da != db) return da > db
        val na = ra.groupValues[2].toIntOrNull() ?: 0
        val nb = rb.groupValues[2].toIntOrNull() ?: 0
        return na > nb
    }
}
