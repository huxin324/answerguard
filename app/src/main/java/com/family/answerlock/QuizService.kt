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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * 守护前台服务 = 计时主驱动 + 进程保活 + 闹钟兜底。
 *
 * 三重保险（针对华为鸿蒙的「后台冻结」）：
 * ① 媒体级保活：用 AudioTrack 循环播放一段全 0 的静音音频。
 *    系统会把本进程当成「正在播放媒体」的应用（和音乐 App 同级），不会被当作可回收进程冻结。
 *    音频数据全是 0，完全无声，耗电可忽略。
 * ② 前台服务 + 每秒心跳：前台通知抬高进程优先级，Handler 每秒 tick 一次结算时间，到点直接弹悬浮窗。
 * ③ AlarmManager.setAlarmClock 兜底：万一进程还是被压住，系统闹钟会准点唤醒服务（状态栏会显示闹钟图标）。
 *
 * 注：华为/鸿蒙禁止侧载安装的 App 开启「无障碍」权限（提示"应用暂不支持获取系统无障碍权限"），
 * 所以 v1.7 不再依赖无障碍，主动力换成本服务的静音保活 + 心跳。
 */
class QuizService : Service() {

    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())
    private var silentTrack: AudioTrack? = null

    private val ticker = object : Runnable {
        override fun run() {
            try {
                GuardCore.tick(this@QuizService)
            } catch (e: Throwable) {
                GuardCore.diag(this@QuizService, "心跳异常：${e.javaClass.simpleName}")
            }
            h.postDelayed(this, 1000)
        }
    }

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
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }

        startSilentKeepAlive()          // ① 媒体保活
        h.removeCallbacks(ticker)
        h.post(ticker)                  // ② 每秒心跳

        OverlayQuiz.get(this).let {
            it.onClosed = { GuardCore.afterOverlayClosed(this) }
            it.warmup()
        }

        // 进程刚被重建（孩子划掉任务 / 系统杀进程后重启）：有一题没答完就立刻弹回来
        if (prefs.getBoolean("overlayOpen", false)) {
            h.postDelayed({ OverlayQuiz.get(this@QuizService).resumePending() }, 600)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> {
                OverlayQuiz.get(this).warmup()
                OverlayQuiz.get(this).showQuiz()
            }
            ACTION_TICK -> {
                GuardCore.tick(this)
                scheduleSelf()
            }
            ACTION_RESUME -> {
                GuardCore.restore(this)
                scheduleSelf()
            }
            else -> {
                // 无 action ＝ 家长点「启动守护」：重置为全新一天
                GuardCore.bootstrap(this)
                scheduleSelf()
            }
        }
        return START_STICKY
    }

    /* ================= ① 媒体级静音保活 ================= */

    private fun startSilentKeepAlive() {
        if (silentTrack != null) return
        try {
            val sampleRate = 8000
            val samples = ShortArray(sampleRate)          // 1 秒全 0（完全无声）
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            t.write(samples, 0, samples.size)
            t.setLoopPoints(0, samples.size, -1)          // 无限循环
            t.setVolume(0.002f)                            // 数据本身是 0，实际无声
            t.play()
            silentTrack = t
            keepAlive = true
        } catch (e: Exception) {
            keepAlive = false
            GuardCore.diag(this, "媒体保活启动失败：${e.javaClass.simpleName}")
        }
    }

    private fun stopSilentKeepAlive() {
        try {
            silentTrack?.stop()
            silentTrack?.release()
        } catch (e: Exception) { /* ignore */ }
        silentTrack = null
        keepAlive = false
    }

    /* ================= ③ 闹钟兜底 ================= */

    private fun scheduleSelf() {
        if (!prefs.getBoolean("guardOn", false)) return
        val interval = prefs.getInt("intervalSec", 60)
        val remaining = prefs.getInt("remaining", 0)
        scheduleNext(minOf(interval, maxOf(remaining, 5)))
    }

    private fun scheduleNext(delaySec: Int) {
        val pi = tickPendingIntent()
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val wall = System.currentTimeMillis() + delaySec * 1000L

        // ① 闹钟类闹钟：系统几乎必定投递（状态栏会显示闹钟图标，这是「守护在跑」的凭证）
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(wall, null), pi)
            alarmMode = "闹钟"
            return
        } catch (e: Exception) { /* 退到精确闹钟 */ }

        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delaySec * 1000L, pi
            )
            alarmMode = "精确闹钟"
        } catch (e: Exception) {
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delaySec * 1000L, pi
                )
                alarmMode = "宽松闹钟"
            } catch (e2: Exception) {
                alarmMode = "闹钟失败"
            }
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

    /** 孩子上划划掉任务后：立刻重启服务 + 把没答完的题弹回来（划掉 ≠ 逃脱） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.getBoolean("guardOn", false)) {
            // ① 立刻拉起（本进程还活着时这一步就能成功，衔接最快）
            try {
                startForegroundService(
                    Intent(this, QuizService::class.java).setAction(ACTION_RESUME)
                )
            } catch (e: Exception) { /* 后台受限，靠下面的闹钟 */ }
            // ② 闹钟兜底：万一进程随后被系统杀，1 秒后也要把服务唤醒
            try {
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(this, RestartReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                (getSystemService(ALARM_SERVICE) as AlarmManager).setAlarmClock(
                    AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 1200, null), pi
                )
            } catch (e: Exception) { /* ignore */ }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        h.removeCallbacks(ticker)
        stopSilentKeepAlive()
        running = false
        // 不清理 guardOn：系统杀进程后闹钟到点会重新唤醒服务，守护自动恢复
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "guard"
        const val ACTION_TEST = "com.family.answerlock.TEST"
        const val ACTION_TICK = "com.family.answerlock.TICK"
        const val ACTION_START = "com.family.answerlock.START"
        const val ACTION_RESUME = "com.family.answerlock.RESUME"

        /** 兼容旧代码的字段 */
        @Volatile var quizOpen = false

        /** 服务是否活着（家长页自检用） */
        @Volatile var running = false

        /** 静音媒体保活是否在跑 */
        @Volatile var keepAlive = false

        /** 当前用的是哪种闹钟（诊断显示） */
        @Volatile var alarmMode = ""

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, QuizService::class.java).setAction(ACTION_START)
            )
        }

        fun resume(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, QuizService::class.java).setAction(ACTION_RESUME)
                )
            } catch (e: Exception) { /* 后台受限时可能抛异常 */ }
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
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            } catch (e: Exception) { /* ignore */ }
            GuardCore.prefs(context).edit()
                .putBoolean("guardOn", false).putBoolean("locked", false).apply()
            OverlayQuiz.get(context).close()
            context.stopService(Intent(context, QuizService::class.java))
        }
    }
}
