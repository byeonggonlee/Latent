package com.byeonggon.latent.backup

import android.content.Context
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** A location history of years: through a backup and back in a time to wait for, and without holding it all at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupSizeTest {
    @get:Rule
    val folders = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val settings = SettingsRepository(context)
    private lateinit var source: TestPhone
    private lateinit var target: TestPhone

    @Before
    fun setUp() = runBlocking {
        settings.update { AppSettings() }
        source = TestPhone(context, folders.newFolder("source"), user = 0, settings)
        target = TestPhone(context, folders.newFolder("target"), user = 0, settings)
    }

    @After
    fun tearDown() = runBlocking {
        source.close()
        target.close()
        settings.update { AppSettings() }
    }

    private fun point(n: Int) = LocationPointEntity(
        timestamp = START + n * 300_000L,
        latitude = 37.0 + n * 1e-6,
        longitude = 127.0 - n * 1e-6,
        altitude = if (n % 2 == 0) n * 0.1 else null,
        accuracy = if (n % 5 == 0) null else 4.5f,
        source = if (n % 3 == 0) LocationSource.TIMELINE else LocationSource.APP,
    )

    /**
     * The most the heap held at any of the moments [sample] was called, counting only what is still
     * in use (it collects the garbage first). What a stream goes through is garbage a moment later; a list of every
     * point, or a text of the whole entry, is not.
     */
    private class HeapWatch {
        private val runtime = Runtime.getRuntime()
        private val before = live()
        private var highest = 0L

        // What the heap held, class by class, before the work: the growth is what the work (or a test beside it) left.
        private val baseline = classBytes()

        /** The classes whose bytes grew most when the heap held the most beyond [HELD_LIMIT]; empty if it never did. */
        var growth = ""
            private set

        fun sample() {
            val now = live()
            if (now <= highest) return
            highest = now
            if (now - before > HELD_LIMIT) growth = growthSince(baseline)
        }

        /**
         * What was held at most beyond what the heap holds without the work, before it or after it: other tests in the
         * same process may leave work of their own going, which slowly takes some memory too.
         */
        fun most(): Long = (highest - maxOf(before, live())).coerceAtLeast(0)

        // A collection is only asked for; of a few, the least is what is really in use.
        private fun live(): Long = (1..3).minOf {
            System.gc()
            Thread.sleep(10)
            runtime.totalMemory() - runtime.freeMemory()
        }
    }

    /** Samples [watch] now and then while the timeline goes through, the place where a backup is largest. */
    private fun watching(watch: HeapWatch) = BackupProgress { step, done, _ ->
        if ((step == BackupStep.TIMELINE || step == BackupStep.CHECKING) && done > 0 && done % 30_000 == 0) watch.sample()
    }

    @Test
    fun carriesAHundredAndFiftyThousandPlacesThereAndBack() = runBlocking {
        source.use()
        // About two years recorded every five minutes.
        for (batch in 0 until POINTS / 2_000) source.database.locationDao().insertAll(List(2_000) { point(batch * 2_000 + it) })
        val file = folders.newFile()

        val started = System.nanoTime()
        val writing = HeapWatch()
        file.outputStream().use { source.writer().write(it, watching(writing)) }
        val backedUp = System.nanoTime()
        val written = writing.most()
        target.use()
        val reading = HeapWatch()
        target.restorer().restore(file, watching(reading))
        val restored = System.nanoTime()

        val read = reading.most()
        val seconds = { from: Long, to: Long -> (to - from) / 1e9 }
        println(
            "150k places: ${file.length() / 1024} KB, backup ${seconds(started, backedUp)} s (${written / KB} KB held), " +
                "restore ${seconds(backedUp, restored)} s (${read / KB} KB held)",
        )
        assertTrue("backup took ${seconds(started, backedUp)} s", seconds(started, backedUp) < 60)
        assertTrue("restore took ${seconds(backedUp, restored)} s", seconds(backedUp, restored) < 90)
        assertTrue("the backup held ${written / KB} KB; the heap grew in\n${writing.growth}", written < HELD_LIMIT)
        assertTrue("the restore held ${read / KB} KB; the heap grew in\n${reading.growth}", read < HELD_LIMIT)

        assertEquals(POINTS, target.database.backupDao().countLocations())
        var after = 0L
        var compared = 0
        while (true) {
            val page = target.database.backupDao().locationsAfter(after, 5_000)
            if (page.isEmpty()) break
            for (each in page) {
                assertEquals(point(compared), each.copy(id = 0))
                compared++
            }
            after = page.last().id
        }
        assertEquals(POINTS, compared)
        assertEquals(0, target.restorer().restore(file).locationsAdded)
    }

    private companion object {
        /** The bytes the live objects of each class take, from the JDK's jcmd; empty where there is none. */
        fun classBytes(): Map<String, Long> = runCatching {
            val jcmd = File(System.getProperty("java.home"), "bin/jcmd")
            val process = ProcessBuilder(jcmd.path, ProcessHandle.current().pid().toString(), "GC.class_histogram")
                .redirectErrorStream(true).start()
            // Lines look like "   3:       12345      987654  java.lang.String (java.base@21)".
            val line = Regex("""^\s*\d+:\s+\d+\s+(\d+)\s+(\S+)""")
            process.inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull(line::find).associate { it.groupValues[2] to it.groupValues[1].toLong() }
            }
        }.getOrDefault(emptyMap())

        /** The twelve classes that grew most from [before], by how much, and what they take now. */
        fun growthSince(before: Map<String, Long>): String = classBytes().entries
            .map { (name, bytes) -> Triple(name, bytes - (before[name] ?: 0L), bytes) }
            .sortedByDescending { it.second }
            .take(12)
            .joinToString("\n") { (name, grew, now) -> "  +${grew / KB} KB (${now / KB} KB) $name" }

        const val POINTS = 150_000
        const val START = 1_700_000_000_000L
        const val KB = 1024

        /** A list of the 150,000 points alone would be about 12 MB, the text of them more. */
        const val HELD_LIMIT = 8L * 1024 * KB
    }
}
