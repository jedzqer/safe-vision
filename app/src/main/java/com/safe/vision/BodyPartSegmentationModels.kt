package com.safe.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

data class BodyPartMaskRegion(
    val label: String,
    val box: Rect,
    val maskAlphaBitmap: Bitmap
) {
    fun encodeMaskToRle(): IntArray {
        val width = maskAlphaBitmap.width
        val height = maskAlphaBitmap.height
        if (width <= 0 || height <= 0) return intArrayOf()
        val pixels = IntArray(width * height)
        maskAlphaBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val counts = ArrayList<Int>()
        var currentValue = 0
        var runLength = 0
        for (pixel in pixels) {
            val value = if (Color.alpha(pixel) > 0) 1 else 0
            if (value == currentValue) {
                runLength++
            } else {
                counts.add(runLength)
                currentValue = value
                runLength = 1
            }
        }
        counts.add(runLength)
        return counts.toIntArray()
    }

    companion object {
        fun decodeMaskFromRle(width: Int, height: Int, counts: IntArray): Bitmap? {
            if (width <= 0 || height <= 0) return null
            val totalPixels = width * height
            val maskPixels = IntArray(totalPixels)
            var value = 0
            var offset = 0
            counts.forEach { count ->
                if (count < 0) return null
                repeat(count) {
                    if (offset >= totalPixels) return@repeat
                    maskPixels[offset] = if (value == 1) {
                        Color.argb(255, 255, 255, 255)
                    } else {
                        Color.argb(0, 255, 255, 255)
                    }
                    offset++
                }
                value = 1 - value
            }
            while (offset < totalPixels) {
                maskPixels[offset] = Color.argb(0, 255, 255, 255)
                offset++
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).apply {
                setPixels(maskPixels, 0, width, 0, 0, width, height)
            }
        }
    }
}

object BodyPartSegmentationMetadata {
    private const val KEY_MASK = "mask"
    private const val KEY_MASK_ENCODING = "encoding"
    private const val KEY_MASK_ORIGIN = "origin"
    private const val KEY_MASK_SIZE = "size"
    private const val KEY_MASK_COUNTS = "counts"
    private const val MASK_ENCODING_RLE_V1 = "rle_v1"

    fun toJsonObject(region: BodyPartMaskRegion): org.json.JSONObject {
        val box = region.box
        val mask = region.maskAlphaBitmap
        return org.json.JSONObject()
            .put("class", DetectionConfig.normalizeSegmentationLabel(region.label))
            .put(
                KEY_MASK,
                org.json.JSONObject()
                    .put(KEY_MASK_ENCODING, MASK_ENCODING_RLE_V1)
                    .put(
                        KEY_MASK_ORIGIN,
                        org.json.JSONArray().apply {
                            put(box.left)
                            put(box.top)
                        }
                    )
                    .put(
                        KEY_MASK_SIZE,
                        org.json.JSONArray().apply {
                            put(mask.width)
                            put(mask.height)
                        }
                    )
                    .put(
                        KEY_MASK_COUNTS,
                        org.json.JSONArray().apply {
                            region.encodeMaskToRle().forEach { put(it) }
                        }
                    )
            )
    }

    fun fromJsonObject(obj: org.json.JSONObject): BodyPartMaskRegion? {
        val label = DetectionConfig.normalizeSegmentationLabel(obj.optString("class"))
        if (!DetectionConfig.BODY_LABELS.contains(label)) return null
        val decoded = decodeMaskObject(obj.optJSONObject(KEY_MASK)) ?: return null
        return BodyPartMaskRegion(
            label = label,
            box = decoded.first,
            maskAlphaBitmap = decoded.second
        )
    }

    private fun decodeMaskObject(obj: org.json.JSONObject?): Pair<Rect, Bitmap>? {
        if (obj == null) return null
        if (obj.optString(KEY_MASK_ENCODING) != MASK_ENCODING_RLE_V1) return null
        val originArray = obj.optJSONArray(KEY_MASK_ORIGIN) ?: return null
        val sizeArray = obj.optJSONArray(KEY_MASK_SIZE) ?: return null
        if (originArray.length() < 2 || sizeArray.length() < 2) return null
        val left = originArray.optInt(0, 0)
        val top = originArray.optInt(1, 0)
        val width = sizeArray.optInt(0, 0)
        val height = sizeArray.optInt(1, 0)
        if (width <= 0 || height <= 0) return null
        val countsArray = obj.optJSONArray(KEY_MASK_COUNTS) ?: return null
        val counts = IntArray(countsArray.length()) { index ->
            countsArray.optInt(index, -1)
        }
        val bitmap = BodyPartMaskRegion.decodeMaskFromRle(width, height, counts) ?: return null
        return Rect(left, top, left + width, top + height) to ensureAlphaMask(bitmap)
    }

    private fun ensureAlphaMask(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ALPHA_8) return bitmap
        val alpha = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val alphaPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = maxOf(
                Color.alpha(pixel),
                Color.red(pixel),
                Color.green(pixel),
                Color.blue(pixel)
            )
            alphaPixels[i] = Color.argb(a, 255, 255, 255)
        }
        alpha.setPixels(alphaPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (!bitmap.isRecycled) bitmap.recycle()
        return alpha
    }
}
