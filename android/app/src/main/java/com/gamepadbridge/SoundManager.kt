package com.gamepadbridge

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

class SoundManager(private val context: Context) {

    private val retroSounds = listOf(
        R.raw.retro_1, R.raw.retro_2, R.raw.retro_3, R.raw.retro_4, R.raw.retro_5
    )
    private val playerSounds = mapOf(
        1 to R.raw.player_1,
        2 to R.raw.player_2,
        3 to R.raw.player_3,
        4 to R.raw.player_4,
    )
    private val randomSounds = listOf(
        R.raw.random_1, R.raw.random_2
    )

    private val handler = Handler(Looper.getMainLooper())

    fun playConnectedSequence(playerNumber: Int) {
        val retroRes = retroSounds.random()
        val mp = MediaPlayer.create(context, retroRes)
        mp.setOnCompletionListener {
            mp.release()
            playPlayerSound(playerNumber)
        }
        mp.start()
    }

    private fun playPlayerSound(playerNumber: Int) {
        val roll = Random.nextFloat()
        val resId: Int? = if (roll < 0.05f) {
            randomSounds.random()
        } else {
            playerSounds[playerNumber]
        }
        resId ?: return
        val mp = MediaPlayer.create(context, resId)
        mp.setOnCompletionListener { mp.release() }
        mp.start()
    }
}
