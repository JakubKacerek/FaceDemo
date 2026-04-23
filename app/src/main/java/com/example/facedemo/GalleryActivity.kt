package com.example.facedemo

import android.os.Bundle
import android.widget.GridView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class GalleryActivity : AppCompatActivity() {

    private lateinit var gridView: GridView
    private lateinit var captureManager: CaptureManager
    private lateinit var adapter: GalleryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        captureManager = CaptureManager(this)
        gridView = findViewById(R.id.gridViewGallery)

        val captures = captureManager.getAllCaptures()
        adapter = GalleryAdapter(this, captures.toMutableList(), captureManager) {
            // On item click
            Toast.makeText(this, "Image selected", Toast.LENGTH_SHORT).show()
        }
        gridView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // Refresh gallery when returning
        val captures = captureManager.getAllCaptures()
        adapter.updateData(captures)
    }
}


