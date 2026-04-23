package com.example.facedemo

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

class FaceOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var faces: List<Face> = emptyList()
    private var identifiedFaces: Map<Int, String> = emptyMap() // trackingId -> name

    // Store the latest frame for capture
    var latestFrame: Bitmap? = null

    // Jednotné škálování a offsety (mapování z image coords -> view coords)
    var scale = 1f
    var offsetX = 0f
    var offsetY = 0f
    var isFrontCamera = false

    var onFaceClick: ((faceIndex: Int, face: Face) -> Unit)? = null

    private val unidentifiedBoxPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val identifiedBoxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    // Whether to draw landmark dots on detected faces
    var landmarksEnabled = false

    // Dot for each landmark point
    private val landmarkDotPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Label for each landmark
    private val landmarkLabelPaint = Paint().apply {
        color = Color.CYAN
        textSize = 24f
        isAntiAlias = true
        style = Paint.Style.FILL
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    // All landmark types we want to draw, paired with short display labels
    private val landmarkInfo = listOf(
        FaceLandmark.LEFT_EYE      to "LE",
        FaceLandmark.RIGHT_EYE     to "RE",
        FaceLandmark.NOSE_BASE     to "N",
        FaceLandmark.LEFT_CHEEK    to "LC",
        FaceLandmark.RIGHT_CHEEK   to "RC",
        FaceLandmark.MOUTH_LEFT    to "ML",
        FaceLandmark.MOUTH_RIGHT   to "MR",
        FaceLandmark.MOUTH_BOTTOM  to "MB",
        FaceLandmark.LEFT_EAR      to "LEar",
        FaceLandmark.RIGHT_EAR     to "REar"
    )

    // Pomocná funkce: převede bounding box (image-space) na RectF v view-space
    private fun mapBoxToView(bounds: Rect): RectF {
        var left = bounds.left * scale
        var right = bounds.right * scale
        val top = offsetY + bounds.top * scale
        val bottom = offsetY + bounds.bottom * scale

        if (isFrontCamera) {
            val tempLeft = left
            left = width - right - offsetX
            right = width - tempLeft - offsetX
        } else {
            left += offsetX
            right += offsetX
        }

        return RectF(left, top, right, bottom)
    }

    // Pomocná funkce: převede souřadnici bodu (image-space) na view-space
    private fun mapPointToView(px: Float, py: Float): PointF {
        val vx = if (isFrontCamera) {
            width - (px * scale + offsetX)
        } else {
            px * scale + offsetX
        }
        val vy = py * scale + offsetY
        return PointF(vx, vy)
    }

    fun setIdentifiedFace(trackingId: Int?, name: String, faceIndex: Int? = null) {
        if (trackingId != null) {
            // Keep this in-memory only to avoid heavy per-frame prefs writes.
            persistentNames[trackingId] = name
            identifiedFaces = persistentNames.toMap()
            invalidate()
        } else if (faceIndex != null) {
            tempNames[faceIndex] = name
            invalidate()
        }
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 50f
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private var persistentNames: MutableMap<Int, String> = mutableMapOf()
    // Dočasná jména pro aktuální snímek (pro tváře bez trackingId)
    private var tempNames: MutableMap<Int, String> = mutableMapOf()

    // Nastavení detekce
    private var smileDetectionEnabled = true
    private var eyesDetectionEnabled = true

    // Manager pro persistentní uchovávání dat tváří
    private var faceManager: FaceIdentificationManager? = null

    // Uložená jména (persistentně)
    fun loadNames(context: Context) {
        if (faceManager == null) {
            faceManager = FaceIdentificationManager(context)
        }

        val prefs = context.getSharedPreferences("face_names", Context.MODE_PRIVATE)
        persistentNames = prefs.all.mapNotNull {
            val key = it.key.toIntOrNull()
            val value = it.value as? String
            if (key != null && value != null) key to value else null
        }.toMap().toMutableMap()
        identifiedFaces = persistentNames.toMap()

        // Načti také nastavení detekce
        loadDetectionSettings(context)

        invalidate()
    }

    fun refreshAllData(context: Context) {
        // Znovu načti všechna jména a nastavení z SharedPreferences
        loadNames(context)
        // Vymaž dočasná jména, protože se vracíme ze settings
        tempNames.clear()
        invalidate()
    }

    fun loadDetectionSettings(context: Context) {
        val prefs = context.getSharedPreferences("detection_settings", Context.MODE_PRIVATE)
        smileDetectionEnabled = prefs.getBoolean("smile_detection_enabled", true)
        eyesDetectionEnabled = prefs.getBoolean("eyes_detection_enabled", true)
    }

    fun saveName(context: Context, trackingId: Int, name: String) {
        persistentNames[trackingId] = name
        identifiedFaces = persistentNames.toMap()

        // Uložit do staré databáze pro kompatibilitu
        val prefs = context.getSharedPreferences("face_names", Context.MODE_PRIVATE)
        prefs.edit().putString(trackingId.toString(), name).apply()

        // Uložit do nového manageru
        if (faceManager == null) {
            faceManager = FaceIdentificationManager(context)
        }
        val faceId = "face_${trackingId}_${System.currentTimeMillis()}"
        val faceData = SavedFaceData(
            faceId = faceId,
            name = name,
            trackingIds = listOf(trackingId),
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis()
        )
        faceManager?.saveFace(faceData)

        invalidate()
    }

    /** Returns the saved name for a face if known, null otherwise. */
    fun getNameForFace(face: Face, faceIndex: Int): String? {
        val trackingId = face.trackingId
        return when {
            trackingId != null && persistentNames[trackingId] != null -> persistentNames[trackingId]
            tempNames[faceIndex] != null -> tempNames[faceIndex]
            else -> null
        }
    }

    fun clearAllNames(context: Context) {
        persistentNames.clear()
        identifiedFaces = emptyMap()
        val prefs = context.getSharedPreferences("face_names", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Vymazat i z nového manageru
        if (faceManager == null) {
            faceManager = FaceIdentificationManager(context)
        }
        faceManager?.deleteAllFaces()

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for ((faceIndex, face) in faces.withIndex()) {
            val rect = mapBoxToView(face.boundingBox)

            val trackingId = face.trackingId
            val name = when {
                trackingId != null && persistentNames[trackingId] != null -> persistentNames[trackingId]
                tempNames[faceIndex] != null -> tempNames[faceIndex]
                else -> null
            }
            val isIdentified = !name.isNullOrEmpty()
            val boxPaint = if (isIdentified) identifiedBoxPaint else unidentifiedBoxPaint

            canvas.drawRect(rect, boxPaint)

            // Draw landmark dots if enabled
            if (landmarksEnabled) {
                for ((landmarkType, label) in landmarkInfo) {
                    val lm = face.getLandmark(landmarkType) ?: continue
                    val pt = mapPointToView(lm.position.x, lm.position.y)
                    // Dot
                    canvas.drawCircle(pt.x, pt.y, 8f, landmarkDotPaint)
                    // Short label slightly offset so it doesn't overlap the dot
                    canvas.drawText(label, pt.x + 10f, pt.y - 6f, landmarkLabelPaint)
                }
            }

            // Zobrazit pouze jméno, pokud je zadané
            if (isIdentified) {
                canvas.drawText(
                    name!!,
                    rect.left,
                    rect.top - 20,
                    textPaint
                )
            }

            // Pravděpodobnost úsměvu vlevo uprostřed boxu - pouze pokud je detekce zapnuta
            if (smileDetectionEnabled) {
                val smileProb = face.smilingProbability
                if (smileProb != null) {
                    val smileText = "${(smileProb * 100).toInt()}%"
                    val y = rect.top + rect.height() / 2 + textPaint.textSize / 2 - 10
                    canvas.drawText(
                        smileText,
                        rect.left - textPaint.measureText(smileText) - 12,
                        y,
                        textPaint
                    )
                }
            }

            // Pravděpodobnost očí vpravo uprostřed boxu - pouze pokud je detekce zapnuta
            if (eyesDetectionEnabled) {
                val leftEye = face.leftEyeOpenProbability
                val rightEye = face.rightEyeOpenProbability
                if (leftEye != null && rightEye != null) {
                    val eL = (leftEye * 100).toInt()
                    val eR = (rightEye * 100).toInt()
                    val eyesLeft = "R: $eL%"
                    val eyesRight = "L: $eR%"
                    val y = rect.top + rect.height() / 2 + textPaint.textSize / 2 - 10
                    canvas.drawText(
                        eyesLeft,
                        rect.right + 12,
                        y,
                        textPaint
                    )
                    canvas.drawText(
                        eyesRight,
                        rect.right + 12,
                        y + textPaint.textSize + 4,
                        textPaint
                    )
                }
            }
        }

    }


    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y

            for ((faceIndex, face) in faces.withIndex()) {
                val rect = mapBoxToView(face.boundingBox)
                // zvětši oblast pro kliknutí o 10px na každou stranu
                val clickRect = RectF(rect.left - 10, rect.top - 10, rect.right + 10, rect.bottom + 10)
                if (clickRect.contains(x, y)) {
                    onFaceClick?.invoke(faceIndex, face)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Capture all visible faces as separate images
     */
    fun captureAllFaces(captureManager: CaptureManager, nameInputCallback: (List<Face>) -> Map<Int, String>): List<String> {
        val capturedPaths = mutableListOf<String>()

        if (latestFrame == null || faces.isEmpty()) return capturedPaths

        // Get names for each face from the callback
        val faceNames = nameInputCallback(faces)

        for ((faceIndex, face) in faces.withIndex()) {
            val name = faceNames[faceIndex] ?: "Unknown"

            // Crop the face from the frame
            var croppedBitmap = cropFaceFromFrame(face)
            if (croppedBitmap != null) {
                // Draw name on the image
                val namedBitmap = drawNameOnBitmap(croppedBitmap, name)
                val path = captureManager.saveFaceCapture(namedBitmap, name)
                if (path != null) {
                    capturedPaths.add(path)
                }
                namedBitmap.recycle()
            }
        }

        return capturedPaths
    }


    /**
     * Draw name on top-right corner of bitmap
     */
    private fun drawNameOnBitmap(bitmap: Bitmap, name: String): Bitmap {
        val result = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            isAntiAlias = true
            style = Paint.Style.FILL
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
        }

        val x = result.width - paint.measureText(name) - 10
        val y = paint.textSize + 10
        canvas.drawText(name, x, y, paint)

        return result
    }

    /**
     * Crop a face from the frame based on bounding box - accounts for rotation
     */
    private fun cropFaceFromFrame(face: Face): Bitmap? {
        if (latestFrame == null) return null

        val boundingBox = face.boundingBox
        val padding = 20

        // The boundingBox is in image coordinates, same as latestFrame
        var left = maxOf(0, boundingBox.left - padding)
        var top = maxOf(0, boundingBox.top - padding)
        var width = minOf(boundingBox.width() + padding * 2, latestFrame!!.width - left)
        var height = minOf(boundingBox.height() + padding * 2, latestFrame!!.height - top)

        if (width <= 0 || height <= 0) return null

        try {
            val croppedBitmap = Bitmap.createBitmap(latestFrame!!, left, top, width, height)
            return croppedBitmap
        } catch (e: Exception) {
            return null
        }
    }
}