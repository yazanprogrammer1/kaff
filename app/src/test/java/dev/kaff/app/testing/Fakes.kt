package dev.kaff.app.testing

import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.domain.model.ReceivedFile
import dev.kaff.app.domain.model.SaveLocation
import dev.kaff.app.domain.model.SavedFile
import dev.kaff.app.domain.model.TransferRecord
import dev.kaff.app.domain.repository.Clock
import dev.kaff.app.domain.repository.FeedbackPlayer
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.repository.HistoryRepository
import dev.kaff.app.domain.repository.IncomingNotifier
import dev.kaff.app.domain.repository.LocalDevice
import dev.kaff.app.domain.repository.SaveResult
import dev.kaff.app.domain.transfer.FeedbackKind
import dev.kaff.app.domain.transfer.IncomingAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun outgoingFile(name: String, bytes: ByteArray, mime: String = "image/jpeg", thumbnail: ByteArray? = byteArrayOf(1, 2, 3)) =
    OutgoingFile("content://test/$name", FileMeta(name, bytes.size.toLong(), mime, sha256(bytes)), thumbnail)

class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun wallMs(): Long = 1_700_000_000_000L + scheduler.currentTime
    override fun elapsedMs(): Long = scheduler.currentTime
}

class FixedClock(var now: Long = 0L) : Clock {
    override fun wallMs(): Long = 1_700_000_000_000L + now
    override fun elapsedMs(): Long = now
}

class FakeReceivedFile(private val bytes: ByteArray) : ReceivedFile {
    var discarded = false
        private set

    override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    override fun discard() {
        discarded = true
    }
}

/** Verifies size and hash like the real MediaStore repository, without Android. */
class FakeFileRepository(private val failStorage: Boolean = false) : FileRepository {
    val saved = mutableListOf<SavedFile>()

    override suspend fun prepare(item: PickItem): OutgoingFile? = null

    override suspend fun save(file: ReceivedFile, meta: FileMeta): SaveResult {
        if (failStorage) return SaveResult.StorageError
        val bytes = file.openStream().use { it.readBytes() }
        if (bytes.size.toLong() != meta.size || sha256(bytes) != meta.sha256) return SaveResult.IntegrityError
        return SaveResult.Saved(SavedFile("content://saved/${meta.name}", meta, SaveLocation.Gallery)).also { saved += it.file }
    }
}

class FakeHistory : HistoryRepository {
    private val flow = MutableStateFlow<List<TransferRecord>>(emptyList())
    override val records = flow
    override suspend fun add(record: TransferRecord) {
        flow.value = flow.value + record
    }

    override suspend fun clear() {
        flow.value = emptyList()
    }
}

class RecordingFeedback : FeedbackPlayer {
    val played = mutableListOf<FeedbackKind>()
    override fun play(kind: FeedbackKind) {
        played += kind
    }
}

class RecordingNotifier : IncomingNotifier {
    val alerts = mutableListOf<IncomingAlert>()
    var cleared = 0
    override fun alert(alert: IncomingAlert) {
        alerts += alert
    }

    override fun clear() {
        cleared++
    }
}

class FixedDevice(name: String) : LocalDevice {
    override val name: StateFlow<String> = MutableStateFlow(name)
}
