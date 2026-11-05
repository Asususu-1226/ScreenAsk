package com.screenask.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream

/**
 * Optional Shizuku integration (requires the Shizuku app and its permission).
 * Used to grant permissions and enable the accessibility service without ADB by hand.
 */
object ShizukuHelper {
    const val PERMISSION_REQUEST_CODE = 4271

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
    }

    fun requestPermission(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    fun statusText(): String = when {
        !isAvailable() -> "未检测到 Shizuku（安装并启动 Shizuku 后可用）"
        !hasPermission() -> "Shizuku 已连接，尚未授权"
        else -> "Shizuku 已连接并已授权"
    }

    /** Runs a shell command through Shizuku (shell uid). Returns combined output. */
    fun run(command: String): String {
        if (!hasPermission()) return "未获得 Shizuku 权限"
        return runCatching {
            val binder = Shizuku.getBinder() ?: return "Shizuku 未连接"
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val descriptor = process.inputStream
            val input = FileInputStream(descriptor.fileDescriptor)
            val text = input.bufferedReader().use { it.readText() }
            runCatching { descriptor.close() }
            process.waitFor()
            text.trim()
        }.getOrElse { "执行失败：" + (it.message ?: it.javaClass.simpleName) }
    }

    fun grantRuntimePermissions(context: Context): String {
        val pkg = context.packageName
        val commands = mutableListOf(
            "pm grant $pkg android.permission.RECORD_AUDIO",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg PROJECT_MEDIA allow"
        )
        if (Build.VERSION.SDK_INT < 33) commands.removeAt(1)
        val results = commands.map { cmd -> cmd.substringAfter("pm grant ").substringAfter("appops set ").substringBefore(" ") + " -> " + run(cmd) }
        return results.joinToString("\n")
    }

    fun enableAccessibility(context: Context): String {
        val component = context.packageName + "/" + ScreenTextAccessibilityService::class.java.name
        val current = run("settings get secure enabled_accessibility_services").trim()
        val value = when {
            current.isBlank() || current == "null" -> component
            current.contains(component) -> return "无障碍服务已启用"
            else -> current + ":" + component
        }
        val result = run("settings put secure enabled_accessibility_services " + quote(value))
        run("settings put secure accessibility_enabled 1")
        return if (result.isBlank()) "已通过 Shizuku 启用无障碍服务" else result
    }

    fun disableAccessibility(context: Context): String {
        val component = context.packageName + "/" + ScreenTextAccessibilityService::class.java.name
        val current = run("settings get secure enabled_accessibility_services").trim()
        if (current.isBlank() || current == "null") return "无障碍服务未启用"
        val remaining = current.split(":").filter { it.isNotBlank() && it != component }.joinToString(":")
        run("settings put secure enabled_accessibility_services " + quote(remaining))
        return "已移除无障碍服务"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
