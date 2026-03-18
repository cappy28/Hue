package com.lumicontrol.ui.activities

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lumicontrol.R
import kotlinx.coroutines.*
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var hue: HueController
    private lateinit var ir: IrController
    private lateinit var tvStatus: TextView
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekRed: SeekBar
    private lateinit var seekGreen: SeekBar
    private lateinit var seekBlue: SeekBar
    private lateinit var viewColor: android.view.View

    private var effectJob: Job? = null
    private var musicJob: Job? = null
    private var scanJob: Job? = null
    private var lastScannedCode = 0
    private var lastScannedFreq = 38000
    private var scanningFor = "on"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hue.scan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hue = HueController(this) { msg -> runOnUiThread { tvStatus.text = msg } }
        ir  = IrController(this)

        tvStatus       = findViewById(R.id.tvStatus)
        seekBrightness = findViewById(R.id.seekBrightness)
        seekRed        = findViewById(R.id.seekRed)
        seekGreen      = findViewById(R.id.seekGreen)
        seekBlue       = findViewById(R.id.seekBlue)
        viewColor      = findViewById(R.id.viewColor)

        setupTabs()
        setupHuePanel()
        setupBaltimorePanel()
        setupWallLightPanel()

        tvStatus.text = if (ir.hasIR()) "✅ IR disponible" else "⚠️ Pas d'IR"
    }

    // ══════════════════════════════════════════
    // ONGLETS
    // ══════════════════════════════════════════

    private fun setupTabs() {
        val panelHue       = findViewById<android.view.View>(R.id.panelHue)
        val panelBaltimore = findViewById<android.view.View>(R.id.panelBaltimore)
        val panelWallLight = findViewById<android.view.View>(R.id.panelWallLight)
        val tabHue         = findViewById<TextView>(R.id.tabHue)
        val tabBaltimore   = findViewById<TextView>(R.id.tabBaltimore)
        val tabWallLight   = findViewById<TextView>(R.id.tabWallLight)

        fun show(i: Int) {
            panelHue.visibility       = if (i == 0) android.view.View.VISIBLE else android.view.View.GONE
            panelBaltimore.visibility = if (i == 1) android.view.View.VISIBLE else android.view.View.GONE
            panelWallLight.visibility = if (i == 2) android.view.View.VISIBLE else android.view.View.GONE
            val on = "#FF6B35"; val off = "#333355"
            tabHue.setBackgroundColor(android.graphics.Color.parseColor(if (i == 0) on else off))
            tabBaltimore.setBackgroundColor(android.graphics.Color.parseColor(if (i == 1) on else off))
            tabWallLight.setBackgroundColor(android.graphics.Color.parseColor(if (i == 2) on else off))
        }

        show(0)
        tabHue.setOnClickListener { show(0) }
        tabBaltimore.setOnClickListener { show(1) }
        tabWallLight.setOnClickListener { show(2) }
    }

    // ══════════════════════════════════════════
    // PANEL HUE
    // ══════════════════════════════════════════

    private fun setupHuePanel() {
        findViewById<Button>(R.id.btnScan).setOnClickListener { checkPermissionsAndScan() }
        findViewById<Button>(R.id.btnHueOn).setOnClickListener  { hue.setPower(true) }
        findViewById<Button>(R.id.btnHueOff).setOnClickListener { hue.setPower(false) }

        findViewById<Button>(R.id.hueRed).setOnClickListener    { stopEffect(); hue.setColor(255,0,0) }
        findViewById<Button>(R.id.hueGreen).setOnClickListener  { stopEffect(); hue.setColor(0,255,0) }
        findViewById<Button>(R.id.hueBlue).setOnClickListener   { stopEffect(); hue.setColor(0,0,255) }
        findViewById<Button>(R.id.hueWhite).setOnClickListener  { stopEffect(); hue.setColor(255,255,255) }
        findViewById<Button>(R.id.hueOrange).setOnClickListener { stopEffect(); hue.setColor(255,100,0) }
        findViewById<Button>(R.id.huePink).setOnClickListener   { stopEffect(); hue.setColor(255,0,150) }
        findViewById<Button>(R.id.huePurple).setOnClickListener { stopEffect(); hue.setColor(128,0,255) }
        findViewById<Button>(R.id.hueYellow).setOnClickListener { stopEffect(); hue.setColor(255,220,0) }

        findViewById<Button>(R.id.hueWarm).setOnClickListener  { stopEffect(); hue.setTemperature(500) }
        findViewById<Button>(R.id.hueDay).setOnClickListener   { stopEffect(); hue.setTemperature(300) }
        findViewById<Button>(R.id.hueCool).setOnClickListener  { stopEffect(); hue.setTemperature(153) }

        seekBrightness.max = 254
        seekBrightness.progress = 254
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) hue.setBrightness(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        val cl = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    stopEffect()
                    val r = seekRed.progress
                    val g = seekGreen.progress
                    val b = seekBlue.progress
                    hue.setColor(r, g, b)
                    viewColor.setBackgroundColor(android.graphics.Color.rgb(r, g, b))
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        seekRed.setOnSeekBarChangeListener(cl)
        seekGreen.setOnSeekBarChangeListener(cl)
        seekBlue.setOnSeekBarChangeListener(cl)

        findViewById<Button>(R.id.hueFade).setOnClickListener      { startFade() }
        findViewById<Button>(R.id.hueStrobe).setOnClickListener    { startStrobe() }
        findViewById<Button>(R.id.hueRainbow).setOnClickListener   { startRainbow() }
        findViewById<Button>(R.id.hueBreath).setOnClickListener    { startBreathing() }
        findViewById<Button>(R.id.hueLightning).setOnClickListener { startLightning() }
        findViewById<Button>(R.id.hueFire).setOnClickListener      { startFire() }
        findViewById<Button>(R.id.huePolice).setOnClickListener    { startPolice() }
        findViewById<Button>(R.id.hueParty).setOnClickListener     { startParty() }
        findViewById<Button>(R.id.hueStop).setOnClickListener      { stopEffect() }
        findViewById<Button>(R.id.hueMusicOn).setOnClickListener   { startMusicSync() }
        findViewById<Button>(R.id.hueMusicOff).setOnClickListener  { stopMusicSync() }
    }

    // ══════════════════════════════════════════
    // PANEL BALTIMORE
    // ══════════════════════════════════════════

    private fun setupBaltimorePanel() {
        val buttons = mapOf(
            R.id.balOn      to "on",
            R.id.balOff     to "off",
            R.id.balBrightP to "brightp",
            R.id.balBrightM to "brightm",
            R.id.balRed     to "red",
            R.id.balGreen   to "green",
            R.id.balBlue    to "blue",
            R.id.balWhite   to "white",
            R.id.balOrange  to "orange",
            R.id.balLime    to "lime",
            R.id.balCyan    to "cyan",
            R.id.balWarmW   to "warmw",
            R.id.balPink    to "pink",
            R.id.balPurple  to "purple",
            R.id.balYellow  to "yellow",
            R.id.balMode    to "mode",
            R.id.balNight   to "night",
            R.id.balSpeedP  to "speedp",
            R.id.balSpeedM  to "speedm",
            R.id.balFlash   to "flash",
            R.id.balStrobe  to "strobe",
            R.id.balFade    to "fade",
            R.id.balSmooth  to "smooth",
            R.id.balMusic1  to "music1",
            R.id.balMusic2  to "music2",
            R.id.balMusic3  to "music3",
            R.id.balMusic4  to "music4",
            R.id.balTimer1  to "timer1",
            R.id.balTimer2  to "timer2",
            R.id.balTimer3  to "timer3",
            R.id.balTimer4  to "timer4"
        )

        buttons.forEach { (id, key) ->
            findViewById<Button>(id).setOnClickListener { sendBalCode(key) }
            findViewById<Button>(id).setOnLongClickListener {
                scanningFor = key
                startBaltimoreScan()
                true
            }
        }

        findViewById<Button>(R.id.balScan).setOnClickListener { startBaltimoreScan() }
        findViewById<Button>(R.id.balScanStop).setOnClickListener { stopBaltimoreScan() }
    }

    private fun sendBalCode(key: String) {
        val code = ir.getBalCode(key)
        val freq = ir.getBalFreq(key)
        if (code != 0) ir.sendWithFreq(code, freq)
        else {
            scanningFor = key
            tvStatus.text = "⚠️ Maintiens appuyé ce bouton pour le programmer !"
        }
    }

    private fun startBaltimoreScan() {
        scanJob?.cancel()
        tvStatus.text = "🔍 Scanner pour '$scanningFor' — Regarde la lampe !\nQuand elle réagit → STOP !"
        scanJob = hue.scope.launch {
            for (freq in ir.irScanFreqs()) {
                for (code in ir.irScanCodes()) {
                    lastScannedCode = code
                    lastScannedFreq = freq
                    runOnUiThread {
                        tvStatus.text = "📡 ${freq}Hz — 0x${code.toString(16).uppercase()}"
                    }
                    ir.sendWithFreq(code, freq)
                    delay(800)
                }
            }
            runOnUiThread { tvStatus.text = "❌ Scan terminé sans réponse — réessaie" }
        }
    }

    private fun stopBaltimoreScan() {
        scanJob?.cancel()
        ir.saveBalCode(scanningFor, lastScannedCode)
        ir.saveBalFreq(scanningFor, lastScannedFreq)
        tvStatus.text = "✅ '$scanningFor' sauvegardé !\nCode: 0x${lastScannedCode.toString(16).uppercase()} — ${lastScannedFreq}Hz"
    }

    // ══════════════════════════════════════════
    // PANEL WALL LIGHT
    // ══════════════════════════════════════════

    private fun setupWallLightPanel() {
        findViewById<Button>(R.id.wlOn).setOnClickListener       { ir.send(ir.WL_ON) }
        findViewById<Button>(R.id.wlOff).setOnClickListener      { ir.send(ir.WL_OFF) }
        findViewById<Button>(R.id.wlBrightP).setOnClickListener  { ir.send(ir.WL_BRIGHT_P) }
        findViewById<Button>(R.id.wlBrightM).setOnClickListener  { ir.send(ir.WL_BRIGHT_M) }
        findViewById<Button>(R.id.wlRed).setOnClickListener      { ir.send(ir.WL_RED) }
        findViewById<Button>(R.id.wlGreen).setOnClickListener    { ir.send(ir.WL_GREEN) }
        findViewById<Button>(R.id.wlBlue).setOnClickListener     { ir.send(ir.WL_BLUE) }
        findViewById<Button>(R.id.wlWhite).setOnClickListener    { ir.send(ir.WL_WHITE) }
        findViewById<Button>(R.id.wlOrange).setOnClickListener   { ir.send(ir.WL_ORANGE) }
        findViewById<Button>(R.id.wlLime).setOnClickListener     { ir.send(ir.WL_LIME) }
        findViewById<Button>(R.id.wlCyan).setOnClickListener     { ir.send(ir.WL_CYAN) }
        findViewById<Button>(R.id.wlPink).setOnClickListener     { ir.send(ir.WL_PINK) }
        findViewById<Button>(R.id.wlFlash).setOnClickListener    { ir.send(ir.WL_FLASH) }
        findViewById<Button>(R.id.wlStrobe).setOnClickListener   { ir.send(ir.WL_STROBE) }
        findViewById<Button>(R.id.wlFade).setOnClickListener     { ir.send(ir.WL_FADE) }
        findViewById<Button>(R.id.wlSmooth).setOnClickListener   { ir.send(ir.WL_SMOOTH) }
        findViewById<Button>(R.id.wlSpeedP).setOnClickListener   { ir.send(ir.WL_SPEED_P) }
        findViewById<Button>(R.id.wlSpeedM).setOnClickListener   { ir.send(ir.WL_SPEED_M) }
        findViewById<Button>(R.id.wlMusic1).setOnClickListener   { ir.send(ir.WL_MUSIC1) }
        findViewById<Button>(R.id.wlMusic2).setOnClickListener   { ir.send(ir.WL_MUSIC2) }
        findViewById<Button>(R.id.wlMusic3).setOnClickListener   { ir.send(ir.WL_MUSIC3) }
        findViewById<Button>(R.id.wlMusic4).setOnClickListener   { ir.send(ir.WL_MUSIC4) }
    }

    // ══════════════════════════════════════════
    // EFFETS HUE
    // ══════════════════════════════════════════

    private fun stopEffect() { effectJob?.cancel(); effectJob = null }

    private fun startFade() {
        stopEffect(); tvStatus.text = "🌅 Fondu"
        effectJob = hue.scope.launch {
            var b = 254; var d = -1
            while (isActive) {
                hue.setBrightness(b); b += d * 6
                if (b <= 5) d = 1; if (b >= 254) d = -1
                delay(60)
            }
        }
    }

    private fun startStrobe() {
        stopEffect(); tvStatus.text = "⚡ Stroboscope"
        effectJob = hue.scope.launch {
            var on = true
            while (isActive) { hue.setPower(on); on = !on; delay(80) }
        }
    }

    private fun startRainbow() {
        stopEffect(); tvStatus.text = "🌈 Arc-en-ciel"
        effectJob = hue.scope.launch {
            var hueVal = 0f
            while (isActive) {
                val c = android.graphics.Color.HSVToColor(floatArrayOf(hueVal, 1f, 1f))
                hue.setColor(
                    android.graphics.Color.red(c),
                    android.graphics.Color.green(c),
                    android.graphics.Color.blue(c)
                )
                hueVal = (hueVal + 2f) % 360f
                delay(80)
            }
        }
    }

    private fun startBreathing() {
        stopEffect(); tvStatus.text = "💨 Respiration"
        effectJob = hue.scope.launch {
            var b = 5; var d = 1
            while (isActive) {
                hue.setBrightness(b); b += d * 4
                if (b >= 254) d = -1; if (b <= 5) d = 1
                delay(40)
            }
        }
    }

    private fun startLightning() {
        stopEffect(); tvStatus.text = "⛈️ Éclair"
        effectJob = hue.scope.launch {
            while (isActive) {
                hue.setColor(255, 255, 255)
                hue.setBrightness(254)
                delay((30..100).random().toLong())
                hue.setPower(false)
                delay((50..200).random().toLong())
                hue.setPower(true)
                delay((1000..4000).random().toLong())
            }
        }
    }

    private fun startFire() {
        stopEffect(); tvStatus.text = "🔥 Feu"
        effectJob = hue.scope.launch {
            while (isActive) {
                hue.setColor((200..255).random(), (20..70).random(), 0)
                hue.setBrightness((80..254).random())
                delay((40..120).random().toLong())
            }
        }
    }

    private fun startPolice() {
        stopEffect(); tvStatus.text = "🚨 Police"
        effectJob = hue.scope.launch {
            while (isActive) {
                hue.setColor(255, 0, 0); delay(150)
                hue.setPower(false); delay(80)
                hue.setPower(true); delay(150)
                hue.setPower(false); delay(80)
                hue.setPower(true); delay(200)
                hue.setColor(0, 0, 255); delay(150)
                hue.setPower(false); delay(80)
                hue.setPower(true); delay(150)
                hue.setPower(false); delay(80)
                hue.setPower(true); delay(200)
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
        effectJob = hue.scope.launch {
            while (isActive) {
                val c = colors.random()
                hue.setColor(c.first, c.second, c.third)
                hue.setBrightness((150..254).random())
                delay((100..400).random().toLong())
            }
        }
    }

    // ══════════════════════════════════════════
    // SYNC MUSIQUE
    // ══════════════════════════════════════════

    private fun startMusicSync() {
        stopEffect(); stopMusicSync()
        tvStatus.text = "🎵 Sync musique ON"
        musicJob = hue.scope.launch {
            val bufSize = AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2
            var ar: AudioRecord? = null
            try {
                ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
                ar.startRecording()
                val buf = ShortArray(bufSize)
                var smoothed = 0f
                while (isActive) {
                    val read = ar.read(buf, 0, bufSize)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            val s = buf[i] / 32768f; sum += s * s
                        }
                        val rms = sqrt(sum / read).toFloat().coerceIn(0f, 1f)
                        smoothed = 0.3f * smoothed + 0.7f * rms
                        hue.setBrightness(254)
                        val (r, g, b) = when {
                            smoothed < 0.15f -> Triple(0, 50, 255)
                            smoothed < 0.35f -> Triple(0, 255, 150)
                            smoothed < 0.6f  -> Triple(255, 200, 0)
                            smoothed < 0.8f  -> Triple(255, 80, 0)
                            else             -> Triple(255, 0, 0)
                        }
                        hue.setColor(r, g, b)
                    }
                    delay(50)
                }
            } catch (e: SecurityException) {
                runOnUiThread { tvStatus.text = "❌ Permission micro refusée" }
            } finally {
                ar?.stop(); ar?.release()
            }
        }
    }

    private fun stopMusicSync() { musicJob?.cancel(); musicJob = null }

    // ══════════════════════════════════════════
    // PERMISSIONS
    // ══════════════════════════════════════════

    private fun checkPermissionsAndScan() {
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
        else hue.scan()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEffect(); stopMusicSync(); scanJob?.cancel()
        hue.release()
    }
}

class ScanActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
