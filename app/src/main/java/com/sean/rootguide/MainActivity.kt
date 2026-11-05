package com.sean.rootguide

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页，三 Tab：
 *  - 一、root 前必看：使用者指南 + 4 张速查图 + hnubl 操作手册。
 *  - 二、状态检测：合并为一块（root 状态 + 8E5 机型 / SUSFS / 框架组合 / 模块完整性 / 启动原因），
 *       去掉了 ①②③④ 序号；启动原因改为按钮触发（无需 root，本地 shell 拉日志存本地）。
 *       第二块为「风控环境检测」（对接《春秋检测项解决方案》answer_zh.md）。
 *  - 三、工具：① 管理器及其分支 ② 生成清单（开 SUSFS→vector+NeoZygisk）③ 重启原因监控（按钮拉取）④ 框架恢复。
 *
 * 分支识别逻辑与之前一致（HIGH 直接给结论、MEDIUM/NONE 让手选）；只是把「管理器分支」板块从首页中段
 * 搬到了第三个 Tab，并把四项目检测独立成第二个 Tab。
 */
class MainActivity : AppCompatActivity() {

    // —— 顶部 Tab 切换 ——
    private lateinit var tabPre: View
    private lateinit var tabDetect: View
    private lateinit var tabPost: View
    private lateinit var tabPreContent: View
    private lateinit var tabDetectContent: View
    private lateinit var tabPostContent: View
    private lateinit var tabPreLabel: TextView
    private lateinit var tabDetectLabel: TextView
    private lateinit var tabPostLabel: TextView
    private lateinit var tabPreIndicator: View
    private lateinit var tabDetectIndicator: View
    private lateinit var tabPostIndicator: View

    // —— 分支选择器（在第三个 Tab）——
    private lateinit var spinnerBranch: Spinner
    private lateinit var spinnerBox: View
    private lateinit var tvProbe: TextView
    private lateinit var tvProbeDetail: TextView
    private lateinit var tvProbeMore: TextView
    private lateinit var tvAuthorize: TextView
    private lateinit var tvBranchStatus: TextView
    private lateinit var tvBranchNote: TextView
    private lateinit var tvGuidance: TextView
    private lateinit var tvPickManual: TextView
    private lateinit var tvManualHint: TextView

    // —— 状态检测（第二个 Tab）——
    private lateinit var tvDetect8e5: TextView
    private lateinit var tvDetectSusfs: TextView
    private lateinit var tvDetectFramework: TextView
    private lateinit var tvDetectIntegrity: TextView
    private lateinit var tvDetectBoot: TextView
    private lateinit var tvEnvSummary: TextView
    private lateinit var btnEnvDetect: TextView
    private lateinit var btnEnvFull: TextView
    private lateinit var envCheckContainer: LinearLayout

    // —— root 后（第三个 Tab）——
    private lateinit var tvPostSusfsNote: TextView
    private lateinit var btnRecovery: View

    // —— 重启日志持续采集（开始/停止开关）——
    private val rebootLogRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val rebootLogStop = java.util.concurrent.atomic.AtomicBoolean(false)
    private lateinit var btnPullBootDetect: Button
    private lateinit var swLowInterference: com.google.android.material.switchmaterial.SwitchMaterial
    @Volatile private var lowInterference = true

    /** 最近一次检测到的设备事实；没 root 时为 null */
    private var detectedFacts: BranchDetect.Facts? = null
    /** 完整事实（含检测四项的字段），即使没 root 也尽量保留能拿到的部分 */
    private var currentFacts: BranchDetect.Facts? = null
    /** 自动识别出的分支 id；手选时用来判断「设备事实还算不算数」 */
    private var detectedBranchId: String? = null
    /** 没拿到 root 时的通用建议（与分支无关），检测到分支后清空 */
    private var noRootGuidance: List<String>? = null
    /** 引导区标题由检测结论决定：「先给 App 授权」和「拿到 root 之前」不是一回事 */
    private var guidanceTitle: String = ""
    /** 本次检测到的管理器包名，用于「去管理器授权」按钮 */
    private var managerPkg: String? = null
    /** 最近一次识别到的分支显示名，供「框架组合」一项展示 */
    private var detectedBranchLabel: String = "未识别"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Insets.applyTo(findViewById(R.id.rootView))

        // 诊断文案里的「本 App 包名」用运行时真实值，避免与 applicationId 改版不同步
        RootProbe.appPackage = packageName
        // 环境检测需要 Context（枚举 Xposed 模块 / 读 LSP 名单缓存）
        EnvDetector.init(this)

        bindViews()
        setupTabs()
        setupSpinner()
        setupActions()

