package com.sean.rootguide

/**
 * 框架功能恢复 —— 一键自动修复（选中谁就修谁）。
 *
 * 依据《SuSFS 重启机制与框架恢复流程》整理成一条可自动执行的 root 脚本，
 * 由 RecoveryActivity 经 [SuExec] 包裹进 su 执行。
 *
 * 设计要点（2026-11-05 真机修复固化，两个框架共用同一套骨架，只有 4 个参数随选中项变化）：
 *  - **选中谁就修谁**：顶层按 [framework] 定好 DAEMON / APK / MAIN / NAME，后面 ③~⑥ 全部是
 *    同一套逻辑，不再为 Vector 开小灶。LSPosed 与 Vector 都面临旧锁残留、注入进程残留、dexopt 崩溃。
 *  - **清残留共用**：LSPosed 与 Vector 共用 `/data/adb/lspd/lock` 单例锁。修复前把两侧 daemon、
 *    仍持有该锁 fd 的僵留进程（flock 文件删不掉，只能杀持有者）、两侧 `zygiskd64-<module>` 注入进程、
 *    `zygisk-ptrace64` monitor 残留、`/data/adb/neozygisk` 一次性清干净 —— 不只杀「对端」。
 *  - **启动统一**：两个框架都用 `app_process -Djava.class.path=<模块>/daemon.apk -Xnoimage-dex2oat`
 *    直拉，绕过各自 daemon 包装脚本里的 dexoptanalyzer（Android 17 ART 上会 SIGSEGV）。
 *  - **重启 zygote**：把框架注入 system_server 与各 App；该步会重启所有 App（含本 App），属正常。
 *
 * [susfsOn] 决定要不要额外跑 stpclear；[framework] 决定修复 LSPosed 还是 Vector。
 */
object Recovery {

    const val FW_LSPOSED = "lsposed"
    const val FW_VECTOR = "vector"

