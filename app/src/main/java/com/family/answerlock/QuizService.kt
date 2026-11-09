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
import android.os.PowerManager
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
                // 每次心跳都把「复活闹钟」往后推 6 秒：只要进程活着，系统里就永远有一个
                // 6 秒后的闹钟等着接手；进程一旦被杀，这个闹钟就会把我们重新叫起来。
                // 只在「守护已开启 + 亮屏」时挂这个短闹钟（息屏本来就不计时，避免整夜反复唤醒耗电）
                if (prefs.getBoolean("guardOn", false) &&
                    (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
                ) armRevive()
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
            GuardCore.diag(this, "检测到进程被重建 → 立刻把没答完的题弹回来")
            h.postDelayed({ OverlayQuiz.get(this@QuizService).resumePending() }, 600)
        }

        // 服务一启动就把两条闹钟都排上（复活链条 + 下次弹题）
        scheduleSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> {
                OverlayQuiz.get(this).warmup()
                OverlayQuiz.get(this).showQuiz()
            }
            ACTION_REVIVE -> {
                // 系统闹钟把我们从「死亡」里叫醒：结算时间、重排链条。
                // 有题没答完的重新弹窗在 onCreate 里已经做过了。
                GuardCore.tick(this)
                scheduleSelf()
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
                // 家长点「启动守护」
                // 已经在守护中 → 只更新配置，不重置今天的时间（避免家长改一下设置就把时间清零）
                if (prefs.getBoolean("guardOn", false)) {
                    scheduleSelf()
                    GuardCore.diag(this, "配置已更新（守护继续，时间不重置）")
                } else {
                    GuardCore.bootstrap(this)
                    scheduleSelf()
                }
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

    /* ================= ③ 闹钟：自愈链条（进程被杀也能自己回来） ================= */

    /**
     * 复活闹钟：把「6 秒后叫醒自己」的闹钟一直挂在系统里，每次心跳都重排一次。
     *
     * 为什么必须这样设计（v1.9 之前的最大漏洞）：
     * 孩子上划 / 一键清理会把整个进程杀掉，杀完内存里的每秒心跳就没了。
     * 而 v1.9 只在「该弹题的时刻」排一个闹钟，题一弹出闹钟就被消耗掉了 ——
     * 之后系统里再没有任何东西会唤醒我们，进程一死就是永久死亡，这就是你视频里看到的现象。
     *
     * 现在系统里永远挂着 6 秒后的闹钟（闹钟由系统保管，不依赖 App 是否活着）：
     * 进程被杀 → 6 秒内系统把我们拉起来 → 服务一启动发现「有一题没答完」→ 立刻重新画出来。
     */
    private fun armRevive() {
        arm(REVIVE_SEC, ACTION_REVIVE, REQ_REVIVE)
    }

    private fun scheduleSelf() {
        if (!prefs.getBoolean("guardOn", false)) return
        armRevive()
        val interval = prefs.getInt("intervalSec", 60).coerceAtLeast(5)
        val remain = prefs.getInt("remaining", 0)
        arm(minOf(interval, maxOf(remain, 5)).toLong(), ACTION_TICK, REQ_TICK)
    }

    /**
     * 统一排闹钟。
     *
     * ① setAlarmClock（闹钟类）必须排第一：华为实测它不会被 Doze 延迟投递（v1.7~v1.9 靠它弹题成功），
     *    且进程被杀后它也能把服务叫起来；代价只是状态栏短暂显示闹钟图标。
     * ② setExactAndAllowWhileIdle 只作后备：华为 Doze 下会把这类精确闹钟压住不投递
     *    （v2.0 把它排第一，结果进程被清后永远叫不回来 —— 这就是 v2.0 不弹题的根因）。
     */
    private fun arm(delaySec: Long, action: String, reqCode: Int) {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = alarmPi(this, action, reqCode)
        val elapsed = SystemClock.elapsedRealtime() + delaySec * 1000L
        val wall = System.currentTimeMillis() + delaySec * 1000L

        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(wall, null), pi)
            alarmMode = "闹钟"
            return
        } catch (e: Exception) { /* 退到精确闹钟 */ }

        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                throw IllegalStateException("no exact alarm permission")
            }
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, pi)
            alarmMode = "精确闹钟"
            return
        } catch (e: Exception) { /* 退到宽松闹钟 */ }

        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, pi)
            alarmMode = "宽松闹钟"
        } catch (e2: Exception) {
            alarmMode = "闹钟失败"
        }
    }

    /** 任务被划掉：立刻拉起自己，并补一个 1 秒后的闹钟（万一随后被系统杀） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.getBoolean("guardOn", false)) {
            try {
                startForegroundService(
                    Intent(this, QuizService::class.java).setAction(ACTION_REVIVE)
                )
            } catch (e: Exception) { /* 后台受限，靠下面的闹钟 */ }
            arm(1L, ACTION_REVIVE, REQ_REVIVE)
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
        const val ACTION_REVIVE = "com.family.answerlock.REVIVE"
        const val ACTION_START = "com.family.answerlock.START"
        const val ACTION_RESUME = "com.family.answerlock.RESUME"

        /** 复活闹钟提前量（秒）：进程被杀后，最多这么久就会被系统叫醒 */
        private const val REVIVE_SEC = 6L
        private const val REQ_TICK = 2001
        private const val REQ_REVIVE = 2002

        /** 兼容旧代码的字段 */
        @Volatile var quizOpen = false

        /** 服务是否活着（家长页自检用） */
        @Volatile var running = false

        /** 静音媒体保活是否在跑 */
        @Volatile var keepAlive = false

        /** 当前用的是哪种闹钟（诊断显示） */
        @Volatile var alarmMode = ""

        /** 统一的闹钟 PendingIntent（后台受限时用 getForegroundService 才能拉起服务） */
        private fun alarmPi(c: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(c, QuizService::class.java).setAction(action)
            return if (Build.VERSION.SDK_INT >= 26)
                PendingIntent.getForegroundService(
                    c, reqCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            else
                PendingIntent.getService(
                    c, reqCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
        }

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
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(alarmPi(context, ACTION_TICK, REQ_TICK))
                am.cancel(alarmPi(context, ACTION_REVIVE, REQ_REVIVE))
            } catch (e: Exception) { /* ignore */ }
            GuardCore.prefs(context).edit()
                .putBoolean("guardOn", false).putBoolean("locked", false).apply()
            OverlayQuiz.get(context).close()
            context.stopService(Intent(context, QuizService::class.java))
        }
    }
}
