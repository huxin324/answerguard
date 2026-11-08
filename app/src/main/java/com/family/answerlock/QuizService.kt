package com.family.answerlock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.SystemClock

/**
 * 守护前台服务（保活 + 闹钟兜底）。
 *
 * v1.6 起「计时」和「弹窗」都交给 GuardCore / OverlayQuiz：
 * - 主驱动：无障碍服务每秒心跳（不被鸿蒙冻结）→ 离开 App 也能准时弹题
 * - 本服务：常驻前台通知，把进程优先级抬高；同时用 AlarmManager 排闹钟做第二重保险
 *   （万一家长没开无障碍，就只剩这条路，可能被冻结，所以首页会提示去开无障碍）
 */
class QuizService : Service() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = GuardCore.prefs(this)
        running = true

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "守护运行中", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("答题守护运行中")
            .setContentText("到时间会自动弹题，请勿在设置里关闭本应用")
            .setOngoing(true)
            .build()
        startForeground(1, n)

        // 提前预热语音引擎和音效，弹题时立刻有声音
        OverlayQuiz.get(this).let {
            it.onClosed = { GuardCore.afterOverlayClosed(this) }
            it.warmup()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> {
                OverlayQuiz.get(this).warmup()
                OverlayQuiz.get(this).showQuiz()
            }
            ACTION_TICK -> {
                // 闹钟到点：结算时间并弹题（无障碍服务没开时这就是唯一驱动）
                GuardCore.tick(this)
                scheduleSelf()
            }
            ACTION_RESUME -> {
                GuardCore.restore(this)
                scheduleSelf()
            }
            else -> {
                // 兼容旧版调用（无 action）＝家长点「启动守护」
                GuardCore.bootstrap(this)
                scheduleSelf()
            }
        }
        return START_STICKY
    }

    /** 按当前配置排下一次闹钟 */
    private fun scheduleSelf() {
        if (!prefs.getBoolean("guardOn", false)) return
        val interval = prefs.getInt("intervalSec", 60)
        val remaining = prefs.getInt("remaining", 0)
        scheduleNext(minOf(interval, maxOf(remaining, 5)))
    }

    private fun scheduleNext(delaySec: Int) {
        val at = SystemClock.elapsedRealtime() + delaySec * 1000L
        val pi = tickPendingIntent()
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e2: Exception) { /* 闹钟排不上也不致命：无障碍心跳仍在工作 */ }
        }
    }

    private fun tickPendingIntent(): PendingIntent {
        val intent = Intent(this, QuizService::class.java).setAction(ACTION_TICK)
        return if (Build.VERSION.SDK_INT >= 26)
            PendingIntent.getForegroundService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        else
            PendingIntent.getService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    /** 孩子上划划掉任务后，1.5 秒后自动把服务拉起来 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.getBoolean("guardOn", false)) {
            try {
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(this, RestartReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                (getSystemService(ALARM_SERVICE) as AlarmManager).setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1500, pi
                )
            } catch (e: Exception) { /* 华为可能拒绝，无障碍守护仍在 */ }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 系统杀进程时保留 guardOn，守护状态不丢；只有家长点「停止守护」才会被清掉
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "guard"
        const val ACTION_TEST = "com.family.answerlock.TEST"
        const val ACTION_TICK = "com.family.answerlock.TICK"
        const val ACTION_START = "com.family.answerlock.START"
        const val ACTION_RESUME = "com.family.answerlock.RESUME"

        /** 兼容旧代码的字段（旧版 QuizActivity 引用过） */
        @Volatile var quizOpen = false

        /** 服务是否活着（家长页自检用） */
        @Volatile var running = false

        /** 家长点「启动守护」*/
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, QuizService::class.java).setAction(ACTION_START)
            )
        }

        /** 守护恢复（不重置时间）：开机 / 被系统杀掉后重新拉起时用 */
        fun resume(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, QuizService::class.java).setAction(ACTION_RESUME)
                )
            } catch (e: Exception) { /* 后台受限时可能抛异常，忽略 */ }
        }

        fun test(context: Context) {
            context.startForegroundService(
                Intent(context, QuizService::class.java).setAction(ACTION_TEST)
            )
        }

        fun stop(context: Context) {
            val intent = Intent(context, QuizService::class.java).setAction(ACTION_TICK)
            val pi = if (Build.VERSION.SDK_INT >= 26)
                PendingIntent.getForegroundService(
                    context, 2001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            else
                PendingIntent.getService(
                    context, 2001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            GuardCore.prefs(context).edit()
                .putBoolean("guardOn", false).putBoolean("locked", false).apply()
            OverlayQuiz.get(context).close()
            context.stopService(Intent(context, QuizService::class.java))
        }
    }
}
