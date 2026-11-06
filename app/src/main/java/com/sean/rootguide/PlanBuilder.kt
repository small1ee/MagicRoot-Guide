package com.sean.rootguide

/**
 * 按分支生成「设置项 + 模块」清单。
 *
 * 顺序即安装顺序，不要重排：基础层（元模块 / Zygisk 实现）→ 隐藏与完整性 → Xposed → 功能。
 * 每条 SETUP 步骤都是在管理器 App 里点几下就行；MODULE 步骤带 module 字段才能一键下载安装。
 */
object PlanBuilder {

    fun build(branch: Branch, susfsOn: Boolean = false): List<Step> {
        val steps = mutableListOf<Step>()

        // ---------------- FinaSU：全家桶，设置项最多 ----------------
        if (branch.id == "finasu") {
            // 临时 root（漏洞提权）不是分支：它只是「怎么把 root 装进去」的方式。
            // 漏洞链提权后内核跑的就是 FinaSU，所以这两步并入 FinaSU 清单，仅临时 root 需要。
            steps += Step(
                id = "finasu_temp_verify",
                title = "⓪ 若走临时 root（漏洞提权）：先确认内核里的 root 还在",
                desc = "每次开机都要重新提权。先确认 ksud 与内核模块存在，再谈装模块。",
                required = true, kind = StepKind.SETUP,
                cmd = "su -c \"ksud -V\" && su -c \"lsmod | grep -i kernelsu\"",
                verify = "两条命令都有输出才继续；否则回到提权工具（如 MagicRoot）重新提权"
            )
            steps += Step(
                id = "finasu_mount",
                title = "① 确认挂载后端保持 automatic",
                desc = "可选值：automatic / built-in (Folk-Mount) / metamodule only / NoMount / off。" +
                        "默认 automatic 已经能自动挑最合适的后端。",
                required = true, kind = StepKind.SETUP,
                verify = "设置页若显示\"已回退到 X\"，属于设计行为，不是 bug",
                warn = "绝对不要「内置引擎 + 外部元模块」双开，挂载冲突会卡启动"
            )
            steps += Step(
                id = "finasu_whitelist",
                title = "② 把要 root 的 App 加进白名单",
                desc = "FinaSU 是白名单授权模型，默认任何 App 都没有 root，也不会弹授权窗。",
                required = true, kind = StepKind.SETUP,
                verify = "su 能返回 uid=0 才算授权成功",
                warn = "这是「root 明明装了但 App 说没权限」的第一大原因"
            )
            steps += Step(
                id = "finasu_one_zygisk",
                title = "③ 确认只有一套 Zygisk 引擎",
                desc = "内置 FolkZygisk 与外挂 Zygisk Next / ReZygisk 只能留一个。" +
                        "两者同时启用会抢 zygote 注入，表现为部分模块时好时坏。",
                required = true, kind = StepKind.SETUP,
                cmd = "su -c \"ls /data/adb/modules | grep -i zygisk\"",
                verify = "要么只有内置引擎在跑，要么内置已关、只留外挂"
            )
            steps += Step(
                id = "finasu_temp_softreboot",
                title = "④（临时 root 专用）装完模块用软重启挂载",
                desc = "模块挂载发生在开机阶段。临时 root 物理重启后内核回到原厂、root 消失，挂载永远不会发生；" +
                        "软重启只重启框架、不重载内核，所以 root 还在、模块能挂上。永久 root 用户忽略本步，正常重启即可。",
                required = true, kind = StepKind.SETUP,
                cmd = "su -c \"ksud soft-reboot\"",
                verify = "su -c \"cat /proc/mounts | grep -i overlay\" 有输出即挂载成功",
                warn = "老版本 ksud 的软重启不重放 post-fs-data，那种构建无解"
            )
        }

        // ---------------- APatch ----------------
        if (branch.id == "apatch" || branch.id == "folkpatch") {
            steps += Step(
                id = "apatch_superkey",
                title = "① 设置并记住 SuperKey",
                desc = "APatch 用 SuperKey 认证管理器。忘了 SuperKey 等于管理器进不去。",
                required = true, kind = StepKind.SETUP,
                warn = "SuperKey 丢失后只能重新修补 boot"
            )
        }

        // ---------------- Magisk ----------------
        if (branch.family == Family.MAGISK) {
            steps += Step(
                id = "magisk_zygisk",
                title = "① 打开 Zygisk 开关",
                desc = "Magisk 设置 → 开启 Zygisk → 重启。Magisk 自带注入与挂载能力，不需要任何元模块。",
                required = true, kind = StepKind.SETUP,
                verify = "重启后设置页 Zygisk 显示为开启",
                warn = "要隐藏时用 Zygisk Assistant；Shamiko 已停更"
            )
        }

        // ---------------- 基础层：元模块 / Zygisk 实现 ----------------
        if (branch.needMetaModule) steps += metaStep()
        if (susfsOn) {
            // 已开启 SUSFS：优先建议 vector + NeoZygisk 组合，但不是「必须换掉 Zygisk Next」。
            // NeoZygisk 用 ptrace 直接附加运行的 zygote 注入，不需要重启 zygote 也能注入，
            // 能避开 MagicOS 的 critical window（开机 10 分钟内重启 zygote 必整机重启）。
            // Zygisk Next 同样可用：与 NeoZygisk 共用 zygisksu/post-fs-data.sh，
            // late-load 下手动补跑该脚本（并补齐 sepolicy.rule）即可拉起，只是也要避开 critical window。
            steps += susfsComboStep()
            steps += neoZygiskStep()
        } else {
            if (branch.needZygiskImpl) steps += zygiskStep()
        }

        steps += Step(
            id = "reboot_verify",
            title = "重启并验证基础层已生效",
            desc = "每装完一层就重启一次，确认没问题再装下一层。一次性全装是 bootloop 的常见成因。",
            required = true, kind = StepKind.SETUP,
            cmd = "su -c \"ksud module list\"",
            verify = "模块状态为启用；su -c \"cat /proc/mounts | grep -i overlay\" 有输出"
        )

        // ---------------- 隐藏与完整性 ----------------
        steps += Step(
            id = "zygisk_assistant",
            title = "Zygisk Assistant（隐藏，可选）",
            desc = "强化 DenyList：让目标 App 看不到注入痕迹。银行/支付类 App 才需要。",
            required = false, kind = StepKind.MODULE,
            url = ModuleCatalog.ZYGISK_ASSISTANT.repoUrl,
            module = ModuleCatalog.ZYGISK_ASSISTANT,
            verify = "目标 App 能启动且不弹 root 提示",
            warn = "用它时要关掉 Magisk 的 Enforce DenyList，由它自己读列表"
        )
        steps += Step(
            id = "pif",
            title = "PlayIntegrityFork（完整性，可选）",
            desc = "原版 PlayIntegrityFix 已下架，用 Fork 版。解决 Play 商店/银行提示设备不合规。",
            required = false, kind = StepKind.MODULE,
            url = ModuleCatalog.PLAY_INTEGRITY_FORK.repoUrl,
            module = ModuleCatalog.PLAY_INTEGRITY_FORK,
            verify = "Play Integrity API Checker：BASIC ✅ / DEVICE ✅ 即达正常上限",
            warn = "不要追求 STRONG：依赖未吊销的硬件 keybox，解锁 BL 后基本拿不到"
        )
        steps += Step(
            id = "tricky_store",
            title = "Tricky Store（可选，前一项仍不过时才加）",
            desc = "只在 DEVICE 级仍不过时才加。每加一个变量都要重启 + 复测。",
            required = false, kind = StepKind.MODULE,
            url = ModuleCatalog.TRICKY_STORE.repoUrl,
            module = ModuleCatalog.TRICKY_STORE
        )
        steps += hmaStep()

        // ---------------- Xposed ----------------
        steps += vectorStep()

        // ---------------- 功能 ----------------
        steps += hostsStep()
        steps += Step(
            id = "adaway",
            title = "AdAway（去广告客户端，可选）",
            desc = "广告拦截前端。需要先有 hosts 能力（Magisk 内置开关，或 KSU 系的 hosts 模块）。",
            required = false, kind = StepKind.MODULE,
            url = ModuleCatalog.ADAWAY.repoUrl,
            module = ModuleCatalog.ADAWAY
        )
        steps += Step(
            id = "gms_doze",
            title = "Universal GMS Doze（省电，可选）",
            desc = "抑制 GMS 唤醒。少数机型会因此收不到推送，装完观察一天。",
            required = false, kind = StepKind.MODULE,
            verify = "观察 24 小时：待机耗电下降且推送正常"
        )

        // ---------------- 高级 ----------------
        if (branch.supportsKpm) {
            steps += Step(
                id = "kpm",
                title = "KPM 内核模块（高级，可选）",
                desc = "运行时加载内核模块做内核级 hook，需要内核开启 CONFIG_KPM。没有统一的官方分发渠道，" +
                        "只能按具体模块（如 NoHello）自己的仓库下载。",
                required = false, kind = StepKind.MODULE,
                verify = "lsmod 能看到已加载的模块",
                warn = "内核模块搞错 = 不开机。刷之前一定备份 boot / init_boot"
            )
        }
        if (branch.supportsSusfs) {
            steps += Step(
                id = "susfs",
                title = "SUSFS 内核级隐藏（高级，可选）",
                desc = "在内核里抹掉痕迹：SUS_PATH / SUS_MOUNT / SUS_KSTAT / SPOOF_UNAME。" +
                        "源码在 GitLab，且必须与内核补丁版本配套，App 不代下载。",
                required = false, kind = StepKind.MODULE,
                url = "https://gitlab.com/simonpunk/susfs4ksu",
                warn = "内核必须已打 SUSFS 补丁且 KMI 精确匹配；刷进没补丁的内核会破坏 key attestation"
            )
        }

        return steps
    }

