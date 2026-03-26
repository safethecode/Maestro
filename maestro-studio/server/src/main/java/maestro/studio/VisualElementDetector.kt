package maestro.studio

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object VisualElementDetector {

    data class DetectedRegion(
        val bounds: UIElementBounds,
        val confidence: Double
    )

    fun detect(screenshotFile: File, deviceWidth: Int, deviceHeight: Int): List<DetectedRegion> {
        val image = ImageIO.read(screenshotFile) ?: return emptyList()

        val scaleX = deviceWidth.toDouble() / image.width
        val scaleY = deviceHeight.toDouble() / image.height

        val grayscale = toGrayscale(image)
        val edges = sobelEdgeDetection(grayscale)
        val binary = binarize(edges, adaptiveThreshold(edges))
        val contours = findContours(binary)

        val minArea = (deviceWidth * deviceHeight * 0.001).toInt()
        val maxArea = (deviceWidth * deviceHeight * 0.95).toInt()

        return contours
            .map { rect ->
                DetectedRegion(
                    bounds = UIElementBounds(
                        x = (rect.x * scaleX).toInt(),
                        y = (rect.y * scaleY).toInt(),
                        width = (rect.width * scaleX).toInt(),
                        height = (rect.height * scaleY).toInt()
                    ),
                    confidence = rect.confidence
                )
            }
            .filter { region ->
                val area = region.bounds.width * region.bounds.height
                area in minArea..maxArea
            }
            .let { mergeOverlapping(it) }
            .sortedByDescending { it.confidence }
    }

    private fun toGrayscale(image: BufferedImage): Array<IntArray> {
        val w = image.width
        val h = image.height
        val result = Array(h) { IntArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                result[y][x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
        }
        return result
    }

    private fun sobelEdgeDetection(gray: Array<IntArray>): Array<IntArray> {
        val h = gray.size
        val w = gray[0].size
        val result = Array(h) { IntArray(w) }

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = -gray[y-1][x-1] + gray[y-1][x+1] +
                         -2*gray[y][x-1] + 2*gray[y][x+1] +
                         -gray[y+1][x-1] + gray[y+1][x+1]

                val gy = -gray[y-1][x-1] - 2*gray[y-1][x] - gray[y-1][x+1] +
                          gray[y+1][x-1] + 2*gray[y+1][x] + gray[y+1][x+1]

                result[y][x] = min(255, sqrt((gx * gx + gy * gy).toDouble()).toInt())
            }
        }
        return result
    }

    private fun adaptiveThreshold(edges: Array<IntArray>): Int {
        var sum = 0L
        var count = 0
        for (row in edges) {
            for (v in row) {
                if (v > 0) {
                    sum += v
                    count++
                }
            }
        }
        if (count == 0) return 50
        return max(30, min(100, (sum / count).toInt()))
    }

    private fun binarize(edges: Array<IntArray>, threshold: Int): Array<BooleanArray> {
        return Array(edges.size) { y ->
            BooleanArray(edges[0].size) { x -> edges[y][x] >= threshold }
        }
    }

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int, val confidence: Double)

    private fun findContours(binary: Array<BooleanArray>): List<Rect> {
        val h = binary.size
        val w = binary[0].size
        val visited = Array(h) { BooleanArray(w) }
        val rects = mutableListOf<Rect>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (binary[y][x] && !visited[y][x]) {
                    var minX = x; var maxX = x; var minY = y; var maxY = y
                    var edgePixels = 0
                    val stack = ArrayDeque<Pair<Int, Int>>()
                    stack.addLast(x to y)

                    while (stack.isNotEmpty()) {
                        val (cx, cy) = stack.removeLast()
                        if (cx < 0 || cx >= w || cy < 0 || cy >= h) continue
                        if (visited[cy][cx] || !binary[cy][cx]) continue
                        visited[cy][cx] = true
                        edgePixels++
                        minX = min(minX, cx); maxX = max(maxX, cx)
                        minY = min(minY, cy); maxY = max(maxY, cy)

                        stack.addLast(cx + 1 to cy)
                        stack.addLast(cx - 1 to cy)
                        stack.addLast(cx to cy + 1)
                        stack.addLast(cx to cy - 1)
                    }

                    val rw = maxX - minX
                    val rh = maxY - minY
                    if (rw < 10 || rh < 10) continue

                    val perimeter = 2.0 * (rw + rh)
                    val confidence = min(1.0, edgePixels / perimeter)

                    if (confidence > 0.3) {
                        rects.add(Rect(minX, minY, rw, rh, confidence))
                    }
                }
            }
        }
        return rects
    }

    private fun mergeOverlapping(regions: List<DetectedRegion>): List<DetectedRegion> {
        if (regions.isEmpty()) return regions
        val sorted = regions.sortedBy { it.bounds.x }
        val merged = mutableListOf<DetectedRegion>()

        for (region in sorted) {
            val existing = merged.indexOfFirst { overlapRatio(it.bounds, region.bounds) > 0.7 }
            if (existing >= 0) {
                val e = merged[existing]
                if (region.confidence > e.confidence) {
                    merged[existing] = region
                }
            } else {
                merged.add(region)
            }
        }
        return merged
    }

    private fun overlapRatio(a: UIElementBounds, b: UIElementBounds): Double {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.width, b.x + b.width)
        val y2 = min(a.y + a.height, b.y + b.height)

        if (x2 <= x1 || y2 <= y1) return 0.0

        val intersection = (x2 - x1).toLong() * (y2 - y1)
        val areaA = a.width.toLong() * a.height
        val areaB = b.width.toLong() * b.height
        val smallerArea = min(areaA, areaB)

        return if (smallerArea == 0L) 0.0 else intersection.toDouble() / smallerArea
    }
}
