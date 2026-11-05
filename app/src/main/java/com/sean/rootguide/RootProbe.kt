package com.sean.rootguide

import java.io.File

/** 本 App 与 su 的关系。三态必须分开：把「没授权」说成「没 root」是最容易误导人的一种错。 */
enum class SuState {
    /** su 拿到了数据 */
    GRANTED,

    /** 系统里有 su（或至少装了管理器），但本 App 没拿到权限 */
    DENIED,

    /** 既没有 su 可执行文件，也没有可识别的管理器 */
    ABSENT
}

data class ProbeResult(
    val text: String,
    /** 一行状态，首页只显示这一句；详细证据放 [text] 里收起 */
    val summary: String,
    /** 是否真的拿到了 root。false 时 UI 直接转「手动选择」，不拿分支结论糊弄人 */
    val hasRoot: Boolean,
    /** 能确定分支时为分支 id；否则为 null，UI 必须要求用户手动选择 */
    val suggestedBranchId: String?,
    val needManualPick: Boolean,
    /** 给 UI 的重点提示，空则没有 */
    val alert: String,
    val confidence: BranchDetect.Confidence = BranchDetect.Confidence.NONE,
    val facts: BranchDetect.Facts = BranchDetect.Facts(),
    val candidates: List<String> = emptyList(),
    /** 接下来要改的设置项（按优先级），用于「引导后续设置」 */
    val guidance: List<String> = emptyList(),
    /** 三态：已授权 / 有 root 但本 App 未授权 / 真没有 root */
    val suState: SuState = SuState.ABSENT,
    /** 找到的 su 可执行文件路径；没找到为空 */
    val suPath: String = "",
    /** 引导区标题：状态不同，做的事完全不同 */
    val guidanceTitle: String = "拿到 root 之前要做的事"
)

/**
 * 本 App 真实包名由 MainActivity.onCreate 用 `packageName` 注入，不再写死字符串。
 * 之前这里写死成旧包名 `com.sean.rootguide`（10-29 遗留、已停用），诊断会引导用户
 * 去给一个根本没在运行的包授权；实际 applicationId 是 `com.sean.magicrootguide`。
 */
object RootProbe {

    /** 供诊断文案使用；由 Activity 注入，避免与 build.gradle.kts 的 applicationId 不同步 */
    @Volatile var appPackage: String = "com.sean.magicrootguide"

