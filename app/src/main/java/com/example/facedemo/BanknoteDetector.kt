package com.example.facedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents a single detected banknote.
 * @param label       Class name — initially "Class_X" until labels are identified
 * @param confidence  Detection confidence 0-1
 * @param boundingBox Normalised bounding box [0,1] (left, top, right, bottom)
 */
data class BanknoteDetection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

/**
 * TFLite detector for the banknote_detector.tflite model.
 *
 * HOW TO IDENTIFY LABELS:
 *  1. Enable Debug Mode in the app settings.
 *  2. Point the camera at a banknote.
 *  3. Check the debug log for "[BanknoteDetector] Hit: Class_X conf=0.XX" lines.
 *  4. Note the class index X and look up what banknote it corresponds to.
 *  5. Create assets/banknote_labels.txt (one label per line, index 0 first).
 *
 * Handles both YOLO single-output and SSD/TF-OD-API 4-output formats automatically.
 */
class BanknoteDetector(context: Context) {

    companion object {
        private const val TAG = "BanknoteDetector"
        private const val MODEL_FILE = "banknote_detector.tflite"
        private const val LABELS_FILE = "banknote_labels.txt"

        // Lower threshold for initial testing — tighten once labels are identified
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val OBJECTNESS_THRESHOLD = 0.25f
        private const val CLASS_THRESHOLD = 0.20f
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 20
    }

    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputSize: Int
    private val outputCount: Int
    private var isYoloFormat = false
    private var yoloShapeLogged = false

    /** true while the interpreter is busy running inference */
    var isBusy = false
        private set

    init {
        val opts = Interpreter.Options().apply { numThreads = 2 }
        interpreter = Interpreter(loadModelFile(context), opts)

        // ── Log input tensor ──────────────────────────────────────────────────
        val inTensor = interpreter.getInputTensor(0)
        val inShape  = inTensor.shape()
        inputSize = if (inShape.size >= 3) inShape[1] else 320

        Log.d(TAG, "=== BanknoteDetector init ===")
        Log.d(TAG, "Input  shape : ${inShape.contentToString()}  (type=${inTensor.dataType()})")
        DebugLogger.log(TAG, "Input shape: ${inShape.contentToString()} type=${inTensor.dataType()}")

        // ── Log all output tensors ────────────────────────────────────────────
        outputCount = interpreter.outputTensorCount
        Log.d(TAG, "Output count : $outputCount")
        DebugLogger.log(TAG, "Output tensors: $outputCount")

        for (i in 0 until outputCount) {
            val t = interpreter.getOutputTensor(i)
            Log.d(TAG, "  output[$i] shape=${t.shape().contentToString()} type=${t.dataType()}")
            DebugLogger.log(TAG, "output[$i] shape=${t.shape().contentToString()} type=${t.dataType()}")
        }

        isYoloFormat = outputCount == 1
        Log.d(TAG, "Format: ${if (isYoloFormat) "YOLO single-output" else "SSD / TF-OD-API ($outputCount outputs)"}")
        DebugLogger.log(TAG, "Format: ${if (isYoloFormat) "YOLO" else "SSD"}")

        // ── Load labels if available ──────────────────────────────────────────
        labels = try {
            context.assets.open(LABELS_FILE).bufferedReader().readLines().filter { it.isNotBlank() }
                .also { DebugLogger.log(TAG, "Loaded ${it.size} labels from $LABELS_FILE") }
        } catch (_: Exception) {
            // Derive class count from output tensor to build placeholder labels
            val classCount = deriveClassCount()
            List(classCount) { "Class_$it" }
                .also { DebugLogger.log(TAG, "No $LABELS_FILE found — using ${it.size} placeholder labels") }
        }

        Log.d(TAG, "Labels: ${labels.size}")
        Log.d(TAG, "=== BanknoteDetector ready ===")
    }

