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
import java.util.concurrent.locks.ReentrantLock
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
    fun anExportFindsTheCacheEmptyThoughAReadFilledItWhileTheExportWaited() {
        // The cache's decode lock, held by the test, so that each step comes in this order whatever the scheduler does.
        val gate = RawCache::class.java.getDeclaredField("decoding").apply { isAccessible = true }.get(quick) as ReentrantLock
        var keptDuringExport: Boolean? = null
        var failure: Throwable? = null
        val export: Thread
        gate.lock()
        try {
            export = thread {
                try {
                    quick.decodingAlone {
                        // Reading again decodes again unless the cache still holds what the read below decoded.
                        val before = decodes.get()
                        quick.read(second)
                        keptDuringExport = decodes.get() == before
                    }
                } catch (e: Throwable) {
                    failure = e
                }
            }
            // The export has let the cache go and waits for the lock.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!gate.hasQueuedThread(export)) {
                assertTrue("the export never waited for the lock", System.nanoTime() < deadline)
                Thread.sleep(5)
            }
            // A read that has the lock before the export, as one that waited longer would: it fills the cache.
            quick.read(second)
            val decoded = decodes.get()
            quick.read(second)
            assertEquals("the read did not leave its picture in the cache", decoded, decodes.get())
        } finally {
            gate.unlock()
        }

        export.join(TimeUnit.SECONDS.toMillis(5))
        failure?.let { throw it }
        assertEquals("the cache still held a picture while the export decoded", false, keptDuringExport)
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
