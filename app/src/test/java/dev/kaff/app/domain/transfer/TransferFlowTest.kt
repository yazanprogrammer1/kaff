package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.testing.FakeFileRepository
import dev.kaff.app.testing.FakeHistory
import dev.kaff.app.testing.FakeNearbyNetwork
import dev.kaff.app.testing.FakeNearbyTransport
import dev.kaff.app.testing.FixedDevice
import dev.kaff.app.testing.RecordingFeedback
import dev.kaff.app.testing.RecordingNotifier
import dev.kaff.app.testing.TestClock
import dev.kaff.app.testing.outgoingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two phones, real controllers and reducers, a fake radio in between. */
class TransferFlowTest {

    private class Phones(scope: TestScope, network: FakeNearbyNetwork, storageFails: Boolean = false) {
        val senderLink: FakeNearbyTransport = network.device("A")
        val receiverLink: FakeNearbyTransport = network.device("B")
        val senderHistory = FakeHistory()
        val receiverHistory = FakeHistory()
        val files = FakeFileRepository(failStorage = storageFails)
        val senderFeedback = RecordingFeedback()
        val notifier = RecordingNotifier()
        private val clock = TestClock(scope.testScheduler)

        /** Not a child of the test: its collectors never finish, so it is cancelled explicitly. */
        val childScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + Job())

