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
    private lateinit var objectDetectionOverlay: ObjectDetectionOverlay
    private lateinit var banknoteOverlay: BanknoteOverlay

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var faceManager: FaceIdentificationManager
    private lateinit var captureManager: CaptureManager
    private var faceDetectionEnabled = true
    private var menuAdapter: MenuAdapter? = null

    private var objectDetector: ObjectDetector? = null
    private var objectDetectionEnabled = false
    private var lastObjectDetectionMs = 0L
    private val objectDetectionIntervalMs = 120L

    private var banknoteDetector: BanknoteDetector? = null
    private var banknoteDetectionEnabled = false
    private var lastBanknoteDetectionMs = 0L
    private val banknoteDetectionIntervalMs = 200L

    private val detectionPrefsName = "detection_settings"
    private val keyFaceDetection = "face_detection_enabled"
    private val keyObjectDetection = "yolo_detection_enabled"
    private val keyBanknoteDetection = "banknote_detection_enabled"
    private val keyDebugMode = "debug_mode_enabled"

    private lateinit var debugPanel: ScrollView
    private lateinit var txtDebugLogs: TextView
    private var debugModeEnabled = false
    private var lastDebugFaceLogMs = 0L
    private var lastDebugObjectLogMs = 0L
    private var lastDebugBanknoteLogMs = 0L
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
        objectDetectionOverlay = findViewById(R.id.objectDetectionOverlay)
        banknoteOverlay = findViewById(R.id.banknoteOverlay)
        debugPanel = findViewById(R.id.debugPanel)
        txtDebugLogs = findViewById(R.id.txtDebugLogs)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        faceOverlay.loadNames(this)
        val detectionPrefs = getSharedPreferences(detectionPrefsName, MODE_PRIVATE)
        faceDetectionEnabled = detectionPrefs.getBoolean(keyFaceDetection, true)
        objectDetectionEnabled = detectionPrefs.getBoolean(keyObjectDetection, false)
        banknoteDetectionEnabled = detectionPrefs.getBoolean(keyBanknoteDetection, false)
        debugModeEnabled = detectionPrefs.getBoolean(keyDebugMode, false)
        DebugLogger.setEnabled(debugModeEnabled)
        applyDebugUiState()

        if (objectDetectionEnabled) ensureObjectDetectorReady(showToastOnError = true)
        if (banknoteDetectionEnabled) ensureBanknoteDetectorReady(showToastOnError = true)

        DebugLogger.log("MainActivity", "onCreate()")

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val targetRotation = Surface.ROTATION_0

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetRotation(targetRotation)
                .setTargetResolution(Size(1280, 720))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            @Suppress("DEPRECATION")
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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
                    val rawBitmap = NV21ToBitmap.convertNV21(mediaImage)
                    val orientedBitmap = NV21ToBitmap.rotate(rawBitmap, rotation)
                    faceOverlay.latestFrame?.takeIf { !it.isRecycled }?.recycle()
                    faceOverlay.latestFrame = orientedBitmap
                }

                val image = InputImage.fromMediaImage(mediaImage, rotation)

                if (!faceDetectionEnabled) {
                    faceOverlay.faces = emptyList()
                    faceOverlay.invalidate()
                    runObjectDetectionIfNeeded()
                    runBanknoteDetectionIfNeeded()
                    imageProxy.close()
                    return@setAnalyzer
                }

                detector.process(image)
                    .addOnSuccessListener { faces ->
                        val now = System.currentTimeMillis()
                        if (debugModeEnabled && now - lastDebugFaceLogMs >= debugLogIntervalMs) {
                            DebugLogger.log("Face", "Detected: ${faces.size}")
                            lastDebugFaceLogMs = now
                        }

                        for (face in faces) {
                            val trackingId = face.trackingId
                            var resolvedFaceId: String? = null
                            var resolvedName: String? = null

                            // 1) Quick lookup by trackingId
                            if (trackingId != null) {
                                val cached = faceManager.findFaceByTrackingId(trackingId)
                                if (cached != null) {
                                    resolvedFaceId = cached.faceId
                                    resolvedName = cached.name
                                }
                            }

                            // 2) Descriptor recognition (rate-limited per trackingId)
                            if (resolvedName == null) {
                                val canRecognize = trackingId == null ||
                                    (now - (lastRecognizeMsByTrackingId[trackingId] ?: 0L) >= recognizeIntervalMs)

                                if (canRecognize) {
                                    if (trackingId != null) lastRecognizeMsByTrackingId[trackingId] = now
                                    val descriptor = FaceDescriptor.compute(face)
                                    if (descriptor != null) {
                                        val match = faceManager.recognizeByDescriptor(descriptor)
                                        if (match != null) {
                                            val (savedFace, _) = match
                                            resolvedFaceId = savedFace.faceId
                                            resolvedName = savedFace.name

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
                        runObjectDetectionIfNeeded()
                        runBanknoteDetectionIfNeeded()
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

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
            val desiredSelector = CameraSelector.Builder().requireLensFacing(desiredLensFacing).build()

            val hasDesiredCamera = try {
                cameraProvider.hasCamera(desiredSelector)
            } catch (_: Exception) { false }

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
        objectDetector?.close()
        objectDetector = null
        banknoteDetector?.close()
        banknoteDetector = null
    }

    private fun persistFaceName(face: Face, faceIndex: Int, name: String) {
        val trackingId = face.trackingId
        val descriptor = FaceDescriptor.compute(face)

        if (descriptor != null) {
            val existing = if (trackingId != null) faceManager.findFaceByTrackingId(trackingId) else null
            if (existing != null) {
                faceManager.saveFace(existing.copy(
                    name = name,
                    descriptorCsv = FaceDescriptor.serialize(descriptor),
                    descriptorSamples = existing.descriptorSamples + 1
                ))
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
        val editText = EditText(this).apply { hint = "Zadejte jméno osoby" }

        AlertDialog.Builder(this)
            .setTitle("Identifikace tváře")
            .setView(editText)
            .setPositiveButton("Potvrdit") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) persistFaceName(face, faceIndex, name)
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        faceManager = FaceIdentificationManager(this)
        faceManager.clearInMemoryCache()
        lastRecognizeMsByTrackingId.clear()
        lastDescriptorUpdateMsByFaceId.clear()
        faceOverlay.refreshAllData(this)

        val detectionPrefs = getSharedPreferences(detectionPrefsName, MODE_PRIVATE)
        faceDetectionEnabled = detectionPrefs.getBoolean(keyFaceDetection, true)
        objectDetectionEnabled = detectionPrefs.getBoolean(keyObjectDetection, false)
        banknoteDetectionEnabled = detectionPrefs.getBoolean(keyBanknoteDetection, false)
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
        if (debugModeEnabled) DebugLogger.registerListener(debugListener)
    }

    private fun ensureObjectDetectorReady(showToastOnError: Boolean = false): Boolean {
        if (!objectDetectionEnabled) return false
        if (objectDetector != null) return true

        return try {
            objectDetector = ObjectDetector(this)
            DebugLogger.log("ObjectDetector", "Model initialized")
            true
        } catch (e: Exception) {
            objectDetectionEnabled = false
            getSharedPreferences(detectionPrefsName, MODE_PRIVATE).edit {
                putBoolean(keyObjectDetection, false)
            }
            runOnUiThread {
                objectDetectionOverlay.detections = emptyList()
                DebugLogger.log("ObjectDetector", "Model init failed: ${e.message}")
                if (showToastOnError) {
                    Toast.makeText(this, "Nelze nacist YOLO model: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            false
        }
    }

    private fun runObjectDetectionIfNeeded() {
        if (!objectDetectionEnabled) {
            objectDetectionOverlay.detections = emptyList()
            return
        }
        if (!ensureObjectDetectorReady()) return

        val now = System.currentTimeMillis()
        val frame = faceOverlay.latestFrame
        if (frame == null || frame.isRecycled || now - lastObjectDetectionMs < objectDetectionIntervalMs) return

        val frameForDetection = try {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) { null } ?: return

        lastObjectDetectionMs = now
        val detector = objectDetector ?: run { frameForDetection.recycle(); return }
        if (detector.isBusy) { frameForDetection.recycle(); return }

        cameraExecutor.execute {
            val results = try {
                detector.detect(frameForDetection)
            } finally {
                frameForDetection.recycle()
            }
            runOnUiThread {
                objectDetectionOverlay.detections = results
                val nowUi = System.currentTimeMillis()
                if (debugModeEnabled && nowUi - lastDebugObjectLogMs >= debugLogIntervalMs) {
                    val sample = results.take(3).joinToString { "${it.label}:${"%.2f".format(it.confidence)}" }
                    DebugLogger.log("ObjectDetector", "Detections=${results.size}${if (sample.isNotBlank()) " | $sample" else ""}")
                    lastDebugObjectLogMs = nowUi
                }
            }
        }
    }

    private fun updateOverlayTransforms(mediaImage: android.media.Image, rotation: Int) {
        var imageW = mediaImage.width.toFloat()
        var imageH = mediaImage.height.toFloat()
        if (rotation == 90 || rotation == 270) {
            val tmp = imageW; imageW = imageH; imageH = tmp
        }

        val viewWidth  = previewView.width.toFloat().takeIf  { it > 0f } ?: return
        val viewHeight = previewView.height.toFloat().takeIf { it > 0f } ?: return

        val scale   = min(viewWidth / imageW, viewHeight / imageH)
        val offsetX = (viewWidth  - imageW * scale) / 2f
        val offsetY = (viewHeight - imageH * scale) / 2f

        faceOverlay.scale = scale
        faceOverlay.offsetX = offsetX
        faceOverlay.offsetY = offsetY
        faceOverlay.isFrontCamera = (lensFacing == CameraSelector.LENS_FACING_FRONT)

        objectDetectionOverlay.scale = scale
        objectDetectionOverlay.offsetX = offsetX
        objectDetectionOverlay.offsetY = offsetY
        objectDetectionOverlay.isFrontCamera = (lensFacing == CameraSelector.LENS_FACING_FRONT)

        banknoteOverlay.scale = scale
        banknoteOverlay.offsetX = offsetX
        banknoteOverlay.offsetY = offsetY
        banknoteOverlay.isFrontCamera = (lensFacing == CameraSelector.LENS_FACING_FRONT)
    }

    private fun captureScreen() {
        val viewW = previewView.width
        val viewH = previewView.height
        if (viewW == 0 || viewH == 0) {
            Toast.makeText(this, "Kamera zatím není připravena", Toast.LENGTH_SHORT).show()
            return
        }

        val previewBitmap = previewView.bitmap ?: run {
            Toast.makeText(this, "Kamera zatím není připravena", Toast.LENGTH_SHORT).show()
            return
        }

        // Result is always in view coordinate space so overlays align 1:1
        val result = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)

        // Draw preview content scaled to view size (handles any dimension mismatch)
        val src = android.graphics.Rect(0, 0, previewBitmap.width, previewBitmap.height)
        val dst = android.graphics.Rect(0, 0, viewW, viewH)
        canvas.drawBitmap(previewBitmap, src, dst, null)
        previewBitmap.recycle()

        // Overlays are in view coordinate space — draw directly, no scaling needed
        faceOverlay.draw(canvas)
        objectDetectionOverlay.draw(canvas)
        banknoteOverlay.draw(canvas)

        val path = captureManager.saveScreenCapture(result)
        result.recycle()

        if (path != null) {
            DebugLogger.log("Capture", "Screen saved: $path")
            Toast.makeText(this, "Uloženo!", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, GalleryActivity::class.java))
        } else {
            Toast.makeText(this, "Chyba při ukládání", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureBanknoteDetectorReady(showToastOnError: Boolean = false): Boolean {
        if (!banknoteDetectionEnabled) return false
        if (banknoteDetector != null) return true

        return try {
            banknoteDetector = BanknoteDetector(this)
            DebugLogger.log("BanknoteDetector", "Model initialized")
            true
        } catch (e: Exception) {
            banknoteDetectionEnabled = false
            getSharedPreferences(detectionPrefsName, MODE_PRIVATE).edit {
                putBoolean(keyBanknoteDetection, false)
            }
            runOnUiThread {
                banknoteOverlay.detections = emptyList()
                DebugLogger.log("BanknoteDetector", "Init failed: ${e.message}")
                if (showToastOnError) {
                    Toast.makeText(this, "Nelze nacist banknote model: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            false
        }
    }

    private fun runBanknoteDetectionIfNeeded() {
        if (!banknoteDetectionEnabled) {
            banknoteOverlay.detections = emptyList()
            return
        }
        if (!ensureBanknoteDetectorReady()) return

        val now = System.currentTimeMillis()
        val frame = faceOverlay.latestFrame
        if (frame == null || frame.isRecycled || now - lastBanknoteDetectionMs < banknoteDetectionIntervalMs) return

        val frameForDetection = try {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) { null } ?: return

        lastBanknoteDetectionMs = now
        val detector = banknoteDetector ?: run { frameForDetection.recycle(); return }
        if (detector.isBusy) { frameForDetection.recycle(); return }

        cameraExecutor.execute {
            val results = try {
                detector.detect(frameForDetection)
            } finally {
                frameForDetection.recycle()
            }
            runOnUiThread {
                banknoteOverlay.detections = results
                val nowUi = System.currentTimeMillis()
                if (debugModeEnabled && nowUi - lastDebugBanknoteLogMs >= debugLogIntervalMs) {
                    val sample = results.take(3).joinToString { "${it.label}:${"%.2f".format(it.confidence)}" }
                    DebugLogger.log("BanknoteDetector", "Detections=${results.size}${if (sample.isNotBlank()) " | $sample" else ""}")
                    lastDebugBanknoteLogMs = nowUi
                }
            }
        }
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

    inner class MenuAdapter(
        private val context: Context,
        private val faceOverlay: FaceOverlay
    ) : RecyclerView.Adapter<MenuAdapter.ViewHolder>() {

        private val items = listOf(
            MenuItemType.SETTINGS,
            MenuItemType.CAMERA_SWITCH,
            MenuItemType.SMILE,
            MenuItemType.EYES,
            MenuItemType.FACE,
            MenuItemType.LANDMARKS,
            MenuItemType.CAPTURE,
            MenuItemType.GALLERY,
            MenuItemType.OBJECT_DETECTION,
            MenuItemType.BANKNOTE
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
                MenuItemType.SETTINGS          -> Triple(R.drawable.ic_btn_settings, "Settings", false)
                MenuItemType.CAMERA_SWITCH     -> Triple(R.drawable.ic_btn_camera_switch, "Camera", false)
                MenuItemType.SMILE             -> Triple(R.drawable.ic_btn_smile, "Smile", prefs.getBoolean("smile_detection_enabled", true))
                MenuItemType.EYES              -> Triple(R.drawable.ic_btn_eyes, "Eyes", prefs.getBoolean("eyes_detection_enabled", true))
                MenuItemType.FACE              -> Triple(R.drawable.ic_btn_face, "Face", faceDetectionEnabled)
                MenuItemType.LANDMARKS         -> Triple(R.drawable.ic_btn_landmarks, "Landmarks", faceOverlay.landmarksEnabled)
                MenuItemType.CAPTURE           -> Triple(R.drawable.ic_btn_capture, "Capture", false)
                MenuItemType.GALLERY           -> Triple(R.drawable.ic_btn_gallery, "Gallery", false)
                MenuItemType.OBJECT_DETECTION  -> Triple(R.drawable.ic_btn_banknote, "Objects", objectDetectionEnabled)
                MenuItemType.BANKNOTE          -> Triple(R.drawable.ic_btn_banknote, "Banknote", banknoteDetectionEnabled)
            }

            holder.icon.setImageResource(iconRes)
            holder.label.text = labelText

            val bgRes = if (isActive) R.drawable.btn_circle_active else R.drawable.btn_circle_glass
            holder.icon.background = androidx.core.content.res.ResourcesCompat.getDrawable(resources, bgRes, null)

            holder.icon.setOnClickListener {
                when (item) {
                    MenuItemType.SETTINGS -> {
                        DebugLogger.log("Menu", "Open settings")
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    MenuItemType.CAMERA_SWITCH -> toggleCamera()
                    MenuItemType.SMILE -> {
                        val current = prefs.getBoolean("smile_detection_enabled", true)
                        prefs.edit { putBoolean("smile_detection_enabled", !current) }
                        faceOverlay.loadDetectionSettings(this@MainActivity)
                        notifyItemChanged(position)
                        DebugLogger.log("Menu", "Smile=${if (!current) "ON" else "OFF"}")
                        Toast.makeText(this@MainActivity, "Smile Detection ${if (!current) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    MenuItemType.EYES -> {
                        val current = prefs.getBoolean("eyes_detection_enabled", true)
                        prefs.edit { putBoolean("eyes_detection_enabled", !current) }
                        faceOverlay.loadDetectionSettings(this@MainActivity)
                        notifyItemChanged(position)
                        DebugLogger.log("Menu", "Eyes=${if (!current) "ON" else "OFF"}")
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
                        DebugLogger.log("Menu", "Face=${if (faceDetectionEnabled) "ON" else "OFF"}")
                        Toast.makeText(this@MainActivity, "Face Detection ${if (faceDetectionEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    MenuItemType.LANDMARKS -> {
                        faceOverlay.landmarksEnabled = !faceOverlay.landmarksEnabled
                        faceOverlay.invalidate()
                        notifyItemChanged(position)
                        Toast.makeText(this@MainActivity, "Landmarks ${if (faceOverlay.landmarksEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    MenuItemType.CAPTURE -> captureScreen()
                    MenuItemType.GALLERY -> {
                        startActivity(Intent(this@MainActivity, GalleryActivity::class.java))
                    }
                    MenuItemType.OBJECT_DETECTION -> {
                        objectDetectionEnabled = !objectDetectionEnabled
                        prefs.edit { putBoolean(keyObjectDetection, objectDetectionEnabled) }

                        if (objectDetectionEnabled) {
                            if (!ensureObjectDetectorReady(showToastOnError = true)) {
                                notifyItemChanged(position)
                                return@setOnClickListener
                            }
                        } else {
                            objectDetectionOverlay.detections = emptyList()
                        }
                        notifyItemChanged(position)
                        DebugLogger.log("Menu", "ObjectDetection=${if (objectDetectionEnabled) "ON" else "OFF"}")
                        Toast.makeText(this@MainActivity, "Object Detection ${if (objectDetectionEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    MenuItemType.BANKNOTE -> {
                        banknoteDetectionEnabled = !banknoteDetectionEnabled
                        prefs.edit { putBoolean(keyBanknoteDetection, banknoteDetectionEnabled) }

                        if (banknoteDetectionEnabled) {
                            if (!ensureBanknoteDetectorReady(showToastOnError = true)) {
                                notifyItemChanged(position)
                                return@setOnClickListener
                            }
                        } else {
                            banknoteOverlay.detections = emptyList()
                        }
                        notifyItemChanged(position)
                        DebugLogger.log("Menu", "Banknote=${if (banknoteDetectionEnabled) "ON" else "OFF"}")
                        Toast.makeText(this@MainActivity, "Banknote Detection ${if (banknoteDetectionEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    enum class MenuItemType {
        SETTINGS, CAMERA_SWITCH, SMILE, EYES, FACE, LANDMARKS, CAPTURE, GALLERY, OBJECT_DETECTION, BANKNOTE
    }
}
