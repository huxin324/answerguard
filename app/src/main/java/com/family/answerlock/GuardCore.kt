package com.family.answerlock

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 守护核心：唯一的时间账本 + 弹题决策。
 *
 * 为什么要把计时搬到这里：
 * 华为鸿蒙会把长时间在后台的 App「冻结」——冻结期间 AlarmManager 的闹钟不会被投递，
 * 要等 App 再次回到前台才补投递。这就是「进 App 就弹、离开 App 不弹」的真正原因。
 *
 * 现在时间账本由谁心跳驱动都行：
 *   ① 无障碍服务每秒 tick（系统绑定，不会被冻结）—— 主驱动
 *   ② QuizService 的 AlarmManager 闹钟到点 —— 兜底
 * 两个驱动共用同一份时间戳账本，先到先算、不会重复扣时。
 */
object GuardCore {

    const val PREFS = "guard"

    /** 内存中的结算基准（毫秒），避免每秒写一次 SharedPreferences */
    @Volatile private var lastAtWall = 0L
    private var flushCounter = 0

    /**
     * 最后一次心跳的墙钟时间（毫秒）。
     * 家长页用它判断「守护进程是不是被系统冻结了」：
     * 超过几秒不跳动，就说明进程被压住了（这正是之前不弹的直接原因）。
     */
    @Volatile var lastBeat = 0L
        private set

    /** 上次「重新弹题」的秒级时间，避免每秒都重试 */
    @Volatile private var lastReshowSec = 0L

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* ================= 日志 / 通知 ================= */

    fun diag(c: Context, msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        prefs(c).edit().putString("diag", "$t $msg").apply()
        updateNotification(c, "$t $msg")
    }

    /** 把最近一次动作写到常驻通知上：孩子玩别的 App 时下拉通知栏就能看到守护有没有在工作 */
    fun updateNotification(c: Context, txt: String) {
        try {
            val n = NotificationCompat.Builder(c, QuizService.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("答题守护运行中")
                .setContentText(txt)
                .setOngoing(true)
                .build()
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, n)
        } catch (e: Exception) { /* 通知失败不影响弹题 */ }
    }

    /* ================= 启动 / 恢复 ================= */

    /** 家长点「启动守护」：重置为全新的一天 */
    fun bootstrap(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        p.edit()
            .putInt("remaining", p.getInt("dailyMin", 30) * 60)
            .putBoolean("locked", false)
            .putInt("pending", 0)
            .putLong("lastAtWall", now)
            .putBoolean("guardOn", true)
            .apply()
        lastAtWall = now
        flushCounter = 0
        OverlayQuiz.get(c).warmup()
        diag(c, "守护已启动（${modeText(c)}）")
    }

