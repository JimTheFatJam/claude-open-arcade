package com.openarcade.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Tiny chiptune synthesiser: square/triangle/noise oscillators rendered into float buffers. */
object Synth {
    const val RATE = 22050

    enum class Wave { SQUARE12, SQUARE25, SQUARE50, TRIANGLE, SAW, NOISE, SINE }

    private var noiseState = 0x1234567

    fun noise(): Float {
        // 16-bit LFSR style noise, like old sound chips.
        noiseState = noiseState xor (noiseState shl 13)
        noiseState = noiseState xor (noiseState ushr 17)
        noiseState = noiseState xor (noiseState shl 5)
        return ((noiseState and 0xFFFF) / 32768f) - 1f
    }

    /** Oscillator value for [phase] in [0,1). */
    fun osc(w: Wave, phase: Float): Float = when (w) {
        Wave.SQUARE12 -> if (phase < 0.125f) 1f else -1f
        Wave.SQUARE25 -> if (phase < 0.25f) 1f else -1f
        Wave.SQUARE50 -> if (phase < 0.5f) 1f else -1f
        Wave.TRIANGLE -> if (phase < 0.5f) phase * 4f - 1f else 3f - phase * 4f
        Wave.SAW -> phase * 2f - 1f
        Wave.SINE -> sin(phase * 2f * PI.toFloat())
        Wave.NOISE -> noise()
    }

    fun midiToFreq(m: Int): Float = (440.0 * 2.0.pow((m - 69) / 12.0)).toFloat()

    private val NOTE_OFFSETS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    /** Parses names like "C4", "F#5", "Bb3" into MIDI numbers, or null. */
    fun parseNote(s: String): Int? {
        if (s.length < 2) return null
        val base = NOTE_OFFSETS[s[0].uppercaseChar()] ?: return null
        var i = 1
        var acc = 0
        if (s[i] == '#') { acc = 1; i++ } else if (s[i] == 'b' && s.length > 2) { acc = -1; i++ }
        val oct = s.substring(i).toIntOrNull() ?: return null
        return (oct + 1) * 12 + base + acc
    }

    /**
     * Adds a note to [buf] starting at sample [start] for [len] samples, wrapping around the end of
     * the buffer so looping music has no seam.
     */
    fun addNote(
        buf: FloatArray, start: Int, len: Int, freq: Float, wave: Wave, vol: Float,
        attack: Float = 0.005f, decay: Float = 0.10f, sustain: Float = 0.6f, release: Float = 0.04f,
        vibrato: Boolean = false, slideTo: Float = freq,
    ) {
        val n = buf.size
        val a = (attack * RATE).toInt().coerceAtLeast(1)
        val d = (decay * RATE).toInt().coerceAtLeast(1)
        val r = (release * RATE).toInt().coerceAtLeast(1)
        val total = len + r
        var phase = 0f
        for (i in 0 until total) {
            val env = when {
                i < a -> i / a.toFloat()
                i < a + d -> 1f - (1f - sustain) * ((i - a) / d.toFloat())
                i < len -> sustain
                else -> sustain * (1f - (i - len) / r.toFloat())
            }
            val t = i / RATE.toFloat()
            var f = if (slideTo != freq) freq + (slideTo - freq) * (i / total.toFloat()) else freq
            if (vibrato && t > 0.12f) f *= 1f + 0.006f * sin(t * 2f * PI.toFloat() * 6f)
            phase += f / RATE
            if (phase >= 1f) phase -= phase.toInt()
            val idx = (start + i) % n
            buf[idx] += osc(wave, phase) * env * vol
        }
    }

    fun addKick(buf: FloatArray, start: Int, vol: Float) {
        val len = (0.16f * RATE).toInt()
        var phase = 0f
        for (i in 0 until len) {
            val t = i / RATE.toFloat()
            val f = 40f + 110f * exp(-t * 30f)
            phase += f / RATE
            if (phase >= 1f) phase -= 1f
            buf[(start + i) % buf.size] += sin(phase * 2f * PI.toFloat()) * exp(-t * 18f) * vol
        }
    }

    fun addSnare(buf: FloatArray, start: Int, vol: Float) {
        val len = (0.14f * RATE).toInt()
        var phase = 0f
        for (i in 0 until len) {
            val t = i / RATE.toFloat()
            phase += 190f / RATE
            if (phase >= 1f) phase -= 1f
            val v = (noise() * 0.8f + osc(Wave.TRIANGLE, phase) * 0.4f) * exp(-t * 22f) * vol
            buf[(start + i) % buf.size] += v
        }
    }

    fun addHat(buf: FloatArray, start: Int, vol: Float, open: Boolean = false) {
        val len = ((if (open) 0.12f else 0.04f) * RATE).toInt()
        var prev = 0f
        for (i in 0 until len) {
            val t = i / RATE.toFloat()
            val nz = noise()
            val hp = nz - prev // crude high-pass for a crisp hat
            prev = nz
            buf[(start + i) % buf.size] += hp * 0.5f * exp(-t * (if (open) 25f else 70f)) * vol
        }
    }
}
