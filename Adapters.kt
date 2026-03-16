package com.lumicontrol.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumicontrol.databinding.ItemBulbCardBinding
import com.lumicontrol.databinding.ItemScanResultBinding
import com.lumicontrol.models.Bulb
import com.lumicontrol.models.ConnectionState

// ══════════════════════════════════════════
// BulbCardAdapter — Grille des ampoules
// ══════════════════════════════════════════

/**
 * Adaptateur pour la grille des ampoules du dashboard.
 * Utilise ListAdapter + DiffUtil pour des mises à jour efficaces.
 */
class BulbCardAdapter(
    private val onBulbClick: (Bulb) -> Unit,
    private val onPowerToggle: (Bulb, Boolean) -> Unit,
    private val onFavorite: (Bulb) -> Unit,
    private val onLongClick: (Bulb) -> Unit
) : ListAdapter<Bulb, BulbCardAdapter.ViewHolder>(BulbDiff()) {

    inner class ViewHolder(private val binding: ItemBulbCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(bulb: Bulb) {
            binding.apply {
                tvBulbName.text = bulb.name
                tvRoomName.text = bulb.roomName.ifEmpty { "Sans pièce" }

                // Indicateur de connexion
                val connected = bulb.connectionState == ConnectionState.CONNECTED
                viewConnectionIndicator.setBackgroundResource(
                    if (connected) com.lumicontrol.R.drawable.circle_green
                    else com.lumicontrol.R.drawable.circle_gray
                )
                tvConnectionState.text = bulb.connectionLabel()

                // Aperçu couleur
                viewColorPreview.setBackgroundColor(bulb.getColor())
                viewColorPreview.alpha = if (bulb.isOn) 1f else 0.3f

                // Luminosité texte
                tvBrightness.text = "${bulb.brightness}%"

                // Toggle on/off
                switchPower.isChecked = bulb.isOn
                switchPower.setOnCheckedChangeListener { _, checked ->
                    onPowerToggle(bulb, checked)
                }

                // Favori
                btnFavorite.setImageResource(
                    if (bulb.isFavorite) com.lumicontrol.R.drawable.ic_star_filled
                    else com.lumicontrol.R.drawable.ic_star_outline
                )
                btnFavorite.setOnClickListener { onFavorite(bulb) }

                // Clic principal → contrôle avancé
                root.setOnClickListener { onBulbClick(bulb) }
                root.setOnLongClickListener { onLongClick(bulb); true }

                // RSSI signal
                tvSignal.text = if (bulb.signalStrength != 0) "${bulb.signalStrength} dBm" else ""
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemBulbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))
}

private class BulbDiff : DiffUtil.ItemCallback<Bulb>() {
    override fun areItemsTheSame(a: Bulb, b: Bulb) = a.macAddress == b.macAddress
    override fun areContentsTheSame(a: Bulb, b: Bulb) = a == b
}

// ══════════════════════════════════════════
// ScanResultAdapter — Résultats du scan BLE
// ══════════════════════════════════════════

class ScanResultAdapter(
    private val onConnect: (Bulb) -> Unit,
    private val onHistory: (Bulb) -> Unit
) : RecyclerView.Adapter<ScanResultAdapter.ViewHolder>() {

    private val scanItems = mutableListOf<Bulb>()
    private val historyItems = mutableListOf<Bulb>()
    private val allItems = mutableListOf<ScanItem>()

    sealed class ScanItem {
        data class Header(val title: String) : ScanItem()
        data class BulbItem(val bulb: Bulb, val isHistory: Boolean) : ScanItem()
    }

    fun submitScanList(bulbs: List<Bulb>) {
        scanItems.clear()
        scanItems.addAll(bulbs)
        rebuildList()
    }

    fun submitHistoryList(bulbs: List<Bulb>) {
        historyItems.clear()
        historyItems.addAll(bulbs)
        rebuildList()
    }

    private fun rebuildList() {
        allItems.clear()
        if (scanItems.isNotEmpty()) {
            allItems.add(ScanItem.Header("Appareils détectés"))
            scanItems.forEach { allItems.add(ScanItem.BulbItem(it, false)) }
        }
        if (historyItems.isNotEmpty()) {
            allItems.add(ScanItem.Header("Ampoules connues"))
            historyItems.forEach { allItems.add(ScanItem.BulbItem(it, true)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (allItems[position]) {
        is ScanItem.Header  -> 0
        is ScanItem.BulbItem -> 1
    }

    inner class ViewHolder(private val binding: ItemScanResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ScanItem.BulbItem) {
            val bulb = item.bulb
            binding.apply {
                tvName.text = bulb.name
                tvMac.text = bulb.macAddress
                tvType.text = bulb.type.name
                tvRssi.text = if (bulb.signalStrength != 0) "${bulb.signalStrength} dBm" else ""
                btnConnect.text = if (item.isHistory) "Reconnecter" else "Connecter"
                btnConnect.setOnClickListener {
                    if (item.isHistory) onHistory(bulb) else onConnect(bulb)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemScanResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (allItems[position] as? ScanItem.BulbItem)?.let { holder.bind(it) }
    }

    override fun getItemCount() = allItems.size
}

// ══════════════════════════════════════════════════════════
// ColorUtils — Utilitaires de conversion de couleurs
// ══════════════════════════════════════════════════════════

package com.lumicontrol.utils

import android.graphics.Color

object ColorUtils {

    /** Convertit un int Color Android en composantes RGB (0–255). */
    fun colorToRgb(color: Int) = Triple(Color.red(color), Color.green(color), Color.blue(color))

    /** Convertit des composantes HSV en RGB. */
    fun hsvToRgb(hue: Float, sat: Float, value: Float): Triple<Int, Int, Int> {
        val c = Color.HSVToColor(floatArrayOf(hue, sat, value))
        return colorToRgb(c)
    }

    /** Convertit un code hexadécimal (#RRGGBB) en composantes RGB. */
    fun hexToRgb(hex: String): Triple<Int, Int, Int> {
        val color = Color.parseColor(hex)
        return colorToRgb(color)
    }

    /** Convertit des composantes RGB en code hexadécimal. */
    fun rgbToHex(r: Int, g: Int, b: Int): String =
        String.format("#%02X%02X%02X", r, g, b)

    /** Interpole linéairement entre deux couleurs. */
    fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        val r = (Color.red(from) + fraction * (Color.red(to) - Color.red(from))).toInt()
        val g = (Color.green(from) + fraction * (Color.green(to) - Color.green(from))).toInt()
        val b = (Color.blue(from) + fraction * (Color.blue(to) - Color.blue(from))).toInt()
        return Color.rgb(r, g, b)
    }

    /** Génère une liste de couleurs formant un arc-en-ciel. */
    fun generateRainbow(steps: Int): List<Triple<Int, Int, Int>> =
        (0 until steps).map { i ->
            val hue = 360f * i / steps
            hsvToRgb(hue, 1f, 1f)
        }

    /** Retourne une couleur contrastée (noir/blanc) pour un fond donné. */
    fun contrastColor(background: Int): Int {
        val luminance = 0.299 * Color.red(background) +
                        0.587 * Color.green(background) +
                        0.114 * Color.blue(background)
        return if (luminance > 128) Color.BLACK else Color.WHITE
    }
}

// ══════════════════════════════════════════════════════════
// Widget Android
// ══════════════════════════════════════════════════════════

package com.lumicontrol.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lumicontrol.R
import com.lumicontrol.bluetooth.BleManager

/**
 * Widget d'écran d'accueil pour contrôle rapide.
 * Affiche un bouton ON/OFF global et l'état des ampoules.
 */
class LumiWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_ALL_ON  = "com.lumicontrol.WIDGET_ALL_ON"
        const val ACTION_ALL_OFF = "com.lumicontrol.WIDGET_ALL_OFF"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val ble = BleManager.getInstance(context)
        when (intent.action) {
            ACTION_ALL_ON  -> ble.broadcastCommand(255, 200, 100, 100, true)
            ACTION_ALL_OFF -> ble.broadcastCommand(0, 0, 0, 0, false)
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_lumi)

        // Bouton Tout allumer
        val onIntent = Intent(context, LumiWidgetProvider::class.java).apply {
            action = ACTION_ALL_ON
        }
        val onPending = android.app.PendingIntent.getBroadcast(
            context, 0, onIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_widget_on, onPending)

        // Bouton Tout éteindre
        val offIntent = Intent(context, LumiWidgetProvider::class.java).apply {
            action = ACTION_ALL_OFF
        }
        val offPending = android.app.PendingIntent.getBroadcast(
            context, 1, offIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_widget_off, offPending)

        // Statut
        val connected = BleManager.getInstance(context).connectedBulbs.value.size
        views.setTextViewText(R.id.tv_widget_status, "$connected ampoule(s)")

        manager.updateAppWidget(widgetId, views)
    }
}

// ══════════════════════════════════════════════════════════
// Application class
// ══════════════════════════════════════════════════════════

package com.lumicontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Classe Application de LumiControl.
 * Initialise les canaux de notification et les singletons.
 */
class LumiControlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Canal service BLE
            manager.createNotificationChannel(
                NotificationChannel(
                    "ble_service_channel",
                    "Service Bluetooth",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Maintient la connexion Bluetooth active"
                    setShowBadge(false)
                }
            )

            // Canal rappels/planifications
            manager.createNotificationChannel(
                NotificationChannel(
                    "schedule_channel",
                    "Planifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Rappels et automatisations programmées"
                }
            )
        }
    }
}
