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
        const val TAG = "SnolcVpnService"
        var isRunning = false

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val profile = ConfigManager.loadProfile(this)
                if (profile != null) {
                    startVpn(profile)
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn(profile: ServerProfile) {
        startForegroundNotification(profile.server)

        Thread {
            try {
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
                    Log.e(TAG, "builder.establish() returned null")
                    stopVpn()
                    return@Thread
                }
                vpnInterface = pfd
                val tunFd = pfd.fd
                Log.i(TAG, "VPN established, TUN FD = $tunFd")

                val configPath = ConfigManager.prepareConfigFiles(this, profile, tunFd)
                Log.i(TAG, "Prepared config at: $configPath")

                isRunning = true
                sendBroadcast(Intent(ACTION_STATUS_CHANGED))

                nativeEngineHandle = startEngine(tunFd, configPath)
                Log.i(TAG, "Engine finished, handle = $nativeEngineHandle")
            } catch (e: Exception) {
                Log.e(TAG, "Exception during VPN run", e)
            } finally {
                stopVpn()
                sendBroadcast(Intent(ACTION_STATUS_CHANGED))
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
