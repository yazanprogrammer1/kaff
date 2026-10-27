package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.repository.TransportEvent
import dev.kaff.app.testing.FixedClock
import dev.kaff.app.testing.outgoingFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendReducerTest {

    private val clock = FixedClock()
    private val timeouts = SessionTimeouts()
    private val reducer = SendReducer(clock, { "Pixel 8" }, { "t1" }, timeouts)
    private val peer = Peer("B", "Galaxy A54")
    private val fileA = outgoingFile("a.jpg", ByteArray(10) { 1 })
    private val fileB = outgoingFile("b.mp4", ByteArray(20) { 2 }, mime = "video/mp4")

    private fun t(event: TransportEvent) = SendEvent.Transport(event)
    private fun msg(m: ProtocolMessage) = t(TransportEvent.MessageReceived(peer.endpointId, m))

    private fun ready(files: List<dev.kaff.app.domain.model.OutgoingFile> = listOf(fileA)) =
        SendModel(SendState.Ready(peer, "4721"), files)

    private fun armed() = reducer.reduce(ready(), SendEvent.Grab).model
    private fun transferring() = reducer.reduce(armed(), msg(ProtocolMessage.Accept("t1"))).model

    @Test
    fun `start begins discovery`() {
        val r = reducer.reduce(SendModel(), SendEvent.Start)
        assertEquals(SendState.Searching(), r.model.state)
        assertEquals(listOf(SendEffect.StartDiscovery), r.effects)
    }

    @Test
    fun `idle ignores everything but start`() {
        listOf(SendEvent.Grab, SendEvent.Retry, SendEvent.SendAnother, t(TransportEvent.EndpointFound("B", "x"))).forEach {
            assertEquals(SendModel(), reducer.reduce(SendModel(), it).model)
        }
    }

    @Test
    fun `first found endpoint is connected automatically`() {
        val r = reducer.reduce(SendModel(SendState.Searching()), t(TransportEvent.EndpointFound("B", "Galaxy A54")))
        assertEquals(SendState.Connecting(peer, listOf(peer)), r.model.state)
        assertEquals(listOf(SendEffect.RequestConnection("B")), r.effects)
    }

    @Test
    fun `lost endpoints leave the search list`() {
        val r = reducer.reduce(SendModel(SendState.Searching(listOf(peer))), t(TransportEvent.EndpointLost("B")))
        assertEquals(SendState.Searching(), r.model.state)
    }

    @Test
    fun `connecting accepts our own outgoing link and waits for pairing`() {
        val r = reducer.reduce(
            SendModel(SendState.Connecting(peer, listOf(peer))),
            t(TransportEvent.ConnectionInitiated("B", "Galaxy A54", "4721", incoming = false)),
        )
        assertEquals(SendState.AwaitingPairing(peer, "4721"), r.model.state)
        assertEquals(listOf(SendEffect.AcceptConnection("B")), r.effects)
    }

    @Test
    fun `connecting ignores incoming requests from others`() {
        val model = SendModel(SendState.Connecting(peer, listOf(peer)))
        val r = reducer.reduce(model, t(TransportEvent.ConnectionInitiated("C", "x", "1", incoming = true)))
        assertEquals(model, r.model)
    }

    @Test
    fun `user can switch the target while connecting`() {
        val other = Peer("C", "Pixel 7")
        val r = reducer.reduce(SendModel(SendState.Connecting(peer, listOf(peer, other))), SendEvent.SelectPeer("C"))
        assertEquals(SendState.Connecting(other, listOf(peer, other)), r.model.state)
        assertEquals(listOf(SendEffect.Disconnect("B"), SendEffect.RequestConnection("C")), r.effects)
    }

    @Test
    fun `failed connection goes back to searching with a notice`() {
        val r = reducer.reduce(SendModel(SendState.Connecting(peer, listOf(peer))), t(TransportEvent.ConnectionResult("B", false)))
        assertEquals(SendState.Searching(listOf(peer), TransferError.ConnectionFailed), r.model.state)
    }

    @Test
    fun `pairing confirmed by the receiver makes us ready and says hello`() {
        val r = reducer.reduce(SendModel(SendState.AwaitingPairing(peer, "4721")), t(TransportEvent.ConnectionResult("B", true)))
        assertEquals(SendState.Ready(peer, "4721"), r.model.state)
        assertTrue(r.effects.contains(SendEffect.StopDiscovery))
        assertTrue(r.effects.contains(SendEffect.Send("B", ProtocolMessage.Hello(ProtocolCodec.VERSION, "Pixel 8"))))
        assertTrue(r.effects.contains(SendEffect.StartTimer(SendTimer.Idle, timeouts.idleMs)))
    }

    @Test
    fun `pairing declined returns to searching`() {
        val r = reducer.reduce(SendModel(SendState.AwaitingPairing(peer, "4721")), t(TransportEvent.ConnectionResult("B", false)))
        assertEquals(SendState.Searching(notice = TransferError.ConnectionFailed), r.model.state)
    }

    @Test
    fun `grab without files does nothing`() {
        val model = ready(emptyList())
        assertEquals(model, reducer.reduce(model, SendEvent.Grab).model)
    }

    @Test
    fun `grab arms the offer with a countdown`() {
        clock.now = 1_000
        val r = reducer.reduce(ready(listOf(fileA, fileB)), SendEvent.Grab)
        assertEquals(SendState.Armed(peer, "4721", "t1", 1_000, timeouts.armedWindowMs), r.model.state)
        val offer = r.effects.filterIsInstance<SendEffect.Send>().map { it.message }.filterIsInstance<ProtocolMessage.Offer>().single()
        assertEquals(listOf(fileA.meta, fileB.meta), offer.files)
        assertTrue(offer.thumbnail != null)
        assertTrue(r.effects.contains(SendEffect.Send("B", ProtocolMessage.Armed("t1", timeouts.armedWindowMs))))
        assertTrue(r.effects.contains(SendEffect.StartTimer(SendTimer.Armed, timeouts.armedWindowMs)))
        assertTrue(r.effects.contains(SendEffect.Feedback(FeedbackKind.Grab)))
    }

    @Test
    fun `files cannot change once armed`() {
        val model = armed()
        assertEquals(model, reducer.reduce(model, SendEvent.FilesChanged(listOf(fileB))).model)
        val readyModel = ready()
        assertEquals(listOf(fileB), reducer.reduce(readyModel, SendEvent.FilesChanged(listOf(fileB))).model.files)
    }

    @Test
    fun `armed timeout cancels the offer and returns to ready`() {
        val r = reducer.reduce(armed(), SendEvent.TimerFired(SendTimer.Armed))
        assertEquals(SendState.Ready(peer, "4721", TransferError.ArmedTimeout), r.model.state)
        assertTrue(r.effects.contains(SendEffect.Send("B", ProtocolMessage.Cancel("t1", ProtocolMessage.Reason.Timeout))))
    }

    @Test
    fun `accept starts the transfer and queues files`() {
        clock.now = 2_000
        val r = reducer.reduce(armed(), msg(ProtocolMessage.Accept("t1")))
        val state = r.model.state as SendState.Transferring
        assertEquals(10L, state.totalBytes)
        assertEquals(2_000, state.startedAtMs)
        assertTrue(r.effects.contains(SendEffect.CancelTimer(SendTimer.Armed)))
        assertTrue(r.effects.contains(SendEffect.QueueFiles("B", "t1", listOf(fileA))))
    }

    @Test
    fun `accept for another transfer is ignored`() {
        val model = armed()
        assertEquals(model, reducer.reduce(model, msg(ProtocolMessage.Accept("other"))).model)
    }

    @Test
    fun `reject returns to ready with a notice`() {
        val r = reducer.reduce(armed(), msg(ProtocolMessage.Reject("t1", ProtocolMessage.Reason.UserDeclined)))
        assertEquals(SendState.Ready(peer, "4721", TransferError.Rejected), r.model.state)
    }

    @Test
    fun `progress is aggregated across payloads`() {
        var model = reducer.reduce(transferring(), SendEvent.FilesQueued("t1", listOf(100L))).model
        model = reducer.reduce(model, t(TransportEvent.PayloadProgress("B", 100L, 5, outgoing = true))).model
        assertEquals(0.5f, (model.state as SendState.Transferring).progress, 0.001f)
    }

    @Test
    fun `all payloads sent waits for the receipt`() {
        var model = reducer.reduce(transferring(), SendEvent.FilesQueued("t1", listOf(100L))).model
        val r = reducer.reduce(model, t(TransportEvent.PayloadCompleted("B", 100L, outgoing = true, file = null)))
        assertTrue(r.model.state is SendState.AwaitingReceipt)
        assertTrue(r.effects.contains(SendEffect.StartTimer(SendTimer.Receipt, timeouts.receiptMs)))
        model = r.model
        assertTrue(model.state is SendState.AwaitingReceipt)
    }

    @Test
    fun `payload completion before queue ack still finishes`() {
        val model = reducer.reduce(transferring(), t(TransportEvent.PayloadCompleted("B", 100L, outgoing = true, file = null))).model
        assertTrue(model.state is SendState.Transferring)
        assertTrue(reducer.reduce(model, SendEvent.FilesQueued("t1", listOf(100L))).model.state is SendState.AwaitingReceipt)
    }

    @Test
    fun `matching receipt completes, records and disconnects`() {
        clock.now = 0
        val start = transferring()
        clock.now = 500
        val r = reducer.reduce(start, msg(ProtocolMessage.Done("t1", listOf(fileA.meta.sha256))))
        val state = r.model.state as SendState.Completed
        assertEquals(500L, state.stats.durationMs)
        val record = r.effects.filterIsInstance<SendEffect.Record>().single().record
        assertTrue(record.success)
        assertEquals("Galaxy A54", record.peerName)
        assertEquals(SendEffect.StopAll, r.effects.last())
    }

    @Test
    fun `cancel remaining waits for the receivers partial receipt`() {
        val start = SendModel(SendState.Transferring(peer, "4721", "t1", 30, 0, payloadIds = listOf(100, 101)), listOf(fileA, fileB))
        var r = reducer.reduce(start, SendEvent.CancelRemaining)
        assertTrue((r.model.state as SendState.AwaitingReceipt).cancelling)
        assertTrue(r.effects.contains(SendEffect.Send("B", ProtocolMessage.Cancel("t1", ProtocolMessage.Reason.UserCancelled))))

        r = reducer.reduce(r.model, msg(ProtocolMessage.Done("t1", listOf(fileA.meta.sha256), skipped = listOf(1))))
        val done = r.model.state as SendState.Completed
        assertEquals(setOf(1), done.skipped)
        assertEquals(10L, done.stats.bytes)
        assertEquals(1, r.effects.filterIsInstance<SendEffect.Record>().single().record.fileCount)
    }

    @Test
    fun `nothing delivered after cancel returns to ready`() {
        val waiting = SendModel(SendState.AwaitingReceipt(peer, "t1", 30, 0, cancelling = true, authDigits = "4721"), listOf(fileA, fileB))
        val r = reducer.reduce(waiting, msg(ProtocolMessage.Done("t1", emptyList(), skipped = listOf(0, 1))))
        assertEquals(SendState.Ready(peer, "4721"), r.model.state)
    }

    @Test
    fun `mismatching receipt is an integrity failure`() {
        val r = reducer.reduce(transferring(), msg(ProtocolMessage.Done("t1", listOf("bad"))))
        assertEquals(SendState.Failed(TransferError.Integrity), r.model.state)
        assertTrue(r.effects.contains(SendEffect.StopAll))
    }

    @Test
    fun `disconnect mid transfer fails and stops everything`() {
        val r = reducer.reduce(transferring(), t(TransportEvent.Disconnected("B")))
        assertEquals(SendState.Failed(TransferError.Disconnected), r.model.state)
        assertTrue(r.effects.filterIsInstance<SendEffect.Record>().single().record.success.not())
        assertTrue(r.effects.contains(SendEffect.StopAll))
    }

    @Test
    fun `receiver cancel for integrity is reported as integrity`() {
        val r = reducer.reduce(transferring(), msg(ProtocolMessage.Cancel("t1", ProtocolMessage.Reason.Integrity)))
        assertEquals(SendState.Failed(TransferError.Integrity), r.model.state)
    }

    @Test
    fun `queue failure is an unreadable file`() {
        val r = reducer.reduce(transferring(), SendEvent.QueueFailed("t1"))
        assertEquals(SendState.Failed(TransferError.FileUnreadable), r.model.state)
    }

    @Test
    fun `receipt timeout fails the transfer`() {
        val waiting = SendModel(SendState.AwaitingReceipt(peer, "t1", 10, 0), listOf(fileA))
        assertEquals(SendState.Failed(TransferError.TransferFailed), reducer.reduce(waiting, SendEvent.TimerFired(SendTimer.Receipt)).model.state)
    }

    @Test
    fun `idle connection is dropped`() {
        val r = reducer.reduce(ready(), SendEvent.TimerFired(SendTimer.Idle))
        assertEquals(SendState.Failed(TransferError.Disconnected), r.model.state)
        assertEquals(listOf(SendEffect.StopAll), r.effects)
    }

    @Test
    fun `disconnect while ready searches again`() {
        val r = reducer.reduce(ready(), t(TransportEvent.Disconnected("B")))
        assertEquals(SendState.Searching(notice = TransferError.Disconnected), r.model.state)
        assertTrue(r.effects.contains(SendEffect.StartDiscovery))
    }

    @Test
    fun `incompatible peer version fails`() {
        val r = reducer.reduce(ready(), t(TransportEvent.BadMessage("B", unsupportedVersion = true)))
        assertEquals(SendState.Failed(TransferError.VersionMismatch), r.model.state)
    }

    @Test
    fun `cancel always tears down`() {
        listOf(SendModel(SendState.Searching()), ready(), armed(), transferring()).forEach { model ->
            val r = reducer.reduce(model, SendEvent.Cancel)
            assertEquals(SendModel(), r.model)
            assertEquals(SendEffect.StopAll, r.effects.last())
        }
        val fromArmed = reducer.reduce(armed(), SendEvent.Cancel)
        assertTrue(fromArmed.effects.contains(SendEffect.Send("B", ProtocolMessage.Cancel("t1", ProtocolMessage.Reason.UserCancelled))))
    }

    @Test
    fun `retry and send another restart discovery`() {
        assertEquals(SendState.Searching(), reducer.reduce(SendModel(SendState.Failed(TransferError.Rejected)), SendEvent.Retry).model.state)
        val done = SendModel(SendState.Completed(peer, dev.kaff.app.domain.model.TransferStats(10, 5)), listOf(fileA))
        val r = reducer.reduce(done, SendEvent.SendAnother)
        assertEquals(SendModel(SendState.Searching()), r.model)
        assertEquals(listOf(SendEffect.StartDiscovery), r.effects)
    }

    @Test
    fun `radio failure keeps its specific reason`() {
        val r = reducer.reduce(SendModel(SendState.Searching()), SendEvent.RadioFailed(TransferError.LocationOff))
        assertEquals(SendState.Failed(TransferError.LocationOff), r.model.state)
        assertEquals(listOf(SendEffect.StopAll), r.effects)
    }
}
