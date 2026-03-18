package com.lumicontrol.ui.activities

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lumicontrol.R
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var connectedGatt: BluetoothGatt? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var effectJob: Job? = null
    private var musicJob: Job? = null

    private val HUE_SERVICE = UUID.fromString("932c32bd-0000-47a2-835a-a8d455b859dd")
    private val HUE_POWER   = UUID.fromString("932c32bd-0002-47a2-835a-a8d455b859dd")
    private val HUE_BRIGHT  = UUID.fromString("932c32bd-0003-47a2-835a-a8d455b859dd")
    private val HUE_COLOR   = UUID.fromString("932c32bd-0005-47a2-835a-a8d455b859dd")
    private val HUE_TEMP    = UUID.fromString("932c32bd-0004-47a2-835a-a8d455b859dd")

    private lateinit var tvStatus: TextView
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekRed: SeekBar
    private lateinit var seekGreen: SeekBar
    private lateinit var seekBlue: SeekBar
    private lateinit var viewColor: android.view.View

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus       = findViewById(R.id.tvStatus)
        seekBrightness = findViewById(R.id.seekBrightness)
        seekRed        = findViewById(R.id.seekRed)
        seekGreen      = findViewById(R.id.seekGreen)
        seekBlue       = findViewById(R.id.seekBlue)
        viewColor      = findViewById(R.id.viewColor)

        findViewById<Button>(R.id.btnScan).setOnClickListener { checkAndScan() }
        findViewById<Button>(R.id.btnOn).setOnClickListener { setPower(true) }
        findViewById<Button>(R.id.btnOff).setOnClickListener { setPower(false) }

        // Boutons couleurs rapides
        findViewById<Button>(R.id.btnRed).setOnClickListener    { stopEffect(); setColor(255, 0, 0) }
        findViewById<Button>(R.id.btnGreen).setOnClickListener  { stopEffect(); setColor(0, 255, 0) }
        findViewById<Button>(R.id.btnBlue).setOnClickListener   { stopEffect(); setColor(0, 0, 255) }
        findViewById<Button>(R.id.btnWhite).setOnClickListener  { stopEffect(); setColor(255, 255, 255) }
        findViewById<Button>(R.id.btnOrange).setOnClickListener { stopEffect(); setColor(255, 100, 0) }
        findViewById<Button>(R.id.btnPink).setOnClickListener   { stopEffect(); setColor(255, 0, 150) }
        findViewById<Button>(R.id.btnPurple).setOnClickListener { stopEffect(); setColor(128, 0, 255) }

        // Modes blancs
        findViewById<Button>(R.id.btnWarmWhite).setOnClickListener  { stopEffect(); setTemperature(153) }
        findViewById<Button>(R.id.btnCoolWhite).setOnClickListener  { stopEffect(); setTemperature(500) }
        findViewById<Button>(R.id.btnDaylight).setOnClickListener   { stopEffect(); setTemperature(300) }

        // Effets
        findViewById<Button>(R.id.btnEffectFade).setOnClickListener        { startFade() }
        findViewById<Button>(R.id.btnEffectStrobe).setOnClickListener      { startStrobe() }
        findViewById<Button>(R.id.btnEffectRainbow).setOnClickListener     { startRainbow() }
        findViewById<Button>(R.id.btnEffectBreath).setOnClickListener      { startBreathing() }
        findViewById<Button>(R.id.btnEffectLightning).setOnClickListener   { startLightning() }
        findViewById<Button>(R.id.btnEffectFire).setOnClickListener        { startFire() }
        findViewById<Button>(R.id.btnEffectPolice).setOnClickListener      { startPolice() }
        findViewById<Button>(R.id.btnEffectStop).setOnClickListener        { stopEffect() }

        // Sync musique
        findViewById<Button>(R.id.btnMusicSync).setOnClickListener  { startMusicSync() }
        findViewById<Button>(R.id.btnMusicStop).setOnClickListener  { stopMusicSync() }

        // Luminosité
        seekBrightness.max = 254
        seekBrightness.progress = 254
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setBrightness(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Sliders RGB
        val colorListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) applyRgbSliders()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        seekRed.setOnSeekBarChangeListener(colorListener)
        seekGreen.setOnSeekBarChangeListener(colorListener)
        seekBlue.setOnSeekBarChangeListener(colorListener)
    }

    // ════════════════════════════
    // SCAN & CONNEXION
    // ════════════════════════════

    private fun checkAndScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else startScan()
    }

    private fun startScan() {
        tvStatus.text = "🔍 Scan en cours..."
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = try { result.device.name } catch (e: SecurityException) { null }
                    if (name != null && name.contains("Hue", ignoreCase = true)) {
                        scanner.stopScan(this)
                        runOnUiThread {
                            tvStatus.text = "Trouvé : $name\nConnexion..."
                            connectToDevice(result.device)
                        }
                    }
                }
            })
            Handler(Looper.getMainLooper()).postDelayed({
                try { scanner.stopScan(object : ScanCallback() {}) } catch (e: Exception) {}
                if (connectedGatt == null) runOnUiThread { tvStatus.text = "❌ Aucune ampoule Hue trouvée" }
            }, 10000)
        } catch (e: SecurityException) { tvStatus.text = "Permission refusée" }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            connectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        runOnUiThread { tvStatus.text = "✅ Connectée ! Prête à contrôler" }
                        try { gatt.discoverServices() } catch (e: SecurityException) {}
                    } else {
                        runOnUiThread { tvStatus.text = "❌ Déconnectée" }
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    runOnUiThread { tvStatus.text = "✅ Prête !" }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) { tvStatus.text = "Erreur connexion" }
    }

    // ════════════════════════════
    // COMMANDES DE BASE
    // ════════════════════════════

    private fun setPower(on: Boolean) {
        write(HUE_POWER, if (on) byteArrayOf(0x01) else byteArrayOf(0x00))
        tvStatus.text = if (on) "☀️ Allumée" else "🌙 Éteinte"
    }

    private fun setBrightness(value: Int) {
        write(HUE_BRIGHT, byteArrayOf(value.coerceIn(1, 254).toByte()))
    }

    private fun setColor(r: Int, g: Int, b: Int) {
        val (x, y) = rgbToXY(r, g, b)
        val xi = (x * 65535).toInt()
        val yi = (y * 65535).toInt()
        write(HUE_COLOR, byteArrayOf(
            (xi and 0xFF).toByte(), ((xi shr 8) and 0xFF).toByte(),
            (yi and 0xFF).toByte(), ((yi shr 8) and 0xFF).toByte()
        ))
        viewColor.setBackgroundColor(android.graphics.Color.rgb(r, g, b))
    }

    private fun setTemperature(mirek: Int) {
        // Température en mirek (153=froid/6500K, 500=chaud/2000K)
        write(HUE_TEMP, byteArrayOf(
            (mirek and 0xFF).toByte(),
            ((mirek shr 8) and 0xFF).toByte()
        ))
    }

    private fun applyRgbSliders() {
        stopEffect()
        setColor(seekRed.progress, seekGreen.progress, seekBlue.progress)
    }

    // ════════════════════════════
    // EFFETS
    // ════════════════════════════

    private fun stopEffect() {
        effectJob?.cancel()
        effectJob = null
    }

    private fun startFade() {
        stopEffect()
        tvStatus.text = "🌅 Effet : Fondu"
        effectJob = scope.launch {
            var brightness = 254; var dir = -1
            while (isActive) {
                setBrightness(brightness)
                brightness += dir * 8
                if (brightness <= 5) dir = 1
                if (brightness >= 254) dir = -1
                delay(80)
            }
        }
    }

    private fun startStrobe() {
        stopEffect()
        tvStatus.text = "⚡ Effet : Stroboscope"
        effectJob = scope.launch {
            var on = true
            while (isActive) {
                setPower(on); on = !on
                delay(100)
            }
        }
    }

    private fun startRainbow() {
        stopEffect()
        tvStatus.text = "🌈 Effet : Arc-en-ciel"
        effectJob = scope.launch {
            var hue = 0f
            while (isActive) {
                val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                setColor(
                    android.graphics.Color.red(color),
                    android.graphics.Color.green(color),
                    android.graphics.Color.blue(color)
                )
                hue = (hue + 3f) % 360f
                delay(100)
            }
        }
    }

    private fun startBreathing() {
        stopEffect()
        tvStatus.text = "💨 Effet : Respiration"
        effectJob = scope.launch {
            var brightness = 5; var dir = 1
            while (isActive) {
                setBrightness(brightness)
                brightness += dir * 5
                if (brightness >= 254) dir = -1
                if (brightness <= 5) dir = 1
                delay(50)
            }
        }
    }

    private fun startLightning() {
        stopEffect()
        tvStatus.text = "⚡ Effet : Éclair"
        effectJob = scope.launch {
            while (isActive) {
                // Flash blanc intense
                setColor(255, 255, 255)
                setBrightness(254)
                delay((50..150).random().toLong())
                setPower(false)
                delay((50..300).random().toLong())
                setPower(true)
                // Parfois double flash
                if ((0..3).random() == 0) {
                    delay((500..2000).random().toLong())
                    setColor(255, 255, 255)
                    delay((30..80).random().toLong())
                    setPower(false)
                    delay((30..80).random().toLong())
                    setPower(true)
                }
                delay((1000..4000).random().toLong())
            }
        }
    }

    private fun startFire() {
        stopEffect()
        tvStatus.text = "🔥 Effet : Feu"
        effectJob = scope.launch {
            while (isActive) {
                val r = (200..255).random()
                val g = (30..80).random()
                val b = 0
                val bright = (100..254).random()
                setColor(r, g, b)
                setBrightness(bright)
                delay((50..150).random().toLong())
            }
        }
    }

    private fun startPolice() {
        stopEffect()
        tvStatus.text = "🚨 Effet : Police"
        effectJob = scope.launch {
            while (isActive) {
                // Rouge
                setColor(255, 0, 0)
                delay(150)
                setPower(false); delay(80)
                setPower(true); delay(150)
                setPower(false); delay(80)
                setPower(true)
                delay(200)
                // Bleu
                setColor(0, 0, 255)
                delay(150)
                setPower(false); delay(80)
                setPower(true); delay(150)
                setPower(false); delay(80)
                setPower(true)
                delay(200)
            }
        }
    }

    // ════════════════════════════
    // SYNC MUSIQUE
    // ════════════════════════════

    private fun startMusicSync() {
        stopEffect()
        stopMusicSync()
        tvStatus.text = "🎵 Sync musique active"
        musicJob = scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(
                44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ) * 2
            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 44100,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                audioRecord.startRecording()
                val buffer = ShortArray(bufferSize)
                var smoothed = 0f
                while (isActive) {
                    val read = audioRecord.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) { val s = buffer[i] / 32768f; sum += s * s }
                        val rms = sqrt(sum / read).toFloat().coerceIn(0f, 1f)
                        smoothed = 0.3f * smoothed + 0.7f * rms
                        val bright = (smoothed * 2f * 254f).toInt().coerceIn(5, 254)
                        // Couleur selon intensité
                        val (r, g, b) = when {
                            smoothed < 0.2f -> Triple(0, 50, 255)
                            smoothed < 0.5f -> Triple(0, 255, 100)
                            smoothed < 0.8f -> Triple(255, 150, 0)
                            else            -> Triple(255, 0, 0)
                        }
                        setBrightness(bright)
                        setColor(r, g, b)
                    }
                    delay(50)
                }
            } catch (e: SecurityException) {
                runOnUiThread { tvStatus.text = "Permission micro refusée" }
            } finally {
                audioRecord?.stop(); audioRecord?.release()
            }
        }
    }

    private fun stopMusicSync() {
        musicJob?.cancel(); musicJob = null
        tvStatus.text = "🎵 Sync musique arrêtée"
    }

    // ════════════════════════════
    // UTILITAIRES
    // ════════════════════════════

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

    override fun onDestroy() {
        super.onDestroy()
        stopEffect(); stopMusicSync(); scope.cancel()
        try { connectedGatt?.close() } catch (e: SecurityException) {}
    }
}

class ScanActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