    /**
     * 守护恢复（App 被杀后重回 / 开机 / 无障碍服务重新绑定）。
     * 把「不在的时间」照常扣掉，孩子强停 App 也绕不过去；单次最多补扣 10 分钟，
     * 避免关机一整晚后第二天直接没时间用。
     */
    fun restore(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("guardOn", false)) return
        val now = System.currentTimeMillis()
        var last = p.getLong("lastAtWall", now)
        if (last <= 0L || last > now) last = now
        val gone = ((now - last) / 1000L).toInt().coerceIn(0, 600)
        val remaining = (p.getInt("remaining", 0) - gone).coerceAtLeast(0)
        p.edit().putInt("remaining", remaining).putLong("lastAtWall", now).apply()
        lastAtWall = now
        flushCounter = 0
        OverlayQuiz.get(c).warmup()
        diag(c, "守护恢复（补扣 $gone 秒）")
    }

    /* ================= 心跳 ================= */

    /**
     * 心跳。无障碍服务每秒调一次；AlarmManager 闹钟到点也调一次。
     * 只有「亮屏 + 没在答题」的时间才会被计入使用时长。
     */
    fun tick(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()

        // 记录「心跳最长中断」：正常应约 1 秒。若出现几十秒的空洞，
        // 说明后台进程被系统冻结过（这就是不弹题的证据，家长页会直接显示）
        val prevBeat = lastBeat
        if (prevBeat > 0L) {
            val gap = (now - prevBeat) / 1000L
            if (gap >= 8L) {
                val prevMax = p.getLong("maxGapSec", 0L)
                if (gap > prevMax) p.edit().putLong("maxGapSec", gap).apply()
            }
        }
        lastBeat = now

        if (!p.getBoolean("guardOn", false)) return

        // 自愈：有题没答完，但窗口已经不在了（孩子划掉任务 / 系统移除窗口 / 进程被重建）
        // → 每 1 秒重新弹一次，删进程 ≠ 逃脱，必须答对才能离开。
        if (p.getBoolean("overlayOpen", false) && !OverlayQuiz.isShowing) {
            val t = now / 1000L
            if (t - lastReshowSec >= 1L) {
                lastReshowSec = t
                OverlayQuiz.get(c).resumePending()
            }
        }

        if (lastAtWall == 0L) {
            var last = p.getLong("lastAtWall", now)
            if (last <= 0L || last > now) last = now
            lastAtWall = last
        }
        if (lastAtWall > now) lastAtWall = now

        // 答题中 / 锁屏中 / 息屏 → 时间暂停，基准前移
        if (OverlayQuiz.isShowing || !isScreenOn(c)) {
            lastAtWall = now
            flush(p, now)
            return
        }

        val interval = p.getInt("intervalSec", 60).coerceAtLeast(5)
        val diff = now - lastAtWall
        if (diff < interval * 1000L) {
            flush(p, now)
            return
        }

        val elapsed = (diff / 1000L).toInt()
        val rest = p.getInt("remaining", p.getInt("dailyMin", 30) * 60) - elapsed
        lastAtWall = now
        flushCounter = 0

        if (rest <= 0) {
            p.edit().putInt("remaining", 0).putBoolean("locked", true).putLong("lastAtWall", now).apply()
            OverlayQuiz.get(c).showLock()
            diag(c, "今日时间用完 → 已锁屏")
        } else {
            p.edit().putInt("remaining", rest).putLong("lastAtWall", now).apply()
            OverlayQuiz.get(c).showQuiz()
            diag(c, "已弹题（今日还剩 ${rest / 60} 分 ${rest % 60} 秒）")
        }
    }

    /** 答题窗关闭后：从此刻重新开始计时 */
    fun afterOverlayClosed(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        lastAtWall = now
        flushCounter = 0
        p.edit().putLong("lastAtWall", now).apply()
    }

    /* ================= 供家长页显示 ================= */

    fun remaining(c: Context): Int = prefs(c).getInt("remaining", 0)

    /** 距离下次弹题还有几秒 */
    fun nextInSec(c: Context): Int {
        val p = prefs(c)
        if (!p.getBoolean("guardOn", false)) return 0
        val interval = p.getInt("intervalSec", 60)
        val now = System.currentTimeMillis()
        val last = if (lastAtWall > 0L) lastAtWall else p.getLong("lastAtWall", now).let {
            if (it <= 0L) now else it
        }
        return ((interval * 1000L - (now - last)) / 1000L).toInt().coerceAtLeast(0)
    }

    /** 当前靠什么在保活：静音媒体保活 / 无障碍 / 只有闹钟 */
    fun modeText(c: Context): String = when {
        GuardAccessibilityService.alive -> "无障碍 + 静音保活 ✅"
        QuizService.keepAlive -> "静音保活 ✅ 不会被冻结"
        else -> "仅闹钟 ⚠️ 容易被冻结"
    }

    /* ================= 内部 ================= */

    private fun isScreenOn(c: Context): Boolean {
        return try {
            (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        } catch (e: Exception) {
            true
        }
    }

    /** 每 5 秒落盘一次基准时间，减少 IO */
    private fun flush(p: SharedPreferences, now: Long) {
        flushCounter++
        if (flushCounter >= 5) {
            flushCounter = 0
            p.edit().putLong("lastAtWall", now).apply()
        }
    }
}
