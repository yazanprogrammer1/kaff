package dev.kaff.app.presentation.common

import dev.kaff.app.core.di.DefaultDispatcher
import dev.kaff.app.data.hand.HandTrackerFactory
import dev.kaff.app.domain.repository.PowerMonitor
import dev.kaff.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/** Builds a [GestureCamera] bound to a ViewModel scope. */
class GestureCameraFactory @Inject constructor(
    private val trackerFactory: HandTrackerFactory,
    private val settings: SettingsRepository,
    private val power: PowerMonitor,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    fun create(scope: CoroutineScope, autoTimeout: Boolean = true): GestureCamera = GestureCamera(
        tracker = trackerFactory.create(),
        scope = scope,
        settings = settings.settings,
        power = power.state,
        analysisDispatcher = dispatcher,
        autoTimeout = autoTimeout,
    )
}
