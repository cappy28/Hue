package com.lumicontrol.bluetooth

import android.bluetooth.le.ScanResult
import com.lumicontrol.models.BulbType
import java.util.UUID

/**
 * ════════════════════════════════════════════════════════════
 * BulbGattProfile — Profils GATT par marque d'ampoule
 * ════════════════════════════════════════════════════════════
 *
 * Chaque marque d'ampoule utilise ses propres UUIDs et formats
 * de commandes. Ce fichier centralise ces profils.
 *
 * Pour ajouter une nouvelle marque :
 *   1. Créer une classe implémentant GattProfile
 *   2. L'ajouter dans profiles map
 *   3. Ajouter la détection dans detectType()
 */
object BulbGattProfile {

    // ─── Registre des profils ───
    private val profiles = mutableMapOf<String, GattProfile>()
    private val defaultProfile = GenericProfile()

    // ─── Cache marque → profil ───
    private val bulbProfileCache = mutableMapOf<String, GattProfile>()

    init {
        // Enregistrement des profils supportés
        register("GENERIC", defaultProfile)
        register("MIPOW",   MipowProfile())
        register("YEELIGHT", YeelightProfile())
        register("GOVEE",   GoveeProfile())
    }

    fun register(key: String, profile: GattProfile) {
        profiles[key] = profile
    }

    fun profileFor(macAddress: String): GattProfile =
        bulbProfileCache[macAddress] ?: defaultProfile

    fun setProfileForBulb(macAddress: String, type: BulbType) {
        bulbProfileCache[macAddress] = when (type) {
            BulbType.MIPOW    -> profiles["MIPOW"] ?: defaultProfile
            BulbType.YEELIGHT -> profiles["YEELIGHT"] ?: defaultProfile
            BulbType.GOVEE    -> profiles["GOVEE"] ?: defaultProfile
            else              -> defaultProfile
        }
    }

    /**
     * Détecte le type d'ampoule à partir du nom BLE et des données de scan.
     * Appelé lors du scan pour classer automatiquement les appareils.
     */
    fun detectType(deviceName: String, scanResult: ScanResult? = null): BulbType {
        val nameLower = deviceName.lowercase()
        return when {
            nameLower.contains("mipow") || nameLower.contains("playbulb") -> BulbType.MIPOW
            nameLower.contains("yeelight") || nameLower.contains("yldp")  -> BulbType.YEELIGHT
            nameLower.contains("govee") || nameLower.startsWith("ihoment") -> BulbType.GOVEE
            nameLower.contains("sengled")                                  -> BulbType.SENGLED
            else -> BulbType.GENERIC
        }
    }
}

// ══════════════════════════════════════════
// INTERFACE PROFIL
// ══════════════════════════════════════════

/**
 * Interface que chaque profil d'ampoule doit implémenter.
 * Définit les UUIDs GATT et les formats de commandes.
 */
interface GattProfile {
    val serviceUUID: UUID
    val colorCharUUID: UUID
    val brightnessCharUUID: UUID?

    /** Construit la commande pour allumer. */
    fun buildPowerOnCommand(): ByteArray

    /** Construit la commande pour éteindre. */
    fun buildPowerOffCommand(): ByteArray

    /**
     * Construit la commande de couleur RVB.
     * @param r, g, b : 0–255
     * @param brightness : 0–255
     */
    fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int): ByteArray

    /**
     * Construit la commande de luminosité seule.
     * @param value : 0–255
     */
    fun buildBrightnessCommand(value: Int): ByteArray

    /** Construit la commande pour blanc chaud/froid. */
    fun buildWhiteCommand(warmLevel: Int): ByteArray = buildColorCommand(
        255, (255 * warmLevel / 100), (200 * warmLevel / 100), 255
    )
}

// ══════════════════════════════════════════
// PROFIL GÉNÉRIQUE (le plus répandu)
// ══════════════════════════════════════════

/**
 * Protocole utilisé par la majorité des ampoules RGB BLE génériques
 * (marques chinoises, ampoules sans marque).
 *
 * Format : 0x56 R G B 0x00 0xF0 0xAA
 */
class GenericProfile : GattProfile {
    override val serviceUUID    = UUID.fromString("0000FFD5-0000-1000-8000-00805F9B34FB")
    override val colorCharUUID  = UUID.fromString("0000FFD9-0000-1000-8000-00805F9B34FB")
    override val brightnessCharUUID = null // intégré dans la commande couleur

    override fun buildPowerOnCommand() =
        byteArrayOf(0xCC.toByte(), 0x23, 0x33)

    override fun buildPowerOffCommand() =
        byteArrayOf(0xCC.toByte(), 0x24, 0x33)

