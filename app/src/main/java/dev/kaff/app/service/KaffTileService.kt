package dev.kaff.app.service

import android.annotation.SuppressLint
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.kaff.app.R
import dev.kaff.app.core.LaunchTarget

/** Quick Settings tile that opens a Kaff screen and collapses the shade. */
abstract class KaffTileService(private val target: LaunchTarget, private val labelRes: Int) : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(labelRes)
            icon = Icon.createWithResource(this@KaffTileService, R.drawable.ic_stat_kaff)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // The Intent overload is the only one before Android 14.
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(target.pendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(target.intent(this))
        }
    }
}

class SendTileService : KaffTileService(LaunchTarget.Send, R.string.tile_send)

class ReceiveTileService : KaffTileService(LaunchTarget.Receive, R.string.tile_receive)
