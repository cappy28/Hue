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
import kotlin.math.sqrt

class MusicSyncService : Service() {

    companion object {
        const val ACTION_START = "com.lumicontrol.MUSIC_SYNC_START"
        const val ACTION_STOP  = "com.lumicontrol.MUSIC_SYNC_STOP"
        var isRunning = false
        private const val CHANNEL_ID = "music_sync_channel"
        private const val NOTIF_ID = 2001
        private const val SAMPLE_RATE = 44100
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private lateinit var bleManager: BleManager
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

    private fun startSync() {
        if (isRunning) return
        startForeground(NOTIF_ID, buildNotification())
        isRunning = true

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioRecord?.startRecording()
        } catch (e: SecurityException) {
            stopSelf(); return
        }

        scope.launch {
            val buffer = ShortArray(bufferSize)
            while (isRunning && isActive) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val s = buffer[i].toFloat() / Short.MAX_VALUE
                        sum += s * s
                    }
                    val rms = sqrt(sum / read).toFloat().coerceIn(0f, 1f)
                    smoothedLevel = 0.3f * smoothedLevel + 0.7f * rms
                    val brightness = (smoothedLevel * 150f * 100f).toInt().coerceIn(5, 100)
                    bleManager.connectedBulbs.value.forEach { bulb ->
                        bleManager.setBrightness(bulb.macAddress, brightness)
                    }
                }
                delay(50)
            }
        }
    }

    private fun stopSync() {
        isRunning = false
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Sync musicale", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MusicSyncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎵 Sync musicale active")
            .setContentText("Les lumières réagissent à la musique")
            .setSmallIcon(R.drawable.ic_bulb)
            .addAction(R.drawable.ic_bulb, "Arrêter", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() { super.onDestroy(); stopSync() }
}
