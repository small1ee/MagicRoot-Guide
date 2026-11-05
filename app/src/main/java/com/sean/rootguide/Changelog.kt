package com.sean.rootguide

/**
 * App 内「本次更新」纯文本（不渲染 Markdown）。只写用户能感知的变化。
 */
object Changelog {

    const val TEXT = """
v1.20 · 2026-11-06
· 「风险应用」合并展示：静态黑名单 + LSP 模块仓库名单 + Xposed 元数据 + ROOT 管理器，统一成一条，命中即列全部包名。
· 新增「导出风险应用名单」：一键复制包名到剪贴板（也可分享），直接粘贴到隐藏应用列表（HMA / HMA-OSS / FuseFixer）的黑名单。
"""

    /** 标题行里那行小字，跟着版本走 */
    const val VERSION_LABEL = "v1.20 · 本次更新"
}
