package com.openarcade.core

import com.openarcade.audio.Sfx
import com.openarcade.games.AchievementDef
import com.openarcade.games.GameDef
import com.openarcade.games.GameRegistry
import com.openarcade.games.GameResult
import com.openarcade.games.ScoreKind
import com.openarcade.gfx.Icons

/** Outcome of recording a finished round, used by the results panel. */
class RecordInfo(val newBest: Boolean, val best: Int, val bestTime: Int)

/** Statistics, high scores and achievements bookkeeping. */
class Progress(private val app: ArcadeApp) {

    companion object {
        val GLOBAL = listOf(
            AchievementDef("arcade.first", "FIRST CREDIT", "Play your first game."),
            AchievementDef("arcade.sampler", "SAMPLER", "Play 5 different games."),
            AchievementDef("arcade.all", "COMPLETIONIST", "Play every game in the arcade."),
            AchievementDef("arcade.wins10", "WINNER", "Win 10 rounds in total."),
            AchievementDef("arcade.wins50", "CHAMPION", "Win 50 rounds in total."),
            AchievementDef("arcade.hour", "DEDICATED", "Play for 60 minutes in total."),
            AchievementDef("arcade.fav", "FAN FAVORITE", "Add a game to your favorites."),
            AchievementDef("arcade.tutor", "STUDENT", "Read a tutorial to the end."),
        )
    }

    fun allAchievements(): List<AchievementDef> = GLOBAL + GameRegistry.all.flatMap { it.achievements.map { a -> AchievementDef(it.achievementId(a.id), a.title, a.desc) } }

    fun unlock(fullId: String) {
        if (!app.storage.unlock(fullId)) return
        val def = allAchievements().firstOrNull { it.id == fullId }
        app.audio.play(Sfx.ACHIEVE)
        app.toast("UNLOCKED: " + (def?.title ?: fullId), Icons.TROPHY)
    }

    fun roundStarted(def: GameDef) {
        val s = app.storage.stats(def.id)
        s.played++
        s.lastPlayed = System.currentTimeMillis()
        app.storage.saveStats(def.id, s)
        app.storage.markRecent(def.id)
        unlock("arcade.first")
        val playedGames = GameRegistry.all.count { app.storage.stats(it.id).played > 0 }
        if (playedGames >= 5) unlock("arcade.sampler")
        if (playedGames >= GameRegistry.all.size) unlock("arcade.all")
    }

    fun addTime(def: GameDef, seconds: Float) {
        if (seconds <= 0f) return
        val s = app.storage.stats(def.id)
        s.totalTime += seconds.toLong()
        app.storage.saveStats(def.id, s)
        if (GameRegistry.all.sumOf { app.storage.stats(it.id).totalTime } >= 3600) unlock("arcade.hour")
    }

    fun record(def: GameDef, r: GameResult): RecordInfo {
        val s = app.storage.stats(def.id)
        var newBest = false
        if (r.won) {
            s.wins++; s.streak++; s.bestStreak = maxOf(s.bestStreak, s.streak)
        } else {
            s.losses++; s.streak = 0
        }
        val score = r.score
        if (score != null && def.scoreKind == ScoreKind.HIGH_SCORE) {
            if (score > s.best) { newBest = s.best > 0 || score > 0; s.best = score }
            if (score > 0) {
                s.topScores.add(score)
                s.topScores.sortDescending()
                while (s.topScores.size > 5) s.topScores.removeAt(s.topScores.size - 1)
            }
        }
        if (r.won && r.timeSec != null && def.scoreKind == ScoreKind.BEST_TIME) {
            val t = maxOf(1, r.timeSec)
            if (s.bestTime == 0 || t < s.bestTime) { newBest = true; s.bestTime = t }
            s.topScores.add(t)
            s.topScores.sort()
            while (s.topScores.size > 5) s.topScores.removeAt(s.topScores.size - 1)
        }
        if (def.scoreKind == ScoreKind.WINS && r.won) {
            s.best = s.wins
        }
        app.storage.saveStats(def.id, s)
        val totalWins = GameRegistry.all.sumOf { app.storage.stats(it.id).wins }
        if (totalWins >= 10) unlock("arcade.wins10")
        if (totalWins >= 50) unlock("arcade.wins50")
        return RecordInfo(newBest, s.best, s.bestTime)
    }
}
