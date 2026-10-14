package com.byeonggon.latent.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoDetailsTest {
    @Test
    fun putsTogetherHowAPhotoWasTaken() {
        assertEquals(
            "ZV-E10 · E PZ 18-105mm F4 G OSS · 35mm · f/4 · 1/250초 · ISO 100",
            PhotoDetails.describe("ZV-E10", "E PZ 18-105mm F4 G OSS", 35.0, 4.0, 0.004, 100),
        )
    }

    @Test
    fun showsLongExposuresInSecondsAndOddAperturesWithADecimal() {
        assertEquals("f/5.6 · 2초", PhotoDetails.describe(null, " ", 0.0, 5.6, 2.0, 0))
        assertEquals("1/3초", PhotoDetails.describe(null, null, 0.0, 0.0, 1 / 3.0, 0))
        assertEquals("0.5초", PhotoDetails.describe(null, null, 0.0, 0.0, 0.5, 0))
    }

    @Test
    fun saysNothingWhenNothingIsKnown() {
        assertNull(PhotoDetails.describe(null, "", 0.0, 0.0, 0.0, 0))
    }
}
