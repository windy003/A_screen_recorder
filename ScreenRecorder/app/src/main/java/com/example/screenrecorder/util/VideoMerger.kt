package com.example.screenrecorder.util

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class VideoMerger(
    private val targetWidth: Int,
    private val targetHeight: Int
) {
    companion object {
        private const val TAG = "VideoMerger"
        private const val TIMEOUT_US = 10_000L
    }

    data class Segment(val filePath: String, val needsTranscode: Boolean)

    fun merge(segments: List<Segment>, outputPath: String): Boolean {
        if (segments.isEmpty()) return false

        if (segments.size == 1 && !segments[0].needsTranscode) {
            return File(segments[0].filePath).renameTo(File(outputPath))
        }

        try {
            val processedPaths = mutableListOf<String>()
            val tempFiles = mutableListOf<String>()

            for (segment in segments) {
                if (segment.needsTranscode) {
                    val transcodedPath = segment.filePath.replace(".mp4", "_tc.mp4")
                    Log.d(TAG, "Transcoding: ${segment.filePath}")
                    if (transcodeVideo(segment.filePath, transcodedPath)) {
                        processedPaths.add(transcodedPath)
                        tempFiles.add(transcodedPath)
                    } else {
                        Log.e(TAG, "Transcode failed, skipping: ${segment.filePath}")
                    }
                } else {
                    processedPaths.add(segment.filePath)
                }
            }

            if (processedPaths.isEmpty()) return false
            if (processedPaths.size == 1) {
                return File(processedPaths[0]).renameTo(File(outputPath))
            }

            Log.d(TAG, "Remuxing ${processedPaths.size} segments")
            val result = remuxFiles(processedPaths, outputPath)
            tempFiles.forEach { File(it).delete() }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            return false
        }
    }

    // ── 转码 ──────────────────────────────────────────────────────

    private fun transcodeVideo(inputPath: String, outputPath: String): Boolean {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        var srcVideoTrack = -1
        var srcAudioTrack = -1
        var srcVideoFormat: MediaFormat? = null
        var srcAudioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && srcVideoTrack == -1) {
                srcVideoTrack = i; srcVideoFormat = fmt
            } else if (mime.startsWith("audio/") && srcAudioTrack == -1) {
                srcAudioTrack = i; srcAudioFormat = fmt
            }
        }

        if (srcVideoTrack == -1 || srcVideoFormat == null) {
            Log.e(TAG, "No video track found in $inputPath")
            extractor.release(); return false
        }

        val rawWidth = srcVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val rawHeight = srcVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = try {
            srcVideoFormat.getInteger(MediaFormat.KEY_ROTATION)
        } catch (_: Exception) { 0 }
        val srcMime = srcVideoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"

        // 考虑旋转后的实际显示尺寸
        val displayWidth: Int
        val displayHeight: Int
        if (rotation == 90 || rotation == 270) {
            displayWidth = rawHeight
            displayHeight = rawWidth
        } else {
            displayWidth = rawWidth
            displayHeight = rawHeight
        }

        Log.d(TAG, "Source: ${rawWidth}x${rawHeight}, rotation=$rotation, " +
                "display=${displayWidth}x${displayHeight}, target=${targetWidth}x${targetHeight}")

        // 编码器
        val encFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 8 * 1024 * 1024)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        // EGL + OpenGL
        val egl = TranscodeEgl(encoderInputSurface, targetWidth, targetHeight, displayWidth, displayHeight)
        egl.setup()

        // 解码器
        val decoderSurface = Surface(egl.surfaceTexture)
        val decoder = MediaCodec.createDecoderByType(srcMime)
        decoder.configure(srcVideoFormat, decoderSurface, null, 0)
        decoder.start()

        extractor.selectTrack(srcVideoTrack)

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var outputDone = false
        var decodedFrames = 0
        var encodedFrames = 0

        while (!outputDone) {
            // 送数据给解码器
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        Log.d(TAG, "Input EOS sent to decoder")
                    } else {
                        decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // 解码器输出 → OpenGL → 编码器
            if (!decoderDone) {
                val idx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (idx >= 0) {
                    val render = info.size > 0
                    decoder.releaseOutputBuffer(idx, render)
                    if (render) {
                        egl.awaitNewImage()
                        egl.drawFrame()
                        egl.setPresentationTime(info.presentationTimeUs * 1000)
                        egl.swapBuffers()
                        decodedFrames++
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.signalEndOfInputStream()
                        decoderDone = true
                        Log.d(TAG, "Decoder done, decoded $decodedFrames frames")
                    }
                }
            }

            // 编码器输出 → Muxer
            val encIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (srcAudioFormat != null) {
                            muxerAudioTrack = muxer.addTrack(srcAudioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started, video track=$muxerVideoTrack, audio track=$muxerAudioTrack")
                    }
                }
                encIdx >= 0 -> {
                    val data = encoder.getOutputBuffer(encIdx) ?: continue
                    if (info.size > 0 && muxerStarted) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(muxerVideoTrack, data, info)
                        encodedFrames++
                    }
                    encoder.releaseOutputBuffer(encIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        Log.d(TAG, "Encoder done, encoded $encodedFrames frames")
                    }
                }
            }
        }

        // 音频直接拷贝
        if (srcAudioTrack != -1 && muxerAudioTrack != -1 && muxerStarted) {
            val audioExt = MediaExtractor()
            audioExt.setDataSource(inputPath)
            audioExt.selectTrack(srcAudioTrack)

            val audioBuf = ByteBuffer.allocate(512 * 1024)
            val audioInfo = MediaCodec.BufferInfo()
            var audioSamples = 0
            while (true) {
                val size = audioExt.readSampleData(audioBuf, 0)
                if (size < 0) break
                audioInfo.size = size
                audioInfo.presentationTimeUs = audioExt.sampleTime
                audioInfo.flags = audioExt.sampleFlags
                audioInfo.offset = 0
                muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                audioExt.advance()
                audioSamples++
            }
            audioExt.release()
            Log.d(TAG, "Audio copied: $audioSamples samples")
        }

        // 清理
        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        decoderSurface.release()
        egl.release()
        extractor.release()
        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()

        Log.d(TAG, "Transcode done: $outputPath ($encodedFrames video frames)")
        return muxerStarted && encodedFrames > 0
    }

    // ── 快速合并（相同格式） ─────────────────────────────────────────

    private fun remuxFiles(inputPaths: List<String>, outputPath: String): Boolean {
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val firstExt = MediaExtractor()
        firstExt.setDataSource(inputPaths[0])
        val trackMap = mutableMapOf<Int, Int>()
        for (i in 0 until firstExt.trackCount) {
            val fmt = firstExt.getTrackFormat(i)
            trackMap[i] = muxer.addTrack(fmt)
            Log.d(TAG, "Track $i: ${fmt.getString(MediaFormat.KEY_MIME)}")
        }
        muxer.start()
        firstExt.release()

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var timeOffset = 0L

        for ((segIdx, path) in inputPaths.withIndex()) {
            val ext = MediaExtractor()
            ext.setDataSource(path)
            var maxPts = 0L
            var samplesWritten = 0

            for (srcTrack in 0 until ext.trackCount) {
                val dstTrack = trackMap[srcTrack] ?: continue
                ext.selectTrack(srcTrack)
                ext.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                while (true) {
                    val size = ext.readSampleData(buffer, 0)
                    if (size < 0) break
                    val pts = ext.sampleTime + timeOffset
                    info.size = size
                    info.presentationTimeUs = pts
                    info.flags = ext.sampleFlags
                    info.offset = 0
                    if (pts > maxPts) maxPts = pts
                    muxer.writeSampleData(dstTrack, buffer, info)
                    ext.advance()
                    samplesWritten++
                }
                ext.unselectTrack(srcTrack)
            }
            ext.release()
            Log.d(TAG, "Segment $segIdx: $samplesWritten samples, maxPts=${maxPts}us, offset was ${timeOffset}us")
            timeOffset = maxPts + 33_333L
        }

        muxer.stop()
        muxer.release()
        Log.d(TAG, "Remux done: $outputPath (${inputPaths.size} segments)")
        return true
    }

    // ── EGL + OpenGL（支持 SurfaceTexture 旋转矩阵）─────────────────

    private class TranscodeEgl(
        private val outputSurface: Surface,
        private val dstW: Int,
        private val dstH: Int,
        displayW: Int,
        displayH: Int
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var texId = 0
        var surfaceTexture: SurfaceTexture? = null; private set
        private var program = 0
        private var vtxBuf: FloatBuffer? = null
        private var texBuf: FloatBuffer? = null
        private var uTexMatrixLoc = -1
        private val syncObj = Object()
        private var frameReady = false
        private val texMatrix = FloatArray(16)

        private val vtxCoords: FloatArray

        init {
            val srcAspect = displayW.toFloat() / displayH
            val dstAspect = dstW.toFloat() / dstH
            val (sx, sy) = if (srcAspect > dstAspect) {
                1f to (dstAspect / srcAspect)
            } else {
                (srcAspect / dstAspect) to 1f
            }
            vtxCoords = floatArrayOf(-sx, -sy, sx, -sy, -sx, sy, sx, sy)
            Log.d(TAG, "EGL: src=${displayW}x${displayH}, dst=${dstW}x${dstH}, scale=($sx, $sy)")
        }

        private val texCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun setup() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)

            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val nCfg = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, nCfg, 0)

            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0]!!, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

            val sfcAttr = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0]!!, outputSurface, sfcAttr, 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            initGL()
        }

        private fun initGL() {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            texId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            surfaceTexture = SurfaceTexture(texId)
            surfaceTexture?.setOnFrameAvailableListener {
                synchronized(syncObj) { frameReady = true; syncObj.notifyAll() }
            }

            // Vertex shader：使用 uTexMatrix 处理 SurfaceTexture 的旋转
            val vsCode = """
                attribute vec2 aPos;
                attribute vec2 aTex;
                uniform mat4 uTexMatrix;
                varying vec2 vTex;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
                }
            """.trimIndent()

            val fsCode = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTex;
                uniform samplerExternalOES sSampler;
                void main() { gl_FragColor = texture2D(sSampler, vTex); }
            """.trimIndent()

            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vsCode)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fsCode)

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            }

            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            Log.d(TAG, "uTexMatrix location: $uTexMatrixLoc")

            vtxBuf = ByteBuffer.allocateDirect(vtxCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vtxCoords).also { it.position(0) }
            texBuf = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).also { it.position(0) }

            Matrix.setIdentityM(texMatrix, 0)
        }

        private fun loadShader(type: Int, code: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, code)
            GLES20.glCompileShader(s)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] != GLES20.GL_TRUE) {
                val typeName = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
                Log.e(TAG, "$typeName shader compile failed: ${GLES20.glGetShaderInfoLog(s)}")
            }
            return s
        }

        fun awaitNewImage() {
            synchronized(syncObj) {
                while (!frameReady) {
                    syncObj.wait(5000)
                    if (!frameReady) {
                        Log.e(TAG, "Frame wait timed out!")
                        throw RuntimeException("Frame wait timed out")
                    }
                }
                frameReady = false
            }
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(texMatrix)
        }

        fun drawFrame() {
            GLES20.glViewport(0, 0, dstW, dstH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            // 传递纹理变换矩阵
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

            val posH = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(posH)
            GLES20.glVertexAttribPointer(posH, 2, GLES20.GL_FLOAT, false, 0, vtxBuf)

            val texH = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(texH)
            GLES20.glVertexAttribPointer(texH, 2, GLES20.GL_FLOAT, false, 0, texBuf)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(posH)
            GLES20.glDisableVertexAttribArray(texH)

            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "GL error after drawFrame: $err")
            }
        }

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            surfaceTexture?.release(); surfaceTexture = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}
