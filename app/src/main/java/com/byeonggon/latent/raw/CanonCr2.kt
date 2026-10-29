package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.api.DecodeFailure
import com.byeonggon.latent.raw.api.DecodeFailureReason
import com.byeonggon.latent.raw.api.DecodeRequest
import com.byeonggon.latent.raw.api.DecodedFrame
import com.byeonggon.latent.raw.api.RawFormat

/** Full-resolution 14-bit CR2 with the EOS 7D's Adobe two-illuminant camera profile. */
internal object CanonCr2 {
    // XYZ-to-camera ColorMatrix1/2 from Adobe DNG Converter 18.7 (2743), -u -p0,
    // converting the CC0 raw.pixls.us EOS 7D files #131 and #1183. Both give these values.
    // See docs/canon-cr2-investigation.md for source/conversion hashes and limitations.
    private val EOS_7D_STANDARD_A = floatArrayOf(
        1.1620f, -0.6349f, 0.0005f,
        -0.2558f, 1.0146f, 0.2813f,
        0.0024f, 0.0858f, 0.6926f,
    )
    private val EOS_7D_D65 = floatArrayOf(
        0.6843f, -0.0996f, -0.0856f,
        -0.3876f, 1.1761f, 0.2396f,
        -0.0593f, 0.1772f, 0.6198f,
    )

    private fun colorMatrix(whiteBalance: FloatArray) = Dng.Colors(
        floats = { tag ->
            when (tag) {
                0xc621 -> EOS_7D_STANDARD_A
                0xc622 -> EOS_7D_D65
                0xc628 -> floatArrayOf(1f / whiteBalance[0], 1f, 1f / whiteBalance[2])
                // This CR2 reader does not extract per-unit CameraCalibration; keep
                // DNG's identity default rather than guessing calibration gains.
                else -> null
            }
        },
        ints = { tag ->
            when (tag) {
                0xc65a -> 17 // Standard A
                0xc65b -> 21 // D65
                else -> null
            }
        },
    ).matrix

    fun decode(file: ByteArray, request: DecodeRequest): DecodedFrame {
        request.checkCancelled()
        val (data, first) = TiffDirectory.open(file)
        val model = TiffDirectory.read(data, first).text(0x110)
        if (model != "Canon EOS 7D") throw DecodeFailure(DecodeFailureReason.UNSUPPORTED, "Canon camera calibration is not available")
        val metadata = CanonCr2Metadata.read(file)
        if (metadata.width < 8 || metadata.height < 8 || metadata.crop[2] < 8 || metadata.crop[3] < 8 ||
            metadata.width > 5360 || metadata.height > 3516) {
            throw DecodeFailure(DecodeFailureReason.RESOURCE_LIMIT, "Unsupported Canon raw dimensions")
        }
        request.checkSamples(metadata.width.toLong() * metadata.height)
        val matrix = colorMatrix(metadata.whiteBalance)
        val sensor = CanonCr2Sensor.decode(file, metadata, request.checkCancelled)
        val image = ArwImage(metadata.width, metadata.height, metadata.crop[0], metadata.crop[1], metadata.crop[2], metadata.crop[3],
            2048, metadata.whiteLevel, metadata.whiteBalance, matrix, 1f, null, metadata.orientation, sensor, cfaPattern=CfaPattern.GBRG)
        return DecodedFrame(image, RawFormat.CANON_CR2, 14, emptySet())
    }
}
