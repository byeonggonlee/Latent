package com.byeonggon.latent.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The one decoded raw file that the viewer, the editor and shares hold in common. */
class RawCacheTest {
    private val image = SonyArw.read(SyntheticArw.file(red = 600, green = 900, blue = 700))
    private val first = File("DSC00001.ARW")
    private val second = File("DSC00002.ARW")
    private val decodes = AtomicInteger()

    /** A decode that waits for [release] once [started] is counted down: a slow file, or a damaged one. */
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val slow = RawCache { _ ->
        decodes.incrementAndGet()
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
        image
    }
    private val quick = RawCache { _ -> decodes.incrementAndGet(); image }

    @Test
    fun decodesAFileOnceAndKeepsOnlyTheLast() {
        quick.read(first)
        quick.read(first)
        assertEquals(1, decodes.get())
        quick.read(second)
        quick.read(first)
        assertEquals(3, decodes.get())
    }

    @Test(timeout = 5_000)
    fun clearsWithoutWaitingForADecodeUnderWay() {
        val reader = thread { slow.read(first) }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        // The viewer clears the cache from the main thread when it closes: it must not wait seconds for a decode.
        val clearing = thread { slow.clear() }
        clearing.join(1_000)
        assertFalse("clear() waited for the decode", clearing.isAlive)

        release.countDown()
        reader.join()
    }

    @Test(timeout = 5_000)
    fun doesNotKeepWhatADecodeGivesAfterTheCacheWasCleared() {
        val reader = thread { slow.read(first) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        slow.clear()
        release.countDown()
        reader.join()

        // Cleared to free the memory: the file is decoded again when it is asked for, not kept from before.
        slow.read(first)
        assertEquals(2, decodes.get())
    }

    @Test(timeout = 5_000)
    fun aDecodeOutsideTheCacheWaitsForTheOneUnderWay() {
        val reader = thread { slow.read(first) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val exporting = CountDownLatch(1)
        var exported: String? = null

        // An export clears the cache at once, and decodes only once the cache's decode has ended.
        val export = thread { exported = slow.decodingAlone { exporting.countDown(); "exported" } }
        assertFalse("the export decoded beside the cache's decode", exporting.await(500, TimeUnit.MILLISECONDS))

        release.countDown()
        reader.join()
        export.join()
        assertEquals("exported", exported)
        // What the cache decoded is not kept: the export cleared it to have the memory.
        slow.read(first)
        assertEquals(2, decodes.get())
    }

    @Test(timeout = 5_000)
    fun twoReadsOfOneFileDecodeItOnce() {
        var fromOther: ArwImage? = null
        val reader = thread { slow.read(first) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val other = thread { fromOther = slow.read(first) }
        release.countDown()
        reader.join()
        other.join()

        assertEquals(1, decodes.get())
        assertSame(image, fromOther)
    }
}
