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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.screenrecorder.MainActivity
import com.example.screenrecorder.R
import com.example.screenrecorder.ui.FloatingCameraWindow
import com.example.screenrecorder.util.VideoMerger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
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

    private var isCameraOnlyMode = false

    // 所有录制片段，用于最终合并
    private val segments = mutableListOf<VideoMerger.Segment>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
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
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

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

        // 第一个片段：只录前置摄像头
        segments.clear()
        isCameraOnlyMode = true
        isRunning = true

        floatingCameraWindow = FloatingCameraWindow(
            context = this,
            onStopRecording = { stopRecording(); stopSelf() },
            onPauseRecording = { pauseRecording() },
            onResumeRecording = { resumeRecording() },
            onSwitchToCameraOnly = { switchToCameraOnly() },
            onSwitchToFullScreen = { switchToFullScreen() }
        )
        floatingCameraWindow?.show {
            // 摄像头准备好后开始录制
            outputFilePath = createOutputFile()
            floatingCameraWindow?.startCameraRecording(File(outputFilePath)) { _ -> }
            Log.d(TAG, "Recording started (camera-only): $outputFilePath")
        }
    }

    // ── 模式切换 ──────────────────────────────────────────────────

    private fun switchToCameraOnly() {
        if (!isRunning) return
        isCameraOnlyMode = true

        // 停止屏幕录制，保存片段信息（不需要转码）
        finishCurrentScreenSegment()

        // 开始摄像头录制
        outputFilePath = createOutputFile()
        floatingCameraWindow?.startCameraRecording(File(outputFilePath)) { _ ->
            // 摄像头录制完成的回调（停止时触发）— 不在这里处理，由 stopRecording 统一合并
        }
        Log.d(TAG, "Switched to camera-only: $outputFilePath")
    }

    private fun switchToFullScreen() {
        if (!isRunning) return
        isCameraOnlyMode = false

        // 停止摄像头录制，等文件写完后再开始屏幕录制
        val cameraSegmentPath = outputFilePath
        floatingCameraWindow?.stopCameraRecording {
            Log.d(TAG, "Camera segment finalized: $cameraSegmentPath, size=${File(cameraSegmentPath).length()}")
            segments.add(VideoMerger.Segment(cameraSegmentPath, needsTranscode = true))

            // 重新开始屏幕录制
            outputFilePath = createOutputFile()
            setupMediaRecorder()

            val surface = mediaRecorder?.surface ?: run {
                Log.e(TAG, "MediaRecorder surface is null"); return@stopCameraRecording
            }
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
            mediaRecorder?.start()
            Log.d(TAG, "Switched to full-screen: $outputFilePath")
        }
    }

    /** 结束当前的屏幕录制片段，加入 segments 列表 */
    private fun finishCurrentScreenSegment() {
        try { mediaRecorder?.stop() } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        segments.add(VideoMerger.Segment(outputFilePath, needsTranscode = false))
    }

    // ── 暂停/继续 ─────────────────────────────────────────────────

    private fun pauseRecording() {
        if (!isRunning) return
        try {
            if (isCameraOnlyMode) {
                floatingCameraWindow?.pauseCameraRecording()
            } else {
                mediaRecorder?.pause()
            }
            Log.d(TAG, "Recording paused")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing", e)
        }
    }

    private fun resumeRecording() {
        if (!isRunning) return
        try {
            if (isCameraOnlyMode) {
                floatingCameraWindow?.resumeCameraRecording()
            } else {
                mediaRecorder?.resume()
            }
            Log.d(TAG, "Recording resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming", e)
        }
    }

    // ── 停止录制 & 合并 ──────────────────────────────────────────

    private fun stopRecording() {
        if (!isRunning) return
        isRunning = false

        floatingCameraWindow?.hide()

        if (isCameraOnlyMode) {
            // 摄像头录制是异步的，等 Finalize 回调后再清理
            val lastPath = outputFilePath
            floatingCameraWindow?.stopCameraRecording {
                Log.d(TAG, "Camera segment finalized: $lastPath, size=${File(lastPath).length()}")
                segments.add(VideoMerger.Segment(lastPath, needsTranscode = true))
                finishStop()
            }
        } else {
            try { mediaRecorder?.stop() } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaRecorder", e)
            }
            mediaRecorder?.release()
            mediaRecorder = null
            virtualDisplay?.release()
            virtualDisplay = null
            segments.add(VideoMerger.Segment(outputFilePath, needsTranscode = false))
            finishStop()
        }
    }

    private fun finishStop() {
        floatingCameraWindow?.destroy()
        floatingCameraWindow = null

        mediaProjection?.stop()
        mediaProjection = null

        mergeAndSave()
    }

    private fun mergeAndSave() {
        val allSegments = segments.toList()
        segments.clear()

        if (allSegments.isEmpty()) {
            Log.w(TAG, "No segments to save"); return
        }

        // 调试模式：逐个保存每个片段，不合并
        Log.d(TAG, "Saving ${allSegments.size} segments individually for debugging")
        allSegments.forEachIndexed { index, segment ->
            Log.d(TAG, "Segment[$index]: ${segment.filePath}, needsTranscode=${segment.needsTranscode}, exists=${File(segment.filePath).exists()}, size=${File(segment.filePath).length()}")
            saveToMediaStore(segment.filePath)
        }

        mainHandler.post {
            Toast.makeText(this, "已保存 ${allSegments.size} 个片段", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────

    private fun createOutputFile(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "REC_$timestamp.mp4"
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
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(screenWidth, screenHeight)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8 * 1024 * 1024)
            setAudioEncodingBitRate(128 * 1024)
            setAudioSamplingRate(44100)
            setOutputFile(outputFilePath)
            prepare()
        }
    }

    private fun saveToMediaStore(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "Output file not found: $filePath"); return
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Recordings")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { Log.e(TAG, "Failed to create MediaStore entry"); return }

        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            Log.d(TAG, "Saved to DCIM/Recordings: $uri")
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to MediaStore", e)
            contentResolver.delete(uri, null, null)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_record)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_recording), stopPI)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc); setSound(null, null) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
