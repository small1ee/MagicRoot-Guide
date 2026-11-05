package com.sean.rootguide

import java.io.File
import kotlin.math.abs

/**
 * 风控 / 隐藏环境检测（对接《春秋检测项解决方案》answer_zh.md 4.6.2）。
 *
 * 说明：mingzun09/Chunqiu-Detector-Problem-solution 仓库本身**不含检测引擎**，它只是
 * 「检测项 → 解决方案」的知识库；真正的检测由「春秋检测器」这个独立 APK 完成（原生 / JNI 侧信道）。
 * 本模块做的是：**把其中本 App 能在设备上直接复现的检测项实跑一遍**（属性、包名、文件痕迹、
 * mountinfo、内核 uname、进程自身 /proc/self/maps 与 status、/data/local/tmp 元数据等——
 * 这些本就是未授权 App 也能看到的泄漏，或在自身进程命名空间内即可取证的痕迹），命中后**直接
 * 给出 answer_zh.md 里对应的解决方案**。仅有「时序 / 页驻留侧信道」这类纯原生计时侧信道无法在
 * 普通 App 里忠实复现，已做成**真实测量指示器**（标为启发式、绝不误报），其余项全部真实可跑。
 * 完整 135 项答案已随 App 打包（assets/detector/answer_zh.md），点「查看完整检测解决方案」可读。
 *
 * 完整 135 项答案已随 App 打包（assets/detector/answer_zh.md），点「查看完整检测解决方案」可读。
 */
object EnvDetector {

    enum class Severity { PASS, WARN, FAIL, INFO, SKIP }

    data class Check(
        /** 对应 answer_zh.md 的检测项编号/名称，便于查完整文档 */
        val item: String,
        val category: String,
        val title: String,
        /** true = 需要 root（su）才能看全；su 不可用时降级为 INFO */
        val needsRoot: Boolean,
        val run: () -> Outcome,
        /** 命中（FAIL/WARN）后直接展示的解决步骤，取自 answer_zh.md */
        val solution: String
    )

    data class Outcome(val severity: Severity, val evidence: String)

    data class Result(
        val check: Check,
        val severity: Severity,
        val evidence: String
    ) {
        val isProblem: Boolean get() = severity == Severity.FAIL || severity == Severity.WARN
    }

    // ── 普通 shell（无需 root）：/system/bin 绝对路径，避开 App PATH 不含它的问题 ──
    private fun sh(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val s = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        s
    } catch (e: Exception) {
        ""
    }

    private fun getprop(name: String): String =
        sh("/system/bin/getprop", name).trim()

    private fun readFile(path: String): String = try {
        File(path).readText()
    } catch (e: Exception) {
        ""
    }

    private fun fileExists(path: String): Boolean = runCatching { File(path).exists() }.getOrElse { false }

    /** root 下执行；su 不可用时返回空串 */
    private fun su(script: String): String = SuExec.run(script, 15).text()

    /** su 是否真正可用（触发一次探测并缓存） */
    private fun hasSu(): Boolean {
        if (SuExec.activeSuPath().isNotEmpty()) return true
        return SuExec.run("id -u", 4).out.lineSequence().any { it.trim() == "0" }
    }

    /** 本进程自身的内存映射（无需 root，App 总能读自己的 /proc/self/maps） */
    private fun readMaps(): String = readFile("/proc/self/maps")

    /** 本进程的 status（无需 root） */
    private fun readStatus(): String = readFile("/proc/self/status")

    /** 从 /proc/self/status 取某字段（如 "Seccomp:"、"Groups:"）的值 */
    private fun statusField(name: String): String {
        val line = readStatus().lines().firstOrNull { it.startsWith("$name:") } ?: return ""
        return line.substringAfter(":").trim()
    }

    private fun unameR(): String = sh("/system/bin/uname", "-r").trim()

