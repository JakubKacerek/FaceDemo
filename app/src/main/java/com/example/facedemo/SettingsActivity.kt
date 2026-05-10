package com.example.facedemo

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    private val DETECTION_PREFS = "detection_settings"
    private val KEY_DEBUG_MODE = "debug_mode_enabled"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(DETECTION_PREFS, MODE_PRIVATE)

        val btnToggleDebug = findViewById<Button>(R.id.btnToggleDebugMode)
        val btnClearNames = findViewById<Button>(R.id.btnClearNames)

        var debugEnabled = prefs.getBoolean(KEY_DEBUG_MODE, false)

        updateButtonText(btnToggleDebug, debugEnabled, "Debug Mode")

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
