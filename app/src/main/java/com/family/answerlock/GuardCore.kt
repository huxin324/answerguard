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

    /** 日期戳缓存（避免每秒都 new 一个 SimpleDateFormat） */
    private var dayKeyCache = ""
    private var dayKeyAt = 0L

    /** 家长紧急解锁的默认免打扰时长（分钟）：这段时间不弹题、也不扣今日时长 */
    const val EMERGENCY_MIN = 10

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayOf(wall: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(wall))

    /** 带缓存的「今天」：心跳每秒都调，10 秒内不重复格式化 */
    private fun todayCached(now: Long): String {
        if (dayKeyCache.isNotEmpty() && now - dayKeyAt < 10_000L) return dayKeyCache
        dayKeyCache = dayOf(now)
        dayKeyAt = now
        return dayKeyCache
    }

    /** 两个时间戳是不是同一天（跨天的「中断」不算孩子逃课，不能扣时间） */
    fun sameDay(a: Long, b: Long): Boolean = dayOf(a) == dayOf(b)

    /**
     * 跨天自动重置（v3.2 修的严重 bug）。
     *
     * 原来「今日剩余时长」落盘在 remaining 里，**没有任何跨天清零逻辑**：
     * 昨天把 30 分钟用完 → remaining = 0 → 今天一打开 App 就直接显示「今日时长已用完」，
     * 孩子还没玩一分钟。OPPO 上装完第二天打开也是同一个原因。
     * 现在每次心跳 / 打开 App 都先对一下日期，换了天就把额度、题堆、锁定状态全部重置。
     */
    fun rolloverIfNeeded(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val today = todayCached(now)
        val last = p.getString("dayKey", "") ?: ""
        if (last == today) return

        val daily = p.getInt("dailyMin", 30).coerceAtLeast(1)
        val wasLockOverlay = p.getString("overlayMode", "") == "lock"
        p.edit()
            .putString("dayKey", today)
            .putInt("remaining", daily * 60)
            .putInt("pending", 0)
            .putBoolean("locked", false)
            .putBoolean("overlayOpen", false)
            .putString("overlayMode", "")
            .putLong("emergencyUntil", 0L)
            .putLong("lastAtWall", now)
            .putLong("lastBeatWall", now)
            .apply()
        lastAtWall = now
        flushCounter = 0
        dayKeyCache = today
        dayKeyAt = now
        // 跨天时正好停在「今日时间用完」那一屏 → 直接收掉，新的一天不该还被锁着
        if (wasLockOverlay) try { OverlayQuiz.get(c).close() } catch (e: Throwable) { }
        diag(c, "新的一天 → 今日时长重置为 $daily 分钟")
    }

    /** 家长紧急解锁：本轮题目作废，接下来 N 分钟不弹题、也不计使用时长 */
    fun startEmergency(c: Context, minutes: Int, reason: String) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val until = now + minutes * 60_000L
        p.edit()
            .putLong("emergencyUntil", until)
            .putInt("pending", 0)
            .putInt("emgCount", p.getInt("emgCount", 0) + 1)
            .putLong("lastAtWall", now)
            .apply()
        lastAtWall = now
        diag(c, "紧急解锁（$reason）→ 本轮题目作废，$minutes 分钟内不弹题、不计时长")
    }

    /** 紧急解锁还剩几秒（0 = 没有在紧急状态） */
    fun emergencyLeftSec(c: Context): Int {
        val left = (prefs(c).getLong("emergencyUntil", 0L) - System.currentTimeMillis()) / 1000L
        return if (left > 0L) left.toInt() else 0
    }

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

    /**
     * 每次到点弹题的初始题数：一次弹 5 题，答对才算过关（答错还会追加）。
     * 注意：本文件是 object GuardCore，object 里不能再写 companion object（编译报错
     * 「Companion object is not allowed here」），常量直接写在 object 体内即可。
     */
    const val QUIZ_BATCH = 3

    /* ================= 启动 / 恢复 ================= */

    /** 家长点「启动守护」：重置为全新的一天 */
    fun bootstrap(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val daily = p.getInt("dailyMin", 30).coerceAtLeast(1)
        p.edit()
            .putInt("remaining", daily * 60)
            .putBoolean("locked", false)
            .putInt("pending", 0)
            .putLong("lastAtWall", now)
            .putLong("lastBeatWall", now)
            .putString("dayKey", todayCached(now))
            .putLong("emergencyUntil", 0L)
            // v3.5 关键修复「一开守护就弹题」：清掉上一轮残留的弹题账
            // （overlayOpen=true 会让服务一创建就当「逃逸」重弹没答完的题）
            .putBoolean("overlayOpen", false)
            .putString("overlayMode", "")
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
     *
     * v3.2：先做跨天重置 —— 隔夜的中断绝不能算到今天的账上（这正是「刚打开就说今日
     * 时长已用完」的第二个原因：昨天 22 点被杀，今天 8 点打开，中断 10 小时被全额扣掉）。
     */
    fun restore(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("guardOn", false)) return
        rolloverIfNeeded(c)
        val now = System.currentTimeMillis()
        var last = p.getLong("lastAtWall", now)
        if (last <= 0L || last > now) last = now
        // 跨天的中断不补扣（rollover 已经把基准前移到 now 了，这里的 sameDay 只是双保险）
        val gone = if (sameDay(last, now)) ((now - last) / 1000L).toInt().coerceIn(0, 600) else 0
        val remaining = (p.getInt("remaining", 0) - gone).coerceAtLeast(0)
        p.edit().putInt("remaining", remaining).putLong("lastAtWall", now).apply()
        lastAtWall = now
        flushCounter = 0
        OverlayQuiz.get(c).warmup()
        diag(c, "守护恢复（补扣 $gone 秒）")
    }

    /**
     * 「题没答完，但进程是重新启动的」= 刚刚被强行结束过一次（上滑清理 / 一键清理 / 强杀）。
     *
     * 代码无法阻止华为的一键清理杀掉进程，但可以让"杀掉"变成亏本买卖：
     * 记一次逃逸，并把答错追加的题数（默认 2 题）压进 pending ——
     * 重新弹出来的那道题答对之后，还得再答这几道，等于白折腾还倒赔。
     */
    fun onEscape(c: Context) {
        val p = prefs(c)
        val esc = p.getInt("escapes", 0) + 1
        val pen = p.getInt("penalty", 2).coerceIn(1, 3)
        val pending = p.getInt("pending", 0) + pen
        p.edit().putInt("escapes", esc).putInt("pending", pending).apply()
        diag(c, "检测到被强行结束（第 $esc 次）→ 追加 $pen 题")
    }

    /* ================= 心跳 ================= */
    /**
     * 心跳。无障碍服务每秒调一次；AlarmManager 闹钟到点也调一次。
     * 只有「亮屏 + 没在答题」的时间才会被计入使用时长。
     */
    fun tick(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()

        // 跨天先重置额度：否则昨天的「0 分钟」会被带到今天（v3.2 修）
        rolloverIfNeeded(c)

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

        // 家长点了「紧急解锁」：这段免打扰时间里不弹题、也不扣时长（是家长在用手机），
        // 基准时间一直前移，所以过了这 10 分钟也不会反过来扣掉这段时间。
        val emgUntil = p.getLong("emergencyUntil", 0L)
        if (emgUntil > now) {
            lastAtWall = now
            flush(p, now)
            return
        }
        if (emgUntil > 0L) {
            p.edit().putLong("emergencyUntil", 0L).apply()
            diag(c, "紧急解锁结束 → 恢复弹题")
        }

        // 自愈：有题没答完，但窗口已经不在了（孩子划掉任务 / 系统移除窗口 / 进程被重建）
        // → 每 1 秒重新弹一次，删进程 ≠ 逃脱，必须答对才能离开。
        // v3.4：息屏时不重弹 —— 否则重弹的题会在锁屏后面悄悄倒计时、超时加题。
        if (p.getBoolean("overlayOpen", false) && !OverlayQuiz.isShowing && isScreenOn(c)) {
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
            // 到点弹题：一次压入 3 题（QUIZ_BATCH），答错还会额外追加
            val batch = p.getInt("pending", 0) + QUIZ_BATCH
            p.edit().putInt("remaining", rest).putInt("pending", batch)
                .putLong("lastAtWall", now).apply()
            OverlayQuiz.get(c).showQuiz()
            diag(c, "已弹题（本次 $QUIZ_BATCH 题 · 今日还剩 ${rest / 60} 分 ${rest % 60} 秒）")
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
            // lastBeatWall 是「进程还活着」的凭证：进程一旦被 force-stop，它就停在被杀那一刻，
            // 家长下次打开 App 时用它算出守护中断了多久（见 onGapKilled）。
            p.edit().putLong("lastAtWall", now).putLong("lastBeatWall", now).apply()
        }
    }

    /**
     * 守护中断补罚（v2.7）。
     *
     * force-stop 之后第三方 App 没有任何办法把自己拉起来（系统会拒绝 startService），
     * 所以"杀了就回不来"这一点绕不过去。但可以让"杀掉"变成一笔亏本账：
     * 下次打开 App 时，把中断的那段时间照样从今日额度里扣掉，并按中断时长追加惩罚题。
     * 孩子杀一次 10 分钟，代价是少 10 分钟 + 多答 7 道题，比老实答题难受得多。
     */
    fun onGapKilled(c: Context, gapMin: Long) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val pen = p.getInt("penalty", 2).coerceIn(1, 3)
        val extra = minOf((gapMin / 2).toInt(), 6)            // 每中断 2 分钟多 1 题，最多 +6
        val addQ = pen + extra
        val remain = p.getInt("remaining", p.getInt("dailyMin", 30) * 60)
        // 单次最多扣 10 分钟（和 restore 的上限保持一致）：中断很久多半是手机关机，
        // 全额扣会把「今日额度」一次清零 —— 那正是「刚打开就说时长用完」的来源。
        val dockSec = minOf(remain, 600, (gapMin * 60).toInt())  // 中断期间照样算一部分使用时长
        val esc = p.getInt("escapes", 0) + 1
        p.edit()
            .putInt("escapes", esc)
            .putInt("pending", p.getInt("pending", 0) + addQ)
            .putInt("remaining", remain - dockSec)
            .putLong("lastBeatWall", now)
            .putLong("lastAtWall", now)
            .putString("dayKey", todayCached(now))
            .apply()
        lastAtWall = now
        diag(c, "守护中断 ${gapMin} 分钟（第 $esc 次被结束）→ 扣除 $dockSec 秒、追加 $addQ 题")
    }
}
