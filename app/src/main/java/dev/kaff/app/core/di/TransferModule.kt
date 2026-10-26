package dev.kaff.app.core.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kaff.app.data.mediastore.MediaStoreFileRepository
import dev.kaff.app.data.nearby.NearbyConnectionsTransport
import dev.kaff.app.data.platform.AndroidFeedbackPlayer
import dev.kaff.app.data.platform.AndroidLocalDevice
import dev.kaff.app.data.platform.SystemClockImpl
import dev.kaff.app.data.room.KaffDatabase
import dev.kaff.app.data.room.RoomHistoryRepository
import dev.kaff.app.data.room.TransferDao
import dev.kaff.app.domain.repository.Clock
import dev.kaff.app.domain.repository.FeedbackPlayer
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.repository.HistoryRepository
import dev.kaff.app.domain.repository.IncomingNotifier
import dev.kaff.app.domain.repository.LocalDevice
import dev.kaff.app.domain.repository.NearbyTransport
import dev.kaff.app.domain.transfer.ReceiveController
import dev.kaff.app.domain.transfer.SendController
import dev.kaff.app.service.notifications.AndroidIncomingNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransferBindings {
    @Binds abstract fun transport(impl: NearbyConnectionsTransport): NearbyTransport
    @Binds abstract fun files(impl: MediaStoreFileRepository): FileRepository
    @Binds abstract fun history(impl: RoomHistoryRepository): HistoryRepository
    @Binds abstract fun clock(impl: SystemClockImpl): Clock
    @Binds abstract fun device(impl: AndroidLocalDevice): LocalDevice
    @Binds abstract fun feedback(impl: AndroidFeedbackPlayer): FeedbackPlayer
    @Binds abstract fun notifier(impl: AndroidIncomingNotifier): IncomingNotifier
}

@Module
@InstallIn(SingletonComponent::class)
object TransferModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): KaffDatabase =
        Room.databaseBuilder(context, KaffDatabase::class.java, "kaff.db").build()

    @Provides
    fun transferDao(db: KaffDatabase): TransferDao = db.transfers()

    @Provides
    @Singleton
    fun sendController(
        transport: NearbyTransport,
        history: HistoryRepository,
        feedback: FeedbackPlayer,
        device: LocalDevice,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): SendController = SendController(transport, history, feedback, device, clock, scope)

    @Provides
    @Singleton
    fun receiveController(
        transport: NearbyTransport,
        files: FileRepository,
        history: HistoryRepository,
        feedback: FeedbackPlayer,
        notifier: IncomingNotifier,
        device: LocalDevice,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): ReceiveController = ReceiveController(transport, files, history, feedback, notifier, device, clock, scope, io)
}
