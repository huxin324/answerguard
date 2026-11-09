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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
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

    /**
     * 媒体会话（v2.6 新增）：只开静音 AudioTrack 还不够，系统的「正在播放」判定要看
     * MediaSession 的状态。把它置为 STATE_PLAYING，本进程在系统眼里就是一个"正在放音乐的 App"，
     * 华为手机管家的一键清理会跳过正在播放媒体的应用，上划卡片也会被当成"正在播放"而不是普通后台。
     * 这一步完全自动，不需要家长去最近任务里手动下拉加锁（那个加锁是 SystemUI 私有功能，App 无 API 可调）。
     */
    private var mediaSession: MediaSession? = null
    private var beatCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            try {
                GuardCore.tick(this@QuizService)
                // 每 10 秒刷一次「正在播放」状态，防止被 ROM 当成僵死会话清掉
                beatCount++
                if (beatCount % 10 == 0) refreshPlayback()
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
        startMediaSession()             // ①-a 先立起「正在播放」身份
        val n = buildGuardNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, fgType())
        } else {
            startForeground(1, n)
        }
        startSilentKeepAlive()          // ①-b 媒体保活（真正的静音音频）
        startGhostWindow()              // ①-c 隐形常驻窗口
        h.removeCallbacks(ticker)
        h.post(ticker)                  // ② 每秒心跳

        OverlayQuiz.get(this).let {
            it.onClosed = { GuardCore.afterOverlayClosed(this) }
            it.warmup()
        }

        // 进程刚被重建（孩子划掉任务 / 一键清理 / 系统杀进程后重启）：
        // ① 记一次「逃逸」并追加惩罚题（杀进程换不来便宜）
        // ② 立刻把没答完的那道题重新画出来（300ms 内，不等闹钟）
        if (prefs.getBoolean("overlayOpen", false)) {
            GuardCore.onEscape(this)
            h.postDelayed({ OverlayQuiz.get(this@QuizService).resumePending() }, 300)
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

    /* ================= ①-a 「正在播放」身份（自动防清理的关键） ================= */

    /**
     * 建立媒体会话，把状态置成 PLAYING。
     *
     * 为什么能防「一键清理」：厂商清理逻辑（华为手机管家、最近任务上划）通常会保留
     * 「正在播放音频/视频」的进程，因为杀掉会中断用户正在听的东西。我们放的是无声 PCM，
     * 但系统判定「是否在播放」看的是 AudioTrack.isPlaying + MediaSession 的 PlaybackState。
     * 只做前者，部分 ROM 仍当普通后台；两个都做，才会被归到「媒体播放」这一类。
     *
     * 副作用：音量键会显示媒体音量条、通知会带媒体样式。都可以接受，比被杀掉强。
     * 不去申请 AudioFocus（否则会把孩子正在听的歌/视频顶停，反而露馅）。
     */
    private fun startMediaSession() {
        if (mediaSession != null) return
        try {
            val ms = MediaSession(this, "AnswerGuard")
            ms.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "答题守护")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "运行中")
                    .build()
            )
            ms.setPlaybackState(playingState())
            ms.setActive(true)
            mediaSession = ms
            mediaReady = true
            GuardCore.diag(this, "已进入媒体播放状态（系统会按音乐类应用对待，不参与一键清理）")
        } catch (e: Throwable) {
            mediaReady = false
            GuardCore.diag(this, "媒体会话建立失败：${e.javaClass.simpleName}")
        }
    }

    private fun playingState(): PlaybackState =
        PlaybackState.Builder()
            // 只声明「播放中」，不给任何可点的 action —— 通知上没有暂停键，孩子没法暂停保活
            .setActions(0)
            .setState(PlaybackState.STATE_PLAYING, SystemClock.elapsedRealtime(), 1.0f)
            .build()

    /** 心跳里定期刷新播放位置：部分 ROM 会把「状态长时间不更新」的会话当死会话清掉 */
    private fun refreshPlayback() {
        try {
            mediaSession?.setPlaybackState(playingState())
            // AudioTrack 万一被系统打断（来电、其他 App 抢音频），在这里重新 play 起来
            silentTrack?.let { if (it.playState != AudioTrack.PLAYSTATE_PLAYING) it.play() }
        } catch (e: Throwable) { /* 忽略，下一轮再试 */ }
    }

    private fun fgType(): Int =
        if (Build.VERSION.SDK_INT >= 29)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        else 0

    /** 通知：媒体样式 + 绑定会话，进一步坐实「这是一个正在播放的媒体应用」 */
    private fun buildGuardNotification(): Notification {
        val nb =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        nb.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("答题守护运行中")
            .setContentText("到时间会自动弹题")
            .setOngoing(true)
            .setShowWhen(false)
        try {
            val style = Notification.MediaStyle()
            mediaSession?.let { style.setMediaSession(it.sessionToken) }
            nb.setStyle(style)
        } catch (e: Throwable) { /* 个别 ROM 不支持媒体样式就用普通通知 */ }
        return nb.build()
    }

    /* ================= ①-c 隐形常驻窗口（第 4 重保险） ================= */

    /**
     * 挂一个 1×1 像素、透明度 0.01 的窗口。
     *
     * 为什么有用：系统的「移除任务 / 一键清理」优先结束"纯后台"的进程；
     * 而带有可见窗口（Overlay）的应用仍处于可见状态，清理逻辑会更保守。
     * 窗口不可见、不可点、不抢焦点，除了一行权限说明外对家长没有任何感知。
     *
     * 注意：只在开关打开且已经授予悬浮窗权限时才挂，任何异常都静默跳过——
     * 失败也不影响前 3 重保险。
     */
    private var ghostView: android.view.View? = null

    private fun startGhostWindow() {
        if (ghostView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val v = android.view.View(this)
            val type = if (Build.VERSION.SDK_INT >= 26)
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            val lp = android.view.WindowManager.LayoutParams(
                1, 1, type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.x = 0
            lp.y = 0
            lp.alpha = 0.01f
            wm.addView(v, lp)
            ghostView = v
        } catch (e: Throwable) {
            GuardCore.diag(this, "常驻窗口未挂上（不影响弹题）：${e.javaClass.simpleName}")
        }
    }

    private fun stopGhostWindow() {
        try {
            ghostView?.let {
                (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it)
            }
        } catch (e: Throwable) { /* ignore */ }
        ghostView = null
    }

    /* ================= ①-b 媒体级静音保活 ================= */

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
     * 复活闹钟：把「几秒后叫醒自己」的闹钟一直挂在系统里，每次心跳都重排一次。
     *
     * 为什么必须这样设计（v1.9 之前的最大漏洞）：
     * 孩子上划 / 一键清理会把整个进程杀掉，杀完内存里的每秒心跳就没了。
     * 而 v1.9 只在「该弹题的时刻」排一个闹钟，题一弹出闹钟就被消耗掉了 ——
     * 之后系统里再没有任何东西会唤醒我们，进程一死就是永久死亡，这就是你视频里看到的现象。
     *
     * 现在同时挂两条（互不覆盖，因为 PendingIntent 的 requestCode 不同）：
     *  - setAlarmClock 5 秒：华为实测最不容易被压，进程一死就能把我们拉起来
     *  - setAndAllowWhileIdle 9 秒：万一闹钟类被系统丢弃，还有一条宽松闹钟兜底
     */
    private fun armRevive() {
        arm(REVIVE_SEC, ACTION_REVIVE, REQ_REVIVE)
        armBackup(REVIVE_SEC + 4L)
    }

    /** 第二重闹钟：宽松闹钟（不占状态栏，Doze 下最晚 9 分钟内也可能被提前，够用了） */
    private fun armBackup(delaySec: Long) {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delaySec * 1000L,
                alarmPi(this, ACTION_REVIVE, REQ_REVIVE_BACKUP)
            )
        } catch (e: Exception) { /* 拿不到就算了，主闹钟还在 */ }
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

    /** 任务被划掉：立刻把题画回来 + 拉起自己 + 补一条 1 秒闹钟（万一随后被系统杀） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.getBoolean("guardOn", false)) {
            // ① 进程还活着：立刻重弹（窗口被系统藏起来时，重画一遍比等闹钟快得多）
            try { OverlayQuiz.get(this).resumePending() } catch (e: Exception) { /* ignore */ }
            // ② 拉起自己
            try {
                startForegroundService(
                    Intent(this, QuizService::class.java).setAction(ACTION_REVIVE)
                )
            } catch (e: Exception) { /* 后台受限，靠下面的闹钟 */ }
            // ③ 闹钟兜底
            arm(1L, ACTION_REVIVE, REQ_REVIVE)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        h.removeCallbacks(ticker)
        stopSilentKeepAlive()
        stopGhostWindow()
        try {
            mediaSession?.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                    .build()
            )
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Throwable) { /* ignore */ }
        mediaSession = null
        mediaReady = false
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
        private const val REVIVE_SEC = 5L
        private const val REQ_TICK = 2001
        private const val REQ_REVIVE = 2002
        private const val REQ_REVIVE_BACKUP = 2003

        /** 兼容旧代码的字段 */
        @Volatile var quizOpen = false

        /** 服务是否活着（家长页自检用） */
        @Volatile var running = false

        /** 静音媒体保活是否在跑 */
        @Volatile var keepAlive = false

        /** 是否已经进入「媒体播放」状态（系统按音乐类应用对待，诊断显示用） */
        @Volatile var mediaReady = false

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
                am.cancel(alarmPi(context, ACTION_REVIVE, REQ_REVIVE_BACKUP))
            } catch (e: Exception) { /* ignore */ }
            GuardCore.prefs(context).edit()
                .putBoolean("guardOn", false).putBoolean("locked", false).apply()
            OverlayQuiz.get(context).close()
            context.stopService(Intent(context, QuizService::class.java))
        }
    }
}
