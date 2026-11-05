package com.sean.rootguide

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 解析 GitHub 最新 Release → 下载资产 → 交给 ksud / magisk / apd 安装。
 *
 * 三个刻意的设计取舍：
 *
 * 1. **下载直链不写死**。资产名带版本号 + commit 短哈希，写死必过期；每次走
 *    `/releases/latest` 现查。代价是依赖 GitHub API（未认证 60 次/小时，够用），
 *    失败时把原因原样抛给用户，而不是静默退回旧版本。
 *
 * 2. **文件先落到 App 私有目录，再用 root 复制到 /data/local/tmp**。App 进程写不了
 *    /data/local/tmp（属 shell:shell），但 root 读得到 App 私有目录；这一步不做，
 *    `ksud module install` 会因为 SELinux 上下文读不到文件而失败。
 *
 * 3. **只装用户点的那一个 zip**。不批量、不自动挑、不改写任何分区。
 */
object ModuleInstaller {

    private const val UA = "RootGuide/1.1 (Android)"

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val asset: Asset)

    class NetException(message: String) : Exception(message)

    /** 查最新 Release 并挑出与 [ModuleSpec.assetRegex] 匹配的资产 */
    fun resolve(spec: ModuleSpec): Release {
        val json = getJson("https://api.github.com/repos/${spec.repo}/releases/latest")
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name", "").ifBlank { "latest" }
        val assets = obj.optJSONArray("assets") ?: JSONArray()
        if (assets.length() == 0) {
            throw NetException("${spec.repo} 的最新 Release（$tag）没有附带任何文件，只能去仓库页手动下载。")
        }

        val regex = Regex(spec.assetRegex, RegexOption.IGNORE_CASE)
        val all = (0 until assets.length()).mapNotNull { i ->
            val a = assets.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (name.isBlank() || url.isBlank()) return@mapNotNull null
            Asset(name, url, a.optLong("size", 0L))
        }

        val usable = all.filter { asset ->
            spec.excludeIfContains.none { asset.name.contains(it, ignoreCase = true) }
        }
        val hit = usable.firstOrNull { regex.matches(it.name) }
            ?: usable.firstOrNull { it.name.endsWith(".zip", true) || it.name.endsWith(".apk", true) }
            ?: throw NetException("${spec.repo} 的 $tag 里没有合适的安装包：${all.joinToString { it.name }}")

        return Release(tag, hit)
    }

    /** 下载到 dest，回调 0..100 */
    fun download(asset: Asset, dest: File, onProgress: (Int) -> Unit): File {
        dest.parentFile?.mkdirs()
        val conn = open(asset.url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw NetException("下载失败：HTTP $code（${asset.name}）")
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else asset.size
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!dest.exists() || dest.length() == 0L) throw NetException("下载后文件为空：${dest.name}")
        return dest
    }

    /**
     * 复制到 root 进程读得到的位置。
     * 返回目标路径；失败返回 null（调用方要提示用户改用手动安装页）。
     */
    fun stageForRoot(file: File): String? {
        val safeName = file.name.replace("\"", "").replace(" ", "_")
        val dest = "/data/local/tmp/rg_${System.currentTimeMillis()}_$safeName"
        val src = file.absolutePath.replace("\"", "")
        val r = SuExec.run(
            "cp \"$src\" \"$dest\" 2>/dev/null || cat \"$src\" > \"$dest\"; " +
                    "chmod 644 \"$dest\" 2>/dev/null; " +
                    "if [ -s \"$dest\" ]; then echo STAGED_OK; fi",
            timeoutSec = 120
        )
        return if (r.out.contains("STAGED_OK")) dest else null
    }

    /** 按体系拼安装命令 */
    fun installCmd(family: Family, path: String, kind: ArtifactKind): String = when {
        kind == ArtifactKind.APK -> "pm install -r \"$path\""
        family == Family.MAGISK -> "magisk --install-module \"$path\""
        family == Family.APATCH -> "/data/adb/ap/bin/apd module install \"$path\""
        else -> "ksud module install \"$path\""
    }

    fun install(family: Family, path: String, kind: ArtifactKind): SuExec.Result =
        SuExec.run(installCmd(family, path, kind), timeoutSec = 240)

    /** 列出已装模块（用于装完复核） */
    fun listModules(family: Family): List<String> {
        val script = when (family) {
            Family.MAGISK -> "ls -1 /data/adb/modules 2>/dev/null"
            else -> "ksud module list 2>/dev/null"
        }
        val out = SuExec.out(script, 20)
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun getJson(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) {
                val hint = when (code) {
                    403 -> "GitHub 接口限流（403）。等一小时，或在电脑上下载后用「手动安装模块」。"
                    404 -> "仓库或 Release 不存在（404）。"
                    in 500..599 -> "GitHub 服务端错误（$code），稍后重试。"
                    else -> "HTTP $code"
                }
                throw NetException(hint)
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
}