        val sender = SendController(
            senderLink, senderHistory, senderFeedback, FixedDevice("Pixel 8"), clock, childScope,
            newTransferId = { "t1" },
        )
        val receiver = ReceiveController(
            receiverLink, files, receiverHistory, RecordingFeedback(), notifier, FixedDevice("Galaxy A54"), clock, childScope,
            StandardTestDispatcher(scope.testScheduler),
        )
    }

    private val network = FakeNearbyNetwork()
    private val bytesA = ByteArray(4096) { (it % 251).toByte() }
    private val bytesB = ByteArray(1000) { (it % 13).toByte() }
    private val fileA = outgoingFile("IMG_2841.jpg", bytesA).also { network.register(it, bytesA) }
    private val fileB = outgoingFile("notes.pdf", bytesB, mime = "application/pdf", thumbnail = null).also { network.register(it, bytesB) }

    private fun flowTest(storageFails: Boolean = false, block: suspend TestScope.(Phones) -> Unit) = runTest {
        val phones = Phones(this, network, storageFails)
        try {
            block(phones)
        } finally {
            phones.childScope.cancel()
        }
    }

    /** Runs everything that is ready without letting session timers fire. */
    private fun TestScope.settle() {
        advanceTimeBy(10)
        runCurrent()
    }

    /** Runs both sides up to a paired, ready connection. */
    private fun TestScope.paired(phones: Phones) {
        phones.receiver.dispatch(ReceiveEvent.Start)
        settle()
        phones.sender.dispatch(SendEvent.Start)
        phones.sender.dispatch(SendEvent.FilesChanged(listOf(fileA)))
        settle()
        assertTrue(phones.receiver.state.value is ReceiveState.PairingRequest)
        assertTrue(phones.sender.model.value.state is SendState.AwaitingPairing)
        phones.receiver.dispatch(ReceiveEvent.ConfirmPairing)
        settle()
        assertTrue("${phones.sender.model.value.state}", phones.sender.model.value.state is SendState.Ready)
        assertTrue("${phones.receiver.state.value}", phones.receiver.state.value is ReceiveState.Connected)
    }

    @Test
    fun `fist then palm moves a file end to end and cleans up`() = flowTest { phones ->
        runCurrent()
        paired(phones)

        phones.sender.dispatch(SendEvent.Grab)
        settle()
        val offered = phones.receiver.state.value as ReceiveState.Offered
        assertTrue(offered.armed)
        assertEquals("IMG_2841.jpg", offered.offer.files.single().name)
        assertTrue(offered.offer.thumbnailBytes()!!.contentEquals(fileA.thumbnailJpeg))

        phones.receiver.dispatch(ReceiveEvent.Accept)
        settle()

        val received = phones.receiver.state.value as ReceiveState.Completed
        assertEquals(listOf(fileA.meta), received.saved.map { it.meta })
        assertTrue(phones.sender.model.value.state is SendState.Completed)
        assertEquals(true, phones.senderHistory.records.value.single().success)
        assertEquals(true, phones.receiverHistory.records.value.single().success)
        assertTrue(phones.senderFeedback.played.containsAll(listOf(FeedbackKind.Grab, FeedbackKind.Success)))

        advanceTimeBy(SessionTimeouts().lingerMs + 1)
        settle()
        assertTrue("sender idle", phones.senderLink.isIdle)
        assertTrue("receiver idle", phones.receiverLink.isIdle)
    }

    @Test
    fun `several files arrive in order even when payloads beat their headers`() = runTest {
        network.filesBeforeHeaders = true
        val phones = Phones(this, network)
        runCurrent()
        paired(phones)
        phones.sender.dispatch(SendEvent.FilesChanged(listOf(fileA, fileB)))
        phones.sender.dispatch(SendEvent.Grab)
        settle()
        phones.receiver.dispatch(ReceiveEvent.Accept)
        settle()

        val received = phones.receiver.state.value as ReceiveState.Completed
        assertEquals(listOf(fileA.meta, fileB.meta), received.saved.map { it.meta })
        assertEquals(5096L, received.stats.bytes)
        assertTrue(phones.sender.model.value.state is SendState.Completed)
    }

    @Test
    fun `no palm within the window expires the offer on both sides`() = flowTest { phones ->
        runCurrent()
        paired(phones)
        phones.sender.dispatch(SendEvent.Grab)
        advanceTimeBy(SessionTimeouts().armedWindowMs + 1)
        runCurrent()

        assertEquals(TransferError.ArmedTimeout, (phones.sender.model.value.state as SendState.Ready).notice)
        assertEquals(TransferError.OfferExpired, (phones.receiver.state.value as ReceiveState.Connected).notice)
    }

    @Test
    fun `receiver decline returns the sender to ready`() = flowTest { phones ->
        runCurrent()
        paired(phones)
        phones.sender.dispatch(SendEvent.Grab)
        settle()
        phones.receiver.dispatch(ReceiveEvent.Reject)
        settle()
        assertEquals(TransferError.Rejected, (phones.sender.model.value.state as SendState.Ready).notice)
    }

    @Test
    fun `corrupted bytes are never saved`() = runTest {
        network.corruptFiles = true
        val phones = Phones(this, network)
        runCurrent()
        paired(phones)
        phones.sender.dispatch(SendEvent.Grab)
        settle()
        phones.receiver.dispatch(ReceiveEvent.Accept)
        settle()

        assertEquals(ReceiveState.Failed(TransferError.Integrity), phones.receiver.state.value)
        assertEquals(SendState.Failed(TransferError.Integrity), phones.sender.model.value.state)
        assertTrue(phones.files.saved.isEmpty())
        assertTrue(phones.senderLink.isIdle && phones.receiverLink.isIdle)
    }

    @Test
    fun `storage failure is reported to the sender`() = flowTest(storageFails = true) { phones ->
        runCurrent()
        paired(phones)
        phones.sender.dispatch(SendEvent.Grab)
        settle()
        phones.receiver.dispatch(ReceiveEvent.Accept)
        settle()
        assertEquals(ReceiveState.Failed(TransferError.SaveFailed), phones.receiver.state.value)
        assertEquals(SendState.Failed(TransferError.CancelledByPeer), phones.sender.model.value.state)
    }

    @Test
    fun `declining the pairing never connects`() = flowTest { phones ->
        runCurrent()
        phones.receiver.dispatch(ReceiveEvent.Start)
        settle()
        phones.sender.dispatch(SendEvent.Start)
        settle()
        phones.receiver.dispatch(ReceiveEvent.DeclinePairing)
        settle()

        assertTrue(phones.receiverLink.connected.isEmpty())
        assertEquals(ReceiveState.Advertising(), phones.receiver.state.value)
        assertEquals(TransferError.ConnectionFailed, (phones.sender.model.value.state as SendState.Searching).notice)
    }

    @Test
    fun `radio drop mid offer reconnects and asks for the digits again`() = flowTest { phones ->
        runCurrent()
        paired(phones)
        phones.sender.dispatch(SendEvent.Grab)
        settle()
        phones.senderLink.dropLink()
        settle()

        // Receiver re-advertises, sender re-discovers, and the pairing check is required again.
        assertEquals(ReceiveState.PairingRequest(Peer("A", "Pixel 8"), network.authDigits), phones.receiver.state.value)
        assertTrue(phones.sender.model.value.state is SendState.AwaitingPairing)
        assertTrue(phones.receiverLink.connected.isEmpty())
    }

    @Test
    fun `radio start failure surfaces the specific reason`() = flowTest { phones ->
        phones.senderLink.startError = TransferError.NearbyPermission
        runCurrent()
        phones.sender.dispatch(SendEvent.Start)
        settle()
        assertEquals(SendState.Failed(TransferError.NearbyPermission), phones.sender.model.value.state)
    }

    @Test
    fun `user cancel on the sender stops the radio`() = flowTest { phones ->
        runCurrent()
        paired(phones)
        phones.sender.dispatch(SendEvent.Cancel)
        settle()
        assertEquals(SendModel(), phones.sender.model.value)
        assertTrue(phones.senderLink.isIdle)
    }
}
