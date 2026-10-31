package com.example.steamgrab

/**
 * Menjelaskan exception dalam teks yang bisa dibaca di layar, termasuk penyebab di dalamnya.
 * Exception tanpa pesan ditulis "(tanpa pesan)" supaya tidak tampil kosong seperti "Login gagal" saja.
 */
fun Throwable.describe(): String {
    val lines = mutableListOf<String>()
    var cur: Throwable? = this
    while (cur != null && lines.size < 6) {
        val msg = cur.message?.takeIf { it.isNotBlank() } ?: "(tanpa pesan)"
        lines += "${cur.javaClass.simpleName}: $msg"
        val next = cur.cause
        cur = if (next === cur) null else next
    }

    // Lokasi kode yang paling dalam melempar error, hanya frame dari library atau aplikasi ini
    val root = generateSequence(this) { it.cause?.takeIf { c -> c !== it } }.last()
    val frame = root.stackTrace.firstOrNull {
        it.className.startsWith("in.dragonbra") || it.className.startsWith("com.example")
    }
    val where = frame?.let { "\nDi ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""

    return lines.joinToString("\n← ") + where
}
