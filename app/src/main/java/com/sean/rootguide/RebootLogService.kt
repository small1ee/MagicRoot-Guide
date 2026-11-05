package com.sean.rootguide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 后台持续采集的保活服务：
 *  - 前台服务 + 常驻通知（带「停止」），避免 App 退到后台后进程被系统/厂商清理；
 *  - 持有 PARTIAL_WAKE_LOCK，减少息屏后 CPU 休眠导致的采集停顿；
 *  - 若已授予悬浮窗权限，显示一个小悬浮条（状态 + 停止），离开 App 也能看到并随时停止。
 * 真正的采集循环仍在 MainActivity 的线程里，本服务只负责「让进程活着」+ 展示/停止入口。
 */
class RebootLogService : Service() {

    private var overlay: LinearLayout? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "重启日志采集", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        acquireWake()
        showOverlay()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RebootLogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在采集重启日志")
            .setContentText("后台运行中 · 点此回到 App，或用悬浮窗/通知停止")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
        packageManager.getLaunchIntentForPackage(packageName)?.let { open ->
            b.setContentIntent(
                PendingIntent.getActivity(
                    this, 2, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return b.build()
    }

    private fun acquireWake() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RootGuide:rebootlog")
        }
        runCatching {
            if (wakeLock?.isHeld == false) wakeLock?.acquire(60 * 60 * 1000L)
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return

        val dm = resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0101826.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // 头部：标题 + 停止（也可作为拖动区域）
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "采集日志"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "■ 停止"
            setTextColor(0xFFFF8A80.toInt())
            textSize = 11f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener {
                stopRequested = true
                stopSelf()
            }
        })

        // 滚动日志窗口
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(170))
        }
        val log = TextView(this).apply {
            setTextColor(0xFFD6E2F5.toInt())
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(log)

        container.addView(header)
        container.addView(scroll)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点 + 非模态：窗外区域照常可以操作 App
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(140)
        }

        // 拖动头部移动悬浮窗，避免挡住 App
        header.setOnTouchListener(object : View.OnTouchListener {
            private var sx = 0
            private var sy = 0
            private var ox = 0
            private var oy = 0

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX.toInt(); sy = e.rawY.toInt()
                        ox = lp.x; oy = lp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = ox + (e.rawX.toInt() - sx)
                        lp.y = oy + (e.rawY.toInt() - sy)
                        runCatching { wm.updateViewLayout(container, lp) }
                        return true
                    }
                }
                return false
            }
        })

        if (runCatching { wm.addView(container, lp) }.isSuccess) {
            overlay = container
            logRef = log
            scrollRef = scroll
        }
    }

    override fun onDestroy() {
        runCatching {
            overlay?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        overlay = null
        logRef = null
        scrollRef = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "reboot_log"
        private const val NOTIF_ID = 98260
        private const val ACTION_STOP = "com.sean.rootguide.action.STOP_REBOOT_LOG"

        /** 停止标志：通知 / 悬浮窗 / Activity 采集循环都读它 */
        @Volatile var stopRequested = false
        @Volatile private var logRef: TextView? = null
        @Volatile private var scrollRef: ScrollView? = null

        fun start(ctx: Context) {
            stopRequested = false
            ctx.startForegroundService(Intent(ctx, RebootLogService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RebootLogService::class.java))
        }

        /** 采集循环持续把日志推到悬浮窗滚动窗口（自动滚到底；没有悬浮窗时忽略） */
        fun updateLog(text: String) {
            val tv = logRef ?: return
            tv.post {
                tv.text = text
                scrollRef?.post { scrollRef?.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }
}
