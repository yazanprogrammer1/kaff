package dev.kaff.app.domain.gesture

import dev.kaff.app.domain.model.GestureReading
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.StableGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDebouncerTest {

    private val thresholds = GestureThresholds()
    private val fist = GestureReading(GestureState.Fist, 0.95f, fistScore = 0.95f, openScore = 0.05f)
    private val open = GestureReading(GestureState.OpenPalm, 0.95f, fistScore = 0.05f, openScore = 0.95f)
    private val none = GestureReading(GestureState.None, 0.9f, fistScore = 0.1f, openScore = 0.1f)

    private var clock = 0L
    private fun GestureDebouncer.feed(reading: GestureReading, times: Int = 1): List<StableGesture> =
        List(times) { clock += 33; update(reading, clock) }

    @Test
    fun `confirms after stable frames and reports justConfirmed once`() {
        val debouncer = GestureDebouncer(thresholds)
        val outputs = debouncer.feed(fist, 12)
        val firstConfirmed = outputs.indexOfFirst { it.state == GestureState.Fist }
        assertTrue("confirmed at $firstConfirmed", firstConfirmed >= thresholds.stableFrames - 1)
        assertTrue(outputs[firstConfirmed].justConfirmed)
        assertEquals(1, outputs.count { it.justConfirmed })
        assertTrue(outputs.drop(firstConfirmed).all { it.state == GestureState.Fist })
    }

    @Test
    fun `stableSinceMs is the confirmation timestamp`() {
        val debouncer = GestureDebouncer(thresholds)
        val outputs = debouncer.feed(open, 15)
        val confirmed = outputs.first { it.justConfirmed }
        assertEquals(confirmed.stableSinceMs, outputs.last().stableSinceMs)
    }

    @Test
    fun `alternating flicker never confirms`() {
        val debouncer = GestureDebouncer(thresholds)
        repeat(30) {
            val out = debouncer.feed(if (it % 2 == 0) fist else open).single()
            assertEquals(GestureState.None, out.state)
        }
    }

    @Test
    fun `short bursts below the frame count never confirm`() {
        val debouncer = GestureDebouncer(thresholds)
        repeat(5) {
            debouncer.feed(fist, thresholds.stableFrames - 2).forEach { assertEquals(GestureState.None, it.state) }
            debouncer.feed(none, 6)
        }
    }

    @Test
    fun `single dropped frame does not release a confirmed gesture`() {
        val debouncer = GestureDebouncer(thresholds)
        debouncer.feed(fist, 12)
        repeat(10) {
            assertEquals(GestureState.Fist, debouncer.feed(none).single().state)
            assertEquals(GestureState.Fist, debouncer.feed(fist).single().state)
        }
    }

    @Test
    fun `sustained loss releases the gesture`() {
        val debouncer = GestureDebouncer(thresholds)
        debouncer.feed(fist, 12)
        val outputs = debouncer.feed(none, 15)
        assertEquals(GestureState.None, outputs.last().state)
        assertFalse(outputs.any { it.justConfirmed })
    }

    @Test
    fun `switching gestures confirms the new one after the frame count`() {
        val debouncer = GestureDebouncer(thresholds)
        debouncer.feed(fist, 12)
        val outputs = debouncer.feed(open, 15)
        assertEquals(GestureState.OpenPalm, outputs.last().state)
        assertEquals(1, outputs.count { it.justConfirmed })
    }

    @Test
    fun `weak readings below enter confidence never confirm`() {
        val debouncer = GestureDebouncer(thresholds)
        val weak = GestureReading(GestureState.Fist, 0.6f, fistScore = 0.6f, openScore = 0f)
        debouncer.feed(weak, 40).forEach { assertEquals(GestureState.None, it.state) }
    }

    @Test
    fun `reset clears the state`() {
        val debouncer = GestureDebouncer(thresholds)
        debouncer.feed(fist, 12)
        debouncer.reset()
        assertEquals(StableGesture.NONE, debouncer.state)
        assertEquals(GestureState.None, debouncer.feed(fist).single().state)
    }

    @Test
    fun `engine pipeline confirms a synthetic fist and rejects a claw`() {
        val engine = GestureEngine()
        var t = 0L
        val fistFrames = List(10) { t += 33; engine.process(SyntheticHand.frame(SyntheticHand.FIST, timestampMs = t), t) }
        assertEquals(GestureState.Fist, fistFrames.last().stable.state)

        engine.reset()
        val clawFrames = List(30) { t += 33; engine.process(SyntheticHand.frame(SyntheticHand.CLAW_HAND, timestampMs = t), t) }
        assertTrue(clawFrames.all { it.stable.state == GestureState.None })
    }
}
