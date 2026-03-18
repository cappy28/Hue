package com.lumicontrol.ui.activities

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lumicontrol.R
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    // ══════════════════════════════════════════
    // VARIABLES
    // ══════════════════════════════════════════

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var irManager: ConsumerIrManager? = null
    private var connectedGatt: BluetoothGatt? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var effectJob: Job? = null
    private var musicJob: Job? = null

    // UUIDs Hue
    private val HUE_SERVICE = UUID.fromString("932c32bd-0000-47a2-835a-a8d455b859dd")
    private val HUE_POWER   = UUID.fromString("932c32bd-0002-47a2-835a-a8d455b859dd")
    private val HUE_BRIGHT  = UUID.fromString("932c32bd-0003-47a2-835a-a8d455b859dd")
    private val HUE_COLOR   = UUID.fromString("932c32bd-0005-47a2-835a-a8d455b859dd")
    private val HUE_TEMP    = UUID.fromString("932c32bd-0004-47a2-835a-a8d455b859dd")

    // ══════════════════════════════════════════
    // CODES IR — BALTIMORE (petite télécommande 24 touches)
    // NEC protocol 38kHz
    // ══════════════════════════════════════════
    private val BAL_ON       = 0xFF30CF
    private val BAL_OFF      = 0xFFB04F
    private val BAL_BRIGHT_P = 0xFF3AC5
    private val BAL_BRIGHT_M = 0xFFBA45
    private val BAL_RED      = 0xFF906F
    private val BAL_GREEN    = 0xFF10EF
    private val BAL_BLUE     = 0xFF50AF
    private val BAL_WHITE    = 0xFFD02F
    private val BAL_ORANGE   = 0xFF8877
    private val BAL_LIME     = 0xFF48B7
    private val BAL_CYAN     = 0xFF32CD
    private val BAL_WARMW    = 0xFFE817
    private val BAL_SPEED_P  = 0xFF20DF
    private val BAL_SPEED_M  = 0xFFA05F
    private val BAL_MODE     = 0xFF807F
    private val BAL_NIGHT    = 0xFF60CF

    // ══════════════════════════════════════════
    // CODES IR — WALL LIGHT (grande télécommande 44 touches)
    // ══════════════════════════════════════════
    private val WL_ON        = 0xFF02FD
    private val WL_OFF       = 0xFF827D
    private val WL_BRIGHT_P  = 0xFF3AC5
    private val WL_BRIGHT_M  = 0xFFBA45
    private val WL_RED       = 0xFF1AE5
    private val WL_GREEN     = 0xFF9A65
    private val WL_BLUE      = 0xFFA25D
    private val WL_WHITE     = 0xFF22DD
    private val WL_ORANGE    = 0xFF2AD5
    private val WL_LIME      = 0xFFAA55
    private val WL_CYAN      = 0xFF926D
    private val WL_PINK      = 0xFF12ED
    private val WL_PURPLE    = 0xFF926D
    private val WL_FLASH     = 0xFFD02F
    private val WL_STROBE    = 0xFFC837
    private val WL_FADE      = 0xFF48B7
    private val WL_SMOOTH    = 0xFF6897
    private val WL_SPEED_P   = 0xFF20DF
    private val WL_SPEED_M   = 0xFFA05F
    private val WL_MUSIC1    = 0xFF58A7
    private val WL_MUSIC2    = 0xFFD827
    private val WL_MUSIC3    = 0xFF7887
    private val WL_MUSIC4    = 0xFFF807

    private lateinit var tvStatus: TextView
    private lateinit var tabHue: TextView
    private lateinit var tabBaltimore: TextView
    private lateinit var tabWallLight: TextView
    private lateinit var panelHue: View
    private lateinit var panelBaltimore: View
    private lateinit var panelWallLight: View
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekRed: SeekBar
    private lateinit var seekGreen: SeekBar
    private lateinit var seekBlue: SeekBar
    private lateinit var viewColor: View

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startScan() }

    // ══════════════════════════════════════════
    // ONCREATE
    // ══════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager

        tvStatus       = findViewById(R.id.tvStatus)
        tabHue         = findViewById(R.id.tabHue)
        tabBaltimore   = findViewById(R.id.tabBaltimore)
        tabWallLight   = findViewById(R.id.tabWallLight)
        panelHue       = findViewById(R.id.panelHue)
        panelBaltimore = findViewById(R.id.panelBaltimore)
        panelWallLight = findViewById(R.id.panelWallLight)
        seekBrightness = findViewById(R.id.seekBrightness)
        seekRed        = findViewById(R.id.seekRed)
        seekGreen      = findViewById(R.id.seekGreen)
        seekBlue       = findViewById(R.id.seekBlue)
        viewColor      = findViewById(R.id.viewColor)

        setupTabs()
        setupHuePanel()
        setupBaltimorPanel()
        setupWallLightPanel()

        if (irManager?.hasIrEmitter() == true) {
            tvStatus.text = "✅ Émetteur IR disponible"
        } else {
            tvStatus.text = "⚠️ Pas d'IR détecté"
        }
    }

    // ══════════════════════════════════════════
    // ONGLETS
    // ══════════════════════════════════════════

    private fun setupTabs() {
        showPanel(0)
        tabHue.setOnClickListener { showPanel(0) }
        tabBaltimore.setOnClickListener { showPanel(1) }
        tabWallLight.setOnClickListener { showPanel(2) }
    }

    private fun showPanel(index: Int) {
        panelHue.visibility       = if (index == 0) View.VISIBLE else View.GONE
        panelBaltimore.visibility = if (index == 1) View.VISIBLE else View.GONE
        panelWallLight.visibility = if (index == 2) View.VISIBLE else View.GONE

        val active   = "#FF6B35"
        val inactive = "#333355"
        tabHue.setBackgroundColor(android.graphics.Color.parseColor(if (index == 0) active else inactive))
        tabBaltimore.setBackgroundColor(android.graphics.Color.parseColor(if (index == 1) active else inactive))
        tabWallLight.setBackgroundColor(android.graphics.Color.parseColor(if (index == 2) active else inactive))
    }

    // ══════════════════════════════════════════
    // PANEL HUE BLUETOOTH
    // ══════════════════════════════════════════

    private fun setupHuePanel() {
        findViewById<Button>(R.id.btnScan).setOnClickListener { checkAndScan() }
        findViewById<Button>(R.id.btnHueOn).setOnClickListener  { setPower(true) }
        findViewById<Button>(R.id.btnHueOff).setOnClickListener { setPower(false) }

        // Couleurs rapides Hue
        findViewById<Button>(R.id.hueRed).setOnClickListener    { stopEffect(); setColor(255,0,0) }
        findViewById<Button>(R.id.hueGreen).setOnClickListener  { stopEffect(); setColor(0,255,0) }
        findViewById<Button>(R.id.hueBlue).setOnClickListener   { stopEffect(); setColor(0,0,255) }
        findViewById<Button>(R.id.hueWhite).setOnClickListener  { stopEffect(); setColor(255,255,255) }
        findViewById<Button>(R.id.hueOrange).setOnClickListener { stopEffect(); setColor(255,100,0) }
        findViewById<Button>(R.id.huePink).setOnClickListener   { stopEffect(); setColor(255,0,150) }
        findViewById<Button>(R.id.huePurple).setOnClickListener { stopEffect(); setColor(128,0,255) }
        findViewById<Button>(R.id.hueYellow).setOnClickListener { stopEffect(); setColor(255,220,0) }

        // Blancs
        findViewById<Button>(R.id.hueWarm).setOnClickListener  { stopEffect(); setTemperature(500) }
        findViewById<Button>(R.id.hueDay).setOnClickListener   { stopEffect(); setTemperature(300) }
        findViewById<Button>(R.id.hueCool).setOnClickListener  { stopEffect(); setTemperature(153) }

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

        // RGB sliders
        val cl = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    stopEffect()
                    val r = seekRed.progress
                    val g = seekGreen.progress
                    val b = seekBlue.progress
                    setColor(r, g, b)
                    viewColor.setBackgroundColor(android.graphics.Color.rgb(r, g, b))
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        seekRed.setOnSeekBarChangeListener(cl)
        seekGreen.setOnSeekBarChangeListener(cl)
        seekBlue.setOnSeekBarChangeListener(cl)

        // Effets Hue
        findViewById<Button>(R.id.hueFade).setOnClickListener      { startFade() }
        findViewById<Button>(R.id.hueStrobe).setOnClickListener    { startStrobe() }
        findViewById<Button>(R.id.hueRainbow).setOnClickListener   { startRainbow() }
        findViewById<Button>(R.id.hueBreath).setOnClickListener    { startBreathing() }
        findViewById<Button>(R.id.hueLightning).setOnClickListener { startLightning() }
        findViewById<Button>(R.id.hueFire).setOnClickListener      { startFire() }
        findViewById<Button>(R.id.huePolice).setOnClickListener    { startPolice() }
        findViewById<Button>(R.id.hueParty).setOnClickListener     { startParty() }
        findViewById<Button>(R.id.hueStop).setOnClickListener      { stopEffect() }

        // Sync musique
        findViewById<Button>(R.id.hueMusicOn).setOnClickListener   { startMusicSync() }
        findViewById<Button>(R.id.hueMusicOff).setOnClickListener  { stopMusicSync() }
    }

    // ══════════════════════════════════════════
    // PANEL BALTIMORE IR
    // ══════════════════════════════════════════

    private fun setupBaltimorPanel() {
        findViewById<Button>(R.id.balOn).setOnClickListener       { sendIR(BAL_ON) }
        findViewById<Button>(R.id.balOff).setOnClickListener      { sendIR(BAL_OFF) }
        findViewById<Button>(R.id.balBrightP).setOnClickListener  { sendIR(BAL_BRIGHT_P) }
        findViewById<Button>(R.id.balBrightM).setOnClickListener  { sendIR(BAL_BRIGHT_M) }
        findViewById<Button>(R.id.balRed).setOnClickListener      { sendIR(BAL_RED) }
        findViewById<Button>(R.id.balGreen).setOnClickListener    { sendIR(BAL_GREEN) }
        findViewById<Button>(R.id.balBlue).setOnClickListener     { sendIR(BAL_BLUE) }
        findViewById<Button>(R.id.balWhite).setOnClickListener    { sendIR(BAL_WHITE) }
        findViewById<Button>(R.id.balOrange).setOnClickListener   { sendIR(BAL_ORANGE) }
        findViewById<Button>(R.id.balLime).setOnClickListener     { sendIR(BAL_LIME) }
        findViewById<Button>(R.id.balCyan).setOnClickListener     { sendIR(BAL_CYAN) }
        findViewById<Button>(R.id.balWarmW).setOnClickListener    { sendIR(BAL_WARMW) }
        findViewById<Button>(R.id.balSpeedP).setOnClickListener   { sendIR(BAL_SPEED_P) }
        findViewById<Button>(R.id.balSpeedM).setOnClickListener   { sendIR(BAL_SPEED_M) }
        findViewById<Button>(R.id.balMode).setOnClickListener     { sendIR(BAL_MODE) }
        findViewById<Button>(R.id.balNight).setOnClickListener    { sendIR(BAL_NIGHT) }
    }

    // ══════════════════════════════════════════
    // PANEL WALL LIGHT IR
    // ══════════════════════════════════════════

    private fun setupWallLightPanel() {
        findViewById<Button>(R.id.wlOn).setOnClickListener       { sendIR(WL_ON) }
        findViewById<Button>(R.id.wlOff).setOnClickListener      { sendIR(WL_OFF) }
        findViewById<Button>(R.id.wlBrightP).setOnClickListener  { sendIR(WL_BRIGHT_P) }
        findViewById<Button>(R.id.wlBrightM).setOnClickListener  { sendIR(WL_BRIGHT_M) }
        findViewById<Button>(R.id.wlRed).setOnClickListener      { sendIR(WL_RED) }
        findViewById<Button>(R.id.wlGreen).setOnClickListener    { sendIR(WL_GREEN) }
        findViewById<Button>(R.id.wlBlue).setOnClickListener     { sendIR(WL_BLUE) }
        findViewById<Button>(R.id.wlWhite).setOnClickListener    { sendIR(WL_WHITE) }
        findViewById<Button>(R.id.wlOrange).setOnClickListener   { sendIR(WL_ORANGE) }
        findViewById<Button>(R.id.wlLime).setOnClickListener     { sendIR(WL_LIME) }
        findViewById<Button>(R.id.wlCyan).setOnClickListener     { sendIR(WL_CYAN) }
        findViewById<Button>(R.id.wlPink).setOnClickListener     { sendIR(WL_PINK) }
        findViewById<Button>(R.id.wlFlash).setOnClickListener    { sendIR(WL_FLASH) }
        findViewById<Button>(R.id.wlStrobe).setOnClickListener   { sendIR(WL_STROBE) }
        findViewById<Button>(R.id.wlFade).setOnClickListener     { sendIR(WL_FADE) }
        findViewById<Button>(R.id.wlSmooth).setOnClickListener   { sendIR(WL_SMOOTH) }
        findViewById<Button>(R.id.wlSpeedP).setOnClickListener   { sendIR(WL_SPEED_P) }
        findViewById<Button>(R.id.wlSpeedM).setOnClickListener   { sendIR(WL_SPEED_M) }
        findViewById<Button>(R.id.wlMusic1).setOnClickListener   { sendIR(WL_MUSIC1) }
        findViewById<Button>(R.id.wlMusic2).setOnClickListener   { sendIR(WL_MUSIC2) }
        findViewById<Button>(R.id.wlMusic3).setOnClickListener   { sendIR(WL_MUSIC3) }
        findViewById<Button>(R.id.wlMusic4).setOnClickListener   { sendIR(WL_MUSIC4) }
    }

    // ══════════════════════════════════════════
    // INFRAROUGE
    // ══════════════════════════════════════════

    private fun sendIR(code: Int) {
        if (irManager?.hasIrEmitter() != true) {
            tvStatus.text = "❌ Pas d'émetteur IR"
            return
        }
        try {
            irManager?.transmit(38000, necToPulse(code))
            tvStatus.text = "📡 Signal IR envoyé"
        } catch (e: Exception) {
            tvStatus.text = "❌ Erreur IR : ${e.message}"
        }
    }

    /**
     * Convertit un code NEC 32 bits en tableau de pulses microseconde
     * pour Android ConsumerIrManager
     */
    private fun necToPulse(code: Int): IntArray {
        val pulses = mutableListOf<Int>()
        // Header
        pulses.add(9000)
        pulses.add(4500)
        // 32 bits MSB first
        for (i in 31 downTo 0) {
            pulses.add(562)
            if ((code shr i) and 1 == 1) pulses.add(1687)
            else pulses.add(562)
        }
        // Stop bit
        pulses.add(562)
        return pulses.toIntArray()
    }

    // ══════════════════════════════════════════
    // BLUETOOTH HUE
    // ══════════════════════════════════════════

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
        tvStatus.text = "🔍 Scan Hue..."
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = try { result.device.name } catch (e: SecurityException) { null }
                    if (name != null && name.contains("Hue", ignoreCase = true)) {
                        scanner.stopScan(this)
                        runOnUiThread {
                            tvStatus.text = "Trouvé : $name — Connexion..."
                            connectToDevice(result.device)
                        }
                    }
                }
            })
            Handler(Looper.getMainLooper()).postDelayed({
                try { scanner.stopScan(object : ScanCallback() {}) } catch (e: Exception) {}
                if (connectedGatt == null)
                    runOnUiThread { tvStatus.text = "❌ Aucune ampoule Hue trouvée" }
            }, 10000)
        } catch (e: SecurityException) { tvStatus.text = "Permission refusée" }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            connectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        runOnUiThread { tvStatus.text = "✅ Hue connectée !" }
                        try { gatt.discoverServices() } catch (e: SecurityException) {}
                    } else {
                        runOnUiThread { tvStatus.text = "❌ Hue déconnectée" }
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    runOnUiThread {
                        tvStatus.text = "✅ Hue prête !"
                        setBrightness(254)
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) { tvStatus.text = "Erreur connexion" }
    }

    private fun setPower(on: Boolean) {
        write(HUE_POWER, if (on) byteArrayOf(0x01) else byteArrayOf(0x00))
        tvStatus.text = if (on) "☀️ Hue allumée" else "🌙 Hue éteinte"
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
        runOnUiThread { viewColor.setBackgroundColor(android.graphics.Color.rgb(r, g, b)) }
    }

    private fun setTemperature(mirek: Int) {
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

    // ══════════════════════════════════════════
    // EFFETS HUE
    // ════════════════════════════════════════
    
private fun stopEffect() { effectJob?.cancel(); effectJob = null }

    private fun startFade() {
        stopEffect(); tvStatus.text = "🌅 Fondu"
        effectJob = scope.launch {
            var b = 254; var d = -1
            while (isActive) {
                setBrightness(b); b += d * 6
                if (b <= 5) d = 1; if (b >= 254) d = -1
                delay(60)
            }
        }
    }

    private fun startStrobe() {
        stopEffect(); tvStatus.text = "⚡ Stroboscope"
        effectJob = scope.launch {
            var on = true
            while (isActive) { setPower(on); on = !on; delay(80) }
        }
    }

    private fun startRainbow() {
        stopEffect(); tvStatus.text = "🌈 Arc-en-ciel"
        effectJob = scope.launch {
            var hue = 0f
            while (isActive) {
                val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                setColor(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
                hue = (hue + 2f) % 360f; delay(80)
            }
        }
    }

    private fun startBreathing() {
        stopEffect(); tvStatus.text = "💨 Respiration"
        effectJob = scope.launch {
            var b = 5; var d = 1
            while (isActive) {
                setBrightness(b); b += d * 4
                if (b >= 254) d = -1; if (b <= 5) d = 1
                delay(40)
            }
        }
    }

    private fun startLightning() {
        stopEffect(); tvStatus.text = "⛈️ Éclair"
        effectJob = scope.launch {
            while (isActive) {
                setColor(255, 255, 255); setBrightness(254)
                delay((30..100).random().toLong())
                setPower(false); delay((50..200).random().toLong())
                setPower(true); delay((1000..4000).random().toLong())
            }
        }
    }

    private fun startFire() {
        stopEffect(); tvStatus.text = "🔥 Feu"
        effectJob = scope.launch {
            while (isActive) {
                setColor((200..255).random(), (20..70).random(), 0)
                setBrightness((80..254).random())
                delay((40..120).random().toLong())
            }
        }
    }

    private fun startPolice() {
        stopEffect(); tvStatus.text = "🚨 Police"
        effectJob = scope.launch {
            while (isActive) {
                setColor(255, 0, 0); delay(150)
                setPower(false); delay(80); setPower(true); delay(150)
                setPower(false); delay(80); setPower(true); delay(200)
                setColor(0, 0, 255); delay(150)
                setPower(false); delay(80); setPower(true); delay(150)
                setPower(false); delay(80); setPower(true); delay(200)
            }
        }
    }

    private fun startParty() {
        stopEffect(); tvStatus.text = "🎉 Fête"
        val colors = listOf(
            Triple(255,0,0), Triple(0,255,0), Triple(0,0,255),
            Triple(255,255,0), Triple(255,0,255), Triple(0,255,255),
            Triple(255,128,0), Triple(128,0,255)
        )
        effectJob = scope.launch {
            while (isActive) {
                val c = colors.random()
                setColor(c.first, c.second, c.third)
                setBrightness((150..254).random())
                delay((100..400).random().toLong())
            }
        }
    }

    private fun startMusicSync() {
        stopEffect(); stopMusicSync()
        tvStatus.text = "🎵 Sync musique ON"
        musicJob = scope.launch {
            val bufSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
            var ar: AudioRecord? = null
            try {
                ar = AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                ar.startRecording()
                val buf = ShortArray(bufSize)
                var smoothed = 0f
                while (isActive) {
                    val read = ar.read(buf, 0, bufSize)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) { val s = buf[i] / 32768f; sum += s * s }
                        val rms = sqrt(sum / read).toFloat().coerceIn(0f, 1f)
                        smoothed = 0.3f * smoothed + 0.7f * rms
                        val bright = (smoothed * 2.5f * 254f).toInt().coerceIn(5, 254)
                        val (r, g, b) = when {
                            smoothed < 0.15f -> Triple(0, 50, 255)
                            smoothed < 0.35f -> Triple(0, 255, 150)
                            smoothed < 0.6f  -> Triple(255, 200, 0)
                            else             -> Triple(255, 0, 0)
                        }
                        setBrightness(bright); setColor(r, g, b)
                    }
                    delay(50)
                }
            } catch (e: SecurityException) {
                runOnUiThread { tvStatus.text = "❌ Permission micro refusée" }
            } finally { ar?.stop(); ar?.release() }
        }
    }

    private fun stopMusicSync() {
        musicJob?.cancel(); musicJob = null
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
