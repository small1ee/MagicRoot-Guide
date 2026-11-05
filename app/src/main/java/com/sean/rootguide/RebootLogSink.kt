package com.sean.rootguide

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 逐项实时落档（flush + fsync）。做成全局可引用的入口，便于其它页面写入「动作标记」，
 * 例如点「一键修复」时在日志里留一行，日后把动作和重启记录对齐。
 */
class RebootLogSink(private val ctx: Context) {

    var path: String? = null
        private set
    private var out: OutputStream? = null
    private var fdSync: FileDescriptor? = null
    private val lock = Any()

    fun open(header: String): Boolean {
        val name = "reboot_log_" + System.currentTimeMillis() + ".txt"
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RB-log")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore 插入失败")
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
                    ?: throw IllegalStateException("打开输出失败")
                fdSync = pfd.fileDescriptor
                out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                path = "Download/RB-log/$name"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RB-log"
                )
                if (!dir.exists()) dir.mkdirs()
                val fos = FileOutputStream(File(dir, name), true)
                out = fos
                fdSync = fos.fd
                path = "Download/RB-log/$name"
            }
            true
        } catch (e: Exception) {
            runCatching { out?.close() }
            out = null
            fdSync = null
            runCatching {
                val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "RB-log").apply { mkdirs() }
                val f = File(d, name)
                val fos = FileOutputStream(f, true)
                out = fos
                fdSync = fos.fd
                path = "App 专有目录：${f.absolutePath}"
                true
            }.getOrDefault(false)
        }
        if (ok) append(header)
        return ok
    }

    /** 写一段并立即落盘（flush + fsync），保证掉电 / 重启前内容不丢 */
    fun append(text: String) {
        synchronized(lock) {
            val o = out ?: return
            try {
                o.write(text.toByteArray(Charsets.UTF_8))
                o.flush()
                fdSync?.sync()
            } catch (_: Exception) {
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { out?.flush() }
            runCatching { fdSync?.sync() }
            runCatching { out?.close() }
            out = null
            fdSync = null
        }
    }

    companion object {
        @Volatile
        private var active: RebootLogSink? = null

        fun setActive(s: RebootLogSink?) {
            active = s
        }

        /** 当前是否有活动采集（用于界面提示"先开始采集再打点"） */
        fun isActive(): Boolean = active != null

        /**
         * 从任意页面写一行动作标记（带时间），例如「用户触发一键修复框架」。
         * 只有当前存在活动采集时才写入；返回是否写入成功。
         */
        fun marker(text: String): Boolean {
            val s = active ?: return false
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            s.append("\n[动作 $ts] $text\n")
            return true
        }
    }
}
