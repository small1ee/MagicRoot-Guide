package com.sean.rootguide

enum class Family { MAGISK, KSU, APATCH }

/** 步骤类型：决定卡片上的按钮与分组标题 */
enum class StepKind {
    /** 设置项：只需在管理器 App 里改设置，没有东西可下载 */
    SETUP,
    /** 模块/插件：可直接下载并安装 */
    MODULE
}

data class Branch(
    val id: String,
    val label: String,
    val family: Family,
    val needMetaModule: Boolean,
    val needZygiskImpl: Boolean,
    val supportsKpm: Boolean,
    val supportsSusfs: Boolean,
    val note: String
)

data class Step(
    val id: String,
    val title: String,
    val desc: String,
    val required: Boolean,
    val kind: StepKind = StepKind.MODULE,
    val url: String? = null,
    val cmd: String? = null,
    val verify: String? = null,
    val warn: String? = null,
    /** 可自动下载安装的模块；为空表示该步骤只能手动处理 */
    val module: ModuleSpec? = null
)

object Branches {
    val ALL: List<Branch> = listOf(
        Branch(
            "magisk_official", "Magisk 官方", Family.MAGISK,
            false, false, false, false,
            "最通用、资料最多。装完第一步：设置里打开 Zygisk。Magisk 没有「元模块」概念，别装。"
        ),
        Branch(
            "magisk_alpha", "Magisk Alpha", Family.MAGISK,
            false, false, false, false,
            "官方的隐藏强化版。用法与官方一致，更新节奏不稳，追新要有心理准备。"
        ),
        Branch(
            "kitsune", "Kitsune Mask", Family.MAGISK,
            false, false, false, false,
            "Magisk 的 fork，功能叠加。用法与官方一致。"
        ),
        Branch(
            "ksu_official", "KernelSU 官方", Family.KSU,
            true, true, false, false,
            "必须先装元模块，否则普通模块全部静默失效。不支持 KPM。"
        ),
        Branch(
            "ksu_next", "KernelSU-Next", Family.KSU,
            false, true, false, false,
            "自带挂载引擎，跳过元模块，直接装 Zygisk 实现即可。"
        ),
        Branch(
            "sukisu", "SukiSU-Ultra", Family.KSU,
            false, true, true, true,
            "功能最全：KPM + SUSFS（SUSFS 需内核打过补丁）。稳定性口碑一般，出问题要有自己查的觉悟。"
        ),
        Branch(
            "bakasu", "BakaSU（原 ReSukiSU）", Family.KSU,
            true, true, true, false,
            "需要元模块；支持 KPM；Tag 多为 pre-release，别追最新版本。"
        ),
        Branch(
            "finasu", "FinaSU", Family.KSU,
            false, false, false, true,
            "全家桶：内置 FolkZygisk + Sui + 挂载引擎。挂载后端保持 automatic，别内置+元模块双开；授权是白名单，不弹窗。"
        ),
        Branch(
            "yukisu", "YukiSU", Family.KSU,
            true, false, false, false,
            "需要元模块；内置 YukiZygisk；白名单授权模型（默认任何 App 都没有 root）。"
        ),
        Branch(
            "apatch", "APatch", Family.APATCH,
            false, true, true, false,
            "装完第一步：设好 SuperKey（忘了进不去管理器），再装 APM 模块。支持 KPM，不支持 SUSFS。"
        ),
        Branch(
            "folkpatch", "FolkPatch / ShiroSU", Family.APATCH,
            false, true, true, false,
            "APatch 的分支，用法同 APatch。"
        )
    )

    fun byId(id: String?): Branch = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
