package com.example.facedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

object NV21ToBitmap {

    fun convertNV21(mediaImage: Image): Bitmap {
        val nv21 = yuv420888ToNv21(mediaImage)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, mediaImage.width, mediaImage.height, null)

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, mediaImage.width, mediaImage.height), 95, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /** Rotate bitmap by 0/90/180/270 degrees and recycle original when changed. */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap

        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it != bitmap) bitmap.recycle()
        }
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val out = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Y
        var outIndex = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                out[outIndex++] = yBuffer.get(rowStart + col * yPixelStride)
            }
        }

        // VU (NV21)
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        for (row in 0 until height / 2) {
            val rowStart = row * uvRowStride
            for (col in 0 until width / 2) {
                val uvIndex = rowStart + col * uvPixelStride
                out[outIndex++] = vBuffer.get(uvIndex)
                out[outIndex++] = uBuffer.get(uvIndex)
            }
        }

        return out
    }
}
