package com.sean.rootguide

/**
 * 从真机状态反推「用户实际用的是哪个 su 管理器分支」。
 *
 * 设计原则（改动前请务必理解，每一条都是踩过的坑）：
 *
 * 1. **只在证据确凿时下结论**。不同分支的 needMetaModule / needZygiskImpl 取值可能完全相反，
 *    猜错一个，生成的清单就会让用户去装不该装的东西（FinaSU 装元模块 = 双挂载，轻则模块失效
 *    重则 bootloop）。证据不足就返回 confidence=NONE，让 UI 强制手动选，而不是塞一个"最像"的。
 *
 * 2. **管理器包名不可靠，只能当次要证据**。实测反例（2026-10-29，荣耀 8E5）：
 *    内核是 FinaSU（/data/adb/ksu/bin/folkzygisk 存在、ksud 有 zygisk/sui/boot-hide 子命令），
 *    但机器上装的管理器包名是 `com.resukisu.resukisu` —— FinaSU 从 ReSukiSU 分叉时沿用了
 *    上游包名以保持协议兼容。**只按包名认分支，这台机器就会认错。**
 *
 * 3. **优先认「行为」而不是「名字」**。挂载引擎是内置还是外挂、有没有内置 Zygisk，
 *    这些直接决定"还要不要装元模块 / 要不要装 Zygisk 实现"，比分支名更贴近用户目的。
 *    所以即使 confidence=NONE，也要把 facts 带回去给 UI 用。
 *
 * 4. **判据只用管理器自己铺的文件**，不依赖用户装的模块（用户随时会卸）。
 *    finasu-* 这类模块名只作辅助证据，权重低。
 */
object BranchDetect {

    enum class Confidence { HIGH, MEDIUM, NONE }

    /** 与具体分支名字无关、但能直接指导「下一步装什么」的运行时事实 */
    data class Facts(
        val mountProvider: String = "",   // builtin / external / 空=未知
        val mountMode: String = "",       // automatic / mountify ...
        val hasBuiltinZygisk: Boolean = false,
        val hasSui: Boolean = false,
        val moduleIds: List<String> = emptyList(),
        val managerPackages: List<String> = emptyList(),
        // —— 状态检测四项（Tab2）——
        val is8E5: Boolean = false,       // 是否为荣耀 8E5 平台（hnubl 假回锁仅此平台支持）
        val susfsOn: Boolean = false,     // SUSFS 是否已开启
        val susfsEvidence: String = "",   // SUSFS 命中依据（诊断用，如「/proc/susfs_* ×9」）
        val bootId: String = "",          // 当前 boot_id，用于判断「是否发生过真重启」
        val bootReason: String = "",      // 最近一次启动原因（ro.boot.bootreason / sys.boot.reason）
        // —— 模块完整性 / 反向异常（Tab2 第 4 项）——
        val disabledModules: List<String> = emptyList(), // 带 disable 标记的模块目录（选中却不可用）
        val monitorPtraceCount: Int = 0,  // zygisk-ptrace64 进程数（>1 即互杀）
        val monitorDaemonCount: Int = 0,  // vectord / lspd 进程数（>1 即互杀）
        val frameworkFindings: List<FrameworkFinding> = emptyList(),
        val managerAppFindings: List<ManagerAppFinding> = emptyList(), // 框架管理器本体 App（装没装 / 有没有被停用）
        val conflicts: List<String> = emptyList(), // 反向异常：互斥 / 双开 / 多 monitor / 管理器本体缺失
        val zygiskEngine: String = "" // 解析出的 Zygisk 注入引擎：NeoZygisk / Zygisk Next / YukiZygisk / 内置 FolkZygisk
    )

    /**
     * 一个 Xposed / Zygisk 框架模块的检测结论。
     * [installed] 来自 /data/adb/modules 目录名；[disabled] 来自该目录下的 disable 标记。
     */
    data class FrameworkFinding(
        val key: String,        // 内部标识
        val name: String,       // 显示名
        val installed: Boolean,
        val disabled: Boolean,  // 有 disable 标记 = 反向异常（内核跳过该模块，UI 却显示已启用）
        val note: String = ""
    )

