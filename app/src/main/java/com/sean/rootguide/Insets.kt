package com.sean.rootguide

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35 起 Android 15 会强制 edge-to-edge：根布局直接顶到状态栏和导航栏下面。
 * 不补 inset 的表现就是标题被状态栏压住、底部按钮被手势条盖住。
 *
 * 做法是把 insets **加到布局原有的 padding 上**（而不是覆盖），这样既避开系统栏，
 * 又保留 XML 里设定的视觉间距。
 */
object Insets {

    fun applyTo(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
