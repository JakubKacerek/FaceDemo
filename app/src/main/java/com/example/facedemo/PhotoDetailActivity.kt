package com.example.facedemo

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PhotoDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_detail)

        val photoPath = intent.getStringExtra("photo_path")

        if (photoPath != null) {
            val imageView = findViewById<ImageView>(R.id.imageViewDetail)
            try {
                val bitmap = BitmapFactory.decodeFile(photoPath)
                imageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }
}