    /**
     * 框架「管理器本体 App」的安装/启用状态。
     *
     * 真机坑（2026-11-05，Magic8 Pro / Android 17）：模块装了、框架也成功注入，
     * 但 LSPosed 管理器 UI 包 `org.lsposed.manager` 没装（只装了无界面的 `org.lsposed.daemon`，
     * 且被 pm 停用），表现就是「点 LSPosed 图标/启动按钮进不去界面」——和框架是否正常完全无关。
     * 所以第 4 项完整性检测必须把「管理器本体」也纳入反向异常。
     */
    data class ManagerAppFinding(
        val framework: String,   // LSPosed / Vector
        val pkg: String,         // 包名
        val role: String,        // 管理器 UI / 守护连接器
        val installed: Boolean,
        val disabled: Boolean,   // pm disable（enabled=0）
        val note: String = ""
    )

    data class Guess(
        val branchId: String?,            // confidence!=NONE 时非空
        val confidence: Confidence,
        val family: Family?,
        val reason: String,               // 展示给用户的一句话依据
        val evidences: List<String>,      // 命中的具体判据，逐条展示
        val facts: Facts = Facts(),
        val candidates: List<String> = emptyList()  // 认不出时，让用户从这里选
    )

    /** 管理器包名 → 分支 id。注意：包名只代表「装的是哪个管理器」，不代表内核分支 */
    private val MANAGER_PKGS: Map<String, String> = linkedMapOf(
        "me.weishu.kernelsu" to "ksu_official",
        "com.rifsxd.ksunext" to "ksu_next",
        "com.sukisu.ultra" to "sukisu",
        "com.resukisu.resukisu" to "bakasu",
        "com.anatdx.yukisu" to "yukisu",
        // FinaSU 真机实测（2026-10-29，荣耀 8E5）：管理器包名就是 me.yuki.fina，
        // application-label = FinaSU，versionName v1.3.2，主界面复用 me.weishu.kernelsu.ui.MainActivity。
        // 早前「FinaSU 沿用 com.resukisu.resukisu」的说法来自另一份构建，不适用于这个包。
        "me.yuki.fina" to "finasu",
        "com.lyravoid.finasu" to "finasu",
        "io.github.lyravoid.finasu" to "finasu",
        "com.topjohnwu.magisk" to "magisk_official",
        "io.github.vvb2060.magisk" to "magisk_alpha",
        "com.rifsxd.kitsunemask" to "kitsune",
        "me.bmax.apatch" to "apatch",
        "me.bmax.folkpatch" to "folkpatch"
    )

    /**
     * 包名被多个分支共用时的候选集合。目前只有一例：
     * `com.resukisu.resukisu` 被 BakaSU（ReSukiSU 本体）与部分 FinaSU 构建共用 —— 分叉时沿用了上游包名。
     * 无权限时只看包名无法区分，只能并列候选，绝不能当成 BakaSU 下结论。
     *
     * 注意：8E5 上实测的 FinaSU（v1.3.2）用的是 `me.yuki.fina`，不落在这个歧义里；
     * 这条保留是为了覆盖沿用上游包名的那些构建。
     */
    private val AMBIGUOUS_PKGS: Map<String, List<String>> = mapOf(
        "com.resukisu.resukisu" to listOf("bakasu", "finasu")
    )

    /** 一个包名可能对应的所有分支 id（含歧义展开） */
    private fun candidatesFor(pkg: String): List<String> =
        AMBIGUOUS_PKGS[pkg] ?: listOfNotNull(MANAGER_PKGS[pkg])

