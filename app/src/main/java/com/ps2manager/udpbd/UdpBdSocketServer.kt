/* Ported from https://github.com/israpps/udpbd-server (udpbd.h protocol).
 * One UDP socket on the well-known port 0xBDBD (48573): INFO for discovery
 * (also answerable as a broadcast probe), READ/WRITE for block I/O, framed
 * as RDMA packets to match the PS2 IOP's expectations. Unlike UDPFS, UDPBD
 * has always been single-port -- there's no separate data socket to move to,
 * which is exactly what makes it work with clients (like Modulo) that can't
 * follow a port handoff. */
package com.ps2manager.udpfsserver.udpbd

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.math.min

class UdpBdSocketServer(
    private val backend: UdpBdBackend,
    private val bindAddress: InetAddress?,
    private val port: Int = UdpBdConst.UDPBD_PORT,
    private val verbose: Boolean = false
) {
    companion object {
        private const val TAG = "UdpBdSocketServer"
        private const val MAX_PACKET_SIZE = 2048
    }

    private class PendingWrite(
        val startSector: Long,
        val totalBytes: Int,
        val buffer: ByteArray,
        var received: Int,
        val cmdId: Int
    )

    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private val pendingWrites = HashMap<InetSocketAddress, PendingWrite>()

    fun start() {
        val s = DatagramSocket(port, bindAddress)
        socket = s
        running = true
        thread(name = "UdpBdThread") { loop() }
        Log.i(TAG, "listening on port $port (${backend.sectorCount} sectors of ${backend.sectorSize} bytes)")
    }

    fun stop() {
        running = false
        socket?.close()
        backend.close()
    }

    private fun loop() {
        val s = socket ?: return
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                if (packet.length < UdpBdConst.HEADER_SIZE) continue
                handlePacket(s, packet)
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "socket error", e)
                return
            } catch (e: Exception) {
                if (verbose) Log.w(TAG, "read error", e)
            }
        }
    }

    private fun handlePacket(socket: DatagramSocket, packet: DatagramPacket) {
        val data = packet.data
        val hdr = UdpBdHeader.unpack(data)
        val from = InetSocketAddress(packet.address, packet.port)

        when (hdr.cmd) {
            UdpBdConst.CMD_INFO -> handleInfo(socket, from, hdr)
            UdpBdConst.CMD_READ -> handleRead(socket, from, hdr, data, packet.length)
            UdpBdConst.CMD_WRITE -> handleWrite(from, hdr, data, packet.length)
            UdpBdConst.CMD_WRITE_RDMA -> handleWriteRdma(socket, from, data, packet.length)
            else -> if (verbose) Log.d(TAG, "unhandled cmd ${hdr.cmd} from $from")
        }
    }

    private fun handleInfo(socket: DatagramSocket, from: InetSocketAddress, hdr: UdpBdHeader) {
        val reply = ByteArray(UdpBdConst.INFO_REPLY_SIZE)
        UdpBdHeader(UdpBdConst.CMD_INFO_REPLY, hdr.cmdId, 1).pack(reply)
        putU32LE(reply, UdpBdConst.HEADER_SIZE, backend.sectorSize.toLong())
        putU32LE(reply, UdpBdConst.HEADER_SIZE + 4, backend.sectorCount)
        socket.send(DatagramPacket(reply, reply.size, from.address, from.port))
        Log.i(TAG, "[$from]: INFO -> sectorSize=${backend.sectorSize} sectorCount=${backend.sectorCount}")
    }

    private fun handleRead(socket: DatagramSocket, from: InetSocketAddress, hdr: UdpBdHeader, data: ByteArray, len: Int) {
        if (len < UdpBdConst.RW_REQUEST_SIZE) return
        val startSector = getU32LE(data, UdpBdConst.HEADER_SIZE)
        var count = getU16LE(data, UdpBdConst.HEADER_SIZE + 4)
        if (count <= 0) return
        if (count > UdpBdConst.MAX_SECTOR_READ) count = UdpBdConst.MAX_SECTOR_READ

        if (verbose) Log.d(TAG, "[$from]: READ sector=$startSector count=$count")

        val payload = try {
            backend.readSectors(startSector, count)
        } catch (e: Exception) {
            Log.e(TAG, "read failed at sector $startSector count $count", e)
            return
        }
        sendRdma(socket, from, hdr.cmdId, payload)
    }

    private fun sendRdma(socket: DatagramSocket, to: InetSocketAddress, cmdId: Int, payload: ByteArray) {
        val blockSize = UdpBdConst.DEFAULT_BLOCK_SIZE
        val maxBytesPerPacket = blockSize * UdpBdConst.DEFAULT_MAX_BLOCKS_PER_PACKET

        var offset = 0
        var pktSeq = 1
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val chunk = min(maxBytesPerPacket, remaining)
            val blocksInChunk = (chunk + blockSize - 1) / blockSize
            val paddedChunk = blocksInChunk * blockSize

            val packetBuf = ByteArray(UdpBdConst.HEADER_SIZE + UdpBdConst.BLOCK_TYPE_SIZE + paddedChunk)
            UdpBdHeader(UdpBdConst.CMD_READ_RDMA, cmdId, pktSeq).pack(packetBuf)
            UdpBdBlockType(UdpBdConst.DEFAULT_BLOCK_SHIFT, blocksInChunk).pack(packetBuf, UdpBdConst.HEADER_SIZE)
            System.arraycopy(payload, offset, packetBuf, UdpBdConst.HEADER_SIZE + UdpBdConst.BLOCK_TYPE_SIZE, chunk)

            socket.send(DatagramPacket(packetBuf, packetBuf.size, to.address, to.port))
            offset += chunk
            pktSeq++
        }
    }

    private fun handleWrite(from: InetSocketAddress, hdr: UdpBdHeader, data: ByteArray, len: Int) {
        if (len < UdpBdConst.RW_REQUEST_SIZE) return
        val startSector = getU32LE(data, UdpBdConst.HEADER_SIZE)
        val count = getU16LE(data, UdpBdConst.HEADER_SIZE + 4)
        if (count <= 0) return
        val totalBytes = count * backend.sectorSize
        pendingWrites[from] = PendingWrite(startSector, totalBytes, ByteArray(totalBytes), 0, hdr.cmdId)
        if (verbose) Log.d(TAG, "[$from]: WRITE sector=$startSector count=$count")
    }

    private fun handleWriteRdma(socket: DatagramSocket, from: InetSocketAddress, data: ByteArray, len: Int) {
        val pending = pendingWrites[from] ?: return
        if (len < UdpBdConst.HEADER_SIZE + UdpBdConst.BLOCK_TYPE_SIZE) return
        val bt = UdpBdBlockType.unpack(data, UdpBdConst.HEADER_SIZE)
        val chunkLen = min(bt.blockCount * bt.blockSize, pending.totalBytes - pending.received)
        if (chunkLen <= 0) return
        System.arraycopy(
            data, UdpBdConst.HEADER_SIZE + UdpBdConst.BLOCK_TYPE_SIZE,
            pending.buffer, pending.received, chunkLen
        )
        pending.received += chunkLen

        if (pending.received >= pending.totalBytes) {
            try {
                backend.writeSectors(pending.startSector, pending.buffer)
                sendWriteDone(socket, from, pending.cmdId, 0)
            } catch (e: Exception) {
                Log.e(TAG, "write failed at sector ${pending.startSector}", e)
                sendWriteDone(socket, from, pending.cmdId, -1)
            }
            pendingWrites.remove(from)
        }
    }

    private fun sendWriteDone(socket: DatagramSocket, to: InetSocketAddress, cmdId: Int, result: Int) {
        val reply = ByteArray(UdpBdConst.WRITE_DONE_SIZE)
        UdpBdHeader(UdpBdConst.CMD_WRITE_DONE, cmdId, 1).pack(reply)
        putI32LE(reply, UdpBdConst.HEADER_SIZE, result)
        socket.send(DatagramPacket(reply, reply.size, to.address, to.port))
    }
}
