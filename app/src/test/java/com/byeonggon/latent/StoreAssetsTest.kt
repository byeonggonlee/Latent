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

    private fun save(bitmap: Bitmap, name: String) {
        folder.mkdirs()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun drawsTheStoreIconAndFeatureGraphic() {
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
        save(graphic, "feature-graphic-1024x500.png")
    }

    private companion object {
        val BACKGROUND = 0xFF17171A.toInt()
        val CREAM = 0xFFF2EDE4.toInt()
    }
}
