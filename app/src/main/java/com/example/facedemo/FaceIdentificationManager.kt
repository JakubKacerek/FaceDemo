package com.example.facedemo

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class SavedFaceData(
    val faceId: String,        // Unikátní identifikátor
    val name: String,          // Jméno osoby
    val trackingIds: List<Int>, // Seznam trackingIds (Face detectors se mohou měnit)
    val firstSeen: Long,       // Čas prvního zjištění (ms)
    val lastSeen: Long,        // Čas posledního zjištění (ms)
    val notes: String = "",     // Volné poznámky
    val descriptorCsv: String = "", // Průměrný popisovač tváře (embedding založený na bodech), serializovaný jako CSV
    val descriptorSamples: Int = 0 // Kolik snímků bylo průměrováno do popisovače (pro vážené aktualizace)
)

class FaceIdentificationManager(context: Context) {

    private val PREFS_NAME = "face_identification_db"
    private val KEY_FACES = "faces_data"
    private val KEY_TRACKING_MAP = "tracking_id_map" // trackingId -> faceId mapping

    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fallback na normální SharedPreferences pokud není dostupná bezpečnější verze
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // In-memory cache to avoid expensive JSON parse on every analyzer frame
    private var facesCache: MutableList<SavedFaceData>? = null
    private var trackingMapCache: MutableMap<String, String>? = null