    /** 常见 su 二进制路径（无需 root 即可 stat 大多数） */
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/product/bin/su",
        "/debug_ramdisk/su", "/data/adb/magisk/busybox", "/data/adb/ksu/bin/su",
        "/data/adb/sukisu/bin/su", "/data/adb/ap/bin/su"
    )

    /**
     * 侧信道真实测量（启发式）：在固定时间内跑一段空循环，统计前后非自愿/自愿上下文切换次数。
     * 只报真实测量值，**绝不**以 FAIL 误报；纯计时/页驻留侧信道本就无法在普通 App 里忠实复现，
     * 所以这一项永远只给 INFO，作为「是否值得怀疑」的参考指标。
     */
    private fun sideChannelProbe(): String {
        val nvBefore = statusField("nonvoluntary_ctxt_switches").toLongOrNull() ?: 0
        val vBefore = statusField("voluntary_ctxt_switches").toLongOrNull() ?: 0
        val t0 = System.nanoTime()
        var acc = 0L
        while (System.nanoTime() - t0 < 50_000_000L) acc++   // 忙等约 50ms
        val nvAfter = statusField("nonvoluntary_ctxt_switches").toLongOrNull() ?: 0
        val vAfter = statusField("voluntary_ctxt_switches").toLongOrNull() ?: 0
        val nvDelta = (nvAfter - nvBefore).coerceAtLeast(0)
        val vDelta = (vAfter - vBefore).coerceAtLeast(0)
        return "忙等 50ms（acc=$acc）：非自愿切换 +$nvDelta、自愿切换 +$vDelta（启发式测量，非误报指标）"
    }

    // ── Keystore 密钥证明（attestation）：无需 root 即可判定 TEE 是否真正背书 ──
    /** 读一个 DER TLV 头，返回 (tag, 内容起始, 内容长度)；失败返回 null */
    private fun derHeader(b: ByteArray, off: Int): Triple<Int, Int, Int>? {
        if (off + 1 >= b.size) return null
        val tag = b[off].toInt() and 0xFF
        var p = off + 1
        var len = b[p].toInt() and 0xFF
        p++
        if (len and 0x80 != 0) {
            val n = len and 0x7F
            if (n == 0 || n > 4) return null
            len = 0
            repeat(n) {
                if (p >= b.size) return null
                len = (len shl 8) or (b[p].toInt() and 0xFF); p++
            }
        }
        if (p + len > b.size) return null
        return Triple(tag, p, len)
    }

    /**
     * 生成一把带 attestationChallenge 的临时密钥，解析 KeyDescription 里的
     * attestationSecurityLevel：0=Software（无硬件背书）、1=TEE、2=StrongBox。
     * 无 root 也能跑；设备不支持 / ROM 裁剪时返回 null（降级为 INFO，不误报）。
     */
    private fun attestationSecurityLevel(): Int? {
        val alias = "rg_attest_probe"
        return try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            runCatching { ks.deleteEntry(alias) }
            val kpg = java.security.KeyPairGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                alias, android.security.keystore.KeyProperties.PURPOSE_SIGN
            )
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(byteArrayOf(0x52, 0x47, 0x01))
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
            val entry = ks.getEntry(alias, null) as? java.security.KeyStore.PrivateKeyEntry
            val cert = entry?.certificate as? java.security.cert.X509Certificate
            runCatching { ks.deleteEntry(alias) }
            if (cert == null) return null
            val ext = cert.getExtensionValue("1.3.6.1.4.1.11129.2.1.17") ?: return null
            val oct = derHeader(ext, 0) ?: return null          // OCTET STRING 包住 KeyDescription
            if (oct.first != 0x04) return null
            val seq = derHeader(ext, oct.second) ?: return null // SEQUENCE
            if (seq.first != 0x30) return null
            var p = seq.second
            val ver = derHeader(ext, p) ?: return null          // INTEGER attestationVersion
            p = ver.second + ver.third
            val lvl = derHeader(ext, p) ?: return null          // ENUMERATED attestationSecurityLevel
            if (lvl.first != 0x0A) return null
            var v = 0
            for (i in 0 until lvl.third) v = (v shl 8) or (ext[lvl.second + i].toInt() and 0xFF)
            v
        } catch (e: Exception) {
            runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
            null
        }
    }

    /** 最近一次「风险应用」合并检出的包名（供导出到隐藏应用列表黑名单） */
    @Volatile var lastRiskApps: List<String> = emptyList()
        private set

    // ── 已安装 Xposed 模块（PackageManager 扫 xposedmodule meta-data）──
    @Volatile private var appContext: android.content.Context? = null
    fun init(ctx: android.content.Context) { appContext = ctx.applicationContext }

    private fun xposedModules(ctx: android.content.Context): List<String> = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getInstalledPackages(android.content.pm.PackageManager.GET_META_DATA)
            .filter { it.applicationInfo?.metaData?.containsKey("xposedmodule") == true }
            .map { it.packageName }
            .sorted()
    } catch (e: Exception) {
        emptyList()
    }

    // ───────────────────────── 检测项定义 ─────────────────────────

    val checks: List<Check> = listOf(

        // ===== 一、属性 / 构建信息 =====
        Check("USB 调试已开启", "属性 / 构建信息", "USB 调试是否开启", false, {
            // 注意：adb_enabled 不是 system property（Android 存在 Settings.Global.ADB_ENABLED），
            // 旧逻辑读 persist.adb.enabled / adb_enabled 属性永远为空 → 误判「未开启」。
            // 无需 root 的可靠信号：adbd 守护是否 running、USB 配置是否含 adb、tcp 调试端口。
            val adbd = getprop("init.svc.adbd")
            val usb = getprop("sys.usb.config") + "," + getprop("persist.sys.usb.config")
            val tcp = getprop("service.adb.tcp.port")
            val prop = getprop("persist.adb.enabled").ifBlank { getprop("persist.service.adb.enable") }
            val on = adbd == "running" || usb.contains("adb") || tcp.isNotBlank() || prop == "1"
            if (on) Outcome(Severity.FAIL, "adbd=$adbd, sys.usb.config=${getprop("sys.usb.config")}, tcp=$tcp")
            else Outcome(Severity.PASS, "adbd=$adbd，未检测到 USB 调试开启")
        }, "关闭 USB 调试：开发者选项里关掉「USB 调试」；或 `su -c settings put global adb_enabled 0`；并把该命令写进 `/data/adb/service.d/` 开机自动关闭，避免重启后被系统/模块改回。"),

        Check("avb 校验异常 avb=2.0", "属性 / 构建信息", "vbmeta avb 版本是否被模块写成 2.0", false, {
            val v = getprop("ro.boot.vbmeta.avb_version")
            if (v == "2.0") Outcome(Severity.FAIL, "ro.boot.vbmeta.avb_version=2.0")
            else Outcome(Severity.PASS, "avb_version=$v（非 2.0）")
        }, "某模块（改机型 / Integrity Box 类）强制写了 avb_version=2.0。① 在 `/data/adb/modules/` 搜 avb_version 并修改/卸载问题模块；② 改 `/data/adb/service.d/hash.sh` 里 `\"\$RESETPROP\" ro.boot.vbmeta.avb_version \"2.0\"` 为 `\"1.3\"` 后重启；③ 临时：`su -c resetprop -n ro.boot.vb_version 1.3`（重启失效）。"),

        Check("Found property", "属性 / 构建信息", "persist.logd.size* 是否被置为非空", false, {
            val keys = listOf("persist.logd.size", "persist.logd.size.crash", "persist.logd.size.system", "persist.logd.size.main")
            val hit = keys.filter { getprop(it).isNotBlank() }
            if (hit.isNotEmpty()) Outcome(Severity.FAIL, hit.joinToString())
            else Outcome(Severity.PASS, "logd.size 类属性均为空")
        }, "执行仓库 File/Found property.sh（放 `/data/adb/service.d/` 并确认执行权限）后重启；该脚本会清理这些被检测器盯上的 logd 属性。"),

        Check("Vold 隔离已开启", "属性 / 构建信息", "persist.sys.vold_app_data_isolation_enabled 状态", false, {
            val v = getprop("persist.sys.vold_app_data_isolation_enabled")
            if (v == "1" || v == "true") Outcome(Severity.INFO, "vold appdata 隔离已开启（常见于 HMA / 应用隐藏模块）")
            else Outcome(Severity.PASS, "vold 隔离=$v")
        }, "若被检测器命中：关闭 HMA/HMA-OSS 设置里的「终端 Vold app data 隔离」；若重启后仍为 `=0`，执行 `su shell resetprop -p --delete persist.sys.vold_app_data_isolation_enabled` 再重启。注意它与 Miscellaneous Check(3) 互相牵制，按更优先的一条取舍。"),

        Check("Boot Hash 不匹配", "属性 / 构建信息", "ro.boot.vbmeta.digest 是否为 0000（真解锁特征）", false, {
            val d = getprop("ro.boot.vbmeta.digest")
            if (d.equals("0000000000000000000000000000000000000000000000000000000000000000", true)
                || d.equals("0".repeat(64), true)) Outcome(Severity.WARN, "vbmeta.digest=0000（真解锁设备 hash 变 0000）")
            else Outcome(Severity.PASS, "vbmeta.digest=$d")
        }, "用 Native detector 拿正确 hash，再用密钥模块 + TS 插件配置 `VerifiedBootHash` 把 digest 填回。假回锁 / 自签设备本就不需要这步。"),

        Check("Bootloader unlock", "属性 / 构建信息", "verifiedbootstate 是否为 orange/unlocked", false, {
            val s = getprop("ro.boot.verifiedbootstate")
            val locked = getprop("ro.boot.flash.locked")
            if (s == "orange" || s == "unlocked" || locked == "0")
                Outcome(Severity.INFO, "verifiedbootstate=$s, flash.locked=$locked（真解锁设备如实反映）")
            else Outcome(Severity.PASS, "verifiedbootstate=$s（锁定态）")
        }, "真解锁设备：用密钥模块把解锁状态对检测器隐藏，并把检测器加入包名 target 列表（实时生效，无需重启）。假回锁 / 免解 / 自签设备对外本就是 locked & green，通常不命中。"),

        // ===== 八、包管理 / 应用可见性 =====
        Check("发现 ROOT 管理器", "包管理 / 应用可见性", "是否安装了可被枚举到的 root 管理器", false, {
            val pm = sh("/system/bin/pm", "list", "packages")
            val managers = listOf(
                "com.topjohnwu.magisk", "com.kacher.mega", "io.github.vvb2060.mahide",
                "me.weishu.kernelsu", "com.sukisu.ultra", "com.rifsxd.ksunext",
                "me.yuki.fina", "com.anatdx.yukisu", "io.github.kubuko.kcfm"
            )
            val hit = managers.filter { pm.contains("package:${it}") }
            if (hit.isNotEmpty()) Outcome(Severity.FAIL, "检测到：${hit.joinToString()}")
            else Outcome(Severity.PASS, "未枚举到常见 root 管理器包名")
        }, "用**应用隐藏模块**（HMA / HMA-OSS / FuseFixer）把 root 管理器对检测器隐藏。注意本 App 自身若被识别为管理器类也会被算进去，隐藏时别把本 App 漏掉。"),

        Check("风险应用", "包管理 / 应用可见性", "风险应用（黑名单 + LSP 模块仓库 + Xposed 元数据 + ROOT 管理器，合并检出）", false, {
            val ctx = appContext
            val installed = sh("/system/bin/pm", "list", "packages").lines().map { it.trim() }
                .filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toSet()
            // ① 静态黑名单（Luna / Ruru / 春秋 实测口径）
            val blacklist = listOf(
                "com.xyoye.danmux", "com.gsn.thunder", "bin.mt.plus", "com.mt.manager",
                "com.ext.starfield", "de.robv.android.xposed.installer", "org.meowcat.edxposed.manager",
                "com.sollyu.xposed.hide", "com.devadvance.rootcloak", "com.koushikdutta.superuser",
                "com.locusmimic.app", "com.ss.android.ugc.aweme.yyds",
                "com.tiandi.moshi", "io.github.odomu.honorduo",
                "io.github.voreulch.honoradvanced", "top.hookvip.pro"
            )
            val byBlack = blacklist.filter { installed.contains(it) }
            // ② LSP 模块仓库名单（运行时拉取，失败回落打包快照 ~1452 个包名）
            val catalog = ctx?.let { XposedCatalog.resolve(it) } ?: emptySet()
            val byCatalog = installed.filter { catalog.contains(it) }
            // ③ xposedmodule 元数据（覆盖名单外模块）
            val byMeta = ctx?.let { xposedModules(it) } ?: emptyList()
            // ④ ROOT 管理器（导出到隐藏列表时同样需要隐藏）
            val rootMgrs = listOf(
                "com.topjohnwu.magisk", "com.kacher.mega", "io.github.vvb2060.mahide",
                "me.weishu.kernelsu", "com.sukisu.ultra", "com.rifsxd.ksunext",
                "me.yuki.fina", "com.anatdx.yukisu", "io.github.kubuko.kcfm"
            ).filter { installed.contains(it) }
            val all = (byBlack + byCatalog + byMeta + rootMgrs).toSortedSet().toList()
            lastRiskApps = all
            if (all.isEmpty()) Outcome(Severity.PASS, "未命中风险应用（比对名单 ${catalog.size} 个）")
            else Outcome(Severity.FAIL, "命中 ${all.size} 个风险应用：${all.joinToString()}")
        }, "把下列包名加入应用隐藏模块（HMA / HMA-OSS / FuseFixer）的黑名单，或对检测器开「限制 zygote 权限」（除 INET_GID 外全开）。点「导出风险应用名单」可一键复制到隐藏列表黑名单。"),
        Check("LSPosed / 框架痕迹", "包管理 / 应用可见性", "是否安装了 LSPosed / Xposed 框架管理器", false, {
            val pm = sh("/system/bin/pm", "list", "packages")
            val fx = listOf(
                "org.lsposed.manager", "io.github.lsposed.manager", "com.thinker.ace",
                "de.robv.android.xposed.installer", "org.meowcat.edxposed.manager",
                "com.solohsu.android.edxp.manager", "com.mukul.xposed"
            )
            val hit = fx.filter { pm.contains("package:${it}") }
            if (hit.isNotEmpty()) Outcome(Severity.WARN, "检测到框架管理器：${hit.joinToString()}")
            else Outcome(Severity.PASS, "未枚举到 LSPosed / Xposed 管理器")
        }, "某些 xp 模块修改导致被检；可卸载或更换 LSP 模块。若需保留，用应用隐藏模块把框架管理器对检测器隐藏，并清理 `/data/local/tmp/` 下 sandhook*/pumpcin*/shamiko/suidaemon 残留后重启复扫。"),

        Check("MT 管理器文件夹", "包管理 / 应用可见性", "/sdcard/MT2 是否存在", false, {
            val exists = fileExists("/storage/emulated/0/MT2")
            if (exists) Outcome(Severity.WARN, "/storage/emulated/0/MT2 存在")
            else Outcome(Severity.PASS, "未发现 MT2 文件夹")
        }, "在 MT 管理器设置里把 MT2 路径自定义修改即可解决（记得删除旧文件夹）。"),

        Check("HMA 或许存在", "包管理 / 应用可见性", "是否安装了隐藏应用列表模块", false, {
            val pm = sh("/system/bin/pm", "list", "packages")
            val hma = listOf("com.oasisfeng.island", "ratheroffensive.hidemyapplist", "com.tsng.hidemyapplist", "com.hmdm.hidemylauncher")
            val hit = hma.filter { pm.contains("package:${it}") }
            if (hit.isNotEmpty()) Outcome(Severity.INFO, "疑似隐藏应用列表模块：${hit.joinToString()}")
            else Outcome(Severity.PASS, "未枚举到 HMA 类模块")
        }, "更新 HMA / HMA-OSS 至最新版；如被检，用 Scene-Port-Hider-by-eBPF 或官方 Scene 更新。"),

        // ===== 二、挂载 / 文件系统 =====
        Check("debug_ramdisk 挂载", "挂载 / 文件系统", "/proc/mounts 是否含 /debug_ramdisk", false, {
            val m = readFile("/proc/mounts")
            if (m.contains("/debug_ramdisk")) Outcome(Severity.FAIL, "发现 /debug_ramdisk 挂载")
            else Outcome(Severity.PASS, "无 /debug_ramdisk 挂载")
        }, "执行 `su -c umount /debug_ramdisk`。部分设备存在误报（3.4 版本已修复部分），可先重启复测。"),

        Check("/system 存在挂载行为", "挂载 / 文件系统", "mountinfo 中 /system 是否被模块挂载改写", false, {
            val mi = readFile("/proc/self/mountinfo")
            // 粗略：/system 由非系统设备（如 overlay / tmpfs / 模块镜像）挂载即可疑
            val lines = mi.lines().filter { it.contains(" /system ") }
            val suspicious = lines.any { it.contains("overlay") || it.contains("tmpfs") || it.contains("/data/adb/modules") || it.contains("/debug_ramdisk") }
            if (suspicious) Outcome(Severity.WARN, "检测到 /system 异常挂载来源")
            else Outcome(Severity.PASS, "/system 挂载来源正常")
        }, "排查挂载类模块，更换元模块（如 Hybrid-Mount）或更新 root 管理器重新修补；确定某模块导致则卸载该模块。"),

        Check("挂载间隙", "挂载 / 文件系统", "mountinfo peer-group 表是否出现跳跃", false, {
            val mi = readFile("/proc/self/mountinfo")
            val groups = mi.lines().mapNotNull { l ->
                // 第 4 列是 peer-group / master:X，取 share:X 的 X
                val f = l.split(" ")
                val sg = f.getOrNull(3) ?: ""
                if (sg.startsWith("shared:")) sg.removePrefix("shared:").toIntOrNull() else null
            }
            val sorted = groups.distinct().sorted()
            val gaps = sorted.zipWithNext().count { it.second - it.first > 1 }
            if (gaps > 0) Outcome(Severity.WARN, "peer-group 表检测到 $gaps 处不连续")
            else Outcome(Severity.PASS, "peer-group 表连续")
        }, "Magisk 系：使用 Magisk Alpha；Kernel/APatch 系：更换「元模块」或更新 ROOT 管理器；检查绑定挂载模块，或换 Hybrid-Mount 后重测。"),

        Check("挂载视图不一致 (mountinfo drift)", "挂载 / 文件系统", "极早 mountinfo 快照与运行期是否不一致", false, {
            // 仅做一次快照比对：若 /proc/self/mountinfo 与 /proc/mounts 行数差异过大，提示可能漂移
            val a = readFile("/proc/self/mountinfo").lines().count { it.isNotBlank() }
            val b = readFile("/proc/mounts").lines().count { it.isNotBlank() }
            if (a > 0 && b > 0 && kotlin.math.abs(a - b) > a / 3)
                Outcome(Severity.WARN, "mountinfo=$a 行 / mounts=$b 行，差异偏大")
            else Outcome(Severity.PASS, "mountinfo($a) 与 mounts($b) 基本一致")
        }, "小米设备开机后等高占用时段打开易报；刚开机命中多半是快照误差：**开机后等 20 秒~5 分钟再测**。"),

        Check("/data/local/tmp 元数据", "挂载 / 文件系统", "tmp 目录属组/权限/所有权是否被改", true, {
            if (SuExec.activeSuPath().isEmpty() && SuExec.run("id -u", 4).out.lineSequence().any { it.trim() == "0" }.not())
                return@Check Outcome(Severity.INFO, "su 不可用，tmp 元数据需 root 才能完整读取")
            val owner = su("ls -ld /data/local/tmp").trim()
            if (owner.isBlank()) return@Check Outcome(Severity.INFO, "su 不可用，tmp 元数据需 root 才能完整读取")
            val bad = mutableListOf<String>()
            if (!owner.contains("shell shell")) bad += "属组非 shell"
            if (!Regex("""drwx.{1,8}--x""").containsMatchIn(owner)) bad += "权限非 771 默认"
            if (bad.isEmpty()) Outcome(Severity.PASS, owner.split(" ").take(4).joinToString(" "))
            else Outcome(Severity.FAIL, bad.joinToString("；") + " | " + owner.split(" ").take(4).joinToString(" "))
        }, "属组异常：`su -c chown shell:shell /data/local/tmp`；权限被改：`su -c chmod 771 /data/local/tmp`；inode 异常偏高用 Inode-Hijacker 或内核级隐藏伪装 < 1000；或 `su -c rm -rf /data/local/tmp` 后重启再逐项修复。"),

        // ===== 三、进程 / 命名空间 =====
        Check("设备为模拟器", "进程 / 命名空间", "是否命中模拟器特征", false, {
            val goldfish = fileExists("/dev/goldfish_pipe") || fileExists("/dev/qemu_pipe")
            val brand = getprop("ro.product.brand").lowercase()
            val model = getprop("ro.product.model").lowercase()
            val emulator = goldfish || "goldfish" in brand || "emulator" in model || "sdk" in model
            if (emulator) Outcome(Severity.FAIL, "命中模拟器特征")
            else Outcome(Severity.PASS, "未见模拟器特征")
        }, "卸载重装检测器；避免「未插 SIM 卡 + 满电 + 充电」状态下测试（这些会放大模拟器判定）。"),

        Check("多开 / 容器环境", "进程 / 命名空间", "/proc/self/cgroup 是否命中多开/沙盒", false, {
            val cg = readFile("/proc/self/cgroup")
            val hit = Regex("""^0::/uid_\d+/pid_\d+$""", RegexOption.MULTILINE).containsMatchIn(cg)
            if (hit) Outcome(Severity.WARN, "cgroup 命中多开/容器特征")
            else Outcome(Severity.PASS, "cgroup 正常")
        }, "卸载重装春秋检测；**不要对春秋检测使用应用双开**。"),

        // ===== 七、Keystore / TEE =====
        Check("TrickyStore / 密钥模块痕迹", "Keystore / TEE", "/data/adb/tricky_store 是否配置（需 root）", true, {
            if (SuExec.activeSuPath().isEmpty() && SuExec.run("id -u", 4).out.lineSequence().any { it.trim() == "0" }.not())
                return@Check Outcome(Severity.INFO, "su 不可用，TEE/密钥状态需 root 才能完整读取")
            val ts = su("ls -l /data/adb/tricky_store/ 2>/dev/null; cat /data/adb/tricky_store/keybox.xml 2>/dev/null | head -c 200").trim()
            if (ts.isBlank()) return@Check Outcome(Severity.INFO, "未检测到 TrickyStore 配置")
            val aosp = ts.contains("AOSP", true) || ts.contains("test", true)
            if (aosp) Outcome(Severity.WARN, "keybox 疑似 AOSP 测试根（非厂商/Google 正式根）")
            else Outcome(Severity.INFO, "已配置 TrickyStore（正常，但会被检测器识别为「发现 TrickyStore」）")
        }, "AOSP 测试根：更换 `/data/adb/tricky_store/keybox.xml` 为有效 keybox，或刷 TS 插件重启后 WebUI 配置。证书已被吊销(CRL)：同样更换 keybox.xml。"),

        Check("TEE 环境可信度（attestation）", "Keystore / TEE", "TEE 背书是否可信（attestation 级别 + 环境是否被 root 修改）", false, {
            val lvl = attestationSecurityLevel()
            val pm = sh("/system/bin/pm", "list", "packages")
            val rootMgr = listOf(
                "com.sukisu.ultra", "me.weishu.kernelsu", "com.topjohnwu.magisk",
                "com.rifsxd.ksunext", "me.yuki.fina", "com.anatdx.yukisu", "io.github.kubuko.kcfm"
            ).any { pm.contains("package:$it") }
            val level = when (lvl) { 2 -> "StrongBox"; 1 -> "TEE"; 0 -> "Software"; else -> "未知" }
            when {
                // 与春秋口径一致：环境里装了 root 管理器（系统已被修改），TEE 背书即判不可信
                rootMgr -> Outcome(Severity.FAIL, "attestation=$level，但环境存在 root 管理器 → TEE 不可信（环境已被修改）")
                lvl == 0 -> Outcome(Severity.FAIL, "attestation=Software（仅软件背书，TEE 不可信）")
                lvl == null -> Outcome(Severity.INFO, "无法读取 attestation security level（设备/ROM 不支持密钥证明）")
                else -> Outcome(Severity.PASS, "attestation=$level，未见 root 管理器")
            }
        }, "TEE 不可信的判定（同春秋）：环境里存在 root 管理器 / 只提供软件级 attestation / bootloader 已解锁 / keybox 被替换。真解锁设备用密钥模块 + TS 插件把 VerifiedBootHash/回锁状态喂给 attestation，或按《反检测》章节整体处理。"),

        // ===== 五、注入 / 框架（本 App 进程自身 maps / status 即可取证）=====
        Check("Zygisk 注入痕迹", "注入 / 框架", "本进程 maps 是否出现 zygisk / libzygisk（及模块目录扫描）", false, {
            val maps = readMaps().lowercase()
            val inMaps = "zygisk" in maps || "libzygisk" in maps
            // root 下再扫模块目录，确认是否有 zygisk 模块被加载
            val modScan = if (hasSu()) {
                val r = su("ls /data/adb/modules 2>/dev/null; ls /data/adb/modules_update 2>/dev/null")
                if (r.contains("zygisk", true)) "；模块目录含 zygisk 模块" else ""
            } else ""
            if (inMaps) Outcome(Severity.FAIL, "maps 命中 zygisk 注入痕迹$modScan")
            else if (modScan.isNotEmpty()) Outcome(Severity.WARN, "本进程未注入，但$modScan（检测器若未进排除名单仍可能命中）")
            else Outcome(Severity.PASS, "maps 未发现 zygisk 注入痕迹")
        }, "若被检测器命中 zygisk：把检测器加入 Magisk/KSU 的排除列表（DenyList / 隐藏名单）；或关闭 Magisk 自带 Zygisk，改用 ZygiskNext / ReZygisk / NeoZygisk 等更隐蔽实现。"),

        Check("可疑库注入", "注入 / 框架", "本进程 maps 是否挂载 frida / hook / lsposed 等注入库", false, {
            val maps = readMaps().lowercase()
            val sig = listOf("frida", "sandhook", "whale", "substrate", "xhook", "libinline",
                "lsplant", "dreamland", "pine", "yxposed", "nativehook", "libmagisk")
            val hit = sig.filter { maps.contains(it) }
            if (hit.isNotEmpty()) Outcome(Severity.FAIL, "maps 命中可疑库：${hit.joinToString()}")
            else Outcome(Severity.PASS, "maps 未发现常见注入库")
        }, "清理 `/data/local/tmp/` 下 hook 残留、卸载可疑 xp/注入模块后重启复扫；确认是否由某模块引入。"),

        Check("Seccomp 过滤状态", "注入 / 框架", "本进程 Seccomp 字段（0=未过滤）", false, {
            val seccomp = statusField("Seccomp")
            when (seccomp) {
                "2" -> Outcome(Severity.PASS, "Seccomp=2（严格过滤已生效）")
                "1" -> Outcome(Severity.INFO, "Seccomp=1（仅过滤，非严格）")
                else -> Outcome(Severity.WARN, "Seccomp=$seccomp（未启用过滤；检测器可能据此判断未被 hook 框架保护）")
            }
        }, "加载 SELinux_Hook.kpm / 特定管理器 commit 修复；或确保注入框架正确应用 seccomp 过滤。"),

        Check("侧信道测量（启发式）", "侧信道（启发式）", "忙等期间上下文切换的真实测量值", false, {
            Outcome(Severity.INFO, sideChannelProbe())
        }, "纯计时/页驻留侧信道无法在普通 App 里忠实复现，本项只做真实测量并标记启发式：若数值异常偏高，可更新/降级 root 管理器（KSU 重新修补；APatch 嵌入 NoHello.kpm + 排除列表）。"),

        // ===== 六、进程 / Zygote =====
        Check("Zygote GID 3009 (AID_READPROC)", "进程 / 命名空间", "本进程 Groups 是否含 3009（Zygote 异常特征）", false, {
            val groups = statusField("Groups")
            val has = groups.split("\\s+".toRegex()).any { it.trim() == "3009" }
            if (has) Outcome(Severity.WARN, "Groups 含 3009：$groups")
            else Outcome(Severity.PASS, "Groups 未含 3009：$groups")
        }, "该特征指向 Zygote 被异常提权/注入；将检测器纳入隐藏名单，并排查注入类模块。"),

        // ===== 七、内核 =====
        Check("内核版本欺骗", "内核", "uname -r / /proc/version / 属性 三路是否一致", false, {
            val u = unameR()
            val pv = readFile("/proc/version").trim()
            val pvVer = pv.split("\\s+".toRegex()).getOrNull(2) ?: ""
            val prop = getprop("ro.kernel.version").ifBlank { getprop("ro.boot.kernel.version") }
            val mismatch = mutableListOf<String>()
            if (u.isNotBlank() && pvVer.isNotBlank() && u != pvVer) mismatch += "uname($u)≠/proc/version($pvVer)"
            if (prop.isNotBlank() && u.isNotBlank()) {
                val base = prop.split("-").firstOrNull() ?: prop
                if (!u.startsWith(base) && !base.startsWith(u.take(3))) mismatch += "属性($prop)与uname($u)不符"
            }
            if (mismatch.isNotEmpty()) Outcome(Severity.WARN, mismatch.joinToString("；"))
            else Outcome(Severity.PASS, "三路一致：uname=$u / proc=$pvVer / prop=$prop")
        }, "用 Native detector 拿真实内核版本；若确需伪装，用内核级模块（如 SUSFS 的 spoof 能力）统一三路版本号后重启。"),

        // ===== 八、Root 特征（部分需 root 才能看全）=====
        Check("SU 二进制特征", "Root 特征", "常见 su 二进制路径是否存在", false, {
            val hit = SU_PATHS.filter { fileExists(it) }
            val extra = if (hasSu()) {
                su("which su 2>/dev/null; ls -l /data/adb/magisk/busybox 2>/dev/null")
                    .lines().map { it.trim() }.filter { it.isNotBlank() }
            } else emptyList()
            val extraStr = if (extra.isNotEmpty()) " root发现=${extra.joinToString()}" else ""
            if (hit.isNotEmpty() || extra.isNotEmpty())
                Outcome(Severity.FAIL, "命中路径=${hit.joinToString()}$extraStr")
            else Outcome(Severity.PASS, "未找到常见 su 二进制")
        }, "用密钥模块 / TS 插件把 su 二进制对检测器隐藏（KSU 的 hide 机制、Magisk 的 MagiskHide 替代品）；或在排除列表里把检测器排除，使其看不到 su。"),

        Check("Evil Service / tmp 残留", "Root 特征", "/data/local/tmp 下 sandhook/pumpcin/shamiko/suidaemon 残留", true, {
            if (!hasSu()) return@Check Outcome(Severity.INFO, "su 不可用，tmp 残留需 root 才能完整枚举")
            val r = su("ls -a /data/local/tmp 2>/dev/null")
            if (r.isBlank()) return@Check Outcome(Severity.INFO, "su 不可用，tmp 残留需 root 才能完整枚举")
            val sig = listOf("sandhook", "pumpcin", "shamiko", "suidaemon", "magisk", "ksu", "hook")
            val hit = sig.filter { r.contains(it, true) }
            if (hit.isNotEmpty()) Outcome(Severity.FAIL, "tmp 命中：${hit.joinToString()}")
            else Outcome(Severity.PASS, "tmp 未发现已知残留")
        }, "清理 `/data/local/tmp/` 下上述残留文件；重启后复扫。"),

        Check("KernelSU / SukiSU 特征", "Root 特征", "内核版本串 / 属性 / 模块目录是否暴露 KSU", false, {
            val kv = sh("/system/bin/uname", "-a").lowercase()
            val prop = getprop("ro.ksu.version").ifBlank { getprop("ro.sukisu.version") }
            val dirHit = if (hasSu()) {
                su("ls -d /data/adb/ksu /data/adb/sukisu /data/adb/ksunext 2>/dev/null")
                    .lines().map { it.trim() }.filter { it.isNotBlank() }
            } else emptyList()
            val sig = listOf("ksu", "kernelsu", "sukisu")
            val inKernel = sig.any { kv.contains(it) }
            if (prop.isNotBlank() || inKernel || dirHit.isNotEmpty())
                Outcome(Severity.FAIL, "暴露 KSU：prop=$prop kernel含KSU=$inKernel dir=${dirHit.joinToString()}")
            else Outcome(Severity.PASS, "未暴露 KSU/SukiSU 特征")
        }, "KSU/SukiSU：用管理器的「隐藏 KSU」能力（如 susfs 的 hide 或 ksu 自身的 hide 模块）把 ksu 目录、属性、内核串对检测器隐藏，并把检测器加入排除列表。"),

        // ===== 九、包管理 =====
        Check("GMS 被屏蔽", "包管理 / 应用可见性", "Google 移动服务（GMS）是否缺失/被屏蔽", false, {
            val pm = sh("/system/bin/pm", "list", "packages")
            val gms = listOf("com.google.android.gms", "com.google.android.gsf", "com.android.vending")
            val hit = gms.filter { pm.contains("package:${it}") }
            if (hit.size < gms.size) Outcome(Severity.WARN, "GMS 组件缺失：${gms.minus(hit).joinToString()}（检测器可能判定 GMS 被屏蔽）")
            else Outcome(Severity.PASS, "GMS 组件齐全")
        }, "若被检测器判定 GMS 被屏蔽：确保未用「移除 GMS / 精简系统」类模块；或在排除列表里放行检测器访问 GMS。"),

        // ===== 十、刷机 / Root 文件痕迹 =====
        Check("刷机 / Root 文件痕迹", "挂载 / 文件系统", "magisk / busybox / TWRP 文件是否存在", false, {
            val files = listOf(
                "/system/xbin/busybox", "/sbin/busybox", "/system/sbin/busybox", "/su/bin/su",
                "/data/adb/magisk/busybox", "/data/adb/magisk/magisk", "/data/adb/magisk/magisk64",
                "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox",
                "/sdcard/TWRP", "/sdcard/twrp", "/data/media/TWRP"
            )
            val hit = files.filter { fileExists(it) }
            if (hit.isNotEmpty()) Outcome(Severity.FAIL, "命中：${hit.joinToString()}")
            else Outcome(Severity.PASS, "未发现 magisk/busybox/TWRP 文件")
        }, "删除对应文件 / 目录；root 关闭后 magisk/busybox 应自然消失；TWRP 备份目录建议移出 /sdcard 或改名。")
    )

    /** 实跑所有检测；非 root 项始终执行，root 项在 su 不可用时降级为 INFO */
    fun runAll(): List<Result> = checks.map { c ->
        val o = runCatching { c.run() }.getOrElse { Outcome(Severity.SKIP, "执行异常：${it.message}") }
        Result(c, o.severity, o.evidence)
    }

    /** 分类汇总 */
    fun summarize(results: List<Result>): String {
        val fail = results.count { it.severity == Severity.FAIL }
        val warn = results.count { it.severity == Severity.WARN }
        val info = results.count { it.severity == Severity.INFO }
        val pass = results.count { it.severity == Severity.PASS }
        val skip = results.count { it.severity == Severity.SKIP }
        val sb = StringBuilder("通过 $pass · 警告 $warn · 不通过 $fail · 提示 $info")
        if (skip > 0) sb.append(" · 跳过 $skip")
        return sb.toString()
    }
}