    /**
     * 采集指纹。全部只读，且不依赖用户已装模块。
     * 输出的 key 是与 RootProbe 之间的契约，改动要两边同步。
     */
    val COLLECT: String = listOf(
        "p(){ echo \"RG|\$1|\$2\"; }",
        "p fam \"\$( if [ -d /data/adb/ksu ]; then echo ksu; elif [ -d /data/adb/magisk ]; then echo magisk; elif [ -d /data/adb/ap ]; then echo apatch; else echo unknown; fi )\"",
        "p ksudver \"\$(ksud -V 2>/dev/null | head -1)\"",
        "p magiskver \"\$(magisk -V 2>/dev/null | head -1)\"",
        // ksud 的子命令列表是分支之间最直观的差异：FinaSU 会挂上
        // zygisk(FolkZygisk) / sui / boot-hide / anykernel3 / uts-view / netisolate
        "p ksudhelp \"\$(ksud 2>&1 | tr '\\n' ';' | head -c 800)\"",
        "p folkzygisk \"\$([ -e /data/adb/ksu/bin/folkzygisk ] && echo yes || echo no)\"",
        "p folksusfs \"\$([ -d /data/adb/ksu/folksu-susfs ] && echo yes || echo no)\"",
        "p bootthide \"\$([ -e /data/adb/ksu/.boot_hide ] && echo yes || echo no)\"",
        "p suidir \"\$([ -d /data/adb/sui ] && echo yes || echo no)\"",
        "p ksubin \"\$(ls /data/adb/ksu/bin 2>/dev/null | tr '\\n' ',')\"",
        "p mountmode \"\$(cat /data/adb/ksu/mount_mode 2>/dev/null)\"",
        "p mountprovider \"\$(sed -n 's/^provider=//p' /data/adb/ksu/mount_runtime 2>/dev/null)\"",
        "p modules \"\$(ls /data/adb/modules 2>/dev/null | tr '\\n' ',')\"",
        // 管理器包名：只作次要证据（见类注释第 2 条）
        "p pkgall \"\$(pm list packages 2>/dev/null | sed 's/^package://' | tr '\\n' ',')\"",
        // 被 pm 停用（enabled=0）的包：框架管理器本体被停用 = 图标点不进去
        "p pkgdisabled \"\$(pm list packages -d 2>/dev/null | sed 's/^package://' | tr '\\n' ',')\"",
        "p vbmeta \"\$(getprop ro.boot.vbmeta.device_state)\"",
        "p vboot \"\$(getprop ro.boot.verifiedbootstate)\"",
        "p kmi \"\$(uname -r)\"",
        "p device \"\$(getprop ro.product.device)\"",
        "p android \"\$(getprop ro.build.version.release)\"",
        "p selinux \"\$(getenforce 2>/dev/null)\"",
        "p overlay \"\$(grep -c overlay /proc/mounts)\"",
        // /data/adb/ksud 是持久化安装的 ksud 实体；纯临时 root 通常没有这一层
        "p persistent \"\$([ -f /data/adb/ksud ] && echo yes || echo no)\"",
        // —— 状态检测扩展（Tab2）——
        // 机型型号：8E5（Snapdragon 8 Elite Gen5 = SM8850）平台才能做 hnubl 假回锁，其余平台不应开启 SUSFS
        "p model \"\$(getprop ro.product.model 2>/dev/null)\"",
        // 8E5 判定依据：SoC 型号 SM8850（比字面 model/device 更可靠，BKQ-AN10 这类机型名不含 8e5）
        "p socmodel \"\$(getprop ro.soc.model 2>/dev/null)\"",
        // zygisksu 目录被 NeoZygisk 与 Zygisk Next 共用，用 module.prop 的 name 字段区分
        "p zygisksu_name \"\$(sed -n 's/^name=//p' /data/adb/modules/zygisksu/module.prop 2>/dev/null | head -1)\"",
        // SUSFS 是否开着：目录 / 接口文件 / 已装模块名 任一命中即视为开着
        "p susfsdir \"\$([ -d /data/adb/susfs ] && echo yes || echo no)\"",
        "p susfsproc \"\$([ -e /proc/susfs ] && echo yes || echo no)\"",
        "p susfslkm \"\$(if [ -d /sys/module/susfs_guard_lkm ] || ls -d /proc/susfs* >/dev/null 2>&1; then echo yes; else echo no; fi)\"",
        "p susfsprocglob \"\$(ls -d /proc/susfs* 2>/dev/null | wc -l | tr -d ' ')\"",
        // 上一次/本次启动原因与 boot_id：boot_id 变化 = 发生过真重启
        "p bootid \"\$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\"",
        "p bootreason \"\$(getprop sys.boot.reason 2>/dev/null);\$(getprop ro.boot.bootreason 2>/dev/null)\"",
        // 反向异常：带 disable 标记的模块目录（内核会跳过，但管理器 UI 仍显示已启用）
        "p disables \"\$(ls -1d /data/adb/modules/*/disable 2>/dev/null | sed 's#.*/modules/##; s#/disable##' | tr '\\n' ',')\"",
        // 反向异常：多个 monitor 互杀（zygisk-ptrace64 / vectord / lspd）
        "p mon_ptrace \"\$(pidof zygisk-ptrace64 2>/dev/null | wc -w)\"",
        "p mon_daemon \"\$(pidof vectord lspd 2>/dev/null | wc -w)\""
    ).joinToString("\n")

