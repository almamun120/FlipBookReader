// utils/SoundManager.kt
package com.bookflip.reader.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.bookflip.reader.R

class SoundManager(context: Context) {
    private val soundPool: SoundPool
    private val pageFlipSoundId: Int
    private val pageFlipBackSoundId: Int
    private var isEnabled = true

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(audioAttributes)
            .build()

        pageFlipSoundId = soundPool.load(context, R.raw.page_flip, 1)
        pageFlipBackSoundId = soundPool.load(context, R.raw.page_flip_back, 1)
    }

    fun playPageFlip(forward: Boolean = true) {
        if (!isEnabled) return
        val soundId = if (forward) pageFlipSoundId else pageFlipBackSoundId
        soundPool.play(soundId, 0.8f, 0.8f, 1, 0, 1.0f)
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun release() {
        soundPool.release()
    }
}