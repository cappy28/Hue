package com.lumicontrol.models

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.parcelize.Parcelize

// ══════════════════════════════════════════
// SCÈNE PERSONNALISÉE
// ══════════════════════════════════════════

/**
 * Une scène est un état sauvegardé pour une ou plusieurs ampoules.
 * Peut être partagée via export JSON.
 */
@Parcelize
@Entity(tableName = "scenes")
@TypeConverters(SceneConverters::class)
data class Scene(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    var name: String,                     // Ex : "Soirée cinéma"
    var iconEmoji: String = "✨",          // Emoji représentatif
    var colorHex: String = "#FF6B35",     // Couleur de preview

    // Liste des états : [(macAddress, r, g, b, brightness, isOn)]
    var bulbStates: List<BulbState> = emptyList(),

    var isPreset: Boolean = false,        // Scène système non supprimable
    var isFavorite: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
) : Parcelable {
    /** Exporte la scène en JSON pour partage. */
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): Scene = Gson().fromJson(json, Scene::class.java)

        /** Scènes prédéfinies du système. */
        fun presets() = listOf(
            Scene(
                name = "Relax", iconEmoji = "🧘", colorHex = "#FF8C42", isPreset = true,
                bulbStates = listOf(BulbState("*", 255, 140, 66, 60, true))
            ),
            Scene(
                name = "Lecture", iconEmoji = "📖", colorHex = "#FFF4D6", isPreset = true,
                bulbStates = listOf(BulbState("*", 255, 244, 214, 100, true))
            ),
            Scene(
                name = "Fête", iconEmoji = "🎉", colorHex = "#FF3E9D", isPreset = true,
                bulbStates = listOf(BulbState("*", 255, 62, 157, 100, true))
            ),
            Scene(
                name = "Sommeil", iconEmoji = "😴", colorHex = "#1A0A2E", isPreset = true,
                bulbStates = listOf(BulbState("*", 26, 10, 46, 10, true))
            ),
            Scene(
                name = "Réveil", iconEmoji = "☀️", colorHex = "#FFD700", isPreset = true,
                bulbStates = listOf(BulbState("*", 255, 215, 0, 80, true))
            ),
            Scene(
                name = "Focus", iconEmoji = "🎯", colorHex = "#E8F4FD", isPreset = true,
                bulbStates = listOf(BulbState("*", 232, 244, 253, 100, true))
            ),
            Scene(
                name = "Cinéma", iconEmoji = "🎬", colorHex = "#0D0D0D", isPreset = true,
                bulbStates = listOf(BulbState("*", 13, 13, 13, 5, true))
            ),
            Scene(
                name = "Romantique", iconEmoji = "❤️", colorHex = "#FF1744", isPreset = true,
                bulbStates = listOf(BulbState("*", 255, 23, 68, 30, true))
            )
        )
    }
}

/**
 * État d'une ampoule dans une scène.
 * macAddress = "*" signifie "toutes les ampoules".
 */
@Parcelize
data class BulbState(
    val macAddress: String,
    val r: Int, val g: Int, val b: Int,
    val brightness: Int,
    val isOn: Boolean
) : Parcelable

// ══════════════════════════════════════════
// PLANIFICATION HORAIRE
// ══════════════════════════════════════════

/**
 * Tâche planifiée : allumer/éteindre ou appliquer une scène à une heure précise.
 */
@Parcelize
@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    var name: String = "Planification",
    var isEnabled: Boolean = true,

    var hour: Int,                         // 0–23
    var minute: Int,                       // 0–59

    var repeatDays: Set<Int> = emptySet(), // 0=Lun ... 6=Dim (empty = une seule fois)
    var action: ScheduleAction = ScheduleAction.TOGGLE,
    var sceneId: Long = -1L,              // Si action = SCENE
    var targetMac: String = "*",          // "*" = toutes les ampoules

    var isCountdown: Boolean = false,      // Mode minuteur
    var countdownSeconds: Int = 0
) : Parcelable {
    fun repeatLabel(): String = when {
        repeatDays.isEmpty() -> "Une fois"
        repeatDays.size == 7 -> "Tous les jours"
        repeatDays.containsAll(setOf(0, 1, 2, 3, 4)) &&
                !repeatDays.contains(5) && !repeatDays.contains(6) -> "Jours ouvrables"
        repeatDays.containsAll(setOf(5, 6)) && repeatDays.size == 2 -> "Week-end"
        else -> {
            val days = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
            repeatDays.sorted().joinToString(", ") { days[it] }
        }
    }
}

enum class ScheduleAction {
    TURN_ON, TURN_OFF, TOGGLE, APPLY_SCENE, DIM_50, FULL_BRIGHT
}

// ══════════════════════════════════════════
// GROUPE D'AMPOULES
// ══════════════════════════════════════════

/**
 * Groupe logique d'ampoules (ex : "Salon", "Chambre").
 * Les ampoules sont liées par leur groupId dans la table bulbs.
 */
@Parcelize
@Entity(tableName = "groups")
data class BulbGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    var name: String,
    var iconEmoji: String = "💡",
    var colorHex: String = "#6B73FF"
) : Parcelable

// ══════════════════════════════════════════
// EFFETS DYNAMIQUES
// ══════════════════════════════════════════

/**
 * Types d'effets disponibles pour les ampoules.
 */
enum class LightEffect(val label: String, val emoji: String) {
    NONE("Aucun", "⚫"),
    FADE("Fondu", "🌅"),
    STROBE("Stroboscope", "⚡"),
    COLOR_CYCLE("Cycle couleurs", "🌈"),
    BREATHING("Respiration", "💨"),
    CANDLE("Bougie", "🕯️"),
    MUSIC_SYNC("Synchro musique", "🎵"),
    CUSTOM("Personnalisé", "✏️")
}

// ══════════════════════════════════════════
// CONVERTERS ROOM
// ══════════════════════════════════════════

class SceneConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromBulbStateList(value: List<BulbState>): String =
        gson.toJson(value)

    @TypeConverter
    fun toBulbStateList(value: String): List<BulbState> =
        gson.fromJson(value, object : TypeToken<List<BulbState>>() {}.type)

    @TypeConverter
    fun fromIntSet(value: Set<Int>): String =
        gson.toJson(value)

    @TypeConverter
    fun toIntSet(value: String): Set<Int> =
        gson.fromJson(value, object : TypeToken<Set<Int>>() {}.type)
}
