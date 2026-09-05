package io.github.woods_marshes.mj.view

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.woods_marshes.mj.utils.SimpleLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * 透明动画视图：播放 assets 里"左半边灰度蒙版 + 右半边彩色画面"的双画面视频。
 *
 * MediaCodec 解码出每一帧后，在片元着色器里把左半边的灰度值作为 Alpha、
 * 右半边作为颜色实时合成；SurfaceView 以透明格式置顶叠加，
 * 最终呈现真正带透明通道的动画。
 *
 * 解码器选择：首选系统默认（通常硬解）；创建/配置失败，或播放中途运行期失败时，
 * 自动回退谷歌软解（c2.android / OMX.google）重建解码器重试——部分设备
 * （尤其 MTK）硬解码器对特定视频参数不兼容。两次都失败才弹出错误报告。
 */
class MjAnimationView constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val animationAsset: String = ASSET_DROP,
    private val playSound: Boolean = true,
    private val onFinished: (() -> Unit)? = null,
) : SurfaceView(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var started = false

    init {
        // 必须在 Surface 创建之前设置为置顶的透明层
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (started) return
                started = true
                player = Player(holder.surface, width, height).also { it.start() }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                player?.cancel()
                player = null
            }
        })
    }

    /** 已打开并选好视频轨的媒体源。 */
    private class VideoSource(
        val extractor: MediaExtractor,
        val format: MediaFormat,
        val colorWidth: Int, // 彩色画面宽度（视频宽的一半）
        val height: Int,
        val mime: String,
    )

    private inner class Player(
        private val surface: Surface,
        private val viewWidth: Int,
        private val viewHeight: Int,
    ) : Thread("MjAnimationPlayer") {

        @Volatile
        private var cancelled = false

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var program = 0
        private var uScaleLoc = 0
        private var uTextureLoc = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var quadBuffer: FloatBuffer? = null
        private var uvBuffer: FloatBuffer? = null

        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var decodeSurface: Surface? = null

        private var audioPlayer: MediaPlayer? = null
        private var audioStarted = false

        private var scaleX = 1f
        private var scaleY = 1f

        fun cancel() {
            cancelled = true
            try {
                join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        override fun run() {
            var codec: MediaCodec? = null
            var source: VideoSource? = null
            var decoderSurface: Surface? = null
            try {
                if (!setupEgl(surface)) {
                    reportError("EGL/GPU 初始化失败")
                    return
                }
                setupProgram()

                textureId = createOesTexture()
                val texture = SurfaceTexture(textureId)
                surfaceTexture = texture
                decoderSurface = Surface(texture)
                decodeSurface = decoderSurface

                source = openVideo()
                // 彩色画面只占视频右半边，显示宽高按一半计算
                computeQuadScale(source.colorWidth.toFloat(), source.height.toFloat())

                // 第一次尝试：系统默认解码器（通常硬解）；该设备曾硬解失败则直接软解
                val preferSw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_PREFER_SW, false)
                codec = createDecoder(source.format, decoderSurface, preferSw = preferSw, failedName = null)
                codec.start()

                if (playSound) prepareAudio()
                playLoop(codec, source.extractor)
            } catch (e: Exception) {
                if (cancelled) return
                SimpleLog.d(TAG, "Playback attempt failed: $e")
                // 硬解运行期失败：重开视频源、强制软解重建解码器再完整重试一次
                val ds = decoderSurface
                if (ds == null) {
                    reportError("内部状态异常：解码表面未就绪")
                    return
                }
                try {
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    codec = null
                    runCatching { source?.extractor?.release() }
                    source = openVideo()
                    computeQuadScale(source.colorWidth.toFloat(), source.height.toFloat())
                    restartAudio()
                    codec = createDecoder(source.format, ds, preferSw = true, failedName = null)
                    codec.start()
                    // 硬解失败、软解可用：记住偏好，这台设备之后直接软解
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_PREFER_SW, true).apply()
                    SimpleLog.d(TAG, "Retrying with software decoder")
                    playLoop(codec, source.extractor)
                } catch (e2: Exception) {
                    reportError("${e2::class.java.simpleName}: ${e2.message?.take(100) ?: "未知错误"}")
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { source?.extractor?.release() }
                releaseAudio()
                releaseGl()
                // 因视图被移除而中断时不再回调，避免对已移除的视图重复操作
                if (!cancelled) mainHandler.post { onFinished?.invoke() }
            }
        }

        private fun playLoop(codec: MediaCodec, extractor: MediaExtractor) {
            val info = MediaCodec.BufferInfo()
            var eosQueued = false
            var startPtsUs = Long.MIN_VALUE
            var startNanos = 0L

            while (!cancelled) {
                if (!eosQueued) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(inIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size >= 0) {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (startPtsUs == Long.MIN_VALUE) {
                        startPtsUs = info.presentationTimeUs
                        startNanos = System.nanoTime()
                    }
                    // 按时间戳节奏播放，避免解码快于实时导致动画瞬间放完
                    val targetNanos = startNanos + (info.presentationTimeUs - startPtsUs) * 1000
                    val waitMs = (targetNanos - System.nanoTime()) / 1_000_000
                    if (waitMs > 1) SystemClock.sleep(waitMs)
                    if (cancelled) {
                        codec.releaseOutputBuffer(outIndex, false)
                        break
                    }
                    codec.releaseOutputBuffer(outIndex, true)
                    surfaceTexture?.updateTexImage()
                    // 首帧视频上屏时才起音，抵消解码器预热造成的音画错位
                    if (!audioStarted) {
                        audioStarted = true
                        runCatching { audioPlayer?.start() }
                    }
                    drawFrame()
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    SimpleLog.d(TAG, "Decoder output format changed")
                }
            }
        }

        private fun openVideo(): VideoSource {
            val extractor = MediaExtractor()
            context.assets.openFd(animationAsset).use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                throw IllegalStateException("视频文件中没有视频轨")
            }
            extractor.selectTrack(trackIndex)

            var videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            var videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation =
                if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rotation == 90 || rotation == 270) {
                val tmp = videoWidth
                videoWidth = videoHeight
                videoHeight = tmp
            }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            return VideoSource(extractor, format, videoWidth / 2, videoHeight, mime)
        }

        private fun computeQuadScale(colorWidth: Float, colorHeight: Float) {
            val fit = min(viewWidth / colorWidth, viewHeight / colorHeight)
            scaleX = colorWidth * fit / viewWidth
            scaleY = colorHeight * fit / viewHeight
        }

        private fun drawFrame() {
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTextureLoc, 0)
            GLES20.glUniform2f(uScaleLoc, scaleX, scaleY)
            quadBuffer?.let {
                it.position(0)
                GLES20.glEnableVertexAttribArray(aPositionLoc)
                GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, it)
            }
            uvBuffer?.let {
                it.position(0)
                GLES20.glEnableVertexAttribArray(aTexCoordLoc)
                GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, it)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        private fun setupEgl(surface: Surface): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.size, numConfigs, 0) ||
                numConfigs[0] == 0
            ) {
                return false
            }
            val config = configs[0] ?: return false

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun setupProgram() {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) { "Program link failed: " + GLES20.glGetProgramInfoLog(program) }

            uScaleLoc = GLES20.glGetUniformLocation(program, "uScale")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            quadBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            uvBuffer = floatBufferOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun createOesTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val id = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return id
        }

        /** 用 MediaPlayer 只取视频里的音频轨（不给 Surface，视频部分自动丢弃）。 */
        private fun prepareAudio() {
            try {
                audioPlayer = MediaPlayer().apply {
                    context.assets.openFd(animationAsset).use { afd ->
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    prepare()
                }
            } catch (e: Exception) {
                SimpleLog.d(TAG, "Audio prepare failed: $e")
                runCatching { audioPlayer?.release() }
                audioPlayer = null
            }
        }

        private fun releaseAudio() {
            runCatching { audioPlayer?.let { if (it.isPlaying) it.stop() } }
            runCatching { audioPlayer?.release() }
            audioPlayer = null
            audioStarted = false
        }

        private fun restartAudio() {
            releaseAudio()
            if (playSound) prepareAudio()
        }

        /**
         * 创建视频解码器。
         *
         * @param preferSw true 时跳过系统默认（硬解）直接按"软解优先"排序尝试，
         * 用于硬解运行期失败后的整管线重试
         */
        private fun createDecoder(
            format: MediaFormat,
            surface: Surface,
            preferSw: Boolean,
            failedName: String?,
        ): MediaCodec {
            if (!preferSw) {
                try {
                    val codec = MediaCodec.createDecoderByType(
                        requireNotNull(format.getString(MediaFormat.KEY_MIME))
                    )
                    codec.configure(format, surface, null, 0)
                    SimpleLog.d(TAG, "Using default decoder")
                    return codec
                } catch (e: Exception) {
                    SimpleLog.d(TAG, "Default decoder failed: $e")
                }
            }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder && runCatching { it.getCapabilitiesForType(mime) }.isSuccess }
                .map { it.name }
                .filter { it != failedName }
                .sortedByDescending { name ->
                    when {
                        name.contains("sw") || name.contains("google") || name.startsWith("c2.android") -> 2
                        else -> 1
                    }
                }
            for (name in candidates) {
                try {
                    SimpleLog.d(TAG, "Trying decoder: $name")
                    val codec = MediaCodec.createByCodecName(name)
                    codec.configure(format, surface, null, 0)
                    SimpleLog.d(TAG, "Using decoder: $name")
                    return codec
                } catch (e: Exception) {
                    SimpleLog.d(TAG, "Decoder $name failed: $e")
                }
            }
            throw IllegalStateException("no usable decoder for $mime")
        }

        /** 渲染失败时弹出错误报告窗口（release 包日志不可见，这是主要诊断手段）。 */
        private fun reportError(message: String) {
            SimpleLog.d(TAG, "ERROR: $message")
            mainHandler.post { ErrorReporter.show(context, message) }
        }

        private fun releaseGl() {
            runCatching {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    if (program != 0) {
                        GLES20.glDeleteProgram(program)
                        program = 0
                    }
                    if (textureId != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                        textureId = 0
                    }
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                }
            }
            decodeSurface?.release()
            decodeSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
        }

        private fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }

    companion object {
        private const val TAG = "MjAnimationView"
        private const val TIMEOUT_US = 10_000L
        private const val PREFS_NAME = "settings"
        private const val KEY_PREFER_SW = "prefer_sw_decoder"

        const val ASSET_DROP = "animations/mj-drop-dual-mask.mp4"
        const val ASSET_SWING = "animations/mj-swing-dual-mask.mp4"

        private val allAssets = listOf(ASSET_DROP, ASSET_SWING)
        private var nextAssetIndex = 0

        /** 轮流返回下一段动画素材（drop → swing → drop…） */
        @Synchronized
        fun nextAsset(): String = allAssets[nextAssetIndex++ % allAssets.size]

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uScale;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
                vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                // 左半边是灰度蒙版，右半边是彩色画面
                float mask = texture2D(uTexture, vec2(vTexCoord.x * 0.5, vTexCoord.y)).r;
                vec3 color = texture2D(uTexture, vec2(vTexCoord.x * 0.5 + 0.5, vTexCoord.y)).rgb;
                // 预乘 Alpha 输出，配合半透明 Surface 合成时不会出现白边
                gl_FragColor = vec4(color * mask, mask);
            }
        """
    }
}
