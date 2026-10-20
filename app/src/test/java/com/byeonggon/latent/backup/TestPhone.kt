package com.byeonggon.latent.backup

import android.content.Context
import androidx.room.Room
import com.byeonggon.latent.data.SettingsRepository
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.enhance.LutStore
import com.byeonggon.latent.enhance.MaskStore
import kotlinx.coroutines.sync.Mutex
import java.io.File

/**
 * One phone in a backup test: its own database, library folder, phone storage, LUTs and masks. The settings are the
 * process's one store, shared by every phone. [user] puts its storage at another absolute place, as on another phone.
 */
internal class TestPhone(context: Context, home: File, val user: Int, private val settings: SettingsRepository) {
    val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    val library = File(home, "storage/emulated/$user/DCIM/Latent")
    val luts = File(home, "files/luts")
    val masks = File(home, "files/masks")

    /** What the library takes in when it looks at its folders, as adoption and the phone's listing would. */
    val onDisk = ArrayList<MediaItemEntity>()
    var access = true

    /** The phone folders the library was last asked to look through; null before. */
    var noticed: Set<String>? = null

    /** What the app holds while it lists the phone's photos, and whether it was held while the files were taken in. */
    val phoneListing = Mutex()
    var listingHeld = false

    /** Makes the LUTs and masks this phone's. */
    fun use() {
        LutStore.directory = luts
        MaskStore.directory = masks
    }

    fun phonePath(relative: String) = "/storage/emulated/$user/$relative"

    fun libraryFile(relative: String) = File(library, relative)

    fun writer(now: Long = 1_760_950_000_000L) =
        BackupWriter(database, settings, library, AppIdentity("com.byeonggon.latent", "0.1.0-test", 42), clock = { now })

    fun restorer() = Restorer(
        database, settings, library,
        hasAccess = { access },
        noticeFiles = { chosen ->
            noticed = chosen
            listingHeld = phoneListing.isLocked
            // Adoption only adds what the library does not know yet.
            val known = database.mediaDao().all().mapTo(HashSet()) { it.path }
            onDisk.filter { it.path !in known }.forEach { database.mediaDao().insert(it) }
        },
        hasOriginal = { item -> File(library, ".originals/" + File(item.path).relativeTo(library).path).exists() },
        exclusive = phoneListing,
    )

    fun close() = database.close()
}

internal inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.simpleName}, got $e", e)
    }
    throw AssertionError("Expected ${T::class.simpleName}, nothing was thrown")
}
