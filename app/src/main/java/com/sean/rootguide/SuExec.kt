package com.sean.rootguide

import java.util.concurrent.TimeUnit

object SuExec {

    data class Result(val exitCode: Int, val out: String, val error: String) {
        val ok: Boolean get() = exitCode == 0
        /** 进程根本没起来（命令不在 PATH / 路径不存在 / 被 SELinux 拦），不是「跑了但没权限」 */
        val notStarted: Boolean get() = exitCode == -999
        fun text(): String {
            val sb = StringBuilder()
            if (out.isNotBlank()) sb.append(out.trim())
            if (error.isNotBlank()) sb.append("\n[stderr] ").append(error.trim())
            if (exitCode == -1) sb.append("\n[超时：命令未在限定时间内结束]")
            else if (exitCode != 0 && exitCode != -999) sb.append("\n[exit code] ").append(exitCode)
            return sb.toString().trim()
        }
    }

    /**
     * su 候选命令，按优先级排列。
     *
     * KernelSU / SukiSU 的 sucompat 并不在文件系统里放一个真实的 su 文件：内核把对
     * `/system/bin/su` 的 execve / faccessat / stat 重定向到 `/data/adb/ksud`，而且
     * **只对已授权的 UID 呈现这个路径**。裸 "su" 交给 ProcessBuilder 走 PATH 搜索，
     * 不会稳定命中这个被 hook 的精确路径 —— 真机实测（Magic8 Pro / SukiSU-Ultra，
     * 2026-11-05）：管理器里已给本 App 勾选「超级用户」(UID 10287)，但
     * `ProcessBuilder("su")` 仍报 `error=2, No such file or directory`。
     * 所以必须显式逐个尝试绝对路径。
     */
    private val SU_COMMANDS = listOf(
        "/system/bin/su",              // KernelSU sucompat 的精确路径，最高优先级
        "su",                          // 传统 PATH（Magisk / 老 KernelSU）
        "/system/xbin/su",             // 老 Magisk / 部分定制 ROM
        "/sbin/su",                    // 旧版 Magisk（system-as-root 之前）
        "/su/bin/su",                  // systemless root
        "/system/sbin/su",
        "/vendor/bin/su",              // 部分厂商预置
        "/odm/bin/su",
        "/product/bin/su",
        "/debug_ramdisk/su",
        "/data/local/xbin/su",         // 工程机 / adb root 场景
        "/data/local/bin/su",
        "/data/adb/ksu/bin/su",        // KernelSU 用户态 su
        "/data/adb/sukisu/bin/su",
        "/data/adb/ksu/su",
        "/data/adb/ksud",              // ksud 本体（部分构建直接扮演 su 后端）
        "/data/adb/magisk/su",
        "/data/adb/ap/bin/su"          // APatch
    )

    /** 已确认能拿到 uid=0 的命令；空 = 还没探测或全部失败 */
    @Volatile private var activeSu: String = ""

    /** 探测失败时，第一个「至少能把进程起起来」的候选，用于保留具体错误 */
    @Volatile private var runnableSu: String = "su"

    /** 每个候选的逐项结果，供「查看检测详情」输出，避免下次再靠猜 */
    @Volatile var lastProbeLog: String = ""
        private set

    /** 确认可用的 su 命令；空表示没找到 */
    fun activeSuPath(): String = activeSu

    /** 清空缓存，下次 run() 重新探测（用户在管理器里改完授权后点「重新检测」会走这里） */
    fun reset() {
        activeSu = ""
        runnableSu = "su"
        lastProbeLog = ""
    }

    /**
     * 通过 su 执行一段 shell 脚本。
     *
     * 参数直接作为 argv 传递（不经过本地 shell 解析），所以脚本里的引号、换行、$ 都安全。
     * 必须在 waitFor **之前**把 stdout/stderr 读空：探测会 dump 全部包名（几十 KB），
     * 超过管道缓冲后子进程写阻塞、父进程等退出 = 死锁。
     *
     * 各家管理器自带的 su 对 `-c` 的处理并不一致：有的把后面整串当一条命令、有的不吃多行脚本，
     * 结果 stdout 空白、只在 stderr 留一句看不懂的错。所以**直接调用拿不到任何输出时**，
     * 再用 stdin 把脚本喂给 su 的交互 shell 跑一遍；两条路都失败才把原始错误交回去。
     */
    fun run(script: String, timeoutSec: Long = 30): Result {
        val found = if (activeSu.isNotEmpty()) activeSu else detectSu()
        val use = if (found.isNotEmpty()) found else runnableSu
        return runWith(use, script, timeoutSec)
    }

