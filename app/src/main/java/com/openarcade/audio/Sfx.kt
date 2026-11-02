package com.openarcade.audio

import com.openarcade.audio.Synth.Wave
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** All sound effects, synthesised once on first use. */
enum class Sfx {
    CLICK, SELECT, BACK, MOVE, ROTATE, DROP, PLACE, LINE, EAT, POWER, BOUNCE, WALL, HIT,
    EXPLODE, SHOOT, WIN, LOSE, ERROR, FLAG, REVEAL, MERGE, CAPTURE, CHECK, LEVELUP, COIN,
    TICK, MEOW, ACHIEVE, START;

    companion object {
        private val cache = HashMap<Sfx, FloatArray>()

        @Synchronized
        fun buffer(s: Sfx): FloatArray = cache.getOrPut(s) { build(s) }

        private fun buf(sec: Float) = FloatArray((sec * Synth.RATE).toInt())

        private fun tone(f0: Float, f1: Float, dur: Float, w: Wave, vol: Float, decayRate: Float = 8f): FloatArray {
            val b = buf(dur)
            var ph = 0f
            for (i in b.indices) {
                val t = i / b.size.toFloat()
                val f = f0 + (f1 - f0) * t
                ph += f / Synth.RATE; if (ph >= 1f) ph -= ph.toInt()
                val env = exp(-t * decayRate / 4f) * (1f - t).coerceAtLeast(0f).let { if (it < 0.05f) it * 20f else 1f }
                b[i] = Synth.osc(w, ph) * vol * env
            }
            return b
        }

        private fun seq(freqs: FloatArray, each: Float, w: Wave, vol: Float, last: Float = each): FloatArray {
            val n = (each * Synth.RATE).toInt()
            val total = n * (freqs.size - 1) + (last * Synth.RATE).toInt()
            val b = FloatArray(total)
            for ((k, f) in freqs.withIndex()) {
                val len = if (k == freqs.size - 1) (last * Synth.RATE).toInt() else n
                var ph = 0f
                for (i in 0 until len) {
                    ph += f / Synth.RATE; if (ph >= 1f) ph -= ph.toInt()
                    val t = i / len.toFloat()
                    val env = (1f - t * 0.7f) * (if (t > 0.9f) (1f - t) * 10f else 1f)
                    b[k * n + i] += Synth.osc(w, ph) * vol * env
                }
            }
            return b
        }

        private fun noise(dur: Float, vol: Float, decay: Float): FloatArray {
            val b = buf(dur)
            for (i in b.indices) {
                val t = i / Synth.RATE.toFloat()
                b[i] = Synth.noise() * vol * exp(-t * decay)
            }
            return b
        }

        private fun mixAll(vararg parts: FloatArray): FloatArray {
            val out = FloatArray(parts.maxOf { it.size })
            for (p in parts) for (i in p.indices) out[i] += p[i]
            return out
        }

        private fun build(s: Sfx): FloatArray = when (s) {
            CLICK -> tone(900f, 900f, 0.03f, Wave.SQUARE25, 0.22f)
            SELECT -> seq(floatArrayOf(660f, 990f), 0.045f, Wave.SQUARE25, 0.22f)
            BACK -> seq(floatArrayOf(660f, 440f), 0.045f, Wave.SQUARE25, 0.22f)
            START -> seq(floatArrayOf(523f, 784f, 1046f), 0.06f, Wave.SQUARE25, 0.22f, 0.12f)
            MOVE -> tone(420f, 520f, 0.035f, Wave.SQUARE12, 0.15f)
            ROTATE -> tone(600f, 950f, 0.05f, Wave.SQUARE25, 0.16f)
            DROP -> mixAll(noise(0.08f, 0.25f, 40f), tone(220f, 70f, 0.09f, Wave.SQUARE50, 0.2f))
            PLACE -> tone(330f, 220f, 0.05f, Wave.SQUARE50, 0.18f)
            LINE -> seq(floatArrayOf(523f, 659f, 784f, 1046f), 0.05f, Wave.SQUARE25, 0.2f, 0.1f)
            EAT -> tone(300f, 650f, 0.06f, Wave.SQUARE25, 0.18f)
            POWER -> seq(floatArrayOf(392f, 494f, 587f, 784f, 988f, 1175f), 0.035f, Wave.SQUARE12, 0.22f, 0.08f)
            BOUNCE -> tone(520f, 520f, 0.035f, Wave.SQUARE50, 0.18f)
            WALL -> tone(260f, 260f, 0.035f, Wave.SQUARE50, 0.16f)
            HIT -> mixAll(tone(700f, 300f, 0.06f, Wave.SQUARE25, 0.18f), noise(0.04f, 0.1f, 60f))
            EXPLODE -> noise(0.45f, 0.4f, 8f)
            SHOOT -> tone(1400f, 300f, 0.11f, Wave.SQUARE25, 0.13f)
            WIN -> seq(floatArrayOf(523f, 659f, 784f, 1046f, 784f, 1046f), 0.09f, Wave.SQUARE25, 0.2f, 0.35f)
            LOSE -> seq(floatArrayOf(392f, 330f, 262f, 196f), 0.14f, Wave.SQUARE50, 0.18f, 0.4f)
            ERROR -> tone(150f, 140f, 0.14f, Wave.SQUARE50, 0.18f)
            FLAG -> seq(floatArrayOf(880f, 1320f), 0.035f, Wave.SQUARE12, 0.2f)
            REVEAL -> mixAll(noise(0.03f, 0.08f, 80f), tone(700f, 900f, 0.03f, Wave.SQUARE12, 0.12f))
            MERGE -> tone(400f, 820f, 0.07f, Wave.SQUARE25, 0.17f)
            CAPTURE -> mixAll(noise(0.08f, 0.2f, 30f), tone(500f, 200f, 0.08f, Wave.SQUARE50, 0.15f))
            CHECK -> seq(floatArrayOf(880f, 660f, 880f), 0.06f, Wave.SQUARE25, 0.2f)
            LEVELUP -> seq(floatArrayOf(523f, 659f, 784f, 1046f, 1318f), 0.06f, Wave.SQUARE25, 0.2f, 0.2f)
            COIN -> seq(floatArrayOf(988f, 1318f), 0.06f, Wave.SQUARE25, 0.2f, 0.22f)
            ACHIEVE -> seq(floatArrayOf(784f, 988f, 1175f, 1568f, 1175f, 1568f), 0.06f, Wave.SQUARE12, 0.24f, 0.3f)
            TICK -> tone(1500f, 1500f, 0.012f, Wave.SQUARE50, 0.1f)
            MEOW -> meow()
        }

        /** A cute synthesised "meow": a rising then falling pitch glide with vibrato. */
        private fun meow(): FloatArray {
            val b = buf(0.32f)
            var ph = 0f
            for (i in b.indices) {
                val t = i / b.size.toFloat()
                val f = when {
                    t < 0.35f -> 520f + 380f * (t / 0.35f)
                    else -> 900f - 420f * ((t - 0.35f) / 0.65f)
                } * (1f + 0.02f * sin(t * 40f * PI.toFloat()))
                ph += f / Synth.RATE; if (ph >= 1f) ph -= ph.toInt()
                val env = if (t < 0.1f) t / 0.1f else (1f - t) / 0.9f
                b[i] = (Synth.osc(Wave.SQUARE12, ph) * 0.6f + Synth.osc(Wave.TRIANGLE, ph) * 0.4f) * 0.22f * env
            }
            return b
        }
    }
}
