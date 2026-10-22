package com.byeonggon.latent.backup

import android.content.Context
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import com.byeonggon.latent.enhance.ColorLut
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.MaskStore
import com.google.gson.JsonObject
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
import java.util.zip.ZipFile

/** A backup the writer finishes is one a restore reads: a library past a restore's limits is not written at all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupWriterLimitsTest {
    @get:Rule
    val folders = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val settings = SettingsRepository(context)
    private lateinit var phone: TestPhone

    @Before
    fun setUp() = runBlocking {
        settings.update { AppSettings() }
        phone = TestPhone(context, folders.newFolder("phone"), user = 0, settings)
        phone.use()
        LutStore.save(LUT, ColorLut(2, FloatArray(24)))
        phone.database.lutDao().insert(com.byeonggon.latent.data.db.LutEntity(name = "Flat", size = 2, hash = LUT, createdAt = 5))
        phone.database.locationDao().insertAll(List(500) { LocationPointEntity(timestamp = it * 1000L, latitude = 1.0, longitude = 2.0, source = LocationSource.APP) })
    }

    @After
    fun tearDown() = runBlocking {
        phone.close()
        settings.update { AppSettings() }
        LutStore.directory = null
        MaskStore.directory = null
    }

    private fun writer(mostEntries: Int, mostUnpacked: Long) = BackupWriter(
        phone.database, settings, phone.library, AppIdentity("p", "v", 1),
        mostEntries = mostEntries, mostUnpacked = mostUnpacked, phoneRelativePath = { it },
    )

    private fun write(writer: BackupWriter): File = folders.newFile().also { file -> runBlocking { file.outputStream().use { writer.write(it) } } }

    @Test
    fun aBackupPastARestoresLimitsIsNotWritten() {
        val whole = write(writer(BackupArchive.MOST_ENTRIES, BackupArchive.MOST_UNPACKED))
        val (entries, unpacked) = ZipFile(whole).use { zip -> zip.size() to zip.entries().asSequence().sumOf { it.size } }

        // Just within, it is written; one entry or one byte past, it is refused, with the limits it went past.
        write(writer(entries, unpacked))
        assertEquals(entries - 1, assertThrows<BackupTooLargeException> { write(writer(entries - 1, unpacked)) }.mostEntries)
        assertEquals(unpacked - 1, assertThrows<BackupTooLargeException> { write(writer(entries, unpacked - 1)) }.mostUnpacked)
    }

    @Test
    fun whatTheWriterFinishesARestoreReadsUnderTheSameLimits() = runBlocking {
        val whole = write(writer(BackupArchive.MOST_ENTRIES, BackupArchive.MOST_UNPACKED))
        val (entries, unpacked) = ZipFile(whole).use { zip -> zip.size() to zip.entries().asSequence().sumOf { it.size } }
        val file = write(writer(entries, unpacked))

        BackupArchive.open(file, mostEntries = entries, mostUnpacked = unpacked).use { archive ->
            assertEquals(500, archive.eachRecord(BackupFormat.LOCATIONS, { json: JsonObject -> json }) {})
            assertTrue(archive.json(BackupFormat.SETTINGS) != null)
            assertEquals(28 + 4 * 24, archive.bytes(BackupFormat.lutEntry(LUT), 4096).size)
        }
    }

    private companion object {
        const val LUT = "0123456789abcdef0123456789abcdef"
    }
}
