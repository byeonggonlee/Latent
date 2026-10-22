package com.byeonggon.latent.enhance

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * Ultra HDR JPEGs, as phones take them: a picture for ordinary screens with a gain map beside it, which brightens it on
 * an HDR screen. Editing works on the ordinary picture only, so an edited one is shown and saved without the gain map.
 */
object UltraHdr {
    private const val MARKER = 0xFF
    private const val START_OF_IMAGE = 0xD8
    private const val START_OF_SCAN = 0xDA
    private const val END_OF_IMAGE = 0xD9
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2

    /** Adobe's gain map namespace, which Ultra HDR's XMP declares. */
    private const val GAIN_MAP_XMP = "http://ns.adobe.com/hdr-gain-map/1.0/"

    /** ISO 21496-1's mark, in an APP2 segment of the picture with a gain map. */
    private const val ISO_GAIN_MAP = "urn:iso:std:iso:ts:21496:-1"

    /**
     * Whether the JPEG in [file] carries a gain map, as Ultra HDR's XMP or ISO 21496-1 describes it. Only the segments
     * before the picture data are read; a file that is no JPEG, or cannot be read, has none.
     */
    fun hasGainMap(file: File): Boolean = try {
        DataInputStream(file.inputStream().buffered()).use(::readsGainMap)
    } catch (e: EOFException) {
        false
    } catch (e: IOException) {
        false
    }

    private fun readsGainMap(input: DataInputStream): Boolean {
        if (input.readUnsignedByte() != MARKER || input.readUnsignedByte() != START_OF_IMAGE) return false
        var marker = START_OF_IMAGE
        while (marker != START_OF_SCAN && marker != END_OF_IMAGE) {
            if (input.readUnsignedByte() != MARKER) return false
            marker = input.readUnsignedByte()
            // Any number of fill bytes may stand before a marker.
            while (marker == MARKER) marker = input.readUnsignedByte()
            if (marker == START_OF_SCAN || marker == END_OF_IMAGE) continue
            val length = input.readUnsignedShort() - 2
            if (length < 0) return false
            if (marker != APP1 && marker != APP2) {
                input.skipNBytes(length.toLong())
                continue
            }
            val text = String(ByteArray(length).also(input::readFully), Charsets.ISO_8859_1)
            if (marker == APP1 && text.contains(GAIN_MAP_XMP)) return true
            if (marker == APP2 && text.startsWith(ISO_GAIN_MAP)) return true
        }
        return false
    }
}
