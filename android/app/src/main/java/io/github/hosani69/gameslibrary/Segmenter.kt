package io.github.hosani69.gameslibrary

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in pixel coordinates (plain Kotlin so it can be tested off the phone). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width.toLong() * height
    fun scaled(f: Double) = Box((left * f).roundToInt(), (top * f).roundToInt(), (right * f).roundToInt(), (bottom * f).roundToInt())
}

/**
 * Splits a photo of several items lying on a table or shelf into one box per item.
 *
 * 1. The photo's border is taken as the background colour; everything clearly different is an item.
 * 2. Separate blobs become separate items.
 * 3. Items touching each other form one wide (or tall) blob; it is cut where a straight edge runs
 *    across most of the blob, which is where one box ends and the next begins.
 */
class Segmenter(private val pixels: IntArray, private val w: Int, private val h: Int) {
    private val gray = IntArray(w * h) { i ->
        val p = pixels[i]
        (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
    }
    val mask: BooleanArray = foregroundMask()

    /** Boxes of the items, biggest gaps first resolved, in reading order. Empty when nothing stands out. */
    fun items(): List<Box> {
        val blobs = components().filter { b ->
            b.box.area >= w.toLong() * h * 0.02 && b.count >= b.box.area * 0.35
        }
        // One blob filling the whole frame means the background could not be told apart.
        if (blobs.size == 1 && blobs[0].box.area >= w.toLong() * h * 0.9) return emptyList()
        return readingOrder(blobs.flatMap { split(it.box) })
    }

    /** Cuts the area into an even grid, then trims each cell to the item inside it. */
    fun grid(cols: Int, rows: Int): List<Box> {
        val area = contentBox() ?: Box(0, 0, w, h)
        val cells = mutableListOf<Box>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val cell = Box(
                area.left + area.width * c / cols, area.top + area.height * r / rows,
                area.left + area.width * (c + 1) / cols, area.top + area.height * (r + 1) / rows,
            )
            cells += trim(cell)
        }
        return cells
    }

