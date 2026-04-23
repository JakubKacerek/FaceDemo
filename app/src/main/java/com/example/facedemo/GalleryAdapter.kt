package com.example.facedemo

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.Toast
import java.io.File

class GalleryAdapter(
    private val context: Context,
    private val captures: MutableList<File>,
    private val captureManager: CaptureManager,
    private val onItemClick: (File) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = captures.size

    override fun getItem(position: Int): Any = captures[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val imageView = convertView as? ImageView ?: ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(400, 400)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val file = captures[position]
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        imageView.setImageBitmap(bitmap)

        imageView.setOnClickListener {
            val intent = Intent(context, PhotoDetailActivity::class.java)
            intent.putExtra("photo_path", file.absolutePath)
            context.startActivity(intent)
            onItemClick(file)
        }

        imageView.setOnLongClickListener {
            captureManager.deleteCapture(file)
            captures.removeAt(position)
            notifyDataSetChanged()
            Toast.makeText(context, "Image deleted", Toast.LENGTH_SHORT).show()
            true
        }

        return imageView
    }

    fun updateData(newCaptures: List<File>) {
        captures.clear()
        captures.addAll(newCaptures)
        notifyDataSetChanged()
    }
}

