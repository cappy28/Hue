package com.lumicontrol.ui.activities

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.util.UUID

class HueController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    private val HUE_SERVICE = UUID.fromString("932c32bd-0000-47a2-835a-a8d455b859dd")
    private val HUE_POWER   = UUID.fromString("932c32bd-0002-47a2-835a-a8d455b859dd")
    private val HUE_BRIGHT  = UUID.fromString("932c32bd-0003-47a2-835a-a8d455b859dd")
    private val HUE_COLOR   = UUID.fromString("932c32bd-0005-47a2-835a-a8d455b859dd")
    private val HUE_TEMP    = UUID.fromString("932c32bd-0004-47a2-835a-a8d455b859dd")

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    var connectedGatt: BluetoothGatt? = null
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun scan() {
        onStatus("🔍 Scan Hue...")
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = try { result.device.name } catch (e: SecurityException) { null }
                    if (name != null && name.contains("Hue", ignoreCase = true)) {
                        scanner.stopScan(this)
                        onStatus("Trouvé : $name — Connexion...")
                        connect(result.device)
                    }
                }
            })
            Handler(Looper.getMainLooper()).postDelayed({
                try { scanner.stopScan(object : ScanCallback() {}) } catch (e: Exception) {}
                if (connectedGatt == null) onStatus("❌ Aucune ampoule Hue trouvée")
            }, 10000)
        } catch (e: SecurityException) { onStatus("Permission refusée") }
    }

    private fun connect(device: BluetoothDevice) {
        try {
            connectedGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        onStatus("✅ Hue connectée !")
                        try { gatt.discoverServices() } catch (e: SecurityException) {}
                    } else {
                        onStatus("❌ Hue déconnectée")
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    onStatus("✅ Hue prête !")
                    setBrightness(254)
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) { onStatus("Erreur connexion") }
    }

    fun setPower(on: Boolean) {
        write(HUE_POWER, if (on) byteArrayOf(0x01) else byteArrayOf(0x00))
    }

    fun setBrightness(value: Int) {
        write(HUE_BRIGHT, byteArrayOf(value.coerceIn(1, 254).toByte()))
    }

    fun setColor(r: Int, g: Int, b: Int) {
        val (x, y) = rgbToXY(r, g, b)
        val xi = (x * 65535).toInt()
        val yi = (y * 65535).toInt()
        write(HUE_COLOR, byteArrayOf(
            (xi and 0xFF).toByte(), ((xi shr 8) and 0xFF).toByte(),
            (yi and 0xFF).toByte(), ((yi shr 8) and 0xFF).toByte()
        ))
    }

    fun setTemperature(mirek: Int) {
        write(HUE_TEMP, byteArrayOf(
            (mirek and 0xFF).toByte(),
            ((mirek shr 8) and 0xFF).toByte()
        ))
    }

    private fun write(charUUID: UUID, data: ByteArray) {
        val gatt = connectedGatt ?: return
        val service = gatt.getService(HUE_SERVICE) ?: return
        val char = service.getCharacteristic(charUUID) ?: return
        try {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) {}
    }

    private fun rgbToXY(r: Int, g: Int, b: Int): Pair<Float, Float> {
        var red = r / 255f; var green = g / 255f; var blue = b / 255f
        red   = if (red   > 0.04045f) Math.pow(((red   + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else red / 12.92f
        green = if (green > 0.04045f) Math.pow(((green + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else green / 12.92f
        blue  = if (blue  > 0.04045f) Math.pow(((blue  + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else blue / 12.92f
        val X = red * 0.664511f + green * 0.154324f + blue * 0.162028f
        val Y = red * 0.283881f + green * 0.668433f + blue * 0.047685f
        val Z = red * 0.000088f + green * 0.072310f + blue * 0.986039f
        val sum = X + Y + Z
        if (sum == 0f) return Pair(0f, 0f)
        return Pair(X / sum, Y / sum)
    }

    fun release() {
        try { connectedGatt?.close() } catch (e: SecurityException) {}
        scope.cancel()
    }
}
