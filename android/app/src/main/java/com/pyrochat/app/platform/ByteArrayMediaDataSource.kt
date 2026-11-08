package com.pyrochat.app.platform

import android.media.MediaDataSource

/**
 * In-memory [MediaDataSource] so `MediaExtractor` / `MediaPlayer` can read a decrypted voice note
 * without the plaintext ever touching disk (iOS has to go through a `waveform-*` / `vn-*` temp
 * file; the framework here can seek in RAM directly).
 */
class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= bytes.size) return -1
        val n = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() {}
}
