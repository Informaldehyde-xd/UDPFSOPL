/* Filesystem abstraction the protocol layer calls into — mirrors the FS
 * interface in fs.go. Implementations throw UdpfsErrno(code) for protocol-level
 * errors, or a normal exception (mapped via errorToErrno) for I/O failures. */
package com.ps2manager.udpfsserver.udpfs

interface UdpfsBackend {
    fun open(path: String, flags: Int, isDir: Boolean): OpenResult
    fun close(handle: Int)
    fun read(handle: Int, size: Int, readBuffer: ByteArray): ReadResult
    fun writeStart(handle: Int)
    fun writeChunk(handle: Int, chunkNr: Int, chunkSize: Int, totalChunks: Int, chunk: ByteArray): Boolean
    fun completeWrite(handle: Int): Int
    fun lseek(handle: Int, offset: Long, whence: Int): Long
    fun dread(handle: Int): DreadEntry?
    fun getstat(path: String): StatInfo
    fun mkdir(path: String)
    fun remove(path: String)
    fun rmdir(path: String)
    fun bread(handle: Int, sectorNr: Long, sectorCount: Int, readBuffer: ByteArray): ByteArray
    fun bwriteStart(handle: Int, sectorNr: Long, sectorCount: Int)
}

data class OpenResult(val handle: Int, val stat: StatInfo)
data class ReadResult(val n: Int, val data: ByteArray)
data class DreadEntry(val name: String, val stat: StatInfo)