package dev.kaff.app.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kaff.app.presentation.desktop.DesktopPreview
import dev.kaff.app.presentation.done.DonePreview
import dev.kaff.app.presentation.home.HomePreview
import dev.kaff.app.presentation.picker.PickerPreview
import dev.kaff.app.presentation.receive.ReceiveOfferPreview
import dev.kaff.app.presentation.send.SendArmedPreview
import dev.kaff.app.presentation.send.SendTransferPreview
import dev.kaff.app.presentation.share.SharePreview
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Google Play screenshots of the real screens, in Arabic, at 1080 × 2160 (Play allows at most 2:1).
 * Camera views are skipped (inspection mode), so no gallery or face ever ends up in a store image.
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*StoreScreenshots*" -Pscreenshots=true
 *
 * Images land in fastlane/metadata/android/screens-raw/; graphics-src/frame.html frames them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w360dp-h720dp-xxhdpi")
class StoreScreenshots {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("kaff.screenshots") != null)

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { content() } }
        // Let entrance animations finish; infinite ones (pulses) are frozen mid-way.
        compose.mainClock.advanceTimeBy(2_500)
        compose.onRoot().captureRoboImage("../fastlane/metadata/android/screens-raw/$name.png")
    }

    @Test fun home() = shoot("1_home") { HomePreview() }
    @Test fun picker() = shoot("2_picker") { PickerPreview() }
    @Test fun armed() = shoot("3_grab") { SendArmedPreview() }
    @Test fun receive() = shoot("4_receive") { ReceiveOfferPreview() }
    @Test fun transfer() = shoot("5_transfer") { SendTransferPreview() }
    @Test fun done() = shoot("6_done") { DonePreview() }
    @Test fun desktop() = shoot("7_desktop") { DesktopPreview() }
    @Test fun share() = shoot("8_share") { SharePreview() }
}
