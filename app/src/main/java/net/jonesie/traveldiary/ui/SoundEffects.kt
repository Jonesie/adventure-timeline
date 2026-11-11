package net.jonesie.traveldiary.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import net.jonesie.traveldiary.R
import net.jonesie.traveldiary.util.FinishSound
import net.jonesie.traveldiary.util.IncidentSound
import net.jonesie.traveldiary.util.TransportAccent
import net.jonesie.traveldiary.util.TransportSound

/**
 * Plays the trip animation's sounds: short incident clips, a quiet loop for the transport being travelled, and the odd
 * accent on top. Release it when done: it holds the decoded sounds in memory.
 */
class SoundEffects(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val incidentIds = IncidentSound.entries.associateWith { pool.load(context, it.resource(), 1) }
    private val loopIds = TransportSound.entries.associateWith { pool.load(context, it.resource(), 1) }
    private val accentIds = TransportAccent.entries.associateWith { pool.load(context, it.resource(), 1) }
    private val finishIds = FinishSound.entries.associateWith { pool.load(context, it.resource(), 1) }

    // Loading is asynchronous and the callback comes on another thread, so the loop's state is guarded.
    private val loaded = mutableSetOf<Int>()
    private var wanted: TransportSound? = null
    private var loopStream = 0

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            synchronized(this) {
                if (status == 0) loaded += id
                if (status == 0 && loopStream == 0 && wanted?.let { loopIds[it] } == id) startLoop()
            }
        }
    }

    /** Silently does nothing if the clip hasn't finished loading yet. */
    fun play(sound: IncidentSound) {
        incidentIds[sound]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun play(sound: FinishSound) {
        finishIds[sound]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun play(accent: TransportAccent) {
        accentIds[accent]?.let { pool.play(it, ACCENT_VOLUME, ACCENT_VOLUME, 1, 0, 1f) }
    }

    /** Loops [sound] until told otherwise; null stops it. Starts once the clip has loaded if it hasn't yet. */
    fun setLoop(sound: TransportSound?) {
        synchronized(this) {
            if (sound == wanted) return
            stopLoop()
            wanted = sound
            startLoop()
        }
    }

    private fun startLoop() {
        val id = wanted?.let { loopIds[it] } ?: return
        if (id in loaded) loopStream = pool.play(id, LOOP_VOLUME, LOOP_VOLUME, 0, -1, 1f)
    }

    private fun stopLoop() {
        if (loopStream != 0) pool.stop(loopStream)
        loopStream = 0
    }

    fun release() {
        synchronized(this) {
            stopLoop()
            wanted = null
            pool.release()
        }
    }

    private companion object {
        // Quiet enough to sit under the incident sounds and not wear thin over a long trip.
        const val LOOP_VOLUME = 0.35f
        const val ACCENT_VOLUME = 0.6f
    }
}

private fun IncidentSound.resource(): Int = when (this) {
    IncidentSound.CRASH -> R.raw.sfx_crash
    IncidentSound.SCREECH -> R.raw.sfx_screech
    IncidentSound.SPLASH -> R.raw.sfx_splash
    IncidentSound.SPUTTER -> R.raw.sfx_sputter
    IncidentSound.THUNDER -> R.raw.sfx_thunder
    IncidentSound.ALERT -> R.raw.sfx_alert
    IncidentSound.SCREAM -> R.raw.sfx_scream
    IncidentSound.SIREN -> R.raw.sfx_siren
    IncidentSound.SCREAM_SPLASH -> R.raw.sfx_scream_splash
}

private fun TransportSound.resource(): Int = when (this) {
    TransportSound.WALK -> R.raw.amb_walk
    TransportSound.BIKE -> R.raw.amb_bike
    TransportSound.MOTORBIKE -> R.raw.amb_motorbike
    TransportSound.CAR -> R.raw.amb_car
    TransportSound.FOUR_WD -> R.raw.amb_fourwd
    TransportSound.BUS -> R.raw.amb_bus
    TransportSound.TRAIN -> R.raw.amb_train
    TransportSound.PLANE -> R.raw.amb_plane
    TransportSound.BOAT -> R.raw.amb_boat
}

private fun TransportAccent.resource(): Int = when (this) {
    TransportAccent.FOG_HORN -> R.raw.sfx_fog_horn
    TransportAccent.SHIP_BELL -> R.raw.sfx_ship_bell
}

private fun FinishSound.resource(): Int = when (this) {
    FinishSound.TA_DA -> R.raw.fin_ta_da
    FinishSound.DRUM_ROLL -> R.raw.fin_drum_roll
    FinishSound.FLOURISH -> R.raw.fin_flourish
    FinishSound.CROWD_CHEER -> R.raw.fin_crowd_cheer
}