    override fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int) =
        byteArrayOf(0x56.toByte(), r.toByte(), g.toByte(), b.toByte(), 0x00, 0xF0.toByte(), 0xAA.toByte())

    override fun buildBrightnessCommand(value: Int) =
        byteArrayOf(0x56.toByte(), 0x00, 0x00, 0x00, value.toByte(), 0x0F.toByte(), 0xAA.toByte())
}

// ══════════════════════════════════════════
// PROFIL MIPOW PLAYBULB
// ══════════════════════════════════════════

/**
 * Profil pour les ampoules MIPOW PLAYBULB.
 * Caractérisées par l'UUID de service 0xFF02.
 *
 * Format couleur : [brightness, r, g, b]  (4 bytes)
 * brightness=0 allume la couleur, brightness>0 = blanc uniquement
 */
class MipowProfile : GattProfile {
    override val serviceUUID    = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
    override val colorCharUUID  = UUID.fromString("0000FFFC-0000-1000-8000-00805F9B34FB")
    override val brightnessCharUUID = UUID.fromString("0000FFFC-0000-1000-8000-00805F9B34FB")

    override fun buildPowerOnCommand()  = byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    override fun buildPowerOffCommand() = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    override fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int) =
        byteArrayOf(0x00, r.toByte(), g.toByte(), b.toByte())

    override fun buildBrightnessCommand(value: Int) =
        byteArrayOf(value.toByte(), 0x00, 0x00, 0x00)
}

// ══════════════════════════════════════════
// PROFIL YEELIGHT BLE
// ══════════════════════════════════════════

/**
 * Profil pour les ampoules Xiaomi YeeLight (modèles BLE).
 *
 * Commande : [0x43, 0x00, 0x00] + payload variable selon la commande
 */
class YeelightProfile : GattProfile {
    override val serviceUUID    = UUID.fromString("8E790000-1778-4F2E-94AA-D30B5B5BD05D")
    override val colorCharUUID  = UUID.fromString("8E790001-1778-4F2E-94AA-D30B5B5BD05D")
    override val brightnessCharUUID = UUID.fromString("8E790001-1778-4F2E-94AA-D30B5B5BD05D")

    // Header pour les commandes YeeLight
    private val CMD_HEADER = byteArrayOf(0x43.toByte(), 0x00, 0x00)

    override fun buildPowerOnCommand() =
        byteArrayOf(0x43.toByte(), 0x00, 0x00, 0x01)

    override fun buildPowerOffCommand() =
        byteArrayOf(0x43.toByte(), 0x00, 0x00, 0x00)

    override fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int): ByteArray {
        // YeeLight : commande couleur en HSV converti
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val hue = (hsv[0] / 360f * 255).toInt()
        val sat = (hsv[1] * 255).toInt()
        val `val` = (hsv[2] * 255).toInt()
        return byteArrayOf(
            0x43.toByte(), 0x00, 0x02,
            hue.toByte(), sat.toByte(), `val`.toByte()
        )
    }

    override fun buildBrightnessCommand(value: Int) =
        byteArrayOf(0x43.toByte(), 0x00, 0x03, value.toByte())
}

// ══════════════════════════════════════════
// PROFIL GOVEE
// ══════════════════════════════════════════

/**
 * Profil pour les ampoules Govee (séries H6xxx, H7xxx).
 *
 * Format : [0x33, commande, données..., checksum]
 * Checksum = XOR de tous les bytes de la commande
 */
class GoveeProfile : GattProfile {
    override val serviceUUID    = UUID.fromString("00010203-0405-0607-0809-0A0B0C0D1910")
    override val colorCharUUID  = UUID.fromString("00010203-0405-0607-0809-0A0B0C0D2B11")
    override val brightnessCharUUID = UUID.fromString("00010203-0405-0607-0809-0A0B0C0D2B11")

    override fun buildPowerOnCommand() =
        buildCommand(byteArrayOf(0x01, 0x01))

    override fun buildPowerOffCommand() =
        buildCommand(byteArrayOf(0x01, 0x00))

    override fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int): ByteArray =
        buildCommand(byteArrayOf(0x05, 0x02, r.toByte(), g.toByte(), b.toByte(), 0x00, 0xFF.toByte(), 0x7F.toByte()))

    override fun buildBrightnessCommand(value: Int): ByteArray =
        buildCommand(byteArrayOf(0x04, value.toByte()))

    /** Construit une commande Govee avec checksum XOR en fin. */
    private fun buildCommand(payload: ByteArray): ByteArray {
        val cmd = ByteArray(20) { 0x00 }
        cmd[0] = 0x33
        payload.copyInto(cmd, 1)

        // Checksum = XOR de tous les bytes
        var checksum = 0
        for (i in 0 until 19) checksum = checksum xor cmd[i].toInt()
        cmd[19] = checksum.toByte()

        return cmd
    }
}
