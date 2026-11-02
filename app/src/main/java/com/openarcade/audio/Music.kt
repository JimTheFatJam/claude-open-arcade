package com.openarcade.audio

import com.openarcade.audio.Synth.Wave

/**
 * Original chiptune songs for Open Arcade, written in a tiny tracker notation.
 *
 * Each song is 8 bars of 16 sixteenth-note steps. Lead lines are written out by hand; bass, arpeggio
 * and drums are generated from chord symbols and per-bar patterns. Songs are rendered to a single
 * buffer whose note tails wrap around to the start, so they loop seamlessly.
 */
class Song(
    val id: String,
    val title: String,
    val bpm: Int,
    val chords: List<String>,      // one chord per bar
    val lead: String,              // one token per step: note, "." rest, "-" hold
    val leadWave: Wave = Wave.SQUARE25,
    val bassPattern: String,       // 16 tokens per bar: R root, O octave, 5 fifth, 3 third, "-" hold, "." rest
    val arpPattern: String,        // 16 tokens per bar: 0..3 chord tone index, "-" hold, "." rest
    val drumPattern: String,       // 16 tokens: k kick, s snare, h hat, o open hat, K kick+hat, S snare+hat
    val drumFill: String = drumPattern,
    val leadVol: Float = 0.15f,
    val arpVol: Float = 0.05f,
    val bassVol: Float = 0.20f,
    val drumVol: Float = 1f,
) {
    val bars get() = chords.size
    val steps get() = bars * 16
}

object Music {
    private const val STEPS_PER_BAR = 16

    val MENU = Song(
        id = "menu", title = "INSERT COIN", bpm = 132,
        chords = listOf("C", "Am", "F", "G", "C", "Am", "F", "G"),
        lead = """
            C5 . E5 . G5 . E5 . C6 - - . G5 . E5 .
            A4 . C5 . E5 . C5 . A5 - - . G5 . E5 .
            F5 . A5 . C6 - A5 . F5 . G5 . A5 . G5 .
            G5 - - . D5 . G5 . B5 - - . A5 . G5 .
            E5 - G5 - C6 - - . D6 . C6 . B5 . G5 .
            A5 - - . E5 . A5 . C6 - B5 . A5 . E5 .
            F5 . F5 . A5 . C6 . D6 - C6 . A5 . F5 .
            G5 - - - B5 - - - D6 - - - . . . .
        """,
        bassPattern = "R . O . R . O . R . O . R . O .",
        arpPattern = ". . 0 1 2 . . . . . 0 1 2 . . .",
        drumPattern = "K . h . S . h . K . h K S . h .",
        drumFill = "K . h . S . h . K . S . S S S S",
    )

    val PUZZLE = Song(
        id = "puzzle", title = "PUZZLE GARDEN", bpm = 96,
        chords = listOf("F", "Dm", "Bb", "C", "F", "Dm", "Bb", "C"),
        lead = """
            A4 - - - C5 - - - F5 - - - E5 - C5 -
            D5 - - - F5 - - - A5 - - - G5 - F5 -
            D5 - - - F5 - - - Bb5 - - - A5 - F5 -
            E5 - - - G5 - - - C5 - - - - - . .
            A5 - - - G5 - F5 - C5 - - - D5 - E5 -
            F5 - - - E5 - D5 - A4 - - - - - . .
            Bb4 - D5 - F5 - Bb5 - A5 - - - G5 - - -
            E5 - - - - - - - C5 - - - - - . .
        """,
        leadWave = Wave.TRIANGLE, leadVol = 0.22f,
        bassPattern = "R - - - - - . . 5 - - - . . . .",
        arpPattern = "0 . 1 . 2 . 1 . 0 . 1 . 2 . 3 .",
        drumPattern = "k . . . h . . . . . k . h . . .",
        arpVol = 0.045f,
    )