    fun detect(v: Map<String, String>): Guess {
        val fam = familyOf(v["fam"])
        val rawModules = v["modules"]?.takeIf { it.isNotBlank() } ?: v["modlist"] ?: ""
        val modules = rawModules.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val allPkgs = (v["pkgall"] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val installedManagers = allPkgs.filter { MANAGER_PKGS.containsKey(it) }
        val disabledPkgs = (v["pkgdisabled"] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }

        val hasBuiltinZygisk = v["folkzygisk"] == "yes"
        val is8E5 = (v["socmodel"] ?: "").contains("SM8850", ignoreCase = true) ||
                (v["device"] ?: "").contains("8e5", ignoreCase = true) ||
                (v["model"] ?: "").contains("8e5", ignoreCase = true)
        val procHits = v["susfsprocglob"]?.trim()?.toIntOrNull() ?: 0
        val susfsHits = mutableListOf<String>()
        if (v["susfsdir"] == "yes") susfsHits += "/data/adb/susfs"
        if (v["susfsproc"] == "yes") susfsHits += "/proc/susfs"
        if (v["susfslkm"] == "yes") susfsHits += "/sys/module/susfs_guard_lkm 或 /proc/susfs_*"
        if (procHits > 0) susfsHits += "/proc/susfs_* ×$procHits"
        if (v["folksusfs"] == "yes") susfsHits += "/data/adb/ksu/folksu-susfs"
        val susfsModules = modules.filter { it.contains("susfs", ignoreCase = true) }
        if (susfsModules.isNotEmpty()) susfsHits += "模块 " + susfsModules.joinToString("、")
        val susfsEvidence = susfsHits.joinToString(" / ")
        val susfsOn = susfsHits.isNotEmpty()
        val bootId = v["bootid"] ?: ""
        val bootReason = (v["bootreason"] ?: "")
            .split(";").map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ; ")
        val disabledModules = (v["disables"] ?: "")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val monitorPtraceCount = v["mon_ptrace"]?.toIntOrNull() ?: 0
        val monitorDaemonCount = v["mon_daemon"]?.toIntOrNull() ?: 0
        val zygisksuName = v["zygisksu_name"] ?: ""
        val (frameworkFindings, baseConflicts) = scanFrameworks(
            modules, disabledModules, hasBuiltinZygisk, monitorPtraceCount, monitorDaemonCount, zygisksuName
        )
        val (managerAppFindings, managerConflicts) = scanManagerApps(
            frameworkFindings, allPkgs, disabledPkgs
        )
        val conflicts = baseConflicts + managerConflicts
        // 当前生效的 Zygisk 注入引擎：优先内置，否则取已启用且非禁用的外挂实现
        val zygiskEngine = when {
            hasBuiltinZygisk -> "内置 FolkZygisk"
            else -> frameworkFindings.firstOrNull {
                it.installed && !it.disabled &&
                        it.name in setOf("NeoZygisk", "Zygisk Next", "YukiZygisk", "Zygisk Next / NeoZygisk")
            }?.name ?: ""
        }

        val facts = Facts(
            mountProvider = v["mountprovider"] ?: "",
            mountMode = v["mountmode"] ?: "",
            hasBuiltinZygisk = hasBuiltinZygisk,
            hasSui = v["suidir"] == "yes",
            moduleIds = modules,
            managerPackages = installedManagers,
            is8E5 = is8E5,
            susfsOn = susfsOn,
            susfsEvidence = susfsEvidence,
            bootId = bootId,
            bootReason = bootReason,
            disabledModules = disabledModules,
            monitorPtraceCount = monitorPtraceCount,
            monitorDaemonCount = monitorDaemonCount,
            frameworkFindings = frameworkFindings,
            managerAppFindings = managerAppFindings,
            conflicts = conflicts,
            zygiskEngine = zygiskEngine
        )

        val strong = ArrayList<String>()
        val weak = ArrayList<String>()
        val help = v["ksudhelp"] ?: ""
        val ksudver = v["ksudver"]?.takeIf { it.isNotBlank() } ?: v["ksud"] ?: ""

        // ---------- FinaSU：目前唯一有稳定文件级指纹的分支（真机验证过） ----------
        if (v["folkzygisk"] == "yes") strong.add("/data/adb/ksu/bin/folkzygisk 存在（内置 FolkZygisk 引擎）")
        if (help.contains("FolkZygisk")) strong.add("ksud 子命令含 zygisk → FolkZygisk 管理")
        if (help.contains("boot-hide")) strong.add("ksud 子命令含 boot-hide（FinaSU 特有，隐藏解锁状态）")
        if (help.contains("uts-view")) strong.add("ksud 子命令含 uts-view（FinaSU 的 uname 伪装）")
        if (help.contains("netisolate")) strong.add("ksud 子命令含 netisolate（FinaSU 的网络隔离）")
        if (v["folksusfs"] == "yes") strong.add("/data/adb/ksu/folksu-susfs 存在")
        if (v["suidir"] == "yes") weak.add("/data/adb/sui 存在（内置 Sui 扩展）")
        if (ksudver.contains("uapi")) weak.add("ksud 版本串含 uapi：$ksudver")
        if (modules.any { it.startsWith("finasu") }) weak.add("已装 finasu-* 模块")

        if (strong.size >= 2) {
            return Guess(
                "finasu", Confidence.HIGH, Family.KSU,
                "命中 ${strong.size} 条 FinaSU 独有指纹",
                strong + weak, facts
            )
        }

        // ---------- 其它分支：靠管理器包名，但只给 MEDIUM ----------
        val byPkg = installedManagers.mapNotNull { pkg -> MANAGER_PKGS[pkg]?.let { it to pkg } }.distinct()
        if (byPkg.size == 1) {
            val (branchId, pkg) = byPkg.first()
            // 歧义包名（FinaSU 沿用 ReSukiSU 包名）：指纹都没命中时，绝不能只凭包名认 BakaSU
            val ambiguous = AMBIGUOUS_PKGS[pkg]
            if (ambiguous != null) {
                return Guess(
                    null, Confidence.NONE, fam,
                    "包名 $pkg 同时被 ${ambiguous.joinToString("、")} 使用（FinaSU 分叉自 ReSukiSU 沿用包名），" +
                            "指纹又没命中，无法仅凭包名区分",
                    strong + weak, facts, candidates = ambiguous
                )
            }
            val familyOk = fam == null || Branches.byId(branchId).family == fam
            if (familyOk) {
                weak.add("检测到管理器包名 $pkg")
                return Guess(
                    branchId, Confidence.MEDIUM, fam,
                    "只根据管理器包名推断，可能与内核分支不一致",
                    strong + weak, facts
                )
            }
        } else if (byPkg.size > 1) {
            return Guess(
                null, Confidence.NONE, fam,
                "装了多个管理器（${byPkg.joinToString { it.second }}），无法判断内核实际属于哪一支",
                weak, facts, candidates = byPkg.map { it.first }.distinct()
            )
        }

        // ---------- 认不出：不猜 ----------
        return Guess(
            null, Confidence.NONE, fam,
            if (fam == Family.KSU)
                "没有命中任何分支的独有指纹。KSU 系各分支目录结构几乎一样，靠目录名硬猜一定会错。"
            else
                "没有命中可识别的指纹，请在下方手动选择。",
            strong + weak, facts, candidates = candidatesOf(fam)
        )
    }

    /**
     * LSPosed / Vector / Zygisk Next / NeoZygisk / YukiZygisk 的模块目录名 → 显示名。
     * 注意：`zygisksu` 目录被 NeoZygisk 与 Zygisk Next 共用（撞名），所以这一项在
     * [scanFrameworks] 里会再拿 module.prop 的 name 字段二次判定，见 [resolveZygisksuName]。
     */
    private val FRAMEWORK_DIRS: Map<String, String> = linkedMapOf(
        "zygisk_lsposed" to "LSPosed",
        "lsposed" to "LSPosed",
        "zygisk_vector" to "Vector",
        "zygisksu" to "Zygisk Next / NeoZygisk",
        "zygisknext" to "Zygisk Next",
        "zygisk-next" to "Zygisk Next",
        "neozygisk" to "NeoZygisk",
        "yukizygisk" to "YukiZygisk"
    )

    /** `zygisksu` 目录撞名，用 module.prop 的 name 字段区分 NeoZygisk 与 Zygisk Next */
    private fun resolveZygisksuName(name: String): String = when {
        name.contains("neozygisk", ignoreCase = true) -> "NeoZygisk"
        name.contains("zygisk next", ignoreCase = true) -> "Zygisk Next"
        name.contains("zygisknext", ignoreCase = true) -> "Zygisk Next"
        else -> "Zygisk Next / NeoZygisk"
    }

    /**
     * 检测 5 个框架模块的「装没装 / 有没有 disable 标记」，并推导反向异常
     * （互斥、双开、多 monitor 互杀）。这些结论是 Tab2 第 4 项的素材。
     */
    private fun scanFrameworks(
        modules: List<String>,
        disabled: List<String>,
        hasBuiltinZygisk: Boolean,
        monPtrace: Int,
        monDaemon: Int,
        zygisksuName: String
    ): Pair<List<FrameworkFinding>, List<String>> {
        val mset = modules.map { it.lowercase() }.toSet()
        val dset = disabled.map { it.lowercase() }.toSet()

        // 去重到显示名：同一框架的多个目录名归一（保留第一个命中）
        val byName = LinkedHashMap<String, String>()
        for ((dir, name) in FRAMEWORK_DIRS) {
            val resolved = if (dir == "zygisksu") resolveZygisksuName(zygisksuName) else name
            if (mset.contains(dir) && !byName.containsKey(resolved)) byName[resolved] = dir
        }

        val findings = byName.map { (name, dir) ->
            val installed = mset.contains(dir)
            val disabledHere = dset.contains(dir)
            val note = when {
                !installed -> "未安装"
                disabledHere -> "⚠ 带 disable 标记：内核会跳过该模块，但管理器 UI 可能仍显示已启用"
                else -> "已安装且启用"
            }
            FrameworkFinding(dir, name, installed, disabledHere, note)
        }

        val conflicts = ArrayList<String>()
        val active = findings.filter { it.installed && !it.disabled }.map { it.name }

        // 反向异常 1：LSPosed 与 Vector 同时启用（框架互斥）
        if (active.contains("LSPosed") && active.contains("Vector")) {
            conflicts.add("LSPosed 与 Vector 同时启用：两者互斥，只留一个（另一个保持 disable 标记）")
        }
        // 反向异常 2：多个 Zygisk 实现同时启用
        val zygiskImpls = active.filter {
            it in setOf("Zygisk Next", "Zygisk Next / NeoZygisk", "NeoZygisk", "YukiZygisk")
        }
        if (zygiskImpls.size > 1) {
            conflicts.add("多个 Zygisk 实现同时启用（${zygiskImpls.joinToString("、")}）：只保留一个，其余关掉")
        }
        // 反向异常 3：内置 Zygisk 引擎（FinaSU/YukiSU）与外挂实现双开
        if (hasBuiltinZygisk && active.any {
                it in setOf("Zygisk Next", "Zygisk Next / NeoZygisk", "NeoZygisk")
            }) {
            conflicts.add("内置 Zygisk 引擎与外挂实现双开：只保留一套，否则抢 zygote 注入")
        }
        // 反向异常 4：多个 monitor 进程互杀
        if (monPtrace > 1 || monDaemon > 1) {
            conflicts.add("检测到多个 monitor 进程（zygisk-ptrace64=$monPtrace / vectord|lspd=$monDaemon）：会互相打死 zygote，需清理到各 ≤1")
        }

        return findings to conflicts
    }

    /**
     * 框架显示名 → 必须存在的「管理器本体 / 连接器」APK 包名。
     *
     * 真机核实（2026-11-05，LSPosed 2.2.1 / 7912 发布包解包）：
     *  - LSPosed 拆成两个 APK：manager.apk = `org.lsposed.manager`（带 launcher Activity，就是「LSPosed 界面」），
     *    daemon.apk = `org.lsposed.daemon`（零 Activity 的后台连接器）。**只装 daemon、没装 manager，
     *    点图标/启动按钮就是进不去界面** —— 与框架是否注入无关。
     *  - Vector 的管理器 UI = `org.matrix.vector.manager`。
     */
    private val FRAMEWORK_MANAGER_APKS: Map<String, List<Pair<String, String>>> = mapOf(
        "LSPosed" to listOf(
            "org.lsposed.manager" to "管理器 UI",
            "org.lsposed.daemon" to "守护连接器"
        ),
        "Vector" to listOf(
            "org.matrix.vector.manager" to "管理器 UI"
        )
    )

    /**
     * 只对「模块已启用」的框架检查管理器本体。模块都 disable 了就不该再要求装 APK。
     * 返回 findings（第 4 项展示用）与 conflicts（反向异常，给出可执行修复）。
     */
    private fun scanManagerApps(
        findings: List<FrameworkFinding>,
        allPkgs: List<String>,
        disabledPkgs: List<String>
    ): Pair<List<ManagerAppFinding>, List<String>> {
        val activeNames = findings.filter { it.installed && !it.disabled }.map { it.name }.toSet()
        val out = ArrayList<ManagerAppFinding>()
        val conflicts = ArrayList<String>()
        for ((framework, apks) in FRAMEWORK_MANAGER_APKS) {
            if (framework !in activeNames) continue
            for ((pkg, role) in apks) {
                val installed = allPkgs.contains(pkg)
                val disabled = disabledPkgs.contains(pkg)
                val note = when {
                    !installed -> "⚠ 未安装：框架能注入，但点图标/启动按钮进不去界面"
                    disabled -> "⚠ 已被停用（enabled=0）：图标不显示或点不开"
                    else -> "已安装且启用"
                }
                out.add(ManagerAppFinding(framework, pkg, role, installed, disabled, note))
                when {
                    !installed -> conflicts.add(
                        "$framework $role `$pkg` 未安装：框架可注入但进不去管理界面，需从发布包 zip 提取 manager.apk 安装（LSPosed 的 manager.apk 才是带界面的那个，daemon.apk 无界面）"
                    )
                    disabled -> conflicts.add(
                        "$framework $role `$pkg` 被 pm 停用（enabled=0）：图标不显示、拉不起，执行 `pm enable $pkg` 恢复"
                    )
                }
            }
        }
        return out to conflicts
    }

    private fun familyOf(raw: String?): Family? = when (raw) {
        "ksu" -> Family.KSU
        "magisk" -> Family.MAGISK
        "apatch" -> Family.APATCH
        else -> null
    }

    private fun candidatesOf(fam: Family?): List<String> = when (fam) {
        Family.KSU -> listOf("ksu_official", "ksu_next", "sukisu", "bakasu", "finasu", "yukisu")
        Family.MAGISK -> listOf("magisk_official", "magisk_alpha", "kitsune")
        Family.APATCH -> listOf("apatch", "folkpatch")
        else -> Branches.ALL.map { it.id }
    }

    // ---------------- 无 root 权限时的包名级检测 ----------------

    /** 该包名是否为已知的管理器包名（供无权限预检测用） */
    fun isManagerPkg(pkg: String): Boolean = MANAGER_PKGS.containsKey(pkg)

    /** 所有已知管理器包名，供 UI 用 PackageManager 逐个查询 */
    val MANAGER_PKGS_KEYS: Set<String> = MANAGER_PKGS.keys

    /**
     * 只根据「已安装的管理器包名」推断分支。用于拿不到 root（未授权 / 无 su）时，
     * 至少告诉用户系统层面已 root、装的是哪个管理器、可能对应哪个分支。
     *
     * 注意：包名只是次要证据（FinaSU 沿用 ReSukiSU 的包名），所以最高只给 MEDIUM；
     * 歧义包名（BakaSU/FinaSU 共用）不给结论，并列候选并说明原因。
     */
    fun detectByManagerPackages(pkgs: List<String>): Guess {
        val known = pkgs.filter { isManagerPkg(it) }
        val candidates = known.flatMap { candidatesFor(it) }.distinct()
        val evidences = known.map { "检测到已安装管理器：$it" }
        val facts = Facts(managerPackages = known)

        return when {
            candidates.isEmpty() -> Guess(
                null, Confidence.NONE, null,
                "没有命中任何已知的管理器包名",
                evidences, facts,
                candidates = Branches.ALL.map { it.id }
            )

            candidates.size == 1 -> Guess(
                candidates.first(), Confidence.MEDIUM, Branches.byId(candidates.first()).family,
                "只根据管理器包名推断，可能与内核分支不一致；授权后复测可确认",
                evidences, facts,
                candidates = candidates
            )

            else -> {
                val ambiguous = known.filter { candidatesFor(it).size > 1 }
                val reason = if (ambiguous.isNotEmpty()) {
                    "包名 ${ambiguous.joinToString("、")} 同时对应 " +
                            "${ambiguous.flatMap { candidatesFor(it) }.distinct().joinToString("、")}" +
                            "（FinaSU 分叉自 ReSukiSU 沿用包名），无权限无法区分"
                } else {
                    "检测到多个管理器（${known.joinToString("、")}），无法判断内核实际属于哪一支"
                }
                Guess(
                    null, Confidence.NONE, null,
                    reason, evidences, facts,
                    candidates = candidates
                )
            }
        }
    }
}
