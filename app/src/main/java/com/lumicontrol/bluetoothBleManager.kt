package com.lumicontrol.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lumicontrol.models.Bulb
import com.lumicontrol.models.BulbType
import com.lumicontrol.models.ConnectionState
import com.lumicontrol.models.LightEffect
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class BleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_PERIOD_MS = 10_000L

        // UUIDs Philips Hue Bluetooth
        val HUE_SERVICE_UUID = UUID.fromString("932c32bd-0000-47a2-835a-a8d455b859dd")
        val HUE_POWER_UUID   = UUID.fromString("932c32bd-0002-47a2-835a-a8d455b859dd")
        val HUE_BRIGHT_UUID  = UUID.fromString("932c32bd-0003-47a2-835a-a8d455b859dd")
        val HUE_COLOR_UUID   = UUID.fromString("932c32bd-0005-47a2-835a-a8d455b859dd")

        @Volatile private var INSTANCE: BleManager? = null
        fun getInstance(context: Context): BleManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private val gattConnections = mutableMapOf<String, BluetoothGatt>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scannedDevices = mutableListOf<Bulb>()

    private val _scanResults = MutableStateFlow<List<Bulb>>(emptyList())
    val scanResults: StateFlow<List<Bulb>> = _scanResults

    private val _connectedBulbs = MutableStateFlow<List<Bulb>>(emptyList())
    val connectedBulbs: StateFlow<List<Bulb>> = _connectedBulbs

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    val bleEnabled: StateFlow<Boolean> get() = MutableStateFlow(bluetoothAdapter?.isEnabled == true)

    // ════════════════════════════
    // SCAN
    // ════════════════════════════

    fun startScan() {
        if (_isScanning.value) return
        if (bluetoothAdapter?.isEnabled != true) return
        scannedDevices.clear()
        _isScanning.value = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner?.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            _isScanning.value = false
            return
        }
        mainHandler.postDelayed({ stopScan() }, SCAN_PERIOD_MS)
    }

    fun stopScan() {
        if (!_isScanning.value) return
        try { bleScanner?.stopScan(scanCallback) } catch (e: SecurityException) {}
        _isScanning.value = false
        _scanResults.value = scannedDevices.toList()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try { device.name } catch (e: SecurityException) { null }
            if (scannedDevices.none { it.macAddress == device.address }) {
                val bulb = Bulb(
                    macAddress = device.address,
                    name = name ?: "Ampoule ${device.address.takeLast(5)}",
                    type = BulbType.GENERIC
                )
                scannedDevices.add(bulb)
                _scanResults.value = scannedDevices.toList()
            }
        }
        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    // ════════════════════════════
    // CONNEXION
    // ════════════════════════════

    fun connect(bulb: Bulb, onResult: (Boolean) -> Unit = {}) {
        if (gattConnections.containsKey(bulb.macAddress)) { onResult(true); return }
        updateBulbState(bulb.macAddress, ConnectionState.CONNECTING)
        val device = try {
            bluetoothAdapter?.getRemoteDevice(bulb.macAddress)
        } catch (e: SecurityException) { onResult(false); return } ?: run { onResult(false); return }
        try {
            val gatt = device.connectGatt(context, false, createGattCallback(bulb, onResult), BluetoothDevice.TRANSPORT_LE)
            gattConnections[bulb.macAddress] = gatt
        } catch (e: SecurityException) { onResult(false) }
    }

    fun disconnect(macAddress: String) {
        gattConnections[macAddress]?.let { gatt ->
            try { gatt.disconnect(); gatt.close() } catch (e: SecurityException) {}
            gattConnections.remove(macAddress)
        }
        updateBulbState(macAddress, ConnectionState.DISCONNECTED)
    }

    fun disconnectAll() = gattConnections.keys.toList().forEach { disconnect(it) }

    // ════════════════════════════
    // COMMANDES HUE
    // ════════════════════════════

    fun setPower(macAddress: String, on: Boolean) {
        val gatt = gattConnections[macAddress] ?: return
        val service = gatt.getService(HUE_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(HUE_POWER_UUID) ?: return
        try {
            @Suppress("DEPRECATION")
            char.value = if (on) byteArrayOf(0x01) else byteArrayOf(0x00)
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) {}
        Log.d(TAG, "Power ${if (on) "ON" else "OFF"} → $macAddress")
    }

    fun setColor(macAddress: String, r: Int, g: Int, b: Int) {
        val gatt = gattConnections[macAddress] ?: return
        val service = gatt.getService(HUE_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(HUE_COLOR_UUID) ?: return
        val (x, y) = rgbToXY(r, g, b)
        val xInt = (x * 65535).toInt()
        val yInt = (y * 65535).toInt()
        val data = byteArrayOf(
            (xInt and 0xFF).toByte(),
            ((xInt shr 8) and 0xFF).toByte(),
            (yInt and 0xFF).toByte(),
            ((yInt shr 8) and 0xFF).toByte()
        )
        try {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) {}
        Log.d(TAG, "Couleur ($r,$g,$b) → $macAddress")
    }

    fun setBrightness(macAddress: String, brightness: Int) {
        val gatt = gattConnections[macAddress] ?: return
        val service = gatt.getService(HUE_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(HUE_BRIGHT_UUID) ?: return
        val bVal = (brightness * 254 / 100).coerceIn(1, 254)
        try {
            @Suppress("DEPRECATION")
            char.value = byteArrayOf(bVal.toByte())
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) {}
        Log.d(TAG, "Luminosité $brightness% → $macAddress")
    }

    fun broadcastCommand(r: Int, g: Int, b: Int, brightness: Int, on: Boolean) {
        gattConnections.keys.toList().forEach { mac ->
            setPower(mac, on)
            if (on) { setColor(mac, r, g, b); setBrightness(mac, brightness) }
        }
    }

    fun startEffect(macAddress: String, effect: LightEffect, speedMs: Long = 500) {
        scope.launch {
            when (effect) {
                LightEffect.FADE        -> fadeEffect(macAddress, speedMs)
                LightEffect.STROBE      -> strobeEffect(macAddress, speedMs)
                LightEffect.COLOR_CYCLE -> colorCycleEffect(macAddress, speedMs)
                LightEffect.BREATHING   -> breathingEffect(macAddress, speedMs)
                else -> Unit
            }
        }
    }

    private suspend fun fadeEffect(mac: String, stepMs: Long) {
        var brightness = 100; var direction = -1
        while (gattConnections.containsKey(mac)) {
            setBrightness(mac, brightness)
            brightness += direction * 5
            if (brightness <= 0 || brightness >= 100) direction *= -1
            delay(stepMs)
        }
    }

    private suspend fun strobeEffect(mac: String, stepMs: Long) {
        var on = true
        while (gattConnections.containsKey(mac)) {
            setPower(mac, on); on = !on; delay(stepMs)
        }
    }

    private suspend fun colorCycleEffect(mac: String, stepMs: Long) {
        var hue = 0f
        while (gattConnections.containsKey(mac)) {
            val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            setColor(mac, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
            hue = (hue + 2f) % 360f
            delay(stepMs)
        }
    }

    private suspend fun breathingEffect(mac: String, stepMs: Long) {
        var brightness = 0; var direction = 1
        while (gattConnections.containsKey(mac)) {
            setBrightness(mac, brightness)
            brightness += direction * 3
            if (brightness >= 100) direction = -1
            if (brightness <= 5) direction = 1
            delay(stepMs)
        }
    }

    // ════════════════════════════
    // GATT CALLBACK
    // ════════════════════════════

    private fun createGattCallback(bulb: Bulb, onConnect: (Boolean) -> Unit) =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        updateBulbState(bulb.macAddress, ConnectionState.CONNECTED)
                        val current = _connectedBulbs.value.toMutableList()
                        if (current.none { it.macAddress == bulb.macAddress }) current.add(bulb)
                        _connectedBulbs.value = current
                        try { gatt.discoverServices() } catch (e: SecurityException) {}
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        updateBulbState(bulb.macAddress, ConnectionState.DISCONNECTED)
                        gattConnections.remove(bulb.macAddress)
                        try { gatt.close() } catch (e: SecurityException) {}
                        onConnect(false)
                    }
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) onConnect(true)
                else onConnect(false)
            }
        }

    // ════════════════════════════
    // UTILITAIRES
    // ════════════════════════════

    private fun rgbToXY(r: Int, g: Int, b: Int): Pair<Float, Float> {
        var red = r / 255f; var green = g / 255f; var blue = b / 255f
        red   = if (red   > 0.04045f) Math.pow(((red   + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else red   / 12.92f
        green = if (green > 0.04045f) Math.pow(((green + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else green / 12.92f
        blue  = if (blue  > 0.04045f) Math.pow(((blue  + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else blue  / 12.92f
        val X = red * 0.664511f + green * 0.154324f + blue * 0.162028f
        val Y = red * 0.283881f + green * 0.668433f + blue * 0.047685f
        val Z = red * 0.000088f + green * 0.072310f + blue * 0.986039f
        val sum = X + Y + Z
        if (sum == 0f) return Pair(0f, 0f)
        return Pair(X / sum, Y / sum)
    }

    private fun updateBulbState(macAddress: String, state: ConnectionState) {
        val current = _connectedBulbs.value.toMutableList()
        val idx = current.indexOfFirst { it.macAddress == macAddress }
        if (idx >= 0) current[idx] = current[idx].copy().also { it.connectionState = state }
        _connectedBulbs.value = current
    }

    fun release() { disconnectAll(); scope.cancel() }
}
