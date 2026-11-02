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

    override fun layout() {
        contentH = app.p * 30 + entries.sumOf { rowH(it).toDouble() }.toFloat() + app.p * 10
        scroller.setContentHeight(contentH, content.height())
    }

    override fun update(dt: Float) = scroller.update(dt)

    override fun draw(c: Canvas) {
        val p = app.p
        val colW = minOf(content.width() - p * 8, p * 220)
        val left = (app.w - colW) / 2
        val right = left + colW
        c.save()
        c.clipRect(content)
        c.translate(0f, -scroller.offset)
        var y = content.top + p * 6
        val total = entries.count { it.group == null }
        val got = entries.count { it.group == null && app.storage.isUnlocked(it.id) }
        Gfx.text(c, "$got / $total UNLOCKED", left, y, app.tBody, Pal.YELLOW)
        y += PixelFont.height(app.tBody) + p * 4
        // progress bar
        Gfx.rect(c, left, y, right, y + p * 6, Pal.BG1)
        Gfx.rect(c, left + p, y + p, left + p + (colW - p * 2) * (got.toFloat() / maxOf(1, total)), y + p * 5, Pal.GREEN)
        y += p * 14
        val viewTop = scroller.offset + content.top - p * 30
        val viewBottom = scroller.offset + content.bottom + p * 30
        for (e in entries) {
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
        c.restore()
        scroller.drawBar(c, content)
        if (total == 0) Gfx.text(c, "NO AWARDS", app.w / 2, content.centerY(), app.tBody, Pal.GREY, Align.CENTER)
    }

    override fun touch(e: MotionEvent): Boolean {
        scroller.touch(e)
        return true
    }
}
