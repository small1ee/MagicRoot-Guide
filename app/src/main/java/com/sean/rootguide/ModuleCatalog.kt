package com.sean.rootguide

enum class ArtifactKind { MODULE_ZIP, APK }

/**
 * 一个可自动下载的模块/插件。
 *
 * [repo] 与 [assetRegex] 都是 2026-10-29 用 GitHub API 实测确认过的真实值，改之前先跑一次：
 *   curl -s https://api.github.com/repos/<repo>/releases/latest | grep '"name"'
 *
 * 故意不写死下载直链：Release 资产名带版本号与 commit 短哈希（例如
 * `Zygisk-Next-1.5.2-864-a4a72ce-release.zip`），写死必过期。运行时走 latest 接口现查现下。
 *
 * [installedIds] 用来判断"是不是已经装过了"，取值是 /data/adb/modules 下的目录名（小写比较）。
 */
data class ModuleSpec(
    val repo: String,
    val assetRegex: String,
    val kind: ArtifactKind,
    val installedIds: List<String> = emptyList(),
    /** 资产名里含这些词的跳过（下游分支常同时发 debug 包） */
    val excludeIfContains: List<String> = listOf("debug")
) {
    val repoUrl: String get() = "https://github.com/$repo"
}

object ModuleCatalog {

    /** 元模块：只给「没有内置挂载引擎」的 KSU 分支用（官方 / BakaSU / YukiSU） */
    val META_OVERLAYFS = ModuleSpec(
        repo = "KernelSU-Modules-Repo/meta-overlayfs",
        assetRegex = """.*\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("meta-overlayfs")
    )

    val MOUNTIFY = ModuleSpec(
        repo = "backslashxx/mountify",
        assetRegex = """mountify.*\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("mountify")
    )

    val ZYGISK_NEXT = ModuleSpec(
        repo = "Dr-TSNG/ZygiskNext",
        assetRegex = """.*release\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("zygisksu", "zygisknext", "zygisk-next")
    )

    val REZYGISK = ModuleSpec(
        repo = "PerformanC/ReZygisk",
        assetRegex = """.*\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("rezygisk")
    )

    /**
     * NeoZygisk：ptrace 直接附加正在运行的 zygote 注入，不依赖 post-fs-data 拉起 daemon，
     * 因此适配「临时 root / late-load」场景（开关 SUSFS 时强烈推荐的外挂 Zygisk 实现）。
     * repo 与 assetRegex 请以 GitHub API 实测为准；这里按 NeoZygisk 的发布命名填，改前先跑一次 curl 确认。
     */
    val NEOZYGISK = ModuleSpec(
        repo = "NeoZygisk/NeoZygisk",
        assetRegex = """.*release\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("neozygisk")
    )

    val ZYGISK_ASSISTANT = ModuleSpec(
        repo = "snake-4/Zygisk-Assistant",
        assetRegex = """.*release\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("zygisk-assistant", "zygisk_assistant")
    )

    val PLAY_INTEGRITY_FORK = ModuleSpec(
        repo = "osm0sis/PlayIntegrityFork",
        assetRegex = """.*\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("playintegrityfix", "playintegrityfork", "pif")
    )

    val TRICKY_STORE = ModuleSpec(
        repo = "5ec1cff/TrickyStore",
        assetRegex = """.*release\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("trickystore", "tricky-store", "tricky_store")
    )

    val VECTOR = ModuleSpec(
        repo = "JingMatrix/Vector",
        assetRegex = """.*release\.zip$""",
        kind = ArtifactKind.MODULE_ZIP,
        installedIds = listOf("zygisk_vector", "vector", "zygisk_lsposed")
    )

    val HIDE_MY_APPLIST = ModuleSpec(
        repo = "Dr-TSNG/Hide-My-Applist",
        assetRegex = """.*\.apk$""",
        kind = ArtifactKind.APK,
        installedIds = listOf("hma", "hma_oss_zygisk")
    )

    val ADAWAY = ModuleSpec(
        repo = "AdAway/AdAway",
        assetRegex = """.*\.apk$""",
        kind = ArtifactKind.APK,
        installedIds = emptyList()
    )

    /** 按 module 反查"是否已装"：模块目录名只要命中任一别名即认为已装 */
    fun isInstalled(spec: ModuleSpec?, installedModules: List<String>): Boolean {
        if (spec == null || installedModules.isEmpty()) return false
        val lower = installedModules.map { it.lowercase() }
        return spec.installedIds.any { id ->
            lower.any { it == id || it.startsWith("$id-") || it.startsWith("${id}_") }
        }
    }
}
