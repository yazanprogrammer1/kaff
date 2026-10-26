package dev.kaff.app.domain.repository

import dev.kaff.app.domain.model.PowerState
import kotlinx.coroutines.flow.Flow

interface PowerMonitor {
    val state: Flow<PowerState>
}
