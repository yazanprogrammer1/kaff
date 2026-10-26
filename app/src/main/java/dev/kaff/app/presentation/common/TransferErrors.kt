package dev.kaff.app.presentation.common

import androidx.annotation.StringRes
import dev.kaff.app.R
import dev.kaff.app.domain.model.TransferError

/** Friendly, specific Arabic (or English) copy for every failure. */
@StringRes
fun TransferError.message(): Int = when (this) {
    TransferError.ArmedTimeout -> R.string.error_armed_timeout
    TransferError.Rejected -> R.string.error_rejected
    TransferError.Disconnected -> R.string.error_disconnected
    TransferError.ConnectionFailed -> R.string.error_connection_failed
    TransferError.TransferFailed -> R.string.error_transfer_failed
    TransferError.Integrity -> R.string.error_integrity
    TransferError.SaveFailed -> R.string.error_save_failed
    TransferError.CancelledByPeer -> R.string.error_cancelled_by_peer
    TransferError.OfferExpired -> R.string.error_offer_expired
    TransferError.NearbyUnavailable -> R.string.error_nearby_unavailable
    TransferError.NearbyPermission -> R.string.error_nearby_permission
    TransferError.LocationOff -> R.string.error_location_off
    TransferError.BluetoothOff -> R.string.error_bluetooth_off
    TransferError.FileUnreadable -> R.string.error_file_unreadable
    TransferError.PairingDeclined -> R.string.error_pairing_declined
    TransferError.VersionMismatch -> R.string.error_version
    TransferError.WatchTimeout -> R.string.error_watch_timeout
    TransferError.Protocol -> R.string.error_protocol
}
