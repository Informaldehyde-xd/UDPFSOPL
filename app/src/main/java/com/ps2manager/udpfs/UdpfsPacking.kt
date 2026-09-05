/* Ported from https://github.com/pcm720/udpfsd (BSD-3-Clause), udpfs/utils.go
 * Pack* functions. Byte layouts confirmed exactly against the real source —
 * every multi-byte field is little-endian. */
package com.ps2manager.udpfsserver.udpfs

object UdpfsPacking {

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun putU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /** msg_type, 3 reserved, handle, mode, size, hisize, ctime[8], mtime[8] — 44 bytes total
     *  (matches the reference server's allocated size exactly, including its unused trailing padding). */
    fun packOpenReply(handle: Int, st: StatInfo): ByteArray {
        val b = ByteArray(44)
        b[0] = UdpfsMsg.OPEN_REPLY.toByte()
        putU32(b, 4, handle)
        putU32(b, 8, st.mode)
        putU32(b, 12, st.size)
        putU32(b, 16, st.hisize)
        System.arraycopy(st.ctime, 0, b, 20, 8)
        System.arraycopy(st.mtime, 0, b, 28, 8)
        return b
    }

    fun packCloseReply(result: Int): ByteArray {
        val b = ByteArray(8)
        b[0] = UdpfsMsg.CLOSE_REPLY.toByte()
        putU32(b, 4, result)
        return b
    }

    fun packResultReply(result: Int): ByteArray {
        val b = ByteArray(8)
        b[0] = UdpfsMsg.RESULT_REPLY.toByte()
        putU32(b, 4, result)
        return b
    }

    fun packWriteDone(result: Int): ByteArray {
        val b = ByteArray(8)
        b[0] = UdpfsMsg.WRITE_DONE.toByte()
        putU32(b, 4, result)
        return b
    }

    fun packLseekReply(position: Long): ByteArray {
        val b = ByteArray(12)
        b[0] = UdpfsMsg.LSEEK_REPLY.toByte()
        if (position < 0) {
            putU32(b, 4, position.toInt())
            putU32(b, 8, -1) // 0xFFFFFFFF
        } else {
            putU32(b, 4, (position and 0xFFFFFFFFL).toInt())
            putU32(b, 8, ((position ushr 32) and 0xFFFFFFFFL).toInt())
        }
        return b
    }

    /** 48-byte fixed header (msg_type, reserved, name_len(2), result, mode, attr, size, hisize,
     *  ctime[8], atime[8], mtime[8]) followed by the 4-byte-padded NUL-terminated name when result > 0. */
    fun packDreadReply(result: Int, name: String, st: StatInfo): ByteArray {
        var nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        if (nameBytes.isNotEmpty()) nameBytes += 0
        var nameLen = nameBytes.size
        if (nameLen > 0) nameLen--
        val paddedLen = (nameBytes.size + 3) and 3.inv()
        if (nameBytes.size < paddedLen) nameBytes = nameBytes.copyOf(paddedLen)

        val header = ByteArray(48)
        header[0] = UdpfsMsg.DREAD_REPLY.toByte()
        header[1] = 0
        putU16(header, 2, nameLen)
        putU32(header, 4, result)
        putU32(header, 8, st.mode)
        putU32(header, 12, st.attr)
        putU32(header, 16, st.size)
        putU32(header, 20, st.hisize)
        System.arraycopy(st.ctime, 0, header, 24, 8)
        System.arraycopy(st.atime, 0, header, 32, 8)
        System.arraycopy(st.mtime, 0, header, 40, 8)

        return if (result > 0) header + nameBytes else header
    }

    /** 48 bytes: msg_type, reserved, result, mode, attr, size, hisize, ctime[8], atime[8], mtime[8]. */
    fun packGetstatReply(result: Int, st: StatInfo): ByteArray {
        val b = ByteArray(48)
        b[0] = UdpfsMsg.GETSTAT_REPLY.toByte()
        putU32(b, 4, result)
        putU32(b, 8, st.mode)
        putU32(b, 12, st.attr)
        putU32(b, 16, st.size)
        putU32(b, 20, st.hisize)
        System.arraycopy(st.ctime, 0, b, 24, 8)
        System.arraycopy(st.atime, 0, b, 32, 8)
        System.arraycopy(st.mtime, 0, b, 40, 8)
        return b
    }
}