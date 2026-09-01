/* Ported from https://github.com/pcm720/udpfsd (BSD-3-Clause), udpfs/protocol.go
 * and udpfs/fs.go message/errno definitions. */
package com.ps2manager.udpfsserver.udpfs

object UdpfsMsg {
    const val OPEN_REQ = 0x10
    const val OPEN_REPLY = 0x11
    const val CLOSE_REQ = 0x12
    const val CLOSE_REPLY = 0x13
    const val READ_REQ = 0x14
    const val WRITE_REQ = 0x16
    const val WRITE_DATA = 0x17
    const val WRITE_DONE = 0x18
    const val LSEEK_REQ = 0x1A
    const val LSEEK_REPLY = 0x1B
    const val DREAD_REQ = 0x1C
    const val DREAD_REPLY = 0x1D
    const val GETSTAT_REQ = 0x1E
    const val GETSTAT_REPLY = 0x1F
    const val MKDIR_REQ = 0x20
    const val REMOVE_REQ = 0x22
    const val RMDIR_REQ = 0x24
    const val RESULT_REPLY = 0x26
    const val BREAD_REQ = 0x28
    const val BWRITE_REQ = 0x2A
}

object UdpfsFlag {
    const val READ_ONLY = 0x01
    const val WRITE_ONLY = 0x02
    const val READ_WRITE = 0x03
    const val APPEND = 0x0100
    const val CREATE = 0x0200
    const val TRUNCATE = 0x0400
}

object FioMode {
    const val S_IFREG = 0x2000
    const val S_IFDIR = 0x1000
}

object Errno {
    const val EOK = 0
    const val ENOENT = 2
    const val EIO = 5
    const val EBADF = 9
    const val EACCES = 13
    const val EEXIST = 17
    const val ENODEV = 19
    const val EINVAL = 22
    const val EMFILE = 24
}

const val BLOCK_DEVICE_HANDLE = 0

/** UDPFS-level exception carrying a specific PS2 errno. Throw this from a
 *  UdpfsBackend implementation when you need a precise error code rather than
 *  a generic mapped one. */
class UdpfsErrno(val errno: Int) : Exception("UDPFS errno $errno")

/** Maps a thrown exception to a PS2 errno, mirroring errToErrno() in the reference server. */
fun errorToErrno(e: Throwable): Int = when (e) {
    is UdpfsErrno -> e.errno
    is java.nio.file.NoSuchFileException -> Errno.ENOENT
    is java.io.FileNotFoundException -> Errno.ENOENT
    is java.nio.file.FileAlreadyExistsException -> Errno.EEXIST
    is SecurityException -> Errno.EACCES
    else -> Errno.EIO
}

/** PS2-compatible stat structure sent in replies. */
data class StatInfo(
    val mode: Int = 0,
    val attr: Int = 0,
    val size: Int = 0,
    val hisize: Int = 0,
    val ctime: ByteArray = ByteArray(8),
    val atime: ByteArray = ByteArray(8),
    val mtime: ByteArray = ByteArray(8)
) {
    companion object {
        /** Converts epoch millis to PS2 iox_stat_t time: [0, sec, min, hour, day, month, year_lo, year_hi]. */
        fun encodeTime(epochMillis: Long): ByteArray {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = epochMillis
            val out = ByteArray(8)
            out[0] = 0
            out[1] = cal.get(java.util.Calendar.SECOND).toByte()
            out[2] = cal.get(java.util.Calendar.MINUTE).toByte()
            out[3] = cal.get(java.util.Calendar.HOUR_OF_DAY).toByte()
            out[4] = cal.get(java.util.Calendar.DAY_OF_MONTH).toByte()
            out[5] = (cal.get(java.util.Calendar.MONTH) + 1).toByte()
            val year = cal.get(java.util.Calendar.YEAR)
            out[6] = (year and 0xFF).toByte()
            out[7] = ((year shr 8) and 0xFF).toByte()
            return out
        }

        fun fromFile(isDir: Boolean, sizeBytes: Long, modifiedEpochMillis: Long): StatInfo {
            val mode = if (isDir) FioMode.S_IFDIR else FioMode.S_IFREG
            val size = (sizeBytes and 0xFFFFFFFFL).toInt()
            val hisize = ((sizeBytes ushr 32) and 0xFFFFFFFFL).toInt()
            val t = encodeTime(modifiedEpochMillis)
            return StatInfo(mode = mode, size = size, hisize = hisize, ctime = t, atime = t, mtime = t)
        }
    }
}