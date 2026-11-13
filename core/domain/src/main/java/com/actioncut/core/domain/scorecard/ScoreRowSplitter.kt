package com.actioncut.core.domain.scorecard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds where one row ends and the next begins in a cricket scoreboard screenshot.
 *
 * Pure Kotlin (works on ARGB pixel arrays) so it is unit-testable on the JVM.
 */
object ScoreRowSplitter {

    private class Cluster(var first: Int, var last: Int, var count: Int)
    private class Candidate(val y: Int, val count: Int)

    fun boundaries(pixels: IntArray, width: Int, height: Int, referenceHeight: Int): List<Int> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return listOf(0, max(height, 0))
        }
        val xFrom = (width * 0.15f).toInt()
        val xTo = (width * 0.85f).toInt()

        // 1. Every pixel-row that looks like a neutral light-gray separator.
        val clusters = ArrayList<Cluster>()
        for (y in 0 until height) {
            var count = 0
            var x = xFrom
            while (x < xTo) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (abs(r - g) < 7 && abs(g - b) < 7 && r in 222..248) count++
                x += 8
            }
            if (count > 12) {
                // 2. Group consecutive gray pixel-rows into ONE line.
                val last = clusters.lastOrNull()
                if (last != null && y - last.last <= 4) {
                    last.last = y
                    last.count = max(last.count, count)
                } else {
                    clusters.add(Cluster(y, y, count))
                }
            }
        }

        // 3. Thin line -> its centre; thick band -> its top and bottom edge.
        val candidates = ArrayList<Candidate>()
        for (k in clusters) {
            if (k.last - k.first > 6) {
                candidates.add(Candidate(k.first, k.count))
                candidates.add(Candidate(k.last, k.count))
            } else {
                candidates.add(Candidate(((k.first + k.last) / 2f).roundToInt(), k.count))
            }
        }

        // 4. De-duplicate with a gap that adapts to the real row spacing.
        val ys = candidates.map { it.y }.sorted()
        var minDistance = referenceHeight * 0.045f
        var smallestGap = Int.MAX_VALUE
        for (i in 1 until ys.size) {
            val gap = ys[i] - ys[i - 1]
            if (gap > referenceHeight * 0.02f) smallestGap = min(smallestGap, gap)
        }
        if (smallestGap != Int.MAX_VALUE) minDistance = min(minDistance, smallestGap * 0.6f)
        minDistance = max(minDistance, 8f)

        val accepted = ArrayList<Int>()
        for (c in candidates.sortedByDescending { it.count }) {
            if (accepted.none { abs(it - c.y) < minDistance }) accepted.add(c.y)
        }
        accepted.add(0)
        accepted.add(height)
        accepted.sort()

        val result = ArrayList<Int>()
        for (y in accepted) {
            if (result.isEmpty() || y != result.last()) result.add(y)
        }
        return result
    }

    fun rows(pixels: IntArray, width: Int, height: Int, referenceHeight: Int): List<IntRange> {
        val cuts = boundaries(pixels, width, height, referenceHeight)
        val minRow = referenceHeight * 0.02f
        val out = ArrayList<IntRange>()
        for (i in 0 until cuts.size - 1) {
            val y0 = cuts[i]
            val y1 = cuts[i + 1]
            if (y1 - y0 >= minRow) out.add(y0 until y1)
        }
        if (out.isEmpty()) out.add(0 until max(height, 1))
        return out
    }
}