    private fun metaStep(): Step = Step(
        id = "meta_module",
        title = "元模块 meta-overlayfs（必装）",
        desc = "给 KernelSU 提供模块挂载能力。官方 KernelSU / BakaSU / YukiSU 不装它，所有普通模块装了也完全不生效。",
        required = true, kind = StepKind.MODULE,
        url = ModuleCatalog.META_OVERLAYFS.repoUrl,
        module = ModuleCatalog.META_OVERLAYFS,
        cmd = "su -c \"ksud module install /data/local/tmp/meta-overlayfs.zip\"",
        verify = "su -c \"ksud module list\" 能看到元模块且为启用",
        warn = "KSU-Next / SukiSU / FinaSU / Magisk / APatch 都不需要它，别重复装"
    )

    private fun zygiskStep(): Step = Step(
        id = "zygisk_impl",
        title = "Zygisk 实现：Zygisk Next（必装）",
        desc = "为 zygote 提供注入能力，Vector / Xposed 模块与 Zygisk 模块都依赖它。与内置引擎二选一。",
        required = true, kind = StepKind.MODULE,
        url = ModuleCatalog.ZYGISK_NEXT.repoUrl,
        module = ModuleCatalog.ZYGISK_NEXT,
        cmd = "su -c \"ksud module install /data/local/tmp/zygisknext.zip\"",
        verify = "装一个 Zygisk 模块能正常启停即通",
        warn = "FinaSU / YukiSU 已内置引擎，装外部实现前先关掉内置的"
    )

