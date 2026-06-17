package com.safe.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream

data class BodyPartMaskRegion(
    val label: String,
    val box: Rect,
    val maskAlphaBitmap: Bitmap
) {
    fun encodeMaskToBase64Png(): String {
        val output = ByteArrayOutputStream()
        maskAlphaBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        fun decodeMaskFromBase64Png(base64: String): Bitmap? {
            return runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
}

object BodyPartSegmentationMetadata {
    fun toJsonObject(region: BodyPartMaskRegion): org.json.JSONObject {
        val box = region.box
        return org.json.JSONObject()
            .put("class", DetectionConfig.normalizeSegmentationLabel(region.label))
            .put(
                "box",
                org.json.JSONArray().apply {
                    put(box.left)
                    put(box.top)
                    put(box.width())
                    put(box.height())
                }
            )
            .put("mask_png_base64", region.encodeMaskToBase64Png())
    }

    fun fromJsonObject(obj: org.json.JSONObject): BodyPartMaskRegion? {
        val label = DetectionConfig.normalizeSegmentationLabel(obj.optString("class"))
        if (!DetectionConfig.BODY_LABELS.contains(label)) return null
        val boxArray = obj.optJSONArray("box") ?: return null
        if (boxArray.length() < 4) return null
        val x = boxArray.optInt(0, 0)
        val y = boxArray.optInt(1, 0)
        val w = boxArray.optInt(2, 0)
        val h = boxArray.optInt(3, 0)
        if (w <= 0 || h <= 0) return null
        val maskBitmap = BodyPartMaskRegion.decodeMaskFromBase64Png(
            obj.optString("mask_png_base64")
        ) ?: return null
        return BodyPartMaskRegion(
            label = label,
            box = Rect(x, y, x + w, y + h),
            maskAlphaBitmap = ensureAlphaMask(maskBitmap)
        )
    }

    private fun ensureAlphaMask(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ALPHA_8) return bitmap
        val alpha = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val alphaPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val a = Color.alpha(pixels[i])
            alphaPixels[i] = Color.argb(a, 255, 255, 255)
        }
        alpha.setPixels(alphaPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (!bitmap.isRecycled) bitmap.recycle()
        return alpha
    }
}
