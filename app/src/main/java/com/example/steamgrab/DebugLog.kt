package com.example.steamgrab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan langkah login dan download, lengkap dengan jam. Bisa dilihat dan disalin dari menu Debug.
 * Isinya tidak memuat password.
 */
object DebugLog {
    private const val MAX_LINES = 500
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    @Synchronized
    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    fun text(): String = _lines.value.joinToString("\n")

    fun setVisible(value: Boolean) {
        _visible.value = value
    }
}
