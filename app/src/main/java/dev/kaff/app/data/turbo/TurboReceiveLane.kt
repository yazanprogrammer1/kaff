package dev.kaff.app.data.turbo

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Receiving half of the turbo lane: every socket is read independently and each authenticated
 * chunk is written at its offset into a temporary file under [tempDir]. A file is complete when all
 * of its bytes arrived; then it is handed over exactly like a finished Nearby FILE payload.
 */
internal class TurboReceiveLane(
    private val sockets: List<Socket>,
    key: ByteArray,
    private val tempDir: File,
    parent: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun progress(payloadId: Long, bytes: Long)
        suspend fun completed(payloadId: Long, file: File)
        suspend fun failed(payloadId: Long)
    }

    private class InFile(val file: File, val size: Long) {
        val channel: FileChannel = RandomAccessFile(file, "rw").channel
        var received = 0L
        var closed = false
    }

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
    private val files = ConcurrentHashMap<Long, InFile>()

    /** Ids stopped by either side; late chunks for them are read and dropped. */
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()

    val broken: Boolean get() = failed.get() || closed.get()

    init {
        sockets.forEach { socket -> scope.launch { read(socket, ChunkCipher(key)) } }
    }

    /** Drops a file and deletes what arrived of it. Returns false if it was already complete. */
    fun cancel(payloadId: Long): Boolean {
        cancelled += payloadId
        val file = files.remove(payloadId) ?: return false
        discard(file)
        return true
    }

    /** Closes every socket and deletes partial files without reporting anything. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        sockets.forEach { it.closeQuietly() }
        files.values.forEach(::discard)
        files.clear()
        scope.cancel()
    }

    private suspend fun read(socket: Socket, cipher: ChunkCipher) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
        val headerBytes = ByteArray(TurboFrames.HEADER_BYTES)
        val sealed = ByteArray(TurboFrames.CHUNK_BYTES + TurboFrames.TAG_BYTES)
        val plain = ByteArray(TurboFrames.CHUNK_BYTES)
        try {
            while (true) {
                val header = TurboFrames.readHeader(input, headerBytes) ?: break
                if (!header.valid()) throw IOException("Invalid turbo frame")
                val sealedLength = header.length + TurboFrames.TAG_BYTES
                input.readFully(sealed, 0, sealedLength)
                val length = cipher.open(header, headerBytes, sealed, sealedLength, plain)
                if (header.payloadId in cancelled) continue
                val file = files.computeIfAbsent(header.payloadId) {
                    InFile(File(tempDir, "turbo-${header.payloadId}.part"), header.fileSize)
                }
                if (file.size != header.fileSize) throw IOException("Turbo file size changed")
                val done = synchronized(file) {
                    if (file.closed) return@synchronized null
                    var position = header.offset
                    val buffer = ByteBuffer.wrap(plain, 0, length)
                    while (buffer.hasRemaining()) position += file.channel.write(buffer, position)
                    file.received += length
                    file.received >= file.size
                } ?: continue
                listener.progress(header.payloadId, file.received)
                if (done && files.remove(header.payloadId, file)) {
                    synchronized(file) {
                        file.closed = true
                        file.channel.close()
                    }
                    listener.completed(header.payloadId, file.file)
                }
            }
        } catch (e: IOException) {
            if (!closed.get()) {
                Log.w(TAG, "Turbo stream failed: ${e.message}")
                breakLane()
            }
        }
    }

    private suspend fun breakLane() {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        sockets.forEach { it.closeQuietly() }
        val unfinished = files.keys.toList()
        unfinished.forEach { id ->
            files.remove(id)?.let(::discard)
            listener.failed(id)
        }
        scope.cancel()
    }

    private fun discard(file: InFile) {
        synchronized(file) {
            file.closed = true
            file.channel.closeQuietly()
        }
        file.file.delete()
    }

    private companion object {
        const val TAG = "TurboLane"
    }
}
