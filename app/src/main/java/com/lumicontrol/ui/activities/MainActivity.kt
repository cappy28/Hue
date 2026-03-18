package com.lumicontrol.ui.activities

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lumicontrol.R
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var connectedGatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null

    private val HUE_SERVICE   = UUID.fromString("932c32bd-0000-47a2-835a-a8d455b859dd")
    private val HUE_POWER     = UUID.fromString("932c32bd-0002-47a2-835a-a8d455b859dd")
    private val HUE_BRIGHT    = UUID.fromString("932c32bd-0003-47a2-835a-a8d455b859dd")
    private val HUE_COLOR     = UUID.fromString("932c32bd-0005-47a2-835a-a8d455b859dd")

    private lateinit var tvStatus: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnScan).setOnClickListener { checkAndScan() }
        findViewById<Button>(R.id.btnOn).setOnClickListener { setPower(true) }
        findViewById<Button>(R.id.btnOff).setOnClickListener { setPower(false) }
    }

    private fun checkAndScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else startScan()
    }

    private fun startScan() {
        tvStatus.text = "Scan en cours..."
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = try { result.device.name } catch (e: SecurityException) { null }
                    if (name != null && name.contains("Hue", ignoreCase = true)) {
                        scanner.stopScan(this)
                        targetDevice = result.device
                        runOnUiThread {
                            tvStatus.text = "Ampoule trouvée : $name\nConnexion..."
                            connectToDevice(result.device)
                        }
                    }
                }
            })
            Handler(Looper.getMainLooper()).postDelayed({
                try { scanner.stopScan(object : ScanCallback() {}) } catch (e: Exception) {}
                if (targetDevice == null) runOnUiThread { tvStatus.text = "Aucune ampoule Hue trouvée" }
            }, 10000)
        } catch (e: SecurityException) {
            tvStatus.text = "Permission refusée"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            connectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        runOnUiThread { tvStatus.text = "✅ Connecté !" }
                        try { gatt.discoverServices() } catch (e: SecurityException) {}
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        runOnUiThread { tvStatus.text = "Déconnecté" }
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    runOnUiThread { tvStatus.text = "✅ Prêt à contrôler !" }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            tvStatus.text = "Erreur connexion"
        }
    }

    private fun setPower(on: Boolean) {
        val gatt = connectedGatt ?: run {
            Toast.makeText(this, "Pas connecté !", Toast.LENGTH_SHORT).show()
            return
        }
        val service = gatt.getService(HUE_SERVICE) ?: return
        val char = service.getCharacteristic(HUE_POWER) ?: return
        try {
            @Suppress("DEPRECATION")
            char.value = if (on) byteArrayOf(0x01) else byteArrayOf(0x00)
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
            tvStatus.text = if (on) "☀️ Allumée !" else "🌙 Éteinte !"
        } catch (e: SecurityException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { connectedGatt?.close() } catch (e: SecurityException) {}
    }
}

class ScanActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
