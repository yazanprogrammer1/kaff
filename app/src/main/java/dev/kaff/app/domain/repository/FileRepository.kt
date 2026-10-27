package dev.kaff.app.domain.repository

import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.domain.model.ReceivedFile
import dev.kaff.app.domain.model.SavedFile

sealed interface SaveResult {
    data class Saved(val file: SavedFile) : SaveResult
    /** Size or SHA-256 did not match the offer; nothing was kept. */
    data object IntegrityError : SaveResult
    data object StorageError : SaveResult
}

interface FileRepository {
    /** Hashes the item's content and makes a small thumbnail for the offer. */
    suspend fun prepare(item: PickItem): OutgoingFile?

    /** Copies a received payload into shared storage, verifying size and SHA-256 on the way. */
    suspend fun save(file: ReceivedFile, meta: FileMeta): SaveResult
}
