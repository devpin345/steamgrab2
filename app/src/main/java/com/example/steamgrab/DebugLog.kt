package com.example.steamgrab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan langkah login dan download, lengkap dengan jam. Disimpan juga ke file,
 * jadi tetap ada walaupun aplikasi crash. Isinya tidak memuat password.
 */
object DebugLog {
    private const val MAX_LINES = 500
    private const val FILE_NAME = "debug.log"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var file: File? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /**
     * Panggil sekali saat aplikasi dibuka. Log sesi sebelumnya dimuat lagi,
     * dan crash yang tidak tertangkap dicatat sebelum aplikasi berhenti.
     */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f

        val previous = runCatching { f.readLines() }.getOrDefault(emptyList())
        _lines.value = previous.takeLast(MAX_LINES)
        if (f.length() > 200_000L) {
            runCatching { f.writeText(previous.takeLast(MAX_LINES).joinToString("\n") + "\n") }
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            log("CRASH di thread ${thread.name}:\n${Log.getStackTraceString(e)}")
            previousHandler?.uncaughtException(thread, e)
        }

        log("---- aplikasi dibuka ----")
    }

    @Synchronized
    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
    }

    fun text(): String = _lines.value.joinToString("\n")

    fun setVisible(value: Boolean) {
        _visible.value = value
    }
}
