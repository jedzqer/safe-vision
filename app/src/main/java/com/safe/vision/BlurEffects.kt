package com.safe.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared image/video blur/mosaic helpers.
 */
object BlurEffects {
    data class EyeStrip(
        val bounds: Rect,
        val path: Path
    )

    private val outlinePaint: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
    }

    fun clampRect(rect: Rect, width: Int, height: Int): Rect {
        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val right = rect.right.coerceAtMost(width)
        val bottom = rect.bottom.coerceAtMost(height)
        return Rect(left, top, right, bottom)
    }

    fun capAspectRatio(rect: Rect, maxRatio: Float, surfaceWidth: Int, surfaceHeight: Int): Rect {
        if (maxRatio <= 1f) return clampRect(rect, surfaceWidth, surfaceHeight)
        val safe = clampRect(rect, surfaceWidth, surfaceHeight)
        if (safe.width() <= 0 || safe.height() <= 0) return safe

        val w = safe.width().toFloat()
        val h = safe.height().toFloat()
        val ratioWH = w / h
        if (ratioWH >= maxRatio) return safe

        val targetW = (h * maxRatio).roundToInt().coerceAtLeast(1).coerceAtMost(surfaceWidth)
        val cx = safe.exactCenterX()
        val left = (cx - targetW / 2f).roundToInt()
        val right = left + targetW
        return clampRect(Rect(left, safe.top, right, safe.bottom), surfaceWidth, surfaceHeight)
    }

    fun scaleRect(rect: Rect, scale: Float, surfaceWidth: Int, surfaceHeight: Int): Rect {
        val safeScale = scale.coerceAtLeast(1f)
        val safeRect = clampRect(rect, surfaceWidth, surfaceHeight)
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return safeRect
        if (safeScale == 1f) return safeRect

        val centerX = safeRect.exactCenterX()
        val centerY = safeRect.exactCenterY()
        val halfW = safeRect.width() * safeScale / 2f
        val halfH = safeRect.height() * safeScale / 2f
        val scaled = Rect(
            (centerX - halfW).roundToInt(),
            (centerY - halfH).roundToInt(),
            (centerX + halfW).roundToInt(),
            (centerY + halfH).roundToInt()
        )
        return clampRect(scaled, surfaceWidth, surfaceHeight)
    }

    fun cropToEyeStrip(rect: Rect, surfaceWidth: Int, surfaceHeight: Int): Rect {
        val safeRect = clampRect(rect, surfaceWidth, surfaceHeight)
        val height = safeRect.height()
        if (height <= 0) return safeRect
        val stripYCenter = safeRect.top + (height * 0.3f).toInt()
        val stripHeight = max(1, (height * 0.4f).toInt())
        val stripYStart = max(0, stripYCenter - stripHeight / 2)
        val stripYEnd = min(surfaceHeight, stripYCenter + stripHeight / 2)
        val stripRect = Rect(safeRect.left, stripYStart, safeRect.right, stripYEnd)
        return clampRect(stripRect, surfaceWidth, surfaceHeight)
    }

    fun eyeStripFromEyes(
        faceRect: Rect,
        leftEye: PointF,
        rightEye: PointF,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): EyeStrip? {
        val safeFace = clampRect(faceRect, surfaceWidth, surfaceHeight)
        if (safeFace.width() <= 0 || safeFace.height() <= 0) return null

        val baseStrip = cropToEyeStrip(safeFace, surfaceWidth, surfaceHeight)
        if (baseStrip.width() <= 0 || baseStrip.height() <= 0) return null

        val lx = leftEye.x.coerceIn(safeFace.left.toFloat(), safeFace.right.toFloat())
        val rx = rightEye.x.coerceIn(safeFace.left.toFloat(), safeFace.right.toFloat())
        val ly = leftEye.y.coerceIn(safeFace.top.toFloat(), safeFace.bottom.toFloat())
        val ry = rightEye.y.coerceIn(safeFace.top.toFloat(), safeFace.bottom.toFloat())

        val eyeDx = rx - lx
        val eyeDy = ry - ly
        val eyeDistance = hypot(eyeDx, eyeDy).coerceAtLeast(1f)
        val angle = atan2(eyeDy, eyeDx)

        val barThickness = baseStrip.height().toFloat().coerceAtLeast(1f)
        var barLength = baseStrip.width().toFloat().coerceAtLeast(1f)
        val minLengthByEyes = eyeDistance * 1.15f
        if (barLength < eyeDistance) {
            barLength = minLengthByEyes
        }

        val ux = cos(angle)
        val uy = sin(angle)
        val nx = -uy
        val ny = ux
        val centerX = (lx + rx) / 2f
        val centerY = (ly + ry) / 2f

        val halfL = barLength / 2f
        val halfT = barThickness / 2f
        val p1 = PointF(centerX - ux * halfL + nx * halfT, centerY - uy * halfL + ny * halfT)
        val p2 = PointF(centerX + ux * halfL + nx * halfT, centerY + uy * halfL + ny * halfT)
        val p3 = PointF(centerX + ux * halfL - nx * halfT, centerY + uy * halfL - ny * halfT)
        val p4 = PointF(centerX - ux * halfL - nx * halfT, centerY - uy * halfL - ny * halfT)
        val points = arrayOf(p1, p2, p3, p4)

        val minX = points.minOf { it.x }.roundToInt()
        val maxX = points.maxOf { it.x }.roundToInt()
        val minY = points.minOf { it.y }.roundToInt()
        val maxY = points.maxOf { it.y }.roundToInt()
        val finalBounds = clampRect(Rect(minX, minY, maxX, maxY), surfaceWidth, surfaceHeight)
        if (finalBounds.width() <= 0 || finalBounds.height() <= 0) return null

        val path = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            lineTo(p4.x, p4.y)
            close()
        }
        return EyeStrip(bounds = finalBounds, path = path)
    }

    fun eyeBarFromEyes(
        faceRect: Rect,
        leftEye: PointF,
        rightEye: PointF,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): Rect {
        return eyeStripFromEyes(faceRect, leftEye, rightEye, surfaceWidth, surfaceHeight)?.bounds
            ?: cropToEyeStrip(faceRect, surfaceWidth, surfaceHeight)
    }

    fun circumscribedCircleBounds(rect: Rect, surfaceWidth: Int, surfaceHeight: Int): Rect {
        val safeRect = clampRect(rect, surfaceWidth, surfaceHeight)
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return safeRect
        val cx = safeRect.exactCenterX()
        val cy = safeRect.exactCenterY()
        val radius = hypot(safeRect.width() / 2f, safeRect.height() / 2f)
        val left = (cx - radius).roundToInt()
        val top = (cy - radius).roundToInt()
        val right = (cx + radius).roundToInt()
        val bottom = (cy + radius).roundToInt()
        return clampRect(Rect(left, top, right, bottom), surfaceWidth, surfaceHeight)
    }

    fun drawWithCircularClip(canvas: Canvas, rect: Rect, draw: () -> Unit) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val radius = hypot(rect.width() / 2f, rect.height() / 2f)
        val path = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        val checkpoint = canvas.save()
        canvas.clipPath(path)
        draw()
        canvas.restoreToCount(checkpoint)
    }

    private fun outlineStrokeWidth(rect: Rect): Float {
        val minDim = min(rect.width(), rect.height()).toFloat()
        return max(4f, minDim * 0.04f)
    }

    fun drawRectOutline(canvas: Canvas, rect: Rect, color: Int = Color.RED) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        val stroke = outlineStrokeWidth(rect)
        val inset = stroke / 2f
        val left = rect.left + inset
        val top = rect.top + inset
        val right = rect.right - inset
        val bottom = rect.bottom - inset
        if (right <= left || bottom <= top) return
        outlinePaint.color = color
        outlinePaint.strokeWidth = stroke
        canvas.drawRect(left, top, right, bottom, outlinePaint)
    }

    fun drawCircularOutline(canvas: Canvas, rect: Rect, color: Int = Color.RED) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        val stroke = outlineStrokeWidth(rect)
        val radius = hypot(rect.width() / 2f, rect.height() / 2f) - stroke / 2f
        if (radius <= 0f) return
        outlinePaint.color = color
        outlinePaint.strokeWidth = stroke
        canvas.drawCircle(rect.exactCenterX(), rect.exactCenterY(), radius, outlinePaint)
    }

    fun drawBlack(canvas: Canvas, rect: Rect, color: Int = Color.BLACK) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        val paint = Paint().apply { this.color = color }
        canvas.drawRect(rect, paint)
    }

    fun drawMosaic(canvas: Canvas, source: Bitmap, rect: Rect, blockSize: Int = 15) {
        val safeRect = clampRect(rect, source.width, source.height)
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return

        val region = Bitmap.createBitmap(
            source,
            safeRect.left,
            safeRect.top,
            safeRect.width(),
            safeRect.height()
        )

        val mosaic = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
        val mosaicCanvas = Canvas(mosaic)
        val paint = Paint()

        for (x in 0 until region.width step blockSize) {
            for (y in 0 until region.height step blockSize) {
                val pixel = region.getPixel(x, y)
                val block = Rect(
                    x,
                    y,
                    (x + blockSize).coerceAtMost(region.width),
                    (y + blockSize).coerceAtMost(region.height)
                )
                paint.color = pixel or (0xff shl 24)
                mosaicCanvas.drawRect(block, paint)
            }
        }

        canvas.drawBitmap(mosaic, safeRect.left.toFloat(), safeRect.top.toFloat(), null)
    }

    fun drawGaussian(canvas: Canvas, source: Bitmap, rect: Rect, radius: Int = 12) {
        val safeRect = clampRect(rect, source.width, source.height)
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return

        val region = Bitmap.createBitmap(
            source,
            safeRect.left,
            safeRect.top,
            safeRect.width(),
            safeRect.height()
        )
        val blurred = gaussianBlur(region, radius)
        canvas.drawBitmap(blurred, safeRect.left.toFloat(), safeRect.top.toFloat(), null)
    }

    fun drawSticker(
        canvas: Canvas,
        sticker: Bitmap,
        rect: Rect,
        surfaceWidth: Int,
        surfaceHeight: Int,
        fitInsideRect: Boolean = false,
        rotationDegrees: Float = 0f
    ) {
        val safeRect = clampRect(rect, surfaceWidth, surfaceHeight)
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return
        if (sticker.width <= 0 || sticker.height <= 0) return

        val scale = if (fitInsideRect) {
            min(
                safeRect.width().toFloat() / sticker.width.toFloat(),
                safeRect.height().toFloat() / sticker.height.toFloat()
            ).coerceAtMost(1f)
        } else {
            val targetSide = max(safeRect.width(), safeRect.height()).toFloat()
            val stickerShortSide = min(sticker.width, sticker.height).toFloat()
            if (stickerShortSide <= 0f) return
            targetSide / stickerShortSide
        }
        val destWidth = sticker.width * scale
        val destHeight = sticker.height * scale
        val centerX = safeRect.exactCenterX()
        val centerY = safeRect.exactCenterY()
        val destRect = RectF(
            centerX - destWidth / 2f,
            centerY - destHeight / 2f,
            centerX + destWidth / 2f,
            centerY + destHeight / 2f
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (abs(rotationDegrees) > 0.01f) {
            val checkpoint = canvas.save()
            canvas.rotate(rotationDegrees, centerX, centerY)
            canvas.drawBitmap(sticker, null, destRect, paint)
            canvas.restoreToCount(checkpoint)
        } else {
            canvas.drawBitmap(sticker, null, destRect, paint)
        }
    }

    fun drawSobelEdge(canvas: Canvas, source: Bitmap, rect: Rect, scale: Float = 1f) {
        val safeRect = clampRect(rect, source.width, source.height)
        if (safeRect.width() <= 1 || safeRect.height() <= 1) return

        val region = Bitmap.createBitmap(
            source,
            safeRect.left,
            safeRect.top,
            safeRect.width(),
            safeRect.height()
        )
        val width = region.width
        val height = region.height
        val pixels = IntArray(width * height)
        region.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(width * height) { index ->
            val color = pixels[index]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            (0.299f * r + 0.587f * g + 0.114f * b).roundToInt()
        }
        val magnitudes = FloatArray(width * height)
        var maxMag = 0f

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val topLeft = gray[(y - 1) * width + (x - 1)]
                val top = gray[(y - 1) * width + x]
                val topRight = gray[(y - 1) * width + (x + 1)]
                val left = gray[y * width + (x - 1)]
                val right = gray[y * width + (x + 1)]
                val bottomLeft = gray[(y + 1) * width + (x - 1)]
                val bottom = gray[(y + 1) * width + x]
                val bottomRight = gray[(y + 1) * width + (x + 1)]

                val gx = (topRight + 2 * right + bottomRight) - (topLeft + 2 * left + bottomLeft)
                val gy = (bottomLeft + 2 * bottom + bottomRight) - (topLeft + 2 * top + topRight)
                val magnitude = hypot(gx.toFloat(), gy.toFloat())
                val index = y * width + x
                magnitudes[index] = magnitude
                if (magnitude > maxMag) {
                    maxMag = magnitude
                }
            }
        }
        if (maxMag <= 0f) return

        val factor = (255f / maxMag) * scale.coerceAtLeast(0f)
        for (i in magnitudes.indices) {
            val edge = (magnitudes[i] * factor).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(edge, edge, edge)
        }

        val sobelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        sobelBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        canvas.drawBitmap(sobelBitmap, safeRect.left.toFloat(), safeRect.top.toFloat(), null)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun stackBlur(source: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return source

        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val vmin = IntArray(max(w, h))

        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (index in dv.indices) {
            dv[index] = index / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (yIndex in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (iIndex in -radius..radius) {
                val pixel = pix[yi + min(wm, max(iIndex, 0))]
                sir = stack[iIndex + radius]
                sir[0] = (pixel and 0xff0000) shr 16
                sir[1] = (pixel and 0x00ff00) shr 8
                sir[2] = pixel and 0x0000ff
                rbs = r1 - abs(iIndex)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (iIndex > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (xIndex in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (yIndex == 0) {
                    vmin[xIndex] = min(xIndex + radius + 1, wm)
                }
                p = pix[yw + vmin[xIndex]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (xIndex in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (iIndex in -radius..radius) {
                yi = max(0, yp) + xIndex

                sir = stack[iIndex + radius]

                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]

                rbs = r1 - abs(iIndex)

                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs

                if (iIndex > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }

                if (iIndex < hm) {
                    yp += w
                }
            }
            yi = xIndex
            stackpointer = radius
            for (yIndex in 0 until h) {
                pix[yi] = (pix[yi] and -0x1000000) or
                    (dv[rsum] shl 16) or
                    (dv[gsum] shl 8) or
                    dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (yIndex == 0) {
                    vmin[yIndex] = min(yIndex + r1, hm) * w
                }
                p = xIndex + vmin[yIndex]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun gaussianBlur(source: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return source
        val w = source.width
        val h = source.height
        val kernel = buildGaussianKernel(radius)

        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val tempPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val pixel = srcPixels[rowOffset + sx]
                    val weight = kernel[k + radius]
                    r += ((pixel shr 16) and 0xff) * weight
                    g += ((pixel shr 8) and 0xff) * weight
                    b += (pixel and 0xff) * weight
                }
                tempPixels[rowOffset + x] =
                    (0xff shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val pixel = tempPixels[sy * w + x]
                    val weight = kernel[k + radius]
                    r += ((pixel shr 16) and 0xff) * weight
                    g += ((pixel shr 8) and 0xff) * weight
                    b += (pixel and 0xff) * weight
                }
                outPixels[y * w + x] =
                    (0xff shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }

        return Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun buildGaussianKernel(radius: Int): FloatArray {
        val size = radius * 2 + 1
        val sigma = max(1f, radius / 2.5f)
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            val x = i - radius
            val value = kotlin.math.exp(-(x * x) / (2f * sigma * sigma))
            kernel[i] = value
            sum += value
        }
        if (sum > 0f) {
            for (i in kernel.indices) {
                kernel[i] /= sum
            }
        }
        return kernel
    }
}