    /** 逐个候选执行 `-c id`，找到真正拿到 root 的那个并缓存 */
    private fun detectSu(): String {
        val log = StringBuilder()
        var firstRunnable: String? = null
        for (cmd in SU_COMMANDS) {
            // 用 `id -u` 单独一行输出 0 才算真拿到 root：只看 echo 标记会误判
            // 「命令跑起来了但没提权」的候选（例如 ksud 被当成普通 CLI 执行）。
            val probe = exec(listOf(cmd, "-c", "id -u; echo RG_SU_OK"), null, 4)
            val gotRoot = probe.exitCode == 0 &&
                probe.out.lineSequence().any { it.trim() == "0" } &&
                probe.out.contains("RG_SU_OK")
            log.append(cmd).append(" → exit=").append(probe.exitCode)
            if (probe.out.isNotBlank()) {
                log.append(" out=").append(probe.out.trim().take(70).replace("\n", " "))
            }
            if (probe.error.isNotBlank()) {
                log.append(" err=").append(probe.error.trim().take(100).replace("\n", " "))
            }
            log.append("\n")
            if (!probe.notStarted && firstRunnable == null) firstRunnable = cmd
            if (gotRoot) {
                activeSu = cmd
                runnableSu = cmd
                lastProbeLog = log.toString()
                return cmd
            }
        }
        runnableSu = firstRunnable ?: "su"
        lastProbeLog = log.toString()
        return ""
    }

    private fun runWith(cmd: String, script: String, timeoutSec: Long): Result {
        val direct = exec(listOf(cmd, "-c", script), stdin = null, timeoutSec)
        if (direct.out.isNotBlank() || direct.notStarted) return direct

        val viaStdin = exec(listOf(cmd), stdin = script + "\nexit\n", timeoutSec)
        return if (viaStdin.out.isNotBlank()) viaStdin else direct
    }

    /**
     * 流式执行：每读到一行输出（stdout/stderr 合并）就回调 [onLine]，用于「一键修复」这类长脚本的
     * 实时进度显示。必须在读的同时 waitFor，否则输出超过管道缓冲会死锁。
     *
     * 注意：本方法**不做**「-c 没输出就再走 stdin」的兜底——恢复脚本含「重启 zygote」，若因第一遍
     * 没输出而重复执行整条脚本，等于二次重启 zygote。只有当进程根本起不来（notStarted）时才用
     * stdin 兜底。
     */
    fun runStreaming(script: String, timeoutSec: Long = 120, onLine: (String) -> Unit): Result {
        val found = if (activeSu.isNotEmpty()) activeSu else detectSu()
        val use = if (found.isNotEmpty()) found else runnableSu
        val direct = execStreaming(listOf(use, "-c", script), null, timeoutSec, onLine)
        if (direct.notStarted && direct.out.isBlank()) {
            return execStreaming(listOf(use), script + "\nexit\n", timeoutSec, onLine)
        }
        return direct
    }

    private fun execStreaming(
        cmd: List<String>,
        stdin: String?,
        timeoutSec: Long,
        onLine: (String) -> Unit
    ): Result {
        return try {
            // 合并 stderr：进度既能提示正常流程，也能第一时间暴露错误
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            if (stdin != null) {
                runCatching {
                    process.outputStream.bufferedWriter().use { w ->
                        w.write(stdin)
                        w.flush()
                    }
                }
            }
            val outBuf = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            outBuf.append(line).append('\n')
                            runCatching { onLine(line) }
                        }
                    }
                }
            }
            reader.start()
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                reader.join(1000)
                return Result(-1, outBuf.toString(), "")
            }
            reader.join(3000)
            Result(process.exitValue(), outBuf.toString(), "")
        } catch (e: Exception) {
            Result(-999, "", e.message ?: "无法执行 su（未 root 或未授权）")
        }
    }

    private fun exec(cmd: List<String>, stdin: String?, timeoutSec: Long): Result {
        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()

            if (stdin != null) {
                runCatching {
                    process.outputStream.bufferedWriter().use { w ->
                        w.write(stdin)
                        w.flush()
                    }
                }
            }

            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val outThread = Thread {
                runCatching { process.inputStream.bufferedReader().use { r -> outBuf.append(r.readText()) } }
            }
            val errThread = Thread {
                runCatching { process.errorStream.bufferedReader().use { r -> errBuf.append(r.readText()) } }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                outThread.join(1000)
                errThread.join(1000)
                return Result(-1, outBuf.toString(), errBuf.toString())
            }
            outThread.join(3000)
            errThread.join(3000)
            Result(process.exitValue(), outBuf.toString(), errBuf.toString())
        } catch (e: Exception) {
            Result(-999, "", e.message ?: "无法执行 su（未 root 或未授权）")
        }
    }

    /** 只关心输出的便捷版本 */
    fun out(script: String, timeoutSec: Long = 30): String = run(script, timeoutSec).text()
}
