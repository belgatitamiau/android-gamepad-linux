package com.gamepadbridge

import android.util.Log
import android.view.InputDevice

class GamepadManager {
    private val slotStates = Array(MAX_GAMEPADS) { GamepadState(it) }
    private val deviceToSlot = LinkedHashMap<Int, Int>()
    private val slotToDevice = arrayOfNulls<Int>(MAX_GAMEPADS)

    @Synchronized
    fun getOrAssignSlot(deviceId: Int): Int {
        deviceToSlot[deviceId]?.let { return it }
        for (slot in 0 until MAX_GAMEPADS) {
            if (slotToDevice[slot] == null) {
                slotToDevice[slot] = deviceId
                deviceToSlot[deviceId] = slot
                val devName = InputDevice.getDevice(deviceId)?.name ?: "Unknown"
                Log.i(TAG, "Assigned device #$deviceId ($devName) -> slot $slot")
                return slot
            }
        }
        Log.w(TAG, "No free slots! Max $MAX_GAMEPADS gamepads connected")
        return -1
    }

    @Synchronized
    fun releaseSlot(deviceId: Int) {
        val slot = deviceToSlot.remove(deviceId) ?: return
        if (slot in 0 until MAX_GAMEPADS) {
            slotToDevice[slot] = null
            slotStates[slot].apply {
                buttons = 0
                leftStickX = 0
                leftStickY = 0
                rightStickX = 0
                rightStickY = 0
                leftTrigger = 0
                rightTrigger = 0
            }
            Log.i(TAG, "Released slot $slot (device #$deviceId)")
        }
    }

    @Synchronized
    fun getState(deviceId: Int): GamepadState? {
        val slot = deviceToSlot[deviceId] ?: return null
        return if (slot >= 0) slotStates[slot] else null
    }

    @Synchronized
    fun getStateBySlot(slot: Int): GamepadState? {
        return if (slot in 0 until MAX_GAMEPADS && slotToDevice[slot] != null) slotStates[slot] else null
    }

    fun getActiveSlotIds(): List<Int> {
        synchronized(this) {
            return deviceToSlot.values.filter { it >= 0 }
        }
    }

    fun getSlotCount(): Int {
        synchronized(this) {
            return deviceToSlot.size
        }
    }

    fun hasSlots(): Boolean = deviceToSlot.isNotEmpty()

    companion object {
        private const val TAG = "GamepadManager"
        private const val MAX_GAMEPADS = 4
    }
}
