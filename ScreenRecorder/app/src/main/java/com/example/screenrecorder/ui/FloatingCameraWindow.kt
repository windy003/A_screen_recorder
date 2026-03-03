package com.example.screenrecorder.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageButton
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.screenrecorder.R
import kotlin.math.roundToInt

class FloatingCameraWindow(
    private val context: Context,
    private val onStopRecording: () -> Unit
) : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // 摄像头悬浮窗
    private var cameraView: View? = null
    private var cameraParams: WindowManager.LayoutParams? = null
    private var previewView: PreviewView? = null

    // 独立的停止按钮悬浮窗
    private var stopBtnView: View? = null
    private var stopBtnParams: WindowManager.LayoutParams? = null

    private val metrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(it)
    }

    private val density = context.resources.displayMetrics.density
    private val minSizePx = (80 * density).roundToInt()
    private val maxSizePx = (300 * density).roundToInt()
    private val defaultSizePx = (120 * density).roundToInt()
    // 停止按钮尺寸：44dp，紧贴摄像头圆形右侧 8dp 间距
    private val stopBtnSizePx = (44 * density).roundToInt()
    private val stopBtnGapPx = (8 * density).roundToInt()

    private var currentSize = defaultSizePx
    private var isFullscreen = false
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

    fun show() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        setupGestureDetectors()
        showCameraWindow()
        showStopButton()
        startCamera()
    }

    fun hide() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopCamera()
        removeView(cameraView)
        removeView(stopBtnView)
        cameraView = null
        stopBtnView = null
    }

    // ── 摄像头窗口 ────────────────────────────────────────────────

    private fun showCameraWindow() {
        val inflater = LayoutInflater.from(context)
        cameraView = inflater.inflate(R.layout.floating_camera_window, null)
        previewView = cameraView?.findViewById(R.id.preview_view)

        applyCircularClip()

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

    // ── 停止按钮窗口 ──────────────────────────────────────────────

    private fun showStopButton() {
        val inflater = LayoutInflater.from(context)
        stopBtnView = inflater.inflate(R.layout.floating_stop_button, null)

        stopBtnView?.findViewById<ImageButton>(R.id.btn_stop)?.setOnClickListener {
            onStopRecording()
        }

        val (bx, by) = stopBtnPosition(savedCameraX, savedCameraY, currentSize)
        stopBtnParams = makeOverlayParams(stopBtnSizePx, stopBtnSizePx, bx, by)
        windowManager.addView(stopBtnView, stopBtnParams)
    }

    /** 计算停止按钮位置：紧贴摄像头圆形右侧，垂直居中对齐 */
    private fun stopBtnPosition(camX: Int, camY: Int, camSize: Int): Pair<Int, Int> {
        val bx = camX + camSize + stopBtnGapPx
        val by = camY + (camSize - stopBtnSizePx) / 2
        return Pair(bx, by)
    }

    /** 同步更新停止按钮位置（摄像头拖动时调用） */
    private fun syncStopButtonPosition(camX: Int, camY: Int) {
        val params = stopBtnParams ?: return
        val (bx, by) = stopBtnPosition(camX, camY, currentSize)
        params.x = bx
        params.y = by
        try {
            windowManager.updateViewLayout(stopBtnView, params)
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
                    syncStopButtonPosition(params.x, params.y)
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
                // 停止按钮同步跟随
                syncStopButtonPosition(params.x, params.y)
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

            cameraView?.outlineProvider = null
            cameraView?.clipToOutline = false

            // 全屏时隐藏停止按钮（全屏画面不遮挡）
            stopBtnView?.visibility = View.GONE
        } else {
            currentSize = savedSize
            params.width = currentSize
            params.height = currentSize
            params.x = savedCameraX
            params.y = savedCameraY

            applyCircularClip()

            // 恢复停止按钮并同步位置
            stopBtnView?.visibility = View.VISIBLE
            syncStopButtonPosition(savedCameraX, savedCameraY)
        }

        try { windowManager.updateViewLayout(cameraView, params) } catch (_: Exception) {}
    }

    // ── 工具方法 ──────────────────────────────────────────────────

    private fun applyCircularClip() {
        cameraView?.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        cameraView?.clipToOutline = true
    }

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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView?.surfaceProvider)
            }
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        } catch (_: Exception) {}
    }
}