    private fun susfsComboStep(): Step = Step(
        id = "susfs_combo",
        title = "⚠ 已检测到 SUSFS 开启：建议优先 vector + NeoZygisk 组合（Zygisk Next 亦可用）",
        desc = "开启 SUSFS 后本 App 优先建议 NeoZygisk（外挂 Zygisk 实现）+ Vector（Xposed）的组合：" +
                "NeoZygisk 用 ptrace 注入活着的 zygote，不依赖 post-fs-data，更适配临时 root / late-load，也能避开 MagicOS 的 critical window。" +
                "这不是「必须换掉 Zygisk Next」——Zygisk Next 与之共用 zygisksu/post-fs-data.sh，late-load 下手动补跑该脚本并补齐 sepolicy.rule 同样能拉起（实测），只是重启 zygote 时要避开窗口期。",
        required = true, kind = StepKind.SETUP,
        warn = "若当前分支自带内置 Zygisk（FinaSU / YukiSU）或已开 Magisk Zygisk，先关掉内置/自带的那套，只留一套外挂实现，否则双开抢注入。" +
                "Zygisk 实现同时只能装一套：NeoZygisk 与 Zygisk Next 二选一，不是叠加。" +
                "开着 SUSFS 时切勿做第一次注入，先确认放行条与注入库都在，再重启 zygote（见「框架功能恢复」）。"
    )

