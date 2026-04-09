package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Range
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object VideoCodecUtils {
    class SurfaceInputWriter(
        private val inputSurface: Surface
    ) : Closeable {
        private val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val eglContext: android.opengl.EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val program: Int
        private val positionHandle: Int
        private val texCoordHandle: Int
        private val textureHandle: Int
        private val vertexBuffer: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
        )
        private val texCoordBuffer: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            )
        )

        init {
            require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "无法获取EGL display" }
            val version = IntArray(2)
            require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "EGL 初始化失败" }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            require(EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
                "EGL 配置选择失败"
            }
            val eglConfig = configs[0] ?: error("未找到可用EGL配置")
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            require(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context 创建失败" }
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, inputSurface, surfaceAttribs, 0)
            require(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL surface 创建失败" }
            makeCurrent()
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureHandle = createTexture()
        }

        fun renderBitmap(bitmap: Bitmap, presentationTimeUs: Long) {
            makeCurrent()
            GLES20.glViewport(0, 0, bitmap.width, bitmap.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000L)
            require(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers 失败" }
        }

        override fun close() {
            runCatching {
                makeCurrent()
                GLES20.glDeleteTextures(1, intArrayOf(textureHandle), 0)
                GLES20.glDeleteProgram(program)
            }
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
            runCatching { inputSurface.release() }
        }

        private fun makeCurrent() {
            require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent 失败: ${eglErrorString(EGL14.eglGetError())}"
            }
        }

        private fun createTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            require(textureId != 0) { "纹理创建失败" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return textureId
        }

        private fun createProgram(vertexShaderSource: String, fragmentShaderSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
            val programId = GLES20.glCreateProgram()
            require(programId != 0) { "GL program 创建失败" }
            GLES20.glAttachShader(programId, vertexShader)
            GLES20.glAttachShader(programId, fragmentShader)
            GLES20.glLinkProgram(programId)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
            require(linkStatus[0] == GLES20.GL_TRUE) {
                val message = GLES20.glGetProgramInfoLog(programId)
                GLES20.glDeleteProgram(programId)
                "GL program 链接失败: $message"
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return programId
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            require(shader != 0) { "Shader 创建失败 type=$type" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            require(compileStatus[0] == GLES20.GL_TRUE) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                "Shader 编译失败: $message"
            }
            return shader
        }

        private fun createFloatBuffer(values: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
        }

        private companion object {
            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """

            private const val FRAGMENT_SHADER = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """
        }
    }

    private fun eglErrorString(error: Int): String {
        return when (error) {
            EGL14.EGL_SUCCESS -> "EGL_SUCCESS"
            EGL14.EGL_NOT_INITIALIZED -> "EGL_NOT_INITIALIZED"
            EGL14.EGL_BAD_ACCESS -> "EGL_BAD_ACCESS"
            EGL14.EGL_BAD_ALLOC -> "EGL_BAD_ALLOC"
            EGL14.EGL_BAD_ATTRIBUTE -> "EGL_BAD_ATTRIBUTE"
            EGL14.EGL_BAD_CONFIG -> "EGL_BAD_CONFIG"
            EGL14.EGL_BAD_CONTEXT -> "EGL_BAD_CONTEXT"
            EGL14.EGL_BAD_CURRENT_SURFACE -> "EGL_BAD_CURRENT_SURFACE"
            EGL14.EGL_BAD_DISPLAY -> "EGL_BAD_DISPLAY"
            EGL14.EGL_BAD_MATCH -> "EGL_BAD_MATCH"
            EGL14.EGL_BAD_NATIVE_PIXMAP -> "EGL_BAD_NATIVE_PIXMAP"
            EGL14.EGL_BAD_NATIVE_WINDOW -> "EGL_BAD_NATIVE_WINDOW"
            EGL14.EGL_BAD_PARAMETER -> "EGL_BAD_PARAMETER"
            EGL14.EGL_BAD_SURFACE -> "EGL_BAD_SURFACE"
            EGL14.EGL_CONTEXT_LOST -> "EGL_CONTEXT_LOST"
            else -> "0x${error.toString(16)}"
        }
    }

    fun alignDimensionToEncoder(value: Int): Int {
        if (value <= 1) return 2
        return if (value % 2 == 0) value else value - 1
    }

    fun clampDimensionToRange(value: Int, range: Range<Int>, alignment: Int): Int {
        val safeAlignment = alignment.coerceAtLeast(1)
        val clamped = value.coerceIn(range.lower, range.upper)
        val alignedDown = clamped - (clamped % safeAlignment)
        if (alignedDown in range) {
            return alignedDown.coerceAtLeast(safeAlignment)
        }
        val alignedUp = alignedDown + safeAlignment
        if (alignedUp in range) {
            return alignedUp
        }
        return range.lower
    }

    fun resolveSupportedSize(
        capabilities: MediaCodecInfo.VideoCapabilities,
        requestedWidth: Int,
        requestedHeight: Int
    ): Pair<Int, Int> {
        val widthAlignment = capabilities.widthAlignment.coerceAtLeast(1)
        val heightAlignment = capabilities.heightAlignment.coerceAtLeast(1)
        val alignedWidth = clampDimensionToRange(requestedWidth, capabilities.supportedWidths, widthAlignment)
        val alignedHeight = clampDimensionToRange(requestedHeight, capabilities.supportedHeights, heightAlignment)
        return alignedWidth to alignedHeight
    }

    @Suppress("DEPRECATION")
    fun colorFormatToString(format: Int): String {
        return when (format) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "Surface"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "YUV420SemiPlanar(NV12)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "YUV420Planar(I420)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> "YUV420PackedPlanar"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> "YUV420PackedSemiPlanar(NV21)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV420Flexible"
            else -> "Unknown($format)"
        }
    }

    fun addAudioTrackIfPresent(context: Context, muxer: android.media.MediaMuxer, uri: Uri): Int {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                audioTrackIndex = muxer.addTrack(format)
                break
            }
        }
        extractor.release()
        return audioTrackIndex
    }

    fun copyAudioToMuxer(context: Context, muxer: android.media.MediaMuxer, trackIndex: Int, uri: Uri) {
        if (trackIndex == -1) return
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var audioTrack = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                audioTrack = i
                break
            }
        }
        if (audioTrack == -1) {
            extractor.release()
            return
        }

        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break
            }
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(trackIndex, buffer, info)
            extractor.advance()
        }
        extractor.release()
    }

}
