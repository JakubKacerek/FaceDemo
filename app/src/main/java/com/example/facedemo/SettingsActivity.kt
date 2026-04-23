package com.example.facedemo

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    private val DETECTION_PREFS = "detection_settings"
    private val KEY_SMILE_DETECTION = "smile_detection_enabled"
    private val KEY_EYES_DETECTION = "eyes_detection_enabled"
    private val KEY_DEBUG_MODE = "debug_mode_enabled"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(DETECTION_PREFS, MODE_PRIVATE)

        val btnToggleSmile = findViewById<Button>(R.id.btnToggleSmileDetection)
        val btnToggleEyes = findViewById<Button>(R.id.btnToggleEyesDetection)
        val btnToggleDebug = findViewById<Button>(R.id.btnToggleDebugMode)
        val btnClearNames = findViewById<Button>(R.id.btnClearNames)

        // Načti aktuální stav
        var smileEnabled = prefs.getBoolean(KEY_SMILE_DETECTION, true)
        var eyesEnabled = prefs.getBoolean(KEY_EYES_DETECTION, true)
        var debugEnabled = prefs.getBoolean(KEY_DEBUG_MODE, false)

        // Nastavení tlačítek
        updateButtonText(btnToggleSmile, smileEnabled, "Smile Detection")
        updateButtonText(btnToggleEyes, eyesEnabled, "Eyes Detection")
        updateButtonText(btnToggleDebug, debugEnabled, "Debug Mode")

        btnToggleSmile.setOnClickListener {
            smileEnabled = !smileEnabled
            prefs.edit { putBoolean(KEY_SMILE_DETECTION, smileEnabled) }
            updateButtonText(btnToggleSmile, smileEnabled, "Smile Detection")
            Toast.makeText(this, "Smile Detection ${if (smileEnabled) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        btnToggleEyes.setOnClickListener {
            eyesEnabled = !eyesEnabled
            prefs.edit { putBoolean(KEY_EYES_DETECTION, eyesEnabled) }
            updateButtonText(btnToggleEyes, eyesEnabled, "Eyes Detection")
            Toast.makeText(this, "Eyes Detection ${if (eyesEnabled) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        btnToggleDebug.setOnClickListener {
            debugEnabled = !debugEnabled
            prefs.edit { putBoolean(KEY_DEBUG_MODE, debugEnabled) }
            DebugLogger.setEnabled(debugEnabled)
            updateButtonText(btnToggleDebug, debugEnabled, "Debug Mode")
            Toast.makeText(this, "Debug Mode ${if (debugEnabled) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        btnClearNames.setOnClickListener {
            try {
                // Smazat všechna jména ze SharedPreferences
                val namesPrefs = getSharedPreferences("face_names", MODE_PRIVATE)
                namesPrefs.edit { clear() }

                // Smazat také z FaceIdentificationManager
                val faceManager = FaceIdentificationManager(this)
                faceManager.deleteAllFaces()

                Toast.makeText(this, "All names cleared!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error clearing names: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateButtonText(button: Button, isEnabled: Boolean, label: String) {
        val status = getString(if (isEnabled) R.string.status_on else R.string.status_off)
        button.text = getString(R.string.button_status_format, label, status)
    }
}
