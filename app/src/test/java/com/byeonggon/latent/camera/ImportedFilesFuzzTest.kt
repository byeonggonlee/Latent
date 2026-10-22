package com.byeonggon.latent.camera

import com.byeonggon.latent.Mutator
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.fuzz
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Import keys of any shape, as a damaged backup or database might hold, and file names, sizes and times of any value, as
 * a camera might give: a file is taken as imported only by its camera, name, size and a time a time zone explains.
 */
class ImportedFilesFuzzTest {
    private val step = 15 * 60_000L
    private val span = 26 * 60 * 60_000L
    private val typical = 1_733_600_000_000L

    private fun time(mutator: Mutator): Long {
        val random = mutator.random
        return when (random.nextInt(6)) {
            0 -> listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, Long.MIN_VALUE + 899_999, Long.MAX_VALUE - 900_000)[random.nextInt(6)]
            1 -> random.nextLong()
            else -> typical + step * random.nextInt(-110, 110) + if (random.nextInt(4) == 0) random.nextInt(1, 1000) else 0
        }
    }

    private fun name(mutator: Mutator): String = listOf("DSC00001.ARW", "DSC00001.JPG", "a|b.JPG", "", "|", "||", "x.jpg|1|2")[mutator.random.nextInt(7)]

    private fun size(mutator: Mutator): Long = listOf(0L, -1L, 25_000_000L, Long.MAX_VALUE, Long.MIN_VALUE)[mutator.random.nextInt(5)]

    @Test
    fun anImportedFileIsRecognisedOnlyAtATimeAZoneExplains() = fuzz(seed = 61, cases = 20_000) { mutator ->
        val random = mutator.random
        val keys = List(1 + random.nextInt(4)) {
            when (random.nextInt(5)) {
                0 -> String(mutator.bytes("camera|DSC00001.ARW|25000000|$typical".toByteArray()), Charsets.ISO_8859_1)
                else -> "${listOf("camera", "folder:x", "", "a|b")[random.nextInt(4)]}|${name(mutator)}|${size(mutator)}|${time(mutator)}"
            }
        }
        val imported = ImportedFiles(keys)
        val file = RemoteFile(1, name(mutator), size(mutator), time(mutator), MediaKind.RAW, "card:/")
        for (camera in listOf("camera", "folder:x", "a|b")) {
            if (!imported.contains(file, camera)) continue
            // Some key of this camera, name and size, at a time that differs by whole quarter hours, a day and two hours at most.
            val explained = keys.any { key ->
                key == "$camera|${file.name}|${file.sizeBytes}|${key.substringAfterLast('|')}" && key.substringAfterLast('|').toLongOrNull()?.let { at ->
                    val shift = (BigInteger.valueOf(at) - BigInteger.valueOf(file.modifiedAt)).abs()
                    shift <= BigInteger.valueOf(span) && shift.mod(BigInteger.valueOf(step)) == BigInteger.ZERO
                } == true
            }
            assertTrue("$file taken as imported from $camera by $keys", explained)
        }
        imported.importedElsewhere(file, "folder:y")
    }

    @Test
    fun timesAtTheEndsOfALongAreNotOneQuarterHourApart() {
        // Their difference wraps round to minus a quarter hour.
        val imported = ImportedFiles(listOf("camera|DSC00001.ARW|100|${Long.MAX_VALUE}"))

        assertFalse(imported.contains(RemoteFile(1, "DSC00001.ARW", 100, Long.MIN_VALUE + 899_999, MediaKind.RAW, "card:/"), "camera"))
    }
}
