package com.byeonggon.latent.ui.editor

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustment
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.AutoSettings
import com.byeonggon.latent.enhance.ClippingMarks
import com.byeonggon.latent.enhance.ColorGrading
import com.byeonggon.latent.enhance.ColorMixer
import com.byeonggon.latent.enhance.DetailFilter
import com.byeonggon.latent.enhance.Filter
import com.byeonggon.latent.enhance.Framing
import com.byeonggon.latent.enhance.Healing
import com.byeonggon.latent.enhance.LinearImage
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.enhance.PhotoEnhancer
import com.byeonggon.latent.enhance.PictureRegion
import com.byeonggon.latent.enhance.Spot
import com.byeonggon.latent.enhance.ToneCurves
import com.byeonggon.latent.enhance.ToneRenderer
import com.byeonggon.latent.enhance.bitmapOf
import com.byeonggon.latent.enhance.framed
import com.byeonggon.latent.raw.RawRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import androidx.annotation.StringRes
import com.byeonggon.latent.R

/** What the editor shows of the picture. */
enum class EditorView {
    /** The picture as the edit makes it. */
    PICTURE,

    /** All of the picture, for the part that is kept to be marked on it. */
    WHOLE,

    /** A part of the picture at its full size, where sharpening and noise can be told. */
    DETAIL,
}

/** How many pixels of a picture have each brightness, for red, green and blue, from black (first) to white. */
class Histogram(val red: IntArray, val green: IntArray, val blue: IntArray)

