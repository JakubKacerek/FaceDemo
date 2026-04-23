package com.example.facedemo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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

    private val cache: LruCache<String, Bitmap>

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        cache = object : LruCache<String, Bitmap>(maxMemory / 8) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }

    override fun getCount(): Int = captures.size
    override fun getItem(position: Int): Any = captures[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val imageView = convertView as? ImageView ?: ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(400, 400)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val file = captures[position]
        // Cache key includes lastModified so edited files get re-decoded
        val cacheKey = "${file.absolutePath}_${file.lastModified()}"

        val cached = cache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
        } else {
            val thumb = decodeThumbnail(file.absolutePath, 400, 400)
            if (thumb != null) {
                cache.put(cacheKey, thumb)
                imageView.setImageBitmap(thumb)
            } else {
                imageView.setImageBitmap(null)
            }
        }

        imageView.setOnClickListener {
            val intent = Intent(context, PhotoDetailActivity::class.java)
            intent.putExtra("photo_path", file.absolutePath)
            context.startActivity(intent)
            onItemClick(file)
        }

        imageView.setOnLongClickListener {
            captureManager.deleteCapture(file)
            cache.remove(cacheKey)
            captures.removeAt(position)
            notifyDataSetChanged()
            Toast.makeText(context, "Fotografie smazána", Toast.LENGTH_SHORT).show()
            true
        }

        return imageView
    }

    fun updateData(newCaptures: List<File>) {
        captures.clear()
        captures.addAll(newCaptures)
        notifyDataSetChanged()
    }

    private fun decodeThumbnail(path: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val h = opts.outHeight; val w = opts.outWidth
        var sampleSize = 1
        if (h > reqH || w > reqW) {
            while ((h / sampleSize / 2) >= reqH && (w / sampleSize / 2) >= reqW) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }
}
