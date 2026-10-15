package org.owenewans.snolc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.owenewans.snolc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(SnolcVpnService.EXTRA_STATE)
            val error = intent?.getStringExtra(SnolcVpnService.EXTRA_ERROR)
            updateUi(state, error)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Требуется разрешение на запуск VPN!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Обработка входящей ссылки snolc://profile/...
        intent?.data?.let { uri ->
            handleImportUri(uri.toString())
        }

        updateUi()

        binding.btnConnect.setOnClickListener {
            if (SnolcVpnService.isRunning || SnolcVpnService.currentStatus == SnolcVpnService.STATE_CONNECTING) {
                stopVpnService()
            } else {
                prepareAndConnect()
            }
        }

        binding.btnImport.setOnClickListener {
            val url = binding.etProfileUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                handleImportUri(url)
            } else {
                Toast.makeText(this, "Введите ссылку snolc://", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleImportUri(uriString: String) {
        val profile = ConfigManager.parseProfileUri(uriString)
        if (profile != null) {
            ConfigManager.saveProfile(this, profile)
            binding.etProfileUrl.setText("")
            Toast.makeText(this, "Профиль успешно импортирован!", Toast.LENGTH_SHORT).show()
            updateUi()
        } else {
            Toast.makeText(this, "Неверный формат ссылки snolc://", Toast.LENGTH_LONG).show()
        }
    }

    private fun prepareAndConnect() {
        val profile = ConfigManager.loadProfile(this)
        if (profile == null) {
            Toast.makeText(this, "Сначала импортируйте профиль сервера!", Toast.LENGTH_LONG).show()
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, SnolcVpnService::class.java).apply {
            action = SnolcVpnService.ACTION_CONNECT
        }
        updateUi(SnolcVpnService.STATE_CONNECTING)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, SnolcVpnService::class.java).apply {
            action = SnolcVpnService.ACTION_DISCONNECT
        }
        ContextCompat.startForegroundService(this, intent)
        updateUi(SnolcVpnService.STATE_IDLE)
    }

    private fun updateUi(state: String? = null, error: String? = null) {
        val profile = ConfigManager.loadProfile(this)
        if (profile != null) {
            binding.tvServerInfo.text = "${profile.server} (Маскировка: ${profile.sni})"
        } else {
            binding.tvServerInfo.text = "Сервер не настроен"
        }

        val currentState = state ?: SnolcVpnService.currentStatus

        when (currentState) {
            SnolcVpnService.STATE_CONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                binding.btnConnect.text = getString(R.string.disconnect)
            }
            SnolcVpnService.STATE_CONNECTING -> {
                binding.tvStatus.text = "Подключение..."
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                binding.btnConnect.text = getString(R.string.disconnect)
            }
            SnolcVpnService.STATE_ERROR -> {
                val errText = error ?: SnolcVpnService.lastError ?: "Ошибка подключения"
                binding.tvStatus.text = "Ошибка: $errText"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                binding.btnConnect.text = getString(R.string.connect)
                Toast.makeText(this, errText, Toast.LENGTH_LONG).show()
            }
            else -> { // STATE_IDLE
                binding.tvStatus.text = getString(R.string.status_idle)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                binding.btnConnect.text = getString(R.string.connect)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SnolcVpnService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }
}