data class EditorUiState(
    val item: MediaItemEntity? = null,
    val edit: PhotoEdit = PhotoEdit(),
    val preview: Bitmap? = null,
    val histogram: Histogram? = null,
    /** Size of the whole picture as it is drawn here, upright and before the edit's own framing. */
    val pictureWidth: Int = 0,
    val pictureHeight: Int = 0,
    val view: EditorView = EditorView.PICTURE,
    /** Which view the preview was drawn for; it lags a moment behind [view]. */
    val previewView: EditorView = EditorView.PICTURE,
    /** Showing the picture without its corrections for a moment, to compare. */
    val comparing: Boolean = false,
    /** Marking where the picture is blown out or crushed. */
    val clipping: Boolean = false,
    /** Whether anything was changed since the editor opened; closing now would lose it. */
    val changed: Boolean = false,
    val canSave: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Whether tones and colours copied from another photo are waiting to be used. */
    val canPaste: Boolean = false,
    val filters: List<Filter> = emptyList(),
    /** The picture with each of the [filters] on it, by the filter's id. */
    val filterPreviews: Map<String, Bitmap> = emptyMap(),
    /** The filter whose look the edit has right now, if it has one, and at what strength. */
    val filter: Filter? = null,
    val filterAmount: Float = 1f,
    /** The picture is still being read. */
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    /** Nothing is left to do here: the edit was saved, or the photo could not be opened at all. */
    val finished: Boolean = false,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val mediaDao = container.database.mediaDao()
    private val edits = container.libraryEdits
    private val ui = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = ui.asStateFlow()

    private val source = MutableStateFlow<Source?>(null)
    private var session: Long? = null
    private var loading: Job? = null

    /** The edit as it was when the editor opened, and whether it is stored with the photo already. */
    private var opened = PhotoEdit()
    private var stored = false

    /** Every state the edit went through, for undoing; [position] is the one in effect. */
    private val history = ArrayList<PhotoEdit>()
    private var position = 0

    /** While a slider or a point is being dragged, its moves count as one change. */
    private var changing = false

    /**
     * Whether the preview may be drawn at half its size, to keep up with a slider that is still moving;
     * once it is let go of, the picture is drawn in full again.
     */
    private val drafting = MutableStateFlow(false)

    /** The centre of the part shown at full size, as fractions of the picture. */
    private val detailCentre = MutableStateFlow(0.5f to 0.5f)

    init {
        viewModelScope.launch { edits.copied.collect { copied -> ui.update { it.copy(canPaste = copied != null) } } }
        viewModelScope.launch { edits.filters.collect { filters -> ui.update { it.copy(filters = filters).checked() } } }
        // The filters are shown on the picture itself; drawing them all takes a moment.
        viewModelScope.launch {
            combine(source, edits.filters) { source, filters -> source to filters }.collectLatest { (source, filters) ->
                if (source == null) return@collectLatest
                val previews = withContext(Dispatchers.Default) { source.previews(filters) }
                if (this@EditorViewModel.source.value === source) ui.update { it.copy(filterPreviews = previews) }
            }
        }
        // One rendering at a time, always of the latest settings: a moving slider asks for more than can be drawn.
        viewModelScope.launch {
            combine(source, ui.map(::drawn).distinctUntilChanged(), detailCentre, drafting) { source, drawn, centre, draft ->
                Drawing(source, drawn.first, drawn.second, centre, draft, drawn.third)
            }
                .conflate()
                .collect { (source, edit, view, centre, draft, clipping) ->
                    if (source == null) return@collect
                    val rendered = try {
                        withContext(Dispatchers.Default) { source.render(edit, view, centre, draft, clipping) }
                    } catch (e: OutOfMemoryError) {
                        null
                    } catch (e: IOException) {
                        null
                    }
                    if (this@EditorViewModel.source.value !== source) return@collect
                    ui.update {
                        if (rendered == null) {
                            it.copy(loading = false, message = text(R.string.preview_failed))
                        } else {
                            it.copy(
                                preview = rendered.bitmap,
                                histogram = rendered.histogram,
                                previewView = view,
                                pictureWidth = source.image.width,
                                pictureHeight = source.image.height,
                                loading = false,
                            )
                        }
                    }
                }
        }
    }

    /** Starts editing a photo. Asking again for the photo being edited changes nothing, so a rotated screen keeps the edit. */
    fun open(id: Long) {
        if (session == id) return
        close()
        session = id
        loading = viewModelScope.launch {
            val item = mediaDao.byId(id)?.takeIf { it.kind != MediaKind.VIDEO }
            if (item == null) {
                ui.update { it.copy(loading = false, finished = true) }
                return@launch
            }
            val inEffect = withContext(Dispatchers.IO) { edits.editOf(item) }
            opened = inEffect ?: PhotoEdit()
            // A raw file that was never saved here is saved even unchanged: from then on it is shown as developed.
            stored = inEffect != null || item.kind == MediaKind.PHOTO
            history.clear()
            history += opened
            position = 0
            changing = false
            ui.update { it.copy(item = item, edit = opened).checked() }
            load(item, opened.lensCorrection)
        }
    }

    /** Ends the editing session and lets go of the picture. */
    fun close() {
        loading?.cancel()
        session = null
        source.value = null
        detailCentre.value = 0.5f to 0.5f
        ui.update { EditorUiState(canPaste = edits.copied.value != null, filters = it.filters) }
    }

    /** Remembers this photo's tones and colours, to be given to other photos. */
    fun copyEdit() {
        edits.copied.value = ui.value.edit
        ui.update { it.copy(message = text(R.string.edit_copied)) }
    }

    /** Takes over the tones and colours copied from another photo; the framing stays this photo's own. */
    fun pasteEdit() {
        val copied = edits.copied.value ?: return
        changeOnce { it.withLookOf(copied) }
    }

    fun adjust(change: (Adjustments) -> Adjustments) = change { it.copy(adjustments = change(it.adjustments)) }

    fun setCurves(curves: ToneCurves) = change { it.copy(curves = curves) }

    fun setMixer(mixer: ColorMixer) = change { it.copy(mixer = mixer) }

    fun setGrading(grading: ColorGrading) = change { it.copy(grading = grading) }

    fun setFraming(framing: Framing) = change { it.copy(framing = framing) }

    fun setLocals(locals: List<LocalEdit>) = change { it.copy(locals = locals) }

    fun setSpots(spots: List<Spot>) = change { it.copy(spots = spots) }

    /**
     * Takes out a blemish at ([x], [y]) of the whole picture, [radius] across, patched from wherever
     * nearby looks most like what surrounds it. [onAdded] is told the new spot's place among the others.
     */
    fun addSpot(x: Float, y: Float, radius: Float, onAdded: (Int) -> Unit) {
        val image = source.value?.image ?: return
        viewModelScope.launch {
            val (fromX, fromY) = withContext(Dispatchers.Default) { Healing.sourceFor(image, x, y, radius) }
            val spots = ui.value.edit.spots
            changeOnce { it.copy(spots = spots + Spot(x, y, radius, fromX, fromY)) }
            onAdded(spots.size)
        }
    }

    fun setMonochrome(enabled: Boolean) = changeOnce { it.copy(monochrome = enabled) }

    fun setAutoEnhance(enabled: Boolean) = changeOnce { it.copy(autoEnhance = enabled) }

    /**
     * Sets the light and colour sliders to what the picture seems to need. Settings it does not judge,
     * such as texture or vignette, stay as they are.
     */
    fun autoAdjust() {
        val image = source.value?.image ?: return
        viewModelScope.launch {
            val auto = withContext(Dispatchers.Default) { AutoSettings.of(image.downscaled(ToneRenderer.OVERVIEW_EDGE)) }
            changeOnce { edit ->
                val judged = listOf(
                    Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS, Adjustment.SHADOWS, Adjustment.WHITES,
                    Adjustment.BLACKS, Adjustment.TEMPERATURE, Adjustment.TINT, Adjustment.VIBRANCE,
                )
                edit.copy(adjustments = judged.fold(edit.adjustments) { all, each -> each.set(all, each.get(auto)) }, autoEnhance = false)
            }
        }
    }

    fun setLensCorrection(enabled: Boolean) = changeOnce { it.copy(lensCorrection = enabled) }

    /** Gives the photo the look of [filter] at full strength, or takes every look away for null. */
    fun applyFilter(filter: Filter?) {
        ui.update { it.copy(filter = filter, filterAmount = 1f) }
        changeOnce { it.withLookOf(filter?.look ?: PhotoEdit()) }
    }

    /** Doses the filter that was applied: nothing of it at 0, all of it at 1, more than that above. */
    fun setFilterAmount(amount: Float) {
        val filter = ui.value.filter ?: return
        ui.update { it.copy(filterAmount = amount) }
        change { it.withLookOf(filter.look.lookAt(amount)) }
    }

    /** Keeps the look the photo has now as a filter of the user's own. */
    fun saveFilter(name: String) {
        val edit = ui.value.edit
        viewModelScope.launch {
            edits.saveFilter(name.trim(), edit)
            ui.update { it.copy(message = text(R.string.filter_saved)) }
        }
    }

    fun deleteFilter(filter: Filter) {
        viewModelScope.launch { edits.deleteFilter(filter) }
    }

    /** Puts every setting back to where it leaves the picture alone. */
    fun reset() = changeOnce { PhotoEdit() }

    /**
     * Ends a change that was made in many small moves, such as dragging a slider. What follows is a
     * new change, to be undone separately.
     */
    fun commit() {
        changing = false
        drafting.value = false
    }

    fun undo() = stepTo(position - 1)

    fun redo() = stepTo(position + 1)

    fun setComparing(comparing: Boolean) = ui.update { it.copy(comparing = comparing) }

    fun setClipping(clipping: Boolean) = ui.update { it.copy(clipping = clipping) }

    fun show(view: EditorView) = ui.update { it.copy(view = view) }

    /**
     * Moves on from the part shown at full size, as dragging the picture does: [across] and [down]
     * are how far it was dragged, in fractions of what is shown.
     */
    fun moveDetail(across: Float, down: Float) {
        val share = source.value?.detailShare ?: return
        detailCentre.update { (x, y) ->
            (x - across * share.first).coerceIn(0f, 1f) to (y - down * share.second).coerceIn(0f, 1f)
        }
    }

    /** Hands the edit over to be saved in the background and closes the editor at once. */
    fun save() {
        val current = ui.value
        val item = current.item ?: return
        if (current.saving || !current.canSave) return
        container.editSaver.save(item, current.edit)
        ui.update { it.copy(finished = true) }
    }

    fun consumeMessage() = ui.update { it.copy(message = null) }

    /** A change that is complete in itself, such as flipping a switch. */
    private fun changeOnce(transform: (PhotoEdit) -> PhotoEdit) {
        commit()
        change(transform)
        commit()
    }

    private fun change(transform: (PhotoEdit) -> PhotoEdit) {
        if (ui.value.item == null) return
        val before = ui.value.edit
        val after = transform(before)
        if (after == before) return
        // Whatever was undone is gone once something new is done.
        while (history.size > position + 1) history.removeAt(history.lastIndex)
        if (changing && position > 0) {
            history[position] = after
            // From the second move of a drag on, the drag is under way.
            drafting.value = true
        } else {
            history += after
            position++
            changing = true
        }
        put(after)
    }

    private fun stepTo(wanted: Int) {
        if (wanted !in history.indices) return
        commit()
        position = wanted
        put(history[wanted])
    }

    /** Makes [edit] the one in effect. A raw file is read again if its lens correction changed with it. */
    private fun put(edit: PhotoEdit) {
        val item = ui.value.item ?: return
        val lensChanged = edit.lensCorrection != ui.value.edit.lensCorrection
        ui.update { it.copy(edit = edit).checked() }
        if (lensChanged && item.kind == MediaKind.RAW) {
            loading?.cancel()
            loading = viewModelScope.launch { load(item, edit.lensCorrection) }
        }
    }

    /** The edit and the view as the preview has to show them right now. */
    private fun drawn(state: EditorUiState): Triple<PhotoEdit, EditorView, Boolean> {
        val toned = if (state.comparing) PhotoEdit(lensCorrection = state.edit.lensCorrection, framing = state.edit.framing) else state.edit
        // Only the picture as it will be is framed; the other views show it uncut.
        return Triple(if (state.view == EditorView.PICTURE) toned else toned.copy(framing = Framing()), state.view, state.clipping)
    }

    private fun EditorUiState.checked(): EditorUiState {
        val changed = edit != opened
        // A filter stays chosen only for as long as the edit is what the filter made it, and the filter exists.
        val current = filter?.takeIf { it in filters && edit == edit.withLookOf(it.look.lookAt(filterAmount)) }
        return copy(
            filter = current,
            changed = changed,
            canSave = changed || !stored,
            canUndo = position > 0,
            canRedo = position < history.lastIndex,
        )
    }

    private suspend fun load(item: MediaItemEntity, lensCorrection: Boolean) {
        ui.update { it.copy(loading = true) }
        val loaded = try {
            withContext(Dispatchers.Default) {
                if (item.kind == MediaKind.RAW) {
                    val raw = container.rawCache.read(File(item.path))
                    Source(
                        image = RawRenderer.proxy(raw, PROXY_CELLS, lensCorrection),
                        scale = 1f / (2 * PROXY_CELLS),
                        region = { x, y -> RawRenderer.region(raw, x, y, DETAIL_SIZE) },
                    )
                } else {
                    val original = edits.originalOf(item)
                    val image = PhotoEnhancer.load(original, PROXY_EDGE)
                    Source(
                        image = image,
                        scale = maxOf(image.width, image.height).toFloat() / PhotoEnhancer.longSideOf(original).coerceAtLeast(1),
                        region = { x, y -> PhotoEnhancer.region(original, x, y, DETAIL_SIZE) },
                    )
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (loaded == null) {
            ui.update { it.copy(loading = false, message = text(R.string.open_failed)) }
        } else {
            source.value = loaded
        }
    }

    private class Rendered(val bitmap: Bitmap, val histogram: Histogram)

    private data class Drawing(val source: Source?, val edit: PhotoEdit, val view: EditorView, val centre: Pair<Float, Float>, val draft: Boolean, val clipping: Boolean)

    /**
     * The picture being edited, small enough to be drawn again for every move of a slider; [scale] is
     * its size relative to the original. [region] fetches a part of the original at full size. Only
     * one rendering runs at a time, so what is kept between renderings needs no guarding.
     */
    private class Source(
        val image: LinearImage,
        private val scale: Float,
        private val region: (centreX: Float, centreY: Float) -> PictureRegion,
    ) {
        private var spots = emptyList<Spot>()
        private var healed = image
        private var wholeOverview = image.downscaled(ToneRenderer.OVERVIEW_EDGE)
        private var framing = Framing()
        private var framed = image
        private var draftFramed: LinearImage? = null
        private var overview = wholeOverview
        private var detailCentre: Pair<Float, Float>? = null
        private var detail: PictureRegion? = null

        /** The whole picture, small, with the look of each of [filters] on it. */
        fun previews(filters: List<Filter>): Map<String, Bitmap> {
            val small = image.downscaled(FILTER_PREVIEW_EDGE)
            fun drawn(look: PhotoEdit) =
                Bitmap.createBitmap(ToneRenderer(look, small).render(small), small.width, small.height, Bitmap.Config.ARGB_8888)
            return filters.associate { it.id to drawn(PhotoEdit().withLookOf(it.look)) } + (NO_FILTER to drawn(PhotoEdit()))
        }

        /** How much of the whole picture's width and height the part shown at full size takes up. */
        @Volatile
        var detailShare = 1f to 1f
            private set

        /**
         * [draft] allows half the size while a slider moves; only for the picture as it will be, as the
         * frames drawn over the whole picture in other views go by its size.
         */
        fun render(edit: PhotoEdit, view: EditorView, centre: Pair<Float, Float>, draft: Boolean = false, clipping: Boolean = false): Rendered {
            if (view == EditorView.DETAIL) return renderDetail(edit, centre, clipping)
            if (edit.spots != spots) {
                spots = edit.spots
                healed = if (spots.isEmpty()) image else image.copy().also { Healing.heal(it, spots) }
                wholeOverview = healed.downscaled(ToneRenderer.OVERVIEW_EDGE)
                // Framed anew below.
                framing = Framing(left = -1f)
            }
            if (edit.framing != framing) {
                framing = edit.framing
                framed = healed.framed(framing)
                draftFramed = null
                overview = if (framing == Framing()) wholeOverview else framed.downscaled(ToneRenderer.OVERVIEW_EDGE)
            }
            val picture = if (draft && view == EditorView.PICTURE) {
                draftFramed ?: framed.downscaled(maxOf(framed.width, framed.height) / 2).also { draftFramed = it }
            } else {
                framed
            }
            val pixels = ToneRenderer(edit, overview).render(picture)
            val shrunk = picture.width.toFloat() / framed.width
            val histogram = histogramOf(pixels)
            return Rendered(bitmapOf(pixels, picture.width, picture.height, DetailFilter(edit.adjustments, scale * shrunk)).marked(clipping), histogram)
        }

        /** A part of the original at its full size, toned by what the edit makes of the picture as a whole. */
        private fun renderDetail(edit: PhotoEdit, centre: Pair<Float, Float>, clipping: Boolean): Rendered {
            val part = detail?.takeIf { centre == detailCentre } ?: region(centre.first, centre.second).also {
                detail = it
                detailCentre = centre
                detailShare = it.image.width.toFloat() / it.frameWidth to it.image.height.toFloat() / it.frameHeight
            }
            val pixels = IntArray(part.image.width * part.image.height)
            ToneRenderer(edit, wholeOverview).render(part.image, pixels, part.top, part.frameHeight, part.left, part.frameWidth)
            val histogram = histogramOf(pixels)
            val bitmap = bitmapOf(pixels, part.image.width, part.image.height, DetailFilter(edit.adjustments, scale = 1f)).marked(clipping)
            return Rendered(bitmap, histogram)
        }

        private fun Bitmap.marked(clipping: Boolean): Bitmap = if (clipping) ClippingMarks.markOn(this) else this

        private fun histogramOf(pixels: IntArray): Histogram {
            val red = IntArray(HISTOGRAM_BINS)
            val green = IntArray(HISTOGRAM_BINS)
            val blue = IntArray(HISTOGRAM_BINS)
            // A sample of the pixels tells the same story as all of them.
            val step = maxOf(1, pixels.size / HISTOGRAM_SAMPLES)
            for (index in pixels.indices step step) {
                val pixel = pixels[index]
                red[((pixel ushr 16) and 0xFF) * HISTOGRAM_BINS / 256]++
                green[((pixel ushr 8) and 0xFF) * HISTOGRAM_BINS / 256]++
                blue[(pixel and 0xFF) * HISTOGRAM_BINS / 256]++
            }
            return Histogram(red, green, blue)
        }
    }

    private fun text(@StringRes id: Int): String = getApplication<Application>().getString(id)

    companion object {
        /** The key of the picture without any filter among [EditorUiState.filterPreviews]. */
        const val NO_FILTER = ""

        private const val PROXY_EDGE = 1600

        /** A quarter of the sensor's resolution: 1500 pixels wide for a ZV-E10. */
        private const val PROXY_CELLS = 2

        /** The side of the part shown at full size, in pixels. */
        private const val DETAIL_SIZE = 1024
        private const val HISTOGRAM_BINS = 64
        private const val HISTOGRAM_SAMPLES = 50_000
        private const val FILTER_PREVIEW_EDGE = 200
    }
}
