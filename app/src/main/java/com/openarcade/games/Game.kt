package com.openarcade.games

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.audio.Sfx
import com.openarcade.audio.Song
import com.openarcade.gfx.PixelArt
import org.json.JSONObject
import kotlin.random.Random

/**
 * Library categories. Add new entries here when future games introduce new genres; the library
 * filter chips are generated from this enum automatically.
 */
enum class Category(val label: String) {
    PUZZLE("PUZZLE"),
    ARCADE("ARCADE"),
    BOARD("BOARD"),
}

enum class Difficulty(val label: String) { EASY("EASY"), NORMAL("NORMAL"), HARD("HARD") }

/** How a game's "best" is measured, used by the stats & high score displays. */
enum class ScoreKind { HIGH_SCORE, BEST_TIME, WINS }

/**
 * A tutorial page. Besides a static [art] illustration, a page can host an interactive [demo]: a
 * tiny playable board where the player tries the mechanic being explained.
 */
class TutorialPage(
    val title: String,
    val text: String,
    val art: PixelArt? = null,
    val demo: (() -> TutorialDemo)? = null,
)

/** An interactive tutorial exercise drawn inside the tutorial page. */
abstract class TutorialDemo {
    /** The instruction shown under the demo ("TRY IT: ..."). */
    abstract val task: String
    /** Short feedback after the player's last action; replaces [task] while non-empty. */
    var feedback: String = ""
    var feedbackGood = true
    /** Set once the player has completed the exercise. */
    var done = false
    /** Sound hook supplied by the tutorial screen. */
    var sound: (Sfx) -> Unit = {}

    open fun update(dt: Float) {}
    abstract fun draw(c: Canvas, box: RectF, p: Float, t: Float)
    /** Touch inside the demo's [box]. */
    abstract fun onTouch(e: MotionEvent, box: RectF)

    protected fun good(msg: String, s: Sfx = Sfx.SELECT) { feedback = msg; feedbackGood = true; sound(s) }
    protected fun bad(msg: String) { feedback = msg; feedbackGood = false; sound(Sfx.ERROR) }
    protected fun complete(msg: String) { feedback = msg; feedbackGood = true; if (!done) { done = true; sound(Sfx.COIN) } }
}

class AchievementDef(val id: String, val title: String, val desc: String)

/**
 * Static description of a game: everything the console needs to list it, explain it and start it.
 * Adding a new game = write a [GameSession] subclass + a [GameDef], then register it in
 * [GameRegistry].
 */
class GameDef(
    val id: String,
    val title: String,
    val category: Category,
    val tagline: String,
    val description: String,
    val icon: PixelArt,
    val accent: Int,
    val music: Song,
    val scoreKind: ScoreKind,
    val tutorial: List<TutorialPage>,
    val achievements: List<AchievementDef>,
    val hasDifficulty: Boolean = true,
    /** Custom labels for difficulty levels (e.g. board sizes), or null for EASY/NORMAL/HARD. */
    val difficultyLabels: List<String>? = null,
    val factory: (GameHost) -> GameSession,
) {
    fun achievementId(local: String) = "$id.$local"
    fun difficultyName(d: Difficulty) = difficultyLabels?.getOrNull(d.ordinal) ?: d.label
}

/** Result reported by a session when a round ends. */
class GameResult(
    val won: Boolean,
    val score: Int? = null,
    val timeSec: Int? = null,
    val title: String? = null,
    val detail: String? = null,
)

/** A small value shown in the HUD strip ("SCORE 1200"). */
class HudItem(val label: String, val value: String, val color: Int? = null)

/** An extra in-game control shown in the action bar (e.g. flag mode, undo, notes). */
class GameAction(
    val label: String,
    val icon: PixelArt? = null,
    val isActive: () -> Boolean = { false },
    val isEnabled: () -> Boolean = { true },
    val onClick: () -> Unit,
)

/** Services the console provides to a running game. */
interface GameHost {
    val def: GameDef
    val difficulty: Difficulty
    val rng: Random
    /** Size of one "art pixel" on screen. Games should draw in multiples of it. */
    val px: Float
    /** Seconds of active (unpaused) play in the current round. */
    val playTime: Float
    fun sfx(s: Sfx)
    fun vibrate(ms: Long = 20)
    fun unlock(localAchievementId: String)
    fun toast(msg: String)
    fun gameOver(result: GameResult)
    fun bestScore(): Int
    fun bestTime(): Int
    /** Persistent per-game values (unlocked levels etc). */
    fun getInt(key: String, def: Int = 0): Int
    fun putInt(key: String, v: Int)
}

/**
 * A running round of a game. The host gives it a rectangle to play in; the session handles input,
 * advances its own state and draws itself with the shared pixel-art helpers.
 */
abstract class GameSession(val host: GameHost) {
    /** The rectangle (in screen pixels) the game may draw into. */
    val area = RectF()

    /** Real-time games are frozen while paused; turn-based games are simply covered. */
    open val realtime: Boolean = false

    /** Set by the host when the round has ended; input is then ignored. */
    var finished = false

    val px get() = host.px

    fun layout(a: RectF) {
        area.set(a)
        onLayout()
    }

    /** Recompute cell sizes and positions after [area] changed (rotation, resize). */
    open fun onLayout() {}

    /** Start a new round, or resume from [saved] state when non-null. */
    abstract fun start(saved: JSONObject?)

    open fun update(dt: Float) {}

    abstract fun draw(c: Canvas)

    open fun onTouch(e: MotionEvent) {}

    /** Physical keys / D-pad. Return true when handled. */
    open fun onKey(keyCode: Int): Boolean = false

    /** Serialise the round so it can be continued later, or null if not resumable. */
    open fun save(): JSONObject? = null

    open fun hud(): List<HudItem> = emptyList()

    open fun actions(): List<GameAction> = emptyList()

    private var endTimer = -1f
    private var pendingResult: GameResult? = null

    /** Called by the host every frame; handles delayed endings, then [update]. */
    fun step(dt: Float) {
        if (endTimer >= 0f) {
            endTimer -= dt
            if (endTimer < 0f) pendingResult?.let { pendingResult = null; host.gameOver(it) }
        }
        update(dt)
    }

    /**
     * End the round as a win. With [delay] > 0 the game keeps animating (e.g. a victory flourish)
     * before the results panel appears; input is blocked immediately.
     */
    protected fun win(score: Int? = null, detail: String? = null, delay: Float = 0.8f, title: String? = null) =
        end(GameResult(true, score, host.playTime.toInt(), title, detail), delay)

    protected fun lose(score: Int? = null, detail: String? = null, delay: Float = 1.0f, title: String? = null) =
        end(GameResult(false, score, host.playTime.toInt(), title, detail), delay)

    private fun end(r: GameResult, delay: Float) {
        if (finished) return
        finished = true
        if (delay <= 0f) host.gameOver(r) else { pendingResult = r; endTimer = delay }
    }
}

/** Formats seconds as M:SS. */
fun formatTime(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
