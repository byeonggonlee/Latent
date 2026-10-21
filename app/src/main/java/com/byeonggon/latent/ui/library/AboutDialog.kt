package com.byeonggon.latent.ui.library

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.ui.common.openInBrowser
import com.byeonggon.latent.R

private const val REPOSITORY = "https://github.com/byeonggonlee/Latent"

/** The app's name and version, where its source and privacy policy are, and what it is built from. */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.versionName() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_built_from), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.about_trademarks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            Column {
                TextButton(onClick = { context.openInBrowser("$REPOSITORY/blob/main/PRIVACY.md") }) { Text(stringResource(R.string.about_privacy)) }
                TextButton(onClick = { context.openInBrowser(REPOSITORY) }) { Text(stringResource(R.string.about_source)) }
            }
        },
    )
}

private fun Context.versionName(): String = try {
    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: ""
} catch (e: PackageManager.NameNotFoundException) {
    ""
}
