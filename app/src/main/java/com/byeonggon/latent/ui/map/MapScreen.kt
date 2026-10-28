package com.byeonggon.latent.ui.map

import com.byeonggon.latent.ui.common.openInBrowser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.byeonggon.latent.R
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.map.Cluster
import com.byeonggon.latent.map.Clusters
import com.byeonggon.latent.map.MapMath
import com.byeonggon.latent.map.MapPoint
import com.byeonggon.latent.map.MapTiles
import com.byeonggon.latent.ui.common.rememberThumbnailModel
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.material3.CardDefaults

private const val MIN_ZOOM = 1.5f
private const val MAX_ZOOM = 19f

/** Where the photos were taken, on a map from the internet that the person has to turn on first. */
@Composable
fun MapScreen(onOpen: (Long) -> Unit, viewModel: MapViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.onlineMaps) {
        MapsOff(onTurnOn = { viewModel.setOnlineMaps(true) })
        return
    }
    Box(Modifier.fillMaxSize()) {
        PhotoMap(state.points, viewModel.tiles, viewModel, onOpen)
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            FilledTonalIconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.map_menu)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.map_turn_off)) },
                    onClick = {
                        menu = false
                        viewModel.setOnlineMaps(false)
                    },
                )
            }
        }
        if (state.points.isEmpty()) {
            Surface(Modifier.align(Alignment.Center).padding(32.dp), shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
                Text(stringResource(R.string.map_no_places), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** What the map tab says while the internet is off, which is how the app starts out: why it asks, and a way to say yes. */
@Composable
private fun MapsOff(onTurnOn: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.tab_map), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
        // A quiet card in the map's place, saying that online maps are off and how to turn them on (UI_GUIDE §8.11).
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_place), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Text(stringResource(R.string.map_off_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.map_off_text), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { asking = true }) { Text(stringResource(R.string.map_turn_on)) }
            }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.map_ask_title)) },
            text = { Text(stringResource(R.string.map_ask_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onTurnOn()
                    },
                ) { Text(stringResource(R.string.map_ask_yes)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private data class TileKey(val zoom: Int, val x: Int, val y: Int)

/**
 * The map: tiles under photo markers, moved with a finger or the mouse wheel, zoomed by pinching, by a
 * double tap or by the buttons. Photos near each other are one marker with their number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoMap(points: List<MapPoint>, tiles: MapTiles, viewModel: MapViewModel, onOpen: (Long) -> Unit) {
    val density = LocalDensity.current
    // Tiles are 256 pixels; drawn at more than twice that on a dense screen they would blur.
    val tilePixels = 256f * min(density.density, 2f)
    var zoom by rememberSaveable { mutableFloatStateOf(2f) }
    var centreX by rememberSaveable { mutableFloatStateOf(0.5f) }
    var centreY by rememberSaveable { mutableFloatStateOf(0.4f) }
    var placed by rememberSaveable { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<List<MediaItemEntity>?>(null) }

    // The first time, the map opens on where the photos are.
    LaunchedEffect(points.isNotEmpty(), size) {
        if (placed || points.isEmpty() || size == IntSize.Zero) return@LaunchedEffect
        placed = true
        val xs = points.map { MapMath.worldX(it.longitude) }
        val ys = points.map { MapMath.worldY(it.latitude) }
        // Most photos are near home; a handful far away should not push the map out to the whole world.
        val low = 0.05
        val high = 0.95
        fun spread(values: List<Double>) = values.sorted().let { it[(it.size * low).toInt()] to it[((it.size - 1) * high).toInt()] }
        val (x0, x1) = spread(xs)
        val (y0, y1) = spread(ys)
        centreX = ((x0 + x1) / 2).toFloat()
        centreY = ((y0 + y1) / 2).toFloat()
        val across = max(x1 - x0, 1e-6) * 1.3
        val down = max(y1 - y0, 1e-6) * 1.3
        val fit = min(
            ln2(size.width / (tilePixels * across)),
            ln2(size.height / (tilePixels * down)),
        )
        zoom = fit.toFloat().coerceIn(MIN_ZOOM, 15f)
    }

    fun zoomBy(change: Float, around: Offset) {
        val worldBefore = tilePixels * 2f.pow(zoom)
        val newZoom = (zoom + change).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val worldAfter = tilePixels * 2f.pow(newZoom)
        // The place under [around] stays under it.
        val dx = around.x - size.width / 2f
        val dy = around.y - size.height / 2f
        centreX = (centreX + dx / worldBefore - dx / worldAfter).coerceIn(0f, 1f)
        centreY = (centreY + dy / worldBefore - dy / worldAfter).coerceIn(0f, 1f)
        zoom = newZoom
    }

    BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged { size = it }.background(Color(0xFFE5E3DF)).clip(androidx.compose.ui.graphics.RectangleShape)) {
        val worldPixels = tilePixels * 2f.pow(zoom)
        val tileZoom = floor(zoom).toInt().coerceIn(0, 19)
        val tileSide = worldPixels / MapMath.tilesAcross(tileZoom)

        // Tiles come in as they are fetched; those no longer wanted are dropped.
        val loaded = remember { mutableStateMapOf<TileKey, ImageBitmap>() }
        val wanted = remember(zoom, centreX, centreY, size) {
            if (size == IntSize.Zero) emptyList() else {
                val n = MapMath.tilesAcross(tileZoom)
                val left = ((centreX - size.width / 2f / worldPixels) * n).toInt().coerceAtLeast(0)
                val right = ((centreX + size.width / 2f / worldPixels) * n).toInt().coerceAtMost(n - 1)
                val top = ((centreY - size.height / 2f / worldPixels) * n).toInt().coerceAtLeast(0)
                val bottom = ((centreY + size.height / 2f / worldPixels) * n).toInt().coerceAtMost(n - 1)
                (top..bottom).flatMap { y -> (left..right).map { x -> TileKey(tileZoom, x, y) } }
            }
        }
        LaunchedEffect(wanted) {
            wanted.filter { it !in loaded }.forEach { key ->
                launch { tiles.tile(key.zoom, key.x, key.y)?.let { loaded[key] = it.asImageBitmap() } }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val world = tilePixels * 2f.pow(zoom)
                        centreX = (centreX - pan.x / world).coerceIn(0f, 1f)
                        centreY = (centreY - pan.y / world).coerceIn(0f, 1f)
                        if (gestureZoom != 1f) zoomBy(kotlin.math.log2(gestureZoom), centroid)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoomBy(1f, it) }) }
                .pointerInput(Unit) {
                    // A mouse wheel or a touchpad zooms, as maps do on a computer.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val turned = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                            if (turned != 0f) zoomBy(-turned * 0.4f, event.changes.first().position)
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            val n = MapMath.tilesAcross(tileZoom)
            for ((key, image) in loaded) {
                if (key.zoom != tileZoom) continue
                val left = (key.x.toFloat() / n - centreX) * worldPixels + this.size.width / 2f
                val top = (key.y.toFloat() / n - centreY) * worldPixels + this.size.height / 2f
                drawImage(
                    image,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(tileSide.roundToInt() + 1, tileSide.roundToInt() + 1),
                )
            }
        }

        // Photos near each other at this zoom level, as one marker with their number.
        val clusters = remember(points, tileZoom) { Clusters.of(points, tilePixels * 2.0.pow(tileZoom), with(density) { 48.dp.toPx() }.toDouble()) }
        val markerPixels = with(density) { 36.dp.toPx() }
        clusters.forEach { cluster ->
            val x = (MapMath.worldX(cluster.longitude) - centreX) * worldPixels + size.width / 2f
            val y = (MapMath.worldY(cluster.latitude) - centreY) * worldPixels + size.height / 2f
            if (x < -markerPixels || y < -markerPixels || x > size.width + markerPixels || y > size.height + markerPixels) return@forEach
            Marker(
                cluster,
                modifier = Modifier.offset { IntOffset((x - markerPixels / 2).roundToInt(), (y - markerPixels / 2).roundToInt()) },
                onClick = {
                    if (cluster.count <= SHOWN_AT_ONCE || zoom >= 16f) {
                        scope.launch { shown = viewModel.itemsOf(cluster.ids) }
                    } else {
                        // Zoomed in on the place, to tell the photos apart.
                        centreX = MapMath.worldX(cluster.longitude).toFloat()
                        centreY = MapMath.worldY(cluster.latitude).toFloat()
                        zoomBy(2f, Offset(size.width / 2f, size.height / 2f))
                    }
                },
            )
        }

        Column(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { zoomBy(1f, Offset(size.width / 2f, size.height / 2f)) }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.map_zoom_in))
            }
            val zoomOut = stringResource(R.string.map_zoom_out)
            FilledTonalIconButton(
                onClick = { zoomBy(-1f, Offset(size.width / 2f, size.height / 2f)) },
                modifier = Modifier.semantics { contentDescription = zoomOut },
            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        }
        // The map's makers ask to be named wherever it is shown, with a link to their copyright page.
        val context = LocalContext.current
        Text(
            stringResource(R.string.map_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd)
                .clickable { context.openInBrowser(OSM_COPYRIGHT) }
                .background(Color.White.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }

    shown?.let { items ->
        ModalBottomSheet(onDismissRequest = { shown = null }) {
            Text(
                pluralStringResource(R.plurals.map_photos_here, items.size, items.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item ->
                    AsyncImage(
                        model = rememberThumbnailModel(item),
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(104.dp).clip(MaterialTheme.shapes.small).clickable {
                            shown = null
                            onOpen(item.id)
                        },
                    )
                }
            }
        }
    }
}

/** A circle on the map with the number of photos taken there. */
@Composable
private fun Marker(cluster: Cluster, modifier: Modifier, onClick: () -> Unit) {
    val description = pluralStringResource(R.plurals.map_photos_here, cluster.count, cluster.count)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
        modifier = modifier.size(36.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                if (cluster.count > 999) "999+" else cluster.count.toString(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(2.dp),
            )
        }
    }
}

/** How many photos a marker may stand for and still show them all at once instead of zooming in. */
private const val SHOWN_AT_ONCE = 40

private fun ln2(value: Double): Double = kotlin.math.ln(value) / kotlin.math.ln(2.0)

/** What OpenStreetMap's licence asks the map's credit to lead to. */
private const val OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright"
