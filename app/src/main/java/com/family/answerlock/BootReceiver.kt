package com.family.answerlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自动恢复守护：
 * 手机重启后如果之前守护是开启状态（guardOn=true），自动重新启动守护服务，
 * 堵住「孩子重启手机绕过守护」的口子。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
            if (prefs.getBoolean("guardOn", false)) {
                QuizService.start(context)
            }
        }
    }
}
