package com.byeonggon.latent.enhance

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES31
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The graphics processor, for compute shaders: an OpenGL ES 3.1 context on a thread of its own, as a
 * context belongs to the thread that made it. Whatever uses it runs its work through [run].
 */
internal object Graphics {
    private const val TAG = "Graphics"
    private const val EGL_OPENGL_ES3_BIT = 0x40

    private val thread: ExecutorService = Executors.newSingleThreadExecutor { work -> Thread(work, "graphics").apply { isDaemon = true } }
    private val programs = HashMap<String, Int>()

    /** The widest picture a texture can hold. */
    var maxSize = 0
        private set

    /** Whether there is a graphics processor to use: a device with OpenGL ES 3.1, not the JVM the tests run on. */
    val available: Boolean by lazy {
        try {
            thread.submit(Callable { start() }).get()
            true
        } catch (e: Throwable) {
            // On the plain JVM that unit tests run on, even logging is a stub that throws.
            runCatching { Log.i(TAG, "No graphics processor to use: ${e.message}") }
            false
        }
    }

    /** Runs [work] on the graphics thread, with the context current, and returns what it returns. */
    fun <T> run(work: () -> T): T = thread.submit(Callable { work() }).get()

    /** The program of the compute shader [source], compiled the first time it is asked for under [name]. Graphics thread only. */
    fun program(name: String, source: String): Int = programs.getOrPut(name) { compile(name, source) }

    /** Throws if the graphics processor reported an error, naming [what] was being done. Graphics thread only. */
    fun check(what: String) {
        val error = GLES31.glGetError()
        if (error != GLES31.GL_NO_ERROR) throw IllegalStateException("$what: GL error 0x${Integer.toHexString(error)}")
    }

    private fun start() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "no display" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        val wanted = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, found, 0) && found[0] > 0) { "no configuration" }
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "no context" }
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "cannot make the context current" }
        val glVersion = GLES31.glGetString(GLES31.GL_VERSION).orEmpty()
        check(Regex("""OpenGL ES 3\.[1-9]""").containsMatchIn(glVersion)) { "needs OpenGL ES 3.1, has $glVersion" }
        val size = IntArray(1)
        GLES31.glGetIntegerv(GLES31.GL_MAX_TEXTURE_SIZE, size, 0)
        maxSize = size[0]
    }

    private fun compile(name: String, source: String): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, source)
        GLES31.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "shader $name: ${GLES31.glGetShaderInfoLog(shader)}" }
        val program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, shader)
        GLES31.glLinkProgram(program)
        val linked = IntArray(1)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "program $name: ${GLES31.glGetProgramInfoLog(program)}" }
        GLES31.glDeleteShader(shader)
        return program
    }
}
