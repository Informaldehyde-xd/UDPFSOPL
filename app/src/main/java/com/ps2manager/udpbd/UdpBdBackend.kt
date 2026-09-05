package com.ps2manager.udpfsserver.udpbd

import java.io.RandomAccessFile

/** Backing store for the UDPBD server: a raw disk/partition image addressed
 *  in fixed-size sectors, matching what the PS2's BDM UDPBD driver expects. */
interface UdpBdBackend {
    val sectorSize: Int
    val sectorCount: Long
    fun readSectors(startSector: Long, count: Int): ByteArray
    fun writeSectors(startSector: Long, data: ByteArray)
    fun close()
}

/** Serves a plain .img file (raw FAT32/exFAT disk image, e.g. one created via
 *  the Termux dd/fallocate workflow) as the UDPBD block device. */
class FileUdpBdBackend(
    private val file: RandomAccessFile,
    override val sectorSize: Int = UdpBdConst.SECTOR_SIZE
) : UdpBdBackend {
    override val sectorCount: Long = file.length() / sectorSize

    @Synchronized
    override fun readSectors(startSector: Long, count: Int): ByteArray {
        val buf = ByteArray(count * sectorSize)
        file.seek(startSector * sectorSize)
        file.readFully(buf)
        return buf
    }

    @Synchronized
    override fun writeSectors(startSector: Long, data: ByteArray) {
        file.seek(startSector * sectorSize)
        file.write(data)
    }

    override fun close() {
        try {
            file.close()
        } catch (e: Exception) {
            // best-effort close
        }
    }
}
