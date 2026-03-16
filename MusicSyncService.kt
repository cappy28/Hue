package com.lumicontrol.services

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lumicontrol.R
import com.lumicontrol.bluetooth.BleManager
import com.lumicontrol.ui.activities.MainActivity
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════
 * MusicSyncService — Synchronisation lumière / musique
 * ════════════════════════════════════════════════════════════
 *
 * Service de premier plan qui :
 *  1. Capture l'audio ambiant via le microphone (AudioRecord)
 *  2. Calcule le niveau sonore (RMS)
 *  3. Mappe le niveau sur la luminosité et/ou la couleur
 *  4. Envoie les commandes BLE en temps réel
 *
 * La synchronisation est basée sur l'amplitude du son.
 * Pour une sync plus avancée, une FFT peut être ajoutée pour
 * détecter les graves/médiums/aigus et mapper sur les couleurs.
 */
class MusicSyncService : Service() {

    companion object {
        private const val TAG = "MusicSyncService"
        private const val CHANNEL_ID = "music_sync_channel"
        private const val NOTIF_ID = 2001

        const val ACTION_START = "com.lumicontrol.MUSIC_SYNC_START"
        const val ACTION_STOP  = "com.lumicontrol.MUSIC_SYNC_STOP"

        // Paramètres audio
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SIZE_FACTOR = 2

        var isRunning = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private lateinit var bleManager: BleManager

    // ─── Paramètres de sync ───
    private var colorMode = ColorMode.BRIGHTNESS_ONLY
    private var sensitivity = 1.5f   // Multiplicateur de sensibilité
    private var smoothingFactor = 0.3f // Lissage temporel (0=brut, 1=figé)
    private var smoothedLevel = 0f

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSync()
            ACTION_STOP  -> stopSync()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ════════════════════════════════════════════════════════════
    // DÉMARRAGE / ARRÊT
    // ════════════════════════════════════════════════════════════

    private fun startSync() {
        if (isRunning) return

        startForeground(NOTIF_ID, buildNotification())
        isRunning = true

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * BUFFER_SIZE_FACTOR

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioRecord?.startRecording()
            Log.d(TAG, "Enregistrement audio démarré")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission RECORD_AUDIO manquante")
            stopSelf()
            return
        }

        scope.launch {
            processMusicSync(bufferSize)
        }
    }

    private fun stopSync() {
        isRunning = false
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Synchronisation musicale arrêtée")
    }

    // ════════════════════════════════════════════════════════════
    // TRAITEMENT AUDIO
    // ════════════════════════════════════════════════════════════

    /**
     * Boucle principale de traitement audio.
     * Lit les buffers audio, calcule le niveau RMS, puis envoie
     * les commandes BLE.
     */
    private suspend fun processMusicSync(bufferSize: Int) {
        val buffer = ShortArray(bufferSize)

        while (isRunning && isActive) {
            val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
            if (read <= 0) {
                delay(10)
                continue
            }

            // Calcul RMS (niveau sonore moyen)
            val rms = calculateRMS(buffer, read)

            // Lissage temporel pour éviter le clignotement brutal
            smoothedLevel = smoothingFactor * smoothedLevel + (1 - smoothingFactor) * rms

            // Mapper sur brightness (0–100)
            val brightness = (smoothedLevel * sensitivity * 100f)
                .coerceIn(5f, 100f)
                .toInt()

            // Mapper sur couleur si activé
            when (colorMode) {
                ColorMode.BRIGHTNESS_ONLY -> {
                    applyToBulbs { mac -> bleManager.setBrightness(mac, brightness) }
                }
                ColorMode.COLOR_BY_LEVEL -> {
                    val (r, g, b) = levelToColor(smoothedLevel)
                    applyToBulbs { mac ->
                        bleManager.setColor(mac, r, g, b)
                        bleManager.setBrightness(mac, brightness)
                    }
                }
                ColorMode.RAINBOW -> {
                    val hue = (smoothedLevel * 360f) % 360f
                    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                    applyToBulbs { mac ->
                        bleManager.setColor(mac,
                            android.graphics.Color.red(color),
                            android.graphics.Color.green(color),
                            android.graphics.Color.blue(color)
                        )
                    }
                }
            }

            // 50ms entre chaque update → ~20 FPS
            delay(50)
        }
    }

