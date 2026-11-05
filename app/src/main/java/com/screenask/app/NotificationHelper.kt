package com.screenask.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    const val CHANNEL_MAIN = "screenask_main_v2"
    const val CHANNEL_ANSWER = "screenask_answer_v2"
    const val CHANNEL_SERVICE = "screenask_service_v2"

    const val LAUNCHER_ID = 42101
    const val ANSWER_ID = 42102
    const val SERVICE_ID = 42103
    const val SESSION_ID = 42104
    const val AUDIO_ID = 42105
    const val OVERLAY_ID = 42106

    const val REPLY_KEY = "screenask_reply_text"
    const val EXTRA_TARGET = "screenask_reply_target"
    const val TARGET_FOLLOW_UP = "follow_up"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIN, "识屏入口", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "通知栏识屏入口与快捷操作"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ANSWER, "AI 回答", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "识屏答案与追问结果"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "采集与任务状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "屏幕采集、录音与上传任务的前台通知"
                setShowBadge(false)
            }
        )
    }

    private fun immutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun mutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun activityIntent(context: Context, target: Class<*>, requestCode: Int, flags: Int = 0): PendingIntent {
        val intent = Intent(context, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (flags != 0) addFlags(flags)
        }
        return PendingIntent.getActivity(context, requestCode, intent, immutableFlags())
    }

    private fun captureIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, CaptureTriggerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(context, requestCode, intent, immutableFlags())
    }

    private fun followUpAction(context: Context, requestCode: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(REPLY_KEY)
            .setLabel("输入追问内容…")
            .build()
        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.ACTION_REPLY
            putExtra(EXTRA_TARGET, TARGET_FOLLOW_UP)
        }
        val pending = PendingIntent.getBroadcast(context, requestCode, replyIntent, mutableFlags())
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "追问", pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun copyAction(context: Context, answer: String): NotificationCompat.Action {
        val intent = Intent(context, ControlReceiver::class.java).apply {
            action = ControlReceiver.ACTION_COPY
            putExtra(ControlReceiver.EXTRA_TEXT, answer.take(120000))
        }
        val pending = PendingIntent.getBroadcast(context, 9001, intent, immutableFlags())
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_save, "复制", pending).build()
    }

    private fun settingsIntent(context: Context): PendingIntent = activityIntent(context, MainActivity::class.java, 9002)

    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun titleFor(kind: String): String = when (kind) {
        "followup" -> "AI 追问回答"
        "transcript" -> "听力转写结果"
        "text" -> "AI 识屏回答"
        else -> "AI 识屏回答"
    }

    private fun neutralTitle(context: Context): String =
        if (Prefs.load(context).neutralText) "系统通知" else "识屏问答"

    fun showLauncher(context: Context) {
        createChannels(context)
        val config = Prefs.load(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_MAIN)
            .setSmallIcon(R.drawable.ic_stat_screenask)
            .setContentTitle(if (config.neutralText) "快捷操作已就绪" else "识屏问答已就绪")
            .setContentText("点“识屏”开始；也可在通知栏直接追问")
            .setContentIntent(captureIntent(context, 100))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (config.silentNotifications) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_search, "识屏", captureIntent(context, 101))
            .addAction(followUpAction(context, 102))
            .addAction(android.R.drawable.ic_menu_manage, "设置", settingsIntent(context))
        if (config.silentNotifications) builder.setSilent(true)
        notify(context, LAUNCHER_ID, builder.build())
    }

    fun buildService(context: Context, text: String): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_screenask)
            .setContentTitle(neutralTitle(context))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(settingsIntent(context))
            .build()
    }

    /** Persistent recording session notification with full control actions. */
    fun buildSession(context: Context, text: String): Notification {
        createChannels(context)
        val stopIntent = Intent(context, ControlReceiver::class.java).apply {
            action = ControlReceiver.ACTION_STOP_SESSION
        }
        val stopPending = PendingIntent.getBroadcast(context, 9101, stopIntent, immutableFlags())
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_screenask)
            .setContentTitle(neutralTitle(context))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(settingsIntent(context))
            .addAction(android.R.drawable.ic_menu_search, "识屏", captureIntent(context, 110))
            .addAction(followUpAction(context, 111))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .build()
    }

    fun showSession(context: Context, text: String) {
        notify(context, SESSION_ID, buildSession(context, text))
    }

    fun cancelSession(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(SESSION_ID) }
    }

    fun showAnswer(context: Context, answer: String, title: String) {
        createChannels(context)
        val config = Prefs.load(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ANSWER)
            .setSmallIcon(R.drawable.ic_stat_screenask)
            .setContentTitle(if (config.neutralText) "系统通知" else title)
            .setContentText(answer.take(160))
            .setStyle(NotificationCompat.BigTextStyle().bigText(answer))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(if (config.silentNotifications) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(activityIntent(context, HistoryActivity::class.java, 120))
            .addAction(followUpAction(context, 121))
            .addAction(android.R.drawable.ic_menu_search, "再识屏", captureIntent(context, 122))
            .addAction(copyAction(context, answer))
        if (config.neutralText) builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        if (config.silentNotifications) builder.setSilent(true)
        notify(context, ANSWER_ID, builder.build())
    }

    fun showError(context: Context, message: String) {
        showAnswer(context, "请求失败：" + message, "识屏问答错误")
    }

    fun showTranscript(context: Context, text: String) {
        showAnswer(context, text, "听力转写结果")
    }

    fun audioRecordingNotification(context: Context, source: String): Notification {
        createChannels(context)
        val stopIntent = Intent(context, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(context, 9201, stopIntent, immutableFlags())
        val sourceText = if (source == "system") "系统内部音频" else "麦克风"
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_screenask)
            .setContentTitle(prefsTitle(context, "正在采集音频"))
            .setContentText(sourceText + "录音中，点击“停止并转写”上传 STT 接口")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "停止并转写", stopPending)
            .build()
    }

    fun overlayNotification(context: Context): Notification {
        createChannels(context)
        val stopIntent = Intent(context, ControlReceiver::class.java).apply {
            action = ControlReceiver.ACTION_STOP_OVERLAY
        }
        val stopPending = PendingIntent.getBroadcast(context, 9301, stopIntent, immutableFlags())
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_screenask)
            .setContentTitle(neutralTitle(context))
            .setContentText("悬浮球已开启：点按识屏，长按输入追问，可拖动")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(settingsIntent(context))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭悬浮球", stopPending)
            .build()
    }

    fun askProgress(context: Context, text: String): Notification = buildService(context, text)

    private fun prefsTitle(context: Context, title: String): String =
        if (Prefs.load(context).neutralText) "系统通知" else title
}
