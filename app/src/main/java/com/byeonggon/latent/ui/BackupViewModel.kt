package com.byeonggon.latent.ui

import android.app.Application
import android.content.Context
import android.database.SQLException
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.latent.R
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.backup.BackupCounts
import com.byeonggon.latent.backup.BackupFormat
import com.byeonggon.latent.backup.BackupProgress
import com.byeonggon.latent.backup.BackupStep
import com.byeonggon.latent.backup.DamagedBackupException
import com.byeonggon.latent.backup.MissingPhotos
import com.byeonggon.latent.backup.NewerBackupException
import com.byeonggon.latent.backup.NoLibraryAccessException
import com.byeonggon.latent.backup.NotABackupException
import com.byeonggon.latent.backup.RestorePlan
import com.byeonggon.latent.backup.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.time.Instant

/** A backup or a restore under way: which, and how far it has come. */
data class BackupWork(val restoring: Boolean, val step: BackupStep, val done: Int = 0, val total: Int = 0)

/** What the backup part of the settings shows. */
data class BackupUiState(
    val work: BackupWork? = null,
    /** A restore read and waiting for the person to confirm it. */
    val plan: RestorePlan? = null,
    /** How the last backup or restore went. */
    val message: String? = null,
)

/**
 * Backs up into a file the person chose and restores from one, off the main thread. Kept by the activity, so that
 * the work goes on when the settings are left or the screen turns.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val resolver get() = getApplication<Application>().contentResolver
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** A copy of the chosen file, which a restore reads twice, in any order. Nothing of it stays. */
    private val staged get() = File(getApplication<Application>().cacheDir, "restore/backup.zip")

    /** The name the save dialog suggests, such as latent-backup-20261020-1530.zip. */
    fun suggestedName(): String = BackupFormat.fileName(Instant.now())

    fun backUp(uri: Uri) = start(restoring = false) {
        val message = try {
            val counts = withContext(Dispatchers.IO) {
                // Truncated: a file chosen to be written over would otherwise keep the end of the backup before.
                val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")
                output.use { container.backupWriter.write(it, progress(restoring = false)) }
            }
            text(R.string.backup_done) + "\n" + describe(counts)
        } catch (e: CancellationException) {
            val deleted = withContext(NonCancellable + Dispatchers.IO) { discard(uri) }
            _state.update { BackupUiState(message = text(if (deleted) R.string.backup_cancelled else R.string.backup_cancelled_left)) }
            throw e
        } catch (e: IOException) {
            withContext(Dispatchers.IO) { discard(uri) }
            text(R.string.backup_failed, e.message.orEmpty())
        } catch (e: SecurityException) {
            text(R.string.backup_failed, e.message.orEmpty())
        } catch (e: SQLException) {
            withContext(Dispatchers.IO) { discard(uri) }
            text(R.string.backup_failed, e.message.orEmpty())
        }
        _state.update { BackupUiState(message = message) }
    }

    /** Reads the backup at [uri] and says what restoring it would do; the restore waits for [confirmRestore]. */
    fun inspect(uri: Uri) = start(restoring = true) {
        if (waitForSync()) return@start
        val plan = restoreStep {
            withContext(Dispatchers.IO) { stage(uri) }
            container.restorer.inspect(staged, progress(restoring = true))
        } ?: return@start
        _state.update { BackupUiState(plan = plan) }
    }

    fun confirmRestore() {
        if (_state.value.plan == null) return
        start(restoring = true) {
            try {
                // The summary may have waited for a long time on the screen: a sync could have begun since it was read.
                if (waitForSync()) return@start
                val result = restoreStep { container.restorer.restore(staged, progress(restoring = true)) } ?: return@start
                _state.update { BackupUiState(message = describe(result)) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { staged.parentFile?.deleteRecursively() }
            }
        }
    }

    /** Whether a camera sync is running; if so, says that the restore has to wait and lets the staged copy go. */
    private suspend fun waitForSync(): Boolean {
        if (!container.syncEngine.state.value.isRunning) return false
        _state.update { BackupUiState(message = text(R.string.backup_restore_wait_for_sync)) }
        withContext(NonCancellable + Dispatchers.IO) { staged.parentFile?.deleteRecursively() }
        return true
    }

    /** A copy of a backup that was read and never restored holds the person's places: it does not outlive the screen. */
    override fun onCleared() {
        staged.parentFile?.deleteRecursively()
    }

    fun dismissPlan() {
        _state.update { it.copy(plan = null) }
        viewModelScope.launch(Dispatchers.IO) { staged.parentFile?.deleteRecursively() }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun start(restoring: Boolean, work: suspend () -> Unit) {
        if (job?.isActive == true) return
        _state.update { BackupUiState(work = BackupWork(restoring, if (restoring) BackupStep.CHECKING else BackupStep.PHOTOS)) }
        job = viewModelScope.launch { work() }
    }

    /** Runs a part of a restore; null, with the reason shown, when it fails. Nothing of the backup is taken over then. */
    private suspend fun <T> restoreStep(block: suspend () -> T): T? {
        val reason = try {
            return block()
        } catch (e: CancellationException) {
            _state.update { BackupUiState(message = text(R.string.backup_restore_cancelled)) }
            withContext(NonCancellable + Dispatchers.IO) { staged.parentFile?.deleteRecursively() }
            throw e
        } catch (e: NotABackupException) {
            text(R.string.backup_restore_not_backup)
        } catch (e: DamagedBackupException) {
            text(R.string.backup_restore_damaged)
        } catch (e: NewerBackupException) {
            text(R.string.backup_restore_too_new, e.version, BackupFormat.VERSION)
        } catch (e: NoLibraryAccessException) {
            text(R.string.backup_restore_no_access)
        } catch (e: IOException) {
            text(R.string.backup_restore_failed, e.message.orEmpty())
        } catch (e: SecurityException) {
            text(R.string.backup_restore_failed, e.message.orEmpty())
        } catch (e: SQLException) {
            text(R.string.backup_restore_failed, e.message.orEmpty())
        }
        _state.update { BackupUiState(message = reason) }
        withContext(NonCancellable + Dispatchers.IO) { staged.parentFile?.deleteRecursively() }
        return null
    }

    @Throws(IOException::class)
    private fun stage(uri: Uri) {
        val folder = staged.parentFile!!
        folder.deleteRecursively()
        folder.mkdirs()
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { source -> staged.outputStream().use { source.copyTo(it) } }
    }

    /** Deletes the unfinished file at [uri]. Returns whether it is gone. */
    private fun discard(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: Exception) {
        // A provider says that it cannot in its own way; the file then stays where it is.
        false
    }

    private fun progress(restoring: Boolean) = BackupProgress { step, done, total ->
        _state.update { current -> current.copy(work = BackupWork(restoring, step, done, total)) }
    }

    private fun describe(counts: BackupCounts): String = text(
        R.string.backup_counts,
        number(counts.media), number(counts.albums), number(counts.versions), number(counts.filters), number(counts.lutFiles),
        number(counts.masks), number(counts.locations), number(counts.syncRecords),
    )

    private fun describe(result: RestoreResult): String = buildString {
        append(text(R.string.backup_restore_done))
        append('\n')
        append(
            text(
                R.string.backup_restore_counts,
                number(result.photos), number(result.albums), number(result.versionsAdded), number(result.filtersAdded), number(result.lutsAdded),
                number(result.masksAdded), number(result.locationsAdded), number(result.syncRecordsAdded),
            ),
        )
        missingText(getApplication(), result.missing)?.let { append('\n').append(it) }
        if (result.missing.count > 0 && result.syncRecordsAdded > 0) append('\n').append(text(R.string.backup_restore_reimport_hint))
        if (result.onlineMapsLeftOff) append('\n').append(text(R.string.backup_restore_online_maps))
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)
}

/** A count as the phone's language writes numbers. */
internal fun number(count: Int): String = NumberFormat.getIntegerInstance().format(count)

/** The photos a restore leaves out, by name; null when there are none. */
internal fun missingText(context: Context, missing: MissingPhotos): String? {
    if (missing.count == 0) return null
    val names = missing.names.joinToString(", ") + if (missing.count > missing.names.size) ", …" else ""
    return context.getString(R.string.backup_restore_missing, number(missing.count), names)
}
