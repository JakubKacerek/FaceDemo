package com.example.facedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Represents a single detected object.
 * @param label       Class name (e.g. "person", "car")
 * @param confidence  Detection confidence 0-1
 * @param boundingBox Normalised bounding box [0,1] in image space (left, top, right, bottom)
 */
data class ObjectDetection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

/**
 * Wrapper around a TFLite object-detection model (YOLO / SSD style outputs).
 */
class ObjectDetector(context: Context) {

    companion object {
        private const val TAG = "ObjectDetector"
        private val MODEL_CANDIDATES = listOf(
            "yolo_coco_float32_float32.tflite",
            "yolo_coco_float32.tflite",
            "coco_yolo_float32.tflite"
        )
        private const val LABELS_FILE = "coco_labels.txt"
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val OBJECTNESS_THRESHOLD = 0.35f
        private const val CLASS_THRESHOLD = 0.25f
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 100
        private const val MAX_PRE_NMS_CANDIDATES = 300
    }

    private val interpreter: Interpreter
    private val labels: List<String>

    private val inputSize: Int
    private val outputCount: Int

    private var isYoloFormat = false
    private var isSSDFormat = false
    private var yoloShapeLogged = false

    /** true while the interpreter is busy running inference */
    var isBusy = false
        private set

    init {
        val opts = Interpreter.Options().apply { numThreads = 2 }
        interpreter = Interpreter(loadModelFile(context), opts)

        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape()
        inputSize = if (inShape.size >= 3) inShape[1] else 320
        Log.d(TAG, "Model input size: $inputSize x $inputSize")
        DebugLogger.log(TAG, "Model input size: $inputSize x $inputSize")
        DebugLogger.log(TAG, "Input tensor type: ${inTensor.dataType()}, shape=${inShape.contentToString()}")

        outputCount = interpreter.outputTensorCount
        Log.d(TAG, "Model output count: $outputCount")
        DebugLogger.log(TAG, "Model output count: $outputCount")
        for (i in 0 until outputCount) {
            val outTensor = interpreter.getOutputTensor(i)
            val shape = outTensor.shape()
            Log.d(TAG, "  output[$i] shape: ${shape.contentToString()}")
            DebugLogger.log(TAG, "output[$i] type=${outTensor.dataType()}, shape=${shape.contentToString()}")
        }

        when {
            outputCount == 1 -> {
                isYoloFormat = true
                Log.d(TAG, "Detected YOLO single-output format")
            }
            outputCount >= 4 -> {
                isSSDFormat = true
                Log.d(TAG, "Detected SSD/TF-OD-API 4-output format")
            }
            else -> {
                isSSDFormat = true
                Log.d(TAG, "Unknown format ($outputCount outputs) - trying SSD fallback")
            }
        }

        labels = try {
            context.assets.open(LABELS_FILE)
                .bufferedReader().readLines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            (0 until 80).map { "Class_$it" }
        }
        Log.d(TAG, "Loaded ${labels.size} labels")
        DebugLogger.log(TAG, "Loaded labels: ${labels.size}")
    }

