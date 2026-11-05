package com.sean.rootguide

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class PlanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BRANCH_ID = "branch_id"
    }

    private lateinit var adapter: StepAdapter
    private lateinit var branch: Branch
    private lateinit var tvProgress: TextView
    private lateinit var tvHint: TextView
    private var steps: List<Step> = emptyList()

    /** stepId -> 安装过程/结果 */
    private val statuses = mutableMapOf<String, String>()
    private val busy = mutableSetOf<String>()
    private var installedModules: List<String> = emptyList()

    /** 单线程串行执行：同时装两个模块是自找麻烦（互相覆盖挂载） */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan)
        Insets.applyTo(findViewById(R.id.rootView))

        branch = Branches.byId(intent.getStringExtra(EXTRA_BRANCH_ID) ?: Prefs.getBranchId(this))
        steps = PlanBuilder.build(branch, Prefs.getSusfsOn(this))
        installedModules = Prefs.getInstalledModules(this)

        tvProgress = findViewById(R.id.tvProgress)
        tvHint = findViewById(R.id.tvHint)
        findViewById<TextView>(R.id.tvBranch).text = "${branch.label} · ${steps.size} 项"

        val done = Prefs.getDone(this, branch.id)
        adapter = StepAdapter(
            steps = steps,
            done = done,
            statuses = statuses,
            busy = busy,
            installedModules = { installedModules },
            onInstall = { step -> startInstall(step) },
            onChange = {
                Prefs.saveDone(this, branch.id, adapter.doneSet())
                updateProgress()
            }
        )

        val rvSteps = findViewById<RecyclerView>(R.id.rvSteps)
        rvSteps.layoutManager = LinearLayoutManager(this)
        rvSteps.adapter = adapter

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            adapter.clearAll()
            Prefs.saveDone(this, branch.id, emptySet())
            updateProgress()
        }
        findViewById<Button>(R.id.btnSoftReboot).setOnClickListener { runReboot("ksud soft-reboot", false) }
        findViewById<Button>(R.id.btnReboot).setOnClickListener { runReboot("reboot", true) }

        updateProgress()
    }

    // ---------------------------------------------------------------- 安装

    private fun startInstall(step: Step) {
        val spec = step.module ?: return
        if (busy.contains(step.id)) return

        busy.add(step.id)
        adapter.refresh(step.id)

        worker.execute {
            var temp: File? = null
            try {
                post(step.id, "① 查询 ${spec.repo} 的最新 Release…")
                val release = ModuleInstaller.resolve(spec)

                val mb = String.format(Locale.US, "%.1f", release.asset.size / 1048576.0)
                post(step.id, "② 下载 ${release.asset.name}\n   ${release.tag} · $mb MB")
                val dest = File(File(cacheDir, "mods"), release.asset.name)
                temp = ModuleInstaller.download(release.asset, dest) { pct ->
                    post(step.id, "② 下载中 $pct%\n   ${release.asset.name}")
                }

                post(step.id, "③ 复制到 root 可读位置…")
                val path = ModuleInstaller.stageForRoot(dest)
                    ?: throw ModuleInstaller.NetException(
                        "无法把文件放到 /data/local/tmp。可能 su 未授权，或 SELinux 拦截。"
                    )

                post(step.id, "④ 执行安装：\n${ModuleInstaller.installCmd(branch.family, path, spec.kind)}")
                val result = ModuleInstaller.install(branch.family, path, spec.kind)

                val tail = result.text().lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n")
                if (result.ok) {
                    post(step.id, "✅ 安装完成（${release.tag}）\n$tail\n\n下一步：重启或软重启后生效")
                    markDone(step.id)
                    refreshInstalled()
                } else {
                    post(step.id, "❌ 安装失败\n$tail")
                }
            } catch (e: Exception) {
                post(step.id, "❌ ${e.message ?: "未知错误"}")
            } finally {
                temp?.delete()
                busy.remove(step.id)
                runOnUiThread { adapter.refresh(step.id) }
            }
        }
    }

    /** 装完重新读一次模块列表，让「已装」标记和后续步骤判断是准的 */
    private fun refreshInstalled() {
        val list = ModuleInstaller.listModules(branch.family)
        val ids = parseModuleIds(list)
        if (ids.isNotEmpty()) {
            installedModules = ids
            Prefs.saveInstalledModules(this, ids)
        }
        runOnUiThread {
            adapter.notifyDataSetChanged()
            Toast.makeText(this, R.string.installed_need_reboot, Toast.LENGTH_LONG).show()
        }
    }

    /** `ksud module list` 会输出 id/name 两列，取第一列；Magisk 场景本来就是目录名 */
    private fun parseModuleIds(lines: List<String>): List<String> =
        lines.mapNotNull { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> null
                t.startsWith("id:") -> t.removePrefix("id:").trim()
                t.contains(Regex("""^[A-Za-z0-9_.\-]+$""")) -> t
                else -> t.split(Regex("""\s+""")).firstOrNull()
            }
        }.distinct()

    private fun post(stepId: String, text: String) {
        runOnUiThread {
            statuses[stepId] = text
            adapter.refresh(stepId)
        }
    }

    private fun markDone(stepId: String) {
        runOnUiThread {
            // adapter 内部持有 done 集合，改它再落盘，避免两份状态不一致
            adapter.markDone(stepId)
            Prefs.saveDone(this, branch.id, adapter.doneSet())
            updateProgress()
        }
    }

    // ---------------------------------------------------------------- 重启

    private fun runReboot(cmd: String, confirm: Boolean) {
        if (confirm) {
            AlertDialog.Builder(this)
                .setTitle("确认重启？")
                .setMessage("会断开当前所有 App。若你的 root 是临时 root（重启即失效），请改用「软重启」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启") { _, _ -> doReboot(cmd) }
                .show()
        } else {
            doReboot(cmd)
        }
    }

    private fun doReboot(cmd: String) {
        Toast.makeText(this, "执行：$cmd", Toast.LENGTH_SHORT).show()
        worker.execute { SuExec.run(cmd, 30) }
    }

    // ---------------------------------------------------------------- 其它

    private fun updateProgress() {
        val doneSet = adapter.doneSet()
        val done = steps.count { doneSet.contains(it.id) }
        val requiredLeft = steps.count { it.required && !doneSet.contains(it.id) }
        tvProgress.text = "已完成 $done / ${steps.size} ｜ 必装剩余 $requiredLeft"

        val installedCount = steps.count { ModuleCatalog.isInstalled(it.module, installedModules) }
        tvHint.text = if (installedModules.isEmpty()) {
            "点「下载并安装」会从官方仓库拉最新 Release 并交给 ${installerName()} 安装。"
        } else {
            "已检测到 ${installedModules.size} 个模块目录，其中 ${installedCount} 项与本清单匹配（标「已装」）。"
        }
    }

    private fun installerName(): String = when (branch.family) {
        Family.MAGISK -> "magisk"
        Family.APATCH -> "apd"
        else -> "ksud"
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
