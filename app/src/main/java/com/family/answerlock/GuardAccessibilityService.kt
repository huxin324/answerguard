package com.family.answerlock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍守护服务 —— 真正让「离开 App 也能弹题」成立的关键。
 *
 * 原理：
 * 华为鸿蒙会把待在后台的 App「冻结」，冻结期间后台服务的 Handler 会停、AlarmManager 闹钟
 * 也不投递（要等 App 回到前台才补发）——这就是之前「进 App 就弹、离开 App 不弹」的原因。
 * 无障碍服务是系统绑定的特殊服务，系统需要它持续响应界面事件，所以它不会被冻结，
 * 它里面的 Handler 每秒心跳是可靠的。
 *
 * 因此本服务每秒调一次 GuardCore.tick()：到点就用 WindowManager 弹悬浮窗。
 * 只监听「窗口切换」事件获取前台包名用于计时判断，不读取任何屏幕内容。
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        /** 服务是否已连接（用于自检显示） */
        @Volatile var alive = false
            private set

        /** 最近一次前台应用包名（调试用） */
        @Volatile var lastPkg = ""

        /** 从系统设置里读「无障碍服务是否已由用户开启」 */
        fun isEnabled(c: Context): Boolean {
            val flat = try {
                Settings.Secure.getString(
                    c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (e: Exception) {
                null
            } ?: return false
            val full = c.packageName + "/" + GuardAccessibilityService::class.java.name
            val short = c.packageName + "/.GuardAccessibilityService"
            return flat.split(':').any {
                it.equals(full, true) || it.equals(short, true)
            }
        }

        /** 跳到系统无障碍设置页，让家长手动打开开关 */
        fun openSettings(c: Context) {
            try {
                c.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                try {
                    c.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e2: Exception) { /* 打不开就让家长手动找 */ }
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            try {
                GuardCore.tick(this@GuardAccessibilityService)
            } catch (e: Throwable) {
                GuardCore.diag(this@GuardAccessibilityService, "心跳异常：${e.javaClass.simpleName}")
            }
            // 1 秒一次心跳，醒来即结算，绝不依赖会被冻结的闹钟
            h.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = true
        OverlayQuiz.get(this).onClosed = { GuardCore.afterOverlayClosed(this) }
        GuardCore.restore(this)
        h.removeCallbacks(ticker)
        h.post(ticker)
        GuardCore.diag(this, "无障碍守护已接管计时 ✅ 离开 App 也能弹")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != lastPkg) {
            lastPkg = pkg
            GuardCore.prefs(this).edit().putString("fgApp", pkg).apply()
        }
        // 顺手推一次心跳：孩子刚切到新应用，立刻把账算清
        GuardCore.tick(this)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        alive = false
        h.removeCallbacks(ticker)
        GuardCore.diag(this, "⚠️ 无障碍被关闭，守护退回闹钟模式（可能会被系统冻结）")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        alive = false
        h.removeCallbacks(ticker)
        super.onDestroy()
    }
}
