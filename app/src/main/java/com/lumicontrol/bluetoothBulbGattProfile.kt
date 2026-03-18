package com.lumicontrol.bluetooth

import android.bluetooth.le.ScanResult
import com.lumicontrol.models.BulbType
import java.util.UUID

object BulbGattProfile {

    private val profiles = mutableMapOf<String, GattProfile>()
    private val defaultProfile = HueProfile()
    private val bulbProfileCache = mutableMapOf<String, GattProfile>()

    init {
        register("HUE", HueProfile())
        register("GENERIC", GenericProfile())
    }

    fun register(key: String, profile: GattProfile) { profiles[key] = profile }
    fun profileFor(macAddress: String): GattProfile = bulbProfileCache[macAddress] ?: defaultProfile

    fun detectType(deviceName: String, scanResult: ScanResult? = null): BulbType {
        val name = deviceName.lowercase()
        return when {
            name.contains("hue") -> BulbType.YEELIGHT
            else -> BulbType.GENERIC
        }
    }
}

interface GattProfile {
    val serviceUUID: UUID
    val colorCharUUID: UUID
    val brightnessCharUUID: UUID?
    fun buildPowerOnCommand(): ByteArray
    fun buildPowerOffCommand(): ByteArray
    fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int): ByteArray
    fun buildBrightnessCommand(value: Int): ByteArray
}

/**
 * Profil Philips Hue Bluetooth
 * Service : 932c32bd-0000-47a2-835a-a8d455b859dd
 * 0002 = ON/OFF (01=on, 00=off)
 * 0003 = Luminosité (00-FE)
 * 0005 = Couleur XY (4 bytes)
 */
class HueProfile : GattProfile {
    override val serviceUUID = UUID.fromString("932c32bd-0000-47a2-835a-a8d455b859dd")
    override val colorCharUUID = UUID.fromString("932c32bd-0005-47a2-835a-a8d455b859dd")
    override val brightnessCharUUID = UUID.fromString("932c32bd-0003-47a2-835a-a8d455b859dd")

    val powerCharUUID = UUID.fromString("932c32bd-0002-47a2-835a-a8d455b859dd")

    override fun buildPowerOnCommand() = byteArrayOf(0x01)
    override fun buildPowerOffCommand() = byteArrayOf(0x00)

    override fun buildBrightnessCommand(value: Int): ByteArray {
        // Hue : luminosité de 0 à 254
        val hueVal = (value * 254 / 100).coerceIn(1, 254)
        return byteArrayOf(hueVal.toByte())
    }

    override fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int): ByteArray {
        // Convertir RGB en XY (espace colorimétrique CIE)
        val (x, y) = rgbToXY(r, g, b)
        // Hue XY : 4 bytes little-endian (x sur 2 bytes, y sur 2 bytes)
        val xInt = (x * 65535).toInt()
        val yInt = (y * 65535).toInt()
        return byteArrayOf(
            (xInt and 0xFF).toByte(),
            ((xInt shr 8) and 0xFF).toByte(),
            (yInt and 0xFF).toByte(),
            ((yInt shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Convertit RGB en coordonnées XY CIE 1931
     * Algorithme officiel Philips Hue
     */
    private fun rgbToXY(r: Int, g: Int, b: Int): Pair<Float, Float> {
        // Normaliser 0-1
        var red = r / 255f
        var green = g / 255f
        var blue = b / 255f

        // Correction gamma
        red = if (red > 0.04045f) Math.pow(((red + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else red / 12.92f
        green = if (green > 0.04045f) Math.pow(((green + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else green / 12.92f
        blue = if (blue > 0.04045f) Math.pow(((blue + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else blue / 12.92f

        // RGB vers XYZ (matrice Wide RGB D65)
        val X = red * 0.664511f + green * 0.154324f + blue * 0.162028f
        val Y = red * 0.283881f + green * 0.668433f + blue * 0.047685f
        val Z = red * 0.000088f + green * 0.072310f + blue * 0.986039f

        // XYZ vers xy
        val sum = X + Y + Z
        if (sum == 0f) return Pair(0f, 0f)
        return Pair(X / sum, Y / sum)
    }
}

class GenericProfile : GattProfile {
    override val serviceUUID = UUID.fromString("0000FFD5-0000-1000-8000-00805F9B34FB")
    override val colorCharUUID = UUID.fromString("0000FFD9-0000-1000-8000-00805F9B34FB")
    override val brightnessCharUUID = null
    override fun buildPowerOnCommand() = byteArrayOf(0xCC.toByte(), 0x23, 0x33)
    override fun buildPowerOffCommand() = byteArrayOf(0xCC.toByte(), 0x24, 0x33)
    override fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int) =
        byteArrayOf(0x56.toByte(), r.toByte(), g.toByte(), b.toByte(), 0x00, 0xF0.toByte(), 0xAA.toByte())
    override fun buildBrightnessCommand(value: Int) =
        byteArrayOf(0x56.toByte(), 0x00, 0x00, 0x00, value.toByte(), 0x0F.toByte(), 0xAA.toByte())
}