    private fun foregroundMask(): BooleanArray {
        val ring = max(2, min(w, h) / 60)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x >= ring && y >= ring && x < w - ring && y < h - ring) continue
            val p = pixels[y * w + x]
            rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255
        }
        val mr = median(rs); val mg = median(gs); val mb = median(bs)
        val dist = { p: Int -> abs(((p shr 16) and 255) - mr) + abs(((p shr 8) and 255) - mg) + abs((p and 255) - mb) }
        val borderDist = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x >= ring && y >= ring && x < w - ring && y < h - ring) continue
            borderDist += dist(pixels[y * w + x])
        }
        borderDist.sort()
        val p90 = borderDist[(borderDist.size * 0.9).toInt().coerceAtMost(borderDist.size - 1)]
        val threshold = max(60, (p90 * 1.5).toInt())
        val raw = BooleanArray(w * h) { dist(pixels[it]) > threshold }
        val r = max(2, min(w, h) / 120)
        // Close small holes (text, logos), then remove specks.
        return open(close(raw, r + 1), r)
    }

    private data class Blob(val box: Box, val count: Int)

    private fun components(): List<Blob> {
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val out = mutableListOf<Blob>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var l = w; var t = h; var rr = 0; var b = 0; var n = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                n++
                if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > b) b = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
            }
            out += Blob(Box(l, t, rr + 1, b + 1), n)
        }
        return out
    }

    /**
     * Splits a blob made of touching items along strong straight edges. Tries columns first, then
     * rows, and repeats on each piece, so a 2x2 block of boxes also comes apart. A split is only kept
     * when every piece still has the shape of a box, cover or console.
     */
    private fun split(box: Box, depth: Int = 0): List<Box> {
        if (depth >= 4) return listOf(box)
        for (vertical in listOf(true, false)) {
            val long = if (vertical) box.width >= box.height * 1.1 else box.height >= box.width * 1.1
            val minGap = (min(box.width, box.height) * 0.3).toInt()
            val cuts = cuts(box, vertical, minGap, if (long) 0.6 else 0.72)
            if (cuts.isEmpty()) continue
            val pieces = slices(box, cuts, vertical).flatMap { trimAndKeep(it) }.flatMap { split(it, depth + 1) }
            if (pieces.size > 1 && pieces.all { plausible(it) }) return pieces
        }
        return listOf(box)
    }

    private fun plausible(b: Box): Boolean {
        val ratio = b.width.toDouble() / max(1, b.height)
        return ratio in 0.4..2.5
    }

    private fun trimAndKeep(b: Box): List<Box> {
        val t = trim(b)
        return if (t.area >= b.area * 0.3) listOf(t) else emptyList()
    }

    /** Positions where an edge crosses most of the box from one side to the other. */
    private fun cuts(box: Box, vertical: Boolean, minGap: Int, need: Double): List<Int> {
        val len = if (vertical) box.width else box.height
        val across = if (vertical) box.height else box.width
        // Edge strength per pixel across the cut direction.
        val edge = BooleanArray(len * across) { idx ->
            val k = idx / across; val j = idx % across
            if (k == 0 || k == len - 1) return@BooleanArray false
            val x = if (vertical) box.left + k else box.left + j
            val y = if (vertical) box.top + j else box.top + k
            val d = if (vertical) abs(gray[y * w + x + 1] - gray[y * w + x - 1])
            else abs(gray[(y + 1) * w + x] - gray[(y - 1) * w + x])
            // A gap of background between items counts as an edge too.
            d > 24 || !mask[y * w + x]
        }
        // Photos are rarely perfectly straight, so also try edges that lean slightly either way.
        val tol = max(1, (across * 0.035).toInt())
        val slants = listOf(0, tol / 2, -tol / 2, tol, -tol).distinct()
        val score = DoubleArray(len)
        for (k in 1 until len - 1) {
            for (slant in slants) {
                var strong = 0
                for (j in 0 until across) {
                    val kk = k + ((j - across / 2) * slant * 2) / across
                    if (kk in 0 until len && edge[kk * across + j]) strong++
                }
                score[k] = max(score[k], strong.toDouble() / across)
            }
        }
        // Strongest positions first, keeping them apart by at least one item's width.
        val order = (0 until len).filter { score[it] >= need && it >= minGap && it <= len - minGap }
            .sortedByDescending { score[it] }
        val picked = mutableListOf<Int>()
        for (k in order) if (picked.all { abs(it - k) >= minGap }) picked += k
        return picked.sorted().map { (if (vertical) box.left else box.top) + it }
    }

    private fun slices(box: Box, cuts: List<Int>, vertical: Boolean): List<Box> {
        val edges = listOf(if (vertical) box.left else box.top) + cuts + listOf(if (vertical) box.right else box.bottom)
        return edges.zipWithNext { a, b ->
            if (vertical) Box(a, box.top, b, box.bottom) else Box(box.left, a, box.right, b)
        }
    }

    /** Shrinks a box to the item pixels inside it. */
    private fun trim(b: Box): Box {
        var l = b.right; var t = b.bottom; var r = b.left; var bt = b.top; var n = 0
        for (y in b.top until b.bottom) for (x in b.left until b.right) {
            if (!mask[y * w + x]) continue
            n++
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
        }
        return if (n < b.area * 0.25) b else Box(l, t, r + 1, bt + 1)
    }

    private fun contentBox(): Box? {
        val blobs = components().filter { it.box.area >= w.toLong() * h * 0.01 }
        if (blobs.isEmpty()) return null
        return Box(blobs.minOf { it.box.left }, blobs.minOf { it.box.top }, blobs.maxOf { it.box.right }, blobs.maxOf { it.box.bottom })
            .takeIf { it.area < w.toLong() * h * 0.95 }
    }

    private fun readingOrder(boxes: List<Box>): List<Box> {
        if (boxes.isEmpty()) return boxes
        val rowHeight = max(1, boxes.minOf { it.height } / 2)
        return boxes.sortedWith(compareBy({ (it.top + it.height / 2) / rowHeight }, { it.left }))
    }

    private fun close(m: BooleanArray, r: Int) = erode(dilate(m, r), r)
    private fun open(m: BooleanArray, r: Int) = dilate(erode(m, r), r)

    private fun dilate(m: BooleanArray, r: Int): BooleanArray {
        val s = integral(m)
        return BooleanArray(w * h) { i -> windowSum(s, i % w, i / w, r) > 0 }
    }

    private fun erode(m: BooleanArray, r: Int): BooleanArray {
        val s = integral(m)
        return BooleanArray(w * h) { i ->
            val x = i % w; val y = i / w
            val full = (min(w - 1, x + r) - max(0, x - r) + 1) * (min(h - 1, y + r) - max(0, y - r) + 1)
            windowSum(s, x, y, r) == full
        }
    }

    private fun integral(m: BooleanArray): IntArray {
        val s = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                if (m[y * w + x]) row++
                s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + row
            }
        }
        return s
    }

    private fun windowSum(s: IntArray, x: Int, y: Int, r: Int): Int {
        val x0 = max(0, x - r); val y0 = max(0, y - r); val x1 = min(w, x + r + 1); val y1 = min(h, y + r + 1)
        return s[y1 * (w + 1) + x1] - s[y0 * (w + 1) + x1] - s[y1 * (w + 1) + x0] + s[y0 * (w + 1) + x0]
    }

    private fun median(v: ArrayList<Int>): Int { v.sort(); return v[v.size / 2] }
}
