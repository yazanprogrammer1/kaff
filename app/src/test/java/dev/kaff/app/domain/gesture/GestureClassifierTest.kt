package dev.kaff.app.domain.gesture

import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.Handedness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureClassifierTest {

    private val classifier = GestureClassifier()

    @Test
    fun `null frame is None`() {
        assertEquals(GestureState.None, classifier.classify(null).state)
    }

    @Test
    fun `open right palm facing camera is OpenPalm`() {
        val reading = classifier.classify(SyntheticHand.frame(SyntheticHand.OPEN))
        assertEquals(GestureState.OpenPalm, reading.state)
        assertTrue("confidence ${reading.confidence}", reading.confidence > 0.8f)
        assertTrue(reading.palmFacing)
    }

    @Test
    fun `open left palm facing camera is OpenPalm`() {
        val reading = classifier.classify(SyntheticHand.frame(SyntheticHand.OPEN, handedness = Handedness.Left))
        assertEquals(GestureState.OpenPalm, reading.state)
        assertTrue(reading.palmFacing)
    }

    @Test
    fun `fist is Fist with high confidence`() {
        val reading = classifier.classify(SyntheticHand.frame(SyntheticHand.FIST))
        assertEquals(GestureState.Fist, reading.state)
        assertTrue("confidence ${reading.confidence}", reading.confidence > 0.85f)
    }

    @Test
    fun `classification is rotation invariant`() {
        for (deg in 0 until 360 step 15) {
            val open = classifier.classify(SyntheticHand.frame(SyntheticHand.OPEN, rotationDeg = deg.toFloat(), wristY = 0.5f))
            val fist = classifier.classify(SyntheticHand.frame(SyntheticHand.FIST, rotationDeg = deg.toFloat(), wristY = 0.5f))
            assertEquals("open at $deg", GestureState.OpenPalm, open.state)
            assertEquals("fist at $deg", GestureState.Fist, fist.state)
        }
    }

    @Test
    fun `classification is scale and position invariant`() {
        listOf(0.05f, 0.12f, 0.3f).forEach { scale ->
            listOf(0.3f, 0.5f, 0.7f).forEach { cx ->
                val open = classifier.classify(SyntheticHand.frame(SyntheticHand.OPEN, scale = scale, centerX = cx))
                val fist = classifier.classify(SyntheticHand.frame(SyntheticHand.FIST, scale = scale, centerX = cx))
                assertEquals(GestureState.OpenPalm, open.state)
                assertEquals(GestureState.Fist, fist.state)
            }
        }
    }

    @Test
    fun `non square images are corrected by aspect ratio`() {
        val open = classifier.classify(SyntheticHand.frame(SyntheticHand.OPEN, imageWidth = 480, imageHeight = 640, rotationDeg = 90f, wristY = 0.5f))
        val fist = classifier.classify(SyntheticHand.frame(SyntheticHand.FIST, imageWidth = 640, imageHeight = 480, rotationDeg = 45f, wristY = 0.5f))
        assertEquals(GestureState.OpenPalm, open.state)
        assertEquals(GestureState.Fist, fist.state)
    }

    @Test
    fun `back of hand is not an open palm when palm facing is required`() {
        val reading = classifier.classify(SyntheticHand.frame(SyntheticHand.OPEN, backFacing = true))
        assertEquals(GestureState.None, reading.state)
        assertTrue(!reading.palmFacing)
    }

    @Test
    fun `back of hand counts when palm facing is not required`() {
        val lenient = GestureClassifier(GestureThresholds(requirePalmFacing = false))
        assertEquals(GestureState.OpenPalm, lenient.classify(SyntheticHand.frame(SyntheticHand.OPEN, backFacing = true)).state)
    }

    @Test
    fun `fist does not need the palm to face the camera`() {
        assertEquals(GestureState.Fist, classifier.classify(SyntheticHand.frame(SyntheticHand.FIST, backFacing = true)).state)
    }

    @Test
    fun `pointing and peace signs are neither gesture`() {
        assertEquals(GestureState.None, classifier.classify(SyntheticHand.frame(SyntheticHand.POINTING)).state)
        assertEquals(GestureState.None, classifier.classify(SyntheticHand.frame(SyntheticHand.PEACE)).state)
    }

    @Test
    fun `pinched fingers are not an open palm`() {
        val reading = classifier.classify(SyntheticHand.frame(SyntheticHand.PINCHED))
        assertTrue("open ${reading.openScore}", reading.state != GestureState.OpenPalm)
    }

    @Test
    fun `half curled claw never reaches the confirm threshold`() {
        val thresholds = GestureThresholds()
        val reading = GestureClassifier(thresholds).classify(SyntheticHand.frame(SyntheticHand.CLAW_HAND))
        assertTrue("fist ${reading.fistScore}", reading.fistScore < thresholds.enterConfidence)
        assertTrue("open ${reading.openScore}", reading.openScore < thresholds.enterConfidence)
    }

    @Test
    fun `sensitivity mapping keeps defaults at midpoint and stays valid at extremes`() {
        assertEquals(GestureThresholds(), GestureThresholds.forSensitivity(GestureThresholds.DEFAULT_SENSITIVITY))
        listOf(0f, 1f, -3f, 7f).forEach { s ->
            val t = GestureThresholds.forSensitivity(s)
            val c = GestureClassifier(t)
            assertEquals(GestureState.OpenPalm, c.classify(SyntheticHand.frame(SyntheticHand.OPEN)).state)
            assertEquals(GestureState.Fist, c.classify(SyntheticHand.frame(SyntheticHand.FIST)).state)
        }
        assertTrue(GestureThresholds.forSensitivity(1f).stableFrames < GestureThresholds.forSensitivity(0f).stableFrames)
    }
}
