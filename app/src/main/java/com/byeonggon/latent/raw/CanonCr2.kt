package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.api.DecodeFailure
import com.byeonggon.latent.raw.api.DecodeFailureReason
import com.byeonggon.latent.raw.api.DecodeRequest
import com.byeonggon.latent.raw.api.DecodedFrame
import com.byeonggon.latent.raw.api.RawFormat

/** Full-resolution 14-bit CR2 for independently measured EOS 7D calibration only. */
internal object CanonCr2 {
    // Measured from the CC0 7D colour-chart sample at raw.pixls.us (id 1183); see the CR2 report.
    private val EOS_7D_MATRIX = floatArrayOf(1.84922f,-0.70786f,-0.14136f,-0.27746f,2.09015f,-0.81270f,-0.08440f,-0.38730f,1.47170f)

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
        val sensor = CanonCr2Sensor.decode(file, metadata, request.checkCancelled)
        val image = ArwImage(metadata.width, metadata.height, metadata.crop[0], metadata.crop[1], metadata.crop[2], metadata.crop[3],
            2048, metadata.whiteLevel, metadata.whiteBalance, EOS_7D_MATRIX, 1f, null, metadata.orientation, sensor, cfaPattern=CfaPattern.GBRG)
        return DecodedFrame(image, RawFormat.CANON_CR2, 14, emptySet())
    }
}
