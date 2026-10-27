package dev.kaff.app.data.turbo

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Sending half of the turbo lane: files queued with [send] are read in order, cut into chunks and
 * striped over every socket, so one slow TCP window never limits the radio.
 *
 * Callbacks run on IO threads. Once a socket fails the lane is [broken]: every unfinished file is
 * reported failed and new files must go another way.
 */
internal class TurboSendLane(
    private val sockets: List<Socket>,
    key: ByteArray,
    parent: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun progress(payloadId: Long, bytes: Long)
        suspend fun completed(payloadId: Long)
        suspend fun failed(payloadId: Long)
    }

    private class OutFile(val id: Long, val size: Long, val input: InputStream) {
        val sent = AtomicLong()
        @Volatile var cancelled = false
        val startedAt = System.nanoTime()
    }

    private class Chunk(val file: OutFile, val offset: Long, val buffer: ByteArray, val length: Int)

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
    private val files = ConcurrentHashMap<Long, OutFile>()
    private val queue = Channel<OutFile>(Channel.UNLIMITED)
    private val chunks = Channel<Chunk>(capacity = sockets.size * 2)
    private val buffers = Channel<ByteArray>(capacity = sockets.size * 3)
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()

    /** True once a socket failed or the lane was closed; new files must take another route. */
    val broken: Boolean get() = failed.get() || closed.get()

    init {
        repeat(sockets.size * 3) { buffers.trySend(ByteArray(TurboFrames.CHUNK_BYTES)) }
        scope.launch { produce() }
        sockets.forEach { socket -> scope.launch { write(socket, ChunkCipher(key)) } }
    }

    /** Queues a file; [input] is owned by the lane from now on. */
    fun send(payloadId: Long, size: Long, input: InputStream) {
        val file = OutFile(payloadId, size, input)
        files[payloadId] = file
        if (broken || queue.trySend(file).isFailure) {
            files.remove(payloadId)
            input.closeQuietly()
            scope.launch { listener.failed(payloadId) }
        }
    }

    /** Stops a file. Returns false if the lane does not know it (already finished). */
    fun cancel(payloadId: Long): Boolean {
        val file = files.remove(payloadId) ?: return false
        file.cancelled = true
        return true
    }

    /** Closes every socket without reporting anything; used on disconnect. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.close()
        sockets.forEach { it.closeQuietly() }
        files.values.forEach { it.input.closeQuietly() }
        files.clear()
        scope.cancel()
    }

    private suspend fun produce() {
        for (file in queue) {
            if (file.cancelled) {
                file.input.closeQuietly()
                continue
            }
            try {
                file.input.use { input ->
                    var offset = 0L
                    do {
                        if (file.cancelled || broken) break
                        val buffer = buffers.receive()
                        val length = input.readUpTo(buffer, minOf(TurboFrames.CHUNK_BYTES.toLong(), file.size - offset).toInt())
                        if (length < 0 || (length == 0 && offset < file.size)) throw IOException("File shorter than announced")
                        chunks.send(Chunk(file, offset, buffer, length))
                        offset += length
                    } while (offset < file.size)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Cannot read outgoing file", e)
                if (files.remove(file.id) != null) listener.failed(file.id)
            }
        }
    }

    private suspend fun write(socket: Socket, cipher: ChunkCipher) {
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val header = ByteArray(TurboFrames.HEADER_BYTES)
        val sealed = ByteArray(TurboFrames.CHUNK_BYTES + TurboFrames.TAG_BYTES)
        try {
            for (chunk in chunks) {
                val file = chunk.file
                if (file.cancelled) {
                    buffers.trySend(chunk.buffer)
                    continue
                }
                val h = TurboFrames.Header(file.id, file.size, chunk.offset, chunk.length)
                h.encode(header)
                val sealedLength = cipher.seal(h, header, chunk.buffer, chunk.length, sealed)
                buffers.trySend(chunk.buffer)
                TurboFrames.writeFrame(out, header, sealed, sealedLength)
                // Flush when nothing else is waiting, so the last chunk never sits in the buffer.
                if (chunks.isEmpty) out.flush()
                val sent = file.sent.addAndGet(chunk.length.toLong())
                listener.progress(file.id, sent)
                if (sent == file.size && files.remove(file.id) != null) {
                    out.flush()
                    val seconds = (System.nanoTime() - file.startedAt) / 1e9
                    Log.i(TAG, "Sent ${file.size / 1_048_576} MB in ${"%.1f".format(seconds)} s = ${"%.1f".format(file.size / 1_048_576.0 / seconds)} MB/s")
                    listener.completed(file.id)
                }
            }
        } catch (e: IOException) {
            if (!closed.get()) Log.w(TAG, "Turbo stream failed: ${e.message}")
            breakLane()
        }
    }

    private suspend fun breakLane() {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        queue.close()
        sockets.forEach { it.closeQuietly() }
        val unfinished = files.values.toList()
        files.clear()
        unfinished.forEach {
            it.input.closeQuietly()
            listener.failed(it.id)
        }
        scope.cancel()
    }

    private companion object {
        const val TAG = "TurboLane"
    }
}

/** Fills up to [max] bytes; returns how many were read, or -1 if the stream had already ended. */
internal fun InputStream.readUpTo(buffer: ByteArray, max: Int): Int {
    var total = 0
    while (total < max) {
        val n = read(buffer, total, max - total)
        if (n < 0) return if (total == 0 && max > 0) -1 else total
        total += n
    }
    return total
}

internal fun AutoCloseable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
