package dev.kaff.app.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kaff.app.R
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.transfer.ProtocolMessage
import dev.kaff.app.domain.transfer.ReceiveState
import dev.kaff.app.presentation.home.HomeScreen
import dev.kaff.app.presentation.home.HomeUiState
import dev.kaff.app.presentation.receive.ReceiveActions
import dev.kaff.app.presentation.receive.ReceiveScreen
import dev.kaff.app.presentation.receive.ReceiveUiState
import dev.kaff.app.ui.theme.KaffTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensUiTest {

    @get:Rule val compose = createComposeRule()

    private fun string(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test
    fun homeShowsBothRolesAndIconButtonsHaveLabels() {
        var sends = 0
        var receives = 0
        compose.setContent {
            KaffTheme { HomeScreen(HomeUiState(), { sends++ }, { receives++ }, {}, {}, {}) }
        }
        compose.onNodeWithText(string(R.string.home_tagline)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.action_settings)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.home_history)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_start_send)).performClick()
        compose.onNodeWithText(string(R.string.home_receive)).performClick()
        assertEquals(1, sends)
        assertEquals(1, receives)
    }

    @Test
    fun pairingShowsDigitsAndRequiresExplicitConfirm() {
        var confirmed = 0
        compose.setContent {
            KaffTheme {
                ReceiveScreen(
                    ReceiveUiState(session = ReceiveState.PairingRequest(Peer("A", "Pixel 8"), "4721")),
                    analyzer = { it.close() },
                    actions = ReceiveActions(onConfirmPairing = { confirmed++ }),
                )
            }
        }
        compose.onNodeWithText("4721").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.receive_pair_title, "Pixel 8")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.receive_pair_confirm)).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun offerAlwaysOffersManualAcceptAndReject() {
        var accepted = 0
        var rejected = 0
        val offer = ProtocolMessage.Offer("t", listOf(FileMeta("IMG_2841.jpg", 3_200_000, "image/jpeg", "00")))
        compose.setContent {
            KaffTheme {
                ReceiveScreen(
                    ReceiveUiState(session = ReceiveState.Offered(Peer("A", "Pixel 8"), "4721", offer, true, 0L, 8_000)),
                    analyzer = { it.close() },
                    actions = ReceiveActions(onAccept = { accepted++ }, onReject = { rejected++ }),
                )
            }
        }
        compose.onNodeWithText(string(R.string.receive_offer_image, "Pixel 8")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.receive_verified, "4721")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.receive_accept)).performClick()
        compose.onNodeWithText(string(R.string.receive_reject)).performClick()
        assertEquals(1, accepted)
        assertEquals(1, rejected)
    }
}