    val ARCADE = Song(
        id = "arcade", title = "PIXEL RUSH", bpm = 150,
        chords = listOf("Am", "F", "C", "G", "Am", "F", "C", "G"),
        lead = """
            A5 . A5 . C6 . A5 . E5 . A5 . G5 . E5 .
            F5 . F5 . A5 . F5 . C5 . F5 . E5 . C5 .
            E5 . G5 . C6 . G5 . E5 . G5 . C6 . D6 .
            B5 - - . G5 . D5 . G5 . B5 . D6 - - .
            E6 - - . D6 . C6 . B5 . C6 . A5 - - .
            C6 - - . A5 . F5 . A5 . C6 . F6 - - .
            E6 . D6 . C6 . G5 . E5 . G5 . C6 . E6 .
            D6 - - - B5 - - - G5 - - - G#5 - - -
        """,
        leadVol = 0.12f,
        bassPattern = "R . R O R . R O R . R O R . 5 O",
        arpPattern = "0 1 2 0 1 2 0 1 2 0 1 2 0 1 2 1",
        drumPattern = "K . h . S . h . K . K . S . h h",
        drumFill = "K . h . S . h . K . S S S . S S",
        arpVol = 0.035f,
    )

    val BOARD = Song(
        id = "board", title = "GRANDMASTER", bpm = 84,
        chords = listOf("Dm", "Gm", "A7", "Dm", "Bb", "Gm", "A", "A7"),
        lead = """
            D5 - - - F5 - - - A5 - - - - - . .
            G5 - - - Bb5 - - - D6 - - - C6 - Bb5 -
            A5 - - - C#6 - - - E6 - - - G5 - - -
            F5 - - - E5 - - - D5 - - - - - . .
            D6 - - - C6 - - - Bb5 - - - A5 - - -
            G5 - - - A5 - Bb5 - D6 - - - Bb5 - - -
            A5 - - - - - - - E5 - - - C#6 - - -
            A5 - - - G5 - - - E5 - - - C#5 - - -
        """,
        leadWave = Wave.SQUARE12, leadVol = 0.11f,
        bassPattern = "R - - - - - - - 5 - - - - - - -",
        arpPattern = "0 . 1 . 2 . 3 . 2 . 1 . 0 . . .",
        drumPattern = "k . . . . . . . h . . . . . . .",
        arpVol = 0.04f,
    )

    val CAT = Song(
        id = "cat", title = "KITTY WALTZ", bpm = 120,
        chords = listOf("G", "Em", "C", "D", "G", "Em", "C", "D"),
        lead = """
            B5 . D6 . B5 . G5 . A5 . B5 . A5 . G5 .
            E5 . G5 . B5 . G5 . E5 - - . . . . .
            C6 . E6 . C6 . G5 . A5 . C6 . B5 . A5 .
            F#5 . A5 . D6 - - . C6 . B5 . A5 . . .
            G5 . B5 . D6 . G6 - - . F#6 . E6 . D6 .
            E6 - - . B5 . G5 . E5 . G5 . B5 . . .
            C6 . B5 . A5 . G5 . E5 . G5 . C6 . E6 .
            D6 - - - A5 - - - F#5 - - - D5 - - -
        """,
        leadWave = Wave.SQUARE12, leadVol = 0.13f,
        bassPattern = "R . . . 5 . . . O . . . 5 . . .",
        arpPattern = ". . 1 2 . . 1 2 . . 1 2 . . 1 2",
        drumPattern = "k . . . h . h . k . . . h . h .",
    )

    val ALL = listOf(MENU, PUZZLE, ARCADE, BOARD, CAT)

    /** Chord symbol → MIDI notes (root in octave 3 for bass use, tones in octave 4). */
    fun chordTones(symbol: String): List<Int> {
        var i = 1
        var name = symbol.substring(0, 1)
        if (symbol.length > 1 && (symbol[1] == '#' || symbol[1] == 'b')) { name += symbol[1]; i = 2 }
        val quality = symbol.substring(i)
        val root = Synth.parseNote(name + "4")!!
        val ints = when (quality) {
            "m" -> listOf(0, 3, 7, 12)
            "7" -> listOf(0, 4, 7, 10)
            "m7" -> listOf(0, 3, 7, 10)
            "maj7" -> listOf(0, 4, 7, 11)
            "sus4" -> listOf(0, 5, 7, 12)
            else -> listOf(0, 4, 7, 12)
        }
        return ints.map { root + it }
    }

    private fun tokens(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "|" }

