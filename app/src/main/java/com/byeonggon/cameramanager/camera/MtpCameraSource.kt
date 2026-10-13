package com.byeonggon.cameramanager.camera

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.ParcelFileDescriptor
import com.byeonggon.cameramanager.data.db.MediaKind
import java.io.File
import java.io.IOException

class MtpCameraSource(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
) : CameraSource {
    private var device: MtpDevice? = null

    override fun open(): CameraInfo {
        val connection = usbManager.openDevice(usbDevice) ?: throw IOException("Cannot open the USB device")
        val mtp = MtpDevice(usbDevice)
        if (!mtp.open(connection)) {
            connection.close()
            throw IOException("The camera did not accept an MTP session")
        }
        device = mtp
        val info = mtp.deviceInfo
        return CameraInfo(
            manufacturer = info?.manufacturer.orEmpty(),
            model = info?.model ?: usbDevice.productName.orEmpty(),
            serial = info?.serialNumber.orEmpty(),
        )
    }

    override fun listFiles(onFound: (Int) -> Unit): List<RemoteFile> {
        val mtp = requireDevice()
        val files = ArrayList<RemoteFile>()
        for (storageId in mtp.storageIds ?: IntArray(0)) {
            val pendingFolders = ArrayDeque<Int>().apply { add(ROOT_PARENT) }
            while (pendingFolders.isNotEmpty()) {
                val parent = pendingFolders.removeLast()
                for (handle in mtp.getObjectHandles(storageId, ALL_FORMATS, parent) ?: IntArray(0)) {
                    val info = mtp.getObjectInfo(handle) ?: continue
                    if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                        pendingFolders.add(handle)
                        continue
                    }
                    val name = info.name ?: continue
                    val kind = MediaKind.fromFileName(name) ?: continue
                    files += RemoteFile(
                        handle = handle,
                        name = name,
                        sizeBytes = sizeOf(info),
                        modifiedAt = info.dateCreated.takeIf { it > 0 } ?: info.dateModified,
                        kind = kind,
                    )
                    onFound(files.size)
                }
            }
        }
        return files
    }

    override fun download(file: RemoteFile, destination: File): Boolean {
        val mode = ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or
            ParcelFileDescriptor.MODE_WRITE_ONLY
        return ParcelFileDescriptor.open(destination, mode).use { descriptor ->
            requireDevice().importFile(file.handle, descriptor)
        }
    }

    override fun close() {
        device?.close()
        device = null
    }

    private fun requireDevice() = device ?: throw IOException("The camera is not open")

    // Object info carries 32 bits, so files of 4 GB and more report an overflow marker instead of a size.
    private fun sizeOf(info: MtpObjectInfo): Long =
        info.compressedSizeLong.takeIf { it != SIZE_OVERFLOW } ?: RemoteFile.UNKNOWN_SIZE

    private companion object {
        const val ALL_FORMATS = 0
        const val ROOT_PARENT = -1 // 0xFFFFFFFF: objects directly under the storage root
        const val SIZE_OVERFLOW = 0xFFFFFFFFL
    }
}
