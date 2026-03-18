package com.lumicontrol.ui.activities

import android.content.Context
import android.hardware.ConsumerIrManager

class IrController(private val context: Context) {

    private val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    fun hasIR() = irManager?.hasIrEmitter() == true

    fun send(code: Int) {
        if (!hasIR()) return
        try {
            irManager?.transmit(38000, necToPulse(code))
        } catch (e: Exception) {}
    }

    private fun necToPulse(code: Int): IntArray {
        val pulses = mutableListOf<Int>()
        pulses.add(9000); pulses.add(4500)
        for (i in 31 downTo 0) {
            pulses.add(562)
            if ((code shr i) and 1 == 1) pulses.add(1687) else pulses.add(562)
        }
        pulses.add(562)
        return pulses.toIntArray()
    }

    fun saveCode(key: String, code: Int) {
        context.getSharedPreferences("ir_codes", Context.MODE_PRIVATE)
            .edit().putInt(key, code).apply()
    }

    fun getCode(key: String) =
        context.getSharedPreferences("ir_codes", Context.MODE_PRIVATE)
            .getInt(key, 0)

    // ── Codes Wall Light (grande télécommande) ──
    val WL_ON       = 0xFF02FD
    val WL_OFF      = 0xFF827D
    val WL_BRIGHT_P = 0xFF3AC5
    val WL_BRIGHT_M = 0xFFBA45
    val WL_RED      = 0xFF1AE5
    val WL_GREEN    = 0xFF9A65
    val WL_BLUE     = 0xFFA25D
    val WL_WHITE    = 0xFF22DD
    val WL_ORANGE   = 0xFF2AD5
    val WL_LIME     = 0xFFAA55
    val WL_CYAN     = 0xFF926D
    val WL_PINK     = 0xFF12ED
    val WL_FLASH    = 0xFFD02F
    val WL_STROBE   = 0xFFC837
    val WL_FADE     = 0xFF48B7
    val WL_SMOOTH   = 0xFF6897
    val WL_SPEED_P  = 0xFF20DF
    val WL_SPEED_M  = 0xFFA05F
    val WL_MUSIC1   = 0xFF58A7
    val WL_MUSIC2   = 0xFFD827
    val WL_MUSIC3   = 0xFF7887
    val WL_MUSIC4   = 0xFFF807

    // ── Codes Baltimore à scanner ──
    fun getBalCode(button: String) = getCode("bal_$button")
    fun saveBalCode(button: String, code: Int) = saveCode("bal_$button", code)

    fun irScanCodes() = listOf(
        0xFF30CF, 0xFFB04F, 0xFF708F, 0xFFF00F,
        0xFF50AF, 0xFFD02F, 0xFF10EF, 0xFF906F,
        0xFF6897, 0xFF9867, 0xFFB847, 0xFF8877,
        0xFF48B7, 0xFF28D7, 0xFF6817, 0xFF08F7,
        0xFF18E7, 0xFF58A7, 0xFF38C7, 0xFF7887,
        0xFF20DF, 0xFFA05F, 0xFF609F, 0xFFE01F,
        0xFF40BF, 0xFFC03F, 0xFF807F, 0xFF00FF,
        0xFF02FD, 0xFF827D, 0xFF42BD, 0xFFC23D,
        0xFF22DD, 0xFFA25D, 0xFF629D, 0xFFE21D,
        0xFF12ED, 0xFF926D, 0xFF52AD, 0xFFD22D,
        0xFF32CD, 0xFFB24D, 0xFF728D, 0xFFF20D,
        0xFF1AE5, 0xFF9A65, 0xFF5AA5, 0xFFDA25,
        0xFF3AC5, 0xFFBA45, 0xFF7A85, 0xFFFA05
    )
}
