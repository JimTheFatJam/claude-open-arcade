package com.openarcade.gfx

/**
 * The Open Arcade palette. Every screen and every game draws with these colours so the whole
 * console shares one cohesive 8-bit identity.
 */
object Pal {
    const val BG0 = 0xFF0F0F23.toInt()      // deep night background
    const val BG1 = 0xFF1B1B3A.toInt()      // panels
    const val BG2 = 0xFF2E2E5E.toInt()      // raised panels
    const val BG3 = 0xFF44448A.toInt()      // highlights on panels
    const val BLACK = 0xFF07070F.toInt()
    const val WHITE = 0xFFF4F4F4.toInt()
    const val LGREY = 0xFFC2C2D6.toInt()
    const val GREY = 0xFF8B8BA7.toInt()
    const val DGREY = 0xFF4A4A6A.toInt()
    const val RED = 0xFFFF4D6D.toInt()
    const val DRED = 0xFFA8243F.toInt()
    const val ORANGE = 0xFFFF9F1C.toInt()
    const val YELLOW = 0xFFFFE066.toInt()
    const val GREEN = 0xFF5CE65C.toInt()
    const val DGREEN = 0xFF2A9D4A.toInt()
    const val CYAN = 0xFF4DE1FF.toInt()
    const val BLUE = 0xFF3A6EFF.toInt()
    const val DBLUE = 0xFF2340A8.toInt()
    const val PURPLE = 0xFFA65CFF.toInt()
    const val PINK = 0xFFFF77C8.toInt()
    const val BROWN = 0xFF9C5B3B.toInt()
    const val DBROWN = 0xFF5E3423.toInt()
    const val PEACH = 0xFFFFC8A2.toInt()
    const val TAN = 0xFFE0B080.toInt()

    /** Arcade accents cycled for rainbow effects (title logo, confetti, etc). */
    val RAINBOW = intArrayOf(RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK)

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** Mix two colours; t = 0 gives [a], t = 1 gives [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * k).toInt() and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun darker(c: Int, t: Float = 0.35f) = mix(c, BLACK, t)
    fun lighter(c: Int, t: Float = 0.35f) = mix(c, WHITE, t)
}
