package com.byeonggon.latent.location

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Builds the XMP sidecar that carries GPS data for RAW files, which cannot be rewritten safely in place. */
object XmpSidecar {
    private const val BYTE_ORDER_MARK = "\uFEFF"

    fun build(point: GeoPoint): String {
        val attributes = buildList {
            add("""exif:GPSVersionID="2.3.0.0"""")
            add("""exif:GPSLatitude="${coordinate(point.latitude, 'N', 'S')}"""")
            add("""exif:GPSLongitude="${coordinate(point.longitude, 'E', 'W')}"""")
            point.altitude?.let {
                add("""exif:GPSAltitudeRef="${if (it < 0) 1 else 0}"""")
                add("""exif:GPSAltitude="${(abs(it) * 10).roundToLong()}/10"""")
            }
        }
        return listOf(
            """<?xpacket begin="$BYTE_ORDER_MARK" id="W5M0MpCehiHzreSzNTczkc9d"?>""",
            """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Latent">""",
            """ <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""",
            """  <rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/"""",
            attributes.joinToString("\n") { "    $it" } + "/>",
            """ </rdf:RDF>""",
            """</x:xmpmeta>""",
            """<?xpacket end="w"?>""",
            "",
        ).joinToString("\n")
    }

    /** XMP writes coordinates as degrees and decimal minutes: "37,33.990000N". */
    internal fun coordinate(value: Double, positive: Char, negative: Char): String {
        val microMinutes = (abs(value) * 60 * 1_000_000).roundToLong()
        val degrees = microMinutes / 60_000_000
        val minutes = (microMinutes % 60_000_000) / 1_000_000.0
        return String.format(Locale.US, "%d,%.6f%c", degrees, minutes, if (value < 0) negative else positive)
    }
}
