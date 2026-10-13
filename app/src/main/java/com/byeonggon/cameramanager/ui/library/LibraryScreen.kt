package com.byeonggon.cameramanager.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.byeonggon.cameramanager.data.db.GeoSource
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.ui.common.ChoiceRow
import com.byeonggon.cameramanager.ui.common.StorageAccessCard
import com.byeonggon.cameramanager.ui.common.rememberStorageAccess
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN)

@Composable
fun LibraryScreen(
    gridState: LazyGridState,
    onOpen: (Long) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storageGranted = rememberStorageAccess()

    Column(Modifier.fillMaxSize()) {
        if (!storageGranted) StorageAccessCard(Modifier.padding(16.dp))
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ChoiceRow(
                options = LibraryFilter.entries,
                selected = state.filter,
                label = { it.label },
                onSelect = viewModel::setFilter,
            )
        }
        if (state.loading) {
            // Nothing to show until the first query returns; it is too quick to warrant a spinner.
        } else if (state.days.isEmpty()) {
            EmptyLibrary(noItemsAtAll = state.libraryIsEmpty)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                state = gridState,
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.days.forEach { day ->
                    item(
                        key = "day-${day.date}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "header",
                    ) {
                        Text(
                            text = "${day.date.format(DAY_FORMAT)} · ${day.items.size}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(day.items, key = { it.id }, contentType = { "media" }) { item ->
                        MediaCell(item, onClick = { onOpen(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(noItemsAtAll: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = if (noItemsAtAll) {
                "아직 가져온 사진이 없습니다.\n동기화 탭에서 카메라를 연결해 가져오세요."
            } else {
                "이 종류의 항목이 없습니다."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MediaCell(item: MediaItemEntity, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = File(item.path),
            contentDescription = item.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        when (item.kind) {
            MediaKind.RAW -> Text(
                text = "RAW",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            MediaKind.VIDEO -> Icon(
                Icons.Default.PlayArrow,
                contentDescription = "동영상",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
            )
            MediaKind.PHOTO -> Unit
        }
        if (item.geoSource != GeoSource.NONE) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = "위치 정보 있음",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp),
            )
        }
    }
}
