package com.gamepadbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GamepadState(
    var gamepadId: Int = 0,
    var buttons: Int = 0,
    var leftStickX: Short = 0,
    var leftStickY: Short = 0,
    var rightStickX: Short = 0,
    var rightStickY: Short = 0,
    var leftTrigger: Int = 0,
    var rightTrigger: Int = 0
) {
    fun toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_GAMEPAD_STATE)
        buf.put(gamepadId.toByte())
        buf.putInt(buttons)
        buf.putShort(leftStickX)
        buf.putShort(leftStickY)
        buf.putShort(rightStickX)
        buf.putShort(rightStickY)
        buf.put(leftTrigger.toByte())
        buf.put(rightTrigger.toByte())
        buf.putInt((System.currentTimeMillis() % (1L shl 32)).toInt())
        return buf.array()
    }

    fun pressedButtons(): List<String> {
        return ALL_NAMES.filter { (bit, _) -> buttons and bit != 0 }.map { it.value }
    }

    fun debugString(): String {
        val pressed = pressedButtons()
        val btnStr = if (pressed.isEmpty()) "none" else pressed.joinToString(",")
        return "[GP#$gamepadId] buttons=$btnStr lx=$leftStickX ly=$leftStickY rx=$rightStickX ry=$rightStickY lt=$leftTrigger rt=$rightTrigger"
    }

    companion object {
        const val BIT_A = 1
        const val BIT_B = 2
        const val BIT_X = 4
        const val BIT_Y = 8
        const val BIT_LB = 16
        const val BIT_RB = 32
        const val BIT_LT = 64
        const val BIT_RT = 128
        const val BIT_SELECT = 256
        const val BIT_START = 512
        const val BIT_L3 = 1024
        const val BIT_R3 = 2048
        const val BIT_DPAD_UP = 4096
        const val BIT_DPAD_DOWN = 8192
        const val BIT_DPAD_LEFT = 16384
        const val BIT_DPAD_RIGHT = 32768
        const val BIT_HOME = 65536
        const val MSG_GAMEPAD_STATE: Byte = 0

        val ALL_NAMES: Map<Int, String> = mapOf(
            BIT_A to "A",
            BIT_B to "B",
            BIT_X to "X",
            BIT_Y to "Y",
            BIT_LB to "LB",
            BIT_RB to "RB",
            BIT_LT to "LT(d)",
            BIT_RT to "RT(d)",
            BIT_SELECT to "SELECT",
            BIT_START to "START",
            BIT_L3 to "L3",
            BIT_R3 to "R3",
            BIT_DPAD_UP to "DPAD_UP",
            BIT_DPAD_DOWN to "DPAD_DOWN",
            BIT_DPAD_LEFT to "DPAD_LEFT",
            BIT_DPAD_RIGHT to "DPAD_RIGHT",
            BIT_HOME to "HOME",
        )
    }
}
