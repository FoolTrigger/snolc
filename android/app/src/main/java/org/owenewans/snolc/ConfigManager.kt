package org.owenewans.snolc

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

data class ServerProfile(
    val server: String,
    val sni: String,
    val presharedKey: String,
    val shortId: String,
    val userId: String,
    val bearerToken: String
)

object ConfigManager {

    private const val PREFS_FILE = "snolc_secure_prefs"

    private fun getPrefs(context: Context) = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    fun parseProfileUri(uriString: String): ServerProfile? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "snolc" || uri.host != "profile") return null

            val server = uri.getQueryParameter("server") ?: return null
            val sni = uri.getQueryParameter("sni") ?: "dl.google.com"
            val key = uri.getQueryParameter("key") ?: return null
            val shortId = uri.getQueryParameter("short_id") ?: ""
            val user = uri.getQueryParameter("user") ?: ""
            val token = uri.getQueryParameter("token") ?: ""

            ServerProfile(server, sni, key, shortId, user, token)
        } catch (e: Exception) {
            null
        }
    }

    fun saveProfile(context: Context, profile: ServerProfile) {
        getPrefs(context).edit().apply {
            putString("server", profile.server)
            putString("sni", profile.sni)
            putString("key", profile.presharedKey)
            putString("short_id", profile.shortId)
            putString("user", profile.userId)
            putString("token", profile.bearerToken)
            apply()
        }
    }

    fun loadProfile(context: Context): ServerProfile? {
        val prefs = getPrefs(context)
        val server = prefs.getString("server", null) ?: return null
        val sni = prefs.getString("sni", "dl.google.com") ?: "dl.google.com"
        val key = prefs.getString("key", "") ?: ""
        val shortId = prefs.getString("short_id", "") ?: ""
        val user = prefs.getString("user", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        return ServerProfile(server, sni, key, shortId, user, token)
    }

    fun generateClientToml(profile: ServerProfile): String {
        return """
            wire_version = 1

            [paths]
            packages = "packages"
            state = "state"

            [engine]
            max_sessions = 2
            max_flows = 64
            max_pending_sessions = 2
            max_pending_opens = 8
            max_managed_bytes = 16777216
            max_commands = 64
            max_events = 256
            max_io_chunk = 16384
            max_ingress_packets_per_tick = 32
            connect_timeout_ms = 15000
            handshake_timeout_ms = 15000
            shutdown_timeout_ms = 5000

            [stack]
            ipv4 = true
            ipv6 = true
            mtu = 1280
            tcp_socket_rx_bytes = 16384
            tcp_socket_tx_bytes = 16384
            udp_socket_rx_bytes = 65536
            udp_socket_tx_bytes = 65536
            udp_metadata_slots = 8
            packet_queue_bytes = 262144
            max_udp_payload_bytes = 65507
            reassembly_slots = 4
            reassembly_timeout_ms = 15000

            [yamux]
            max_streams_per_session = 64
            receive_window_bytes = 2097152
            split_send_size = 16384
            read_after_close = true

            [[tunnels]]
            name = "client-main"
            role = "client"
            server = "${profile.server}"
            sni = "${profile.sni}"
            preshared_key = "${profile.presharedKey}"
            short_id = "${profile.shortId}"
        """.trimIndent()
    }
}
