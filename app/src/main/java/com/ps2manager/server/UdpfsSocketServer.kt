/* Ported from https://github.com/pcm720/udpfsd (BSD-3-Clause):
 * internal/udpfsd/server.go, discovery.go, data.go.
 *
 * Normally: two UDP sockets — one bound to the well-known discovery port
 * that only answers DISCOVERY probes, and one bound to an OS-chosen
 * ephemeral port that handles the actual reliable data session — matching
 * the reference server exactly, since the client learns the data port from
 * the *source port* of the INFORM reply, which is deliberately sent from
 * the data socket.
 *
 * Modulo's UDPFS client does not follow this: it never moves off the
 * discovery port after receiving INFORM (and never restarts its sequence
 * counter across a server restart). This isn't specific to this server —
 * it fails against the reference udpfsd for the same reason. Modulo ships
 * its own patched server that instead runs discovery AND data over a single
 * port. When moduloMode is enabled below, this server does the same. */
package com.ps2manager.udpfsserver.server

import android.util.Log
import com.ps2manager.udpfsserver.udpfs.UdpfsBackend
import com.ps2manager.udpfsserver.udpfs.UdpfsConnection
import com.ps2manager.udpfsserver.udpfs.UdpfsHandlers
import com.ps2manager.udpfsserver.udprdma.RdmaHeader
import com.ps2manager.udpfsserver.udprdma.UdpRdmaConst
import com.ps2manager.udpfsserver.udprdma.UdpRdmaSession
import com.ps2manager.udpfsserver.udprdma.processDiscoveryPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UdpfsSocketServer(
    private val backend: UdpfsBackend,
    private val bindAddress: InetAddress?,
    private val discoveryPort: Int = UdpRdmaConst.UDPFS_PORT,
    private val verbose: Boolean = false,
    private val peerTimeoutMs: Long = TimeUnit.HOURS.toMillis(1),
    private val moduloMode: Boolean = false
) {
    companion object {
        private const val TAG = "UdpfsSocketServer"
        private const val PEER_CLEANUP_INTERVAL_MS = 30_000L
        private const val MAX_PACKET_SIZE = 2048
    }

    private class Peer(val connection: UdpfsConnection, val handlers: UdpfsHandlers, var lastSeenMs: Long)

    private var discSocket: DatagramSocket? = null
    private var dataSocket: DatagramSocket? = null
    private val peers = ConcurrentHashMap<InetSocketAddress, Peer>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false

    var dataPort: Int = 0
        private set

    fun start() {
        val disc = DatagramSocket(discoveryPort, bindAddress)
        discSocket = disc
        running = true

        if (moduloMode) {
            // Single-port mode: discovery AND data both ride the discovery
            // socket, since Modulo never moves to a separately-advertised
            // data port.
            dataSocket = disc
            dataPort = discoveryPort
            thread(name = "UdpfsModuloThread") { moduloLoop() }
            Log.i(TAG, "listening (Modulo single-port mode) on port $discoveryPort")
        } else {
            val data = DatagramSocket(0, bindAddress) // ephemeral port, OS-assigned
            dataSocket = data
            dataPort = data.localPort
            thread(name = "UdpfsDiscoveryThread") { discoveryLoop() }
            thread(name = "UdpfsDataThread") { dataLoop() }
            Log.i(TAG, "listening: discovery on port $discoveryPort, data on port $dataPort")
        }

        scheduler.scheduleWithFixedDelay(
            { cleanupPeers() }, PEER_CLEANUP_INTERVAL_MS, PEER_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        running = false
        discSocket?.close()
        if (!moduloMode) dataSocket?.close()
        scheduler.shutdownNow()
        for (p in peers.values) p.connection.close()
        peers.clear()
    }

    /** Spec-compliant discovery loop: only answers DISCOVERY probes, replying
     *  from the separate data socket so the client learns the data port. */
    private fun discoveryLoop() {
        val socket = discSocket ?: return
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                if (packet.length < 6) continue

                val reply = processDiscoveryPacket(packet.data, packet.length, UdpRdmaConst.SERVICE_UDPFS)
                if (reply == null) {
                    if (verbose) Log.d(TAG, "invalid/unrelated discovery packet from ${packet.address}:${packet.port}")
                    continue
                }
                val replyPacket = DatagramPacket(reply, reply.size, packet.address, packet.port)
                dataSocket?.send(replyPacket) // sent FROM the data socket on purpose
                Log.i(TAG, "[${packet.address}:${packet.port}]: discovery request received")
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "discovery socket error", e)
                return
            } catch (e: Exception) {
                if (verbose) Log.w(TAG, "discovery read error", e)
            }
        }
    }

    /** Spec-compliant data loop: runs on the separate ephemeral-port socket. */
    private fun dataLoop() {
        val socket = dataSocket ?: return
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                if (packet.length < 6) continue

                val addr = InetSocketAddress(packet.address, packet.port)
                val data = packet.data.copyOfRange(0, packet.length)
                handleData(socket, data, addr)
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "data socket error", e)
                return
            } catch (e: Exception) {
                if (verbose) Log.w(TAG, "data read error", e)
            }
        }
    }

    /** Modulo single-port loop: discovery AND data both arrive here, since
     *  Modulo's client never moves off the discovery port after INFORM. */
    private fun moduloLoop() {
        val socket = discSocket ?: return
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                if (packet.length < 6) continue

                val packetType = RdmaHeader.unpack(packet.data).packetType
                if (packetType == UdpRdmaConst.PACKET_DISCOVERY) {
                    val reply = processDiscoveryPacket(packet.data, packet.length, UdpRdmaConst.SERVICE_UDPFS)
                    if (reply == null) {
                        if (verbose) Log.d(TAG, "invalid/unrelated discovery packet from ${packet.address}:${packet.port}")
                        continue
                    }
                    val replyPacket = DatagramPacket(reply, reply.size, packet.address, packet.port)
                    socket.send(replyPacket) // same socket on purpose — Modulo never leaves this port
                    Log.i(TAG, "[${packet.address}:${packet.port}]: discovery request received (modulo mode)")
                } else {
                    val addr = InetSocketAddress(packet.address, packet.port)
                    val data = packet.data.copyOfRange(0, packet.length)
                    handleData(socket, data, addr)
                }
            } catch (e: SocketException) {
                if (running) Log.e(TAG, "modulo socket error", e)
                return
            } catch (e: Exception) {
                if (verbose) Log.w(TAG, "modulo read error", e)
            }
        }
    }

    private fun handleData(socket: DatagramSocket, data: ByteArray, addr: InetSocketAddress) {
        val peer = peers.getOrPut(addr) {
            Log.i(TAG, "[$addr]: creating new connection")
            val writeTo: (InetSocketAddress, ByteArray) -> Unit = { a, payload ->
                try {
                    socket.send(DatagramPacket(payload, payload.size, a.address, a.port))
                } catch (e: Exception) {
                    if (verbose) Log.w(TAG, "write error to $a", e)
                }
            }
            val session = UdpRdmaSession(addr, scheduler, writeTo)
            val connection = UdpfsConnection(addr, session, backend, verbose)
            Peer(connection, UdpfsHandlers(connection, backend), System.currentTimeMillis())
        }
        peer.lastSeenMs = System.currentTimeMillis()

        val payload = peer.connection.processIncoming(data)
        if (payload != null) {
            peer.handlers.handlePayload(payload)
        }
    }

    private fun cleanupPeers() {
        val now = System.currentTimeMillis()
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            val (addr, peer) = it.next()
            if (now - peer.lastSeenMs >= peerTimeoutMs) {
                Log.i(TAG, "peer $addr hasn't been seen in a while, removing")
                peer.connection.close()
                it.remove()
            }
        }
    }
}