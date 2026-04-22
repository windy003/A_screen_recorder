package com.example.screenrecorder.ui

import android.content.Context
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.screenrecorder.R
import java.io.File
import kotlin.math.roundToInt

class FloatingCameraWindow(
    private val context: Context,
    private val onStopRecording: () -> Unit,
    private val onPauseRecording: () -> Unit,
    private val onResumeRecording: () -> Unit,
    private val onSwitchToCameraOnly: () -> Unit,
    private val onSwitchToFullScreen: () -> Unit
) : LifecycleOwner {

    companion object {
        private const val TAG = "FloatingCameraWindow"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // 摄像头悬浮窗
    private var cameraView: View? = null
    private var cameraParams: WindowManager.LayoutParams? = null
    private var previewView: PreviewView? = null

    // 控制面板悬浮窗
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val metrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(it)
    }

    private val density = context.resources.displayMetrics.density
    private val minSizePx = (80 * density).roundToInt()
    private val maxSizePx = (300 * density).roundToInt()
    private val defaultSizePx = (120 * density).roundToInt()
    private val panelGapPx = (8 * density).roundToInt()

    private var currentSize = defaultSizePx
    private var isFullscreen = false
    private var isPanelExpanded = false
    private var isPaused = false
    var isCameraOnlyMode = true
        private set
    private var savedSize = defaultSizePx
    private var savedCameraX = 0
    private var savedCameraY = 100

    // 拖动相关
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // 控制面板中的按钮
    private var btnTogglePanel: ImageButton? = null
    private var btnPauseResume: ImageButton? = null
    private var btnToggleRecordMode: ImageButton? = null
    private var panelButtons: LinearLayout? = null

    // CameraX VideoCapture（用于只录前置摄像头模式）
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var onRecordingFinalized: (() -> Unit)? = null
    private var onCameraReady: (() -> Unit)? = null

    fun show(onReady: (() -> Unit)? = null) {
        onCameraReady = onReady

        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        setupGestureDetectors()
        showCameraWindow()
        showControlPanel()
        startCamera()
    }

    /** 只清理 UI，不停止摄像头录制（由调用方负责） */
    fun hide() {
        removeView(cameraView)
        removeView(panelView)
        cameraView = null
        panelView = null
    }

    /** 彻底销毁：停止摄像头 & 释放 lifecycle */
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopCamera()
    }

    // ── 摄像头窗口 ────────────────────────────────────────────────

    private fun showCameraWindow() {
        val inflater = LayoutInflater.from(context)
        cameraView = inflater.inflate(R.layout.floating_camera_window, null)
        previewView = cameraView?.findViewById(R.id.preview_view)

        cameraView?.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                handleDragEvent(event)
            }
            true
        }

        cameraParams = makeOverlayParams(defaultSizePx, defaultSizePx, savedCameraX, savedCameraY)
        windowManager.addView(cameraView, cameraParams)
    }

    // ── 控制面板窗口 ──────────────────────────────────────────────

    private fun showControlPanel() {
        val inflater = LayoutInflater.from(context)
        panelView = inflater.inflate(R.layout.floating_control_panel, null)

        btnTogglePanel = panelView?.findViewById(R.id.btn_toggle_panel)
        panelButtons = panelView?.findViewById(R.id.panel_buttons)
        btnPauseResume = panelView?.findViewById(R.id.btn_pause_resume)
        btnToggleRecordMode = panelView?.findViewById(R.id.btn_toggle_record_mode)

        // 展开/收缩按钮
        btnTogglePanel?.setOnClickListener {
            togglePanel()
        }

        // 停止按钮
        panelView?.findViewById<ImageButton>(R.id.btn_stop)?.setOnClickListener {
            onStopRecording()
        }

        // 暂停/继续按钮
        btnPauseResume?.setOnClickListener {
            if (isPaused) {
                onResumeRecording()
                isPaused = false
                btnPauseResume?.setImageResource(R.drawable.ic_pause)
                btnPauseResume?.contentDescription = context.getString(R.string.pause_recording)
            } else {
                onPauseRecording()
                isPaused = true
                btnPauseResume?.setImageResource(R.drawable.ic_resume)
                btnPauseResume?.contentDescription = context.getString(R.string.resume_recording)
            }
        }

        // 录制模式切换按钮
        btnToggleRecordMode?.setOnClickListener {
            if (isCameraOnlyMode) {
                // 当前是只录摄像头 → 切换回全屏录制
                isCameraOnlyMode = false
                btnToggleRecordMode?.setImageResource(R.drawable.ic_record_screen)
                btnToggleRecordMode?.contentDescription = context.getString(R.string.switch_to_camera_only)
                // 重置暂停状态
                isPaused = false
                btnPauseResume?.setImageResource(R.drawable.ic_pause)
                onSwitchToFullScreen()
            } else {
                // 当前是全屏录制 → 切换为只录摄像头
                isCameraOnlyMode = true
                btnToggleRecordMode?.setImageResource(R.drawable.ic_record_camera)
                btnToggleRecordMode?.contentDescription = context.getString(R.string.switch_to_full_screen)
                // 重置暂停状态
                isPaused = false
                btnPauseResume?.setImageResource(R.drawable.ic_pause)
                onSwitchToCameraOnly()
            }
        }

        // 计算面板初始位置
        val (px, py) = panelPosition(savedCameraX, savedCameraY, currentSize)
        panelParams = makeOverlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            px, py
        )
        windowManager.addView(panelView, panelParams)
    }

    private fun togglePanel() {
        isPanelExpanded = !isPanelExpanded
        if (isPanelExpanded) {
            panelButtons?.visibility = View.VISIBLE
            btnTogglePanel?.setImageResource(R.drawable.ic_close)
            btnTogglePanel?.contentDescription = context.getString(R.string.collapse_panel)
        } else {
            panelButtons?.visibility = View.GONE
            btnTogglePanel?.setImageResource(R.drawable.ic_menu)
            btnTogglePanel?.contentDescription = context.getString(R.string.expand_panel)
        }
        try {
            windowManager.updateViewLayout(panelView, panelParams)
        } catch (_: Exception) {}
    }

    /** 计算控制面板位置：紧贴摄像头右侧 */
    private fun panelPosition(camX: Int, camY: Int, camSize: Int): Pair<Int, Int> {
        val px = camX + camSize + panelGapPx
        val py = camY
        return Pair(px, py)
    }

    /** 同步更新控制面板位置 */
    private fun syncPanelPosition(camX: Int, camY: Int) {
        val params = panelParams ?: return
        val (px, py) = panelPosition(camX, camY, currentSize)
        params.x = px
        params.y = py
        try {
            windowManager.updateViewLayout(panelView, params)
        } catch (_: Exception) {}
    }

    // ── 手势处理 ──────────────────────────────────────────────────

    private fun setupGestureDetectors() {
        scaleGestureDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (isFullscreen) return true
                    val newSize = (currentSize * detector.scaleFactor).roundToInt()
                    currentSize = newSize.coerceIn(minSizePx, maxSizePx)
                    val params = cameraParams ?: return true
                    params.width = currentSize
                    params.height = currentSize
                    try { windowManager.updateViewLayout(cameraView, params) } catch (_: Exception) {}
                    syncPanelPosition(params.x, params.y)
                    return true
                }
            })

        gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleFullscreen()
                    return true
                }
            })
    }

    private fun handleDragEvent(event: MotionEvent) {
        val params = cameraParams ?: return
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                if (isFullscreen) return
                val dx = (event.rawX - initialTouchX).roundToInt()
                val dy = (event.rawY - initialTouchY).roundToInt()
                params.x = initialX + dx
                params.y = initialY + dy
                try { windowManager.updateViewLayout(cameraView, params) } catch (_: Exception) {}
                syncPanelPosition(params.x, params.y)
            }
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val params = cameraParams ?: return

        if (isFullscreen) {
            savedSize = currentSize
            savedCameraX = params.x
            savedCameraY = params.y

            params.width = metrics.widthPixels
            params.height = metrics.heightPixels
            params.x = 0
            params.y = 0

            panelView?.visibility = View.GONE
        } else {
            currentSize = savedSize
            params.width = currentSize
            params.height = currentSize
            params.x = savedCameraX
            params.y = savedCameraY

            panelView?.visibility = View.VISIBLE
            syncPanelPosition(savedCameraX, savedCameraY)
        }

        try { windowManager.updateViewLayout(cameraView, params) } catch (_: Exception) {}
    }

    // ── CameraX 摄像头录制（只录前置摄像头模式）─────────────────────

    /**
     * 开始前置摄像头录制
     * @param outputFile 输出文件
     * @param onFinished 录制完成后的回调，参数为文件路径
     */
    fun startCameraRecording(outputFile: File, onFinished: (String) -> Unit) {
        val vc = videoCapture ?: run {
            Log.e(TAG, "VideoCapture not initialized")
            return
        }
        val fileOutput = FileOutputOptions.Builder(outputFile).build()
        activeRecording = vc.output
            .prepareRecording(context, fileOutput)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (event.hasError()) {
                        Log.e(TAG, "Camera recording error: ${event.error}")
                    }
                    Log.d(TAG, "Camera recording finalized: ${outputFile.absolutePath}, size=${outputFile.length()}")
                    onFinished(outputFile.absolutePath)
                    // 触发停止回调（如果有）
                    onRecordingFinalized?.invoke()
                    onRecordingFinalized = null
                }
            }
        Log.d(TAG, "Camera recording started: ${outputFile.absolutePath}")
    }

    /** 停止前置摄像头录制，文件写完后回调 */
    fun stopCameraRecording(onStopped: (() -> Unit)? = null) {
        if (activeRecording == null) {
            onStopped?.invoke()
            return
        }
        onRecordingFinalized = onStopped
        activeRecording?.stop()
        activeRecording = null
    }

    /** 暂停前置摄像头录制 */
    fun pauseCameraRecording() {
        activeRecording?.pause()
    }

    /** 继续前置摄像头录制 */
    fun resumeCameraRecording() {
        activeRecording?.resume()
    }

    // ── 工具方法 ──────────────────────────────────────────────────

    private fun makeOverlayParams(
        width: Int, height: Int, x: Int, y: Int
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun removeView(v: View?) {
        v ?: return
        try { windowManager.removeView(v) } catch (_: Exception) {}
    }

    // ── 摄像头 ────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView?.surfaceProvider)
            }

            // 同时绑定 VideoCapture，供只录摄像头模式使用
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, videoCapture!!)
                Log.d(TAG, "Camera ready, videoCapture bound")
                onCameraReady?.invoke()
                onCameraReady = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        cameraProvider = null
        videoCapture = null
    }
}
