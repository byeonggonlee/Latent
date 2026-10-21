package com.byeonggon.latent.camera

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.byeonggon.latent.data.db.MediaKind
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
        Log.i(
            SYNC_LOG_TAG,
            "Opened ${info?.manufacturer} ${info?.model} (version ${info?.version}); partial transfers: " +
                "32-bit=${info?.isOperationSupported(MtpConstants.OPERATION_GET_PARTIAL_OBJECT)}, " +
                "64-bit=${info?.isOperationSupported(MtpConstants.OPERATION_GET_PARTIAL_OBJECT_64)}",
        )
        return CameraInfo(
            manufacturer = info?.manufacturer.orEmpty(),
            model = info?.model ?: usbDevice.productName.orEmpty(),
            serial = info?.serialNumber.orEmpty(),
        )
    }

    override fun listFiles(request: ListingRequest, onFound: (Int) -> Unit): Listing {
        val mtp = requireDevice()
        val files = ArrayList<RemoteFile>()
        val folders = HashMap<String, String>()
        val storageIds = mtp.storageIds ?: IntArray(0)
        if (storageIds.isEmpty()) Log.w(SYNC_LOG_TAG, "The camera reports no storage")
        for (storageId in storageIds) {
            val tree = object : RemoteTree {
                override fun childrenOf(parent: Int): IntArray? = mtp.getObjectHandles(storageId, ALL_FORMATS, parent)

                override fun objectOf(handle: Int): RemoteObject? = mtp.getObjectInfo(handle)?.toRemoteObject()
            }
            val storage = listStorage(tree, ROOT_PARENT, Integer.toHexString(storageId), request) { found ->
                onFound(files.size + found)
            }
            logStorage(mtp, storageId, storage)
            files += storage.files
            folders += storage.fingerprints
        }
        return Listing(files, folders)
    }

    override fun download(file: RemoteFile, destination: File): Boolean {
        val mode = ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or
            ParcelFileDescriptor.MODE_WRITE_ONLY
        return ParcelFileDescriptor.open(destination, mode).use { descriptor ->
            requireDevice().importFile(file.handle, descriptor)
        }
    }

    override fun readTail(file: RemoteFile, length: Int): ByteArray? {
        if (file.sizeBytes == RemoteFile.UNKNOWN_SIZE) return null
        val tail = ByteArray(minOf(length.toLong(), file.sizeBytes).toInt())
        return try {
            // A camera that cannot hand out part of a file says so with an error; the file is then compared no further.
            val read = requireDevice().getPartialObject(file.handle, file.sizeBytes - tail.size, tail.size.toLong(), tail)
            tail.takeIf { read == tail.size.toLong() }
        } catch (e: IOException) {
            Log.w(SYNC_LOG_TAG, "Cannot read the end of ${file.name}", e)
            null
        }
    }

    override fun close() {
        device?.close()
        device = null
    }

    private fun requireDevice() = device ?: throw IOException("The camera is not open")

    private fun MtpObjectInfo.toRemoteObject() = RemoteObject(
        name = name,
        isFolder = format == MtpConstants.FORMAT_ASSOCIATION,
        // Object info carries 32 bits, so files of 4 GB and more report an overflow marker instead of a size.
        sizeBytes = compressedSizeLong.takeIf { it != SIZE_OVERFLOW } ?: RemoteFile.UNKNOWN_SIZE,
        modifiedAt = dateCreated.takeIf { it > 0 } ?: dateModified,
    )

    private fun logStorage(mtp: MtpDevice, storageId: Int, storage: StorageListing) {
        val description = mtp.getStorageInfo(storageId)?.description.orEmpty()
        val kinds = MediaKind.entries.joinToString { kind -> "$kind=${storage.files.count { it.kind == kind }}" }
        val oversized = storage.files.count { it.sizeBytes == RemoteFile.UNKNOWN_SIZE }
        Log.i(
            SYNC_LOG_TAG,
            "Storage 0x${Integer.toHexString(storageId)} '$description': folders read=${storage.foldersRead}, " +
                "unchanged=${storage.foldersUnchanged}, outside dates=${storage.foldersOutsideDates}; " +
                "$kinds, of which 4 GB or larger=$oversized; skipped by extension: ${storage.skippedExtensions}",
        )
    }

    private companion object {
        const val ALL_FORMATS = 0
        const val ROOT_PARENT = -1 // 0xFFFFFFFF: objects directly under the storage root
        const val SIZE_OVERFLOW = 0xFFFFFFFFL
    }
}
