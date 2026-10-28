package com.byeonggon.latent.raw

import org.junit.Assert.assertEquals
import org.junit.Test

class RawDeveloperCfaTest {
    @Test fun preservesColourAtEveryBayerPhaseInPreviewAndFullDevelopment() {
        for (pattern in CfaPattern.entries) {
            val sensor = ShortArray(16 * 16) { index ->
                val x = index % 16 and 1
                val y = index / 16 and 1
                when {
                    x == pattern.redX && y == pattern.redY -> 6000
                    x != pattern.redX && y != pattern.redY -> 2000
                    else -> 4000
                }.toShort()
            }
            val image = ArwImage(16,16,0,0,16,16,0,8000,floatArrayOf(1f,1f,1f),
                floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f),1f,null,1,sensor,cfaPattern=pattern)
            for (result in listOf(RawDeveloper.developPreview(image),RawDeveloper.developPreview(image,2),RawDeveloper.develop(image))) {
                for (i in result.red.indices) {
                    assertEquals("red $pattern at $i", 12287.0, result.red[i].toDouble(), 2.0)
                    assertEquals("green $pattern at $i", 8191.0, result.green[i].toDouble(), 2.0)
                    assertEquals("blue $pattern at $i", 4096.0, result.blue[i].toDouble(), 2.0)
                }
            }
        }
    }

    @Test fun aPartRetainsItsBayerPattern() {
        val image = ArwImage(16,16,0,0,16,16,0,8000,floatArrayOf(1f,1f,1f),
            floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f),1f,null,1,ShortArray(256),cfaPattern=CfaPattern.GBRG)
        assertEquals(CfaPattern.GBRG,image.part(2,2,8,8).cfaPattern)
    }
}
