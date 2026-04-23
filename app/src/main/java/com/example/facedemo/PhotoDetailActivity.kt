package com.example.facedemo

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class PhotoDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_detail)

        val photoPath = intent.getStringExtra("photo_path")
        if (photoPath == null) { finish(); return }

        val imageView = findViewById<ImageView>(R.id.imageViewDetail)
        try {
            val bitmap = decodeSampled(photoPath, 1920, 1080)
            if (bitmap == null) throw Exception("null bitmap")
            imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Toast.makeText(this, "Chyba při načítání fotografie", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Smazat fotografii")
                .setMessage("Opravdu chcete tuto fotografii smazat?")
                .setPositiveButton("Smazat") { _, _ ->
                    if (File(photoPath).delete()) {
                        finish()
                    } else {
                        Toast.makeText(this, "Smazání selhalo", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }
    }

    private fun decodeSampled(path: String, reqW: Int, reqH: Int): android.graphics.Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        if (opts.outHeight > reqH || opts.outWidth > reqW) {
            while ((opts.outHeight / sample / 2) >= reqH && (opts.outWidth / sample / 2) >= reqW) {
                sample *= 2
            }
        }
        opts.inSampleSize = sample
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, opts)
    }
}
