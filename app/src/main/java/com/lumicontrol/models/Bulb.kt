package com.lumicontrol.models

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "bulbs")
data class Bulb(
    @PrimaryKey
    val macAddress: String,
    var name: String = "Ampoule",
    var type: BulbType = BulbType.GENERIC,
    var isOn: Boolean = false,
    var brightness: Int = 100,
    var colorR: Int = 255,
    var colorG: Int = 200,
    var colorB: Int = 100,
    var isFavorite: Boolean = false,
    var groupId: Long = -1L,
    var roomName: String = "",
    var lastConnected: Long = 0L
) {
    @Ignore var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    @Ignore var signalStrength: Int = 0

    fun getColor() = android.graphics.Color.rgb(colorR, colorG, colorB)
    fun connectionLabel() = when (connectionState) {
        ConnectionState.CONNECTED    -> "Connecté"
        ConnectionState.CONNECTING   -> "Connexion..."
        ConnectionState.DISCONNECTED -> "Déconnecté"
        ConnectionState.ERROR        -> "Erreur"
    }
}

enum class BulbType { GENERIC, MIPOW, YEELIGHT, SENGLED, GOVEE, CUSTOM }
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
