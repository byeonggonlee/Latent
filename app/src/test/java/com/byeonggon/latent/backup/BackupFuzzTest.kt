package com.byeonggon.latent.backup

import android.content.Context
import com.byeonggon.latent.Mutator
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.MaskStore
import com.byeonggon.latent.fuzz
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Damaged backups, and ones written by something else: a restore either takes the file or refuses it with a
 * [BackupException], which the screen turns into a message, and what it takes is what the app could have written.
 * The seed is the format 1 file written by hand from docs/BACKUP_FORMAT.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupFuzzTest {
    @get:Rule
    val folders = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val settings = SettingsRepository(context)
    private lateinit var target: TestPhone
    private lateinit var seed: ByteArray
    private lateinit var entries: List<Pair<String, ByteArray>>

    @Before
    fun setUp() = runBlocking {
        settings.update { AppSettings() }
        target = TestPhone(context, folders.newFolder("target"), user = 0, settings)
        target.use()
        seed = BackupFuzzTest::class.java.getResourceAsStream("/backup/latent-backup-format1.zip")!!.use { it.readBytes() }
        entries = folders.newFile().let { file ->
            file.writeBytes(seed)
            ZipFile(file).use { zip -> zip.entries().toList().map { it.name to zip.getInputStream(it).readBytes() } }
        }
        // The photos the seed's records are of, so that a restore goes all the way through.
        val jpeg = MediaItemEntity(path = target.libraryFile("2025/2025-12-24/DSC09001.JPG").path, fileName = "DSC09001.JPG", kind = MediaKind.PHOTO, sizeBytes = 4_100_000, capturedAt = 1_766_566_500_000, importedAt = 9)
        val raw = MediaItemEntity(path = target.libraryFile("2025/2025-12-25/DSC09001.ARW").path, fileName = "DSC09001.ARW", kind = MediaKind.RAW, sizeBytes = 24_500_000, capturedAt = 1_766_566_500_000, importedAt = 9)
        val phone = MediaItemEntity(path = target.phonePath("DCIM/Camera/20251224_101500.jpg"), fileName = "20251224_101500.jpg", kind = MediaKind.PHOTO, sizeBytes = 3_500_000, capturedAt = 1_766_567_700_000, importedAt = 9, origin = MediaOrigin.PHONE)
        target.onDisk += listOf(jpeg, raw, phone)
    }

    @After
    fun tearDown() = runBlocking {
        target.close()
        settings.update { AppSettings() }
        LutStore.directory = null
        MaskStore.directory = null
    }

    /** Restores, or only inspects, [bytes]; true when the file was taken. Throws what is not the failure a restore reports. */
    private fun restore(bytes: ByteArray, mutator: Mutator): Boolean = runBlocking {
        val file = folders.newFile()
        file.writeBytes(bytes)
        try {
            if (mutator.random.nextInt(4) == 0) target.restorer().inspect(file) else target.restorer().restore(file)
            true
        } catch (e: BackupException) {
            false
        } finally {
            file.delete()
        }
    }

    /** What a restore leaves on the phone is what the app itself could have made. */
    private fun assertWhatTheAppCouldHaveWritten() {
        val database = target.database.openHelper.readableDatabase
        database.query("SELECT latitude, longitude, altitude, accuracy FROM location_points").use { rows ->
            while (rows.moveToNext()) {
                val place = "(${rows.getDouble(0)}, ${rows.getDouble(1)}, ${rows.getString(2)}, ${rows.getString(3)})"
                assertTrue(place, rows.getDouble(0) in -90.0..90.0 && rows.getDouble(1) in -180.0..180.0)
                assertTrue(place, (rows.isNull(2) || rows.getDouble(2).isFinite()) && (rows.isNull(3) || rows.getFloat(3).isFinite()))
            }
        }
        database.query("SELECT latitude, longitude, altitude FROM media_items").use { rows ->
            while (rows.moveToNext()) {
                if (rows.isNull(0) && rows.isNull(1)) continue
                val place = "(${rows.getString(0)}, ${rows.getString(1)}, ${rows.getString(2)})"
                assertTrue(place, !rows.isNull(0) && !rows.isNull(1) && rows.getDouble(0) in -90.0..90.0 && rows.getDouble(1) in -180.0..180.0)
                assertTrue(place, rows.isNull(2) || rows.getDouble(2).isFinite())
            }
        }
        for (id in LutStore.ids()) assertTrue(id, LutStore.isTable(File(target.luts, "$id.lut").readBytes()))
        for ((id, _) in MaskStore.kept()) assertTrue(id, MaskStore.isOutline(File(target.masks, "$id.mask").readBytes()))
    }

    private fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            // A name twice is what ZipOutputStream will not write.
            for ((name, data) in entries.distinctBy { it.first }) {
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        if (stored) {
                            method = ZipEntry.STORED
                            size = data.size.toLong()
                            compressedSize = data.size.toLong()
                            crc = CRC32().apply { update(data) }.value
                        }
                    },
                )
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** The seed's entries with one to three of them changed: records broken, doubled or dropped, tables damaged, entries added. */
    private fun mutatedEntries(mutator: Mutator): List<Pair<String, ByteArray>> {
        val random = mutator.random
        val changed = entries.toMutableList()
        repeat(1 + random.nextInt(3)) {
            val index = random.nextInt(changed.size)
            val (name, data) = changed[index]
            when {
                random.nextInt(12) == 0 -> changed.removeAt(index)
                random.nextInt(12) == 0 -> {
                    // Another entry, of a name the reader looks for or not, with what another one holds.
                    val names = listOf("luts/0123456789abcdef0123456789abcdef.lut", "masks/0123456789abcdef0123456789abcdef.mask", "../outside.json", "luts/../../x.lut", "media.jsonl/", "x".repeat(200))
                    changed += names[random.nextInt(names.size)] to mutator.bytes(changed[random.nextInt(changed.size)].second)
                }
                name.endsWith(".jsonl") -> {
                    val lines = String(data, Charsets.UTF_8).lines().filter { it.isNotBlank() }.toMutableList()
                    if (lines.isEmpty()) lines += "{}"
                    val line = random.nextInt(lines.size)
                    when (random.nextInt(4)) {
                        0 -> repeat(1 + random.nextInt(200)) { lines += lines[line] }
                        1 -> lines.add(random.nextInt(lines.size + 1), if (random.nextBoolean()) "" else "\u0000￿")
                        else -> lines[line] = mutator.json(lines[line])
                    }
                    changed[index] = name to lines.joinToString(if (random.nextInt(5) == 0) "\r\n" else "\n").toByteArray()
                }
                name.endsWith(".json") -> changed[index] = name to mutator.json(String(data, Charsets.UTF_8)).toByteArray()
                else -> changed[index] = name to mutator.bytes(data)
            }
        }
        return changed
    }

    @Test
    fun theSeedIsRestored() {
        assertTrue(restore(seed, Mutator(0).also { it.random.nextInt() }))
        assertWhatTheAppCouldHaveWritten()
    }

    @Test
    fun recordsOfAnyShapeAreRestoredOrRefused() = fuzz(seed = 71, cases = 600, slowest = 5_000) { mutator ->
        if (restore(zip(mutatedEntries(mutator), stored = mutator.random.nextBoolean()), mutator)) assertWhatTheAppCouldHaveWritten()
    }

    @Test
    fun aDamagedFileIsRestoredOrRefused() = fuzz(seed = 72, cases = 600, slowest = 5_000) { mutator ->
        // The zip itself damaged: its headers, sizes, offsets and the packed data.
        val source = if (mutator.random.nextBoolean()) seed else zip(entries, stored = true)
        if (restore(mutator.bytes(source), mutator)) assertWhatTheAppCouldHaveWritten()
    }

    @Test
    fun aPlaceOffTheEarthIsLeftOut() = runBlocking {
        val places = listOf(
            """{"timestamp":1,"latitude":48.85,"longitude":2.29,"altitude":NaN,"accuracy":1e999,"source":"APP"}""",
            """{"timestamp":2,"latitude":NaN,"longitude":2.29,"source":"APP"}""",
            """{"timestamp":3,"latitude":48.85,"longitude":-633409.2,"source":"APP"}""",
            """{"timestamp":4,"latitude":"Infinity","longitude":2.29,"source":"TIMELINE"}""",
            """{"timestamp":5,"latitude":90,"longitude":-180,"altitude":35,"accuracy":5,"source":"TIMELINE"}""",
        )
        val changed = entries.map { (name, data) ->
            name to when (name) {
                BackupFormat.LOCATIONS -> places.joinToString("\n").toByteArray()
                // The JPEG's place, 48.8584 north, is put a thousand degrees off.
                BackupFormat.MEDIA -> String(data).replace("48.8584", "1048.8584").toByteArray()
                else -> data
            }
        }
        val file = folders.newFile().apply { writeBytes(zip(changed, stored = false)) }

        val result = target.restorer().restore(file)

        // A thousand degrees, or infinity, was kept as a place on no map; now it is left out as a place of no known source is.
        assertEquals(2, result.locationsAdded)
        val restored = target.database.openHelper.readableDatabase.query("SELECT timestamp, altitude, accuracy FROM location_points ORDER BY timestamp").use { rows ->
            fun number(column: Int) = if (rows.isNull(column)) null else rows.getDouble(column)
            buildList { while (rows.moveToNext()) add(Triple(rows.getLong(0), number(1), number(2))) }
        }
        assertEquals(listOf(Triple(1L, null, null), Triple(5L, 35.0, 5.0)), restored)
        val jpeg = target.database.mediaDao().all().single { it.fileName == "DSC09001.JPG" }
        assertEquals(5, jpeg.rating)
        assertEquals(null, jpeg.latitude)
        assertWhatTheAppCouldHaveWritten()
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList { while (hasMoreElements()) add(nextElement()) }
}
