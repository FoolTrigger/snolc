package org.owenewans.snolc

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.owenewans.snolc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
            if (SnolcVpnService.isRunning) {
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
        startService(intent)
        binding.tvStatus.text = getString(R.string.status_connected)
        binding.btnConnect.text = getString(R.string.disconnect)
    }

    private fun stopVpnService() {
        val intent = Intent(this, SnolcVpnService::class.java).apply {
            action = SnolcVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        binding.tvStatus.text = getString(R.string.status_idle)
        binding.btnConnect.text = getString(R.string.connect)
    }

    private fun updateUi() {
        val profile = ConfigManager.loadProfile(this)
        if (profile != null) {
            binding.tvServerInfo.text = "${profile.server} (Маскировка: ${profile.sni})"
        } else {
            binding.tvServerInfo.text = "Сервер не настроен"
        }

        if (SnolcVpnService.isRunning) {
            binding.tvStatus.text = getString(R.string.status_connected)
            binding.btnConnect.text = getString(R.string.disconnect)
        } else {
            binding.tvStatus.text = getString(R.string.status_idle)
            binding.btnConnect.text = getString(R.string.connect)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }
}
