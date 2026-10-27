package com.byeonggon.latent

import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import com.byeonggon.latent.data.LibraryStorage
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * AndroidX creates view models with the first Application it ever saw. In a test run that is the
 * Application of an earlier test, along with that test's database and files, so screens would show
 * and change another test's library. Call this before the activity starts to let them use their own.
 */
internal fun forgetTheApplicationOfEarlierTests() {
    ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply {
        isAccessible = true
        set(null, null)
    }
}

/** Gives the app what it asks the person for before it manages the library: reading media, and the library folder. */
internal fun grantLibraryAccess() {
    val application = RuntimeEnvironment.getApplication()
    Shadows.shadowOf(application).grantPermissions(*LibraryStorage.MEDIA_PERMISSIONS)
    val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:DCIM")
    application.contentResolver.takePersistableUriPermission(
        tree,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}

/**
 * The action [label] of the bar over selected photos: in the bar where it fits, or else under its More menu, which
 * this opens. In any of the app's languages.
 */
internal fun ComposeTestRule.selectionAction(label: String): SemanticsNodeInteraction {
    if (onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()) {
        val more = listOf("More", "더 보기", "その他").first { onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription(more).performClick()
    }
    return onNodeWithText(label)
}
