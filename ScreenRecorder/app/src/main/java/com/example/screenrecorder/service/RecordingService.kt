package com.example.screenrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.screenrecorder.MainActivity
import com.example.screenrecorder.R
import com.example.screenrecorder.ui.FloatingCameraWindow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        const val NOTIFICATION_CHANNEL_ID = "screen_recorder_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set

        private const val TAG = "RecordingService"
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var floatingCameraWindow: FloatingCameraWindow? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var outputFilePath: String = ""
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        // H264 要求宽高必须是偶数（向下取偶）
        screenWidth = metrics.widthPixels and 1.inv()
        screenHeight = metrics.heightPixels and 1.inv()
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startRecording(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // 【修复1】Android 14+ 强制要求在 createVirtualDisplay 前注册 Callback
        // 否则投影被系统静默终止，导致 Surface 收不到任何帧 → 无画面
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (isRunning) {
                        Log.w(TAG, "MediaProjection stopped by system")
                        stopRecording()
                        stopSelf()
                    }
                }
            }
        }, mainHandler)

        // 【修复2】使用 App 私有目录作为录制临时文件
        // 直接写 DCIM 在 Android 11+ 无 WRITE_EXTERNAL_STORAGE 会导致视频轨静默丢帧
        outputFilePath = createOutputFile()

        setupMediaRecorder()

        val surface = mediaRecorder?.surface ?: run {
            Log.e(TAG, "MediaRecorder surface is null after prepare()")
            return
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        mediaRecorder?.start()
        isRunning = true

        floatingCameraWindow = FloatingCameraWindow(this) {
            stopRecording()
            stopSelf()
        }
        floatingCameraWindow?.show()

        Log.d(TAG, "Recording started: $outputFilePath (${screenWidth}x${screenHeight})")
    }

    private fun createOutputFile(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "REC_$timestamp.mp4"
        // 始终写入 App 私有目录，无需任何额外权限，Android 10/11/12/13/14 全兼容
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        dir.mkdirs()
        return File(dir, fileName).absolutePath
    }

    private fun setupMediaRecorder() {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            // 必须严格按此顺序：Source → Format → Encoder/Size/Rate → OutputFile → prepare
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(screenWidth, screenHeight)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8 * 1024 * 1024) // 8 Mbps
            setAudioEncodingBitRate(128 * 1024)       // 128 kbps
            setAudioSamplingRate(44100)
            setOutputFile(outputFilePath)
            prepare()
        }
    }

    private fun stopRecording() {
        if (!isRunning) return
        isRunning = false

        floatingCameraWindow?.hide()
        floatingCameraWindow = null

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }
        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        // 【修复3】录制完成后，将私有目录的临时文件正确复制到 DCIM/Recordings
        if (outputFilePath.isNotEmpty()) {
            saveToMediaStore(outputFilePath)
        }

        Log.d(TAG, "Recording stopped")
    }

    private fun saveToMediaStore(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "Output file not found: $filePath")
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Recordings")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // IS_PENDING = 1：占位写入，写完后设 0 通知媒体库
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.e(TAG, "Failed to create MediaStore entry")
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            // 写入完成，清除 IS_PENDING 标记，文件才在相册中可见
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val update = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, update, null, null)
            }
            Log.d(TAG, "Video saved to DCIM/Recordings: $uri")
            file.delete() // 删除私有目录中的临时文件
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to MediaStore", e)
            contentResolver.delete(uri, null, null)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_record)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_recording), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setSound(null, null)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
