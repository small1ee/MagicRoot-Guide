package com.sean.rootguide

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 外部资料入口。地址与内置图都收在这里，换链接只改这一处。
 *
 * [Ref.asset] 是随 App 打包的同一张图：参考图站点要联网、登录，且用户常装 AdAway 之类的
 * 屏蔽模块，纯在线打开会白屏。所以默认看内置版，[Ref.url] 留给「在线打开」拿最新版。
 */
object Links {

    /** 网页版《安卓 Root 使用者指南》 */
    const val GUIDE = "https://www.workbuddy.cn/space/d/J59lkmRHX1chuHTV9q3d9U"

    data class Ref(val title: String, val desc: String, val url: String, val asset: String)

    /** 标题旁「查看」指向的指南：内置一份，站上那份留给「在线打开」 */
    val GUIDE_REF = Ref(
        "安卓 Root 使用者指南",
        "选型、模块、排错，12 张速查表",
        GUIDE,
        "refs/guide.html"
    )

    /** hnubl 操作手册（荣耀 8E5 假回锁）：仅 8E5 平台支持，离线单文件 HTML（内嵌图，无需联网） */
    val HNUBL_REF = Ref(
        "hnubl 操作手册（8E5 假回锁）",
        "8E5（SM8850）机型专属：从备份识别、ABL 修补到刷 init_boot 的完整流程，含前提、确认与风险 · 单文件离线可看",
        "",
        "refs/hnubl_guide.html"
    )

    val REFS: List<Ref> = listOf(
        Ref(
            "Root 注意事项速览图",
            "假回锁、双挂载、Zygisk 双开：最容易踩的坑都在这张图上",
            "https://www.workbuddy.cn/space/d/clVpdncbJfbKU26s3uPamr",
            "refs/precautions.svg"
        ),
        Ref(
            "su 管理器选型决策图",
            "12 个分支怎么挑，一图看完",
            "https://www.workbuddy.cn/space/d/85jhsCUrPYYdHOj4qcpop4",
            "refs/branch_map.svg"
        ),
        // 第三张：本机（Magic8 Pro / MagicOS 11）实测的「三条线选一条」速览图。
        // 纯离线：没有对应的线上页面，所以 url 留空，查看页会自动隐藏「在线打开」。
        Ref(
            "magicroot决策速览",
            "要稳定走 01、想一劳永逸走 02（仅 8E5）、别指望 SUSFS 走 03：一图看清代价",
            "",
            "refs/magicroot_guide.svg"
        ),
        // 第四张：环境隐藏整体打法（三级判定 + 洋葱七层 + 三条铁律 + 高频翻车）。
        // 纯离线，没有对应的线上页面，url 留空。
        Ref(
            "环境隐藏 · 一图速览",
            "先定三级预期 → 穿洋葱七层 → 三条线路照抄 → 三条铁律：环境隐藏整体打法，依据《安卓 Root 使用者指南》v2.0 第 6 章",
            "",
            "refs/env_hide_overview.svg"
        )
    )

    fun openInBrowser(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    fun openRef(ctx: Context, ref: Ref) {
        ctx.startActivity(
            Intent(ctx, RefViewActivity::class.java)
                .putExtra(RefViewActivity.EXTRA_TITLE, ref.title)
                .putExtra(RefViewActivity.EXTRA_URL, ref.url)
                .putExtra(RefViewActivity.EXTRA_ASSET, ref.asset)
        )
    }
}
