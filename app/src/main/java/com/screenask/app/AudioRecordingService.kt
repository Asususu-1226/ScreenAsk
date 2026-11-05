package com.screenask.app

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Records audio for the STT flow.
 * source = "mic"    -> microphone (MediaRecorder, m4a)
 * source = "system" -> internal playback capture (MediaProjection + AudioRecord, wav)
 */
class AudioRecordingService : Service() {
    companion object {
        const val ACTION_START = "com.screenask.app.action.AUDIO_START"
        const val ACTION_STOP = "com.screenask.app.action.AUDIO_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SOURCE = "extra_source"
        const val SOURCE_MIC = "mic"
        const val SOURCE_SYSTEM = "system"

        fun start(context: Context, source: String, resultCode: Int? = null, data: Intent? = null) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE, source)
                if (resultCode != null) putExtra(EXTRA_RESULT_CODE, resultCode)
                if (data != null) putExtra(EXTRA_RESULT_DATA, data)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var projection: MediaProjection? = null
    private var rawFile: File? = null
    private var outputFile: File? = null
    private var source = SOURCE_MIC
    @Volatile private var recording = false
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (recording) return START_NOT_STICKY
                source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_MIC
                begin(intent)
            }
            ACTION_STOP -> finishRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            NotificationHelper.showError(this, "缺少录音权限，请在应用中授予麦克风权限")
            stopSelf()
            return
        }
        val type = if (source == SOURCE_SYSTEM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        val notification = NotificationHelper.audioRecordingNotification(this, source)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NotificationHelper.AUDIO_ID, notification, type)
            } else {
                startForeground(NotificationHelper.AUDIO_ID, notification)
            }
        } catch (e: Exception) {
            NotificationHelper.showError(this, "无法启动录音前台服务：" + (e.message ?: ""))
            stopSelf()
            return
        }
        try {
            if (source == SOURCE_SYSTEM) beginSystemAudio(intent) else beginMicrophone()
        } catch (e: Exception) {
            releaseAll()
            NotificationHelper.showError(this, "无法开始录音：" + (e.message ?: ""))
            stopSelf()
        }
    }

    private fun beginMicrophone() {
        val file = File(cacheDir, "lesson_mic_" + System.currentTimeMillis() + ".m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder.setAudioEncodingBitRate(96000)
        mediaRecorder.setAudioSamplingRate(44100)
        mediaRecorder.setOutputFile(file.absolutePath)
        mediaRecorder.prepare()
        mediaRecorder.start()
        recorder = mediaRecorder
        outputFile = file
        recording = true
    }

    private fun beginSystemAudio(intent: Intent) {
        if (Build.VERSION.SDK_INT < 29) {
            throw IllegalStateException("Android 10 以下不支持系统内部音频采集，请改用麦克风模式")
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) throw IllegalStateException("缺少屏幕采集授权，无法采集系统音频")
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("系统未返回屏幕采集会话")
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {}, null)

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBuffer * 2).coerceAtLeast(8192)
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("无法创建内部音频采集通道")
        }
        val raw = File(cacheDir, "lesson_system_" + System.currentTimeMillis() + ".pcm")
        rawFile = raw
        outputFile = File(cacheDir, "lesson_system_" + System.currentTimeMillis() + ".wav")
        record.startRecording()
        audioRecord = record
        recording = true
        val thread = Thread {
            try {
                FileOutputStream(raw).use { out ->
                    val buffer = ShortArray(bufferSize / 2)
                    while (recording) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val bytes = ByteArray(read * 2)
                            var i = 0
                            while (i < read) {
                                val value = buffer[i].toInt()
                                bytes[i * 2] = (value and 0xFF).toByte()
                                bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                                i++
                            }
                            out.write(bytes)
                        } else if (read < 0) {
                            break
                        }
                    }
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
        recordThread = thread
        thread.start()
    }

    private fun finishRecording() {
        if (!recording || stopping) {
            stopSelf()
            return
        }
        stopping = true
        recording = false
        val capturedSource = source
        if (capturedSource == SOURCE_MIC) {
            runCatching { recorder?.stop() }
        } else {
            runCatching { audioRecord?.stop() }
        }
        val thread = recordThread
        if (thread != null) {
            runCatching { thread.join(2000) }
        }
        releaseAll()

        val file: File? = if (capturedSource == SOURCE_SYSTEM) {
            convertToWav(rawFile, outputFile)
        } else {
            outputFile
        }
        if (file == null || !file.exists() || file.length() == 0L) {
            NotificationHelper.showError(this, "录音文件为空，可能录制时间太短")
            stopSelf()
            return
        }

        val progress = NotificationHelper.buildService(this, "录音已停止，正在上传并转写…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.AUDIO_ID,
                    progress,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.AUDIO_ID, progress)
            }
        } catch (_: Exception) {
        }

        scope.launch {
            try {
                val config = Prefs.load(this@AudioRecordingService)
                val profile = config.stt
                if (profile.baseUrl.isBlank() || profile.model.isBlank()) {
                    throw IllegalStateException("请在设置中填写 STT 接口与模型")
                }
                val transcript = withContext(Dispatchers.IO) {
                    OpenAiClient.transcribe(profile, config, file)
                }
                Prefs.saveLastAnswer(this@AudioRecordingService, transcript)
                if (config.keepHistory) {
                    HistoryStore.add(
                        this@AudioRecordingService,
                        kind = "transcript",
                        question = if (capturedSource == SOURCE_SYSTEM) "系统内部音频" else "麦克风录音",
                        answer = transcript,
                        model = profile.model,
                        hasImage = false,
                        limit = config.historyLimit
                    )
                }
                NotificationHelper.showTranscript(this@AudioRecordingService, transcript)
            } catch (e: Exception) {
                NotificationHelper.showError(this@AudioRecordingService, "听力转写失败：" + (e.message ?: ""))
            } finally {
                runCatching { file.delete() }
                runCatching { ServiceCompat.stopForeground(this@AudioRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun convertToWav(raw: File?, wav: File?): File? {
        if (raw == null || !raw.exists() || raw.length() == 0L) return null
        if (wav == null) return null
        return try {
            val dataSize = raw.length().toInt()
            FileOutputStream(wav).use { out ->
                out.write(wavHeader(dataSize))
                raw.inputStream().use { input -> input.copyTo(out) }
            }
            raw.delete()
            wav
        } catch (_: Exception) {
            null
        }
    }

    private fun wavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        val totalSize = dataSize + 36
        fun writeAscii(offset: Int, value: String) {
            for (i in value.indices) header[offset + i] = value[i].code.toByte()
        }
        fun writeInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun writeShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        writeAscii(0, "RIFF")
        writeInt(4, totalSize)
        writeAscii(8, "WAVE")
        writeAscii(12, "fmt ")
        writeInt(16, 16)
        writeShort(20, 1)
        writeShort(22, 1)
        writeInt(24, 44100)
        writeInt(28, 44100 * 2)
        writeShort(32, 2)
        writeShort(34, 16)
        writeAscii(36, "data")
        writeInt(40, dataSize)
        return header
    }

    private fun releaseAll() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        recordThread = null
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        recording = false
        releaseAll()
        scope.cancel()
        super.onDestroy()
    }
}
