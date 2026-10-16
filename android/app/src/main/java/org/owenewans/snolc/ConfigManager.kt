package org.owenewans.snolc

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.net.InetAddress

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
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    fun parseProfileUri(uriString: String): ServerProfile? {
        return try {
            val clean = uriString.trim()
            val uri = Uri.parse(clean)
            if (uri.scheme != "snolc") return null
            val host = uri.host ?: uri.authority ?: ""
            if (!host.startsWith("profile")) return null

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

    fun prepareConfigFiles(
        context: Context,
        profile: ServerProfile,
        tunFd: Int,
        resolvedIp: String? = null,
        resolvedPort: String? = null
    ): String {
        val filesDir = context.filesDir
        val packagesDir = File(filesDir, "packages").apply { mkdirs() }
        val stateDir = File(filesDir, "state").apply { mkdirs() }

        // 1. Noise public key
        val noiseKeyFile = File(filesDir, "noise_public.hex")
        noiseKeyFile.writeText(profile.presharedKey.trim())

        // 2. Adapter TUN
        val tunToml = File(filesDir, "tun.toml")
        tunToml.writeText(
            """
            wire_version = 1
            instance = "tun-main"
            package = "owenewans/adapter-tun@0.0.4"
            role = "client"

            [options]
            mode = "android-fd"
            fd = $tunFd
            mtu = 1280
            packet_queue_bytes = 262144
            """.trimIndent()
        )

        // 3. Carrier TCP (resolve host to IP if needed)
        val serverHost = profile.server.substringBefore(":")
        val serverPort = resolvedPort ?: if (profile.server.contains(":")) profile.server.substringAfter(":") else "443"
        val endpointIp = resolvedIp ?: try {
            InetAddress.getByName(serverHost).hostAddress ?: serverHost
        } catch (e: Exception) {
            serverHost
        }
        val endpoint = "$endpointIp:$serverPort"

        val tcpToml = File(filesDir, "tcp.toml")
        tcpToml.writeText(
            """
            wire_version = 1
            instance = "tcp-main"
            package = "owenewans/carrier-tcp@0.0.4"
            role = "client"

            [options]
            mode = "connect"
            endpoint_ip = "$endpoint"
            max_connections = 2
            nodelay = true
            """.trimIndent()
        )

        // 4. Protection Noise
        val noiseToml = File(filesDir, "noise.toml")
        noiseToml.writeText(
            """
            wire_version = 1
            instance = "noise-main"
            package = "owenewans/protection-noise@0.0.4"
            role = "client"

            [options]
            mode = "client"
            server_public_key_file = "${noiseKeyFile.absolutePath}"
            """.trimIndent()
        )

        // 5. Policy Local
        val policyDir = File(filesDir, "policy").apply { mkdirs() }
        val storageFile = File(policyDir, "policy-client.redb")
        val policyToml = File(filesDir, "policy.toml")
        policyToml.writeText(
            """
            wire_version = 1
            instance = "policy-main"
            package = "owenewans/policy-local@0.0.4"
            role = "client"

            [options]
            server_id = "node-1"
            credential_transport = "protected"
            max_cached_users = 256
            max_admin_clients = 16
            max_control_frame_bytes = 16384
            status_interval_ms = 1000
            control_grace_ms = 10000
            sniff_bytes = 16384
            sniff_timeout_ms = 2000
            on_unknown_protocol = "allow"
            checkpoint_interval_ms = 5000

            [options.storage]
            path = "${storageFile.absolutePath}"
            cache_bytes = 4194304
            max_database_bytes = 134217728
            queue_capacity = 64
            accounting_block_bytes = 1048576
            batch_max_delay_ms = 5

            [options.global_rate]
            mode = "unlimited"

            [options.rules]
            terminal = "allow"
            entries = []

            [options.client.credential]
            source = "toml"
            value = "${profile.bearerToken.trim()}"
            """.trimIndent()
        )

        // 6. snolc.toml
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val snolcToml = File(filesDir, "snolc.toml")
        snolcToml.writeText(
            """
            wire_version = 1

            [paths]
            packages = "$nativeLibDir"
            state = "${stateDir.absolutePath}"

            [engine]
            max_sessions = 2
            max_flows = 256
            max_pending_sessions = 2
            max_pending_opens = 64
            max_managed_bytes = 134217728
            max_commands = 64
            max_events = 256
            max_io_chunk = 16384
            max_ingress_packets_per_tick = 32
            connect_timeout_ms = 15000
            handshake_timeout_ms = 15000
            shutdown_timeout_ms = 5000

            [stack]
            ipv4 = true
            ipv6 = false
            mtu = 1280
            tcp_socket_rx_bytes = 16384
            tcp_socket_tx_bytes = 16384
            udp_socket_rx_bytes = 131072
            udp_socket_tx_bytes = 131072
            udp_metadata_slots = 32
            packet_queue_bytes = 262144
            max_udp_payload_bytes = 65507
            reassembly_slots = 4
            reassembly_timeout_ms = 15000

            [yamux]
            max_streams_per_session = 128
            receive_window_bytes = 33554432
            split_send_size = 16384
            read_after_close = true

            [logging]
            mode = "off"

            [control]
            mode = "off"

            [[tunnels]]
            name = "main"
            role = "client"
            adapters = ["${tunToml.absolutePath}"]
            carrier = "${tcpToml.absolutePath}"
            protection = "${noiseToml.absolutePath}"
            policy = "${policyToml.absolutePath}"
            """.trimIndent()
        )

        return snolcToml.absolutePath
    }
}
