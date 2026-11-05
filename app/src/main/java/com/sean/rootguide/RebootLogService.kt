package com.sean.rootguide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 后台持续采集的保活服务：
 *  - 前台服务 + 常驻通知（带「停止」），避免 App 退到后台后进程被系统/厂商清理；
 *  - 持有 PARTIAL_WAKE_LOCK，减少息屏后 CPU 休眠导致的采集停顿；
 *  - 若已授予悬浮窗权限，显示一个小悬浮窗：标题条（状态 + 折叠/展开 + 停止）+ 滚动日志。
 *    · 展开态显示完整滚动日志；**闲置 8 秒自动折叠**成一条细窄状态条，不挡 App；
 *    · 点标题条（或 ▸/▾）随时展开/收起；按住标题条可拖动；折叠时进度仍在状态条上更新。
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
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0101826.toInt())
            setPadding(dp(10), dp(6), dp(6), dp(8))
        }

        // 头部：红点 + 标题 + 折叠态进度 + 展开/收起 + 停止；整条可拖动，轻点可折叠/展开
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(28)
        }
        header.addView(TextView(this).apply {
            text = "●"
            setTextColor(0xFFFF5252.toInt())
            textSize = 9f
            setPadding(0, 0, dp(5), 0)
        })
        header.addView(TextView(this).apply {
            text = "采集日志"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        // 折叠后显示在这里（轮次 / 项数 / root），避免折叠就看不到进度
        val status = TextView(this).apply {
            text = ""
            setTextColor(0xFF9FB2CC.toInt())
            textSize = 10f
            setPadding(dp(6), 0, dp(4), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        header.addView(status)
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        val toggle = TextView(this).apply {
            text = "▾"
            setTextColor(0xFFB9C9E2.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(1), dp(8), dp(1))
            // 显式点击也保留：折叠态下这就是「展开」按钮
            setOnClickListener { toggleCollapsed() }
        }
        header.addView(toggle)
        header.addView(TextView(this).apply {
            text = "■ 停止"
            setTextColor(0xFFFF8A80.toInt())
            textSize = 11f
            setPadding(dp(6), dp(2), dp(8), dp(2))
            setOnClickListener {
                stopRequested = true
                stopSelf()
            }
        })

        // 滚动日志窗口（展开态）
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

        // 头部：拖动移动窗口；没有位移的轻点 = 折叠/展开
        header.setOnTouchListener(object : View.OnTouchListener {
            private var sx = 0
            private var sy = 0
            private var ox = 0
            private var oy = 0
            private var downAt = 0L
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX.toInt(); sy = e.rawY.toInt()
                        ox = lp.x; oy = lp.y
                        downAt = System.currentTimeMillis()
                        moved = false
                        // 用户开始操作：先别自动折叠，免得手指还在点它就缩了
                        cancelAutoCollapse()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX.toInt() - sx
                        val dy = e.rawY.toInt() - sy
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true
                        if (moved) {
                            lp.x = ox + dx
                            lp.y = oy + dy
                            runCatching { wm.updateViewLayout(container, lp) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved && System.currentTimeMillis() - downAt < 400) toggleCollapsed()
                        scheduleAutoCollapse()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        scheduleAutoCollapse()
                        return true
                    }
                }
                return false
            }
        })

        if (runCatching { wm.addView(container, lp) }.isSuccess) {
            overlay = container
            containerRef = container
            logRef = log
            scrollRef = scroll
            statusRef = status
            toggleRef = toggle
            mainHandler = Handler(Looper.getMainLooper())
            collapsed = false
            // 启动后先展开给用户看一眼，闲置即自动折叠
            scheduleAutoCollapse()
        }
    }

    override fun onDestroy() {
        cancelAutoCollapse()
        runCatching {
            overlay?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        overlay = null
        containerRef = null
        logRef = null
        scrollRef = null
        statusRef = null
        toggleRef = null
        mainHandler = null
        collapsed = false
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "reboot_log"
        private const val NOTIF_ID = 98260
        private const val ACTION_STOP = "com.sean.rootguide.action.STOP_REBOOT_LOG"

        /** 展开后闲置多久自动折叠（毫秒）。想改成常驻展开，调大这个值即可 */
        private const val AUTO_COLLAPSE_MS = 8_000L

        /** 停止标志：通知 / 悬浮窗 / Activity 采集循环都读它 */
        @Volatile var stopRequested = false

        @Volatile private var containerRef: LinearLayout? = null
        @Volatile private var logRef: TextView? = null
        @Volatile private var scrollRef: ScrollView? = null
        @Volatile private var statusRef: TextView? = null
        @Volatile private var toggleRef: TextView? = null
        @Volatile private var mainHandler: Handler? = null
        @Volatile private var autoTask: Runnable? = null

        /** 当前是否处于折叠态（只显示标题条） */
        @Volatile var collapsed = false
            private set

        /** 切换折叠/展开（标题条轻点、▸/▾ 都走这里） */
        fun toggleCollapsed() {
            setCollapsed(!collapsed, byUser = true)
        }

        /**
         * 设置折叠状态。
         * 折叠：隐藏滚动日志，状态条改为显示「第 N 轮 · 已写 N 项 · root/shell」，窗口缩到一条细窄横条；
         * 展开：恢复滚动日志并滚到最新一行，同时重新计时（闲置 8 秒再自动折叠）。
         */
        fun setCollapsed(value: Boolean, byUser: Boolean = false) {
            val h = mainHandler ?: return
            h.post {
                if (!value && collapsed == false && byUser) {
                    // 已经是展开态：仅重置计时，不重复布局
                    scheduleAutoCollapse()
                    return@post
                }
                collapsed = value
                scrollRef?.visibility = if (value) View.GONE else View.VISIBLE
                statusRef?.visibility = if (value) View.VISIBLE else View.GONE
                toggleRef?.text = if (value) "▸" else "▾"
                // 折叠时窗口宽度跟着收缩，避免留下大片空白挡视线
                containerRef?.requestLayout()
                if (value) {
                    cancelAutoCollapse()
                } else {
                    scrollRef?.post { scrollRef?.fullScroll(View.FOCUS_DOWN) }
                    scheduleAutoCollapse()
                }
            }
        }

        /** 展开后闲置 [AUTO_COLLAPSE_MS] 自动折叠；用户一碰窗口就取消，松手/结束后重新计时 */
        fun scheduleAutoCollapse() {
            val h = mainHandler ?: return
            cancelAutoCollapse()
            if (collapsed) return
            val r = Runnable { if (!collapsed) setCollapsed(true) }
            autoTask = r
            h.postDelayed(r, AUTO_COLLAPSE_MS)
        }

        fun cancelAutoCollapse() {
            val h = mainHandler ?: return
            autoTask?.let { h.removeCallbacks(it) }
            autoTask = null
        }

        fun start(ctx: Context) {
            stopRequested = false
            ctx.startForegroundService(Intent(ctx, RebootLogService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RebootLogService::class.java))
        }

        /**
         * 采集循环持续把日志推到悬浮窗：
         *  - 展开态：整段显示并滚到底；
         *  - 折叠态：只把首行（第 N 轮 · 已写 N 项 · root/shell）刷到状态条，窗口保持不挡视线。
         */
        fun updateLog(text: String) {
            val tv = logRef ?: return
            val head = text.lineSequence().firstOrNull().orEmpty().trim()
            tv.post {
                tv.text = text
                val s = statusRef
                if (collapsed) {
                    if (s != null && head.isNotEmpty()) s.text = head
                } else {
                    scrollRef?.post { scrollRef?.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}
