package dev.kaff.app.presentation

import app.cash.turbine.test
import dev.kaff.app.domain.gesture.SyntheticHand
import dev.kaff.app.domain.model.AppSettings
import dev.kaff.app.domain.model.PowerState
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.transfer.ReceiveController
import dev.kaff.app.domain.transfer.ReceiveEvent
import dev.kaff.app.domain.transfer.ReceiveState
import dev.kaff.app.domain.transfer.SendController
import dev.kaff.app.domain.transfer.SendEvent
import dev.kaff.app.domain.transfer.SendState
import dev.kaff.app.presentation.common.GestureCamera
import dev.kaff.app.presentation.common.GestureCameraFactory
import dev.kaff.app.presentation.receive.LastReceiveHolder
import dev.kaff.app.presentation.receive.ReceiveViewModel
import dev.kaff.app.presentation.send.SendViewModel
import dev.kaff.app.testing.FakeFileRepository
import dev.kaff.app.testing.FakeHandTracker
import dev.kaff.app.testing.FakeHistory
import dev.kaff.app.testing.FakeNearbyNetwork
import dev.kaff.app.testing.FixedDevice
import dev.kaff.app.testing.RecordingFeedback
import dev.kaff.app.testing.RecordingNotifier
import dev.kaff.app.testing.TestClock
import dev.kaff.app.testing.outgoingFile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Send and Receive ViewModels with real controllers on both "phones", fake radio and a fake
 * camera that replays synthetic hands: proves fist -> OFFER and palm hold -> ACCEPT end to end.
 */
class GestureViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val network = FakeNearbyNetwork()
    private val bytes = ByteArray(2048) { it.toByte() }
    private val file = outgoingFile("IMG_1.jpg", bytes).also { network.register(it, bytes) }
    private val senderTracker = FakeHandTracker()
    private val receiverTracker = FakeHandTracker()
    private val controllersScope = CoroutineScope(dispatcher + Job())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        controllersScope.cancel()
        Dispatchers.resetMain()
    }

    private fun cameraFactory(tracker: FakeHandTracker): GestureCameraFactory = mockk {
        every { create(any(), any()) } answers {
            GestureCamera(
                tracker = tracker,
                scope = firstArg(),
                settings = flowOf(AppSettings()),
                power = flowOf(PowerState()),
                analysisDispatcher = dispatcher,
                autoTimeout = secondArg(),
            )
        }
    }

    private inner class World(scope: TestScope) {
        private val clock = TestClock(scope.testScheduler)
        val send = SendController(network.device("A"), FakeHistory(), RecordingFeedback(), FixedDevice("Pixel 8"), clock, controllersScope)
        val receive = ReceiveController(
            network.device("B"), FakeFileRepository(), FakeHistory(), RecordingFeedback(), RecordingNotifier(),
            FixedDevice("Galaxy A54"), clock, controllersScope, dispatcher,
        )
        private val files: FileRepository = mockk { coEvery { prepare(file.uri) } returns file }

        // Each phone has its own process in reality; idle stand-ins replace the other role here.
        val sendVm = SendViewModel(send, mockk(relaxed = true), files, cameraFactory(senderTracker))
        val receiveVm = ReceiveViewModel(receive, mockk(relaxed = true), FixedDevice("Galaxy A54"), LastReceiveHolder(), cameraFactory(receiverTracker))
    }

    private var time = 0L
    private fun TestScope.show(tracker: FakeHandTracker, pose: SyntheticHand.Pose, frames: Int) {
        repeat(frames) {
            time += 33
            tracker.emit(SyntheticHand.frame(pose, timestampMs = time), time)
            advanceTimeBy(33)
            runCurrent()
        }
    }

    private fun TestScope.settle() {
        advanceTimeBy(10)
        runCurrent()
    }

    private fun TestScope.pair(world: World) {
        settle()
        world.sendVm.onFilesPicked(listOf(file.uri))
        settle()
        assertTrue(world.receive.state.value is ReceiveState.PairingRequest)
    }

    @Test
    fun `open palm during pairing never accepts anything`() = runTest(dispatcher) {
        val world = World(this)
        pair(world)
        show(receiverTracker, SyntheticHand.OPEN, 40)
        assertTrue("palm must not bypass the digits", world.receive.state.value is ReceiveState.PairingRequest)
        world.receive.dispatch(ReceiveEvent.Stop)
        world.send.dispatch(SendEvent.Cancel)
        settle()
    }

    @Test
    fun `fist arms the offer and a held palm accepts it`() = runTest(dispatcher) {
        val world = World(this)
        pair(world)
        world.receiveVm.onConfirmPairing()
        settle()
        assertTrue(world.send.model.value.state is SendState.Ready)

        show(senderTracker, SyntheticHand.FIST, 12)
        assertTrue("sender armed", world.send.model.value.state is SendState.Armed)
        assertTrue("receiver offered", world.receive.state.value is ReceiveState.Offered)

        world.receiveVm.state.test {
            show(receiverTracker, SyntheticHand.OPEN, 12)
            var item = expectMostRecentItem()
            assertTrue("hold started: ${item.holdProgress}", item.holdProgress in 0f..0.99f)
            show(receiverTracker, SyntheticHand.OPEN, 24)
            settle()
            item = expectMostRecentItem()
            assertTrue("completed: ${item.session}", item.session is ReceiveState.Completed)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(world.send.model.value.state is SendState.Completed)
    }

    @Test
    fun `a brief palm flash is not enough to accept`() = runTest(dispatcher) {
        val world = World(this)
        pair(world)
        world.receiveVm.onConfirmPairing()
        settle()
        show(senderTracker, SyntheticHand.FIST, 12)
        show(receiverTracker, SyntheticHand.OPEN, 10) // ~330 ms: confirmed palm but held < 700 ms
        show(receiverTracker, SyntheticHand.FIST, 20)
        assertTrue(world.receive.state.value is ReceiveState.Offered)
        assertEquals(0f, world.receiveVm.state.value.holdProgress)
    }

    @Test
    fun `camera sleeps while no offer is waiting`() = runTest(dispatcher) {
        val world = World(this)
        settle()
        assertTrue(receiverTracker.paused)
        assertEquals(false, world.receiveVm.state.value.camera.active)
    }
}