    /**
     * Run detection on [bitmap]. The bitmap may be any size; it is scaled internally.
     * Call on a background thread.
     */
    fun detect(bitmap: Bitmap): List<ObjectDetection> {
        if (isBusy) return emptyList()
        isBusy = true
        return try {
            when {
                isYoloFormat -> runYolo(bitmap)
                else -> runSSD(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed: ${e.message}", e)
            DebugLogger.log(TAG, "Detection failed: ${e.message}")
            emptyList()
        } finally {
            isBusy = false
        }
    }

    fun close() = interpreter.close()

    // ─── SSD / TF OD API ─────────────────────────────────────────────────────

    private fun runSSD(bitmap: Bitmap): List<ObjectDetection> {
        val input = preprocessBitmap(bitmap)

        val boxShape = interpreter.getOutputTensor(0).shape()
        val maxDet = if (boxShape.size >= 2) boxShape[1] else MAX_DETECTIONS

        val boxes = Array(1) { Array(maxDet) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(maxDet) }
        val scores = Array(1) { FloatArray(maxDet) }
        val numDets = FloatArray(1)

        val outputs = mutableMapOf<Int, Any>(
            0 to boxes,
            1 to classes,
            2 to scores
        )
        if (outputCount >= 4) outputs[3] = numDets

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val count = if (outputCount >= 4) numDets[0].toInt().coerceIn(0, maxDet) else maxDet
        val results = mutableListOf<ObjectDetection>()

        for (i in 0 until count) {
            val score = scores[0][i]
            if (score < CONFIDENCE_THRESHOLD) continue

            val classIdx = classes[0][i].toInt()
            val label = labels.getOrElse(classIdx) { "Class_$classIdx" }

            val top    = boxes[0][i][0].coerceIn(0f, 1f)
            val left   = boxes[0][i][1].coerceIn(0f, 1f)
            val bottom = boxes[0][i][2].coerceIn(0f, 1f)
            val right  = boxes[0][i][3].coerceIn(0f, 1f)

            results.add(ObjectDetection(label, score, RectF(left, top, right, bottom)))
        }

        return nonMaximumSuppression(results)
    }

    // ─── YOLO single-output ───────────────────────────────────────────────────

    private fun runYolo(bitmap: Bitmap): List<ObjectDetection> {
        val prep = preprocessBitmapLetterbox(bitmap)
        val input = prep.input

        val outShape = interpreter.getOutputTensor(0).shape()
        Log.d(TAG, "YOLO output shape: ${outShape.contentToString()}")
        if (!yoloShapeLogged) {
            DebugLogger.log(TAG, "YOLO output shape: ${outShape.contentToString()}")
            yoloShapeLogged = true
        }

        if (outShape.size < 3) return emptyList()

        val dim1 = outShape[1]
        val dim2 = outShape[2]
        val transposed = (dim1 < dim2 && dim1 >= 5)

        val numPreds = if (transposed) dim2 else dim1
        val numCols  = if (transposed) dim1 else dim2

        val hasObjectness = when {
            numCols == labels.size + 5 -> true
            numCols == labels.size + 4 -> false
            else -> numCols > labels.size + 4
        }

        val rawOut = Array(1) { Array(dim1) { FloatArray(dim2) } }
        interpreter.run(input, rawOut)

        val results = mutableListOf<ObjectDetection>()

        for (i in 0 until numPreds) {
            val pred = if (transposed) {
                FloatArray(numCols) { c -> rawOut[0][c][i] }
            } else {
                rawOut[0][i]
            }

            if (pred.size < 5) continue

            val cx = pred[0]
            val cy = pred[1]
            val w  = pred[2]
            val h  = pred[3]

            val classAndScore = bestClassScore(pred, hasObjectness) ?: continue
            val classIdx  = classAndScore.first
            val finalScore = classAndScore.second

            val label = labels.getOrElse(classIdx) { "Class_$classIdx" }
            if (finalScore < classSpecificThreshold(label)) continue

            val maxCoord = maxOf(cx, cy, w, h)
            val usesNormalizedCoords = maxCoord <= 2f

            val modelCx = if (usesNormalizedCoords) cx * inputSize else cx
            val modelCy = if (usesNormalizedCoords) cy * inputSize else cy
            val modelW  = if (usesNormalizedCoords) w  * inputSize else w
            val modelH  = if (usesNormalizedCoords) h  * inputSize else h

            val leftModel   = modelCx - modelW / 2f
            val topModel    = modelCy - modelH / 2f
            val rightModel  = modelCx + modelW / 2f
            val bottomModel = modelCy + modelH / 2f

            val leftSrc   = (leftModel   - prep.padX) / prep.scale
            val topSrc    = (topModel    - prep.padY) / prep.scale
            val rightSrc  = (rightModel  - prep.padX) / prep.scale
            val bottomSrc = (bottomModel - prep.padY) / prep.scale

            val left   = (leftSrc   / prep.srcW).coerceIn(0f, 1f)
            val top    = (topSrc    / prep.srcH).coerceIn(0f, 1f)
            val right  = (rightSrc  / prep.srcW).coerceIn(0f, 1f)
            val bottom = (bottomSrc / prep.srcH).coerceIn(0f, 1f)

            if (right <= left || bottom <= top) continue
            results.add(ObjectDetection(label, finalScore, RectF(left, top, right, bottom)))
        }

        val preNms = results.sortedByDescending { it.confidence }.take(MAX_PRE_NMS_CANDIDATES)
        return nonMaximumSuppression(preNms).take(MAX_DETECTIONS)
    }

    private fun bestClassScore(pred: FloatArray, hasObjectness: Boolean): Pair<Int, Float>? {
        if (pred.size <= 4) return null

        var bestClass = -1
        var bestScore = 0f

        if (hasObjectness) {
            if (pred.size <= 5) return null
            val obj = normalizeScore(pred[4])
            if (obj < OBJECTNESS_THRESHOLD) return null
            for (i in 5 until pred.size) {
                val cls = normalizeScore(pred[i])
                if (cls < CLASS_THRESHOLD) continue
                val score = obj * cls
                if (score > bestScore) { bestScore = score; bestClass = i - 5 }
            }
        } else {
            for (i in 4 until pred.size) {
                val score = normalizeScore(pred[i])
                if (score > bestScore) { bestScore = score; bestClass = i - 4 }
            }
        }

        return if (bestClass >= 0) Pair(bestClass, bestScore) else null
    }

    private fun normalizeScore(raw: Float): Float =
        if (raw in 0f..1f) raw else sigmoid(raw)

    private fun sigmoid(x: Float): Float {
        val clamped = x.coerceIn(-20f, 20f)
        return (1f / (1f + kotlin.math.exp(-clamped)))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun nonMaximumSuppression(input: List<ObjectDetection>): List<ObjectDetection> {
        if (input.isEmpty()) return emptyList()

        val sorted = input.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<ObjectDetection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            sorted.removeAll { it.label == best.label && iou(best.boundingBox, it.boundingBox) > NMS_IOU_THRESHOLD }
        }

        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left, b.left)
        val interTop    = maxOf(a.top, b.top)
        val interRight  = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    private fun loadModelFile(context: Context): ByteBuffer {
        var loadedBytes: ByteArray? = null
        var loadedName: String? = null

        for (candidate in MODEL_CANDIDATES) {
            try {
                loadedBytes = context.assets.open(candidate).use { it.readBytes() }
                loadedName = candidate
                break
            } catch (_: Exception) { }
        }

        if (loadedBytes == null || loadedName == null) {
            throw IllegalStateException(
                "YOLO model not found in assets. Expected one of: ${MODEL_CANDIDATES.joinToString()}"
            )
        }

        Log.d(TAG, "Loaded model: $loadedName (${loadedBytes.size} bytes)")
        DebugLogger.log(TAG, "Loaded model: $loadedName (${loadedBytes.size} bytes)")

        return ByteBuffer.allocateDirect(loadedBytes.size).also {
            it.put(loadedBytes)
            it.rewind()
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .also { it.order(ByteOrder.nativeOrder()) }

        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (px in pixels) {
            buffer.putFloat(((px shr 16) and 0xFF) / 255f)
            buffer.putFloat(((px shr 8)  and 0xFF) / 255f)
            buffer.putFloat((px          and 0xFF) / 255f)
        }

        if (scaled != bitmap) scaled.recycle()
        buffer.rewind()
        return buffer
    }

    private data class LetterboxPreprocessResult(
        val input: ByteBuffer,
        val srcW: Float,
        val srcH: Float,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private fun preprocessBitmapLetterbox(bitmap: Bitmap): LetterboxPreprocessResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = minOf(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)

        val resizedW = (srcW * scale).toInt().coerceAtLeast(1)
        val resizedH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (inputSize - resizedW) / 2f
        val padY = (inputSize - resizedH) / 2f

        val resized    = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(letterboxed)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(resized, padX, padY, null)

        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .also { it.order(ByteOrder.nativeOrder()) }

        val pixels = IntArray(inputSize * inputSize)
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (px in pixels) {
            buffer.putFloat(((px shr 16) and 0xFF) / 255f)
            buffer.putFloat(((px shr 8)  and 0xFF) / 255f)
            buffer.putFloat((px          and 0xFF) / 255f)
        }

        if (resized != bitmap) resized.recycle()
        letterboxed.recycle()
        buffer.rewind()

        return LetterboxPreprocessResult(
            input = buffer,
            srcW  = srcW.toFloat(),
            srcH  = srcH.toFloat(),
            scale = scale,
            padX  = padX,
            padY  = padY
        )
    }

    private fun classSpecificThreshold(label: String): Float = when (label.lowercase(Locale.US)) {
        "dog"           -> 0.60f
        "traffic light" -> 0.55f
        else            -> CONFIDENCE_THRESHOLD
    }
}
