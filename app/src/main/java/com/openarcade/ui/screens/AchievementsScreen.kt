package com.openarcade.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.openarcade.core.ArcadeApp
import com.openarcade.core.Progress
import com.openarcade.games.GameRegistry
import com.openarcade.gfx.Align
import com.openarcade.gfx.Gfx
import com.openarcade.gfx.Icons
import com.openarcade.gfx.Pal
import com.openarcade.gfx.PixelArt
import com.openarcade.gfx.PixelFont
import com.openarcade.ui.Scroller
import com.openarcade.ui.Screen

/** Every achievement in the arcade, grouped by game, with locked/unlocked state. */
class AchievementsScreen(app: ArcadeApp) : Screen(app) {
    override val title = "AWARDS"
    private val scroller = Scroller(app)

    private class Entry(val group: String?, val icon: PixelArt?, val id: String, val title: String, val desc: String, val accent: Int)
    private val entries = ArrayList<Entry>()
    private var contentH = 0f

    init {
        entries.add(Entry("ARCADE", Icons.TROPHY, "", "", "", Pal.YELLOW))
        for (a in Progress.GLOBAL) entries.add(Entry(null, null, a.id, a.title, a.desc, Pal.YELLOW))
        for (d in GameRegistry.all) {
            entries.add(Entry(d.title, d.icon, "", "", "", d.accent))
            for (a in d.achievements) entries.add(Entry(null, null, d.achievementId(a.id), a.title, a.desc, d.accent))
        }
    }

    private fun rowH(e: Entry) = if (e.group != null) app.p * 20 else app.p * 22

    private val wide get() = app.wideLayout

    /** Splits entries into columns at a group boundary near the middle (two columns on wide screens). */
    private fun columns(): List<List<Entry>> {
        if (!wide) return listOf(entries)
        val total = entries.sumOf { rowH(it).toDouble() }
        var acc = 0.0
        var split = entries.size
        for ((i, e) in entries.withIndex()) {
            if (e.group != null && acc >= total / 2) { split = i; break }
            acc += rowH(e)
        }
        return listOf(entries.subList(0, split), entries.subList(split, entries.size))
    }

    override fun layout() {
        val tallest = columns().maxOf { col -> col.sumOf { rowH(it).toDouble() } }.toFloat()
        contentH = app.p * 30 + tallest + app.p * 10
        scroller.setContentHeight(contentH, content.height())
    }

    override fun update(dt: Float) = scroller.update(dt)

    override fun draw(c: Canvas) {
        val p = app.p
        val cols = columns()
        val colGap = p * 10
        val colW = minOf((content.width() - p * 8 - colGap * (cols.size - 1)) / cols.size, p * 220)
        val totalW = colW * cols.size + colGap * (cols.size - 1)
        val left0 = (app.w - totalW) / 2
        c.save()
        c.clipRect(content)
        c.translate(0f, -scroller.offset)
        var y = content.top + p * 6
        val total = entries.count { it.group == null }
        val got = entries.count { it.group == null && app.storage.isUnlocked(it.id) }
        Gfx.text(c, "$got / $total UNLOCKED", left0, y, app.tBody, Pal.YELLOW)
        y += PixelFont.height(app.tBody) + p * 4
        // progress bar
        Gfx.rect(c, left0, y, left0 + totalW, y + p * 6, Pal.BG1)
        Gfx.rect(c, left0 + p, y + p, left0 + p + (totalW - p * 2) * (got.toFloat() / maxOf(1, total)), y + p * 5, Pal.GREEN)
        y += p * 14
        val viewTop = scroller.offset + content.top - p * 30
        val viewBottom = scroller.offset + content.bottom + p * 30
        val startY = y
        cols.forEachIndexed { ci, col ->
            val left = left0 + ci * (colW + colGap)
            val right = left + colW
            y = startY
            for (e in col) {
                val h = rowH(e)
                if (y + h > viewTop && y < viewBottom) {
                    if (e.group != null) {
                        e.icon?.drawFit(c, RectF(left, y + p * 4, left + p * 12, y + p * 16))
                        Gfx.text(c, e.group, left + p * 16, y + p * 7, app.tBody, e.accent)
                    } else {
                        val unlocked = app.storage.isUnlocked(e.id)
                        val box = RectF(left, y, right, y + p * 19)
                        Gfx.rect(c, box, if (unlocked) Pal.withAlpha(Pal.DGREEN, 110) else Pal.withAlpha(Pal.BG1, 200))
                        Gfx.frame(c, box, p * 0.5f, if (unlocked) Pal.GREEN else Pal.BG3)
                        val ic = if (unlocked) Icons.TROPHY else Icons.LOCK
                        ic.drawFit(c, RectF(box.left + p * 4, box.top + p * 4, box.left + p * 15, box.bottom - p * 4), tint = if (unlocked) null else Pal.DGREY)
                        Gfx.text(c, e.title, box.left + p * 19, box.top + p * 3, app.tSmall, if (unlocked) Pal.YELLOW else Pal.LGREY)
                        val ds = Gfx.fitSize(e.desc, box.width() - p * 23, app.tSmall * 0.8f)
                        Gfx.text(c, e.desc, box.left + p * 19, box.top + p * 5 + PixelFont.height(app.tSmall), ds, Pal.GREY, shadow = null)
                        if (unlocked) Icons.CHECK.drawFit(c, RectF(box.right - p * 11, box.top + p * 5, box.right - p * 3, box.bottom - p * 5), tint = Pal.GREEN)
                    }
                }
                y += h
            }
        }
        c.restore()
        scroller.drawBar(c, content)
        if (total == 0) Gfx.text(c, "NO AWARDS", app.w / 2, content.centerY(), app.tBody, Pal.GREY, Align.CENTER)
    }

    override fun touch(e: MotionEvent): Boolean {
        scroller.touch(e)
        return true
    }
}
