package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.SaveLocation
import dev.kaff.app.domain.model.SavedFile
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.repository.TransportEvent
import dev.kaff.app.testing.FakeReceivedFile
import dev.kaff.app.testing.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveReducerTest {

    private val clock = FixedClock()
    private val timeouts = SessionTimeouts()
    private val reducer = ReceiveReducer(clock, { "Galaxy A54" }, timeouts)
    private val peer = Peer("A", "Pixel 8")
    private val metaA = FileMeta("a.jpg", 10, "image/jpeg", "aa")
    private val metaB = FileMeta("b.pdf", 20, "application/pdf", "bb")
    private val offer = ProtocolMessage.Offer("t1", listOf(metaA, metaB), null)

    private fun t(event: TransportEvent) = ReceiveEvent.Transport(event)
    private fun msg(m: ProtocolMessage) = t(TransportEvent.MessageReceived(peer.endpointId, m))
    private val connected = ReceiveState.Connected(peer, "4721")
    private fun offered() = reducer.reduce(connected, msg(offer)).model as ReceiveState.Offered
    private fun receiving() = reducer.reduce(offered(), ReceiveEvent.Accept).model as ReceiveState.Receiving
    private fun saved(meta: FileMeta) = SavedFile("content://x/${meta.name}", meta, SaveLocation.Gallery)

    @Test
    fun `start advertises with the local name`() {
        val r = reducer.reduce(ReceiveState.Idle, ReceiveEvent.Start)
        assertEquals(ReceiveState.Advertising(), r.model)
        assertEquals(listOf(ReceiveEffect.StartAdvertising("Galaxy A54")), r.effects)
    }

    @Test
    fun `incoming connection asks the user to confirm digits and alerts`() {
        val r = reducer.reduce(ReceiveState.Advertising(), t(TransportEvent.ConnectionInitiated("A", "Pixel 8", "4721", incoming = true)))
        assertEquals(ReceiveState.PairingRequest(peer, "4721"), r.model)
        assertTrue(r.effects.contains(ReceiveEffect.Alert(IncomingAlert.Pairing("Pixel 8"))))
        assertTrue(r.effects.none { it is ReceiveEffect.AcceptConnection })
    }

    @Test
    fun `outgoing initiations are not ours`() {
        val r = reducer.reduce(ReceiveState.Advertising(), t(TransportEvent.ConnectionInitiated("A", "Pixel 8", "4721", incoming = false)))
        assertEquals(ReceiveState.Advertising(), r.model)
    }

    @Test
    fun `nothing is accepted until the user confirms`() {
        val pairing = ReceiveState.PairingRequest(peer, "4721")
        val r = reducer.reduce(pairing, ReceiveEvent.ConfirmPairing)
        assertEquals(pairing.copy(confirmed = true), r.model)
        assertEquals(listOf(ReceiveEffect.AcceptConnection("A")), r.effects)
        assertEquals(emptyList<ReceiveEffect>(), reducer.reduce(r.model, ReceiveEvent.ConfirmPairing).effects)
    }

    @Test
    fun `palm or accept during pairing does nothing`() {
        val pairing = ReceiveState.PairingRequest(peer, "4721")
        val r = reducer.reduce(pairing, ReceiveEvent.Accept)
        assertEquals(pairing, r.model)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `declining pairing rejects the link`() {
        val r = reducer.reduce(ReceiveState.PairingRequest(peer, "4721"), ReceiveEvent.DeclinePairing)
        assertEquals(ReceiveState.Advertising(), r.model)
        assertTrue(r.effects.contains(ReceiveEffect.RejectConnection("A")))
    }

    @Test
    fun `a second sender is refused while pairing`() {
        val pairing = ReceiveState.PairingRequest(peer, "4721")
        val r = reducer.reduce(pairing, t(TransportEvent.ConnectionInitiated("C", "Other", "1111", incoming = true)))
        assertEquals(pairing, r.model)
        assertEquals(listOf(ReceiveEffect.RejectConnection("C")), r.effects)
    }

    @Test
    fun `connection result connects, stops advertising and says hello`() {
        val r = reducer.reduce(ReceiveState.PairingRequest(peer, "4721", confirmed = true), t(TransportEvent.ConnectionResult("A", true)))
        assertEquals(connected, r.model)
        assertTrue(r.effects.contains(ReceiveEffect.StopAdvertising))
        assertTrue(r.effects.contains(ReceiveEffect.Send("A", ProtocolMessage.Hello(ProtocolCodec.VERSION, "Galaxy A54"))))
    }

    @Test
    fun `offer starts the watch timer and alerts`() {
        clock.now = 300
        val r = reducer.reduce(connected, msg(offer))
        assertEquals(ReceiveState.Offered(peer, "4721", offer, armed = false, offeredAtMs = 300, watchMs = timeouts.watchMs), r.model)
        assertTrue(r.effects.contains(ReceiveEffect.StartTimer(ReceiveTimer.Watch, timeouts.watchMs)))
        assertTrue(r.effects.contains(ReceiveEffect.Alert(IncomingAlert.Offer("Pixel 8", offer))))
    }

    @Test
    fun `armed marks the offer live`() {
        assertTrue((reducer.reduce(offered(), msg(ProtocolMessage.Armed("t1", 5000))).model as ReceiveState.Offered).armed)
    }

    @Test
    fun `accept sends ACCEPT and starts receiving`() {
        clock.now = 1_000
        val r = reducer.reduce(offered(), ReceiveEvent.Accept)
        assertEquals(1_000, (r.model as ReceiveState.Receiving).startedAtMs)
        assertTrue(r.effects.contains(ReceiveEffect.Send("A", ProtocolMessage.Accept("t1"))))
        assertTrue(r.effects.contains(ReceiveEffect.CancelTimer(ReceiveTimer.Watch)))
        assertTrue(r.effects.contains(ReceiveEffect.Feedback(FeedbackKind.Accept)))
    }

    @Test
    fun `reject and watch timeout go back to connected`() {
        val rejected = reducer.reduce(offered(), ReceiveEvent.Reject)
        assertEquals(connected, rejected.model)
        assertTrue(rejected.effects.contains(ReceiveEffect.Send("A", ProtocolMessage.Reject("t1", ProtocolMessage.Reason.UserDeclined))))

        val timedOut = reducer.reduce(offered(), ReceiveEvent.TimerFired(ReceiveTimer.Watch))
        assertEquals(connected.copy(notice = TransferError.WatchTimeout), timedOut.model)
        assertTrue(timedOut.effects.contains(ReceiveEffect.Send("A", ProtocolMessage.Reject("t1", ProtocolMessage.Reason.Timeout))))
    }

    @Test
    fun `sender cancel expires the offer`() {
        val r = reducer.reduce(offered(), msg(ProtocolMessage.Cancel("t1", ProtocolMessage.Reason.Timeout)))
        assertEquals(connected.copy(notice = TransferError.OfferExpired), r.model)
    }

    @Test
    fun `new offer replaces the old one`() {
        val second = offer.copy(transferId = "t2")
        assertEquals(second, (reducer.reduce(offered(), msg(second)).model as ReceiveState.Offered).offer)
    }

    @Test
    fun `file with header is saved immediately`() {
        var state: ReceiveState = receiving()
        state = reducer.reduce(state, msg(ProtocolMessage.FileHeader("t1", 0, 7L))).model
        val file = FakeReceivedFile(ByteArray(10))
        val r = reducer.reduce(state, t(TransportEvent.PayloadCompleted("A", 7L, outgoing = false, file = file)))
        assertEquals(listOf(ReceiveEffect.SaveFile("t1", 0, metaA, file)), r.effects)
    }

    @Test
    fun `file before its header waits, then saves`() {
        val file = FakeReceivedFile(ByteArray(20))
        var r = reducer.reduce(receiving(), t(TransportEvent.PayloadCompleted("A", 9L, outgoing = false, file = file)))
        assertTrue(r.effects.isEmpty())
        r = reducer.reduce(r.model, msg(ProtocolMessage.FileHeader("t1", 1, 9L)))
        assertEquals(listOf(ReceiveEffect.SaveFile("t1", 1, metaB, file)), r.effects)
        assertTrue((r.model as ReceiveState.Receiving).pending.isEmpty())
    }

    @Test
    fun `headers with a bad index are ignored`() {
        val state = receiving()
        assertEquals(state, reducer.reduce(state, msg(ProtocolMessage.FileHeader("t1", 5, 1L))).model)
    }

    @Test
    fun `progress sums incoming payloads`() {
        var state: ReceiveState = receiving()
        state = reducer.reduce(state, t(TransportEvent.PayloadProgress("A", 1L, 15, outgoing = false))).model
        assertEquals(0.5f, (state as ReceiveState.Receiving).progress, 0.001f)
    }

    @Test
    fun `all files saved completes with a DONE receipt in offer order`() {
        clock.now = 0
        var state: ReceiveState = receiving()
        clock.now = 400
        state = reducer.reduce(state, ReceiveEvent.FileSaved("t1", 1, saved(metaB))).model
        val r = reducer.reduce(state, ReceiveEvent.FileSaved("t1", 0, saved(metaA)))
        val done = r.model as ReceiveState.Completed
        assertEquals(listOf(saved(metaA), saved(metaB)), done.saved)
        assertEquals(400L, done.stats.durationMs)
        assertTrue(r.effects.contains(ReceiveEffect.Send("A", ProtocolMessage.Done("t1", listOf("aa", "bb")))))
        assertTrue(r.effects.contains(ReceiveEffect.StartTimer(ReceiveTimer.Linger, timeouts.lingerMs)))
        assertTrue(r.effects.filterIsInstance<ReceiveEffect.Record>().single().record.success)
    }

    @Test
    fun `linger disconnects after completion`() {
        val done = ReceiveState.Completed(peer, offer, emptyList(), dev.kaff.app.domain.model.TransferStats(30, 10))
        assertEquals(listOf(ReceiveEffect.StopAll), reducer.reduce(done, ReceiveEvent.TimerFired(ReceiveTimer.Linger)).effects)
    }

    @Test
    fun `integrity failure tells the sender and discards`() {
        val pendingFile = FakeReceivedFile(ByteArray(1))
        var state: ReceiveState = receiving()
        state = reducer.reduce(state, t(TransportEvent.PayloadCompleted("A", 9L, outgoing = false, file = pendingFile))).model
        val r = reducer.reduce(state, ReceiveEvent.SaveFailed("t1", 0, integrity = true))
        assertEquals(ReceiveState.Failed(TransferError.Integrity), r.model)
        assertTrue(r.effects.contains(ReceiveEffect.Send("A", ProtocolMessage.Cancel("t1", ProtocolMessage.Reason.Integrity))))
        assertTrue(r.effects.contains(ReceiveEffect.Discard(listOf(pendingFile))))
        assertEquals(ReceiveEffect.StopAll, r.effects.last())
    }

    @Test
    fun `storage failure is reported as save failed`() {
        assertEquals(ReceiveState.Failed(TransferError.SaveFailed), reducer.reduce(receiving(), ReceiveEvent.SaveFailed("t1", 0, integrity = false)).model)
    }

    @Test
    fun `disconnect while receiving fails`() {
        assertEquals(ReceiveState.Failed(TransferError.Disconnected), reducer.reduce(receiving(), t(TransportEvent.Disconnected("A"))).model)
    }

    @Test
    fun `disconnect while connected keeps advertising`() {
        val r = reducer.reduce(connected, t(TransportEvent.Disconnected("A")))
        assertEquals(ReceiveState.Advertising(TransferError.Disconnected), r.model)
        assertTrue(r.effects.contains(ReceiveEffect.StartAdvertising("Galaxy A54")))
    }

    @Test
    fun `idle link is dropped but the receiver stays visible`() {
        val r = reducer.reduce(connected, ReceiveEvent.TimerFired(ReceiveTimer.Idle))
        assertEquals(ReceiveState.Advertising(), r.model)
        assertEquals(listOf(ReceiveEffect.StopAll, ReceiveEffect.StartAdvertising("Galaxy A54")), r.effects)
    }

    @Test
    fun `stop tears down from any state`() {
        listOf(ReceiveState.Advertising(), ReceiveState.PairingRequest(peer, "1"), connected, offered(), receiving()).forEach {
            val r = reducer.reduce(it, ReceiveEvent.Stop)
            assertEquals(ReceiveState.Idle, r.model)
            assertEquals(ReceiveEffect.StopAll, r.effects.last())
        }
        assertTrue(reducer.reduce(ReceiveState.Idle, ReceiveEvent.Stop).effects.isEmpty())
    }

    @Test
    fun `receive another restarts advertising`() {
        val done = ReceiveState.Completed(peer, offer, emptyList(), dev.kaff.app.domain.model.TransferStats(30, 10))
        assertEquals(ReceiveState.Advertising(), reducer.reduce(done, ReceiveEvent.Start).model)
        assertEquals(ReceiveState.Advertising(), reducer.reduce(ReceiveState.Failed(TransferError.Rejected), ReceiveEvent.Start).model)
    }
}
