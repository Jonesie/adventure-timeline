package net.jonesie.traveldiary.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import net.jonesie.traveldiary.R
import net.jonesie.traveldiary.util.IncidentSound

/** Plays the short incident clips. Release it when done: it holds the decoded sounds in memory. */
class SoundEffects(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = IncidentSound.entries.associateWith { pool.load(context, it.resource(), 1) }

    /** Silently does nothing if the clip hasn't finished loading yet. */
    fun play(sound: IncidentSound) {
        ids[sound]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun release() = pool.release()
}

private fun IncidentSound.resource(): Int = when (this) {
    IncidentSound.CRASH -> R.raw.sfx_crash
    IncidentSound.SCREECH -> R.raw.sfx_screech
    IncidentSound.SPLASH -> R.raw.sfx_splash
    IncidentSound.SPUTTER -> R.raw.sfx_sputter
    IncidentSound.THUNDER -> R.raw.sfx_thunder
    IncidentSound.ALERT -> R.raw.sfx_alert
}