    /**
     * Run banknote detection on [bitmap]. Call on a background thread.
     */
    fun detect(bitmap: Bitmap): List<BanknoteDetection> {
        if (isBusy) return emptyList()
        isBusy = true
        return try {
            if (isYoloFormat) runYolo(bitmap) else runSSD(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed: ${e.message}", e)
            DebugLogger.log(TAG, "Detection error: ${e.message}")
            emptyList()
        } finally {
            isBusy = false
        }
    }

    fun close() = interpreter.close()

    // ─── SSD / TF OD API ─────────────────────────────────────────────────────

    private fun runSSD(bitmap: Bitmap): List<BanknoteDetection> {
        val input    = preprocessBitmap(bitmap)
        val boxShape = interpreter.getOutputTensor(0).shape()
        val maxDet   = if (boxShape.size >= 2) boxShape[1] else MAX_DETECTIONS

        val boxes   = Array(1) { Array(maxDet) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(maxDet) }
        val scores  = Array(1) { FloatArray(maxDet) }
        val numDets = FloatArray(1)

        val outputs = mutableMapOf<Int, Any>(0 to boxes, 1 to classes, 2 to scores)
        if (outputCount >= 4) outputs[3] = numDets

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val count   = if (outputCount >= 4) numDets[0].toInt().coerceIn(0, maxDet) else maxDet
        val results = mutableListOf<BanknoteDetection>()

        for (i in 0 until count) {
            val score = scores[0][i]
            if (score < CONFIDENCE_THRESHOLD) continue

            val classIdx = classes[0][i].toInt()
            val label    = labels.getOrElse(classIdx) { "Class_$classIdx" }

            // TF OD API: [top, left, bottom, right] normalised
            val top    = boxes[0][i][0].coerceIn(0f, 1f)
            val left   = boxes[0][i][1].coerceIn(0f, 1f)
            val bottom = boxes[0][i][2].coerceIn(0f, 1f)
            val right  = boxes[0][i][3].coerceIn(0f, 1f)

            DebugLogger.log(TAG, "Hit: $label conf=${"%.2f".format(score)}")
            results.add(BanknoteDetection(label, score, RectF(left, top, right, bottom)))
        }

        return nms(results)
    }

    // ─── YOLO single-output ───────────────────────────────────────────────────

    private fun runYolo(bitmap: Bitmap): List<BanknoteDetection> {
        val prep     = preprocessBitmapLetterbox(bitmap)
        val outShape = interpreter.getOutputTensor(0).shape()

        if (!yoloShapeLogged) {
            DebugLogger.log(TAG, "YOLO output shape: ${outShape.contentToString()}")
            yoloShapeLogged = true
        }

        if (outShape.size < 3) return emptyList()

        val dim1       = outShape[1]
        val dim2       = outShape[2]
        val transposed = dim1 < dim2 && dim1 >= 5
        val numPreds   = if (transposed) dim2 else dim1
        val numCols    = if (transposed) dim1 else dim2

        val hasObjectness = numCols >= labels.size + 5

        val rawOut = Array(1) { Array(dim1) { FloatArray(dim2) } }
        interpreter.run(prep.input, rawOut)

        val results = mutableListOf<BanknoteDetection>()

        for (i in 0 until numPreds) {
            val pred = if (transposed) FloatArray(numCols) { c -> rawOut[0][c][i] } else rawOut[0][i]
            if (pred.size < 5) continue

            val cx = pred[0]; val cy = pred[1]; val w = pred[2]; val h = pred[3]

            var bestClass = -1; var bestScore = 0f
            if (hasObjectness) {
                val obj = normalizeScore(pred[4])
                if (obj < OBJECTNESS_THRESHOLD) continue
                for (j in 5 until pred.size) {
                    val cls = normalizeScore(pred[j])
                    val s   = obj * cls
                    if (s > bestScore && cls >= CLASS_THRESHOLD) { bestScore = s; bestClass = j - 5 }
                }
            } else {
                for (j in 4 until pred.size) {
                    val s = normalizeScore(pred[j])
                    if (s > bestScore) { bestScore = s; bestClass = j - 4 }
                }
            }

            if (bestClass < 0 || bestScore < CONFIDENCE_THRESHOLD) continue

            val label    = labels.getOrElse(bestClass) { "Class_$bestClass" }
            val maxCoord = maxOf(cx, cy, w, h)
            val norm     = maxCoord <= 2f

            val mCx = if (norm) cx * inputSize else cx
            val mCy = if (norm) cy * inputSize else cy
            val mW  = if (norm) w  * inputSize else w
            val mH  = if (norm) h  * inputSize else h

            val left   = ((mCx - mW / 2f - prep.padX) / prep.scale / prep.srcW).coerceIn(0f, 1f)
            val top    = ((mCy - mH / 2f - prep.padY) / prep.scale / prep.srcH).coerceIn(0f, 1f)
            val right  = ((mCx + mW / 2f - prep.padX) / prep.scale / prep.srcW).coerceIn(0f, 1f)
            val bottom = ((mCy + mH / 2f - prep.padY) / prep.scale / prep.srcH).coerceIn(0f, 1f)

            if (right <= left || bottom <= top) continue

            DebugLogger.log(TAG, "Hit: $label conf=${"%.2f".format(bestScore)}")
            results.add(BanknoteDetection(label, bestScore, RectF(left, top, right, bottom)))
        }

        return nms(results.sortedByDescending { it.confidence }).take(MAX_DETECTIONS)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun nms(input: List<BanknoteDetection>): List<BanknoteDetection> {
        val sorted   = input.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<BanknoteDetection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            sorted.removeAll { it.label == best.label && iou(best.boundingBox, it.boundingBox) > NMS_IOU_THRESHOLD }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iL = maxOf(a.left, b.left); val iT = maxOf(a.top, b.top)
        val iR = minOf(a.right, b.right); val iB = minOf(a.bottom, b.bottom)
        if (iR <= iL || iB <= iT) return 0f
        val inter = (iR - iL) * (iB - iT)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun normalizeScore(raw: Float): Float =
        if (raw in 0f..1f) raw else 1f / (1f + kotlin.math.exp(-raw.coerceIn(-20f, 20f)))

    private fun deriveClassCount(): Int {
        val shape = interpreter.getOutputTensor(0).shape()
        // YOLO: [1, preds, 5+classes] or [1, 5+classes, preds]
        // SSD:  [1, maxDet, 4] — classes come from tensor 1
        return when {
            outputCount == 1 && shape.size >= 3 -> {
                val dim1 = shape[1]; val dim2 = shape[2]
                val cols = if (dim1 < dim2 && dim1 >= 5) dim1 else dim2
                maxOf(cols - 5, 1)
            }
            outputCount >= 2 -> {
                val clsShape = interpreter.getOutputTensor(1).shape()
                if (clsShape.size >= 2) clsShape[1] else 10
            }
            else -> 10
        }
    }

    private fun loadModelFile(context: Context): ByteBuffer {
        val bytes = try {
            context.assets.open(MODEL_FILE).use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException("Model '$MODEL_FILE' not found in assets.", e)
        }
        Log.d(TAG, "Loaded $MODEL_FILE (${bytes.size} bytes)")
        DebugLogger.log(TAG, "Loaded $MODEL_FILE (${bytes.size} bytes)")
        return ByteBuffer.allocateDirect(bytes.size).also { it.put(bytes); it.rewind() }
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

    private data class LetterboxResult(
        val input: ByteBuffer,
        val srcW: Float, val srcH: Float,
        val scale: Float, val padX: Float, val padY: Float
    )

    private fun preprocessBitmapLetterbox(bitmap: Bitmap): LetterboxResult {
        val srcW  = bitmap.width; val srcH = bitmap.height
        val scale = minOf(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
        val rW    = (srcW * scale).toInt().coerceAtLeast(1)
        val rH    = (srcH * scale).toInt().coerceAtLeast(1)
        val padX  = (inputSize - rW) / 2f
        val padY  = (inputSize - rH) / 2f

        val resized     = Bitmap.createScaledBitmap(bitmap, rW, rH, true)
        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(letterboxed).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(resized, padX, padY, null)
        }

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

        return LetterboxResult(buffer, srcW.toFloat(), srcH.toFloat(), scale, padX, padY)
    }
}