    /**
     * 常见 su 落地位置。**不需要 root 就能 stat**，所以哪怕本 App 没被授权，
     * 也能分清「系统里根本没有 su」和「有 su 但不让我用」。
     */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/product/bin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/su",
        "/data/adb/ap/bin/su"
    )

    /**
     * 探测 root 与管理器分支。
     *
     * [installedManagerPackages] 由 UI 用 PackageManager 无权限查得（需要 manifest 的 <queries>），
     * 用于在 su 拿不到权限时区分「系统没 root」和「系统已 root 但本 App 未被授权」，
     * 并尽量给出管理器对应的分支候选。
     */
    fun probe(installedManagerPackages: List<String> = emptyList()): ProbeResult {
        // 用户可能刚在管理器里改完授权；每次检测都重新找一次可用的 su
        SuExec.reset()
        val suPath = findSu()
        val res = SuExec.run(BranchDetect.COLLECT, 40)
        val out = res.out
        val v = if (out.isBlank()) emptyMap() else parse(out)

        // 解析不出 fam 说明 su 根本没跑起来（或输出被别的东西污染）。
        // 这时候不能把「空结果」当成「没 root」，得回到三态判定。
        if (v["fam"] == null) {
            return notGranted(installedManagerPackages, res, suPath)
        }

        val guess = BranchDetect.detect(v)
        val branch = if (guess.confidence != BranchDetect.Confidence.NONE) Branches.byId(guess.branchId) else null

        val sb = StringBuilder()
        var alert = ""

        // ---- 体系 ----
        val fam = v["fam"]
        sb.append(
            "体系：${
                when (fam) {
                    "ksu" -> "KernelSU 系（/data/adb/ksu）"
                    "magisk" -> "Magisk 系（/data/adb/magisk）"
                    "apatch" -> "APatch 系（/data/adb/ap）"
                    else -> "未识别（没有 /data/adb/{ksu,magisk,ap}）"
                }
            }\n"
        )

        // ---- 具体分支 ----
        when (guess.confidence) {
            BranchDetect.Confidence.HIGH -> {
                sb.append("分支：${branch!!.label}  ✅ 自动识别\n")
                guess.evidences.forEach { sb.append("  · $it\n") }
            }
            BranchDetect.Confidence.MEDIUM -> {
                sb.append("分支：可能是 ${branch!!.label}  ⚠ 需要你确认\n")
                guess.evidences.forEach { sb.append("  · $it\n") }
                alert = "只根据管理器包名推断为「${branch.label}」。不同分支的包名可能被复用" +
                        "（例如部分 FinaSU 构建沿用 ReSukiSU 的包名），请核对后再生成清单。"
            }
            BranchDetect.Confidence.NONE -> {
                sb.append("分支：无法确定  ⚠ 需要手动选择\n")
                sb.append("  ${guess.reason}\n")
                alert = "无法自动确定 su 管理器分支，请在下方手动选择。" +
                        "不同分支要装的模块差别很大，选错会导致模块失效。"
            }
        }

        // ---- 运行时事实 ----
        if (guess.facts.mountProvider.isNotBlank()) {
            sb.append("挂载引擎：${guess.facts.mountProvider}\n")
        }
        if (guess.facts.moduleIds.isNotEmpty()) {
            sb.append("已装模块（${guess.facts.moduleIds.size}）：${guess.facts.moduleIds.joinToString("、")}\n")
        }
        if (guess.facts.managerPackages.isNotEmpty()) {
            sb.append("管理器包名：${guess.facts.managerPackages.joinToString("、")}\n")
            sb.append("  对应应用：${guess.facts.managerPackages.joinToString("、") { managerNameOf(it) }}\n")
        }

        val vbmeta = v["vbmeta"] ?: ""
        val vboot = v["vboot"] ?: ""
        if (vbmeta.isNotBlank() || vboot.isNotBlank()) {
            sb.append("bootloader：vbmeta=${vbmeta.ifBlank { "?" }} / verifiedbootstate=${vboot.ifBlank { "?" }}\n")
            if (vbmeta == "locked" && vboot == "green" && v["persistent"] == "yes") {
                sb.append("  → 显示已锁但校验绿 + 持久化 ksud：这是假回锁，实际是永久 root\n")
            }
        }

        val kmi = v["kmi"].orEmpty().ifBlank { "?" }
        val device = v["device"].orEmpty().ifBlank { "?" }
        val androidVer = v["android"].orEmpty().ifBlank { "?" }
        val overlay = v["overlay"].orEmpty()
        sb.append("设备：$device / Android $androidVer / 内核 $kmi\n")
        sb.append("模块挂载：overlay 挂载点 ${overlay.ifBlank { "?" }} 个")
        sb.append(if (overlay == "0") "（当前没有模块在挂载，属正常：没装模块或未重启）\n" else "\n")
        sb.append("su：已授权（${suPath.orEmpty().ifBlank { "路径未识别" }}）")

        val modCount = guess.facts.moduleIds.size
        val overlays = overlay.ifBlank { "0" }
        val summary = buildString {
            append("已拿到 root")
            if (guess.confidence == BranchDetect.Confidence.HIGH && branch != null) {
                append(" · ${branch.label}")
            }
            append(" · 已装 $modCount 个模块 · overlay 挂载 $overlays 处")
        }

        return ProbeResult(
            text = sb.toString().trim(),
            summary = summary,
            hasRoot = true,
            suggestedBranchId = guess.branchId,
            needManualPick = guess.confidence != BranchDetect.Confidence.HIGH,
            alert = alert,
            confidence = guess.confidence,
            facts = guess.facts,
            candidates = guess.candidates,
            guidance = guidanceOf(branch, guess.facts),
            suState = SuState.GRANTED,
            suPath = suPath.orEmpty(),
            guidanceTitle = "这个分支先改这几项"
        )
    }

    /** 根据「分支 + 运行时事实」推导接下来必须改的设置项 */
    fun guidanceOf(branch: Branch?, f: BranchDetect.Facts): List<String> {
        val g = ArrayList<String>()

        if (f.managerPackages.isEmpty()) {
            g.add("没认出管理器包名（可能是不在识别表里的构建）。管理器装在哪台机器上、叫什么名字，先确认一遍")
        }

        // 这里只写「这台机器实测出来的差异」；分支自身的第一步（Magisk 开 Zygisk、APatch 设 SuperKey）
        // 已经写在 Branch.note 里，重复说一遍只会让两块内容打架。

        if (branch?.id == "finasu" || branch?.id == "yukisu") {
            g.add("授权是白名单模型、不弹窗：在管理器里把本 App（$appPackage）加进白名单，否则 su 返回空")
        }

        if (f.hasBuiltinZygisk) {
            val external = f.moduleIds.filter { it.contains("zygisk", ignoreCase = true) }
            if (external.isNotEmpty()) {
                g.add("内置 FolkZygisk 与外挂 ${external.joinToString("、")} 同时存在：只保留一套，双开会抢 zygote 注入")
            } else {
                g.add("已内置 Zygisk 引擎（FolkZygisk）：不要装 Zygisk Next / ReZygisk")
            }
        }

        if (f.mountProvider == "builtin") {
            g.add("挂载引擎是内置的：不要装元模块，装了会双挂载")
        }
        if (f.mountMode.isNotBlank()) {
            g.add("挂载模式 = ${f.mountMode}：保持默认，别随意改")
        }
        if (branch?.needMetaModule == true) {
            g.add("该分支必须先装元模块，否则普通模块全部静默失效")
        }
        if (branch?.supportsSusfs == true) {
            g.add("支持 SUSFS，但内核必须已打补丁；没打补丁刷 SUSFS 模块会破坏 key attestation")
        }
        return g
    }

    /**
     * 没拿到 su 数据的兜底。这里最容易出错的一句话是「没检测到 root 环境」——
     * 系统明明 root 了、只是本 App 没被授权，说成没 root 会让人去重刷系统。
     *
     * 判定顺序：
     *  1. 有 su 可执行文件 → 系统有 root，本 App 未授权；
     *  2. 没有 su 但装了已知管理器 → 系统有 root（su 在别的位置或 PATH 里没它），本 App 未授权；
     *  3. 两者都没有 → 才是真的没有 root。
     */
    private fun notGranted(
        installedManagerPackages: List<String>,
        res: SuExec.Result,
        suPath: String?
    ): ProbeResult {
        val pkgs = installedManagerPackages.filter { BranchDetect.isManagerPkg(it) }
        val guess = BranchDetect.detectByManagerPackages(pkgs)
        val branch = if (guess.confidence != BranchDetect.Confidence.NONE)
            Branches.byId(guess.branchId) else null

        // KernelSU 的 su 是内核 sucompat 虚拟出来的，Java 的 File.exists() 常常 stat 不到，
        // 所以「常见路径没找到」不等于「系统没有 su」。以 SuExec 实际 exec 探测结果为准。
        val probedSu = SuExec.activeSuPath()
        val hasSuBinary = suPath != null || probedSu.isNotEmpty()
        val state = if (hasSuBinary || pkgs.isNotEmpty()) SuState.DENIED else SuState.ABSENT

        val failure = when {
            probedSu.isNotEmpty() -> "找到可用 su，但采集脚本没返回 RG| 数据（脚本兼容问题，不是授权问题）"
            res.exitCode == -1 -> "su 调用超时"
            res.notStarted -> "所有 su 候选都起不来（见下方逐项探测）"
            res.out.isBlank() && res.error.isBlank() -> "su 起了但没有任何输出"
            else -> "su 返回非零退出码"
        }
        val suLog = SuExec.lastProbeLog

        // KernelSU 系可以在启动配置里把「传统 SU 命令支持」锁成「始终禁用」：
        // 此时内核 root 与 App 授权都正常，但系统里不存在 su 命令，任何 App 都提不了权。
        // 真机（Magic8 Pro / SukiSU-Ultra，2026-11-05）就是这个配置，必须直说，别让用户反复去授权。
        val ksuStyle = pkgs.any {
            it == "com.sukisu.ultra" || it == "me.weishu.kernelsu" ||
                it == "com.rifsxd.ksunext" || it == "me.yuki.fina" ||
                it == "com.anatdx.yukisu"
        }
        val suCompatDisabled = ksuStyle && probedSu.isEmpty() && res.notStarted

        // ---- 诊断：把 su 到底怎么失败的写清楚，下次不用再猜 ----
        val diag = buildString {
            append("\n\n— 诊断 —\n")
            append("本 App 包名：$appPackage\n")
            append("su 可执行文件：")
            append(when {
                probedSu.isNotEmpty() -> "$probedSu（已确认可拿到 uid=0）"
                suPath != null -> "$suPath（stat 到了，但执行没成功）"
                else -> "常见路径 stat 不到（KernelSU 的 su 由内核 sucompat 虚拟，属正常）"
            })
            append("\n")
            append("已装管理器：")
            append(if (pkgs.isEmpty()) "识别表里一个都没命中" else pkgs.joinToString("、") { "$it（${managerNameOf(it)}）" })
            append("\n")
            append("su 调用：exit=${res.exitCode} · $failure")
            if (res.out.isNotBlank()) append("，stdout=${res.out.trim().take(120)}")
            if (res.error.isNotBlank()) append("，stderr=${res.error.trim().take(200)}")
            if (suLog.isNotBlank()) {
                append("\n\nsu 候选逐项探测（各执行 `-c id`）：\n")
                append(suLog.trim())
            }
            val envPath = System.getenv("PATH") ?: "?"
            append("\nApp PATH：$envPath")
        }

        if (state == SuState.ABSENT) {
            return ProbeResult(
                text = "没有拿到 root，也没有 su 可执行文件、没有可识别的管理器。\n" +
                        "· 本 App 包名：$appPackage\n" +
                        "· 若这台机器确实刷过 root：确认管理器已安装、能正常打开，再回来重新检测\n" +
                        "· FinaSU / YukiSU：白名单模型不弹窗，要先把本 App 加进白名单" +
                        diag,
                summary = "没检测到 root 环境（无 su、无管理器）",
                hasRoot = false,
                suggestedBranchId = null,
                needManualPick = true,
                alert = "没有找到 su，也没有已知的 root 管理器。若你确信已经刷过 root，" +
                        "先打开管理器确认它工作正常，再点「重新检测」。",
                guidance = listOf(
                    "确认系统真的刷了 root：装好管理器（Magisk / KernelSU 系 / APatch）后 su 才会存在",
                    "若在用 FinaSU / YukiSU：授权是白名单模型，不弹窗，要手动把 $appPackage 加进白名单",
                    "若在用临时 root：先完成提权（重启后要重新提权）再回来点检测"
                ),
                suState = state,
                suPath = "",
                guidanceTitle = "拿到 root 之前要做的事"
            )
        }

        // ---- 有 su（或至少有管理器），但本 App 没拿到权限 ----
        val managerName = branch?.label ?: pkgs.firstOrNull()?.let { managerNameOf(it) }
        // 管理器里明明已授权却调不起 su 时，不能再说成「未获授权」——
        // 那是 su 路径 / sucompat 的问题，用户照着去反复授权只会白忙。
        val tail = if (probedSu.isNotEmpty()) "但状态采集没返回数据"
                   else "但调不起 su（详见检测详情）"
        val summary = when {
            managerName != null && branch != null -> "检测到 $managerName，$tail"
            managerName != null -> "检测到管理器 $managerName，$tail"
            else -> "系统里有 root 环境，$tail"
        }

        val detail = buildString {
            append("系统已 root：")
            if (probedSu.isNotEmpty()) append("已确认 $probedSu 能拿到 uid=0")
            else if (suPath != null) append("stat 到 su（$suPath），但执行没成功")
            else append("装了管理器，su 由内核 sucompat 虚拟（常见路径 stat 不到属正常）")
            append("\n")
            if (probedSu.isNotEmpty()) {
                append("· su 已能提权，但采集脚本没有返回 RG| 数据 —— 这是脚本兼容问题，不是授权问题。\n")
            } else {
                append("· 本 App（$appPackage）目前调不起 su，所以读不到 /data/adb 与 ksud。\n")
                append("· 若在管理器里已给本 App 勾选「超级用户」，请看下方逐项探测：多半是 su 兼容/路径问题，不是没授权。\n")
            }
            if (suCompatDisabled) {
                append("· 重点：管理器若把「设置 → 传统 SU 命令支持」设为「始终禁用」（启动配置强制指定），系统里就不会有 su 命令 —— 这是启动配置，与管理器的「超级用户」勾选无关，本 App 无法通过反复授权解决。\n")
            }
            if (branch != null) {
                append("· 管理器对应分支：${branch.label}（授权后复测可确认）\n")
            } else if (pkgs.isNotEmpty()) {
                append("· ${guess.reason}\n")
            }
            append("· 包名只代表装的是哪个管理器，不代表内核分支；授权后重新检测会更准。")
            append(diag)
        }

        val guidance = ArrayList<String>()
        val whitelist = branch?.id == "finasu" || branch?.id == "yukisu" ||
                guess.candidates.contains("finasu") || guess.candidates.contains("yukisu")
        if (whitelist) {
            val name = managerName ?: "管理器"
            guidance.add("打开 $name → 找到「超级用户 / 授权」列表 → 把「MagicRoot Guide」设为允许")
            guidance.add("这类管理器是白名单模型：「不会弹窗」问你要不要授权，不手动加就永远返回空")
        } else {
            guidance.add("在管理器里给「MagicRoot Guide」授权：Magisk 会弹窗，KernelSU 系在「超级用户」列表里点允许")
        }
        guidance.add("授权后回到本页点「重新检测」，通过后会自动认出分支")
        if (suCompatDisabled) {
            guidance.add("SukiSU / KernelSU 系：到管理器「设置 → 传统 SU 命令支持」确认是否为「始终禁用」；它由内核/LKM 启动配置强制指定，需在启动配置里启用（会削弱 root 隐藏），否则本 App 读不到 /data/adb")
        }
        guidance.add("若管理器里已显示授权：看「查看检测详情」的逐项探测，确认是不是 su 兼容/路径问题（KernelSU 系的 su 由内核虚拟，不一定在 PATH 里）")
        guidance.add("仍不行就看「查看检测详情」里 su 的 exit code 与 stderr，照着那行错查")
        if (!hasSuBinary) {
            guidance.add("没找到 su 可执行文件：管理器里确认 su 已安装（有的版本要在设置里手动装一次）")
        }

        return ProbeResult(
            text = detail,
            summary = summary,
            hasRoot = false,
            suggestedBranchId = guess.branchId,
            needManualPick = guess.confidence != BranchDetect.Confidence.HIGH,
            alert = "检测到 root 环境，但本 App 目前调不起 su —— 不等于没 root。若管理器里已授权，请看「查看检测详情」的逐项探测结果。",
            confidence = guess.confidence,
            facts = guess.facts,
            candidates = guess.candidates,
            guidance = guidance,
            suState = state,
            suPath = suPath ?: "",
            guidanceTitle = if (probedSu.isNotEmpty()) "采集脚本异常" else "先让本 App 能调用 su"
        )
    }

    /** 管理器包名 → 用户看得懂的名字。查不到就退回包名本身 */
    fun managerNameOf(pkg: String): String = when (pkg) {
        "me.yuki.fina" -> "FinaSU"
        "com.resukisu.resukisu" -> "BakaSU / FinaSU（包名共用）"
        "me.weishu.kernelsu" -> "KernelSU"
        "com.rifsxd.ksunext" -> "KSU-Next"
        "com.sukisu.ultra" -> "SukiSU-Ultra"
        "com.anatdx.yukisu" -> "YukiSU"
        "com.lyravoid.finasu", "io.github.lyravoid.finasu" -> "FinaSU"
        "com.topjohnwu.magisk" -> "Magisk"
        "io.github.vvb2060.magisk" -> "Magisk Alpha"
        "com.rifsxd.kitsunemask" -> "Kitsune Mask"
        "me.bmax.apatch" -> "APatch"
        "me.bmax.folkpatch" -> "FolkPatch"
        else -> pkg
    }

    /** 不需要 root：stat 常见 su 路径，再退回 PATH 里找一遍 */
    private fun findSu(): String? {
        SU_CANDIDATES.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
            ?.let { return it }
        return runCatching {
            val p = ProcessBuilder("sh", "-c", "command -v su 2>/dev/null || which su 2>/dev/null").start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") }
        }.getOrNull()
    }

    /** 解析 RG|key|value 行 */
    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val idx = line.indexOf("RG|")
            if (idx >= 0) {
                val body = line.substring(idx + 3)
                val bar = body.indexOf('|')
                if (bar > 0) map[body.substring(0, bar).trim()] = body.substring(bar + 1).trim()
            }
        }
        return map
    }
}
