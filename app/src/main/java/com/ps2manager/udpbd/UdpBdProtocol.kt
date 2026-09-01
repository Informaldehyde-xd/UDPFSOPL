/* UDPBD v2 protocol, ported from https://github.com/israpps/udpbd-server
 * (udpbd.h) — the protocol designed by Rick Gaiser for PS2 BDM block-device
 * streaming, also used by OPL's UDPBD backend. All values are little-endian,
 * matching the PS2 IOP. */
package com.ps2manager.udpfsserver.udpbd

object UdpBdConst {
    const val UDPBD_PORT = 0xBDBD // 48573

    const val CMD_INFO = 0x00        // client -> server
    const val CMD_INFO_REPLY = 0x01  // server -> client
    const val CMD_READ = 0x02        // client -> server
    const val CMD_READ_RDMA = 0x03   // server -> client
    const val CMD_WRITE = 0x04       // client -> server
    const val CMD_WRITE_RDMA = 0x05  // client -> server
    const val CMD_WRITE_DONE = 0x06  // server -> client

    const val MAX_SECTOR_READ = 512  // 512 sectors * 512 bytes = 256KiB per request
    const val SECTOR_SIZE = 512

    const val HEADER_SIZE = 2
    const val RW_REQUEST_SIZE = HEADER_SIZE + 4 + 2  // hdr + sector_nr(u32) + sector_count(u16)
    const val INFO_REPLY_SIZE = HEADER_SIZE + 4 + 4  // hdr + sector_size(u32) + sector_count(u32)
    const val WRITE_DONE_SIZE = HEADER_SIZE + 4       // hdr + result(i32)
    const val BLOCK_TYPE_SIZE = 4

    const val UDP_MAX_PAYLOAD = 1472
    const val RDMA_MAX_PAYLOAD = UDP_MAX_PAYLOAD - HEADER_SIZE - BLOCK_TYPE_SIZE // 1466

    // Default RDMA framing: 128 bytes/block * 11 blocks = 1408B/packet (matches
    // reference server's default "Block size changed to 128" behavior).
    const val DEFAULT_BLOCK_SHIFT = 5
    const val DEFAULT_BLOCK_SIZE = 1 shl (DEFAULT_BLOCK_SHIFT + 2) // 128
    const val DEFAULT_MAX_BLOCKS_PER_PACKET = RDMA_MAX_PAYLOAD / DEFAULT_BLOCK_SIZE // 11
}

/** 2-byte header: 5-bit cmd, 3-bit cmdid, 8-bit cmdpkt (0=request, 1+=response seq). */
data class UdpBdHeader(val cmd: Int, val cmdId: Int, val cmdPkt: Int) {
    fun pack(b: ByteArray, offset: Int = 0) {
        val v = (cmd and 0x1F) or ((cmdId and 0x7) shl 5) or ((cmdPkt and 0xFF) shl 8)
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    companion object {
        fun unpack(b: ByteArray, offset: Int = 0): UdpBdHeader {
            val v = (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
            return UdpBdHeader(v and 0x1F, (v shr 5) and 0x7, (v shr 8) and 0xFF)
        }
    }
}

/** 4-byte RDMA block-type word: 4-bit block_shift, 9-bit block_count, 19-bit spare. */
data class UdpBdBlockType(val blockShift: Int, val blockCount: Int) {
    val blockSize: Int get() = 1 shl (blockShift + 2)

    fun pack(b: ByteArray, offset: Int = 0) {
        val v = (blockShift.toLong() and 0xF) or ((blockCount.toLong() and 0x1FF) shl 4)
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        b[offset + 2] = ((v shr 16) and 0xFF).toByte()
        b[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    companion object {
        fun unpack(b: ByteArray, offset: Int = 0): UdpBdBlockType {
            val v = (b[offset].toLong() and 0xFF) or
                ((b[offset + 1].toLong() and 0xFF) shl 8) or
                ((b[offset + 2].toLong() and 0xFF) shl 16) or
                ((b[offset + 3].toLong() and 0xFF) shl 24)
            return UdpBdBlockType((v and 0xF).toInt(), ((v shr 4) and 0x1FF).toInt())
        }
    }
}

fun putU32LE(b: ByteArray, offset: Int, value: Long) {
    b[offset] = (value and 0xFF).toByte()
    b[offset + 1] = ((value shr 8) and 0xFF).toByte()
    b[offset + 2] = ((value shr 16) and 0xFF).toByte()
    b[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

fun getU32LE(b: ByteArray, offset: Int): Long {
    return (b[offset].toLong() and 0xFF) or
        ((b[offset + 1].toLong() and 0xFF) shl 8) or
        ((b[offset + 2].toLong() and 0xFF) shl 16) or
        ((b[offset + 3].toLong() and 0xFF) shl 24)
}

fun putU16LE(b: ByteArray, offset: Int, value: Int) {
    b[offset] = (value and 0xFF).toByte()
    b[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

fun getU16LE(b: ByteArray, offset: Int): Int =
    (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

fun putI32LE(b: ByteArray, offset: Int, value: Int) =
    putU32LE(b, offset, value.toLong() and 0xFFFFFFFFL)
