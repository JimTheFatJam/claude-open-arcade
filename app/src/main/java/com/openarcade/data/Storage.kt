package com.openarcade.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Persistent per-game statistics. */
class GameStats(
    var played: Int = 0,
    var wins: Int = 0,
    var losses: Int = 0,
    var best: Int = 0,             // best score (higher is better)
    var bestTime: Int = 0,         // best completion time in seconds (lower is better, 0 = none)
    var totalTime: Long = 0,       // seconds spent playing
    var lastPlayed: Long = 0,
    var streak: Int = 0,
    var bestStreak: Int = 0,
    val topScores: MutableList<Int> = ArrayList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("played", played).put("wins", wins).put("losses", losses)
        .put("best", best).put("bestTime", bestTime).put("totalTime", totalTime)
        .put("last", lastPlayed).put("streak", streak).put("bestStreak", bestStreak)
        .put("top", JSONArray(topScores))

    companion object {
        fun fromJson(o: JSONObject?): GameStats {
            if (o == null) return GameStats()
            val s = GameStats(
                o.optInt("played"), o.optInt("wins"), o.optInt("losses"), o.optInt("best"),
                o.optInt("bestTime"), o.optLong("totalTime"), o.optLong("last"),
                o.optInt("streak"), o.optInt("bestStreak"),
            )
            val top = o.optJSONArray("top")
            if (top != null) for (i in 0 until top.length()) s.topScores.add(top.optInt(i))
            return s
        }
    }
}

/**
 * All local persistence: settings, favourites, recents, statistics, achievements, tutorials seen
 * and saved games. Backed by a single SharedPreferences file — nothing ever leaves the device.
 */
class Storage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("open_arcade", Context.MODE_PRIVATE)
    private val statsCache = HashMap<String, GameStats>()

    // ---- settings -------------------------------------------------------------------------
    var musicOn: Boolean
        get() = prefs.getBoolean("music_on", true)
        set(v) = prefs.edit().putBoolean("music_on", v).apply()
    var sfxOn: Boolean
        get() = prefs.getBoolean("sfx_on", true)
        set(v) = prefs.edit().putBoolean("sfx_on", v).apply()
    /** 0..10 */
    var musicVolume: Int
        get() = prefs.getInt("music_vol", 6)
        set(v) = prefs.edit().putInt("music_vol", v.coerceIn(0, 10)).apply()
    var sfxVolume: Int
        get() = prefs.getInt("sfx_vol", 7)
        set(v) = prefs.edit().putInt("sfx_vol", v.coerceIn(0, 10)).apply()
    var scanlines: Boolean
        get() = prefs.getBoolean("scanlines", true)
        set(v) = prefs.edit().putBoolean("scanlines", v).apply()
    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(v) = prefs.edit().putBoolean("vibration", v).apply()
    /** 0 = auto rotate, 1 = portrait, 2 = landscape */
    var orientation: Int
        get() = prefs.getInt("orientation", 0)
        set(v) = prefs.edit().putInt("orientation", v).apply()

    // ---- favourites & recents ---------------------------------------------------------------
    private val listCache = HashMap<String, List<String>>()

    private fun idList(key: String): MutableList<String> {
        listCache[key]?.let { return it.toMutableList() }
        val arr = try { JSONArray(prefs.getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        val list = List(arr.length()) { arr.optString(it) }
        listCache[key] = list
        return list.toMutableList()
    }

    private fun putIdList(key: String, list: List<String>) {
        listCache[key] = list.toList()
        prefs.edit().putString(key, JSONArray(list).toString()).apply()
    }

    fun favorites(): List<String> = idList("favorites")
    fun isFavorite(id: String) = (listCache["favorites"] ?: favorites()).contains(id)
    fun toggleFavorite(id: String): Boolean {
        val list = idList("favorites")
        val now = if (list.remove(id)) false else { list.add(0, id); true }
        putIdList("favorites", list)
        return now
    }

    fun recents(): List<String> = idList("recents")
    fun markRecent(id: String) {
        val list = idList("recents")
        list.remove(id)
        list.add(0, id)
        while (list.size > 10) list.removeAt(list.size - 1)
        putIdList("recents", list)
    }

    // ---- stats ------------------------------------------------------------------------------
    fun stats(id: String): GameStats = statsCache.getOrPut(id) {
        GameStats.fromJson(prefs.getString("stats_$id", null)?.let { safeJson(it) })
    }

    fun saveStats(id: String, s: GameStats) {
        statsCache[id] = s
        prefs.edit().putString("stats_$id", s.toJson().toString()).apply()
    }

    // ---- achievements -----------------------------------------------------------------------
    private var achCache: JSONObject? = null
    private fun achObj(): JSONObject {
        achCache?.let { return it }
        val o = safeJson(prefs.getString("achievements", "{}") ?: "{}") ?: JSONObject()
        achCache = o
        return o
    }
    fun isUnlocked(achId: String) = achObj().has(achId)
    fun unlockedCount() = achObj().length()
    /** Returns true if this call newly unlocked the achievement. */
    fun unlock(achId: String): Boolean {
        val o = achObj()
        if (o.has(achId)) return false
        o.put(achId, System.currentTimeMillis())
        prefs.edit().putString("achievements", o.toString()).apply()
        return true
    }

    // ---- tutorials --------------------------------------------------------------------------
    fun tutorialSeen(id: String) = prefs.getBoolean("tut_$id", false)
    fun setTutorialSeen(id: String) = prefs.edit().putBoolean("tut_$id", true).apply()
    fun resetTutorials() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("tut_") }.forEach { e.remove(it) }
        e.apply()
    }

    // ---- difficulty & saved games ------------------------------------------------------------
    fun difficulty(id: String) = prefs.getInt("diff_$id", 1)
    fun setDifficulty(id: String, d: Int) = prefs.edit().putInt("diff_$id", d).apply()

    fun savedGame(id: String): JSONObject? = prefs.getString("save_$id", null)?.let { safeJson(it) }
    fun hasSave(id: String) = prefs.contains("save_$id")
    fun putSave(id: String, o: JSONObject?) {
        if (o == null) prefs.edit().remove("save_$id").apply()
        else prefs.edit().putString("save_$id", o.toString()).apply()
    }

    /** Generic per-game key/value storage (e.g. puzzle progress, unlocked levels). */
    fun gameInt(id: String, key: String, def: Int = 0) = prefs.getInt("g_${id}_$key", def)
    fun setGameInt(id: String, key: String, v: Int) = prefs.edit().putInt("g_${id}_$key", v).apply()

    fun resetAll() {
        statsCache.clear()
        listCache.clear()
        achCache = null
        prefs.edit().clear().apply()
    }

    private fun safeJson(s: String): JSONObject? = try { JSONObject(s) } catch (e: Exception) { null }
}