        // 一进来就认分支：用户不该为了「知道自己在用什么」先点一次按钮
        runProbe()
        maybeAutoResume()
    }

    /**
     * 上次采集被进程终止（一键修复重启 zygote / 手机重启 / 被杀）时，落盘的 active 会保持 true；
     * 重新打开 App 时自动续采，避免「修复后采集就断了」。
     */
    private fun maybeAutoResume() {
        val sp = getSharedPreferences("rebootlog_state", MODE_PRIVATE)
        if (!sp.getBoolean("active", false)) return
        val low = sp.getBoolean("low", true)
        swLowInterference.isChecked = low
        lowInterference = low
        btnPullBootDetect.post {
            if (!rebootLogRunning.get()) {
                Toast.makeText(this, "已恢复上次的持续采集", Toast.LENGTH_SHORT).show()
                toggleRebootLog(btnPullBootDetect, tvDetectBoot)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        RebootLogSink.marker("App 回到前台")
    }

    override fun onStop() {
        super.onStop()
        RebootLogSink.marker("App 退到后台 / 主界面不可见（前台服务继续采集）")
    }

    override fun onDestroy() {
        // 仅 App 真正退出（不是切后台）时停止采集；切后台由前台服务保活
        if (isFinishing) {
            RebootLogSink.marker("App 退出（isFinishing）")
            rebootLogStop.set(true)
            RebootLogService.stop(this)
            RebootLogService.stopRequested = false
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 初始化

    private fun bindViews() {
        tabPre = findViewById(R.id.tabPre)
        tabDetect = findViewById(R.id.tabDetect)
        tabPost = findViewById(R.id.tabPost)
        tabPreContent = findViewById(R.id.tabPreContent)
        tabDetectContent = findViewById(R.id.tabDetectContent)
        tabPostContent = findViewById(R.id.tabPostContent)
        tabPreLabel = findViewById(R.id.tabPreLabel)
        tabDetectLabel = findViewById(R.id.tabDetectLabel)
        tabPostLabel = findViewById(R.id.tabPostLabel)
        tabPreIndicator = findViewById(R.id.tabPreIndicator)
        tabDetectIndicator = findViewById(R.id.tabDetectIndicator)
        tabPostIndicator = findViewById(R.id.tabPostIndicator)

        spinnerBranch = findViewById(R.id.spinnerBranch)
        spinnerBox = findViewById(R.id.spinnerBox)
        tvProbe = findViewById(R.id.tvProbe)
        tvProbeDetail = findViewById(R.id.tvProbeDetail)
        tvProbeMore = findViewById(R.id.tvProbeMore)
        tvAuthorize = findViewById(R.id.tvAuthorize)
        tvBranchStatus = findViewById(R.id.tvBranchStatus)
        tvBranchNote = findViewById(R.id.tvBranchNote)
        tvGuidance = findViewById(R.id.tvGuidance)
        tvPickManual = findViewById(R.id.tvPickManual)
        tvManualHint = findViewById(R.id.tvManualHint)

        tvDetect8e5 = findViewById(R.id.tvDetect8e5)
        tvDetectSusfs = findViewById(R.id.tvDetectSusfs)
        tvDetectFramework = findViewById(R.id.tvDetectFramework)
        tvDetectIntegrity = findViewById(R.id.tvDetectIntegrity)
        tvDetectBoot = findViewById(R.id.tvDetectBoot)

        tvPostSusfsNote = findViewById(R.id.tvPostSusfsNote)
        btnRecovery = findViewById(R.id.btnRecovery)
        btnPullBootDetect = findViewById(R.id.btnPullBootDetect)
        swLowInterference = findViewById(R.id.swLowInterference)

        findViewById<TextView>(R.id.tvChangelog).apply {
            text = Changelog.VERSION_LABEL
            setOnClickListener { showChangelog() }
        }

        // —— Tab1 参考资料（4 份）——
        val refs = Links.REFS
        findViewById<TextView>(R.id.tvRefGuideTitle).text = Links.GUIDE_REF.title
        findViewById<TextView>(R.id.tvRefGuideDesc).text = Links.GUIDE_REF.desc
        findViewById<View>(R.id.cardRefGuide).setOnClickListener { Links.openRef(this, Links.GUIDE_REF) }
        findViewById<TextView>(R.id.tvRef1Title).text = refs[0].title
        findViewById<TextView>(R.id.tvRef1Desc).text = refs[0].desc
        findViewById<View>(R.id.cardRef1).setOnClickListener { Links.openRef(this, refs[0]) }
        findViewById<TextView>(R.id.tvRef2Title).text = refs[1].title
        findViewById<TextView>(R.id.tvRef2Desc).text = refs[1].desc
        findViewById<View>(R.id.cardRef2).setOnClickListener { Links.openRef(this, refs[1]) }
        if (refs.size > 2) {
            findViewById<TextView>(R.id.tvRef3Title).text = refs[2].title
            findViewById<TextView>(R.id.tvRef3Desc).text = refs[2].desc
            findViewById<View>(R.id.cardRef3).setOnClickListener { Links.openRef(this, refs[2]) }
        }
        if (refs.size > 3) {
            findViewById<TextView>(R.id.tvRef4Title).text = refs[3].title
            findViewById<TextView>(R.id.tvRef4Desc).text = refs[3].desc
            findViewById<View>(R.id.cardRef4).setOnClickListener { Links.openRef(this, refs[3]) }
        }

        // —— Tab3 hnubl 手册 ——
        findViewById<TextView>(R.id.tvHnublTitle).text = Links.HNUBL_REF.title
        findViewById<TextView>(R.id.tvHnublDesc).text = Links.HNUBL_REF.desc
        findViewById<View>(R.id.cardHnubl).setOnClickListener { Links.openRef(this, Links.HNUBL_REF) }
    }

    private fun setupTabs() {
        tabPre.setOnClickListener { selectTab(0) }
        tabDetect.setOnClickListener { selectTab(1) }
        tabPost.setOnClickListener { selectTab(2) }
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        val (txtActive, txtInactive) = getColor(R.color.text_primary) to getColor(R.color.text_secondary)
        val tabs = listOf(
            Triple(tabPre, tabPreLabel, tabPreIndicator),
            Triple(tabDetect, tabDetectLabel, tabDetectIndicator),
            Triple(tabPost, tabPostLabel, tabPostIndicator)
        )
        val contents = listOf(tabPreContent, tabDetectContent, tabPostContent)
        tabs.forEachIndexed { i, (container, label, indicator) ->
            val on = i == index
            container.isSelected = on
            label.setTextColor(if (on) txtActive else txtInactive)
            indicator.setBackgroundResource(
                if (on) R.drawable.bg_tab_indicator_active else R.drawable.bg_tab_indicator_inactive
            )
            contents[i].visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun setupSpinner() {
        spinnerBranch.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Branches.ALL.map { it.label }
        )
        val saved = Branches.ALL.indexOfFirst { it.id == Prefs.getBranchId(this) }
        spinnerBranch.setSelection(if (saved >= 0) saved else 0)
        showNote()

        // 箭头与整个下拉条都是可点击区：点箭头也能展开下拉，避免「箭头指向了却点不动」的误解
        spinnerBox.setOnClickListener { spinnerBranch.performClick() }

        spinnerBranch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) =
                showNote()

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupActions() {
        findViewById<View>(R.id.tvGuideLink).setOnClickListener {
            Links.openRef(this, Links.GUIDE_REF)
        }
        findViewById<View>(R.id.btnProbe).setOnClickListener { runProbe() }

        // 重启日志：只在「状态检测」标签保留入口（开始 / 停止 开关，持续采集直到手动停止或重启）
        btnPullBootDetect.setOnClickListener { toggleRebootLog(btnPullBootDetect, tvDetectBoot) }
        swLowInterference.setOnCheckedChangeListener { _, checked ->
            lowInterference = checked
            RebootLogSink.marker("低干扰模式：" + (if (checked) "开" else "关"))
        }
        findViewById<View>(R.id.tvOpenAppSettings).setOnClickListener { openAppSettings() }
        findViewById<View>(R.id.tvManualMark).setOnClickListener { showManualMarkDialog() }

        tvEnvSummary = findViewById(R.id.tvEnvSummary)
        btnEnvDetect = findViewById(R.id.btnEnvDetect)
        btnEnvFull = findViewById(R.id.btnEnvFull)
        envCheckContainer = findViewById(R.id.envCheckContainer)
        btnEnvDetect.setOnClickListener { runEnvDetection() }
        btnEnvFull.setOnClickListener {
            startActivity(
                Intent(this, RefViewActivity::class.java)
                    .putExtra(RefViewActivity.EXTRA_TITLE, "完整检测解决方案（135 项）")
                    .putExtra(RefViewActivity.EXTRA_URL, "")
                    .putExtra(RefViewActivity.EXTRA_ASSET, "detector/answer_zh.md")
            )
        }
        findViewById<View>(R.id.btnExportRisk).setOnClickListener { exportRiskList() }
        tvProbeMore.setOnClickListener { toggleProbeDetail() }
        tvAuthorize.setOnClickListener { openManagerToAuthorize() }
        tvPickManual.setOnClickListener {
            showBranchEditing(getString(R.string.branch_status_editing))
        }
        tvManualHint.setOnClickListener { Links.openRef(this, Links.REFS[0]) }

        findViewById<Button>(R.id.btnNext).setOnClickListener {
            val branch = selectedBranch()
            Prefs.saveBranchId(this, branch.id)
            startActivity(
                Intent(this, PlanActivity::class.java)
                    .putExtra(PlanActivity.EXTRA_BRANCH_ID, branch.id)
            )
        }

        findViewById<Button>(R.id.btnInstall).setOnClickListener {
            Prefs.saveBranchId(this, selectedBranch().id)
            startActivity(Intent(this, ModuleInstallActivity::class.java))
        }

        btnRecovery.setOnClickListener {
            // 检测页认出哪个框架启用就预选哪个：Vector 启用且 LSPosed 未启用 → 预选 Vector，否则 LSPosed
            val active = currentFacts?.frameworkFindings
                ?.filter { it.installed && !it.disabled }
                ?.map { it.name }
                .orEmpty()
            val defaultFw = if (active.contains("Vector") && !active.contains("LSPosed"))
                Recovery.FW_VECTOR else Recovery.FW_LSPOSED
            startActivity(
                Intent(this, RecoveryActivity::class.java)
                    .putExtra(RecoveryActivity.EXTRA_SUSFS_ON, Prefs.getSusfsOn(this))
                    .putExtra(RecoveryActivity.EXTRA_IS_8E5, currentFacts?.is8E5 ?: false)
                    .putExtra(RecoveryActivity.EXTRA_FRAMEWORK, defaultFw)
            )
        }
    }

    // ---------------------------------------------------------------- 检测

    private fun runProbe() {
        showBranchProbing()
        currentFacts = null
        detectedFacts = null
        detectedBranchId = null
        noRootGuidance = null
        guidanceTitle = ""
        detectedBranchLabel = "未识别"
        managerPkg = null
        tvProbe.text = getString(R.string.probing)
        tvProbe.setTextColor(getColor(R.color.text_secondary))
        tvProbeDetail.visibility = View.GONE
        tvProbeMore.text = getString(R.string.probe_detail)
        tvAuthorize.visibility = View.GONE
        tvGuidance.visibility = View.GONE

        // 先清空检测四项，等结果回来再填
        tvDetect8e5.text = getString(R.string.detect_8e5_need_root)
        tvDetectSusfs.text = getString(R.string.detect_susfs_need_root)
        tvDetectFramework.text = getString(R.string.detect_framework_need_root)
        tvDetectIntegrity.text = getString(R.string.detect_integrity_need_root)
        tvPostSusfsNote.text = getString(R.string.post_susfs_need_root)
        tvDetectBoot.text = getString(R.string.boot_idle)

        Thread {
            // 无权限预检测：PackageManager 查已装管理器（manifest <queries> 内），
            // 这样即使 su 未授权也能知道「系统层面已 root、装的是哪个管理器」
            val managerPkgs = installedManagerPackages()
            val result = runCatching { RootProbe.probe(managerPkgs) }.getOrNull()
            runOnUiThread {
                if (result == null) {
                    tvProbe.text = "检测过程出错了，请重试。"
                    tvProbe.setTextColor(getColor(R.color.warn))
                    showBranchEditing(getString(R.string.branch_status_unknown))
                } else {
                    render(result)
                }
            }
        }.start()
    }

    /** 用 PackageManager 查已安装的已知管理器包名；不需要任何权限 */
    private fun installedManagerPackages(): List<String> {
        val pm = packageManager
        return BranchDetect.MANAGER_PKGS_KEYS.filter { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    private fun toggleProbeDetail() {
        val open = tvProbeDetail.visibility != View.VISIBLE
        tvProbeDetail.visibility = if (open) View.VISIBLE else View.GONE
        tvProbeMore.text = getString(if (open) R.string.probe_detail_hide else R.string.probe_detail)
    }

    // ---------------------------------------------------------------- 风控环境检测（对接春秋检测项解决方案）

    private fun severityColor(sev: EnvDetector.Severity): Int = when (sev) {
        EnvDetector.Severity.PASS -> R.color.ok
        EnvDetector.Severity.WARN -> R.color.warn
        EnvDetector.Severity.FAIL -> R.color.required
        EnvDetector.Severity.INFO -> R.color.accent
        EnvDetector.Severity.SKIP -> R.color.optional
    }

    private fun severityTag(sev: EnvDetector.Severity): String = when (sev) {
        EnvDetector.Severity.PASS -> "通过"
        EnvDetector.Severity.WARN -> "警告"
        EnvDetector.Severity.FAIL -> "不通过"
        EnvDetector.Severity.INFO -> "提示"
        EnvDetector.Severity.SKIP -> "需原生引擎"
    }

    private fun runEnvDetection() {
        tvEnvSummary.text = "检测中…"
        btnEnvDetect.isEnabled = false
        Thread {
            val results = EnvDetector.runAll()
            runOnUiThread {
                btnEnvDetect.isEnabled = true
                tvEnvSummary.text = EnvDetector.summarize(results)
                buildEnvRows(results)
            }
        }.start()
    }

    /** 提示说明规范：风控检测结果按严重度套用对应 Callout 底色 */
    private fun calloutBgFor(sev: EnvDetector.Severity): Int = when (sev) {
        EnvDetector.Severity.PASS -> R.drawable.bg_callout_success
        EnvDetector.Severity.WARN -> R.drawable.bg_callout_warn
        EnvDetector.Severity.FAIL -> R.drawable.bg_callout_danger
        EnvDetector.Severity.INFO -> R.drawable.bg_callout_info
        EnvDetector.Severity.SKIP -> R.drawable.bg_callout_info
    }

    private fun buildEnvRows(results: List<EnvDetector.Result>) {
        envCheckContainer.removeAllViews()
        // 只显示未通过/警告/提示：通过项不显示；未通过置顶，其次警告，再提示
        val shown = results
            .filter { it.severity != EnvDetector.Severity.PASS }
            .sortedBy {
                when (it.severity) {
                    EnvDetector.Severity.FAIL -> 0
                    EnvDetector.Severity.WARN -> 1
                    EnvDetector.Severity.INFO -> 2
                    else -> 3
                }
            }
        val pad = (resources.displayMetrics.density * 12).toInt()
        val gap = (resources.displayMetrics.density * 8).toInt()
        if (shown.isEmpty()) {
            val ok = TextView(this).apply {
                setTextColor(getColor(R.color.callout_success))
                textSize = 13f
                text = "✓ 未发现异常项（全部通过）"
                setBackgroundResource(R.drawable.bg_callout_success)
                setPadding(pad, pad, pad, pad)
            }
            envCheckContainer.addView(ok)
            return
        }
        for (r in shown) {
            val tv = TextView(this).apply {
                setTextColor(getColor(severityColor(r.severity)))
                textSize = 13f
                setBackgroundResource(calloutBgFor(r.severity))
                setPadding(pad, pad, pad, pad)
                setLineSpacing(resources.displayMetrics.density * 2, 1f)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = gap
                layoutParams = lp
                val sb = StringBuilder()
                sb.append("[${severityTag(r.severity)}] ${r.check.title}")
                if (r.evidence.isNotBlank()) sb.append("\n${r.evidence}")
                if (r.isProblem || r.severity == EnvDetector.Severity.SKIP) {
                    sb.append("\n解决：${r.check.solution}")
                }
                text = sb.toString()
                setTextIsSelectable(true)
            }
            envCheckContainer.addView(tv)
        }
    }

    /** 导出「风险应用」合并名单：复制到剪贴板，并提供分享，方便直接粘贴到隐藏应用列表（HMA）黑名单。 */
    private fun exportRiskList() {
        val pkgs = EnvDetector.lastRiskApps
        if (pkgs.isEmpty()) {
            Toast.makeText(this, "请先运行「运行风控环境检测」，再导出名单", Toast.LENGTH_SHORT).show()
            return
        }
        val text = pkgs.joinToString("\n")
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("风险应用名单", text))
        android.app.AlertDialog.Builder(this)
            .setTitle("风险应用名单（${pkgs.size}）")
            .setMessage("已复制 ${pkgs.size} 个包名到剪贴板，可直接粘贴到隐藏应用列表（HMA / HMA-OSS / FuseFixer）的黑名单。\n\n$text")
            .setPositiveButton("分享") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "分享风险应用名单"))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 跳到管理器让用户自己加白名单。
     *
     * 这里只能「跳过去」不能「代授」——su 授权是管理器的私有数据（/data/adb/ksu/.allowlist），
     * 本 App 恰恰还没有 root，改不了。能做的就是把用户送到该点的那一页。
     */
    private fun openManagerToAuthorize() {
        val pkg = managerPkg
        val intent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.probe_authorize)
                .setMessage(getString(R.string.probe_authorize_not_found))
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }

        runCatching { startActivity(intent) }.onFailure {
            AlertDialog.Builder(this)
                .setTitle(R.string.probe_authorize)
                .setMessage(getString(R.string.probe_authorize_not_found))
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun render(result: ProbeResult) {
        currentFacts = result.facts
        tvProbe.text = result.summary
        tvProbe.setTextColor(getColor(if (result.hasRoot) R.color.ok else R.color.warn))
        tvProbeDetail.text =
            if (result.alert.isEmpty()) result.text else "⚠ ${result.alert}\n\n${result.text}"

        // 「有 root 但本 App 没被授权」时才给直达入口：真没 root 时跳管理器没意义
        managerPkg = result.facts.managerPackages.firstOrNull()
        tvAuthorize.visibility =
            if (result.suState == SuState.DENIED && managerPkg != null) View.VISIBLE else View.GONE
        guidanceTitle = result.guidanceTitle

        if (result.facts.moduleIds.isNotEmpty()) {
            Prefs.saveInstalledModules(this, result.facts.moduleIds)
        }
        // SUSFS 状态缓存给清单页（决定推 vector+NeoZygisk 还是默认 Zygisk Next）
        if (result.hasRoot) Prefs.saveSusfsOn(this, result.facts.susfsOn)

        val branch = result.suggestedBranchId?.let { Branches.byId(it) }
        detectedFacts = if (result.hasRoot) result.facts else null
        detectedBranchId = if (result.confidence == BranchDetect.Confidence.HIGH) branch?.id else null
        detectedBranchLabel = branch?.label ?: "未识别"
        noRootGuidance = if (result.hasRoot) null else result.guidance

        when {
            !result.hasRoot -> {
                // 没拿到 su 权限，但可能已从管理器包名认出分支候选
                val hint = result.suggestedBranchId
                when {
                    result.confidence != BranchDetect.Confidence.NONE && hint != null -> {
                        selectBranch(hint)
                        showBranchEditing(getString(R.string.branch_status_no_auth, Branches.byId(hint).label))
                    }
                    // 检测到管理器，但包名有歧义（如 BakaSU/FinaSU 共用）或多管理器：明确提示无法确定
                    result.facts.managerPackages.isNotEmpty() ->
                        showBranchEditing(getString(R.string.branch_status_ambiguous))
                    else ->
                        showBranchEditing(getString(R.string.branch_status_no_root))
                }
            }

            branch != null && result.confidence == BranchDetect.Confidence.HIGH -> {
                showBranchAuto(branch)
                Prefs.saveBranchId(this, branch.id)
            }

            branch != null && result.confidence == BranchDetect.Confidence.MEDIUM -> {
                selectBranch(branch.id)
                showBranchEditing(getString(R.string.branch_status_confirm, branch.label))
                Prefs.saveBranchId(this, branch.id)
            }

            else ->
                showBranchEditing(getString(R.string.branch_status_unknown))
        }

        // —— 状态检测（第二个 Tab）——
        renderDetection(result.facts, result.hasRoot)
        renderBoot()

        // —— root 后（第三个 Tab）——
        renderPostNote(result.facts, result.hasRoot)

        showNote()
    }

    // ---------------------------------------------------------------- 状态检测渲染

    private fun renderDetection(facts: BranchDetect.Facts?, hasRoot: Boolean) {
        // ① 机型（8E5 判断）
        tvDetect8e5.text = when {
            facts == null || !hasRoot -> getString(R.string.detect_8e5_need_root)
            facts.is8E5 -> getString(R.string.detect_8e5_yes)
            else -> getString(R.string.detect_8e5_no)
        }

        // ② SUSFS 开关
        tvDetectSusfs.text = when {
            facts == null || !hasRoot -> getString(R.string.detect_susfs_need_root)
            facts.susfsOn -> getString(R.string.detect_susfs_on)
            else -> getString(R.string.detect_susfs_off)
        }

        // ③ 框架组合
        tvDetectFramework.text = when {
            facts == null || !hasRoot -> getString(R.string.detect_framework_need_root)
            else -> buildString {
                append("体系/分支：$detectedBranchLabel\n")
                append("注入引擎：${facts.zygiskEngine.ifBlank { "未识别" }}\n")
                append("挂载引擎：${if (facts.mountProvider.isNotBlank()) facts.mountProvider else "未知"}\n")
                append("内置 Zygisk：${if (facts.hasBuiltinZygisk) "是" else "否"}\n")
                append("Sui：${if (facts.hasSui) "是" else "否"}\n")
                val mods = facts.moduleIds
                append(
                    "已装模块：${if (mods.isNotEmpty()) "${mods.size} 个（${mods.joinToString("、")}）" else "0 个"}"
                )
            }
        }

        // ④ 模块完整性 / 反向异常
        tvDetectIntegrity.text = when {
            facts == null || !hasRoot -> getString(R.string.detect_integrity_need_root)
            else -> buildString {
                facts.frameworkFindings.forEach { f ->
                    val status = when {
                        !f.installed -> "未安装"
                        f.disabled -> "⚠ 已禁用（disable 标记）"
                        else -> "已启用"
                    }
                    append("· ${f.name}：$status\n")
                }
                // 框架管理器本体（App）：模块在但管理器 APK 没装/被停用 → 点图标进不去界面
                if (facts.managerAppFindings.isNotEmpty()) {
                    append("\n${getString(R.string.detect_manager_app_title)}\n")
                    facts.managerAppFindings.forEach { m ->
                        val status = when {
                            !m.installed -> "⚠ 未安装"
                            m.disabled -> "⚠ 被停用（enabled=0）"
                            else -> "已安装"
                        }
                        append("· ${m.framework}${m.role}：${m.pkg} → $status\n")
                    }
                }
                if (facts.conflicts.isNotEmpty()) {
                    append("\n${getString(R.string.detect_conflicts_title)}\n")
                    facts.conflicts.forEach { append("  ⚠ $it\n") }
                } else if (facts.frameworkFindings.any { it.installed }) {
                    append("\n${getString(R.string.detect_integrity_ok)}")
                } else {
                    append("\n未检测到上述框架模块（LSPosed / Vector / Zygisk Next / NeoZygisk / YukiZygisk），管理器本体无需核对")
                }
            }.trim()
        }
    }

    /**
     * 启动 / 重启原因：不再在检测阶段自动读 boot_id（那需要 root）。
     * 改为按钮触发，由 [pullRebootLog] 通过本地 shell 采集并保存到本地文件。
     * 这里只把两处结果区复位为引导文案。
     */
    private fun renderBoot() {
        val hint = getString(R.string.boot_idle)
        tvDetectBoot.text = hint
        tvDetectBoot.setTextColor(getColor(R.color.text_secondary))
    }

    /**
     * 开始 / 停止开关：
     *  - 未运行：启动持续采集（首轮全量 + 每秒轮询），按钮变「停止采集」；
     *  - 运行中：置停止标志，当前项采完后退出。
     */
    private fun toggleRebootLog(btn: Button, target: TextView) {
        if (rebootLogRunning.get()) {
            RebootLogSink.marker("用户点击停止采集")
            rebootLogStop.set(true)
            btn.text = "正在停止…"
            return
        }
        if (!rebootLogRunning.compareAndSet(false, true)) return
        rebootLogStop.set(false)
        btn.text = getString(R.string.boot_stop_button)
        // 记录「采集中」，若被 zygote 重启/手机重启杀掉进程，下次打开可自动续采
        getSharedPreferences("rebootlog_state", MODE_PRIVATE)
            .edit().putBoolean("active", true).putBoolean("low", lowInterference).apply()
        // 后台保活：申请通知 / 悬浮窗权限，并启动前台服务（切后台不被杀）
        ensureRebootLogPermissions()
        RebootLogService.start(this)
        Thread {
            try {
                runRebootMonitor(target)
            } finally {
                rebootLogRunning.set(false)
                rebootLogStop.set(false)
                getSharedPreferences("rebootlog_state", MODE_PRIVATE).edit().putBoolean("active", false).apply()
                RebootLogService.stop(this)
                RebootLogService.stopRequested = false
                runOnUiThread {
                    btnPullBootDetect.text = getString(R.string.boot_pull_button)
                }
            }
        }.start()
    }

    /** 手动打点：把外部操作（如 SukiSU 软重启）写进当前采集日志，便于和重启记录对齐 */
    private fun showManualMarkDialog() {
        if (!RebootLogSink.isActive()) {
            Toast.makeText(this, "当前没有在采集：先点「开始持续采集」再打点", Toast.LENGTH_SHORT).show()
            return
        }
        val presets = arrayOf("点了 SukiSU 软重启", "手动重启手机", "改了 SuSFS / 框架设置", "自定义…")
        AlertDialog.Builder(this)
            .setTitle("手动打点")
            .setItems(presets) { _, which ->
                if (which == 3) showCustomMarkDialog() else doMark(presets[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomMarkDialog() {
        if (!RebootLogSink.isActive()) return
        val input = EditText(this).apply {
            hint = "输入要记录的操作"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("自定义标记")
            .setView(input)
            .setPositiveButton("写入") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) doMark(t)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doMark(text: String) {
        if (RebootLogSink.marker("手动标记：" + text)) {
            Toast.makeText(this, "已写入标记", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "当前没有在采集", Toast.LENGTH_SHORT).show()
        }
    }

    /** 打开本应用的系统设置页（后台活动 / 自启动 / 电池优化 都在这里） */
    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }

    /** 后台采集所需权限：通知（常驻通知）+ 悬浮窗（可选，用于显示状态 / 停止） */
    private fun ensureRebootLogPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101) }
        }
        val sp = getSharedPreferences("rebootlog_state", MODE_PRIVATE)
        if (!android.provider.Settings.canDrawOverlays(this) && !sp.getBoolean("overlay_prompted", false)) {
            sp.edit().putBoolean("overlay_prompted", true).apply()
            AlertDialog.Builder(this)
                .setTitle("悬浮窗权限（可选）")
                .setMessage("开启后可在屏幕角落显示采集状态并随时点「停止」；不开也会用通知 + 前台服务在后台继续采集。")
                .setPositiveButton("去开启") { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
                .setNegativeButton("暂不", null)
                .show()
        }
    }

    /**
     * 持续采集：首轮采全量并落盘；随后每秒采动态项（boot_id / uptime，若有 root 再加 dmesg / logcat），
     * 直到手动停止或 boot_id 变化（手机重启）。每一项都立即 flush + fsync，突然重启也只丢正在采的那一项。
     */
    private fun runRebootMonitor(target: TextView) {
        val started = formatNow()
        val low = lowInterference
        val sink = RebootLogSink(this).also {
            it.open(
                "========== 系统重启日志 · 持续采集 ==========\n" +
                    "开始时间：$started\n" +
                    "模式：" + (if (low) "低干扰（间隔 0.5s；未 root 不调 su；已 root 每 3 轮采一次）" else "标准（间隔 1s）") + "\n" +
                    "每项实时落档（flush+fsync）；再次点击按钮停止；boot_id 变化（重启）自动停止\n\n"
            )
            RebootLogSink.setActive(it)
        }
        val baselineBootId = runSh("cat /proc/sys/kernel/random/boot_id").trim()
        val rooted = java.util.concurrent.atomic.AtomicBoolean(detectRoot())
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        RebootLogSink.marker(
            "用户点击开始持续采集｜模式=" + (if (low) "低干扰" else "标准") +
                "｜root=" + (if (rooted.get()) "有" else "无") +
                "｜boot_id=" + baselineBootId.take(8)
        )
        // 后台轻量复检 root：一旦拿到就置位，采样循环下一轮立即切 su；检测本身不阻塞 0.5s 轮询
        Thread {
            while (!rooted.get() && !finished.get() &&
                !(rebootLogStop.get() || RebootLogService.stopRequested)
            ) {
                try {
                    Thread.sleep(500)
                } catch (_: Exception) {
                    break
                }
                if (finished.get() || (rebootLogStop.get() || RebootLogService.stopRequested)) break
                if (detectRoot()) rooted.set(true)
            }
        }.start()

        val ui = StringBuilder()   // 界面只显示「最近一轮」，文件保留全部历史
        var pass = 0
        var samples = 0
        var busy: String? = null

        fun publish(finalNote: String? = null) {
            val t = buildString {
                append("========== 系统重启日志 · 持续采集 ==========\n")
                append("开始：").append(started).append("\n")
                append("状态：")
                if (finalNote != null) {
                    append(finalNote)
                } else {
                    append("运行中 · 第 ").append(pass).append(" 轮 · 已写 ").append(samples).append(" 项")
                    append(" · root=").append(if (rooted.get()) "有" else "无")
                    append(" · ").append(if (low) "低干扰" else "标准")
                    if (busy != null) append(" · ⏳ ").append(busy)
                }
                append("\n\n").append(ui)
            }
            // 悬浮窗滚动窗口：显示本轮到目前为止的日志尾部
            val overlayText = buildString {
                append("第 ").append(pass).append(" 轮 · 已写 ").append(samples).append(" 项 · ")
                    .append(if (rooted.get()) "root" else "shell").append("\n")
                append(ui)
            }
            RebootLogService.updateLog(
                if (overlayText.length > 1600) overlayText.substring(overlayText.length - 1600) else overlayText
            )
            runOnUiThread {
                target.text = t
                target.setTextColor(getColor(R.color.text_primary))
                scrollParentToBottom(target)
            }
        }

        fun collect(rooted: Boolean, label: String, cmd: String, timeoutSec: Long) {
            busy = label
            publish()
            val out = if (rooted) SuExec.run(cmd, timeoutSec).text() else runSh(cmd, timeoutSec)
            val content = if (out.isBlank()) "(无输出 / 无读取权限)" else out.trimEnd()
            ui.append("---- ").append(label).append(" ----\n").append(content).append("\n\n")
            sink.append("\n[" + formatNow() + "] " + label + "\n" + content + "\n")
            samples++
            busy = null
            publish()
        }

        try {
            // —— 首轮全量（静态属性 + 完整日志）——
            pass = 1
            ui.append("【首轮全量 · ").append(formatNow()).append("】\n")
            sink.append("\n========== 首轮全量 " + formatNow() + " ==========\n")

            val baseItems = listOf(
                "boot_id（判断真假重启）" to "cat /proc/sys/kernel/random/boot_id",
                "内核命令行 /proc/cmdline（AP_S_PANIC 判据）" to "cat /proc/cmdline",
                "panic 值（-1 = 非内核 panic）" to "cat /proc/sys/kernel/panic",
                "距上次启动时长 /proc/uptime" to "cat /proc/uptime",
                "启动原因 ro.boot.bootreason" to "getprop ro.boot.bootreason",
                "启动模式 ro.boot.bootmode" to "getprop ro.boot.bootmode",
                "Boot 完成标记 sys.boot_completed" to "getprop sys.boot_completed",
                "bootfail 闸门现值（如机型支持）" to "for f in /sys/module/bootfail/parameters/*; do [ -e \"\$f\" ] && echo \"\$(basename \$f) = \$(cat \$f)\"; done",
                "SUSFS 内核模块是否存在" to "ls -d /sys/module/susfs_guard_lkm 2>/dev/null"
            )
            for ((label, cmd) in baseItems) {
                if ((rebootLogStop.get() || RebootLogService.stopRequested)) break
                // 有 root 时基础项也走 su：/proc/cmdline、/proc/sys/kernel/panic 对普通 App 是拒绝的
                collect(rooted.get(), label, cmd, 12L)
            }

            if (rooted.get()) {
                val rootItems = listOf(
                    "内核日志 dmesg（tail 800）" to "dmesg | tail -n 800",
                    "每秒检查计数 kernel_stp_proc_write" to "dmesg | grep -c kernel_stp_proc_write",
                    "crash 日志（logcat -b crash）" to "logcat -d -b crash -t 800",
                    "zygote / critical / bootfail / panic 过滤" to "logcat -d -b all -t 400 | grep -iE \"zygote|critical|bootfail|watchdog|panic|fatal\"",
                    "厂商可靠性重启原因（/data/log/reliability，如有）" to "tail -n 20 /data/log/reliability/faultdetect/history.log 2>/dev/null",
                    "pstore 跨 panic 记录" to "ls -l /sys/fs/pstore 2>/dev/null && cat /sys/fs/pstore/* 2>/dev/null | tail -n 200",
                    "上次内核日志 /proc/last_kmsg" to "cat /proc/last_kmsg 2>/dev/null | tail -n 200"
                )
                for ((label, cmd) in rootItems) {
                    if ((rebootLogStop.get() || RebootLogService.stopRequested)) break
                    collect(true, label, cmd, 15L)
                }
            } else {
                val note = "（无 root：dmesg / logcat / pstore / last_kmsg 未采集；授权 root 后重新开始即可抓全）\n"
                ui.append(note).append("\n")
                sink.append(note)
            }

            // —— 持续轮询动态项，直到停止或检测到重启 ——
            var prevRooted = rooted.get()
            while (!(rebootLogStop.get() || RebootLogService.stopRequested)) {
                val intervalMs = if (low) 500 else 1000
                var waited = 0
                while (!(rebootLogStop.get() || RebootLogService.stopRequested) && waited < intervalMs) {
                    Thread.sleep(100)
                    waited += 100
                }
                if ((rebootLogStop.get() || RebootLogService.stopRequested)) break

                val now = formatNow()
                val cur = runSh("cat /proc/sys/kernel/random/boot_id").trim()
                if (baselineBootId.isNotBlank() && cur.isNotBlank() && cur != baselineBootId) {
                    sink.append("\n[$now] ⚠ boot_id 变化：$baselineBootId -> $cur ，判定已重启，停止采集\n")
                    ui.setLength(0)
                    ui.append("⚠ 检测到 boot_id 变化，已停止。\n")
                    publish("已停止：检测到手机重启")
                    return
                }

                pass++
                ui.setLength(0)
                ui.append("【第 ").append(pass).append(" 轮 · ").append(now).append("】\n")
                sink.append("\n========== 第 " + pass + " 轮 " + now + " ==========\n")

                collect(rooted.get(), "boot_id", "cat /proc/sys/kernel/random/boot_id", 8L)
                collect(rooted.get(), "uptime", "cat /proc/uptime", 8L)
                if (!prevRooted && rooted.get()) {
                    prevRooted = true
                    ui.append("【已检测到 root，后续改用 su 采集】\n")
                    sink.append("\n[" + formatNow() + "] 已检测到 root，切换 su\n")
                    // 补采只有 root 才能读的关键基线（最强重启判据）
                    collect(true, "内核命令行 /proc/cmdline", "cat /proc/cmdline", 8L)
                    collect(true, "panic 值", "cat /proc/sys/kernel/panic", 8L)
                    collect(true, "bootfail 闸门现值", "for f in /sys/module/bootfail/parameters/*; do [ -e \"\$f\" ] && echo \"\$(basename \$f) = \$(cat \$f)\"; done", 8L)
                }

                // 低干扰：已 root 时也只在每 3 轮采一次 su 日志；未 root 时整段不调 su
                if (rooted.get() && (!low || pass % 3 == 0)) {
                    collect(true, "dmesg tail 200", "dmesg | tail -n 200", 12L)
                    collect(true, "crash tail 100", "logcat -d -b crash -t 100", 12L)
                    collect(true, "zygote/critical tail", "logcat -d -b all -t 200 | grep -iE \"zygote|critical|bootfail|watchdog|panic|fatal\"", 12L)
                }
            }
        } catch (e: Exception) {
            sink.append("\n[异常停止：${e.message ?: e.javaClass.simpleName}]\n")
        } finally {
            finished.set(true)
            sink.append("\n[停止采集 " + formatNow() + "，共 $samples 项]\n")
            RebootLogSink.setActive(null)
            sink.close()
            val where = sink.path ?: "（文件落档失败）"
            runOnUiThread {
                target.text = target.text.toString() + "\n已停止，已保存到：\n$where"
                scrollParentToBottom(target)
            }
        }
    }

    /** 向上找到所在 ScrollView 并滚到底，实时采集时始终能看到最新一项 */
    private fun scrollParentToBottom(v: View) {
        var p = v.parent
        while (p != null) {
            if (p is android.widget.ScrollView) {
                p.fullScroll(View.FOCUS_DOWN)
                return
            }
            p = p.parent
        }
    }

    /** 跨厂商探测 root：优先用已知可用的 su，否则让 SuExec 遍历各家 su 路径 */
    private fun detectRoot(): Boolean {
        if (SuExec.activeSuPath().isNotEmpty()) return true
        val r = SuExec.run("id -u", 8L)
        return r.ok && r.out.lineSequence().any { it.trim() == "0" }
    }

    /** 无 root 的普通 shell 执行：并发读流 + 超时，避免输出超过管道缓冲时死锁 */
    private fun runSh(cmd: String, timeoutSec: Long = 12L): String {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val buf = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().use { r -> buf.append(r.readText()) } }
            }
            reader.start()
            val finished = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) p.destroy()
            reader.join(2000)
            val out = buf.toString()
            when {
                !finished -> "(超时，输出可能不完整)" + if (out.isNotBlank()) "\n" + out.trimEnd() else ""
                out.isBlank() -> "(无输出 / 无读取权限)"
                else -> out.trimEnd()
            }
        } catch (e: Exception) {
            "(执行失败：${e.message ?: e.javaClass.simpleName})"
        }
    }

    private fun formatNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun renderPostNote(facts: BranchDetect.Facts?, hasRoot: Boolean) {
        tvPostSusfsNote.text = when {
            facts == null || !hasRoot -> getString(R.string.post_susfs_need_root)
            facts.susfsOn -> getString(R.string.post_susfs_on)
            else -> getString(R.string.post_susfs_off)
        }
    }

    // ---------------------------------------------------------------- 分支区块

    private fun showBranchProbing() {
        tvBranchStatus.text = getString(R.string.branch_status_probing)
        tvBranchStatus.setTextColor(getColor(R.color.text_secondary))
        spinnerBox.visibility = View.GONE
        tvPickManual.visibility = View.GONE
        tvManualHint.visibility = View.GONE
    }

    /** 识别成功：结果直接摆出来，下拉框收起，只留「不是这个？」一个出口 */
    private fun showBranchAuto(branch: Branch) {
        selectBranch(branch.id)
        tvBranchStatus.text = getString(R.string.branch_status_auto, branch.label)
        tvBranchStatus.setTextColor(getColor(R.color.ok))
        spinnerBox.visibility = View.GONE
        tvPickManual.visibility = View.VISIBLE
        tvManualHint.visibility = View.GONE
    }

    /** 需要用户自己选（没 root / 认不出 / 只认出包名 / 点了「不是这个？」） */
    private fun showBranchEditing(status: String) {
        tvBranchStatus.text = status
        tvBranchStatus.setTextColor(getColor(R.color.warn))
        spinnerBox.visibility = View.VISIBLE
        tvPickManual.visibility = View.GONE
        tvManualHint.visibility = View.VISIBLE
    }

    private fun selectBranch(id: String) {
        val index = Branches.ALL.indexOfFirst { it.id == id }
        if (index >= 0) spinnerBranch.setSelection(index)
    }

    private fun selectedBranch(): Branch = Branches.ALL[spinnerBranch.selectedItemPosition]

    private fun showNote() {
        val branch = Branches.ALL.getOrNull(spinnerBranch.selectedItemPosition) ?: return
        tvBranchNote.text = branch.note

        // 设置项跟着「当前选中的分支」走：手选到别的分支后，不能还挂着上一个分支的建议
        val facts = detectedFacts
        when {
            facts != null -> renderGuidance(
                RootProbe.guidanceOf(branch, factsFor(branch, facts)),
                "这个分支先改这几项"
            )

            noRootGuidance != null -> renderGuidance(
                noRootGuidance!!,
                guidanceTitle.ifBlank { "拿到 root 之前要做的事" }
            )

            else -> tvGuidance.visibility = View.GONE
        }
    }

    /**
     * 设备事实只对「实际识别到的那一支」成立。用户手选别的分支时，只保留与分支无关的包名事实，
     * 否则会把 FolkZygisk、内置挂载这类只属于原分支的判断套到新分支上。
     */
    private fun factsFor(branch: Branch, facts: BranchDetect.Facts): BranchDetect.Facts =
        if (branch.id == detectedBranchId) facts
        else BranchDetect.Facts(managerPackages = facts.managerPackages)

    private fun renderGuidance(items: List<String>, title: String) {
        if (items.isEmpty()) {
            tvGuidance.visibility = View.GONE
            return
        }
        tvGuidance.visibility = View.VISIBLE
        tvGuidance.text = buildString {
            append("$title\n")
            items.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
        }.trim()
    }

    private fun showChangelog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.changelog_title)
            .setMessage(Changelog.TEXT)
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
