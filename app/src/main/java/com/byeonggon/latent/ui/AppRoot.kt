package com.byeonggon.latent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byeonggon.latent.R
import com.byeonggon.latent.ui.common.ReadableWidth
import com.byeonggon.latent.ui.common.rememberWindowLayout
import com.byeonggon.latent.ui.editor.EditorScreen
import com.byeonggon.latent.ui.library.LibraryScreen
import com.byeonggon.latent.ui.library.LibraryViewModel
import com.byeonggon.latent.ui.location.LocationScreen
import com.byeonggon.latent.ui.sync.SyncScreen
import com.byeonggon.latent.ui.viewer.ViewerScreen

private enum class Tab(val label: String) {
    LIBRARY("보관함"),
    SYNC("카메라"),
    LOCATION("위치"),
}

@Composable
fun AppRoot(startOnSync: Boolean) {
    var tab by rememberSaveable { mutableStateOf(if (startOnSync) Tab.SYNC else Tab.LIBRARY) }
    var viewerItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Hoisted so the grid keeps its scroll position while the viewer is open.
    val libraryGridState = rememberLazyGridState()

    var editing by rememberSaveable { mutableStateOf(false) }
    // The viewer pages through the photos in the order, and with the filters, that the library lists them.
    val library: LibraryViewModel = viewModel()
    val libraryState by library.state.collectAsStateWithLifecycle()
    val shownInLibrary = remember(libraryState.days, libraryState.showingTrash) {
        if (libraryState.showingTrash) emptyList() else libraryState.days.flatMap { it.items }
    }

    val openItemId = viewerItemId
    if (openItemId != null) {
        if (editing) {
            EditorScreen(itemId = openItemId, onClose = { editing = false })
        } else {
            ViewerScreen(
                itemId = openItemId,
                items = shownInLibrary,
                partners = libraryState.partners,
                stacked = libraryState.stacked,
                onItemChange = { viewerItemId = it },
                onClose = { viewerItemId = null },
                onEdit = { editing = true },
            )
        }
        return
    }

    val window = rememberWindowLayout()
    val content: @Composable () -> Unit = {
        when (tab) {
            Tab.LIBRARY -> LibraryScreen(gridState = libraryGridState, onOpen = { viewerItemId = it })
            Tab.SYNC -> ReadableWidth { SyncScreen() }
            Tab.LOCATION -> ReadableWidth { LocationScreen() }
        }
    }
    if (window.sideNavigation) {
        // A wide window has height to spare less than width: the tabs go down the side.
        Scaffold { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail {
                    Tab.entries.forEach { entry ->
                        NavigationRailItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { TabIcon(entry) },
                            label = { Text(entry.label) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) { content() }
            }
        }
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
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

@Composable
private fun TabIcon(tab: Tab) {
    when (tab) {
        Tab.LIBRARY -> Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null)
        Tab.SYNC -> Icon(painterResource(R.drawable.ic_camera), contentDescription = null)
        Tab.LOCATION -> Icon(Icons.Default.LocationOn, contentDescription = null)
    }
}
