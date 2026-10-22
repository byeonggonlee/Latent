package com.byeonggon.latent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `scripts/check-privacy.sh`, which CI runs on the release build, run on a project of its own: a `gradlew` that says
 * what the test wants, the permissions expected and a merged manifest. What it must never do is pass without having
 * seen the libraries.
 */
class PrivacyCheckTest {
    @get:Rule
    val project = TemporaryFolder()

    private val script: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "scripts/check-privacy.sh") }
        .first { it.isFile }

    private class Outcome(val status: Int, val output: String)

    /** Runs the script with a `gradlew` that prints [libraries] and exits with [gradleStatus]. */
    private fun check(libraries: String, gradleStatus: Int = 0): Outcome {
        assumeTrue("bash is there to run the script", File("/bin/bash").canExecute())
        val root = project.root
        File(root, "scripts").mkdirs()
        script.copyTo(File(root, "scripts/check-privacy.sh"))
        File(root, "gradlew").apply {
            writeText("#!/bin/bash\ncat <<'LIST'\n$libraries\nLIST\nexit $gradleStatus\n")
            setExecutable(true)
        }
        File(root, "app").mkdirs()
        File(root, "app/expected-permissions.txt").writeText("android.permission.INTERNET\n")
        File(root, "app/build/intermediates/merged_manifests/release/processReleaseManifest").apply { mkdirs() }
            .resolve("AndroidManifest.xml")
            .writeText("""<manifest><uses-permission android:name="android.permission.INTERNET" /></manifest>""")
        val process = ProcessBuilder("/bin/bash", "scripts/check-privacy.sh").directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("the script finished", process.waitFor(30, TimeUnit.SECONDS))
        return Outcome(process.exitValue(), output)
    }

    @Test
    fun passesALibraryListWithNothingThatReports() {
        val outcome = check("+--- androidx.core:core-ktx:1.17.0\n+--- com.google.code.gson:gson:2.13.2")

        assertEquals(outcome.output, 0, outcome.status)
        assertTrue(outcome.output, outcome.output.contains("Privacy checks passed"))
    }

    @Test
    fun failsOnALibraryThatReports() {
        val outcome = check("+--- com.google.firebase:firebase-analytics:23.0.0")

        assertNotEquals(outcome.output, 0, outcome.status)
        assertTrue(outcome.output, outcome.output.contains("firebase-analytics"))
    }

    @Test
    fun failsWhenTheLibrariesCannotBeListed() {
        // As the check was found to do (Codex's legacy audit, LA-07): the listing failed, and it said all was well.
        val outcome = check("Dependency inspection failed", gradleStatus = 23)

        assertNotEquals(outcome.output, 0, outcome.status)
        assertTrue(outcome.output, !outcome.output.contains("Privacy checks passed"))
    }

    @Test
    fun failsWhenTheListComesBackEmpty() {
        val outcome = check("")

        assertNotEquals(outcome.output, 0, outcome.status)
        assertTrue(outcome.output, !outcome.output.contains("Privacy checks passed"))
    }
}
