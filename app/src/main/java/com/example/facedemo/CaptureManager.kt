package com.example.facedemo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class CaptureManager(private val context: Context) {

    private val captureDir = File(context.getExternalFilesDir(null), "face_captures")

    init {
        if (!captureDir.exists()) {
            captureDir.mkdirs()
        }
    }

    /**
     * Save a full-screen capture (camera + overlays).
     */
    fun saveScreenCapture(bitmap: Bitmap): String? {
        return try {
            val fileName = "capture_${System.currentTimeMillis()}.jpg"
            val file = File(captureDir, fileName)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            Log.d("CaptureManager", "Screen captured: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e("CaptureManager", "Error saving screen capture", e)
            null
        }
    }

    /**
     * Save a cropped face bitmap.
     */
    fun saveFaceCapture(bitmap: Bitmap, name: String = ""): String? {
        return try {
            val fileName = "face_${System.currentTimeMillis()}_${name.replace(" ", "_")}.jpg"
            val file = File(captureDir, fileName)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Log.d("CaptureManager", "Face captured: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e("CaptureManager", "Error saving face capture", e)
            null
        }
    }

    /**
     * Get all captured face images
     */
    fun getAllCaptures(): List<File> {
        return captureDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Delete a capture
     */
    fun deleteCapture(file: File): Boolean {
        return file.delete()
    }

    /**
     * Delete all captures
     */
    fun deleteAllCaptures() {
        captureDir.listFiles()?.forEach { it.delete() }
    }
}


