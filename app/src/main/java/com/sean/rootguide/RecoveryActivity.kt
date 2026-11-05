package com.sean.rootguide

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 框架功能恢复 —— 一键自动修复。
 *
 * 选中目标框架（LSPosed / Vector，选中谁就修谁）→ 确认风险 → 后台以 root **流式**执行整条恢复脚本
 * （Recovery.script），每行输出实时追加到「执行输出」区并自动滚到最新，同时进度行显示当前阶段。
 * 脚本末尾会重启 zygote，届时 App 会退出重进（属正常）——因为重启前输出已实时显示，用户不会误以为
 * 「没点上」而反复点击。
 */
class RecoveryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUSFS_ON = "susfs_on"
        const val EXTRA_IS_8E5 = "is_8e5"
        /** 预选框架：传 Recovery.FW_LSPOSED / FW_VECTOR；不传默认 LSPosed */
        const val EXTRA_FRAMEWORK = "framework"
    }

    private lateinit var btnFix: Button
    private lateinit var tvOutput: TextView
    private lateinit var rgFramework: RadioGroup
    private lateinit var progressRow: View
    private lateinit var tvProgress: TextView
    private lateinit var tvRootStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recovery)
        Insets.applyTo(findViewById(R.id.rootView))

        val susfsOn = intent.getBooleanExtra(EXTRA_SUSFS_ON, Prefs.getSusfsOn(this))
        val is8E5 = intent.getBooleanExtra(EXTRA_IS_8E5, false)

        btnFix = findViewById(R.id.btnFix)
        tvOutput = findViewById(R.id.tvOutput)
        rgFramework = findViewById(R.id.rgFramework)
        progressRow = findViewById(R.id.progressRow)
        tvProgress = findViewById(R.id.tvProgress)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        val tvWarning = findViewById<TextView>(R.id.tvWarning)

        // 检测页传来的默认框架：检测到哪个就预选哪个，没传则默认 LSPosed
        val preset = intent.getStringExtra(EXTRA_FRAMEWORK)
        if (preset == Recovery.FW_VECTOR) rgFramework.check(R.id.rbVector)
        else rgFramework.check(R.id.rbLsposed)

        // 选中谁就修谁：切换目标时风险提示同步刷新，避免「选 Vector 却看到 LSPosed 的说明」
        fun refreshWarning() {
            tvWarning.text = Recovery.warning(susfsOn, is8E5, selectedFramework())
        }
        refreshWarning()
        rgFramework.setOnCheckedChangeListener { _, _ ->
            refreshWarning()
            RebootLogSink.marker("切换修复目标：" + selectedFramework())
        }
        RebootLogSink.marker("打开框架恢复页面（当前目标：" + selectedFramework() + "）")

        btnFix.setOnClickListener {
            val fw = selectedFramework()
            AlertDialog.Builder(this)
                .setTitle(R.string.recovery_fix_confirm_title)
                .setMessage(Recovery.warning(susfsOn, is8E5, fw))
                .setPositiveButton(R.string.recovery_fix_confirm_button) { _, _ -> runFix(susfsOn, fw) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * 每次回到本页都重新探测 root：用户可能去 MagicRoot 提权后又返回。
     * 无 root 时在顶部明确提示，避免「点了一键修复却毫无反应」的反复点击。
     */
    override fun onResume() {
        super.onResume()
        refreshRootState()
    }

    private fun refreshRootState() {
        tvRootStatus.text = getString(R.string.recovery_root_checking)
        tvRootStatus.setTextColor(getColor(R.color.text_secondary))
        Thread {
            val ok = SuExec.run("id -u", 6).out.lineSequence().any { it.trim() == "0" }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    tvRootStatus.text = getString(R.string.recovery_root_ok)
                    tvRootStatus.setTextColor(getColor(R.color.ok))
                } else {
                    tvRootStatus.text = getString(R.string.recovery_root_missing)
                    tvRootStatus.setTextColor(getColor(R.color.warn))
                }
            }
        }.start()
    }

    /** 当前选中的目标框架：默认 LSPosed，选 Vector 才返回 vector。 */
    private fun selectedFramework(): String =
        if (rgFramework.checkedRadioButtonId == R.id.rbVector) Recovery.FW_VECTOR else Recovery.FW_LSPOSED

    private fun runFix(susfsOn: Boolean, framework: String) {
        // 往当前采集日志写一行动作标记，便于日后把「重启」与「点了一键修复」对齐
        RebootLogSink.marker("用户触发一键修复框架（$framework）")
        // ① 立刻给出可感知的反馈：按钮变文案+禁用、进度行出现、清空旧输出。避免「点了没反应→反复点」。
        btnFix.isEnabled = false
        btnFix.text = getString(R.string.recovery_fixing)
        progressRow.visibility = View.VISIBLE
        tvProgress.text = getString(R.string.recovery_progress_running)
        tvOutput.text = ""
        scrollToOutput()

        Thread {
            // 流式执行：脚本每 echo 一行就实时追加，重启 zygote 前用户已能看到全部进度
            val res = SuExec.runStreaming(Recovery.script(susfsOn, framework), timeoutSec = 120) { line ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (tvOutput.text.isNotEmpty()) tvOutput.append("\n")
                    tvOutput.append(line)
                    // 状态行跟随脚本阶段推进；到「重启 zygote」时给出明确预期
                    when {
                        line.contains("重启 zygote") || line.contains("已发起") ->
                            tvProgress.text = getString(R.string.recovery_progress_zygote)
                        line.startsWith("==") ->
                            tvProgress.text = line.trim('=', ' ').trim()
                    }
                    scrollToOutput()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progressRow.visibility = View.GONE
                btnFix.isEnabled = true
                btnFix.text = getString(R.string.recovery_fix_button)
                // 流式没吐出任何行时，给出可执行的结论（而不是直接甩一句英文 stderr）
                if (tvOutput.text.isBlank()) {
                    val raw = res.text()
                    val noSu = res.notStarted ||
                        raw.contains("Cannot run program") ||
                        raw.contains("error=2, No such file") ||
                        raw.contains("Permission denied") ||
                        raw.contains("未拿到 root")
                    tvOutput.text = when {
                        noSu -> getString(R.string.recovery_no_su)
                        raw.isBlank() -> getString(R.string.recovery_no_output)
                        else -> raw
                    }
                }
                scrollToOutput()
            }
        }.start()
    }

    /** 输出增长后自动滚到最新一行，让用户始终看到进度（不依赖手动下滑）。 */
    private fun scrollToOutput() {
        val sv = findViewById<ScrollView>(R.id.recoveryScroll) ?: return
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
}
