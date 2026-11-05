import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sean.rootguide"
    compileSdk = 35

    // 构建用的最小 SDK 在 ~/.local/android-sdk（platform-35 + build-tools 35.0.1）
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.sean.magicrootguide"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "1.20"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}

// ───────────── 构建时自动抓取 LSP 模块名单快照（国内多镜像依次尝试）─────────────
// 快照 <24h 时跳过以加快构建；失败保留旧快照。强制刷新：-PforceCatalog=true
val catalogSnapshot = file("src/main/assets/detector/xposed_modules.txt")
val forceCatalog = (project.findProperty("forceCatalog")?.toString() ?: "false").toBoolean()

fun httpGetText(url: String, timeoutMs: Int): String? = try {
    // 若环境里配了代理（HTTP(S)_PROXY），Java 默认不走，这里手动套上，保证在代理/公司网/沙箱里也能抓
    val proxyEnv = (System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
        ?: System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy"))?.trim()
    val proxy: Proxy? = proxyEnv?.takeIf { it.isNotEmpty() }?.let {
        val hp = it.removePrefix("http://").removePrefix("https://").substringBefore("/")
        val host = hp.substringBefore(":")
        val port = hp.substringAfter(":", "80").toIntOrNull() ?: 80
        if (host.isNotBlank()) Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)) else null
    }
    val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", "MagicRootGuide-build")
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    conn.disconnect()
    body
} catch (e: Exception) {
    null
}

val updateXposedSnapshot = tasks.register("updateXposedSnapshot") {
    description = "Fetch LSP (Xposed-Modules-Repo) module catalog snapshot, picking the fastest mirror."
    group = "build"
    val out = catalogSnapshot
    val force = forceCatalog
    doLast {
        if (!force && out.exists() && (System.currentTimeMillis() - out.lastModified()) < 24L * 3600_000L) {
            logger.lifecycle("[catalog] 快照 <24h（${out.readLines().size} 条），跳过抓取。")
            return@doLast
        }
        // 3~4 个镜像候选（不同网络下效果不同），先测速再选最快
        val bases = listOf(
            "https://gh-proxy.com/https://api.github.com",
            "https://api.github.com",
            "https://ghfast.top/https://api.github.com",
            "https://ghproxy.net/https://api.github.com"
        )
        val re = Regex("\"full_name\"\\s*:\\s*\"Xposed-Modules-Repo/([^\"]+)\"")
        // ① 速度探测：各拉第 1 页，记录耗时，选最快且有效的
        var best: String? = null
        var bestMs = Long.MAX_VALUE
        for (b in bases) {
            val t0 = System.currentTimeMillis()
            val body = httpGetText("$b/organizations/78217009/repos?per_page=100&page=1", 8000)
            val ms = System.currentTimeMillis() - t0
            if (body != null && body.contains("\"full_name\"")) {
                logger.lifecycle("[catalog] 探测 ${b.substringAfter("://").substringBefore("/")} -> ${ms}ms")
                if (ms < bestMs) {
                    bestMs = ms
                    best = b
                }
            } else {
                logger.lifecycle("[catalog] 探测 ${b.substringAfter("://").substringBefore("/")} -> 失败")
            }
        }
        if (best == null) {
            logger.warn("[catalog] 所有镜像探测失败，保留旧快照（${if (out.exists()) out.readLines().size else 0} 条）。")
            return@doLast
        }
        // ② 用最快的镜像抓全部页
        val names = LinkedHashSet<String>()
        var page = 1
        while (page <= 16) {
            val body = httpGetText("$best/organizations/78217009/repos?per_page=100&page=$page", 20000) ?: break
            val found = re.findAll(body).map { it.groupValues[1] }.toList()
            if (found.isEmpty()) break
            names.addAll(found)
            if (found.size < 100) break
            page++
        }
        if (names.size > 500) {
            out.parentFile.mkdirs()
            out.writeText(names.sorted().joinToString("\n"))
            logger.lifecycle("[catalog] 用 ${best.substringAfter("://").substringBefore("/")}（${bestMs}ms）抓到 ${names.size} 个模块名 -> ${out.name}")
        } else {
            logger.warn("[catalog] 抓取不足（${names.size}），保留旧快照。")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(updateXposedSnapshot) }
