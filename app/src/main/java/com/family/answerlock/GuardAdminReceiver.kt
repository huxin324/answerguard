package com.family.answerlock

import android.app.admin.DeviceAdminReceiver

/**
 * 设备管理器（Device Admin）。
 *
 * 这是解决「孩子上滑 / 一键清理就能结束守护」唯一真正有效的系统级手段：
 * 应用被激活为设备管理器后，系统的「强行停止」按钮会变灰、上滑清理与一键清理都会跳过它，
 * 进程也就不会被杀掉（音乐 App、家长控制类 App 都用这一招）。
 *
 * 代价（需要提前让家长知道）：
 *  - 激活时系统会弹一次「要激活设备管理器吗」，点「激活」即可；
 *  - 以后要卸载本 App，必须先在本 App 或系统设置里「取消激活」，否则卸不掉。
 *
 * 我们不申请任何危险策略（不锁屏、不清数据、不改密码），只借它挡住「被强行停止」。
 */
class GuardAdminReceiver : DeviceAdminReceiver()
