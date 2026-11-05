package com.sean.rootguide

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * 直接用 ksud / magisk 安装模块，并支持软重启。
 *
 * 流程：用系统文件选择器选 zip → 复制到 su 可读的临时位置 → 调用对应体系的安装命令 → 显示输出。
 * 本页不会自动下载任何东西，也不会改写任何分区。
 */
class ModuleInstallActivity : AppCompatActivity() {

    private lateinit var tvOut: TextView
    private lateinit var tvFile: TextView
    private lateinit var tvEnv: TextView
    private var staged: File? = null

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val file = stage(uri)
        if (file == null) {
            tvFile.text = getString(R.string.install_stage_failed)
            return@registerForActivityResult
        }
        staged = file
        val name = uri.lastPathSegment ?: file.name
        tvFile.text = "已选择：$name\n临时路径：${file.absolutePath}（${file.length() / 1024} KB）"
        tvOut.text = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_install)
        Insets.applyTo(findViewById(R.id.rootView))

        tvOut = findViewById(R.id.tvInstallOut)
        tvFile = findViewById(R.id.tvInstallFile)
        tvEnv = findViewById(R.id.tvInstallEnv)

        val btnPick = findViewById<Button>(R.id.btnPickZip)
        val btnInstall = findViewById<Button>(R.id.btnInstall)
        val btnSoft = findViewById<Button>(R.id.btnSoftReboot)
        val btnReboot = findViewById<Button>(R.id.btnReboot)
        val btnList = findViewById<Button>(R.id.btnListModules)

        val branch = Branches.byId(Prefs.getBranchId(this))
        tvEnv.text = "当前分支：${branch.label}\n安装命令：${installCmd(branch, "<zip>")}"
        tvFile.text = getString(R.string.install_no_file)

        // 读一次 BL 状态，决定建议用软重启还是普通重启
        Thread {
            val state = SuExec.out("getprop ro.boot.vbmeta.device_state", 10).trim()
            val tip = if (state == "locked") {
                "BL 状态：locked → 物理重启会丢 root，请用「软重启」让模块生效"
            } else {
                "BL 状态：${state.ifBlank { "未知" }} → 可以用普通重启"
            }
            runOnUiThread { tvEnv.append("\n$tip") }
        }.start()

        btnPick.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }

        btnInstall.setOnClickListener {
            val file = staged
            if (file == null) {
                Toast.makeText(this, R.string.install_pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cmd = installCmd(branch, file.absolutePath)
            runCmd(cmd, 120)
        }

        btnSoft.setOnClickListener { runCmd("ksud soft-reboot", 30) }
        btnReboot.setOnClickListener { runCmd("reboot", 10) }
        btnList.setOnClickListener { runCmd(listCmd(branch), 20) }
    }

    private fun installCmd(branch: Branch, path: String): String = when (branch.family) {
        Family.MAGISK -> "magisk --install-module \"$path\""
        Family.APATCH -> "/data/adb/ap/bin/apd module install \"$path\""
        else -> "ksud module install \"$path\""
    }

    private fun listCmd(branch: Branch): String = when (branch.family) {
        Family.MAGISK -> "ls -1 /data/adb/modules"
        else -> "ksud module list"
    }

    private fun runCmd(cmd: String, timeout: Long) {
        tvOut.text = "$ $cmd\n执行中…"
        Thread {
            val result = SuExec.run(cmd, timeout)
            val text = "$ $cmd\n" + result.text().ifBlank { "（无输出）" }
            runOnUiThread { tvOut.text = text }
        }.start()
    }

    /** 把用户选择的 zip 放到 su 进程读得到的位置 */
    private fun stage(uri: Uri): File? {
        val name = "rg_${System.currentTimeMillis()}.zip"
        val candidates = listOf(File("/data/local/tmp", name), File(cacheDir, name))
        for (file in candidates) {
            try {
                val input = contentResolver.openInputStream(uri) ?: continue
                input.use { src -> FileOutputStream(file).use { dst -> src.copyTo(dst) } }
                file.setReadable(true, false)
                if (file.length() > 0) return file
                file.delete()
            } catch (e: Exception) {
                // 换下一个位置
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        staged?.delete()
    }
}
