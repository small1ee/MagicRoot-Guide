package com.sean.rootguide

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * LSP 模块名单：打包快照 + 运行时从 GitHub（Xposed-Modules-Repo —— LSP Store 的数据源）实时刷新。
 *
 * 只负责「拿到一份模块包名集合」，供环境检测把**已安装应用**与名单比对。
 *  - 打包快照 `assets/detector/xposed_modules.txt`：离线/被墙时兜底（~1450 个包名）。
 *  - 运行时刷新：拉 `Xposed-Modules-Repo` 全部仓库名（= 模块包名），成功则写缓存 + 时间戳。
 *  - 缓存 6 小时内视为新鲜；刷新失败有 30 分钟冷却，避免每次都卡在网络超时上。
 */
object XposedCatalog {

    private const val ASSET = "detector/xposed_modules.txt"
    private const val CACHE = "xposed_catalog.txt"
    private const val TS = "xposed_catalog.ts"
    private const val ATTEMPT = "xposed_catalog.attempt"
    private const val TTL = 6 * 60 * 60 * 1000L          // 6h
    private const val RETRY_COOLDOWN = 30 * 60 * 1000L   // 失败后 30 分钟内不再重试
    private const val ORG_ID = "78217009"                // Xposed-Modules-Repo
    private const val MAX_PAGES = 16

    fun bundled(ctx: Context): Set<String> = try {
        ctx.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(".") }.toSet()
        }
    } catch (e: Exception) {
        emptySet()
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, CACHE)
    private fun tsFile(ctx: Context) = File(ctx.filesDir, TS)
    private fun attemptFile(ctx: Context) = File(ctx.filesDir, ATTEMPT)

    fun cached(ctx: Context): Set<String> = try {
        val f = cacheFile(ctx)
        if (!f.exists()) emptySet()
        else f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun fresh(ctx: Context): Boolean = try {
        System.currentTimeMillis() - tsFile(ctx).readText().trim().toLong() < TTL
    } catch (e: Exception) {
        false
    }

    private fun recentlyAttempted(ctx: Context): Boolean = try {
        System.currentTimeMillis() - attemptFile(ctx).readText().trim().toLong() < RETRY_COOLDOWN
    } catch (e: Exception) {
        false
    }

    /** 立即拿到的名单：本地打包快照 ∪ 网络缓存（取并集，两者谁有都算上，等于「用最新的」） */
    fun current(ctx: Context): Set<String> {
        val b = bundled(ctx)
        val c = cached(ctx)
        return when {
            b.isEmpty() -> c
            c.isEmpty() -> b
            else -> b + c
        }
    }

    /**
     * 检测时调用：本地快照 ∪ 网络缓存，若缓存过期再在预算内尝试实时刷新（带冷却），
     * 成功则把新拉到的名字也并进来。只应在后台线程调用（可能阻塞数秒）。
     */
    fun resolve(ctx: Context, budgetMs: Long = 6000): Set<String> {
        val base = current(ctx)
        if (cached(ctx).isNotEmpty() && fresh(ctx)) return base
        if (recentlyAttempted(ctx)) return base
        runCatching { attemptFile(ctx).writeText(System.currentTimeMillis().toString()) }
        val remote = refresh(ctx, budgetMs) ?: return base
        return base + remote
    }

    /** 实时从 GitHub 拉取全部模块仓库名；成功写缓存，失败/超预算返回 null */
    fun refresh(ctx: Context, budgetMs: Long = 15000): Set<String>? {
        val names = HashSet<String>()
        val start = System.currentTimeMillis()
        var page = 1
        try {
            while (page <= MAX_PAGES && System.currentTimeMillis() - start < budgetMs) {
                val url = URL("https://api.github.com/organizations/$ORG_ID/repos?per_page=100&page=$page")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "MagicRootGuide")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val body = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    conn.disconnect(); break
                }
                conn.disconnect()
                val arr = try { JSONArray(body) } catch (e: Exception) { break }
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val n = arr.optJSONObject(i)?.optString("name") ?: ""
                    if (n.isNotBlank() && !n.startsWith(".")) names.add(n)
                }
                if (arr.length() < 100) break
                page++
            }
        } catch (e: Exception) {
            // 网络不可用 / 被墙 / 限流：静默失败，回落到快照
        }
        return if (names.size > 200) {
            runCatching {
                cacheFile(ctx).writeText(names.joinToString("\n"))
                tsFile(ctx).writeText(System.currentTimeMillis().toString())
            }
            names
        } else null
    }
}
