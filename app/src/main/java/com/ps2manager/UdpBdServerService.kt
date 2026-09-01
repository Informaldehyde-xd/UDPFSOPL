package com.ps2manager.udpfsserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ps2manager.udpfsserver.udpbd.FileUdpBdBackend
import com.ps2manager.udpfsserver.udpbd.UdpBdConst
import com.ps2manager.udpfsserver.udpbd.UdpBdSocketServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class UdpBdServerState { STOPPED, STARTING, RUNNING, ERROR }

data class UdpBdServerStatus(
    val state: UdpBdServerState = UdpBdServerState.STOPPED,
    val ipAddress: String? = null,
    val port: Int = 0,
    val imagePath: String = "",
    val sectorCount: Long = 0,
    val startTimeMillis: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val errorMessage: String? = null
)

class UdpBdServerService : Service() {

    companion object {
        private const val TAG = "UdpBdServerService"
        private val _status = MutableStateFlow(UdpBdServerStatus())
        val status = _status.asStateFlow()
        private const val NOTIF_ID = 3
        private const val CHANNEL_ID = "UDPBD_CHANNEL"
        private const val TICK_INTERVAL_MS = 5000L
    }

    private var server: UdpBdSocketServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()

            val imagePath = SettingsManager.getUdpBdImagePath(this)
            val bindIp = SettingsManager.getUdpBdBindIp(this)
            val verbose = SettingsManager.getUdpBdVerboseLogging(this)

            _status.value = UdpBdServerStatus(state = UdpBdServerState.STARTING, imagePath = imagePath)
            startForeground(NOTIF_ID, buildNotification(_status.value))
            acquireLocks()

            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                throw IllegalStateException(
                    "Image file not found: $imagePath (create it first, e.g. via the Termux .img guide)"
                )
            }

            val raf = RandomAccessFile(imageFile, "rw")
            val backend = FileUdpBdBackend(raf)
            val bindAddr = if (bindIp.isNotBlank()) InetAddress.getByName(bindIp) else null

            val udpBdServer = UdpBdSocketServer(
                backend = backend,
                bindAddress = bindAddr,
                verbose = verbose
            )
            udpBdServer.start()
            server = udpBdServer

            _status.value = _status.value.copy(
                state = UdpBdServerState.RUNNING,
                ipAddress = bindIp.ifBlank { NetworkUtils.getLocalIpAddress() },
                port = UdpBdConst.UDPBD_PORT,
                sectorCount = backend.sectorCount,
                startTimeMillis = System.currentTimeMillis()
            )
            startTicker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDPBD server", e)
            val errorStatus = _status.value.copy(
                state = UdpBdServerState.ERROR,
                errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"
            )
            _status.value = errorStatus
            showErrorNotification(errorStatus)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startTicker() {
        tickerRunnable = object : Runnable {
            override fun run() {
                val current = _status.value
                if (current.state == UdpBdServerState.RUNNING) {
                    val uptime = (System.currentTimeMillis() - current.startTimeMillis) / 1000
                    val updated = current.copy(uptimeSeconds = uptime)
                    _status.value = updated
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIF_ID, buildNotification(updated))
                }
                mainHandler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
        mainHandler.post(tickerRunnable!!)
    }

    private fun stopTicker() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    private fun showErrorNotification(errorStatus: UdpBdServerStatus) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(errorStatus))
    }

    private fun buildNotification(status: UdpBdServerStatus): android.app.Notification {
        val ipText = status.ipAddress ?: "unknown IP"
        val uptimeText = formatUptime(status.uptimeSeconds)
        val contentText = when (status.state) {
            UdpBdServerState.RUNNING -> "$ipText:${status.port} • ${status.sectorCount} sectors • Up $uptimeText"
            UdpBdServerState.STARTING -> "Starting server..."
            UdpBdServerState.ERROR -> "Error: ${status.errorMessage ?: "server stopped"}"
            UdpBdServerState.STOPPED -> "Server stopped"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UDPBD Server")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOnlyAlertOnce(true)
            .setOngoing(status.state == UdpBdServerState.RUNNING || status.state == UdpBdServerState.STARTING)
            .build()
    }

    private fun formatUptime(totalSeconds: Long): String {
        val h = TimeUnit.SECONDS.toHours(totalSeconds)
        val m = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UdpBdServer::WakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "UDPBD Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopTicker()
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping UDPBD server", e)
        }
        server = null
        wakeLock?.takeIf { it.isHeld }?.release()
        _status.value = _status.value.copy(state = UdpBdServerState.STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
