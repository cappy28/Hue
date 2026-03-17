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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * ════════════════════════════════════════════════════════════
 * BleManager — Gestionnaire central Bluetooth Low Energy
 * ════════════════════════════════════════════════════════════
 *
 * Singleton responsable de :
 *  - Scanner les appareils BLE à proximité
 *  - Gérer les connexions GATT simultanées (jusqu'à 7)
 *  - Envoyer des commandes (couleur, luminosité, on/off)
 *  - Émettre l'état en temps réel via StateFlow
 */
class BleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_PERIOD_MS = 10_000L  // 10 secondes de scan

        // ─── UUIDs GATT standards pour ampoules RGB génériques ───
        val SERVICE_UUID: UUID       = UUID.fromString("0000FFD5-0000-1000-8000-00805F9B34FB")
        val COLOR_CHAR_UUID: UUID    = UUID.fromString("0000FFD9-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_UUID: UUID   = UUID.fromString("0000FFD4-0000-1000-8000-00805F9B34FB")
        val BATTERY_SERVICE: UUID    = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_CHAR_UUID: UUID  = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        @Volatile
        private var INSTANCE: BleManager? = null

        fun getInstance(context: Context): BleManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    // ─── Adaptateur et scanner BLE ───
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    // ─── Connexions GATT actives ───
    private val gattConnections = mutableMapOf<String, BluetoothGatt>()

    // ─── Coroutines ───
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // ─── État observable ───
    private val _scanResults = MutableStateFlow<List<Bulb>>(emptyList())
    val scanResults: StateFlow<List<Bulb>> = _scanResults

    private val _connectedBulbs = MutableStateFlow<List<Bulb>>(emptyList())
    val connectedBulbs: StateFlow<List<Bulb>> = _connectedBulbs

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _bleEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val bleEnabled: StateFlow<Boolean> = _bleEnabled

    // ─── Buffer des résultats de scan ───
    private val scannedDevices = mutableListOf<Bulb>()

    // ════════════════════════════════════════════════════════════
    // SCAN
    // ════════════════════════════════════════════════════════════

    /**
     * Lance un scan BLE pendant SCAN_PERIOD_MS millisecondes.
     * Filtre les appareils connus pour ne montrer que les ampoules probables.
     */
    fun startScan() {
        if (_isScanning.value) return
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth désactivé — impossible de scanner")
            return
        }

        scannedDevices.clear()
        _isScanning.value = true

        // Filtre : chercher les services d'ampoules connues
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
                .build()
            // Ajouter d'autres filtres selon les marques ici
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            bleScanner?.startScan(null, settings, scanCallback) // null = pas de filtre = tout voir
            Log.d(TAG, "Scan BLE démarré")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission Bluetooth manquante : ${e.message}")
            _isScanning.value = false
            return
        }

        // Arrêt automatique après SCAN_PERIOD_MS
        mainHandler.postDelayed({ stopScan() }, SCAN_PERIOD_MS)
    }

    /** Arrête le scan BLE en cours. */
    fun stopScan() {
        if (!_isScanning.value) return
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Erreur arrêt scan : ${e.message}")
        }
        _isScanning.value = false
        _scanResults.value = scannedDevices.toList()
        Log.d(TAG, "Scan arrêté — ${scannedDevices.size} appareil(s) trouvé(s)")
    }

    /** Callback de scan BLE — appelé pour chaque appareil détecté. */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try { device.name } catch (e: SecurityException) { null }

            // Éviter les doublons
            if (scannedDevices.none { it.macAddress == device.address }) {
                val bulb = Bulb(
                    macAddress = device.address,
                    name = name ?: "Ampoule ${device.address.takeLast(5)}",
                    type = BulbGattProfile.detectType(name ?: "", result),
                )
                scannedDevices.add(bulb)
                _scanResults.value = scannedDevices.toList()
                Log.d(TAG, "Appareil détecté : ${bulb.name} (${bulb.macAddress}) RSSI=${result.rssi}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Échec du scan BLE — code : $errorCode")
            _isScanning.value = false
        }
    }

    // ════════════════════════════════════════════════════════════
    // CONNEXION / DÉCONNEXION
    // ════════════════════════════════════════════════════════════

    /**
     * Connecte une ampoule via GATT.
     * @param bulb L'ampoule à connecter
     * @param onResult Callback (succès/erreur)
     */
    fun connect(bulb: Bulb, onResult: (success: Boolean) -> Unit = {}) {
        if (gattConnections.containsKey(bulb.macAddress)) {
            Log.d(TAG, "${bulb.name} déjà connectée")
            onResult(true)
            return
        }

        updateBulbState(bulb.macAddress, ConnectionState.CONNECTING)

        val device = try {
            bluetoothAdapter?.getRemoteDevice(bulb.macAddress)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission manquante pour connecter ${bulb.macAddress}")
            onResult(false)
            return
        } ?: run {
            onResult(false)
            return
        }

        try {
            val gatt = device.connectGatt(
                context,
                false,  // autoConnect = false pour connexion rapide
                createGattCallback(bulb.macAddress, onResult),
                BluetoothDevice.TRANSPORT_LE
            )
            gattConnections[bulb.macAddress] = gatt
        } catch (e: SecurityException) {
            Log.e(TAG, "Erreur de connexion GATT : ${e.message}")
            onResult(false)
        }
    }

    /** Déconnecte une ampoule proprement. */
    fun disconnect(macAddress: String) {
        gattConnections[macAddress]?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "Erreur déconnexion : ${e.message}")
            }
            gattConnections.remove(macAddress)
        }
        updateBulbState(macAddress, ConnectionState.DISCONNECTED)
        Log.d(TAG, "Déconnexion de $macAddress")
    }

    /** Déconnecte toutes les ampoules. */
    fun disconnectAll() {
        gattConnections.keys.toList().forEach { disconnect(it) }
    }

    // ════════════════════════════════════════════════════════════
    // COMMANDES
    // ════════════════════════════════════════════════════════════

    /**
     * Allume ou éteint une ampoule.
     */
    fun setPower(macAddress: String, on: Boolean) {
        val command = if (on)
            byteArrayOf(0xCC.toByte(), 0x23.toByte(), 0x33.toByte())
        else
            byteArrayOf(0xCC.toByte(), 0x24.toByte(), 0x33.toByte())
        sendCommand(macAddress, command)
        Log.d(TAG, "Power ${if (on) "ON" else "OFF"} → $macAddress")
    }

    /**
     * Règle la couleur RVB d'une ampoule.
     * @param r Rouge (0–255)
     * @param g Vert (0–255)
     * @param b Bleu (0–255)
     */
    fun setColor(macAddress: String, r: Int, g: Int, b: Int) {
        val profile = BulbGattProfile.profileFor(macAddress)
        val command = profile.buildColorCommand(r, g, b, 0)
        sendCommand(macAddress, command)
        Log.d(TAG, "Couleur ($r,$g,$b) → $macAddress")
    }

    /**
     * Règle la luminosité.
     * @param brightness 0–100 (pourcentage)
     */
    fun setBrightness(macAddress: String, brightness: Int) {
        val bVal = (brightness * 255 / 100).coerceIn(0, 255)
        val command = byteArrayOf(
            0x56.toByte(), 0x00, 0x00, 0x00,
            bVal.toByte(), 0x0F.toByte(), 0xAA.toByte()
        )
        sendCommand(macAddress, command)
        Log.d(TAG, "Luminosité $brightness% → $macAddress")
    }

    /**
     * Envoie une commande à toutes les ampoules connectées (broadcast).
     */
    fun broadcastCommand(r: Int, g: Int, b: Int, brightness: Int, on: Boolean) {
        val macs = gattConnections.keys.toList()
        macs.forEach { mac ->
            if (on) {
                setPower(mac, true)
                setColor(mac, r, g, b)
                setBrightness(mac, brightness)
            } else {
                setPower(mac, false)
            }
        }
        Log.d(TAG, "Broadcast → ${macs.size} ampoule(s)")
    }

    /**
     * Active un effet dynamique sur une ampoule.
     * Les effets sont simulés côté app (envoi de commandes périodiques).
     */
    fun startEffect(macAddress: String, effect: com.lumicontrol.models.LightEffect, speedMs: Long = 500) {
        scope.launch {
            when (effect) {
                com.lumicontrol.models.LightEffect.FADE -> fadeEffect(macAddress, speedMs)
                com.lumicontrol.models.LightEffect.STROBE -> strobeEffect(macAddress, speedMs)
                com.lumicontrol.models.LightEffect.COLOR_CYCLE -> colorCycleEffect(macAddress, speedMs)
                com.lumicontrol.models.LightEffect.BREATHING -> breathingEffect(macAddress, speedMs)
                else -> Unit
            }
        }
    }

    // ─── Effets internes ───

    private suspend fun fadeEffect(mac: String, stepMs: Long) {
        var brightness = 100
        var direction = -1
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
            setPower(mac, on)
            on = !on
            delay(stepMs)
        }
    }

    private suspend fun colorCycleEffect(mac: String, stepMs: Long) {
        var hue = 0f
        while (gattConnections.containsKey(mac)) {
            val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            setColor(mac,
                android.graphics.Color.red(color),
                android.graphics.Color.green(color),
                android.graphics.Color.blue(color)
            )
            hue = (hue + 2f) % 360f
            delay(stepMs)
        }
    }

    private suspend fun breathingEffect(mac: String, stepMs: Long) {
        var brightness = 0
        var direction = 1
        while (gattConnections.containsKey(mac)) {
            setBrightness(mac, brightness)
            brightness += direction * 3
            if (brightness >= 100) direction = -1
            if (brightness <= 5) direction = 1
            delay(stepMs)
        }
    }

    // ════════════════════════════════════════════════════════════
    // GATT CALLBACK
    // ════════════════════════════════════════════════════════════

    private fun createGattCallback(mac: String, onConnect: (Boolean) -> Unit) =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "GATT connecté : $mac")
                        updateBulbState(mac, ConnectionState.CONNECTED)
                        try { gatt.discoverServices() } catch (e: SecurityException) {}
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "GATT déconnecté : $mac")
                        updateBulbState(mac, ConnectionState.DISCONNECTED)
                        gattConnections.remove(mac)
                        try { gatt.close() } catch (e: SecurityException) {}
                        onConnect(false)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services découverts pour $mac")
                    onConnect(true)
                    // Allumer par défaut après connexion
                    mainHandler.postDelayed({ setPower(mac, true) }, 500)
                } else {
                    Log.w(TAG, "Découverte services échouée pour $mac")
                    onConnect(false)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Écriture GATT échouée sur $mac — status=$status")
                }
            }
        }

    // ════════════════════════════════════════════════════════════
    // UTILITAIRES INTERNES
    // ════════════════════════════════════════════════════════════

    /** Envoie une commande brute à une ampoule via GATT. */
    private fun sendCommand(macAddress: String, data: ByteArray) {
        val gatt = gattConnections[macAddress] ?: run {
            Log.w(TAG, "Tentative d'envoi sans connexion active : $macAddress")
            return
        }

        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.w(TAG, "Service UUID introuvable pour $macAddress")
            return
        }

        val characteristic = service.getCharacteristic(COLOR_CHAR_UUID) ?: run {
            Log.w(TAG, "Caractéristique couleur introuvable pour $macAddress")
            return
        }

        try {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée pour écriture GATT : ${e.message}")
        }
    }

    /** Met à jour l'état d'une ampoule dans la liste connectée. */
    private fun updateBulbState(macAddress: String, state: ConnectionState) {
        val current = _connectedBulbs.value.toMutableList()
        val idx = current.indexOfFirst { it.macAddress == macAddress }
        if (idx >= 0) {
            current[idx] = current[idx].copy().also { it.connectionState = state }
        }
        _connectedBulbs.value = current
    }

    /** Libère toutes les ressources. */
    fun release() {
        disconnectAll()
        scope.cancel()
    }
}
