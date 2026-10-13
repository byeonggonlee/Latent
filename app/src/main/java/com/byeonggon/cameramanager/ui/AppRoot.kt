package com.byeonggon.cameramanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.byeonggon.cameramanager.R
import com.byeonggon.cameramanager.ui.library.LibraryScreen
import com.byeonggon.cameramanager.ui.location.LocationScreen
import com.byeonggon.cameramanager.ui.sync.SyncScreen
import com.byeonggon.cameramanager.ui.viewer.ViewerScreen

private enum class Tab(val label: String) {
    LIBRARY("보관함"),
    SYNC("동기화"),
    LOCATION("위치"),
}

@Composable
fun AppRoot(startOnSync: Boolean) {
    var tab by rememberSaveable { mutableStateOf(if (startOnSync) Tab.SYNC else Tab.LIBRARY) }
    var viewerItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Hoisted so the grid keeps its scroll position while the viewer is open.
    val libraryGridState = rememberLazyGridState()

    val openItemId = viewerItemId
    if (openItemId != null) {
        ViewerScreen(itemId = openItemId, onClose = { viewerItemId = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { TabIcon(entry) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.LIBRARY -> LibraryScreen(gridState = libraryGridState, onOpen = { viewerItemId = it })
                Tab.SYNC -> SyncScreen()
                Tab.LOCATION -> LocationScreen()
            }
        }
    }
}

@Composable
private fun TabIcon(tab: Tab) {
    when (tab) {
        Tab.LIBRARY -> Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null)
        Tab.SYNC -> Icon(Icons.Default.Refresh, contentDescription = null)
        Tab.LOCATION -> Icon(Icons.Default.LocationOn, contentDescription = null)
    }
}
