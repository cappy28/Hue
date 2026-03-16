package com.lumicontrol.models

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Modèle représentant une ampoule connectée.
 * Stocké en base de données Room pour persistence locale.
 */
@Parcelize
@Entity(tableName = "bulbs")
data class Bulb(
    @PrimaryKey
    val macAddress: String,          // Adresse MAC unique — clé primaire

    var name: String = "Ampoule",    // Nom personnalisé par l'utilisateur
    var type: BulbType = BulbType.GENERIC,

    // État actuel
    var isOn: Boolean = false,
    var brightness: Int = 100,       // 0–100 %
    var colorR: Int = 255,
    var colorG: Int = 200,
    var colorB: Int = 100,
    var colorTemperature: Int = 4000, // Kelvin (2700K–6500K)

    // Métadonnées
    var isFavorite: Boolean = false,
    var groupId: Long = -1L,         // -1 = sans groupe
    var roomName: String = "",        // Ex : "Salon", "Chambre"
    var lastConnected: Long = 0L,    // Timestamp dernière connexion

    // État de connexion (non persisté, géré en mémoire)
    @androidx.room.Ignore
    var connectionState: ConnectionState = ConnectionState.DISCONNECTED,

    @androidx.room.Ignore
    var signalStrength: Int = 0       // RSSI en dBm
) : Parcelable {

    /**
     * Retourne la couleur actuelle sous forme d'int Android Color.
     */
    fun getColor(): Int = android.graphics.Color.rgb(colorR, colorG, colorB)

    /**
     * Retourne un label lisible pour l'état de connexion.
     */
    fun connectionLabel(): String = when (connectionState) {
        ConnectionState.CONNECTED    -> "Connecté"
        ConnectionState.CONNECTING   -> "Connexion..."
        ConnectionState.DISCONNECTED -> "Déconnecté"
        ConnectionState.ERROR        -> "Erreur"
    }

    companion object {
        // Icône par défaut selon le type de pièce
        fun iconForRoom(room: String): Int = when (room.lowercase()) {
            "salon", "living"    -> com.lumicontrol.R.drawable.ic_room_living
            "chambre", "bedroom" -> com.lumicontrol.R.drawable.ic_room_bedroom
            "cuisine", "kitchen" -> com.lumicontrol.R.drawable.ic_room_kitchen
            "bureau", "office"   -> com.lumicontrol.R.drawable.ic_room_office
            else                 -> com.lumicontrol.R.drawable.ic_bulb
        }
    }
}

// ─────────────────────────────────────────────
// Énumérations associées
// ─────────────────────────────────────────────

/**
 * Types d'ampoules supportés avec leurs profils GATT.
 */
enum class BulbType {
    GENERIC,      // Ampoule BLE générique (protocole commun)
    MIPOW,        // MIPOW PLAYBULB
    YEELIGHT,     // Xiaomi YeeLight BLE
    SENGLED,      // Sengled Element BLE
    GOVEE,        // Govee BLE
    CUSTOM        // Profil personnalisé utilisateur
}

/**
 * États de connexion Bluetooth.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