    /** Renders [song] to a mono float buffer that loops seamlessly. */
    fun render(song: Song): FloatArray {
        val stepSamples = (Synth.RATE * 60.0 / song.bpm / 4.0).toInt()
        val total = stepSamples * song.steps
        val buf = FloatArray(total)
        val stepSec = stepSamples / Synth.RATE.toFloat()

        // ---- lead
        val lead = tokens(song.lead)
        var i = 0
        while (i < lead.size && i < song.steps) {
            val m = Synth.parseNote(lead[i])
            if (m != null) {
                var len = 1
                while (i + len < lead.size && lead[i + len] == "-") len++
                Synth.addNote(
                    buf, i * stepSamples, (len * stepSamples * 0.92f).toInt(), Synth.midiToFreq(m),
                    song.leadWave, song.leadVol, decay = 0.12f, sustain = 0.55f, vibrato = len >= 3,
                )
                i += len
            } else i++
        }

        // ---- bass, arp, drums from chords
        val bassPat = tokens(song.bassPattern)
        val arpPat = tokens(song.arpPattern)
        val drumPat = tokens(song.drumPattern)
        val fillPat = tokens(song.drumFill)
        for (bar in 0 until song.bars) {
            val tones = chordTones(song.chords[bar])
            val root = tones[0] - 24 // bass octave 2
            val barStart = bar * STEPS_PER_BAR
            renderPattern(bassPat) { step, tok, len ->
                val note = when (tok) {
                    "R" -> root; "O" -> root + 12; "5" -> root + 7
                    "3" -> root + (tones[1] - tones[0]); else -> null
                }
                if (note != null) Synth.addNote(
                    buf, (barStart + step) * stepSamples, (len * stepSamples * 0.85f).toInt(),
                    Synth.midiToFreq(note), Wave.TRIANGLE, song.bassVol, decay = 0.05f, sustain = 0.8f,
                )
            }
            renderPattern(arpPat) { step, tok, len ->
                val idx = tok.toIntOrNull()
                if (idx != null) Synth.addNote(
                    buf, (barStart + step) * stepSamples, (len * stepSamples * 0.8f).toInt(),
                    Synth.midiToFreq(tones[idx % tones.size] + 12), Wave.SQUARE12, song.arpVol,
                    decay = 0.06f, sustain = 0.4f, release = 0.02f,
                )
            }
            val dp = if (bar == song.bars - 1) fillPat else drumPat
            for (s in 0 until minOf(16, dp.size)) {
                val at = (barStart + s) * stepSamples
                val v = song.drumVol
                when (dp[s]) {
                    "k" -> Synth.addKick(buf, at, 0.30f * v)
                    "s" -> Synth.addSnare(buf, at, 0.10f * v)
                    "h" -> Synth.addHat(buf, at, 0.05f * v)
                    "o" -> Synth.addHat(buf, at, 0.05f * v, open = true)
                    "K" -> { Synth.addKick(buf, at, 0.30f * v); Synth.addHat(buf, at, 0.04f * v) }
                    "S" -> { Synth.addSnare(buf, at, 0.10f * v); Synth.addHat(buf, at, 0.04f * v) }
                }
            }
        }
        // soft limiter so peaks never clip harshly
        for (k in buf.indices) {
            val x = buf[k]
            buf[k] = if (x > 0.9f) 0.9f + (x - 0.9f) * 0.2f else if (x < -0.9f) -0.9f + (x + 0.9f) * 0.2f else x
        }
        if (stepSec <= 0f) return FloatArray(Synth.RATE)
        return buf
    }

    private inline fun renderPattern(pat: List<String>, fn: (step: Int, tok: String, len: Int) -> Unit) {
        var s = 0
        while (s < minOf(16, pat.size)) {
            val tok = pat[s]
            if (tok == "." || tok == "-") { s++; continue }
            var len = 1
            while (s + len < pat.size && s + len < 16 && pat[s + len] == "-") len++
            fn(s, tok, len)
            s += len
        }
    }

    /** Number of step tokens in the lead line (used by tests to validate songs). */
    fun leadSteps(song: Song) = tokens(song.lead).size
    fun patternSteps(p: String) = tokens(p).size
}
