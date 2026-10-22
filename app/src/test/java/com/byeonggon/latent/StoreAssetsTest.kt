package com.byeonggon.latent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the pictures the Play Store asks for from the app's own icon, into `store/`. It only runs when
 * asked: `LATENT_STORE_ASSETS=1 ./gradlew testDebugUnitTest --tests '*StoreAssetsTest'`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreAssetsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val folder = File(System.getProperty("user.dir"), "../store")

    /** The icon as a launcher shows it, square and without a mask: the 72 dp that every mask shows, out of the 108 dp canvas. */
    private fun mark(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        val canvasSize = (size * 108f / 72f).toInt()
        val offset = (size - canvasSize) / 2
        val foreground = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
        foreground.setBounds(offset, offset, offset + canvasSize, offset + canvasSize)
        foreground.draw(canvas)
        return bitmap
    }

    /** Play takes the icon with alpha, and the feature graphic and the developer page's header only without it (24-bit PNG). */
    private fun save(bitmap: Bitmap, name: String, opaque: Boolean = false) {
        folder.mkdirs()
        bitmap.setHasAlpha(!opaque)
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun drawsTheStorePictures() {
        assumeTrue(System.getenv("LATENT_STORE_ASSETS") != null)
        save(mark(512), "icon-512.png")

        val graphic = Bitmap.createBitmap(1024, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(graphic)
        canvas.drawColor(BACKGROUND)
        canvas.drawBitmap(mark(300), 90f, 100f, null)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CREAM
            textSize = 120f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Latent", 430f, 250f, title)
        val tagline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 242, 237, 228)
            textSize = 36f
        }
        canvas.drawText("Develop photos on your phone.", 434f, 320f, tagline)
        canvas.drawText("From camera to map, privately.", 434f, 368f, tagline)
        save(graphic, "feature-graphic-1024x500.png", opaque = true)

        // The developer page's header, 4096 × 2304: the mark large in the middle, its developer's surface drawn across the
        // whole width. Play shows it cropped to a wide band on some screens, so nothing lies far above or below the middle.
        val header = Bitmap.createBitmap(4096, 2304, Bitmap.Config.ARGB_8888)
        Canvas(header).apply {
            drawColor(BACKGROUND)
            val unit = 22f // pixels for each dp of the icon's 108 dp canvas: the 40 dp disc comes out 880 px across
            val left = 2048f - 54f * unit
            val top = 1152f - 54f * unit
            drawRect(0f, top + 54.5f * unit, 4096f, top + 57.5f * unit, Paint().apply { color = AMBER })
            val foreground = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
            foreground.setBounds(left.toInt(), top.toInt(), (left + 108f * unit).toInt(), (top + 108f * unit).toInt())
            foreground.draw(this)
        }
        save(header, "developer-header-4096x2304.png", opaque = true)
    }

    private companion object {
        val BACKGROUND = 0xFF17171A.toInt()
        val CREAM = 0xFFF2EDE4.toInt()
        val AMBER = 0xFFE8A23A.toInt()
    }
}
