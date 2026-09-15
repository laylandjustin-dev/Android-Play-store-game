package com.pixeltown.sim

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Gzip for save files.
 *
 * The only part of `:sim` that is not portable Kotlin, and it lives alone in this file for that
 * reason: everything else compiles for the JVM, for Android, *and* for the browser, where
 * `java.util.zip` does not exist. The web build simply leaves this file out.
 *
 * A 60-year run is 981KB of JSON and 141KB gzipped — the file is dominated by two
 * 16,384-element float arrays that cannot be rounded without breaking determinism, so
 * compression is the only lever available.
 */
fun SaveFormat.encodeCompressed(save: SaveGame): ByteArray {
    val bytes = encode(save).toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(bytes.size / 4)
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

fun SaveFormat.decodeCompressed(bytes: ByteArray): SaveGame {
    val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
    return decode(text)
}