    /** Calcule la valeur RMS (Root Mean Square) d'un buffer audio. */
    private fun calculateRMS(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            val sample = buffer[i].toFloat() / Short.MAX_VALUE
            sum += sample * sample
        }
        return sqrt(sum / size).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Mappe un niveau sonore (0.0–1.0) sur une couleur.
     * Bas = bleu calme → Haut = rouge intense.
     */
    private fun levelToColor(level: Float): Triple<Int, Int, Int> {
        return when {
            level < 0.3f -> Triple(0, 50, 255)     // Bleu calme
            level < 0.6f -> Triple(0, 255, 100)     // Vert moyen
            level < 0.8f -> Triple(255, 200, 0)     // Jaune/orange actif
            else         -> Triple(255, 0, 50)       // Rouge intense
        }
    }

    private inline fun applyToBulbs(command: (String) -> Unit) {
        bleManager.connectedBulbs.value.forEach { bulb ->
            if (bulb.connectionState == com.lumicontrol.models.ConnectionState.CONNECTED) {
                command(bulb.macAddress)
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Synchronisation musicale",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Contrôle des lumières en rythme avec la musique"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MusicSyncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎵 Sync musicale active")
            .setContentText("Les lumières réagissent à la musique")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, "Arrêter", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSync()
    }

    enum class ColorMode {
        BRIGHTNESS_ONLY,  // Luminosité seule suit le son
        COLOR_BY_LEVEL,   // Couleur selon l'intensité
        RAINBOW           // Arc-en-ciel selon la fréquence
    }
}

// ══════════════════════════════════════════════════════════
// SchedulerService — Service de planification horaire
// ══════════════════════════════════════════════════════════

package com.lumicontrol.services

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lumicontrol.R
import com.lumicontrol.bluetooth.BleManager
import com.lumicontrol.data.database.AppDatabase
import com.lumicontrol.models.ScheduleAction
import kotlinx.coroutines.*
import java.util.*

/**
 * Service gérant les planifications automatiques.
 * Vérifie toutes les minutes si une action doit être déclenchée.
 */
class SchedulerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bleManager: BleManager

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        startForeground(2002, buildNotification())
        startChecking()
    }

    private fun startChecking() {
        scope.launch {
            while (isActive) {
                checkSchedules()
                delay(60_000L) // Vérification toutes les minutes
            }
        }
    }

    private suspend fun checkSchedules() {
        val db = AppDatabase.getInstance(this@SchedulerService)
        val schedules = db.scheduleDao().getEnabledSchedules()

        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val currentMin  = cal.get(Calendar.MINUTE)
        val currentDay  = (cal.get(Calendar.DAY_OF_WEEK) - 2 + 7) % 7 // 0=Lun ... 6=Dim

        schedules.collect { list ->
            list.filter { schedule ->
                schedule.hour == currentHour &&
                schedule.minute == currentMin &&
                (schedule.repeatDays.isEmpty() || currentDay in schedule.repeatDays)
            }.forEach { schedule ->
                Log.d("Scheduler", "Déclenchement : ${schedule.name}")
                executeSchedule(schedule)

                // Si non répétitif, désactiver après exécution
                if (schedule.repeatDays.isEmpty()) {
                    db.scheduleDao().setEnabled(schedule.id, false)
                }
            }
        }
    }

    private fun executeSchedule(schedule: com.lumicontrol.models.Schedule) {
        val macs = if (schedule.targetMac == "*")
            bleManager.connectedBulbs.value.map { it.macAddress }
        else listOf(schedule.targetMac)

        macs.forEach { mac ->
            when (schedule.action) {
                ScheduleAction.TURN_ON    -> bleManager.setPower(mac, true)
                ScheduleAction.TURN_OFF   -> bleManager.setPower(mac, false)
                ScheduleAction.TOGGLE     -> {
                    val on = bleManager.connectedBulbs.value.find { it.macAddress == mac }?.isOn ?: false
                    bleManager.setPower(mac, !on)
                }
                ScheduleAction.DIM_50     -> bleManager.setBrightness(mac, 50)
                ScheduleAction.FULL_BRIGHT -> bleManager.setBrightness(mac, 100)
                ScheduleAction.APPLY_SCENE -> {
                    // Appliquer une scène (récupérer depuis DB)
                    scope.launch {
                        val scene = AppDatabase.getInstance(this@SchedulerService)
                            .sceneDao().getSceneById(schedule.sceneId)
                        scene?.bulbStates?.forEach { state ->
                            bleManager.setColor(mac, state.r, state.g, state.b)
                            bleManager.setBrightness(mac, state.brightness)
                        }
                    }
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "scheduler_channel",
                "Planificateur",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, "scheduler_channel")
            .setContentTitle("LumiControl")
            .setContentText("Planificateur actif")
            .setSmallIcon(R.drawable.ic_schedule)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
