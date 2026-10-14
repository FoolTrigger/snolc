package org.owenewans.snolc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

class SnolcVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var nativeEngineHandle: Long = 0

    companion object {
        const val ACTION_CONNECT = "org.owenewans.snolc.CONNECT"
        const val ACTION_DISCONNECT = "org.owenewans.snolc.DISCONNECT"
        var isRunning = false

        init {
            try {
                System.loadLibrary("snolc_android")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
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
                    .addAddress("10.254.1.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setSession("snolc-stealth")

                vpnInterface = builder.establish()
                val tunFd = vpnInterface?.detachFd() ?: return@Thread

                val configPath = ConfigManager.prepareConfigFiles(this, profile, tunFd)

                isRunning = true
                nativeEngineHandle = startEngine(tunFd, configPath)
                isRunning = false
            } catch (e: Exception) {
                e.printStackTrace()
                stopVpn()
            }
        }.start()
    }

    // Вызывается из Rust JNI при создании сокета Carrier TCP
    // Защищает сокет от маршрутизации через VPN, исключая петлю трафика
    fun protectSocketFromVpn(socketFd: Int): Boolean {
        return protect(socketFd)
    }

    private fun stopVpn() {
        isRunning = false
        if (nativeEngineHandle != 0L) {
            stopEngine(nativeEngineHandle)
            nativeEngineHandle = 0
        }
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            e.printStackTrace()
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

        startForeground(1, notification)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
