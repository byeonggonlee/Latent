package com.byeonggon.latent.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FastScrollerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun day(date: String, photos: Int) =
        LibraryDay(
            LocalDate.parse(date),
            List(photos) { MediaItemEntity(id = it.toLong(), path = "/$date/$it.jpg", fileName = "$it.jpg", kind = MediaKind.PHOTO, sizeBytes = 1, capturedAt = 1, importedAt = 1) },
        )

    @Test
    fun namesTheMonthOfEachGridItem() {
        Locale.setDefault(Locale.US)
        // The title and the filters take items 0 and 1; each day then takes its header and its photos:
        // October 9 is 2..5, October 2 is 6..8, and September 20 is 9..13.
        val months = monthsAlongTheGrid(listOf(day("2026-10-09", 3), day("2026-10-02", 2), day("2026-09-20", 4)), leading = 2)

        assertEquals("October 2026", months.labelAt(0))
        assertEquals("October 2026", months.labelAt(2))
        assertEquals("October 2026", months.labelAt(8))
        assertEquals("September 2026", months.labelAt(9))
        assertEquals("September 2026", months.labelAt(13))
        assertNull(monthsAlongTheGrid(emptyList(), leading = 0).labelAt(0))
    }

    @Test
    fun travelsThroughALongGridByDraggingTheHandle() {
        val state = LazyGridState()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(GridCells.Fixed(3), state = state, modifier = Modifier.fillMaxSize()) {
                    items(600) { Box(Modifier.aspectRatio(1f)) }
                }
                FastScroller(state, labelAt = { "at $it" })
            }
        }
        // The handle only shows once the grid has been moving; hold the clock so that it does not fade away again.
        compose.onRoot().performTouchInput { swipeUp() }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        val before = state.firstVisibleItemIndex

        compose.onNodeWithTag(FAST_SCROLLER_HANDLE).performTouchInput {
            down(center)
            moveBy(Offset(0f, 600f))
            up()
        }
        compose.mainClock.advanceTimeBy(100)

        assertTrue("from $before to ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > before + 100)
    }
}