    private fun neoZygiskStep(): Step = Step(
        id = "zygisk_impl_neo",
        title = "Zygisk 实现：NeoZygisk（开启 SUSFS 时优先推荐，可与 Zygisk Next 二选一）",
        desc = "ptrace 直接附加运行的 zygote 注入，不依赖 post-fs-data 拉起 daemon，适配临时 root / late-load。与内置引擎二选一；与 Zygisk Next 也是二选一，二者共用 zygisksu/post-fs-data.sh。",
        required = true, kind = StepKind.MODULE,
        url = ModuleCatalog.NEOZYGISK.repoUrl,
        module = ModuleCatalog.NEOZYGISK,
        cmd = "su -c \"ksud module install /data/local/tmp/neozygisk.zip\"",
        verify = "装一个 Zygisk 模块能正常启停即通",
        warn = "若当前分支自带内置 Zygisk（FinaSU / YukiSU）或已开 Magisk Zygisk，先关掉内置/自带的那套，只留一套外挂实现；已装 Zygisk Next 且工作正常的话，可以不换"
    )

    private fun vectorStep(): Step = Step(
        id = "vector",
        title = "Vector（Xposed 框架，需要时才装）",
        desc = "原版 LSPosed 已于 2023-10 停更，Vector 是继任者。装完要在里面勾选模块与作用域，并强停目标 App。",
        required = false, kind = StepKind.MODULE,
        url = ModuleCatalog.VECTOR.repoUrl,
        module = ModuleCatalog.VECTOR,
        verify = "管理器里能看到作用域列表，且目标 App 的 hook 生效",
        warn = "软重启环境下 Vector 可能双 daemon：禁用 → 软重启 → 启用 → 再软重启一次"
    )

    private fun hostsStep(): Step = Step(
        id = "hosts",
        title = "hosts 能力（去广告的前提）",
        desc = "Magisk：设置里打开「Systemless hosts」即可，不必装模块。KSU 系：装一个 systemless hosts 模块。" +
                "有了它才能用 AdAway 写规则。",
        required = false, kind = StepKind.SETUP,
        verify = "cat /system/etc/hosts 里出现大量 0.0.0.0 条目"
    )

    private fun hmaStep(): Step = Step(
        id = "hma",
        title = "Hide My Applist（防应用列表枚举，可选）",
        desc = "阻止 App 通过列出已安装应用判定环境。需要 Xposed 框架（先装 Vector）。",
        required = false, kind = StepKind.MODULE,
        url = ModuleCatalog.HIDE_MY_APPLIST.repoUrl,
        module = ModuleCatalog.HIDE_MY_APPLIST,
        verify = "在 Vector 里启用 HMA 并配好模板后，目标 App 读到的应用列表被过滤"
    )
}
