package com.byeonggon.latent.raw.api

import com.byeonggon.latent.raw.ArwImage

internal enum class RawFormat { SONY_ARW, DNG }
internal enum class PixelLayout { RGGB_AT_CROP, CAMERA_RGB_INTERLEAVED }
internal enum class RawCorrection { SONY_TONE_EXPANSION, DNG_LEVELS, LINEARIZATION, POSITIVE_BASELINE_EXPOSURE, GAIN_MAP }

/** Borrowed U16 storage. The owner and all consumers must stop writing before sharing it. No array escapes this API. */
internal class U16Samples(private val values: ShortArray) {
    val size: Int get() = values.size
    operator fun get(index: Int): Int = values[index].toInt() and 0xFFFF
}

/**
 * Repository-internal v0 contract, backed by the existing heap image without copying its samples.
 * These are camera samples, not rendered sRGB or guaranteed scene radiance. RGB is interleaved;
 * a mosaic repeats RGGB from the crop origin. There is no alpha, row padding, or applied orientation.
 * DNG levels/linearization/exposure/shading already applied by the reader must not be applied again.
 * White balance, calibration, demosaic, the camera look and output encoding remain Core's responsibility.
 *
 * A view keeps the whole parent allocation alive. Cache eviction drops only the cache's reference.
 * Heap ownership needs no close(). The legacy adapter still exposes mutable arrays to trusted Core code:
 * that code must treat samples and calibration as read-only and allocate its own work buffers.
 */
internal class DecodedFrame(
    internal val legacyImage: ArwImage,
    val format: RawFormat,
    val originalBitDepth: Int,
    corrections: Set<RawCorrection>,
    iccProfile: ByteArray? = null,
) {
    val samples = U16Samples(legacyImage.sensor)
    val corrections = corrections.toSet()
    private val originalIcc = iccProfile?.copyOf()
    /** Opaque source profile only: preserved, never interpreted or attached to an sRGB export here. */
    fun iccProfile(): ByteArray? = originalIcc?.copyOf()
    val width: Int get() = legacyImage.width
    val height: Int get() = legacyImage.height
    val cropLeft: Int get() = legacyImage.cropLeft
    val cropTop: Int get() = legacyImage.cropTop
    val cropWidth: Int get() = legacyImage.cropWidth
    val cropHeight: Int get() = legacyImage.cropHeight
    val orientation: Int get() = legacyImage.orientation
    val whiteLevel: Int get() = legacyImage.whiteLevel
    val rowStrideSamples: Int get() = width * legacyImage.channels
    val layout = if (legacyImage.channels == 1) PixelLayout.RGGB_AT_CROP else PixelLayout.CAMERA_RGB_INTERLEAVED

    init {
        require(width > 0 && height > 0 && (legacyImage.channels == 1 || legacyImage.channels == 3)) { "Invalid RAW shape" }
        require(width.toLong() * height * legacyImage.channels == samples.size.toLong()) { "Invalid RAW sample count" }
        require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0 &&
            cropLeft.toLong() + cropWidth <= width && cropTop.toLong() + cropHeight <= height) { "Invalid RAW crop" }
        require(orientation in 1..8 && originalBitDepth in 1..16) { "Unsupported RAW metadata" }
        require(legacyImage.blackLevel >= 0 && legacyImage.blackLevel < whiteLevel && whiteLevel <= 32767) { "Invalid RAW levels" }
        require(legacyImage.whiteBalance.size == 3 && legacyImage.whiteBalance.all { it.isFinite() && it > 0f } &&
            legacyImage.colorMatrix.size == 9 && legacyImage.colorMatrix.all(Float::isFinite)) { "Invalid RAW calibration" }
        require(legacyImage.sensor.all { it >= 0 }) { "Samples exceed the legacy 0..32767 range" }
    }
}
