package org.owenewans.snolc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

class SnolcVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var nativeEngineHandle: Long = 0

    companion object {
        const val ACTION_CONNECT = "org.owenewans.snolc.CONNECT"
        const val ACTION_DISCONNECT = "org.owenewans.snolc.DISCONNECT"
        const val ACTION_STATUS_CHANGED = "org.owenewans.snolc.STATUS_CHANGED"
        const val EXTRA_STATE = "extra_state"
        const val EXTRA_ERROR = "extra_error"

        const val STATE_IDLE = "IDLE"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_ERROR = "ERROR"

        const val TAG = "SnolcVpnService"
        var isRunning = false
        var currentStatus = STATE_IDLE
        var lastError: String? = null

        init {
            val libs = listOf(
                "snolc_protection_noise",
                "snolc_carrier_tcp",
                "snolc_policy_local",
                "snolc_adapter_tun",
                "snolc",
                "snolc_android"
            )
            for (lib in libs) {
                try {
                    System.loadLibrary(lib)
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load library: $lib", e)
                }
            }
        }
    }

    private external fun startEngine(tunFd: Int, configPath: String): Long
    private external fun stopEngine(handle: Long)
    private external fun getLastEngineError(): String?

    private fun broadcastStatus(state: String, error: String? = null) {
        currentStatus = state
        if (error != null) {
            lastError = error
        }
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            if (error != null) {
                putExtra(EXTRA_ERROR, error)
            }
        }
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                broadcastStatus(STATE_IDLE)
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val profile = ConfigManager.loadProfile(this)
                if (profile != null) {
                    startVpn(profile)
                } else {
                    broadcastStatus(STATE_ERROR, "Профиль не найден")
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn(profile: ServerProfile) {
        broadcastStatus(STATE_CONNECTING)
        startForegroundNotification(profile.server)

        Thread {
            try {
                // 1. Предварительное разрешение DNS сервера ДО захвата маршрута TUN интерфейсом
                val serverHost = profile.server.substringBefore(":")
                val serverPort = if (profile.server.contains(":")) profile.server.substringAfter(":") else "443"
                val resolvedIp = try {
                    java.net.InetAddress.getByName(serverHost).hostAddress ?: serverHost
                } catch (e: Exception) {
                    Log.w(TAG, "DNS resolution failed, falling back to raw host: ${e.message}")
                    serverHost
                }
                Log.i(TAG, "Target server resolved to: $resolvedIp:$serverPort")

                val builder = Builder()
                    .setMtu(1280)
                    .addAddress("10.254.1.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setSession("snolc-stealth")

                try {
                    builder.addAddress("fd00::2", 64)
                    builder.addRoute("::", 0)
                    builder.addDnsServer("2606:4700:4700::1111")
                } catch (e: Exception) {
                    Log.w(TAG, "IPv6 not supported: ${e.message}")
                }

                val pfd = builder.establish() ?: run {
                    val err = "VpnService.Builder.establish() вернул null"
                    Log.e(TAG, err)
                    broadcastStatus(STATE_ERROR, err)
                    stopVpn()
                    return@Thread
                }
                vpnInterface = pfd
                val dupPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                val tunFd = dupPfd?.detachFd() ?: pfd.fd
                Log.i(TAG, "VPN established, TUN FD = $tunFd (base pfd = ${pfd.fd})")

                val configPath = ConfigManager.prepareConfigFiles(this, profile, tunFd, resolvedIp, serverPort)
                Log.i(TAG, "Prepared config at: $configPath")

                isRunning = true
                broadcastStatus(STATE_CONNECTED)

                val exitCode = startEngine(tunFd, configPath)
                Log.i(TAG, "Engine finished with code: $exitCode")

                if (exitCode != 0L) {
                    val nativeErr = getLastEngineError() ?: "Движок snolc завершился с кодом $exitCode"
                    Log.e(TAG, "Native engine failure: $nativeErr")
                    broadcastStatus(STATE_ERROR, nativeErr)
                } else {
                    broadcastStatus(STATE_IDLE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during VPN run", e)
                val err = e.localizedMessage ?: e.message ?: "Ошибка VPN соединения"
                broadcastStatus(STATE_ERROR, err)
            } finally {
                stopVpn()
                if (currentStatus != STATE_ERROR) {
                    broadcastStatus(STATE_IDLE)
                }
            }
        }.start()
    }

    // Вызывается из Rust JNI при создании сокета Carrier TCP
    // Защищает сокет от маршрутизации через VPN, исключая петлю трафика
    fun protectSocketFromVpn(socketFd: Int): Boolean {
        val result = protect(socketFd)
        Log.i(TAG, "protect(fd=$socketFd) -> $result")
        return result
    }

    private fun stopVpn() {
        isRunning = false
        if (nativeEngineHandle != 0L) {
            stopEngine(nativeEngineHandle)
            nativeEngineHandle = 0
        } else {
            stopEngine(0)
        }
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing vpnInterface", e)
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification(server: String) {
        val channelId = "snolc_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "snolc Status", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("snolc REALITY Защищен")
            .setContentText("Сервер: $server (Трафик замаскирован под Google)")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
