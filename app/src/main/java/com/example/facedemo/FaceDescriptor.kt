package com.example.facedemo

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Computes a simple but robust face descriptor from ML Kit landmark positions.
 *
 * Strategy: normalize all landmark coordinates relative to the inter-eye distance
 * and eye-center position, then compute pairwise distances between key landmarks.
 * The resulting vector is scale- and translation-invariant.
 */
object FaceDescriptor {

    // Ordered list of landmark types we want to use
    private val LANDMARK_TYPES = listOf(
        FaceLandmark.LEFT_EYE,
        FaceLandmark.RIGHT_EYE,
        FaceLandmark.NOSE_BASE,
        FaceLandmark.LEFT_CHEEK,
        FaceLandmark.RIGHT_CHEEK,
        FaceLandmark.MOUTH_LEFT,
        FaceLandmark.MOUTH_RIGHT,
        FaceLandmark.MOUTH_BOTTOM,
        FaceLandmark.LEFT_EAR,
        FaceLandmark.RIGHT_EAR
    )

    /**
     * Compute a normalized float descriptor vector from a Face.
     * Returns null if critical landmarks (eyes) are missing.
     */
    fun compute(face: Face): FloatArray? {
        val leftEye  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position  ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null

        // Eye center as origin, inter-eye distance as scale
        val cx = (leftEye.x + rightEye.x) / 2f
        val cy = (leftEye.y + rightEye.y) / 2f
        val eyeDist = dist(leftEye.x, leftEye.y, rightEye.x, rightEye.y).coerceAtLeast(1f)

        // Collect normalized landmark positions (x, y) for each landmark type
        val points = mutableListOf<Pair<Float, Float>>()
        for (type in LANDMARK_TYPES) {
            val pos = face.getLandmark(type)?.position
            if (pos != null) {
                points.add(Pair((pos.x - cx) / eyeDist, (pos.y - cy) / eyeDist))
            } else {
                // Use a sentinel so descriptor length stays constant
                points.add(Pair(Float.NaN, Float.NaN))
            }
        }

        // Build pairwise distance vector between all valid point pairs
        val features = mutableListOf<Float>()
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val (ax, ay) = points[i]
                val (bx, by) = points[j]
                if (ax.isNaN() || bx.isNaN()) {
                    features.add(0f) // missing -> neutral
                } else {
                    features.add(dist(ax, ay, bx, by))
                }
            }
        }

        // Also add head tilt angle (yaw, pitch) as extra features for differentiation
        features.add(normalizeAngle(face.headEulerAngleY))
        features.add(normalizeAngle(face.headEulerAngleZ))

        return features.toFloatArray()
    }

    /**
     * Cosine similarity between two descriptor vectors. Returns value in [-1, 1].
     * Higher = more similar. Threshold for "same person" ≈ 0.92.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na  += a[i] * a[i]
            nb  += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-6f) -1f else dot / denom
    }

    /**
     * Euclidean distance between two descriptors (alternative metric).
     * Lower = more similar.
     */
    fun euclidean(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var sum = 0f
        for (i in a.indices) { val d = a[i] - b[i]; sum += d * d }
        return sqrt(sum)
    }

    /** Average multiple descriptors into one (for stored embeddings). */
    fun average(descriptors: List<FloatArray>): FloatArray? {
        if (descriptors.isEmpty()) return null
        val size = descriptors[0].size
        val avg = FloatArray(size)
        for (d in descriptors) {
            for (i in d.indices) avg[i] += d[i]
        }
        for (i in avg.indices) avg[i] /= descriptors.size
        return avg
    }

    /** Serialize descriptor to comma-separated string for storage. */
    fun serialize(d: FloatArray): String = d.joinToString(",")

    /** Deserialize descriptor from stored string. */
    fun deserialize(s: String): FloatArray? {
        if (s.isBlank()) return null
        return try { s.split(",").map { it.toFloat() }.toFloatArray() } catch (_: Exception) { null }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun normalizeAngle(deg: Float): Float = deg / 90f
}