    private fun ensureFacesLoaded(): MutableList<SavedFaceData> {
        if (facesCache != null) return facesCache!!
        val facesJson = prefs.getString(KEY_FACES, "[]") ?: "[]"
        val jsonArray = JSONArray(facesJson)
        val faces = mutableListOf<SavedFaceData>()
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            val trackingIds = mutableListOf<Int>()
            val trackingArray = json.optJSONArray("trackingIds") ?: JSONArray()
            for (j in 0 until trackingArray.length()) {
                trackingIds.add(trackingArray.getInt(j))
            }
            faces.add(
                SavedFaceData(
                    faceId = json.getString("faceId"),
                    name = json.getString("name"),
                    trackingIds = trackingIds,
                    firstSeen = json.getLong("firstSeen"),
                    lastSeen = json.getLong("lastSeen"),
                    notes = json.optString("notes", ""),
                    descriptorCsv = json.optString("descriptorCsv", ""),
                    descriptorSamples = json.optInt("descriptorSamples", 0)
                )
            )
        }
        facesCache = faces
        return faces
    }

    private fun ensureTrackingMapLoaded(): MutableMap<String, String> {
        if (trackingMapCache != null) return trackingMapCache!!
        val mapJson = prefs.getString(KEY_TRACKING_MAP, "{}") ?: "{}"
        val json = JSONObject(mapJson)
        val map = mutableMapOf<String, String>()
        json.keys().forEach { key -> map[key] = json.getString(key) }
        trackingMapCache = map
        return map
    }

    private fun persistFacesCache() {
        val faces = ensureFacesLoaded()
        val jsonArray = JSONArray()
        faces.forEach { face ->
            jsonArray.put(JSONObject().apply {
                put("faceId", face.faceId)
                put("name", face.name)
                put("trackingIds", JSONArray(face.trackingIds))
                put("firstSeen", face.firstSeen)
                put("lastSeen", face.lastSeen)
                put("notes", face.notes)
                put("descriptorCsv", face.descriptorCsv)
                put("descriptorSamples", face.descriptorSamples)
            })
        }
        prefs.edit { putString(KEY_FACES, jsonArray.toString()) }
    }

    private fun persistTrackingMapCache() {
        val trackingJson = JSONObject(ensureTrackingMapLoaded() as Map<*, *>)
        prefs.edit { putString(KEY_TRACKING_MAP, trackingJson.toString()) }
    }

    /**
     * Uloží identifikační data tváře
     */
    fun saveFace(faceData: SavedFaceData) {
        val faces = ensureFacesLoaded()
        faces.removeAll { it.faceId == faceData.faceId }
        faces.add(faceData)
        persistFacesCache()
        updateTrackingIdMap(faceData)
    }

    /**
     * Vrátí všechna uložená data tváří
     */
    fun loadAllFaces(): List<SavedFaceData> {
        return ensureFacesLoaded().toList()
    }

    /**
     * Najde tvář podle trackingId
     */
    fun findFaceByTrackingId(trackingId: Int): SavedFaceData? {
        val trackingMap = loadTrackingIdMap()
        val faceId = trackingMap[trackingId.toString()] ?: return null
        return ensureFacesLoaded().find { it.faceId == faceId }
    }

    /**
     * Aktualizuj čas posledního zjištění tváře
     */
    fun updateLastSeen(faceId: String) {
        val faces = ensureFacesLoaded()
        val index = faces.indexOfFirst { it.faceId == faceId }
        if (index >= 0) {
            faces[index] = faces[index].copy(lastSeen = System.currentTimeMillis())
            persistFacesCache()
        }
    }

    /**
     * Try to recognize a face by comparing its descriptor against all stored faces.
     * Returns the best matching SavedFaceData and its similarity score, or null if
     * no stored face exceeds the threshold.
     *
     * @param descriptor  Float vector from FaceDescriptor.compute()
     * @param threshold   Cosine-similarity threshold (0..1). Default 0.82 is conservative.
     */
    fun recognizeByDescriptor(descriptor: FloatArray, threshold: Float = 0.82f): Pair<SavedFaceData, Float>? {
        var bestFace: SavedFaceData? = null
        var bestScore = -1f

        for (face in loadAllFaces()) {
            if (face.descriptorCsv.isBlank()) continue
            val stored = FaceDescriptor.deserialize(face.descriptorCsv) ?: continue
            val score = FaceDescriptor.similarity(descriptor, stored)
            if (score > bestScore) {
                bestScore = score
                bestFace = face
            }
        }

        return if (bestFace != null && bestScore >= threshold) Pair(bestFace!!, bestScore) else null
    }

    /**
     * Update the stored face descriptor with a new observation using exponential
     * moving average. This progressively improves recognition accuracy over time.
     *
     * @param faceId      The faceId to update
     * @param newDescriptor  New descriptor computed from the current frame
     * @param alpha       Blend factor: 0.0 = keep old entirely, 1.0 = replace entirely
     */
    fun updateDescriptor(faceId: String, newDescriptor: FloatArray, alpha: Float = 0.15f) {
        val faces = ensureFacesLoaded()
        val index = faces.indexOfFirst { it.faceId == faceId }
        if (index < 0) return

        val face = faces[index]
        val existing = FaceDescriptor.deserialize(face.descriptorCsv)
        val merged = if (existing == null || existing.size != newDescriptor.size) {
            newDescriptor
        } else {
            FloatArray(existing.size) { i -> (1f - alpha) * existing[i] + alpha * newDescriptor[i] }
        }
        val newSamples = face.descriptorSamples + 1

        faces[index] = face.copy(
            descriptorCsv = FaceDescriptor.serialize(merged),
            descriptorSamples = newSamples,
            lastSeen = System.currentTimeMillis()
        )

        persistFacesCache()
    }

    /**
     * Save a brand-new face with its first descriptor snapshot.
     */
    fun saveFaceWithDescriptor(name: String, descriptor: FloatArray, trackingId: Int?): SavedFaceData {
        val faceId = "face_${System.currentTimeMillis()}"
        val faceData = SavedFaceData(
            faceId = faceId,
            name = name,
            trackingIds = if (trackingId != null) listOf(trackingId) else emptyList(),
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis(),
            descriptorCsv = FaceDescriptor.serialize(descriptor),
            descriptorSamples = 1
        )
        saveFace(faceData)
        return faceData
    }

    /**
     * Smaž všechna data
     */
    fun deleteAllFaces() {
        facesCache = mutableListOf()
        trackingMapCache = mutableMapOf()
        // commit=true -> data jsou smazana okamzite, bez race condition po navratu ze Settings
        prefs.edit(commit = true) {
            remove(KEY_FACES)
            remove(KEY_TRACKING_MAP)
        }
    }

    /** Vymazani in-memory cache; dalsi cteni nacita z uloziste. */
    fun clearInMemoryCache() {
        facesCache = null
        trackingMapCache = null
    }

    /**
     * Mapuj trackingId na faceId pro rychlé vyhledání
     */
    private fun updateTrackingIdMap(faceData: SavedFaceData) {
        val trackingMap = ensureTrackingMapLoaded()
        faceData.trackingIds.forEach { id ->
            trackingMap[id.toString()] = faceData.faceId
        }
        persistTrackingMapCache()
    }

    /**
     * Načti mapování trackingId -> faceId
     */
    private fun loadTrackingIdMap(): Map<String, String> {
        return ensureTrackingMapLoaded()
    }
}
