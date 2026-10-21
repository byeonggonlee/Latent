package com.byeonggon.latent.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the map servers are told the app is: its name and the version installed, and where to find it. */
class OnlineAccessTest {
    @Test
    fun namesTheInstalledVersion() {
        assertEquals("Latent/0.1.0-nightly.20261022 (+https://github.com/byeonggonlee/Latent)", OnlineAccess.userAgent("0.1.0-nightly.20261022"))
    }

    @Test
    fun saysWhenTheVersionIsNotKnown() {
        assertEquals("Latent/unknown (+https://github.com/byeonggonlee/Latent)", OnlineAccess.userAgent(""))
    }
}