    /** 一键自动修复脚本（以 root 身份运行；RecoveryActivity 用 SuExec 包进 su，故此处不再写 su -c 前缀）。 */
    fun script(susfsOn: Boolean, framework: String): String {
        // 选中谁就修谁：仅这 4 个参数不同，③~⑥ 完全共用
        val isLsp = framework != FW_VECTOR
        val name = if (isLsp) "LSPosed" else "Vector"
        val daemon = if (isLsp) "lspd" else "vectord"
        val apk = if (isLsp)
            "/data/adb/modules/zygisk_lsposed/daemon.apk"
        else
            "/data/adb/modules/zygisk_vector/daemon.apk"
        val main = if (isLsp) "org.lsposed.lspd.Main" else "org.matrix.vector.daemon.VectorDaemon"
        val statusCmd = if (isLsp)
            "lspctl status 2>/dev/null || echo 'lspctl 无输出'"
        else
            "/data/adb/modules/zygisk_vector/cli status 2>/dev/null || echo 'cli status 无输出'"

        val lines = mutableListOf<String>()
        lines += "echo '== ① 确认 root =='"
        lines += "id | grep -q 'uid=0' || { echo '未拿到 root：请先在 MagicRoot 一键提权后重试'; exit 1; }"
        lines += "echo 'root OK'"
        lines += ""
        lines += "echo '== ② 关三道闸门（防第一次注入被判定异常死亡）=='"
        lines += "echo 0 > /sys/module/bootfail/parameters/bfctl 2>/dev/null"
        lines += "/data/adb/ksud resetprop -n ro.sys.ping_bf_dorecovery false 2>/dev/null"
        lines += "for s in zygote zygote64 surfaceflinger keystore2 servicemanager hwservicemanager ueventd; do /data/adb/ksud resetprop -n init.svc_debug.no_fatal.\$s 1 2>/dev/null; done"
        if (susfsOn) {
            lines += "# 开着 SUSFS：清 STP 判定，确认 kernel_stp 每秒检查计数归零"
            lines += "/data/local/tmp/sbh/bin/stpclear 2>/dev/null && echo 'stpclear OK' || echo 'stpclear 不可用（无 libstp，可忽略）'"
        }
        lines += "echo '闸门已关'"
        lines += ""
        // —— 目标参数：选中谁就修谁 ——
        lines += "NAME='$name'"
        lines += "DAEMON='$daemon'"
        lines += "APK='$apk'"
        lines += "MAIN='$main'"
        lines += "echo \"目标框架：\$NAME（\$DAEMON）\""
        lines += ""
        // —— ③ 清残留：两个框架共用同一套 ——
        lines += "echo '== ③ 清残留（LSPosed / Vector 共用同一套）=='"
        lines += "# 1) NeoZygisk monitor 残留"
        lines += "for p in \$(pidof zygisk-ptrace64); do kill -9 \"\$p\" 2>/dev/null; done"
        lines += "# 2) 两侧 daemon 全结束"
        lines += "for p in \$(pidof vectord lspd); do kill -9 \"\$p\" 2>/dev/null; done"
        lines += "# 3) 共享锁：直接删旧 lock（unlink 后新 daemon 会重建，旧持有者不影响）；"
        lines += "#    不再遍历 /proc/*/fd——逐个 fork readlink 会慢到几分钟，导致超时走不到后面"
        lines += "rm -f /data/adb/lspd/lock 2>/dev/null"
        lines += "# 4) 两侧注入进程残留"
        lines += "for p in \$(pidof zygiskd64-zygisk_lsposed zygiskd64-zygisk_vector); do kill -9 \"\$p\" 2>/dev/null; done"
        lines += "# 5) NeoZygisk 运行时目录"
        lines += "rm -rf /data/adb/neozygisk 2>/dev/null"
        lines += "echo '残留已清：两侧 daemon / 共享锁 / 注入进程 / monitor'"
        lines += ""
        // —— ④ 启动：两个框架统一 app_process -Xnoimage-dex2oat 直拉 ——
        lines += "echo '== ④ 拉起 monitor + 目标 daemon（统一 app_process -Xnoimage-dex2oat 直拉）=='"
        lines += "setsid sh /data/adb/modules/zygisksu/post-fs-data.sh </dev/null >/dev/null 2>&1 &"
        // 关键：必须在模块目录下启动。lspd 会按相对路径找 framework.dex / bridge；
        // CWD 不对就会报 framework.dex ENOENT、exec bridge ENOENT，最终 System server: not connected。
        lines += "( cd \"\$(dirname \"\$APK\")\" && setsid /system/bin/app_process -Djava.class.path=\"\$APK\" -Xnoimage-dex2oat /system/bin --nice-name=\"\$DAEMON\" \"\$MAIN\" </dev/null >/dev/null 2>&1 & )"
        // 等 daemon 真正起来再重启 zygote：太早重启，system_server 来不及连上 bridge
        lines += "i=0; while [ \$i -lt 20 ] && ! pidof \"\$DAEMON\" >/dev/null 2>&1; do sleep 1; i=\$((i+1)); done"
        lines += "sleep 3"
        lines += "echo \"monitor 进程数：\$(pidof zygisk-ptrace64 | wc -w)（应为 1）\""
        lines += "echo \"\$NAME daemon(\$DAEMON) 进程数：\$(pidof \"\$DAEMON\" | wc -w)（应为 1）\""
        lines += "echo \"对端残留检查：lspd=\$(pidof lspd | wc -w) vectord=\$(pidof vectord | wc -w)\""
        lines += ""
        lines += "echo '== ⑤ 重启 zygote（屏幕会黑一下，属正常；框架将注入 system_server 与各 App）=='"
        lines += "(sleep 1; setprop ctl.restart zygote) >/dev/null 2>&1 &"
        lines += "echo '已发起 zygote 重启，约 15 秒回桌面'"
        lines += ""
        lines += "echo '== ⑥ 验证（zygote 重启会杀掉本 App，下面只是注入前快照）=='"
        lines += "sleep 1"
        lines += "echo '-- \$NAME status --'"
        lines += statusCmd
        lines += "echo '（System server 连接将在 zygote 重启后建立；App 重开后到状态检测页复测）'"
        lines += "echo '（若重启后仍进不去管理界面：看状态检测第 4 项「框架管理器本体」是否未安装/被停用）'"
        return lines.joinToString("\n")
    }

    /** 一键修复前的风险提示，作为确认弹窗正文。 */
    fun warning(susfsOn: Boolean, is8E5: Boolean, framework: String): String = buildString {
        val name = if (framework == FW_LSPOSED) "LSPosed" else "Vector"
        append("将自动修复【$name】：确认 root → 关三道闸门 → 清残留 → 拉起 $name daemon 与 NeoZygisk monitor → 重启 zygote → 验证。\n\n")
        append("· 两个框架共用同一套清残留：结束两侧 daemon、释放 /data/adb/lspd 共享锁（含旧锁 fd 持有者）、清两侧注入进程与 monitor 残留，然后只拉起 $name。\n")
        append("· 另一个框架本次不会启动，需要时单独再选它修一次。\n")
        append("· 会重启 zygote，所有 App 会退出重进（屏幕黑一下是正常的），本 App 也会被重启。\n")
        append("· 务必在开机满 10 分钟后再点（MagicOS critical window=10，窗口内重启 zygote 可能整机复位）。\n")
        if (susfsOn) append("· 当前开着 SUSFS：会额外跑 stpclear 清 STP 判定，请在验证前确认 kernel_stp 每秒检查计数为 0。\n")
        if (!is8E5) append("· 本机非 8E5（SM8850）：假回锁 / hnubl 不适用，请按本机实际调整。\n")
        append("\n是否继续？")
    }
}
