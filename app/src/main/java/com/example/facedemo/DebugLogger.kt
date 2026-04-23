package com.example.facedemo

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

object DebugLogger {
    private const val MAX_LINES = 250
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var isEnabled: Boolean = false
        private set

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            log("Debug", "Debug mode ON")
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
        notifyListeners(snapshot())
    }

    fun log(tag: String, message: String) {
        if (!isEnabled) return
        val line = "${timeFormat.format(Date())} [$tag] $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
        notifyListeners(snapshot())
    }

    fun registerListener(listener: (String) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun unregisterListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun snapshot(): String {
        synchronized(lock) {
            return lines.joinToString(separator = "\n")
        }
    }

    private fun notifyListeners(text: String) {
        for (listener in listeners) {
            listener(text)
        }
    }
}

