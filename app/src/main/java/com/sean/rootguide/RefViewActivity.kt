package com.sean.rootguide

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 内置资料查看页：网页版指南（html）和两张参考图（svg）共用。
 *
 * 用 WebView 而不是 ImageView：App 是纯原生实现、没引 SVG 渲染库，而 SVG 直接交给
 * Chromium 渲染既清晰又能双指放大。图按可视区宽度等比铺满，避免 1760px 宽的图
 * 在手机竖屏上先比屏幕大一圈再让人横着拖。
 *
 * 密排的决策图在手机上按宽度铺满后字只有 10px 出头，所以右上角加了个「放大」开关：
 * 切到 2.2 倍后图比屏幕宽，左右滑动看，点「适应屏幕」回到整幅。
 *
 * 资料都随 App 打包：站点要登录、用户又常装 AdAway 这类屏蔽模块，纯在线打开会撞登录页或白屏。
 * 右上角「在线打开」留给想拿最新版的人。
 */
class RefViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "ref_title"
        const val EXTRA_URL = "ref_url"
        const val EXTRA_ASSET = "ref_asset"

        /** 「放大」档的倍率：密排决策图按宽度铺满时字太小，2.2 倍后左右滑动看刚好。 */
        private const val ZOOM_STEP = 2.2f

        private const val NARROW_CSS = """
            @media (max-width:640px){
              .wrap{padding:20px 14px 64px!important}
              table{min-width:0!important}
              th{white-space:normal!important}
              th,td{padding:8px 10px!important;font-size:.84em!important}
              h1{font-size:1.45rem!important}
              h2{font-size:1.18rem!important;margin:32px 0 12px!important}
              h3{font-size:1.04rem!important}
              blockquote{padding:10px 12px!important}
              pre{padding:12px!important}
            }
        """
    }

    /** 1 = 适应屏幕宽度；ZOOM_STEP = 放大档（图比屏幕宽，左右滑）。 */
    private var zoom = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ref)
        Insets.applyTo(findViewById(R.id.rootView))

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val url = intent.getStringExtra(EXTRA_URL) ?: Links.GUIDE
        val asset = intent.getStringExtra(EXTRA_ASSET) ?: ""

        findViewById<TextView>(R.id.tvRefTitle).text = title
        findViewById<View>(R.id.tvRefClose).setOnClickListener { finish() }
        // 纯离线参考图（如 MagicRoot 决策图）没有线上页面：隐藏入口，别让人点了白屏。
        val online = findViewById<View>(R.id.tvRefOnline)
        if (url.isBlank()) {
            online.visibility = View.GONE
        } else {
            online.setOnClickListener { Links.openInBrowser(this, url) }
        }

        val web = findViewById<WebView>(R.id.webView)
        val zoomBtn = findViewById<TextView>(R.id.tvRefZoom)
        web.setBackgroundColor(getColor(R.color.bg))

        val isHtml = asset.endsWith(".html")
        val isMd = asset.endsWith(".md")
        with(web.settings) {
            javaScriptEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            // 一律按 viewport 的 device-width 排版，不做整页缩放。
            // 之前图页开着 overview（SVG 内嵌尺寸 1760×1420 / 1648×1606），WebView 会
            // 按原图宽排版再整体缩放，同一张图在不同分辨率的机器上大小完全不一样。
            loadWithOverviewMode = false
        }

        if (isHtml) {
            loadGuide(web, asset)
        } else if (isMd) {
            // 完整检测解决方案是纯文本 markdown：直接塞进 <pre> 阅读，不做渲染（87KB 字符串喂 WebView 无压力）。
            zoomBtn.visibility = View.GONE
            web.post { if (!isFinishing && !isDestroyed) loadMarkdown(web, asset) }
        } else {
            // 「放大 / 适应屏幕」只对图有意义，网页版不需要（正文本来就能上下滑着读）。
            zoomBtn.visibility = View.VISIBLE
            zoomBtn.setOnClickListener {
                zoom = if (zoom == 1f) ZOOM_STEP else 1f
                zoomBtn.setText(if (zoom == 1f) R.string.ref_zoom_in else R.string.ref_zoom_fit)
                web.post { if (!isFinishing && !isDestroyed) loadImage(web, asset) }
            }
            // 等 WebView 真正量到尺寸再排版：布局本身有边距，用屏幕宽高估会略微溢出。
            web.post { if (!isFinishing && !isDestroyed) loadImage(web, asset) }
        }
    }

    /**
     * 参考图按当前设备的实际显示区给死像素尺寸：
     *  · 竖屏（手机常态）：宽度铺满可视区，高度按原图比例算，字够大，长了上下滑；
     *  · 横屏 / 平板 / 折叠展开：整张图缩进一屏，不用来回拖。
     * 都是等比缩放，不拉伸；点了「放大」后图比屏幕宽，靠左右滑动看细节。
     */
    private fun loadImage(web: WebView, asset: String) {
        val dm = resources.displayMetrics
        val viewW = (if (web.width > 0) web.width else dm.widthPixels) / dm.density
        val viewH = (if (web.height > 0) web.height else dm.heightPixels) / dm.density
        val aspect = svgAspect(asset)
        var imgW = viewW * zoom
        var imgH = imgW / aspect
        if (zoom == 1f && viewW > viewH) {
            // 横屏 / 平板：整张图塞进一屏，留一点边剩着贴边。
            val maxH = viewH * 0.96f
            if (imgH > maxH) {
                imgH = maxH
                imgW = imgH * aspect
            }
        }
        web.loadDataWithBaseURL(
            "file:///android_asset/",
            imageHtml(asset, imgW, imgH),
            "text/html",
            "utf-8",
            null
        )
    }

    /** 读 SVG 的 viewBox 拿到原始宽高比（宽 / 高），读不到就按 1.4 兜底。 */
    private fun svgAspect(asset: String): Float {
        return try {
            val head = assets.open(asset).bufferedReader().use { it.readText() }
            val m = Regex("""viewBox="\s*[-0-9.]+\s+[-0-9.]+\s+([0-9.]+)\s+([0-9.]+)""").find(head)
            val w = m?.groupValues?.get(1)?.toFloatOrNull()
            val h = m?.groupValues?.get(2)?.toFloatOrNull()
            if (w != null && h != null && w > 0f && h > 0f) w / h else 1.4f
        } catch (t: Throwable) {
            1.4f
        }
    }

    /**
     * 指南原页面是给电脑屏排的（表格 min-width 560px），手机上会横着超出屏幕、字被切一半。
     * 这里只对 App 内这一份追加窄屏覆盖样式，不改线上那份的排版。
     *
     * 例外：单文件手册（hnubl 操作手册）内嵌了多张 base64 截图，整篇 2 MB+。把这么大的
     * 字符串经 JNI 喂给 loadDataWithBaseURL 会明显卡顿甚至 OOM；这类页面自带 viewport 和
     * 响应式样式，也用不上 [NARROW_CSS]。所以超过 512 KB 时直接按 assets 路径加载。
     */
    private fun loadGuide(web: WebView, asset: String) {
        val raw = assets.open(asset).bufferedReader().use { it.readText() }
        if (raw.length > 512 * 1024) {
            // 大手册：让 WebView 按路径自己读，别把 2 MB+ 字符串经 JNI 传过去
            web.settings.allowFileAccess = true
            web.settings.allowContentAccess = true
            web.loadUrl("file:///android_asset/$asset")
            return
        }
        val patched = raw.replaceFirst("</head>", "<style>$NARROW_CSS</style></head>")
        web.loadDataWithBaseURL(
            "file:///android_asset/",
            patched,
            "text/html",
            "utf-8",
            null
        )
    }

    /**
     * 完整检测解决方案（markdown）原样展示：转义 HTML 特殊字符后塞进 <pre>，深底浅字。
     * 不渲染表格/标题层级，作为可滚动的参考全文，用户在里面 Ctrl/搜 具体检测项名字即可。
     */
    private fun loadMarkdown(web: WebView, asset: String) {
        val raw = try {
            assets.open(asset).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            "无法读取：$asset"
        }
        val esc = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        web.loadDataWithBaseURL(
            "file:///android_asset/",
            """
            <!doctype html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html,body{margin:0;padding:14px 14px 64px;background:#0F1420;color:#cdd6e4;
                font-family:PingFang SC,Microsoft YaHei,sans-serif;font-size:14px;line-height:1.7}
              pre{white-space:pre-wrap;word-break:break-word;margin:0}
            </style>
            </head><body><pre>$esc</pre></body></html>
            """.trimIndent(),
            "text/html",
            "utf-8",
            null
        )
    }

    /**
     * 参考图按当前设备的分辨率给死像素尺寸（见调用处），只在竖屏时需要上下滚动。
     * 等比缩放，不拉伸；双指放大仍然可用。
     */
    private fun imageHtml(asset: String, widthPx: Float, heightPx: Float) = """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=yes">
        <style>
          html,body{margin:0;padding:0;background:#0F1420}
          body{width:${widthPx}px}
          img{display:block;width:${widthPx}px;height:${heightPx}px}
        </style>
        </head><body><img src="$asset"></body></html>
    """.trimIndent()
}
