package com.byeonggon.latent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byeonggon.latent.R
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.TabPosition
import com.byeonggon.latent.ui.common.ReadableWidth
import com.byeonggon.latent.ui.common.SafeInsets
import com.byeonggon.latent.ui.common.forBottomBar
import com.byeonggon.latent.ui.common.rememberWindowLayout
import com.byeonggon.latent.ui.editor.EditorScreen
import com.byeonggon.latent.ui.library.LibraryScreen
import com.byeonggon.latent.ui.compare.CompareScreen
import com.byeonggon.latent.ui.library.LibraryViewModel
import com.byeonggon.latent.ui.location.LocationScreen
import com.byeonggon.latent.ui.map.MapScreen
import com.byeonggon.latent.ui.sync.SyncScreen
import com.byeonggon.latent.ui.viewer.ViewerScreen
import kotlinx.coroutines.flow.map
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

private enum class Tab(@StringRes val label: Int) {
    LIBRARY(R.string.tab_library),
    SYNC(R.string.tab_import),
    MAP(R.string.tab_map),
}

@Composable
fun AppRoot(startOnSync: Boolean) {
    var tab by rememberSaveable { mutableStateOf(if (startOnSync) Tab.SYNC else Tab.LIBRARY) }
    var viewerItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Hoisted so the grid keeps its scroll position while the viewer is open.
    val libraryGridState = rememberLazyGridState()

    var editing by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Recording and placing by location is a screen of its own over the tabs, or over the settings it was opened from.
    var locationOpen by rememberSaveable { mutableStateOf(false) }
    // What the screen under Location had, its scroll position say, kept for the way back; dropped when it is left
    // another way, so a tab or the settings opened again start at the top as before.
    val kept = rememberSaveableStateHolder()
    val closeSettings = {
        kept.removeState(SETTINGS)
        settingsOpen = false
    }
    // The photos being compared, as the ids written out with commas: a saved state holds text more surely than lists.
    var comparing by rememberSaveable { mutableStateOf<String?>(null) }
    val tabPositions = LocalContext.current.appContainer.settings.settings
    val tabPosition by remember { tabPositions.map { it.tabPosition } }.collectAsStateWithLifecycle(TabPosition.AUTO)
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

    comparing?.let { written ->
        CompareScreen(
            ids = written.split(',').mapNotNull { it.toLongOrNull() },
            partners = libraryState.partners,
            onClose = { comparing = null },
        )
        return
    }

    if (locationOpen) {
        LocationScreen(onBack = { locationOpen = false })
        return
    }

    if (settingsOpen) {
        kept.SaveableStateProvider(SETTINGS) {
            SettingsScreen(
                onBack = closeSettings,
                onOpenLocation = { locationOpen = true },
                onChoosePhoneFolders = {
                    // The folders are chosen in the library, which shows the question.
                    closeSettings()
                    tab = Tab.LIBRARY
                    library.choosePhoneFolders()
                },
            )
        }
        return
    }

    val window = rememberWindowLayout()
    val content: @Composable () -> Unit = {
        kept.SaveableStateProvider(tab.name) {
            when (tab) {
                Tab.LIBRARY -> LibraryScreen(gridState = libraryGridState, onOpen = { viewerItemId = it }, onSettings = { settingsOpen = true }, onCompare = { comparing = it.joinToString(",") })
                Tab.SYNC -> ReadableWidth { SyncScreen(onOpenLocation = { locationOpen = true }) }
                Tab.MAP -> MapScreen(onOpen = { viewerItemId = it })
            }
        }
    }
    val choose = { entry: Tab ->
        if (entry != tab) kept.removeState(tab.name)
        tab = entry
    }
    // A wide window has height to spare less than width: the tabs go down a side, the left unless asked otherwise;
    // a narrow one, or the bottom as asked, has them along the bottom.
    val side = if (window.sideNavigation && tabPosition != TabPosition.BOTTOM) tabPosition else null
    if (side != null) {
        val rail: @Composable () -> Unit = {
            // The parent keeps clear of the system bars already.
            NavigationRail(windowInsets = WindowInsets(0)) {
                // In the middle of the side, not at the top: a foldable's camera sits near the top corner of its inner screen.
                Spacer(Modifier.weight(1f))
                Tab.entries.forEach { entry ->
                    NavigationRailItem(
                        selected = tab == entry,
                        onClick = { choose(entry) },
                        icon = { TabIcon(entry) },
                        label = { Text(stringResource(entry.label)) },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Scaffold(contentWindowInsets = SafeInsets) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (side != TabPosition.RIGHT) rail()
                Box(Modifier.weight(1f).fillMaxSize()) { content() }
                if (side == TabPosition.RIGHT) rail()
            }
        }
        return
    }
    Scaffold(
        contentWindowInsets = SafeInsets,
        bottomBar = {
            NavigationBar(windowInsets = SafeInsets.forBottomBar()) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { choose(entry) },
                        icon = { TabIcon(entry) },
                        label = { Text(stringResource(entry.label)) },
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
        Tab.SYNC -> Icon(painterResource(R.drawable.ic_import), contentDescription = null)
        Tab.MAP -> Icon(painterResource(R.drawable.ic_place), contentDescription = null)
    }
}

private const val SETTINGS = "settings"
