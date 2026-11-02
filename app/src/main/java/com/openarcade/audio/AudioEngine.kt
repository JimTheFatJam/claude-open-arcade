package com.openarcade.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One background mixer thread drives a single streaming AudioTrack. Music loops seamlessly from a
 * pre-rendered buffer and fades in/out on toggles and song changes; sound effects are mixed on top.
 * Everything is generated in code, so audio works fully offline with no asset files.
 */
class AudioEngine {
    @Volatile var musicEnabled = true
    @Volatile var sfxEnabled = true
    @Volatile var musicVolume = 0.6f
    @Volatile var sfxVolume = 0.7f
    @Volatile private var active = true
    @Volatile private var requested: Song? = null
    @Volatile private var running = false

    private val pending = ConcurrentLinkedQueue<FloatArray>()
    private val cache = HashMap<String, FloatArray>()
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "arcade-audio").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    fun shutdown() {
        running = false
        thread?.interrupt()
        thread = null
    }

    fun setActive(a: Boolean) { active = a }

    fun playSong(song: Song?) { requested = song }

    fun play(s: Sfx) {
        if (!sfxEnabled || sfxVolume <= 0f) return
        pending.add(Sfx.buffer(s))
    }

    private class Voice(val data: FloatArray) { var pos = 0 }

    @Suppress("DEPRECATION")
    private fun loop() {
        val rate = Synth.RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack(
                AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 2048) * 2, AudioTrack.MODE_STREAM,
            )
        } catch (e: Exception) {
            running = false; return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); running = false; return }

        val chunk = 512
        val out = ShortArray(chunk)
        val voices = ArrayList<Voice>()
        var song: Song? = null
        var songBuf: FloatArray? = null
        var pos = 0
        var gain = 0f
        val ramp = 1f / (0.35f * rate)
        var playing = false

        try {
            while (running) {
                // collect new effects
                while (true) { val b = pending.poll() ?: break; voices.add(Voice(b)) }
                if (!sfxEnabled) voices.clear()

                val want = requested
                val songChanging = want?.id != song?.id
                val target = if (musicEnabled && want != null && !songChanging) musicVolume * 0.85f else 0f

                if (songChanging && gain <= 0.0005f) {
                    song = want
                    songBuf = want?.let { s -> cache.getOrPut(s.id) { Music.render(s) } }
                    pos = 0
                    continue
                }

                val idle = !active || (gain <= 0.0005f && target == 0f && voices.isEmpty())
                if (idle) {
                    if (playing) { track.pause(); track.flush(); playing = false }
                    if (!active) voices.clear()
                    try { Thread.sleep(30) } catch (e: InterruptedException) { /* loop */ }
                    continue
                }
                if (!playing) { track.play(); playing = true }

                val sb = songBuf
                val sv = sfxVolume
                for (i in 0 until chunk) {
                    if (gain < target) gain = minOf(target, gain + ramp) else if (gain > target) gain = maxOf(target, gain - ramp)
                    var v = 0f
                    if (sb != null && gain > 0f) {
                        v = sb[pos] * gain
                        pos++
                        if (pos >= sb.size) pos = 0
                    }
                    if (voices.isNotEmpty()) {
                        var k = 0
                        while (k < voices.size) {
                            val vo = voices[k]
                            v += vo.data[vo.pos] * sv
                            vo.pos++
                            if (vo.pos >= vo.data.size) voices.removeAt(k) else k++
                        }
                    }
                    val x = v.coerceIn(-1f, 1f)
                    out[i] = (x * 32000f).toInt().toShort()
                }
                track.write(out, 0, chunk)
                // keep at most a few overlapping copies of effects to avoid harsh stacking
                while (voices.size > 8) voices.removeAt(0)
            }
        } finally {
            try { track.stop() } catch (e: Exception) { }
            track.release()
        }
    }
}
