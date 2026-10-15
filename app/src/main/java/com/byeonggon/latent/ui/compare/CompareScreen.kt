package com.byeonggon.latent.ui.compare

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.byeonggon.latent.R
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.FLAG_PICKED
import com.byeonggon.latent.data.db.FLAG_REJECTED
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.ui.common.SafeInsets
import com.byeonggon.latent.ui.common.ZoomableBox
import com.byeonggon.latent.ui.common.rememberMediaModel
import com.byeonggon.latent.ui.common.rememberThumbnailModel
import com.byeonggon.latent.ui.common.rememberWindowLayout
import com.byeonggon.latent.ui.common.rememberZoomState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The most photos that are set side by side: more would be too small to tell apart. */
const val MOST_COMPARED = 4

class CompareViewModel(application: Application) : AndroidViewModel(application) {
    private val mediaDao = application.appContainer.database.mediaDao()

    /** The photos with [ids], in that order, as they change; one that is gone drops out. */
    fun items(ids: List<Long>): Flow<List<MediaItemEntity>> =
        if (ids.isEmpty()) flowOf(emptyList()) else combine(ids.map { mediaDao.observeById(it) }) { found -> found.filterNotNull() }

    fun setRating(id: Long, rating: Int) {
        viewModelScope.launch {
            // What the database has: a second tap may come before the screen shows the first.
            val current = mediaDao.byId(id)?.rating ?: return@launch
            mediaDao.setRating(id, if (rating == current) 0 else rating)
        }
    }

    /** Flags a photo as picked or rejected; the flag it has takes itself away again. [alsoId] is the other file of its raw and JPEG pair. */
    fun setFlag(id: Long, flag: Int, alsoId: Long?) {
        viewModelScope.launch {
            val current = mediaDao.byId(id)?.flag ?: return@launch
            mediaDao.setFlag(listOfNotNull(id, alsoId), if (flag == current) 0 else flag)
        }
    }
}

/**
 * Two to four photos side by side to choose between, zoomed and moved together: pinch one and all follow,
 * so that the same detail of each can be compared. Each can be picked, rejected and given stars on the spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(ids: List<Long>, partners: Map<Long, Long>, onClose: () -> Unit, viewModel: CompareViewModel = viewModel()) {
    BackHandler(onBack = onClose)
    val items by remember(ids) { viewModel.items(ids) }.collectAsStateWithLifecycle(emptyList())
    val zoom = rememberZoomState(ids)
    Scaffold(
        contentWindowInsets = SafeInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compare)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close)) } },
            )
        },
    ) { padding ->
        // Two stand side by side where the window is wide, one above the other where it is tall; more, in a grid of two across.
        val across = if (items.size <= 1) 1 else if (items.size == 2 && !rememberWindowLayout().sideNavigation) 1 else 2
        val rows = items.chunked(across)
        Column(Modifier.fillMaxSize().padding(padding).background(Color.Black), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ZoomableBox(resetKey = ids, state = zoom) {
                                AsyncImage(
                                    // A raw file cannot be drawn as it is: its thumbnail stands in.
                                    model = if (item.kind == MediaKind.PHOTO) rememberMediaModel(item) else rememberThumbnailModel(item),
                                    contentDescription = item.fileName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Verdict(
                                item = item,
                                onFlag = { viewModel.setFlag(item.id, it, partners[item.id]) },
                                onRate = { viewModel.setRating(item.id, it) },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                    // A row that is not full leaves its place empty rather than stretching the last photo.
                    repeat(across - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Under each photo: its name, whether it is picked or rejected, and its stars. */
@Composable
private fun Verdict(item: MediaItemEntity, onFlag: (Int) -> Unit, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(item.fileName, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_flag),
                contentDescription = stringResource(R.string.flag_pick),
                tint = if (item.flag == FLAG_PICKED) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onFlag(FLAG_PICKED) }.padding(7.dp).size(20.dp),
            )
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.flag_reject),
                tint = if (item.flag == FLAG_REJECTED) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onFlag(FLAG_REJECTED) }.padding(7.dp).size(20.dp),
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                for (stars in 1..5) {
                    Icon(
                        painterResource(if (stars <= item.rating) R.drawable.ic_star_filled else R.drawable.ic_star),
                        contentDescription = pluralStringResource(R.plurals.stars, stars, stars),
                        tint = if (stars <= item.rating) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onRate(stars) }.padding(3.dp).size(20.dp),
                    )
                }
            }
        }
    }
}
