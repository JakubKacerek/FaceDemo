package com.example.facedemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import java.util.concurrent.Executors
import android.content.Intent
import kotlin.math.min
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.view.Surface
import android.widget.TextView
import android.view.View
import android.widget.ScrollView

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    lateinit var faceOverlay: FaceOverlay
    private lateinit var banknoteOverlay: BanknoteOverlay

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var faceManager: FaceIdentificationManager
    private lateinit var captureManager: CaptureManager
    private var faceDetectionEnabled = true
    private var menuAdapter: MenuAdapter? = null

    // YOLO object detection
    private var banknoteDetector: BanknoteDetector? = null
    private var banknoteDetectionEnabled = false
    private var lastBanknoteDetectionMs = 0L
    private val banknoteDetectionIntervalMs = 120L

    private val detectionPrefsName = "detection_settings"
    private val keyFaceDetection = "face_detection_enabled"
    private val keyYoloDetection = "yolo_detection_enabled"
    private val keyDebugMode = "debug_mode_enabled"

    private lateinit var debugPanel: ScrollView
    private lateinit var txtDebugLogs: TextView
    private var debugModeEnabled = false
    private var lastDebugFaceLogMs = 0L
    private var lastDebugYoloLogMs = 0L
    private val debugLogIntervalMs = 3000L

    // Perf tuning for analyzer
    private var analysisFrameCount = 0
    private val captureBitmapEveryNFrames = 1
    private val recognizeIntervalMs = 200L
    private val descriptorUpdateIntervalMs = 800L
    private var lastLastSeenPersistMs = 0L
    private val lastRecognizeMsByTrackingId = mutableMapOf<Int, Long>()
    private val lastDescriptorUpdateMsByFaceId = mutableMapOf<String, Long>()

    private val debugListener: (String) -> Unit = { logs ->
        runOnUiThread {
            txtDebugLogs.text = logs
            debugPanel.post { debugPanel.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        faceManager = FaceIdentificationManager(this)
        captureManager = CaptureManager(this)

        previewView = findViewById(R.id.previewView)
        faceOverlay = findViewById(R.id.faceOverlay)
        banknoteOverlay = findViewById(R.id.banknoteOverlay)
        debugPanel = findViewById(R.id.debugPanel)
        txtDebugLogs = findViewById(R.id.txtDebugLogs)

        // Keep full frame visible in portrait; no geometric distortion.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        // Načíst uložená jména při startu
        faceOverlay.loadNames(this)
        val detectionPrefs = getSharedPreferences(detectionPrefsName, MODE_PRIVATE)
        faceDetectionEnabled = detectionPrefs.getBoolean(keyFaceDetection, true)
        banknoteDetectionEnabled = detectionPrefs.getBoolean(keyYoloDetection, false)
        debugModeEnabled = detectionPrefs.getBoolean(keyDebugMode, false)
        DebugLogger.setEnabled(debugModeEnabled)
        applyDebugUiState()

        if (banknoteDetectionEnabled) {
            ensureYoloDetectorReady(showToastOnError = true)
        }

        DebugLogger.log("MainActivity", "onCreate()")

        // Nastavení click listeneru pro tváře
        faceOverlay.onFaceClick = { faceIndex, face ->
            showNameInputDialog(face, faceIndex)
        }

        val recyclerMenu = findViewById<RecyclerView>(R.id.recyclerMenu)
        recyclerMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val adapter = MenuAdapter(this, faceOverlay)
        recyclerMenu.adapter = adapter
        menuAdapter = adapter

        requestPermission()
    }

    private fun requestPermission() {

        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startCamera()
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val targetRotation = Surface.ROTATION_0

        previewView.implementationMode =
            PreviewView.ImplementationMode.COMPATIBLE

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetRotation(targetRotation)
                .setTargetResolution(Size(1280, 720))
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            @Suppress("DEPRECATION")
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .build()

            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .enableTracking()
                    .build()
            )

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->

                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val rotation = imageProxy.imageInfo.rotationDegrees
                updateOverlayTransforms(mediaImage, rotation)

                analysisFrameCount++
                if (analysisFrameCount % captureBitmapEveryNFrames == 0) {
                    // Keep captured frame in the same rotation-space as ML Kit bounding boxes.
                    val rawBitmap = NV21ToBitmap.convertNV21(mediaImage)
                    val orientedBitmap = NV21ToBitmap.rotate(rawBitmap, rotation)
                    faceOverlay.latestFrame?.takeIf { !it.isRecycled }?.recycle()
                    faceOverlay.latestFrame = orientedBitmap
                }

                // Run ML Kit on MediaImage with proper rotation metadata.
                val image = InputImage.fromMediaImage(mediaImage, rotation)

                if (!faceDetectionEnabled) {
                    faceOverlay.faces = emptyList()
                    faceOverlay.invalidate()
                    runYoloDetectionIfNeeded()
                    imageProxy.close()
                    return@setAnalyzer
                }

                detector.process(image)
                    .addOnSuccessListener { faces ->

                        val now = System.currentTimeMillis()
                        if (debugModeEnabled && faceDetectionEnabled && now - lastDebugFaceLogMs >= debugLogIntervalMs) {
                            DebugLogger.log("Face", "Detected faces: ${faces.size}")
                            lastDebugFaceLogMs = now
                        }

                        // Automaticky rozpoznej tváře podle uložených dat
                        for (face in faces) {
                            val trackingId = face.trackingId
                            var resolvedFaceId: String? = null
                            var resolvedName: String? = null

                            // 1) Lightweight same-session lookup by trackingId (cheap with cache)
                            if (trackingId != null) {
                                val cached = faceManager.findFaceByTrackingId(trackingId)
                                if (cached != null) {
                                    resolvedFaceId = cached.faceId
                                    resolvedName = cached.name
                                }
                            }

                            // 2) Descriptor recognition only periodically per trackingId
                            if (resolvedName == null) {
                                val canRecognize = trackingId == null ||
                                    (now - (lastRecognizeMsByTrackingId[trackingId] ?: 0L) >= recognizeIntervalMs)

                                if (canRecognize) {
                                    if (trackingId != null) {
                                        lastRecognizeMsByTrackingId[trackingId] = now
                                    }
                                    val descriptor = FaceDescriptor.compute(face)
                                    if (descriptor != null) {
                                        val match = faceManager.recognizeByDescriptor(descriptor)
                                        if (match != null) {
                                            val (savedFace, _) = match
                                            resolvedFaceId = savedFace.faceId
                                            resolvedName = savedFace.name

                                            // Descriptor refinement only every N ms per faceId
                                            val lastUpd = lastDescriptorUpdateMsByFaceId[savedFace.faceId] ?: 0L
                                            if (now - lastUpd >= descriptorUpdateIntervalMs) {
                                                faceManager.updateDescriptor(savedFace.faceId, descriptor)
                                                lastDescriptorUpdateMsByFaceId[savedFace.faceId] = now
                                            }
                                        }
                                    }
                                }
                            }

                            if (trackingId != null && resolvedName != null) {
                                faceOverlay.setIdentifiedFace(trackingId, resolvedName)
                            }

                            if (resolvedFaceId != null && now - lastLastSeenPersistMs >= 1000L) {
                                faceManager.updateLastSeen(resolvedFaceId)
                                lastLastSeenPersistMs = now
                            }
                        }

                        faceOverlay.faces = faces
                        faceOverlay.invalidate()

                        // YOLO object detection runs independently from face detection state.
                        runYoloDetectionIfNeeded()
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            cameraProvider.unbindAll()

            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleCamera() {
        val desiredLensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val desiredSelector = CameraSelector.Builder()
                .requireLensFacing(desiredLensFacing)
                .build()

            val hasDesiredCamera = try {
                cameraProvider.hasCamera(desiredSelector)
            } catch (_: Exception) {
                false
            }

            if (!hasDesiredCamera) {
                Toast.makeText(this, "Tato kamera neni dostupna.", Toast.LENGTH_SHORT).show()
                return@addListener
            }

            lensFacing = desiredLensFacing
            faceOverlay.faces = emptyList()
            faceOverlay.invalidate()
            startCamera()

            val cameraName = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "Predni" else "Zadni"
            DebugLogger.log("Camera", "Switched to $cameraName")
            Toast.makeText(this, "Kamera: $cameraName", Toast.LENGTH_SHORT).show()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        banknoteDetector?.close()
        banknoteDetector = null
    }

    private fun persistFaceName(face: Face, faceIndex: Int, name: String) {
        val trackingId = face.trackingId
        val descriptor = FaceDescriptor.compute(face)

        if (descriptor != null) {
            val existing = if (trackingId != null) faceManager.findFaceByTrackingId(trackingId) else null
            if (existing != null) {
                val updated = existing.copy(
                    name = name,
                    descriptorCsv = FaceDescriptor.serialize(descriptor),
                    descriptorSamples = existing.descriptorSamples + 1
                )
                faceManager.saveFace(updated)
                if (trackingId != null) faceOverlay.setIdentifiedFace(trackingId, name)
            } else {
                faceManager.saveFaceWithDescriptor(name, descriptor, trackingId)
                if (trackingId != null) faceOverlay.setIdentifiedFace(trackingId, name)
                else faceOverlay.setIdentifiedFace(null, name, faceIndex)
            }
        } else {
            if (trackingId != null) faceOverlay.saveName(this, trackingId, name)
            else faceOverlay.setIdentifiedFace(null, name, faceIndex)
        }
    }

    private fun showNameInputDialog(face: Face, faceIndex: Int) {
        val editText = EditText(this)
        editText.hint = "Zadejte jméno osoby"

        AlertDialog.Builder(this)
            .setTitle("Identifikace tváře")
            .setView(editText)
            .setPositiveButton("Potvrdit") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    persistFaceName(face, faceIndex, name)
                }
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Po navratu ze Settings chceme cist jen aktualni ulozena data.
        faceManager = FaceIdentificationManager(this)
        faceManager.clearInMemoryCache()
        lastRecognizeMsByTrackingId.clear()
        lastDescriptorUpdateMsByFaceId.clear()
        faceOverlay.refreshAllData(this)

        // Refresh toggles from Settings
        val detectionPrefs = getSharedPreferences(detectionPrefsName, MODE_PRIVATE)
        faceDetectionEnabled = detectionPrefs.getBoolean(keyFaceDetection, true)
        banknoteDetectionEnabled = detectionPrefs.getBoolean(keyYoloDetection, false)
        debugModeEnabled = detectionPrefs.getBoolean(keyDebugMode, false)
        DebugLogger.setEnabled(debugModeEnabled)
        applyDebugUiState()
        DebugLogger.log("MainActivity", "onResume() debug=${if (debugModeEnabled) "ON" else "OFF"}")
        menuAdapter?.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        DebugLogger.unregisterListener(debugListener)
    }

    override fun onStart() {
        super.onStart()
        if (debugModeEnabled) {
            DebugLogger.registerListener(debugListener)
        }
    }

    /**
     * Convert MediaImage to Bitmap using RenderScript or direct approach
     */
    private fun mediaImageToBitmap(mediaImage: android.media.Image): Bitmap {
        val bitmap = NV21ToBitmap.convertNV21(mediaImage)
        return NV21ToBitmap.rotate(bitmap, 90)
    }

    private fun ensureYoloDetectorReady(showToastOnError: Boolean = false): Boolean {
        if (!banknoteDetectionEnabled) return false
        if (banknoteDetector != null) return true

        return try {
            banknoteDetector = BanknoteDetector(this)
            DebugLogger.log("YOLO", "Model initialized")
            true
        } catch (e: Exception) {
            banknoteDetectionEnabled = false
            getSharedPreferences(detectionPrefsName, MODE_PRIVATE).edit {
                putBoolean(keyYoloDetection, false)
            }
            runOnUiThread {
                banknoteOverlay.detections = emptyList()
                DebugLogger.log("YOLO", "Model init failed: ${e.message}")
                if (showToastOnError) {
                    Toast.makeText(
                        this,
                        "Nelze nacist YOLO model: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            false
        }
    }

    private fun runYoloDetectionIfNeeded() {
        if (!banknoteDetectionEnabled) {
            banknoteOverlay.detections = emptyList()
            return
        }
        if (!ensureYoloDetectorReady()) return

        val now = System.currentTimeMillis()
        val frame = faceOverlay.latestFrame
        if (frame == null || frame.isRecycled || now - lastBanknoteDetectionMs < banknoteDetectionIntervalMs) return

        // Snapshot frame to avoid races with analyzer recycling/replacing latestFrame.
        val frameForDetection = try {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        } ?: return

        lastBanknoteDetectionMs = now
        val detector = banknoteDetector ?: run {
            frameForDetection.recycle()
            return
        }
        if (detector.isBusy) {
            frameForDetection.recycle()
            return
        }

        cameraExecutor.execute {
            val results = try {
                detector.detect(frameForDetection)
            } finally {
                frameForDetection.recycle()
            }
            runOnUiThread {
                banknoteOverlay.detections = results
                val nowUi = System.currentTimeMillis()
                if (debugModeEnabled && banknoteDetectionEnabled && nowUi - lastDebugYoloLogMs >= debugLogIntervalMs) {
                    val sample = results.take(3).joinToString { "${it.label}:${"%.2f".format(it.confidence)}" }
                    DebugLogger.log("YOLO", "Detections=${results.size}${if (sample.isNotBlank()) " | $sample" else ""}")
                    lastDebugYoloLogMs = nowUi
                }
            }
        }
    }

    private fun updateOverlayTransforms(mediaImage: android.media.Image, rotation: Int) {
        var imageW = mediaImage.width.toFloat()
        var imageH = mediaImage.height.toFloat()
        if (rotation == 90 || rotation == 270) {
            val tmp = imageW
            imageW = imageH
            imageH = tmp
        }

        val viewWidth = previewView.width.toFloat().takeIf { it > 0f } ?: return
        val viewHeight = previewView.height.toFloat().takeIf { it > 0f } ?: return

        val scale = min(viewWidth / imageW, viewHeight / imageH)
        val offsetX = (viewWidth - imageW * scale) / 2f
        val offsetY = (viewHeight - imageH * scale) / 2f

        faceOverlay.scale = scale
        faceOverlay.offsetX = offsetX
        faceOverlay.offsetY = offsetY
        faceOverlay.imageWidth = imageW
        faceOverlay.imageHeight = imageH
        faceOverlay.debugDrawImageBounds = false
        faceOverlay.isFrontCamera = (lensFacing == CameraSelector.LENS_FACING_FRONT)

        banknoteOverlay.scale = scale
        banknoteOverlay.offsetX = offsetX
        banknoteOverlay.offsetY = offsetY
        banknoteOverlay.isFrontCamera = (lensFacing == CameraSelector.LENS_FACING_FRONT)
    }

    private fun applyDebugUiState() {
        debugPanel.visibility = if (debugModeEnabled) View.VISIBLE else View.GONE
        if (debugModeEnabled) {
            DebugLogger.registerListener(debugListener)
        } else {
            DebugLogger.unregisterListener(debugListener)
            txtDebugLogs.text = ""
        }
    }

    // MenuAdapter for the scrollable menu
    inner class MenuAdapter(private val context: Context, private val faceOverlay: FaceOverlay) : RecyclerView.Adapter<MenuAdapter.ViewHolder>() {

        private val items = listOf(
            MenuItemType.SETTINGS,
            MenuItemType.CAMERA_SWITCH,
            MenuItemType.SMILE,
            MenuItemType.EYES,
            MenuItemType.FACE,
            MenuItemType.LANDMARKS,
            MenuItemType.CAPTURE,
            MenuItemType.GALLERY,
            MenuItemType.YOLO
        )

        inner class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            val icon: android.widget.ImageButton = itemView.findViewById(R.id.menuIcon)
            val label: android.widget.TextView = itemView.findViewById(R.id.menuLabel)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(context)
                .inflate(R.layout.item_menu_button, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val prefs = getSharedPreferences("detection_settings", MODE_PRIVATE)

            val (iconRes, labelText, isActive) = when (item) {
                MenuItemType.SETTINGS      -> Triple(R.drawable.ic_btn_settings, "Settings", false)
                MenuItemType.CAMERA_SWITCH -> Triple(R.drawable.ic_btn_camera_switch, "Camera", false)
                MenuItemType.SMILE         -> Triple(R.drawable.ic_btn_smile, "Smile", prefs.getBoolean("smile_detection_enabled", true))
                MenuItemType.EYES          -> Triple(R.drawable.ic_btn_eyes, "Eyes", prefs.getBoolean("eyes_detection_enabled", true))
                MenuItemType.FACE          -> Triple(R.drawable.ic_btn_face, "Face", faceDetectionEnabled)
                MenuItemType.LANDMARKS     -> Triple(R.drawable.ic_btn_landmarks, "Landmarks", faceOverlay.landmarksEnabled)
                MenuItemType.CAPTURE       -> Triple(R.drawable.ic_btn_capture, "Capture", false)
                MenuItemType.GALLERY       -> Triple(R.drawable.ic_btn_gallery, "Gallery", false)
                MenuItemType.YOLO          -> Triple(R.drawable.ic_btn_banknote, "YOLO", banknoteDetectionEnabled)
            }

            holder.icon.setImageResource(iconRes)
            holder.label.text = labelText

            // Active toggle buttons glow cyan, others are glass
            val bgRes = if (isActive) R.drawable.btn_circle_active else R.drawable.btn_circle_glass
            holder.icon.background = androidx.core.content.res.ResourcesCompat.getDrawable(resources, bgRes, null)

            holder.icon.setOnClickListener {
                when (item) {
                    MenuItemType.SETTINGS -> {
                        DebugLogger.log("Menu", "Open settings")
                        startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    MenuItemType.CAMERA_SWITCH -> {
                        toggleCamera()
                    }
                    MenuItemType.SMILE -> {
                        val current = prefs.getBoolean("smile_detection_enabled", true)
                        prefs.edit { putBoolean("smile_detection_enabled", !current) }
                        faceOverlay.loadDetectionSettings(this@MainActivity)
                        notifyItemChanged(position)
                        DebugLogger.log("Menu", "Smile detection=${if (!current) "ON" else "OFF"}")
                        Toast.makeText(this@MainActivity, "Smile Detection ${if (!current) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    MenuItemType.EYES -> {
                        val current = prefs.getBoolean("eyes_detection_enabled", true)
                        prefs.edit { putBoolean("eyes_detection_enabled", !current) }
                        faceOverlay.loadDetectionSettings(this@MainActivity)
                        notifyItemChanged(position)
                        DebugLogger.log("Menu", "Eyes detection=${if (!current) "ON" else "OFF"}")
                        Toast.makeText(this@MainActivity, "Eyes Detection ${if (!current) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    MenuItemType.FACE -> {
                        faceDetectionEnabled = !faceDetectionEnabled
                        prefs.edit { putBoolean(keyFaceDetection, faceDetectionEnabled) }
                        if (!faceDetectionEnabled) {
                            faceOverlay.faces = emptyList()
                            faceOverlay.invalidate()
                        }
                        notifyItemChanged(position)
                        DebugLogger.log("Menu", "Face detection=${if (faceDetectionEnabled) "ON" else "OFF"}")
                        Toast.makeText(this@MainActivity, "Face Detection ${if (faceDetectionEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    MenuItemType.LANDMARKS -> {
                        faceOverlay.landmarksEnabled = !faceOverlay.landmarksEnabled
                        faceOverlay.invalidate()
                        notifyItemChanged(position)
                        Toast.makeText(this@MainActivity, "Landmarks ${if (faceOverlay.landmarksEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    MenuItemType.CAPTURE -> {
                        if (faceOverlay.latestFrame == null) {
                            Toast.makeText(this@MainActivity, "No frame available", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val faces = faceOverlay.faces
                        if (faces.isEmpty()) {
                            Toast.makeText(this@MainActivity, "No faces detected", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        // Pre-fill known names; only ask for unnamed faces
                        val faceNames = mutableMapOf<Int, String>()
                        for ((index, face) in faces.withIndex()) {
                            val known = faceOverlay.getNameForFace(face, index)
                            if (known != null) faceNames[index] = known
                        }

                        // Indices that still need a name
                        val unnamed = faces.indices.filter { faceNames[it] == null }.toMutableList()
                        var unnamedPos = 0

                        fun doCapture() {
                            val paths = faceOverlay.captureAllFaces(captureManager) { _ -> faceNames }
                            if (paths.isNotEmpty()) {
                                Toast.makeText(this@MainActivity, "Captured ${paths.size} face(s)!", Toast.LENGTH_SHORT).show()
                                startActivity(android.content.Intent(this@MainActivity, GalleryActivity::class.java))
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to capture faces", Toast.LENGTH_SHORT).show()
                            }
                        }

                        fun showNextUnnamedDialog() {
                            if (unnamedPos >= unnamed.size) {
                                doCapture()
                                return
                            }
                            val idx = unnamed[unnamedPos]
                            val editText = EditText(this@MainActivity).apply {
                                hint = "Enter name for face ${idx + 1}"
                                setText("Person_${idx + 1}")
                            }
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Name face ${idx + 1}/${faces.size}")
                                .setView(editText)
                                .setPositiveButton("Next") { _, _ ->
                                    val finalName = editText.text.toString().trim().ifEmpty { "Person_${idx + 1}" }
                                    faceNames[idx] = finalName
                                    persistFaceName(faces[idx], idx, finalName)
                                    unnamedPos++
                                    showNextUnnamedDialog()
                                }
                                .setNegativeButton("Cancel", null)
                                .setCancelable(false)
                                .show()
                        }

                        showNextUnnamedDialog()
                    }
                    MenuItemType.GALLERY -> {
                        startActivity(android.content.Intent(this@MainActivity, GalleryActivity::class.java))
                    }
                    MenuItemType.YOLO -> {
                        banknoteDetectionEnabled = !banknoteDetectionEnabled
                        prefs.edit { putBoolean(keyYoloDetection, banknoteDetectionEnabled) }

                        if (banknoteDetectionEnabled) {
                            if (!ensureYoloDetectorReady(showToastOnError = true)) {
                                notifyItemChanged(position)
                                return@setOnClickListener
                            }
                        } else {
                            banknoteOverlay.detections = emptyList()
                        }
                        notifyItemChanged(position)
                        DebugLogger.log("Menu", "YOLO=${if (banknoteDetectionEnabled) "ON" else "OFF"}")
                        Toast.makeText(
                            this@MainActivity,
                            "YOLO Detection ${if (banknoteDetectionEnabled) "ON" else "OFF"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // emoce, oči, identifikace - toDo

    enum class MenuItemType {
        SETTINGS, CAMERA_SWITCH, SMILE, EYES, FACE, LANDMARKS, CAPTURE, GALLERY, YOLO
    }
}
