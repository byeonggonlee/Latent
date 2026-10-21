package com.byeonggon.latent.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The GPU's shaders are compiled by the phone's driver, and a mistake in one shows only as the processor doing the work
 * instead. CI has them checked by glslang as well: SHADER_EXPORT_DIR names a folder to write each as a .comp file in.
 */
class ShaderSourceTest {
    private fun shaders(): Map<String, String> = buildMap {
        put("tone", ToneShader.source(displayed = false))
        put("tone-displayed", ToneShader.source(displayed = true))
        for ((name, body) in DetailShaders.ALL) put("detail-$name", DetailShaders.HEADER + body)
    }

    @Test
    fun everyShaderStartsWithItsVersionAndBalancesItsBraces() {
        val all = shaders()
        assertTrue("only ${all.size} shaders", all.size >= 3)
        for ((name, source) in all) {
            assertTrue("$name does not start with its version", source.trimStart().startsWith("#version 310 es"))
            assertTrue("$name has no main", "void main()" in source)
            assertEquals("$name braces", source.count { it == '{' }, source.count { it == '}' })
            assertEquals("$name parentheses", source.count { it == '(' }, source.count { it == ')' })
        }
    }

    @Test
    fun writesTheShadersForGlslangWhenAskedTo() {
        val dir = System.getenv("SHADER_EXPORT_DIR")?.let(::File)
        assumeTrue(dir != null)
        dir!!.mkdirs()
        for ((name, source) in shaders()) File(dir, "$name.comp").writeText(source)
    }
}
