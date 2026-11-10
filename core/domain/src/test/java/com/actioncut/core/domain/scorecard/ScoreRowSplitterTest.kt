package com.actioncut.core.domain.scorecard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreRowSplitterTest {

    private val white = 0xFFFFFFFF.toInt()
    private val sep = 0xFFEBEBEB.toInt() // light gray separator, like scoreboard apps draw
    private val ink = 0xFF202020.toInt()

    /** 5 rows of [rowH] white pixels separated by 1px gray lines. */
    private fun scoreboard(width: Int, rowH: Int, rows: Int): Pair<IntArray, Int> {
        val height = rows * rowH + (rows - 1)
        val px = IntArray(width * height) { white }
        var y = 0
        for (r in 0 until rows) {
            // some "text" in the row so it is not blank
            for (x in 20 until 60) px[(y + rowH / 2) * width + x] = ink
            y += rowH
            if (r < rows - 1) {
                for (x in 0 until width) px[y * width + x] = sep
                y += 1
            }
        }
        return px to height
    }

    @Test
    fun splitsIntoEveryRow() {
        val (px, h) = scoreboard(width = 400, rowH = 60, rows = 5)
        val rows = ScoreRowSplitter.rows(px, 400, h, referenceHeight = h)
        assertEquals(5, rows.size)
    }

    @Test
    fun cutsSitOnTheSeparatorLines() {
        val (px, h) = scoreboard(width = 400, rowH = 60, rows = 4)
        val cuts = ScoreRowSplitter.boundaries(px, 400, h, referenceHeight = h)
        assertEquals(0, cuts.first())
        assertEquals(h, cuts.last())
        // separators are at y = 60, 121, 182
        assertTrue(cuts.contains(60))
        assertTrue(cuts.contains(121))
        assertTrue(cuts.contains(182))
    }

    @Test
    fun blankImageIsOneRow() {
        val px = IntArray(200 * 100) { white }
        val rows = ScoreRowSplitter.rows(px, 200, 100, referenceHeight = 100)
        assertEquals(1, rows.size)
    }
}
